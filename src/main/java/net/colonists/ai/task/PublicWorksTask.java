package net.colonists.ai.task;

import net.colonists.entity.ColonistBrain;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.City;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.minecraft.block.BannerBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** City builders' work: the central plaza (well, bell, banner), the street network, and street lamps. */
public class PublicWorksTask extends Task {
	enum Kind { CLEAR, FILL, PAVE, WATER, RIM, BELL, BANNER, LAMP, WALL }

	record Job(BlockPos pos, Kind kind) {}

	private Job job;
	private int cooldown;
	private int jobsDone;

	public PublicWorksTask(ColonistEntity colonist) {
		super(colonist);
	}

	@Override
	public String describe() {
		if (this.job != null && this.job.kind == Kind.PAVE) return "Laying roads";
		if (this.job != null && this.job.kind == Kind.WALL) return "Building the city wall";
		return "Working on public works";
	}

	@Override
	public ColonistEntity.ToolKind tool() {
		return ColonistEntity.ToolKind.PICKAXE;
	}

	@Override
	public int maxTicks() {
		return 3600;
	}

	@Nullable
	private static Faction faction(ColonistEntity c) {
		FactionManager fm = c.factions();
		return fm == null ? null : fm.get(c.getFactionId());
	}

	public static boolean hasWork(ColonistEntity c) {
		Faction f = faction(c);
		return f != null && f.cityCenter != null && !c.onSearchCooldown("WORKS") && findJob(c, f) != null;
	}

	@Nullable
	private static Job findJob(ColonistEntity c, Faction f) {
		ServerWorld world = (ServerWorld) c.getWorld();
		BlockPos center = f.cityCenter;
		if (center == null) return null;
		List<Job> plaza = new ArrayList<>();
		int cy = center.getY();
		for (int dx = -City.PLAZA_HALF; dx <= City.PLAZA_HALF; dx++) {
			for (int dz = -City.PLAZA_HALF; dz <= City.PLAZA_HALF; dz++) {
				BlockPos ground = new BlockPos(center.getX() + dx, cy - 1, center.getZ() + dz);
				for (int y = 3; y >= 0; y--) {
					BlockPos p = ground.up(1 + y);
					BlockState s = world.getBlockState(p);
					if (!s.isAir() && City.isNatural(s)) plaza.add(new Job(p, Kind.CLEAR));
				}
				if (dx == 0 && dz == 0) {
					if (!world.getBlockState(ground).isOf(Blocks.WATER)) plaza.add(new Job(ground, Kind.WATER));
					continue;
				}
				BlockState g = world.getBlockState(ground);
				// Things standing on the plaza (bell, banner, corner lamps) keep a full block underneath.
				boolean support = (Math.abs(dx) == City.PLAZA_HALF && Math.abs(dz) == City.PLAZA_HALF)
						|| (dx == 3 && dz == -3) || (dx == -3 && dz == -3);
				if (!g.isSolidBlock(world, ground) && !City.isPavement(g)) plaza.add(new Job(ground, Kind.FILL));
				else if (!support && !City.isPavement(g) && City.isNatural(g)) plaza.add(new Job(ground, Kind.PAVE));
				if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && world.getBlockState(ground.up()).isAir()
						&& ColonistBrain.count(c.getInventory(), ColonistBrain.STONE_MATS) > 0) {
					plaza.add(new Job(ground.up(), Kind.RIM));
				}
			}
		}
		BlockPos bell = City.bellPos(center);
		if (world.getBlockState(bell).isAir()) plaza.add(new Job(bell, Kind.BELL));
		BlockPos banner = City.bannerPos(center);
		if (world.getBlockState(banner).isAir()) plaza.add(new Job(banner, Kind.BANNER));
		if (ColonistBrain.count(c.getInventory(), Items.TORCH) > 0) {
			for (int sx = -1; sx <= 1; sx += 2) {
				for (int sz = -1; sz <= 1; sz += 2) {
					BlockPos lamp = center.add(sx * City.PLAZA_HALF, 0, sz * City.PLAZA_HALF);
					if (world.getBlockState(lamp).isAir()) plaza.add(new Job(lamp, Kind.LAMP));
				}
			}
		}
		Job best = nearest(c, plaza);
		if (best != null) return best;

