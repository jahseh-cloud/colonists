package net.colonists.ai;

import net.colonists.entity.ColonistEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.NoPenaltyTargeting;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;

/** Badly hurt colonists stop fighting and run away to recover. */
public class RetreatGoal extends Goal {
	private final ColonistEntity colonist;
	private Vec3d destination;
	private int timer;

	public RetreatGoal(ColonistEntity colonist) {
		this.colonist = colonist;
		this.setControls(EnumSet.of(Control.MOVE));
	}

	@Override
	public boolean canStart() {
		LivingEntity attacker = this.colonist.getAttacker();
		if ((this.colonist.getHealth() > 6.0f && !this.colonist.isBaby()) || attacker == null || !attacker.isAlive()
				|| this.colonist.squaredDistanceTo(attacker) > 12 * 12) {
			return false;
		}
		this.destination = NoPenaltyTargeting.findFrom(this.colonist, 16, 7, attacker.getPos());
		return this.destination != null;
	}

	@Override
	public void start() {
		this.timer = 100;
		this.colonist.setTarget(null);
		this.colonist.activity = "Fleeing";
		this.colonist.getNavigation().startMovingTo(this.destination.x, this.destination.y, this.destination.z, 1.4);
	}

	@Override
	public boolean shouldContinue() {
		return this.timer > 0 && !this.colonist.getNavigation().isIdle();
	}

	@Override
	public void tick() {
		this.timer--;
	}
}
