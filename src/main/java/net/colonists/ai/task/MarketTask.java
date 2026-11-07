package net.colonists.ai.task;

import net.colonists.entity.ColonistEntity;
import net.minecraft.util.math.BlockPos;

/** The merchant keeps the market stall: players can right-click them to trade with the town. */
public class MarketTask extends Task {
	private final BlockPos stall;

	public MarketTask(ColonistEntity colonist, BlockPos stall) {
		super(colonist);
		this.stall = stall;
	}

	@Override
	public String describe() {
		return "Minding the market stall";
	}

	@Override
	public int maxTicks() {
		return 2400;
	}

	@Override
	public void tick() {
		if (this.approach(this.stall, 2.5, 0.9)) {
			this.colonist.getNavigation().stop();
			if (this.ticks % 300 == 0 && this.colonist.getRandom().nextInt(3) == 0) {
				this.colonist.say(switch (this.colonist.getRandom().nextInt(3)) {
					case 0 -> "Fresh bread! Good timber! Fair prices!";
					case 1 -> "Come, have a look. Emeralds only.";
					default -> "Buying and selling, best rates in town!";
				});
			}
		}
	}
}
