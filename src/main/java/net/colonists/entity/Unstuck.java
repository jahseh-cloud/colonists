package net.colonists.entity;

import net.colonists.faction.City;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * Gets colonists out of the holes, pits and caves they wander into. Every second it asks: is this colonist trapped (in a
 * pit it cannot jump out of, deep underground with no business there, or a miner who has not moved for half a minute)?
 * After a few seconds trapped, it gets out: in front of a player it digs up through the roof and pillars out like a player
 * would; unwatched, it simply makes its way back to safe ground.
 */
final class Unstuck {
	private Unstuck() {}

	/** Called every 20 ticks. */
	static void check(ColonistEntity c, ServerWorld world) {
		if (c.hasVehicle() || c.isSleeping() || c.isBaby() && c.hasVehicle()) return;
		Vec3d here = c.getPos();
		if (c.unstuckLast != null && c.unstuckLast.squaredDistanceTo(here) < 0.25) c.stillTicks += 20;
		else c.stillTicks = 0;
		c.unstuckLast = here;
		boolean trapped = trapped(c, world);
		c.trappedTicks = trapped ? c.trappedTicks + 20 : 0;
		if (c.trappedTicks < 100) {
			c.climbSteps = 0;
			return;
		}
		boolean watched = world.getClosestPlayer(c, 32) != null;
		if (watched && c.climbSteps < 16 && climb(c, world)) {
			c.climbSteps++;
			return;
		}
		BlockPos safe = safeGround(world, c.getBlockPos(), 12);
		if (safe == null && c.home != null) safe = c.home;
		if (safe == null && c.settlement() != null && c.settlement().cityCenter != null) safe = c.settlement().cityCenter;
		if (safe != null) {
			int y = City.surfaceY(world, safe.getX(), safe.getZ());
			c.getNavigation().stop();
			c.refreshPositionAndAngles(safe.getX() + 0.5, y, safe.getZ() + 0.5, c.getYaw(), c.getPitch());
			c.fallDistance = 0;
		}
		c.trappedTicks = 0;
		c.stillTicks = 0;
		c.climbSteps = 0;
	}

	static boolean miner(ColonistEntity c) {
		String a = c.activity.toLowerCase();
		return a.contains("mine") || a.contains("quarry");
	}

	/** In a pit, lost underground, or (for miners) not having moved for half a minute. */
	static boolean trapped(ColonistEntity c, ServerWorld world) {
		if (c.isTouchingWater() || c.isInLava() || c.isClimbing()) return false; // swimming is the navigator's business
		BlockPos feet = c.getBlockPos();
		if (miner(c)) return c.stillTicks >= 600;
		int surface = City.surfaceY(world, feet.getX(), feet.getZ());
		// Deep below the open sky with no reason to be there.
		if (feet.getY() < surface - 3 && !world.isSkyVisible(feet.up()) && !indoors(world, feet, surface)) return true;
		// A pit: walls at head height on every side, so no jump gets out.
		int walls = 0;
		for (Direction d : Direction.Type.HORIZONTAL) {
			BlockPos side = feet.offset(d);
			if (solid(world, side.up()) && (solid(world, side) || solid(world, side.up(2)))) walls++;
		}
		return walls == 4 && c.stillTicks >= 60;
	}

	/** Under a built roof (any block that isn't natural ground) rather than buried: a house, not a cave. */
	static boolean indoors(ServerWorld world, BlockPos feet, int surface) {
		for (int y = feet.getY() + 2; y < surface; y++) {
			BlockState s = world.getBlockState(feet.withY(y));
			if (!s.isAir() && !City.isNatural(s)) return true;
		}
		return false;
	}

	static boolean solid(ServerWorld world, BlockPos p) {
		BlockState s = world.getBlockState(p);
		return s.isSolidBlock(world, p) || !s.getCollisionShape(world, p).isEmpty() && !s.isReplaceable();
	}

	/** One step of getting out by hand: dig out the roof, or step up onto a block placed underfoot. */
	static boolean climb(ColonistEntity c, ServerWorld world) {
		BlockPos feet = c.getBlockPos();
		if (!trapped(c, world)) return false;
		BlockPos head2 = feet.up(2);
		BlockState roof = world.getBlockState(head2);
		if (!roof.isAir() && !roof.isReplaceable()) {
			// Only natural ground: never a house wall or a floor someone built.
			if (!City.isNatural(roof) || roof.getHardness(world, head2) < 0 || roof.isOf(Blocks.OBSIDIAN)) return false;
			world.breakBlock(head2, true, c);
			c.swingHand(Hand.MAIN_HAND);
			return true;
		}
		BlockState under = world.getBlockState(feet);
		if (!under.isAir() && !under.isReplaceable()) return false;
		c.getNavigation().stop();
		c.refreshPositionAndAngles(c.getX(), feet.getY() + 1, c.getZ(), c.getYaw(), c.getPitch());
		world.setBlockState(feet, Blocks.DIRT.getDefaultState());
		c.swingHand(Hand.MAIN_HAND);
		c.fallDistance = 0;
		return true;
	}

	/** The nearest open spot on the surface: solid ground, room to stand, no water or lava, out in the open. */
	@Nullable
	static BlockPos safeGround(ServerWorld world, BlockPos from, int radius) {
		for (int r = 1; r <= radius; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					int x = from.getX() + dx, z = from.getZ() + dz;
					if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
					int y = City.surfaceY(world, x, z);
					BlockPos p = new BlockPos(x, y, z);
					BlockState ground = world.getBlockState(p.down());
					if (!ground.getFluidState().isEmpty() || !ground.isSolidBlock(world, p.down())) continue;
					if (!world.getBlockState(p).isAir() || !world.getBlockState(p.up()).isAir()) continue;
					if (Math.abs(y - from.getY()) > 24) continue;
					return p;
				}
			}
		}
		return null;
	}

	/** True if any player could see this happen. */
	static boolean watched(ServerWorld world, ColonistEntity c) {
		for (PlayerEntity p : world.getPlayers()) if (p.squaredDistanceTo(c) < 32 * 32) return true;
		return false;
	}
}
