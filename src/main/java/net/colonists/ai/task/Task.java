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
	private boolean fresh;
	/** Set while the colonist is on a ladder between storeys (see Storeys). */
	boolean climbing;
	int routeTicks;

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
			this.climbing = false;
			return true;
		}
		if (Storeys.route(this, this.colonist, pos)) {
			this.noProgressTicks = 0;
			return false;
		}
		if (!pos.equals(this.moveTarget)) {
			this.routeTicks = 0;
			this.moveTarget = pos;
			this.bestDistance = distance;
			this.noProgressTicks = 0;
			this.repathTimer = 0;
			this.fresh = true;
		}
		climb(pos);
		if (distance < this.bestDistance - 0.5) {
			this.bestDistance = distance;
			this.noProgressTicks = 0;
		} else {
			this.noProgressTicks++;
		}
		if (--this.repathTimer <= 0) {
			// Keep following a good path; only re-plan when it runs out, or now and then to catch changes.
			boolean idle = this.colonist.getNavigation().isIdle();
			if (!this.fresh && !idle && this.noProgressTicks < 30 && this.repathTimer > -40 * (this.colonist.light ? 3 : 1)) return false;
			if (!net.colonists.ai.Budget.path(this.colonist.getWorld())) {
				this.repathTimer = 1 + this.colonist.getRandom().nextInt(3);
				return false;
			}
			this.fresh = false;
			this.repathTimer = this.colonist.light ? 60 : 20;
			BlockPos goal = waypoint(pos);
			Path path = this.colonist.getNavigation().findPathTo(goal, 1);
			if (path == null && goal != pos) path = this.colonist.getNavigation().findPathTo(pos, 1);
			if (path != null) this.colonist.getNavigation().startMovingAlong(path, speed);
		}
		return false;
	}

	/**
	 * Long trips go leg by leg: the pathfinder only sees ~40 blocks, so a far destination is broken into a stop
	 * about 28 blocks along the way, nudged onto a nearby road or path where there is one (roads are walkable
	 * and lead somewhere).
	 */
	private BlockPos waypoint(BlockPos pos) {
		Vec3d from = this.colonist.getPos();
		Vec3d to = Vec3d.ofCenter(pos);
		Vec3d flat = to.subtract(from).multiply(1, 0, 1);
		if (flat.lengthSquared() < 36 * 36) return pos;
		Vec3d step = from.add(flat.normalize().multiply(28));
		net.minecraft.world.World w = this.colonist.getWorld();
		int sx = (int) Math.floor(step.x), sz = (int) Math.floor(step.z);
		if (!w.isChunkLoaded(sx >> 4, sz >> 4)) return pos;
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		for (int dx = -5; dx <= 5; dx++) {
			for (int dz = -5; dz <= 5; dz++) {
				int x = sx + dx, z = sz + dz;
				int y = w.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
				BlockPos floor = new BlockPos(x, y - 1, z);
				if (!w.getFluidState(floor).isEmpty()) continue;
				double score = dx * dx + dz * dz + (net.colonists.faction.City.isPavement(w.getBlockState(floor)) ? 0 : 30);
				if (score < bestScore) {
					bestScore = score;
					best = floor.up();
				}
			}
		}
		return best != null ? best : pos;
	}

	/** Ladders: the pathfinder will not plan a climb, so a colonist standing in one just goes up (or down) it. */
	private void climb(BlockPos pos) {
		if (!this.colonist.isClimbing()) return;
		int dy = pos.getY() - this.colonist.getBlockY();
		if (dy == 0) return;
		Vec3d v = this.colonist.getVelocity();
		this.colonist.setVelocity(v.x * 0.5, dy > 0 ? 0.2 : -0.15, v.z * 0.5);
	}

	protected boolean approach(Entity entity, double reach, double speed) {
		double distance = this.colonist.squaredDistanceTo(entity);
		this.colonist.getLookControl().lookAt(entity, 30f, 30f);
		if (distance <= reach * reach) {
			this.colonist.getNavigation().stop();
			return true;
		}
		if (--this.repathTimer <= 0) {
			if (!net.colonists.ai.Budget.path(this.colonist.getWorld())) {
				this.repathTimer = 1 + this.colonist.getRandom().nextInt(3);
				return false;
			}
			this.repathTimer = this.colonist.light ? 40 : 20;
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
		this.fresh = true;
	}
}