		// Roads.
		List<Job> roads = new ArrayList<>();
		for (BlockPos col : City.roadColumns(f)) {
			if (!world.isChunkLoaded(col.getX() >> 4, col.getZ() >> 4) || c.isBlacklisted(col)) continue;
			if (col.getSquaredDistance(c.getBlockPos().withY(0)) > 48 * 48) continue;
			int top = City.surfaceY(world, col.getX(), col.getZ());
			BlockPos ground = new BlockPos(col.getX(), top - 1, col.getZ());
			BlockState g = world.getBlockState(ground);
			BlockState above = world.getBlockState(ground.up());
			if (!above.isAir() && above.isReplaceable() && above.getFluidState().isEmpty()) roads.add(new Job(ground.up(), Kind.CLEAR));
			else if (g.isIn(BlockTags.LOGS) || g.isIn(BlockTags.LEAVES)) roads.add(new Job(ground, Kind.CLEAR)); // a tree in the road
			else if (City.isWallBlock(g) && f.wallPlanned && (col.getX() == f.wallMinX || col.getX() == f.wallMaxX
					|| col.getZ() == f.wallMinZ || col.getZ() == f.wallMaxZ)) roads.add(new Job(ground, Kind.CLEAR)); // open a gate for a new street
			else if (!g.getFluidState().isEmpty()) {
				if (ColonistBrain.count(c.getInventory(), ColonistBrain.PLANKS) > 0) roads.add(new Job(ground, Kind.FILL));
			} else if (g.isOf(Blocks.GRASS_BLOCK) || g.isOf(Blocks.DIRT) || g.isOf(Blocks.COARSE_DIRT) || g.isOf(Blocks.PODZOL)
					|| g.isOf(Blocks.MYCELIUM) || g.isOf(Blocks.ROOTED_DIRT)) {
				roads.add(new Job(ground, Kind.PAVE));
			}
		}
		best = nearest(c, roads);
		if (best != null) return best;

		// The city wall, once the city is big enough to need one.
		if (f.wallPlanned && ColonistBrain.count(c.getInventory(), ColonistBrain.STONE_MATS) > 0) {
			List<Job> wall = new ArrayList<>();
			for (BlockPos col : City.wallColumns(f)) {
				if (!world.isChunkLoaded(col.getX() >> 4, col.getZ() >> 4) || c.isBlacklisted(col)) continue;
				if (col.getSquaredDistance(c.getBlockPos().withY(0)) > 48 * 48) continue;
				int base = City.wallBase(world, col.getX(), col.getZ());
				if (!world.getBlockState(new BlockPos(col.getX(), base - 1, col.getZ())).getFluidState().isEmpty()) continue;
				for (int i = 0; i < City.WALL_HEIGHT; i++) {
					BlockPos p = new BlockPos(col.getX(), base + i, col.getZ());
					BlockState s = world.getBlockState(p);
					if (City.wallSatisfied(world, p, s)) continue;
					if (s.isIn(BlockTags.LOGS) || s.isIn(BlockTags.LEAVES)) wall.add(new Job(p, Kind.CLEAR));
					else if (s.isReplaceable() || s.isAir()) wall.add(new Job(p, Kind.WALL));
					break; // build each column bottom-up
				}
			}
			best = nearest(c, wall);
			if (best != null) return best;
		}

