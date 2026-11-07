package net.colonists.ai.task;

import net.colonists.entity.ColonistEntity;
import net.colonists.law.Justice;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

/** Serving a sentence: sitting in a cell, breaking stone in the work camp, or waiting on the auction block. */
public class PrisonTask extends Task {
	private final BlockPos place;
	private final Justice.Sentence sentence;

	public PrisonTask(ColonistEntity colonist, BlockPos place, Justice.Sentence sentence) {
		super(colonist);
		this.place = place;
		this.sentence = sentence;
	}

	@Override
	public String describe() {
		return switch (this.sentence) {
			case JAIL -> "Locked in a prison cell";
			case CAMP -> "Breaking stone in the work camp";
			case DEATH -> "Awaiting the gallows";
			case SLAVE -> "On the auction block";
		};
	}

	@Override
	public boolean stopsAtNight() {
		return false;
	}

	@Override
	public int maxTicks() {
		return 600;
	}

	@Override
	public void tick() {
		double radius = this.sentence == Justice.Sentence.CAMP ? 4.0 : 1.5;
		if (this.colonist.getBlockPos().getSquaredDistance(this.place) > radius * radius) {
			this.approach(this.place, 1.0, 0.7);
			return;
		}
		this.colonist.getNavigation().stop();
		if (this.sentence == Justice.Sentence.CAMP && this.ticks % 15 == 0) this.colonist.swingHand(Hand.MAIN_HAND);
		if (this.ticks % 400 == 0 && this.colonist.getRandom().nextInt(4) == 0) {
			this.colonist.say(switch (this.sentence) {
				case JAIL -> "I didn't do it... well, maybe I did.";
				case CAMP -> "My back is killing me.";
				case DEATH -> "Mercy, please...";
				case SLAVE -> "Who will buy me?";
			});
		}
	}
}
