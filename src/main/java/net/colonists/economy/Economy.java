package net.colonists.economy;

import net.colonists.entity.ColonistBrain;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Settlement;
import net.colonists.faction.Stockpile;
import net.colonists.law.Justice;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.minecraft.village.TradedItem;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The emerald economy: purses, the town treasury, wages and taxes, prices that follow supply, the merchant's stall,
 * caravans between friendly towns, and players' shops.
 */
public final class Economy {
	private Economy() {}

	// ---------------------------------------------------------------- purses

	@Nullable
	static Faction.Citizen citizen(ColonistEntity c) {
		FactionManager fm = c.factions();
		Faction f = fm == null ? null : fm.get(c.getFactionId());
		return f == null ? null : f.citizens.get(c.getUuid());
	}

	public static int wealth(ColonistEntity c) {
		Faction.Citizen cit = citizen(c);
		return cit == null ? c.purse : cit.wealth;
	}

	public static void earn(ColonistEntity c, int amount) {
		Faction.Citizen cit = citizen(c);
		if (cit != null) {
			cit.wealth += amount;
			c.purse = cit.wealth;
		} else {
			c.purse += amount;
		}
	}

	public static boolean spend(ColonistEntity c, int amount) {
		if (wealth(c) < amount) return false;
		earn(c, -amount);
		return true;
	}

	public static String classOf(int wealth) {
		return wealth >= 60 ? "Rich" : wealth >= 8 ? "Common" : "Poor";
	}

	// ---------------------------------------------------------------- wages & taxes

	static int wage(String job) {
		return switch (job) {
			case "BLACKSMITH" -> 4;
			case "MINER", "BUILDER", "GUARD", "MERCHANT" -> 3;
			case "FARMER", "WOODCUTTER", "RANCHER", "COOK" -> 2;
			default -> 1;
		};
	}

	public static double defaultTax(Faction f) {
		return switch (f.government) {
			case MONARCHY -> 0.15;
			case REPUBLIC -> 0.08;
			default -> 0.10;
		};
	}

	/** Once per Minecraft day per town: taxes in, wages out. */
	static void payday(MinecraftServer server, FactionManager fm, Faction f, Settlement s, long day) {
		s.lastPayday = day;
		List<Faction.Citizen> people = new ArrayList<>();
		for (Map.Entry<UUID, Faction.Citizen> e : f.citizens.entrySet()) {
			if (e.getValue().settlement == s.id && !e.getValue().child) people.add(e.getValue());
		}
		if (people.isEmpty()) return;
		// Production: the town's farms, workshops and market turn out goods worth this much.
		s.treasury += people.size() * 2 + (s.hasBuilt("market") ? people.size() / 2 : 0);
		// Taxes on wealth (nobles of a monarchy are exempt).
		double rate = s.taxRate >= 0 ? s.taxRate / 100.0 : defaultTax(f);
		if (s.autonomy) rate /= 2;
		for (Faction.Citizen c : people) {
			if (f.government == Faction.Government.MONARCHY && !f.dynasty.isEmpty() && f.dynasty.equals(c.surname)) continue;
			int tax = (int) (c.wealth * rate);
			c.wealth -= tax;
			s.treasury += tax;
		}
		// Wages: the ruler and nobles take more; bonded people get nothing.
		int bill = 0;
		Map<Faction.Citizen, Integer> due = new HashMap<>();
		for (Map.Entry<UUID, Faction.Citizen> e : f.citizens.entrySet()) {
			Faction.Citizen c = e.getValue();
			if (c.settlement != s.id || c.child || c.bonded) continue;
			int w = wage(c.job);
			if (e.getKey().equals(f.ruler)) w += 6;
			else if (!f.dynasty.isEmpty() && f.dynasty.equals(c.surname)) w += 2;
			due.put(c, w);
			bill += w;
		}
		double share = bill == 0 ? 1 : Math.min(1.0, s.treasury / (double) bill);
		for (Map.Entry<Faction.Citizen, Integer> e : due.entrySet()) {
			int paid = (int) Math.floor(e.getValue() * share);
			e.getKey().wealth += paid;
			s.treasury -= paid;
		}
		if (share < 1) {
			s.arrears++;
			f.approval = Math.max(0, f.approval - 2 * s.arrears);
			if (f.atWar) f.warWeariness++;
			if (s.arrears == 2) {
				FactionManager.broadcastNear(server.getOverworld(), s.cityCenter, 160, Text.literal("💰 The treasury of ").formatted(Formatting.GOLD)
						.append(s.displayName()).append(Text.literal(" is empty. Workers go unpaid and grumble.").formatted(Formatting.GOLD)));
			}
		} else {
			s.arrears = 0;
		}
		// Money buys influence in a republic.
		if (f.government == Faction.Government.REPUBLIC) {
			for (Faction.Citizen c : people) if (c.wealth >= 60) c.popularity = Math.min(100, c.popularity + 1);
		}
	}

