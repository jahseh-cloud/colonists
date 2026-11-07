package net.colonists.ai.task;

import net.colonists.entity.ColonistEntity;
import net.colonists.faction.City;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Guards walk the plaza and the streets, keeping watch (and fighting whatever turns up). */
public class PatrolTask extends Task {
	private BlockPos point;
	private int linger;
	private int points;

	public PatrolTask(ColonistEntity colonist) {
		super(colonist);
	}

	@Override
	public String describe() {
		return "On patrol";
	}

	@Override
	public boolean stopsAtNight() {
		return false;
	}

	@Override
	public int maxTicks() {
		return 1800;
	}

	@Override
	public void tick() {
		FactionManager fm = this.colonist.factions();
		Faction f = fm == null ? null : fm.get(this.colonist.getFactionId());
		if (f == null || f.cityCenter == null) {
			this.done = true;
			return;
		}
		if (this.point == null) {
			List<BlockPos> options = new ArrayList<>();
			options.add(f.cityCenter);
			for (BlockPos home : f.homes.keySet()) options.add(BuildStructureTask.blueprintAt(f, home).doorstep(home));
			for (BlockPos farm : f.farms) options.add(farm.add(4, 0, -1));
			BlockPos pick = options.get(this.colonist.getRandom().nextInt(options.size()));
			this.point = pick.withY(City.surfaceYOr(this.colonist.getWorld(), pick.getX(), pick.getZ(), pick.getY()));
			this.resetMovement();
		}
		if (this.approach(this.point, 3.0, 0.8) || this.stuck()) {
			if (++this.linger > 100) {
				this.linger = 0;
				this.point = null;
				if (++this.points >= 3) this.done = true;
			}
		}
	}
}
