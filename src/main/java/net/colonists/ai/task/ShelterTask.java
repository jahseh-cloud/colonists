package net.colonists.ai.task;

import net.colonists.entity.ColonistEntity;
import net.minecraft.util.math.BlockPos;

/** No bed yet: wait out the night indoors. */
public class ShelterTask extends Task {
	private final BlockPos house;
	private final boolean rest;
	private int idle;

	public ShelterTask(ColonistEntity colonist, BlockPos house) {
		this(colonist, house, false);
	}

	/** rest = a lie-down at home to get one's strength back (worn out, or the night watch by day). */
	public ShelterTask(ColonistEntity colonist, BlockPos house, boolean rest) {
		super(colonist);
		this.house = house;
		this.rest = rest;
	}

	@Override
	public String describe() {
		if (this.rest) return this.idle > 0 ? "Resting at home" : "Heading home for a rest";
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
		boolean over = this.rest
				? (net.colonists.ai.Schedule.nightWatch(this.colonist) ? !net.colonists.ai.Schedule.sleepTime(this.colonist) : this.colonist.energy >= 85)
				: !net.colonists.ai.Schedule.sleepTime(this.colonist);
		if (over) {
			this.done = true;
			return;
		}
		BlockPos spot = BuildStructureTask.inside(this.colonist, this.house);
		if (this.approach(spot, 1.8, 0.9)) {
			// Potter about inside: look around now and then.
			if (++this.idle % 80 == 1) {
				this.colonist.getLookControl().lookAt(spot.getX() + this.colonist.getRandom().nextInt(5) - 2, spot.getY() + 1,
						spot.getZ() + this.colonist.getRandom().nextInt(5) - 2);
			}
		} else if (this.stuck()) {
			this.done = true;
		}
	}
}
