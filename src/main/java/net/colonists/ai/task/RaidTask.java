package net.colonists.ai.task;

import net.colonists.faction.Settlement;
import net.colonists.entity.CaptiveEntity;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;

/** A guard of a slaving city goes out to seize a villager and bring them back as a captive. */
public class RaidTask extends Task {
	private final VillagerEntity target;
	private int subdue;

	public RaidTask(ColonistEntity colonist, VillagerEntity target) {
		super(colonist);
		this.target = target;
	}

	@Override
	public String describe() {
		return "Raiding a villager settlement";
	}

	@Override
	public int maxTicks() {
		return 2400;
	}

	@Nullable
	public static VillagerEntity findTarget(ColonistEntity c) {
		return c.getWorld().getEntitiesByClass(VillagerEntity.class, c.getBoundingBox().expand(80, 24, 80),
						v -> v.isAlive() && !v.isBaby() && !v.hasCustomName())
				.stream().min(Comparator.comparingDouble(c::squaredDistanceTo)).orElse(null);
	}

	@Override
	public void tick() {
		FactionManager fm = this.colonist.factions();
		Settlement f = this.colonist.settlement();
		if (f == null || !f.faction.slavery || !this.target.isAlive() || this.target.isRemoved()
				|| this.colonist.squaredDistanceTo(this.target) > 100 * 100) {
			this.done = true;
			return;
		}
		if (!this.approach(this.target, 2.0, 1.15)) {
			this.subdue = 0;
			return;
		}
		ServerWorld world = (ServerWorld) this.colonist.getWorld();
		this.target.getNavigation().stop();
		if (this.subdue == 0) {
			this.colonist.say("You're coming with us.");
			// The village's golems come to the rescue.
			for (IronGolemEntity golem : world.getEntitiesByClass(IronGolemEntity.class, this.target.getBoundingBox().expand(24), g -> g.getTarget() == null)) {
				golem.setTarget(this.colonist);
			}
		}
		if (this.subdue % 10 == 0) {
			this.colonist.swingHand(Hand.MAIN_HAND);
			world.spawnParticles(ParticleTypes.ANGRY_VILLAGER, this.target.getX(), this.target.getEyeY() + 0.3, this.target.getZ(), 2, 0.3, 0.2, 0.3, 0.0);
		}
		if (++this.subdue >= 40) {
			CaptiveEntity.capture(world, this.target, this.colonist, f.faction);
			FactionManager.broadcastNear(world, this.colonist.getBlockPos(), 96, Text.literal("⛓ The ").formatted(Formatting.DARK_RED)
					.append(f.faction.displayName()).append(Text.literal(" have taken a villager captive.").formatted(Formatting.DARK_RED)));
			this.done = true;
		}
	}
}
