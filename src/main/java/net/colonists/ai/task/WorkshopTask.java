package net.colonists.ai.task;

import net.colonists.faction.Settlement;
import net.colonists.build.Blueprint;
import net.colonists.entity.ColonistBrain;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Stockpile;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A craftsperson's working day: fetch raw materials from the city stores, work them at the workshop,
 * and carry the finished goods back. Blacksmiths turn iron into tools and armour for the city;
 * cooks turn wheat and raw meat into bread and cooked food.
 */
public class WorkshopTask extends Task {
	public enum Trade { SMITH, COOK }

	enum Phase { FETCH, WORK, DELIVER }

	private static final Set<Item> RAW_FOOD = Set.of(Items.BEEF, Items.PORKCHOP, Items.CHICKEN, Items.MUTTON, Items.RABBIT,
			Items.COD, Items.SALMON, Items.POTATO);

	private final Trade trade;
	private final net.colonists.build.Site workplace;
	private final Blueprint bp;
	private Phase phase = Phase.FETCH;
	private int workTicks;
	private final List<ItemStack> products = new ArrayList<>();

	public WorkshopTask(ColonistEntity colonist, Trade trade, net.colonists.build.Site workplace, Blueprint bp) {
		super(colonist);
		this.trade = trade;
		this.workplace = workplace;
		this.bp = bp;
	}

	@Override
	public String describe() {
		return switch (this.phase) {
			case FETCH -> "Fetching materials";
			case WORK -> this.trade == Trade.SMITH ? "Working the forge" : "Baking and cooking";
			case DELIVER -> "Delivering goods to the stores";
		};
	}

	@Override
	public ColonistEntity.ToolKind tool() {
		return ColonistEntity.ToolKind.NONE;
	}

	private Predicate<ItemStack> inputs() {
		return this.trade == Trade.SMITH
				? s -> s.isOf(Items.RAW_IRON) || s.isOf(Items.IRON_INGOT) || s.isOf(Items.IRON_NUGGET)
				: s -> s.isOf(Items.WHEAT) || RAW_FOOD.contains(s.getItem());
	}

	private int haveInputs() {
		return ColonistBrain.count(this.colonist.getInventory(), this.inputs());
	}

	public static boolean worthDoing(ColonistEntity c, Settlement f, Trade trade) {
		if (c.onSearchCooldown("WORKSHOP")) return false;
		ServerWorld world = (ServerWorld) c.getWorld();
		Predicate<ItemStack> in = trade == Trade.SMITH
				? s -> s.isOf(Items.RAW_IRON) || s.isOf(Items.IRON_INGOT)
				: s -> s.isOf(Items.WHEAT) || RAW_FOOD.contains(s.getItem());
		int stored = Stockpile.count(Stockpile.chests(world, f), in);
		return stored + ColonistBrain.count(c.getInventory(), in) >= (trade == Trade.SMITH ? 3 : 3);
	}