		// Street lamps in front of each finished house.
		if (ColonistBrain.count(c.getInventory(), Items.TORCH) > 0 && ColonistBrain.count(c.getInventory(), ColonistBrain.PLANKS) > 0) {
			List<Job> lamps = new ArrayList<>();
			for (BlockPos home : f.homes.keySet()) {
				BlockPos col = f.site(home).at(-1, 0, -1).withY(0);
				if (!world.isChunkLoaded(col.getX() >> 4, col.getZ() >> 4)) continue;
				BlockPos base = new BlockPos(col.getX(), City.surfaceY(world, col.getX(), col.getZ()), col.getZ());
				BlockState below = world.getBlockState(base.down());
				if (world.getBlockState(base).isAir() && !below.isOf(Blocks.OAK_FENCE) && below.isSolidBlock(world, base.down())) {
					lamps.add(new Job(base, Kind.LAMP));
				}
			}
			best = nearest(c, lamps);
		}
		return best;
	}

	@Nullable
	private static Job nearest(ColonistEntity c, List<Job> jobs) {
		Job best = null;
		double bestD = Double.MAX_VALUE;
		for (Job j : jobs) {
			if (c.isBlacklisted(j.pos)) continue;
			double d = c.squaredDistanceTo(j.pos.toCenterPos());
			if (d < bestD) {
				bestD = d;
				best = j;
			}
		}
		return best;
	}

	@Override
	public void tick() {
		if (this.cooldown-- > 0) return;
		Faction f = faction(this.colonist);
		if (f == null || f.cityCenter == null) {
			this.done = true;
			return;
		}
		ServerWorld world = (ServerWorld) this.colonist.getWorld();
		if (this.job == null) {
			this.job = findJob(this.colonist, f);
			this.resetMovement();
			if (this.job == null) {
				this.colonist.searchCooldown("WORKS", 1200);
				this.done = true;
				return;
			}
		}
		if (!this.approach(this.job.pos, 4.0, 1.0)) {
			if (this.stuck()) {
				this.colonist.blacklist(this.job.pos.withY(0), 2400);
				this.colonist.blacklist(this.job.pos, 2400);
				this.job = null;
			}
			return;
		}
		this.perform(world, f, this.job);
		this.colonist.swingHand(Hand.MAIN_HAND);
		this.job = null;
		this.cooldown = 5;
		if (++this.jobsDone >= 40) this.done = true;
	}

	private void perform(ServerWorld world, Faction f, Job job) {
		var inv = this.colonist.getInventory();
		BlockPos p = job.pos;
		BlockState state = null;
		switch (job.kind) {
			case CLEAR -> {
				BlockState s = world.getBlockState(p);
				BlockEntity be = world.getBlockEntity(p);
				for (ItemStack drop : Block.getDroppedStacks(s, world, p, be, this.colonist, this.colonist.getMainHandStack())) {
					ColonistBrain.give(this.colonist, drop);
				}
				world.breakBlock(p, false, this.colonist);
			}
			case FILL -> {
				ItemStack fill = ColonistBrain.takeOne(inv, s -> s.isOf(Items.DIRT) || s.isOf(Items.COBBLESTONE) || s.isOf(Items.GRAVEL));
				if (fill.isEmpty()) fill = ColonistBrain.takeOne(inv, ColonistBrain.PLANKS);
				if (fill.isEmpty()) return;
				state = net.colonists.build.Materials.blockOf(fill, Blocks.DIRT).getDefaultState();
			}
			case PAVE -> {
				// The plaza gets cobblestone when there is some to spare; streets are packed-earth paths.
				boolean plaza = f.cityCenter != null && Math.abs(p.getX() - f.cityCenter.getX()) <= City.PLAZA_HALF
						&& Math.abs(p.getZ() - f.cityCenter.getZ()) <= City.PLAZA_HALF;
				if (plaza && ColonistBrain.count(inv, Items.COBBLESTONE) > 16 && ColonistBrain.take(inv, Items.COBBLESTONE, 1)) {
					state = Blocks.COBBLESTONE.getDefaultState();
				} else {
					state = Blocks.DIRT_PATH.getDefaultState();
				}
			}
			case WATER -> state = Blocks.WATER.getDefaultState();
			case RIM -> {
				ItemStack stone = ColonistBrain.takeOne(inv, ColonistBrain.STONE_MATS);
				if (stone.isEmpty()) return;
				state = net.colonists.build.Materials.blockOf(stone, Blocks.COBBLESTONE).getDefaultState();
			}
			case WALL -> {
				ItemStack stone = ColonistBrain.takeOne(inv, ColonistBrain.STONE_MATS);
				if (stone.isEmpty()) return;
				state = net.colonists.build.Materials.blockOf(stone, Blocks.COBBLESTONE).getDefaultState();
			}
			case BELL -> state = Blocks.BELL.getDefaultState();
			case BANNER -> state = bannerFor(f.color).getDefaultState().with(BannerBlock.ROTATION, 8);
			case LAMP -> {
				if (!ColonistBrain.take(inv, Items.TORCH, 1)) return;
				ColonistBrain.take(inv, ColonistBrain.PLANKS, 1);
				world.setBlockState(p, Blocks.OAK_FENCE.getDefaultState(), Block.NOTIFY_ALL);
				world.setBlockState(p.up(), Blocks.TORCH.getDefaultState(), Block.NOTIFY_ALL);
				state = Blocks.OAK_FENCE.getDefaultState();
			}
		}
		if (state == null) return;
		if (job.kind != Kind.LAMP) world.setBlockState(p, state, Block.NOTIFY_ALL);
		world.playSound(null, p, state.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 1.0f, 0.9f);
	}

	private static Block bannerFor(Formatting color) {
		return switch (color) {
			case RED -> Blocks.RED_BANNER;
			case DARK_RED -> Blocks.BROWN_BANNER;
			case BLUE -> Blocks.BLUE_BANNER;
			case DARK_BLUE -> Blocks.BLACK_BANNER;
			case GREEN -> Blocks.LIME_BANNER;
			case DARK_GREEN -> Blocks.GREEN_BANNER;
			case GOLD -> Blocks.ORANGE_BANNER;
			case YELLOW -> Blocks.YELLOW_BANNER;
			case AQUA -> Blocks.LIGHT_BLUE_BANNER;
			case DARK_AQUA -> Blocks.CYAN_BANNER;
			case DARK_PURPLE -> Blocks.PURPLE_BANNER;
			case LIGHT_PURPLE -> Blocks.MAGENTA_BANNER;
			default -> Blocks.WHITE_BANNER;
		};
	}
}