	// ---------------------------------------------------------------- goods & prices

	public enum Good {
		FOOD(ColonistBrain.FOOD, Items.BREAD, 6, 64),
		WHEAT(s -> s.isOf(Items.WHEAT), Items.WHEAT, 10, 64),
		LOGS(s -> s.isIn(ItemTags.LOGS), Items.OAK_LOG, 8, 64),
		PLANKS(s -> s.isIn(ItemTags.PLANKS), Items.OAK_PLANKS, 24, 64),
		STONE(ColonistBrain.STONE_MATS, Items.COBBLESTONE, 24, 128),
		COAL(s -> s.isOf(Items.COAL) || s.isOf(Items.CHARCOAL), Items.COAL, 6, 32),
		IRON(s -> s.isOf(Items.IRON_INGOT), Items.IRON_INGOT, 1, 24),
		WOOL(s -> s.isIn(ItemTags.WOOL), Items.WHITE_WOOL, 4, 16),
		LEATHER(s -> s.isOf(Items.LEATHER), Items.LEATHER, 4, 16);

		public final Predicate<ItemStack> is;
		/** The item the merchant buys from players. */
		public final Item usual;
		/** Units per emerald at normal supply. */
		public final int perEmerald;
		/** How much the town likes to keep in stock. */
		public final int target;

		Good(Predicate<ItemStack> is, Item usual, int perEmerald, int target) {
			this.is = is;
			this.usual = usual;
			this.perEmerald = perEmerald;
			this.target = target;
		}

		@Nullable
		public static Good of(ItemStack s) {
			for (Good g : values()) if (g.is.test(s)) return g;
			return null;
		}
	}

	/** Units of a good one emerald buys right now: plenty makes it cheap, scarcity (and war, for food) dear. */
	public static int perEmerald(Settlement s, Good g, int stock) {
		double supply = MathHelper.clamp((stock + 8) / (double) g.target, 0.34, 3.0);
		if (g == Good.FOOD && s.faction != null && s.faction.atWar) supply /= 1.5;
		return Math.max(1, (int) Math.round(g.perEmerald * supply));
	}

	// ---------------------------------------------------------------- the merchant's stall

	public static TradeOfferList offers(ServerWorld world, Settlement s) {
		TradeOfferList list = new TradeOfferList();
		List<Inventory> chests = Stockpile.chests(world, s);
		for (Good g : Good.values()) {
			int stock = Stockpile.count(chests, g.is);
			int units = perEmerald(s, g, stock);
			// The town sells what it has plenty of...
			int spare = stock - g.target / 2;
			Item have = mostCommon(chests, g.is);
			if (have != null && spare >= units) {
				int n = Math.min(units, 64);
				list.add(new TradeOffer(new TradedItem(Items.EMERALD, 1), new ItemStack(have, n), MathHelper.clamp(spare / n, 1, 16), 0, 0f));
			}
			// ...and buys what it lacks, if the treasury can pay (with a merchant's margin).
			if (stock < g.target && s.treasury > 0) {
				int n = Math.min(64, (int) Math.ceil(units * 1.25));
				int uses = MathHelper.clamp(Math.min(s.treasury, (g.target - stock) / n + 1), 1, 16);
				list.add(new TradeOffer(new TradedItem(g.usual, n), new ItemStack(Items.EMERALD, 1), uses, 0, 0f));
			}
		}
		return list;
	}

