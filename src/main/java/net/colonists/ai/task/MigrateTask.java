package net.colonists.ai.task;

import net.colonists.entity.ColonistEntity;
import net.colonists.faction.City;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** A long walk to a distant place (for founding a new city), in hops the pathfinder can manage. */
public class MigrateTask extends Task {
	private final BlockPos destination;
	private BlockPos hop;

	public MigrateTask(ColonistEntity colonist, BlockPos destination) {
		super(colonist);
		this.destination = destination;
	}

	@Override
	public String describe() {
		return "Setting out to found a new city";
	}

	@Override
	public boolean stopsAtNight() {
		return false;
	}

	@Override
	public int maxTicks() {
		return 6000;
	}

	@Override
	public void tick() {
		Vec3d here = this.colonist.getPos();
		Vec3d there = Vec3d.ofCenter(this.destination);
		if (here.squaredDistanceTo(there.x, here.y, there.z) < 12 * 12) {
			this.done = true;
			return;
		}
		if (this.hop == null || this.colonist.getBlockPos().getSquaredDistance(this.hop) < 4 * 4 || this.stuck()) {
			Vec3d dir = there.subtract(here).multiply(1, 0, 1).normalize();
			double step = Math.min(24, Math.sqrt(here.squaredDistanceTo(there.x, here.y, there.z)));
			// Wiggle a little so an obstacle does not stop us forever.
			double angle = (this.colonist.getRandom().nextDouble() - 0.5) * (this.stuck() ? 2.0 : 0.4);
			double dx = dir.x * Math.cos(angle) - dir.z * Math.sin(angle);
			double dz = dir.x * Math.sin(angle) + dir.z * Math.cos(angle);
			int x = (int) Math.floor(here.x + dx * step), z = (int) Math.floor(here.z + dz * step);
			this.hop = new BlockPos(x, City.surfaceYOr(this.colonist.getWorld(), x, z, this.colonist.getBlockY()), z);
			this.resetMovement();
		}
		this.approach(this.hop, 2.0, 1.0);
	}
}
