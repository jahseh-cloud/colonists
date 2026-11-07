package net.colonists.faction;

import net.colonists.entity.ColonistBrain;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** The city's shared storage: the chests on the plaza. Also the rules for what a colonist keeps on hand. */
public final class Stockpile {
	private Stockpile() {}

	private static final Map<String, Predicate<ItemStack>> CATEGORIES = new LinkedHashMap<>();

	static {
		CATEGORIES.put("tool", ColonistBrain::isTool);
		CATEGORIES.put("food", ColonistBrain.FOOD);
		CATEGORIES.put("planks", ColonistBrain.PLANKS);
		CATEGORIES.put("logs", ColonistBrain.LOGS);
		CATEGORIES.put("stone", ColonistBrain.STONE_MATS);
		CATEGORIES.put("seeds", ColonistBrain.SEEDS);
		CATEGORIES.put("fuel", ColonistBrain.COALS);
		CATEGORIES.put("stick", s -> s.isOf(Items.STICK));
		CATEGORIES.put("torch", s -> s.isOf(Items.TORCH));
		CATEGORIES.put("iron", s -> s.isOf(Items.IRON_INGOT) || s.isOf(Items.RAW_IRON) || s.isOf(Items.DIAMOND));
		CATEGORIES.put("wool", s -> s.isIn(ItemTags.WOOL) || s.isIn(ItemTags.BEDS));
		CATEGORIES.put("sapling", s -> s.isIn(ItemTags.SAPLINGS));
		CATEGORIES.put("dirt", s -> s.isOf(Items.DIRT));
		CATEGORIES.put("wheat", s -> s.isOf(Items.WHEAT));
	}

	public static String category(ItemStack s) {
		for (Map.Entry<String, Predicate<ItemStack>> e : CATEGORIES.entrySet()) {
			if (e.getValue().test(s)) return e.getKey();
		}
		return "other";
	}

	/** How much of a category a colonist likes to carry; the rest goes into storage. */
	public static int allowance(String category) {
		return switch (category) {
			case "tool" -> 64;
			case "food" -> 12;
			case "planks" -> 64;
			case "logs" -> 16;
			case "stone" -> 32;
			case "seeds" -> 16;
			case "fuel", "torch", "stick", "iron", "dirt" -> 16;
			case "wool" -> 6;
			case "sapling" -> 4;
			case "wheat" -> 8;
			default -> 0;
		};
	}

	/** Number of items above the allowances. */
	public static int surplus(Inventory inv) {
		Map<String, Integer> totals = new LinkedHashMap<>();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty()) totals.merge(category(s), s.getCount(), Integer::sum);
		}
		int surplus = 0;
		for (Map.Entry<String, Integer> e : totals.entrySet()) surplus += Math.max(0, e.getValue() - allowance(e.getKey()));
		return surplus;
	}

	/** Moves everything above the allowances from inv into target; returns how many items moved. */
	public static int depositSurplus(Inventory inv, List<Inventory> targets) {
		Map<String, Integer> kept = new LinkedHashMap<>();
		int moved = 0;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty()) continue;
			String category = category(s);
			int allowance = allowance(category) - kept.getOrDefault(category, 0);
			int keep = Math.max(0, Math.min(s.getCount(), allowance));
			kept.merge(category, keep, Integer::sum);
			int store = s.getCount() - keep;
			if (store <= 0) continue;
			ItemStack moving = s.split(store);
			for (Inventory target : targets) {
				if (moving.isEmpty()) break;
				int before = moving.getCount();
				moving = ColonistBrain.insert(target, moving);
				moved += before - moving.getCount();
			}
			if (!moving.isEmpty()) s.increment(moving.getCount());
		}
		inv.markDirty();
		return moved;
	}

	/** The plaza chests that exist and are loaded. */
	public static List<Inventory> chests(ServerWorld world, Settlement f) {
		List<Inventory> list = new ArrayList<>();
		if (f.cityCenter == null) return list;
		for (BlockPos pos : City.stockpileSlots(f.cityCenter)) {
			if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
			BlockEntity be = world.getBlockEntity(pos);
			if (be instanceof Inventory inv) list.add(inv);
		}
		return list;
	}

	public static int count(List<Inventory> chests, Predicate<ItemStack> p) {
		int n = 0;
		for (Inventory inv : chests) n += ColonistBrain.count(inv, p);
		return n;
	}

	/** Takes up to max matching items out of the chests. */
	public static List<ItemStack> take(List<Inventory> chests, Predicate<ItemStack> p, int max) {
		List<ItemStack> out = new ArrayList<>();
		for (Inventory inv : chests) {
			for (int i = 0; i < inv.size() && max > 0; i++) {
				ItemStack s = inv.getStack(i);
				if (s.isEmpty() || !p.test(s)) continue;
				ItemStack taken = s.split(Math.min(max, s.getCount()));
				max -= taken.getCount();
				out.add(taken);
			}
			inv.markDirty();
		}
		return out;
	}
}
