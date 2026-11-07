package net.colonists.ai.task;

import net.colonists.entity.ColonistEntity;
import net.colonists.faction.City;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** A long walk to a distant place (for founding a new city), in hops the pathfinder can manage. */
public class MigrateTask extends Task {
	private final BlockPos destination;
	private final String label;
	private BlockPos hop;

	public MigrateTask(ColonistEntity colonist, BlockPos destination) {
		this(colonist, destination, "Setting out to found a new city");
	}

	public MigrateTask(ColonistEntity colonist, BlockPos destination, String label) {
		super(colonist);
		this.destination = destination;
		this.label = label;
	}

	@Override
	public String describe() {
		return this.label;
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
		// Nobody watching: the chunks along the way are not loaded, so the journey happens "off-screen".
		if (this.ticks == 1 && !net.colonists.faction.Empire.watched((net.minecraft.server.world.ServerWorld) this.colonist.getWorld(), this.colonist, 128)) {
			int x = this.destination.getX() + this.colonist.getRandom().nextInt(9) - 4, z = this.destination.getZ() + this.colonist.getRandom().nextInt(9) - 4;
			int y = City.surfaceYOr(this.colonist.getWorld(), x, z, this.destination.getY());
			this.colonist.refreshPositionAndAngles(x + 0.5, y, z + 0.5, this.colonist.getYaw(), 0f);
			this.colonist.getNavigation().stop();
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
