package net.colonists.ai;

import net.colonists.entity.ColonistEntity;
import net.colonists.faction.Settlement;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;

/**
 * Idle strolling that stays in town: colonists with nothing to do wander the streets near home rather than
 * drifting off into the wilds (and after dark they keep to their own doorstep).
 */
public class TownWanderGoal extends WanderAroundFarGoal {
	private final ColonistEntity colonist;

	public TownWanderGoal(ColonistEntity colonist, double speed) {
		super(colonist, speed);
		this.colonist = colonist;
	}

	@Override
	public boolean canStart() {
		if (this.colonist.light && this.colonist.getRandom().nextInt(3) != 0) return false; // nobody is watching
		return super.canStart();
	}

	@Nullable
	@Override
	protected Vec3d getWanderTarget() {
		ColonistEntity c = this.colonist;
		Settlement f = c.settlement();
		BlockPos anchor;
		int range;
		boolean dark = c.getWorld().isNight();
		if (dark && (c.home != null || c.parentHome != null)) {
			anchor = c.home != null ? c.home : c.parentHome;
			range = 8;
		} else if (f != null && f.cityCenter != null && !c.isBaby()) {
			anchor = f.cityCenter;
			range = Math.min(64, 24 + f.population());
		} else {
			return super.getWanderTarget();
		}
		// Already far outside: head back toward town.
		double d = Math.sqrt(c.getBlockPos().getSquaredDistance(anchor.withY(c.getBlockY())));
		for (int i = 0; i < 8; i++) {
			int x, z;
			if (d > range + 8) {
				Vec3d back = Vec3d.ofCenter(anchor).subtract(c.getPos()).multiply(1, 0, 1).normalize().multiply(Math.min(16, d));
				x = (int) (c.getX() + back.x) + c.getRandom().nextInt(5) - 2;
				z = (int) (c.getZ() + back.z) + c.getRandom().nextInt(5) - 2;
			} else {
				x = c.getBlockX() + c.getRandom().nextInt(21) - 10;
				z = c.getBlockZ() + c.getRandom().nextInt(21) - 10;
				if ((x - anchor.getX()) * (x - anchor.getX()) + (z - anchor.getZ()) * (z - anchor.getZ()) > range * range) continue;
			}
			if (!c.getWorld().isChunkLoaded(x >> 4, z >> 4)) continue;
			int y = c.getWorld().getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
			if (y - c.getBlockY() > 3 || c.getBlockY() - y > 6) continue; // not onto roofs
			if (!c.getWorld().getFluidState(new BlockPos(x, y - 1, z)).isEmpty()) continue;
			return new Vec3d(x + 0.5, y, z + 0.5);
		}
		return null;
	}
}