	/** After a trade at the stall: the goods and the money move between the player and the town. */
	public static void traded(ServerWorld world, Settlement s, TradeOffer offer) {
		List<Inventory> chests = Stockpile.chests(world, s);
		ItemStack paid = offer.getOriginalFirstBuyItem();
		ItemStack got = offer.getSellItem();
		if (paid.isOf(Items.EMERALD)) {
			s.treasury += paid.getCount();
			Stockpile.take(chests, st -> st.isOf(got.getItem()), got.getCount());
		} else {
			s.treasury = Math.max(0, s.treasury - got.getCount());
			ItemStack rest = paid.copy();
			for (Inventory inv : chests) {
				rest = ColonistBrain.insert(inv, rest);
				if (rest.isEmpty()) break;
			}
		}
	}

	@Nullable
	static Item mostCommon(List<Inventory> chests, Predicate<ItemStack> p) {
		Map<Item, Integer> counts = new HashMap<>();
		for (Inventory inv : chests) {
			for (int i = 0; i < inv.size(); i++) {
				ItemStack st = inv.getStack(i);
				if (!st.isEmpty() && p.test(st)) counts.merge(st.getItem(), st.getCount(), Integer::sum);
			}
		}
		return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
	}

	// ---------------------------------------------------------------- ticking

	/** Called every diplomacy tick (once a minute). */
	public static void tick(MinecraftServer server, FactionManager fm) {
		ServerWorld world = server.getOverworld();
		long day = world.getTimeOfDay() / 24000L;
		long now = world.getTime();
		for (Faction f : fm.all()) {
			for (Settlement s : f.settlements.values()) {
				if (s.lastPayday < 0) s.lastPayday = day;
				if (s.lastPayday < day) payday(server, fm, f, s, day);
			}
		}
		if (now % 12000 < 1200) caravans(world, fm);
		if (now % 6000 < 1200) shops(world, fm, Justice.get(server));
		fm.markDirty();
	}

	/** Friendly towns (same realm, or allies) even out food and materials, paying for what they receive. */
	static void caravans(ServerWorld world, FactionManager fm) {
		List<Settlement> all = fm.allSettlements();
		for (Good g : new Good[]{Good.FOOD, Good.LOGS, Good.STONE, Good.IRON}) {
			Settlement rich = null, poor = null;
			int richStock = 0, poorStock = Integer.MAX_VALUE;
			for (Settlement s : all) {
				if (s.faction == null || s.cityCenter == null) continue;
				int stock = Stockpile.count(Stockpile.chests(world, s), g.is);
				if (stock > richStock) {
					richStock = stock;
					rich = s;
				}
			}
			if (rich == null || richStock < g.target) continue;
			for (Settlement s : all) {
				if (s == rich || s.faction == null || s.cityCenter == null) continue;
				boolean friendly = s.faction == rich.faction || fm.relation(s.faction.id, rich.faction.id) >= FactionManager.ALLY;
				if (!friendly || s.cityCenter.getSquaredDistance(rich.cityCenter) > 800 * 800) continue;
				int stock = Stockpile.count(Stockpile.chests(world, s), g.is);
				if (stock < poorStock) {
					poorStock = stock;
					poor = s;
				}
			}
			if (poor == null || poorStock >= g.target / 2) continue;
			List<Inventory> to = Stockpile.chests(world, poor);
			if (to.isEmpty()) continue;
			int amount = Math.min(32, richStock - g.target / 2);
			int moved = 0;
			for (ItemStack st : Stockpile.take(Stockpile.chests(world, rich), g.is, amount)) {
				moved += st.getCount();
				ItemStack rest = st;
				for (Inventory inv : to) {
					rest = ColonistBrain.insert(inv, rest);
					if (rest.isEmpty()) break;
				}
			}
			if (moved == 0) continue;
			int cost = Math.max(1, moved / Math.max(1, perEmerald(rich, g, richStock)));
			int paid = Math.min(cost, poor.treasury);
			poor.treasury -= paid;
			rich.treasury += paid;
			FactionManager.broadcastNear(world, poor.cityCenter, 128, Text.literal("🐎 A caravan from ").formatted(Formatting.GRAY)
					.append(rich.displayName()).append(Text.literal(" brings " + moved + " " + g.name().toLowerCase() + " to ").formatted(Formatting.GRAY))
					.append(poor.displayName()).append(Text.literal(" (" + paid + " emeralds).").formatted(Formatting.GRAY)));
		}
	}

