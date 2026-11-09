package net.colonists.ai.task;

import net.colonists.entity.ColonistEntity;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.world.World;

/** Walks to bed at night and sleeps until morning. */
public class SleepTask extends Task {
	public SleepTask(ColonistEntity colonist) {
		super(colonist);
	}

	@Override
	public String describe() {
		return this.colonist.isSleeping() ? "Sleeping" : "Heading to bed";
	}

	@Override
	public boolean stopsAtNight() {
		return false;
	}

	@Override
	public int maxTicks() {
		return 15000;
	}

	@Override
	public ColonistEntity.ToolKind tool() {
		return ColonistEntity.ToolKind.NONE;
	}

	@Override
	public void tick() {
		World world = this.colonist.getWorld();
		if (this.colonist.isSleeping()) return; // the AI is paused while asleep anyway
		if (!net.colonists.ai.Schedule.sleepTime(this.colonist) || this.colonist.bed == null) {
			this.done = true;
			return;
		}
		BlockState bed = world.getBlockState(this.colonist.bed);
		if (!(bed.getBlock() instanceof BedBlock)) {
			this.colonist.bed = null;
			this.done = true;
			return;
		}
		if (this.approach(this.colonist.bed, 2.2, 0.9)) {
			if (bed.get(BedBlock.OCCUPIED)) {
				this.done = true;
				return;
			}
			this.colonist.sleep(this.colonist.bed);
			this.colonist.activity = "Sleeping";
		} else if (this.stuck()) {
			this.done = true;
		}
	}

	@Override
	public void stop() {
		if (this.colonist.isSleeping()) this.colonist.wakeUp();
	}
}
