package net.colonists.entity;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.world.World;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Inventory helpers and the colonists' "virtual crafting table". */
public final class ColonistBrain {
	private ColonistBrain() {}

	// Tool families, index = tier (0 wood, 1 stone, 2 iron, 3 diamond, 4 netherite).
	public static final Item[] SWORDS = {Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD};
	public static final Item[] PICKAXES = {Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE};
	public static final Item[] AXES = {Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE};
	public static final Item[] HOES = {Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE};
	public static final int WOOD = 0, STONE = 1, IRON = 2, DIAMOND = 3;

	public static final Predicate<ItemStack> PLANKS = s -> s.isIn(ItemTags.PLANKS);
	public static final Predicate<ItemStack> LOGS = s -> s.isIn(ItemTags.LOGS);
	public static final Predicate<ItemStack> STONE_MATS = s -> s.isIn(ItemTags.STONE_TOOL_MATERIALS);
	public static final Predicate<ItemStack> COALS = s -> s.isOf(Items.COAL) || s.isOf(Items.CHARCOAL);
	public static final Predicate<ItemStack> SEEDS = s -> s.isOf(Items.WHEAT_SEEDS) || s.isOf(Items.CARROT) || s.isOf(Items.POTATO) || s.isOf(Items.BEETROOT_SEEDS);
	public static final Predicate<ItemStack> FOOD = s -> s.contains(DataComponentTypes.FOOD) && !isBadFood(s);