	@Override
	public void tick() {
		ServerWorld world = (ServerWorld) this.colonist.getWorld();
		FactionManager fm = this.colonist.factions();
		Settlement f = this.colonist.settlement();
		if (f == null || f.cityCenter == null) {
			this.done = true;
			return;
		}
		List<Inventory> chests = Stockpile.chests(world, f);
		BlockPos storePos = net.colonists.faction.City.stockpileSlots(f.cityCenter).get(0);
		switch (this.phase) {
			case FETCH -> {
				if (chests.isEmpty() || this.haveInputs() >= 16) {
					this.phase = Phase.WORK;
					return;
				}
				if (!this.approach(storePos, 3.5, 1.0)) {
					if (this.stuck()) this.phase = Phase.WORK;
					return;
				}
				for (ItemStack s : Stockpile.take(chests, this.inputs(), 32)) ColonistBrain.give(this.colonist, s);
				if (this.trade == Trade.SMITH) {
					for (ItemStack s : Stockpile.take(chests, ColonistBrain.COALS, 4)) ColonistBrain.give(this.colonist, s);
					if (ColonistBrain.count(this.colonist.getInventory(), Items.STICK) < 4) {
						for (ItemStack s : Stockpile.take(chests, s -> s.isOf(Items.STICK) || ColonistBrain.PLANKS.test(s), 4)) ColonistBrain.give(this.colonist, s);
					}
				}
				world.playSound(null, storePos, SoundEvents.BLOCK_CHEST_OPEN, SoundCategory.BLOCKS, 0.6f, 1.0f);
				if (this.haveInputs() == 0) {
					this.colonist.searchCooldown("WORKSHOP", 2400);
					this.done = true;
					return;
				}
				this.phase = Phase.WORK;
				this.resetMovement();
			}
			case WORK -> {
				BlockPos station = this.bp.workstation != null ? this.workplace.at(this.bp.workstation) : this.bp.inside(this.workplace);
				if (!this.approach(station, 2.5, 1.0)) {
					if (this.stuck()) this.done = true;
					return;
				}
				this.colonist.getLookControl().lookAt(station.getX() + 0.5, station.getY() + 0.5, station.getZ() + 0.5);
				if (++this.workTicks % 15 == 0) {
					this.colonist.swingHand(Hand.MAIN_HAND);
					if (this.trade == Trade.SMITH) {
						world.playSound(null, station, SoundEvents.BLOCK_ANVIL_USE, SoundCategory.BLOCKS, 0.4f, 1.0f + this.colonist.getRandom().nextFloat() * 0.2f);
						world.spawnParticles(ParticleTypes.LAVA, station.getX() + 0.5, station.getY() + 1.1, station.getZ() + 0.5, 1, 0.1, 0.0, 0.1, 0.0);
					} else {
						world.playSound(null, station, SoundEvents.BLOCK_SMOKER_SMOKE, SoundCategory.BLOCKS, 0.6f, 1.0f);
						world.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, station.getX() + 0.5, station.getY() + 1.2, station.getZ() + 0.5, 1, 0.1, 0.1, 0.1, 0.01);
					}
				}
				if (this.workTicks >= 120) {
					if (this.trade == Trade.SMITH) this.forge(world, chests);
					else this.cook(world);
					this.phase = Phase.DELIVER;
					this.resetMovement();
				}
			}
			case DELIVER -> {
				if (this.products.isEmpty() || chests.isEmpty()) {
					this.finishDelivery();
					return;
				}
				if (!this.approach(storePos, 3.5, 1.0)) {
					if (this.stuck()) this.finishDelivery();
					return;
				}
				for (ItemStack p : this.products) {
					ItemStack rest = p;
					for (Inventory inv : chests) rest = ColonistBrain.insert(inv, rest);
					if (!rest.isEmpty()) ColonistBrain.give(this.colonist, rest);
				}
				this.products.clear();
				f.foodInStock = Stockpile.count(chests, ColonistBrain.FOOD);
				fm.markDirty();
				world.playSound(null, storePos, SoundEvents.BLOCK_CHEST_CLOSE, SoundCategory.BLOCKS, 0.6f, 1.0f);
				this.done = true;
			}
		}
	}

	private void finishDelivery() {
		for (ItemStack p : this.products) ColonistBrain.give(this.colonist, p);
		this.products.clear();
		this.done = true;
	}

	/** Smelts raw iron, then makes whatever the city's stores are shortest of. */
	private void forge(ServerWorld world, List<Inventory> chests) {
		SimpleInventory inv = this.colonist.getInventory();
		int raw = ColonistBrain.count(inv, Items.RAW_IRON);
		if (raw > 0) {
			ColonistBrain.take(inv, Items.RAW_IRON, raw);
			ColonistBrain.give(this.colonist, new ItemStack(Items.IRON_INGOT, raw));
		}
		int nuggets = ColonistBrain.count(inv, Items.IRON_NUGGET) / 9;
		if (nuggets > 0) {
			ColonistBrain.take(inv, Items.IRON_NUGGET, nuggets * 9);
			ColonistBrain.give(this.colonist, new ItemStack(Items.IRON_INGOT, nuggets));
		}
		if (ColonistBrain.count(inv, Items.STICK) < 2 && ColonistBrain.take(inv, ColonistBrain.PLANKS, 2)) {
			ColonistBrain.give(this.colonist, new ItemStack(Items.STICK, 4));
		}
		// item, iron, sticks, how many the stores should hold
		Object[][] recipes = {
				{Items.IRON_PICKAXE, 3, 2, 2}, {Items.IRON_SWORD, 2, 1, 3}, {Items.IRON_AXE, 3, 2, 2}, {Items.IRON_HOE, 2, 2, 1},
				{Items.IRON_CHESTPLATE, 8, 0, 2}, {Items.IRON_HELMET, 5, 0, 2}, {Items.IRON_LEGGINGS, 7, 0, 2}, {Items.IRON_BOOTS, 4, 0, 2},
				{Items.SHEARS, 2, 0, 1}, {Items.BUCKET, 3, 0, 1}};
		for (int made = 0; made < 3; made++) {
			Object[] pick = null;
			double bestNeed = 0;
			for (Object[] r : recipes) {
				Item item = (Item) r[0];
				int iron = (int) r[1], sticks = (int) r[2], want = (int) r[3];
				if (ColonistBrain.count(inv, Items.IRON_INGOT) < iron || ColonistBrain.count(inv, Items.STICK) < sticks) continue;
				int have = Stockpile.count(chests, s -> s.isOf(item)) + countProduct(item);
				double need = (want - have) / (double) want;
				if (need > bestNeed) {
					bestNeed = need;
					pick = r;
				}
			}
			if (pick == null) break;
			ColonistBrain.take(inv, Items.IRON_INGOT, (int) pick[1]);
			ColonistBrain.take(inv, Items.STICK, (int) pick[2]);
			this.products.add(new ItemStack((Item) pick[0]));
		}
		// Spare ingots go back to the stores too.
		int spare = ColonistBrain.count(inv, Items.IRON_INGOT);
		if (spare > 0) {
			ColonistBrain.take(inv, Items.IRON_INGOT, spare);
			this.products.add(new ItemStack(Items.IRON_INGOT, spare));
		}
		if (!this.products.isEmpty()) this.colonist.say("Fresh from the forge!");
	}

	private int countProduct(Item item) {
		int n = 0;
		for (ItemStack p : this.products) if (p.isOf(item)) n += p.getCount();
		return n;
	}

	private void cook(ServerWorld world) {
		SimpleInventory inv = this.colonist.getInventory();
		int wheat = ColonistBrain.count(inv, Items.WHEAT) / 3;
		if (wheat > 0) {
			ColonistBrain.take(inv, Items.WHEAT, wheat * 3);
			this.products.add(new ItemStack(Items.BREAD, wheat));
		}
		for (Item raw : RAW_FOOD) {
			int n = ColonistBrain.count(inv, raw);
			if (n == 0) continue;
			ItemStack cooked = ColonistBrain.smeltingResult(world, new ItemStack(raw));
			if (cooked.isEmpty()) continue;
			ColonistBrain.take(inv, raw, n);
			this.products.add(cooked.copyWithCount(n * cooked.getCount()));
		}
	}

	@Override
	public void stop() {
		for (ItemStack p : this.products) ColonistBrain.give(this.colonist, p);
		this.products.clear();
	}
}
