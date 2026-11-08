package net.colonists.ai.task;

import net.colonists.entity.ColonistEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

/** A retainer at their lord's side: following close (catching up if left far behind), or holding a post when told to stay. */
public class FollowLiegeTask extends Task {
	private final ServerPlayerEntity liege;

	public FollowLiegeTask(ColonistEntity colonist, ServerPlayerEntity liege) {
		super(colonist);
		this.liege = liege;
	}

	@Override
	public String describe() {
		return this.colonist.following ? "Following " + this.liege.getName().getString() : "Holding position for " + this.liege.getName().getString();
	}

	@Override
	public ColonistEntity.ToolKind tool() {
		return ColonistEntity.ToolKind.WEAPON;
	}

	@Override
	public boolean stopsAtNight() {
		return false;
	}

	@Override
	public int maxTicks() {
		return 200;
	}

	@Override
	public void tick() {
		if (!this.liege.isAlive() || this.liege.getWorld() != this.colonist.getWorld()) {
			this.done = true;
			return;
		}
		if (!this.colonist.following) {
			BlockPos post = this.colonist.guardPost != null ? this.colonist.guardPost : this.colonist.getBlockPos();
			if (this.colonist.getBlockPos().getSquaredDistance(post) > 9) this.approach(post, 2.0, 1.0);
			else this.colonist.getNavigation().stop();
			return;
		}
		double d = this.colonist.squaredDistanceTo(this.liege);
		if (d > 40 * 40) {
			// Left far behind: catch up (as pets do).
			BlockPos at = this.liege.getBlockPos();
			this.colonist.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, this.colonist.getYaw(), 0f);
			this.colonist.getNavigation().stop();
		} else if (d > 5 * 5) {
			this.colonist.getNavigation().startMovingTo(this.liege, d > 14 * 14 ? 1.25 : 1.0);
		} else {
			this.colonist.getNavigation().stop();
			this.colonist.getLookControl().lookAt(this.liege, 30f, 30f);
		}
	}
}