	/** Towns buy what they are short of from players' shop chests, if the asking price is fair. */
	static void shops(ServerWorld world, FactionManager fm, Justice j) {
		for (Justice.Shop shop : new ArrayList<>(j.shops.values())) {
			if (!world.isChunkLoaded(shop.pos.getX() >> 4, shop.pos.getZ() >> 4)) continue;
			if (!(world.getBlockEntity(shop.pos) instanceof Inventory chest)) {
				j.shops.remove(shop.pos);
				j.markDirty();
				continue;
			}
			Settlement s = fm.settlement(shop.settlement);
			if (s == null) continue;
			List<Inventory> stores = Stockpile.chests(world, s);
			int bought = 0, spent = 0;
			for (int slot = 0; slot < chest.size() && spent < 64; slot++) {
				ItemStack st = chest.getStack(slot);
				if (st.isEmpty() || st.isOf(Items.EMERALD)) continue;
				Good g = Good.of(st);
				if (g == null) continue;
				int stock = Stockpile.count(stores, g.is);
				if (stock >= g.target) continue;
				// Fair: no more than 25% over the market price.
				double fair = shop.batch / (double) perEmerald(s, g, stock) * 1.25;
				if (shop.price > fair || s.treasury < shop.price) continue;
				while (st.getCount() >= shop.batch && s.treasury >= shop.price && stock < g.target && spent < 64) {
					ItemStack batch = st.split(shop.batch);
					for (Inventory inv : stores) {
						batch = ColonistBrain.insert(inv, batch);
						if (batch.isEmpty()) break;
					}
					s.treasury -= shop.price;
					spent += shop.price;
					bought += shop.batch;
					stock += shop.batch;
				}
				chest.setStack(slot, st);
			}
			if (spent == 0) continue;
			ItemStack pay = new ItemStack(Items.EMERALD, spent);
			pay = ColonistBrain.insert(chest, pay);
			if (!pay.isEmpty()) s.treasury += pay.getCount(); // no room: the money stays in the treasury
			shop.earned += spent - pay.getCount();
			chest.markDirty();
			j.markDirty();
			ServerPlayerEntity owner = world.getServer().getPlayerManager().getPlayer(shop.owner);
			if (owner != null) {
				owner.sendMessage(Text.literal("🛒 The people of ").formatted(Formatting.GREEN).append(s.displayName())
						.append(Text.literal(" bought " + bought + " items from your shop for " + (spent - pay.getCount()) + " emeralds.").formatted(Formatting.GREEN)), false);
			}
		}
	}

	/** Registers the chest a player is looking at as a shop of the town it stands in. */
	@Nullable
	public static String openShop(ServerWorld world, ServerPlayerEntity player, BlockPos pos, int price, int batch) {
		if (!(world.getBlockEntity(pos) instanceof Inventory)) return "Look at a chest or barrel.";
		FactionManager fm = FactionManager.get(world.getServer());
		Settlement s = net.colonists.law.Courts.territoryAt(fm, pos, world.getTime());
		if (s == null) return "Your shop has to be inside a colonist town.";
		Justice j = Justice.get(world.getServer());
		Justice.Shop shop = new Justice.Shop();
		shop.pos = pos.toImmutable();
		shop.owner = player.getUuid();
		shop.ownerName = player.getName().getString();
		shop.settlement = s.id;
		shop.price = Math.max(1, price);
		shop.batch = Math.max(1, batch);
		j.shops.put(shop.pos, shop);
		j.markDirty();
		return null;
	}
}
