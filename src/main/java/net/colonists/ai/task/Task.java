package net.colonists.ai.task;

import net.colonists.entity.ColonistEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** One unit of autonomous work. Tasks are short-lived; the planner picks a fresh one when this finishes. */
public abstract class Task {
	protected final ColonistEntity colonist;
	protected boolean done;
	public int ticks;

	private BlockPos moveTarget;
	private int repathTimer;
	private double bestDistance;
	private int noProgressTicks;

	protected Task(ColonistEntity colonist) {
		this.colonist = colonist;
	}

	/** Shown when a player right-clicks the colonist. */
	public abstract String describe();

	public ColonistEntity.ToolKind tool() {
		return ColonistEntity.ToolKind.WEAPON;
	}

	public int maxTicks() {
		return 2400;
	}

	/** Night-time tasks (sleeping, sheltering) return false so nightfall does not cancel them. */
	public boolean stopsAtNight() {
		return true;
	}

	public void start() {}

	public void tick() {}

	public void stop() {}

	public boolean isDone() {
		return this.done;
	}

	/** Walks toward a block. Returns true once the colonist's eyes are within {@code reach} of its centre. */
	protected boolean approach(BlockPos pos, double reach, double speed) {
		Vec3d center = Vec3d.ofCenter(pos);
		double distance = this.colonist.getEyePos().squaredDistanceTo(center);
		this.colonist.getLookControl().lookAt(center.x, center.y, center.z);
		if (distance <= reach * reach) {
			this.colonist.getNavigation().stop();
			return true;
		}
		if (!pos.equals(this.moveTarget)) {
			this.moveTarget = pos;
			this.bestDistance = distance;
			this.noProgressTicks = 0;
			this.repathTimer = 0;
		}
		if (distance < this.bestDistance - 0.5) {
			this.bestDistance = distance;
			this.noProgressTicks = 0;
		} else {
			this.noProgressTicks++;
		}
		if (--this.repathTimer <= 0) {
			this.repathTimer = 20;
			Path path = this.colonist.getNavigation().findPathTo(pos, 1);
			if (path != null) this.colonist.getNavigation().startMovingAlong(path, speed);
		}
		return false;
	}

	protected boolean approach(Entity entity, double reach, double speed) {
		double distance = this.colonist.squaredDistanceTo(entity);
		this.colonist.getLookControl().lookAt(entity, 30f, 30f);
		if (distance <= reach * reach) {
			this.colonist.getNavigation().stop();
			return true;
		}
		if (--this.repathTimer <= 0) {
			this.repathTimer = 20;
			this.colonist.getNavigation().startMovingTo(entity, speed);
		}
		return false;
	}

	/** True when the colonist has made no headway toward its current destination for a while. */
	protected boolean stuck() {
		return this.noProgressTicks > 140;
	}

	protected void resetMovement() {
		this.moveTarget = null;
		this.noProgressTicks = 0;
		this.repathTimer = 0;
	}
}