	private static final Set<Item> BAD_FOOD = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO,
			Items.PUFFERFISH, Items.SUSPICIOUS_STEW, Items.CHORUS_FRUIT, Items.CHICKEN, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE);

	public static boolean isBadFood(ItemStack s) {
		return BAD_FOOD.contains(s.getItem());
	}

	public static boolean isTool(ItemStack s) {
		Item item = s.getItem();
		for (Item[] family : List.of(SWORDS, PICKAXES, AXES, HOES)) {
			for (Item i : family) if (i == item) return true;
		}
		return false;
	}

	// ---------------------------------------------------------------- inventory helpers

	public static int count(Inventory inv, Predicate<ItemStack> p) {
		int n = 0;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty() && p.test(s)) n += s.getCount();
		}
		return n;
	}

	public static int count(Inventory inv, Item item) {
		return count(inv, s -> s.isOf(item));
	}

	/** Removes n matching items; does nothing and returns false if there are not enough. */
	public static boolean take(Inventory inv, Predicate<ItemStack> p, int n) {
		if (count(inv, p) < n) return false;
		for (int i = 0; i < inv.size() && n > 0; i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty() && p.test(s)) {
				int t = Math.min(n, s.getCount());
				s.decrement(t);
				n -= t;
			}
		}
		inv.markDirty();
		return true;
	}

	public static boolean take(Inventory inv, Item item, int n) {
		return take(inv, s -> s.isOf(item), n);
	}

	/** Removes and returns a single matching item (count 1), or EMPTY. */
	public static ItemStack takeOne(Inventory inv, Predicate<ItemStack> p) {
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty() && p.test(s)) {
				ItemStack one = s.copyWithCount(1);
				s.decrement(1);
				inv.markDirty();
				return one;
			}
		}
		return ItemStack.EMPTY;
	}

	public static ItemStack findFirst(Inventory inv, Predicate<ItemStack> p) {
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty() && p.test(s)) return s;
		}
		return ItemStack.EMPTY;
	}

	public static void give(ColonistEntity c, ItemStack stack) {
		if (stack.isEmpty()) return;
		ItemStack rest = c.getInventory().addStack(stack);
		if (!rest.isEmpty()) c.dropStack(rest);
	}

	/** Moves as much of stack as fits into inv; returns what is left. */
	public static ItemStack insert(Inventory inv, ItemStack stack) {
		for (int i = 0; i < inv.size() && !stack.isEmpty(); i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty() && ItemStack.areItemsAndComponentsEqual(s, stack)) {
				int move = Math.min(stack.getCount(), Math.min(s.getMaxCount(), inv.getMaxCountPerStack()) - s.getCount());
				if (move > 0) {
					s.increment(move);
					stack.decrement(move);
				}
			}
		}
		for (int i = 0; i < inv.size() && !stack.isEmpty(); i++) {
			if (inv.getStack(i).isEmpty()) {
				inv.setStack(i, stack.copy());
				stack.setCount(0);
			}
		}
		inv.markDirty();
		return stack;
	}

	public static int emptySlots(SimpleInventory inv) {
		int n = 0;
		for (int i = 0; i < inv.size(); i++) if (inv.getStack(i).isEmpty()) n++;
		return n;
	}

	/** Highest tier of a tool family the colonist owns, or -1. */
	public static int tier(Inventory inv, Item[] family) {
		for (int t = family.length - 1; t >= 0; t--) {
			if (count(inv, family[t]) > 0) return t;
		}
		return -1;
	}

	public static ItemStack best(Inventory inv, Item[] family) {
		int t = tier(inv, family);
		return t < 0 ? ItemStack.EMPTY : new ItemStack(family[t]);
	}

	public static ItemStack bestWeapon(Inventory inv) {
		int sword = tier(inv, SWORDS);
		int axe = tier(inv, AXES);
		if (sword < 0 && axe < 0) return ItemStack.EMPTY;
		return sword >= axe ? new ItemStack(SWORDS[sword]) : new ItemStack(AXES[axe]);
	}

	/** Would this item (a tool or piece of armour) be an upgrade over what the colonist has? */
	public static boolean isUpgrade(ColonistEntity c, ItemStack s) {
		Inventory inv = c.getInventory();
		for (Item[] family : List.of(SWORDS, PICKAXES, AXES)) {
			for (int t = 0; t < family.length; t++) {
				if (s.isOf(family[t])) return t > tier(inv, family);
			}
		}
		if (s.getItem() instanceof ArmorItem armor) {
			ItemStack worn = c.getEquippedStack(armor.getSlotType());
			int wornProt = worn.getItem() instanceof ArmorItem w ? w.getProtection() : -1;
			return armor.getProtection() > wornProt;
		}
		return false;
	}

	public static int woodValue(Inventory inv) {
		return count(inv, PLANKS) + count(inv, LOGS) * 4;
	}

	// ---------------------------------------------------------------- recipes

	public static ItemStack craftingResult(World world, List<ItemStack> grid, int width, int height) {
		CraftingRecipeInput input = CraftingRecipeInput.create(width, height, grid);
		return world.getRecipeManager().getFirstMatch(RecipeType.CRAFTING, input, world)
				.map(entry -> entry.value().getResult(world.getRegistryManager()).copy())
				.orElse(ItemStack.EMPTY);
	}

	public static ItemStack smeltingResult(World world, ItemStack in) {
		SingleStackRecipeInput input = new SingleStackRecipeInput(in.copyWithCount(1));
		return world.getRecipeManager().getFirstMatch(RecipeType.SMELTING, input, world)
				.map(entry -> entry.value().getResult(world.getRegistryManager()).copy())
				.orElse(ItemStack.EMPTY);
	}

	private static boolean worthSmelting(ItemStack in, ItemStack out) {
		if (out.isEmpty()) return false;
		if (in.contains(DataComponentTypes.FOOD) && out.contains(DataComponentTypes.FOOD)) return true;
		return out.isOf(Items.IRON_INGOT) || out.isOf(Items.GOLD_INGOT);
	}

	// ---------------------------------------------------------------- the crafting routine

	/** Called every few seconds: turns raw materials into useful things, like a player at a crafting table. */
	public static void craft(ColonistEntity c) {
		SimpleInventory inv = c.getInventory();
		World world = c.getWorld();

		// Logs -> planks (keep some logs for house corner pillars).
		int keepLogs = c.home == null ? 12 : 4;
		for (int guard = 0; guard < 16; guard++) {
			int planksHave = count(inv, PLANKS), logsHave = count(inv, LOGS);
			boolean surplus = planksHave < 64 && logsHave > keepLogs;
			boolean needForTools = planksHave < 12 && logsHave > 0;
			if (!surplus && !needForTools) break;
			ItemStack log = takeOne(inv, LOGS);
			ItemStack planks = craftingResult(world, List.of(log), 1, 1);
			give(c, planks.isEmpty() || !planks.isIn(ItemTags.PLANKS) ? new ItemStack(Items.OAK_PLANKS, 4) : planks);
		}

		// Sticks.
		if (count(inv, Items.STICK) < 8 && take(inv, PLANKS, 2)) give(c, new ItemStack(Items.STICK, 4));

		// Tools: always try to upgrade to the best tier we have materials for.
		upgrade(c, SWORDS, 2, 1);
		upgrade(c, PICKAXES, 3, 2);
		upgrade(c, AXES, 3, 2);
		if (c.home != null) upgrade(c, HOES, 2, 2);

		// Iron armour once the essentials are iron.
		if (tier(inv, PICKAXES) >= IRON && tier(inv, SWORDS) >= IRON) {
			craftArmor(c, EquipmentSlot.CHEST, Items.IRON_CHESTPLATE, 8);
			craftArmor(c, EquipmentSlot.LEGS, Items.IRON_LEGGINGS, 7);
			craftArmor(c, EquipmentSlot.HEAD, Items.IRON_HELMET, 5);
			craftArmor(c, EquipmentSlot.FEET, Items.IRON_BOOTS, 4);
		}

		// Smelting with whatever fuel is around.
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty()) continue;
			ItemStack out = smeltingResult(world, s);
			if (!worthSmelting(s, out)) continue;
			int n = Math.min(s.getCount(), 8);
			for (int k = 0; k < n && useFuel(c); k++) {
				s.decrement(1);
				give(c, out.copy());
			}
		}

		// Charcoal for torches when there is no coal.
		if (c.home != null && count(inv, COALS) == 0 && count(inv, Items.TORCH) < 4 && count(inv, LOGS) > 2 && useFuel(c)) {
			take(inv, LOGS, 1);
			give(c, new ItemStack(Items.CHARCOAL));
		}
		if (count(inv, Items.TORCH) < 8 && count(inv, Items.STICK) >= 1 && take(inv, COALS, 1)) {
			take(inv, Items.STICK, 1);
			give(c, new ItemStack(Items.TORCH, 4));
		}

		// Bread.
		while (count(inv, Items.WHEAT) >= 3 && count(inv, Items.BREAD) < 16) {
			take(inv, Items.WHEAT, 3);
			give(c, new ItemStack(Items.BREAD));
		}

		// A bed, once there is a house to put it in.
		if (c.home != null && c.bed == null && count(inv, s -> s.isIn(ItemTags.BEDS)) == 0
				&& count(inv, s -> s.isIn(ItemTags.WOOL)) >= 3 && count(inv, PLANKS) >= 3) {
			ItemStack wool = findFirst(inv, s -> s.isIn(ItemTags.WOOL) && s.getCount() >= 3);
			ItemStack planks = findFirst(inv, PLANKS);
			ItemStack bed = ItemStack.EMPTY;
			if (!wool.isEmpty() && !planks.isEmpty()) {
				ItemStack w = wool.copyWithCount(1), p = planks.copyWithCount(1);
				bed = craftingResult(world, List.of(w, w, w, p, p, p), 3, 2);
				if (!bed.isEmpty()) take(inv, s -> ItemStack.areItemsEqual(s, w), 3);
			}
			if (bed.isEmpty() || !bed.isIn(ItemTags.BEDS)) {
				if (!take(inv, s -> s.isIn(ItemTags.WOOL), 3)) bed = ItemStack.EMPTY;
				else bed = new ItemStack(Items.WHITE_BED);
			}
			if (!bed.isEmpty()) {
				take(inv, PLANKS, 3);
				give(c, bed);
			}
		}

		equipBestArmor(c);
	}

	private static Predicate<ItemStack> materialFor(int tier) {
		return switch (tier) {
			case WOOD -> PLANKS;
			case STONE -> STONE_MATS;
			case IRON -> s -> s.isOf(Items.IRON_INGOT);
			case DIAMOND -> s -> s.isOf(Items.DIAMOND);
			default -> s -> false;
		};
	}

	private static void upgrade(ColonistEntity c, Item[] family, int material, int sticks) {
		SimpleInventory inv = c.getInventory();
		int current = tier(inv, family);
		for (int t = DIAMOND; t > current; t--) {
			Predicate<ItemStack> mat = materialFor(t);
			int reserve = t == WOOD ? 0 : (t == IRON ? 0 : 0);
			if (count(inv, mat) >= material + reserve && count(inv, Items.STICK) >= sticks) {
				take(inv, mat, material);
				take(inv, Items.STICK, sticks);
				for (int lower = 0; lower < t; lower++) take(inv, family[lower], count(inv, family[lower]));
				give(c, new ItemStack(family[t]));
				return;
			}
		}
	}

	private static void craftArmor(ColonistEntity c, EquipmentSlot slot, Item item, int cost) {
		SimpleInventory inv = c.getInventory();
		ItemStack worn = c.getEquippedStack(slot);
		if (!worn.isEmpty() || count(inv, s -> s.getItem() instanceof ArmorItem a && a.getSlotType() == slot) > 0) return;
		if (count(inv, Items.IRON_INGOT) >= cost + 3 && take(inv, Items.IRON_INGOT, cost)) give(c, new ItemStack(item));
	}

	/** Puts on any armour in the inventory that beats what is currently worn. */
	public static void equipBestArmor(ColonistEntity c) {
		SimpleInventory inv = c.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!(s.getItem() instanceof ArmorItem armor)) continue;
			EquipmentSlot slot = armor.getSlotType();
			ItemStack worn = c.getEquippedStack(slot);
			int wornProt = worn.getItem() instanceof ArmorItem w ? w.getProtection() : -1;
			if (armor.getProtection() > wornProt) {
				ItemStack wearing = s.copyWithCount(1);
				s.decrement(1);
				if (!worn.isEmpty()) give(c, worn.copy());
				c.equipStack(slot, wearing);
			}
		}
	}

	private static boolean useFuel(ColonistEntity c) {
		if (c.fuel > 0) {
			c.fuel--;
			return true;
		}
		SimpleInventory inv = c.getInventory();
		if (take(inv, COALS, 1)) {
			c.fuel = 7;
			return true;
		}
		if (count(inv, PLANKS) > 24 && take(inv, PLANKS, 2)) {
			c.fuel = 2;
			return true;
		}
		return false;
	}
}
