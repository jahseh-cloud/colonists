package net.colonists.ai.task;

import net.colonists.entity.ColonistEntity;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Comparator;

/** Children tag along with a parent, or play with other children near home. */
public class ChildTask extends Task {
	private Entity buddy;

	public ChildTask(ColonistEntity colonist) {
		super(colonist);
	}

	@Override
	public String describe() {
		return this.buddy instanceof ColonistEntity c && c.isBaby() ? "Playing with " + c.getFirstName() : "Following a parent around";
	}

	@Override
	public ColonistEntity.ToolKind tool() {
		return ColonistEntity.ToolKind.NONE;
	}

	@Override
	public int maxTicks() {
		return 600;
	}

	@Override
	public void start() {
		ServerWorld world = (ServerWorld) this.colonist.getWorld();
		if (this.colonist.getRandom().nextInt(2) == 0) {
			this.buddy = world.getEntitiesByClass(ColonistEntity.class, this.colonist.getBoundingBox().expand(16),
					c -> c != this.colonist && c.isBaby()).stream().findAny().orElse(null);
		}
		if (this.buddy == null) {
			for (java.util.UUID parent : new java.util.UUID[]{this.colonist.parentA, this.colonist.parentB}) {
				if (parent == null) continue;
				Entity p = world.getEntity(parent);
				if (p != null && p.isAlive() && p.squaredDistanceTo(this.colonist) < 48 * 48) {
					this.buddy = p;
					break;
				}
			}
		}
	}

	@Override
	public void tick() {
		if (this.buddy != null && this.buddy.isAlive()) {
			double reach = this.buddy instanceof ColonistEntity c && c.isBaby() ? 1.5 : 3.5;
			this.approach(this.buddy, reach, this.buddy instanceof ColonistEntity c2 && c2.isBaby() ? 1.2 : 1.0);
			if (this.ticks % 60 == 0 && this.colonist.getRandom().nextInt(3) == 0) this.colonist.getJumpControl().setActive();
			return;
		}
		BlockPos home = this.colonist.parentHome;
		if (home == null) {
			this.done = true;
			return;
		}
		if (this.approach(BuildHouseTask.doorstep(home), 3.0, 1.0) || this.stuck()) this.done = true;
	}
}
