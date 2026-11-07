package net.colonists.ai.task;

import net.colonists.entity.ColonistEntity;
import net.minecraft.util.math.BlockPos;

/** No bed yet: wait out the night indoors. */
public class ShelterTask extends Task {
	private final BlockPos house;
	private int idle;

	public ShelterTask(ColonistEntity colonist, BlockPos house) {
		super(colonist);
		this.house = house;
	}

	@Override
	public String describe() {
		return "Sheltering at home for the night";
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
	public void tick() {
		if (this.colonist.getWorld().isDay()) {
			this.done = true;
			return;
		}
		BlockPos spot = BuildStructureTask.inside(this.colonist, this.house);
		if (this.approach(spot, 1.8, 0.9)) {
			// Potter about inside: look around now and then.
			if (++this.idle % 80 == 0) {
				this.colonist.getLookControl().lookAt(spot.getX() + this.colonist.getRandom().nextInt(5) - 2, spot.getY() + 1,
						spot.getZ() + this.colonist.getRandom().nextInt(5) - 2);
			}
		} else if (this.stuck()) {
			this.done = true;
		}
	}
}
