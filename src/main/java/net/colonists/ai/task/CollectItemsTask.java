package net.colonists.ai.task;

import net.colonists.entity.ColonistEntity;
import net.minecraft.entity.ItemEntity;

import java.util.Comparator;
import java.util.List;

/** Picks up useful items lying nearby (drops from hunting, felled trees, and so on). */
public class CollectItemsTask extends Task {
	private ItemEntity item;

	public CollectItemsTask(ColonistEntity colonist) {
		super(colonist);
	}

	@Override
	public String describe() {
		return "Picking things up";
	}

	@Override
	public int maxTicks() {
		return 400;
	}

	public static List<ItemEntity> nearby(ColonistEntity c) {
		return c.getWorld().getEntitiesByClass(ItemEntity.class, c.getBoundingBox().expand(10, 4, 10),
				i -> i.isAlive() && !i.cannotPickup() && c.canGather(i.getStack()) && !c.isBlacklisted(i.getBlockPos()));
	}

	@Override
	public void tick() {
		if (this.item == null || !this.item.isAlive()) {
			this.item = nearby(this.colonist).stream().min(Comparator.comparingDouble(this.colonist::squaredDistanceTo)).orElse(null);
			this.resetMovement();
			if (this.item == null) {
				this.done = true;
				return;
			}
		}
		if (this.approach(this.item.getBlockPos(), 1.6, 1.0)) {
			if (this.colonist.squaredDistanceTo(this.item) < 2.5 * 2.5) this.colonist.pickUp(this.item);
			if (this.item.isAlive()) this.colonist.blacklist(this.item.getBlockPos(), 600);
			this.item = null;
		} else if (this.stuck()) {
			this.colonist.blacklist(this.item.getBlockPos(), 600);
			this.item = null;
		}
	}
}
