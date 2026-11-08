package net.colonists.ai.task;

import net.colonists.entity.ColonistEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * The evening gathering: after the day's work, townsfolk drift to the plaza (or the tavern) to talk. Everyone standing
 * there grows a little closer to everyone else, which is how a compact town turns neighbours into friends, and friends
 * into couples.
 */
public class GatheringTask extends Task {
	private static final String[] LINES = {
			"Long day, %s. Good to sit down.",
			"Did you hear what happened at the market, %s?",
			"The harvest looks good this year, %s.",
			"%s! Come, sit with us.",
			"Another round, %s?",
			"My feet are killing me, %s.",
			"They say there's war in the east, %s."};

	private final BlockPos spot;
	private int stayed;

	public GatheringTask(ColonistEntity colonist, BlockPos spot) {
		super(colonist);
		this.spot = spot;
	}

	@Override
	public String describe() {
		return "Gathering with the neighbours";
	}

	@Override
	public int maxTicks() {
		return 900;
	}

	@Override
	public void tick() {
		if (!this.approach(this.spot, 4.5, 0.85)) {
			if (this.stuck()) this.done = true;
			return;
		}
		this.colonist.getNavigation().stop();
		this.stayed++;
		if (this.stayed % 40 == 0) {
			List<ColonistEntity> company = this.colonist.getWorld().getEntitiesByClass(ColonistEntity.class, this.colonist.getBoundingBox().expand(6, 3, 6),
					o -> o != this.colonist && o.isAlive() && !o.isSleeping() && o.getFactionId().equals(this.colonist.getFactionId()));
			for (ColonistEntity o : company) {
				this.colonist.addAffinity(o.getUuid(), 2);
				o.addAffinity(this.colonist.getUuid(), 2);
			}
			if (!company.isEmpty()) {
				ColonistEntity o = company.get(this.colonist.getRandom().nextInt(company.size()));
				this.colonist.getLookControl().lookAt(o, 30f, 30f);
				if (this.colonist.getRandom().nextInt(4) == 0) this.colonist.say(String.format(LINES[this.colonist.getRandom().nextInt(LINES.length)], o.getFirstName()));
				((ServerWorld) this.colonist.getWorld()).spawnParticles(ParticleTypes.HAPPY_VILLAGER, this.colonist.getX(), this.colonist.getEyeY() + 0.5, this.colonist.getZ(), 1, 0.3, 0.2, 0.3, 0.0);
			}
		}
		if (this.stayed > 400) {
			this.colonist.lastSocialTime = this.colonist.getWorld().getTime();
			this.done = true;
		}
	}
}
