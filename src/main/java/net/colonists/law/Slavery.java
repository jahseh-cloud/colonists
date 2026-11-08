package net.colonists.law;

import net.colonists.ColonistsMod;
import net.colonists.economy.Economy;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Settlement;
import net.colonists.faction.Stockpile;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

/**
 * Slave auctions, owners and estates. An enslaved player is bound to a few chunks around their owner's estate,
 * gets a task every day, and can win freedom by paying their price, by escaping to a free town, by their owner's
 * will, or when the law changes.
 */
public final class Slavery {
	private Slavery() {}

	/** Blocks from the estate centre a slave may go (about three chunks). */
	public static final int ESTATE_RADIUS = 48;
	static final int AUCTION_TICKS = 1200;

	private static final Object[][] TASKS = {
			{Items.OAK_LOG, 32, "Fell timber: bring "},
			{Items.WHEAT, 32, "Bring in the harvest: "},
			{Items.COBBLESTONE, 64, "Break stone: "},
			{Items.COAL, 12, "Dig coal: "},
			{Items.IRON_INGOT, 4, "Smelt iron: "},
	};

	public static void register() {
		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
			if (!(world instanceof ServerWorld sw)) return true;
			Justice.Slave s = Justice.get(sw.getServer()).slaves.get(player.getUuid());
			if (s == null || s.runaway) return true;
			boolean inside = horizontal(pos, s.estate) <= ESTATE_RADIUS;
			if (!inside) player.sendMessage(Text.literal("You may only work your master's land.").formatted(Formatting.DARK_RED), true);
			return inside;
		});
	}

	static double horizontal(BlockPos a, BlockPos b) {
		return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
	}

	public static boolean canEnslave(ServerWorld world, LivingEntity e) {
		if (e instanceof ServerPlayerEntity) return world.getGameRules().getBoolean(ColonistsMod.ENSLAVE_PLAYERS);
		return e instanceof ColonistEntity c && !c.isBaby();
	}

	// ---------------------------------------------------------------- auctions

	/** Puts a convict up for auction at the town market (or plaza), holding them there until it closes. */
	static void sell(ServerWorld world, Justice j, Faction f, Settlement s, LivingEntity e, String name) {
		BlockPos market = s.builtOf("market");
		BlockPos where = market != null ? s.site(market).at(net.colonists.build.Blueprints.MARKET.workstation)
				: s.cityCenter != null ? s.cityCenter : e.getBlockPos();
		Justice.Auction a = new Justice.Auction();
		a.lot = e.getUuid();
		a.player = e instanceof PlayerEntity;
		a.name = name;
		a.faction = f.id;
		a.settlement = s.id;
		a.where = where.toImmutable();
		a.closes = world.getTime() + AUCTION_TICKS;
		a.minimum = a.player ? 40 : 20;
		j.auctions.put(a.lot, a);
		Courts.imprison(world, j, f, s, e, name, Justice.Sentence.SLAVE, where, 0);
		FactionManager.broadcast(world.getServer(), Text.literal("🔨 Slave auction in ").formatted(Formatting.DARK_RED).append(s.displayName())
				.append(Text.literal(": " + name + ", bidding starts at " + a.minimum + " emeralds. Use /colony bid " + a.minimum
						+ " (or more) within a minute.").formatted(Formatting.DARK_RED)));
		if (e instanceof ServerPlayerEntity sp) {
			sp.sendMessage(Text.literal("⛓ You are to be sold at auction. Whoever buys you will own you.").formatted(Formatting.RED), false);
		}
	}

	/** A player's bid on the auction closing soonest. */
	@Nullable
	public static String bid(ServerPlayerEntity player, int amount) {
		Justice j = Justice.get(player.getServer());
		Justice.Auction a = j.auctions.values().stream().filter(x -> !x.lot.equals(player.getUuid()))
				.min((x, y) -> Long.compare(x.closes, y.closes)).orElse(null);
		if (a == null) return "There is no auction open right now.";
		if (amount < a.minimum || amount <= a.bestBid) return "You must bid at least " + Math.max(a.minimum, a.bestBid + 1) + " emeralds.";
		if (player.getInventory().count(Items.EMERALD) < amount) return "You don't have " + amount + " emeralds.";
		a.bestBid = amount;
		a.bidder = player.getUuid();
		a.bidderPlayer = true;
		a.bidderName = player.getName().getString();
		j.markDirty();
		FactionManager.broadcastNear((ServerWorld) player.getWorld(), a.where, 192, Text.literal("🔨 " + a.bidderName + " bids " + amount
				+ " emeralds for " + a.name + ".").formatted(Formatting.GOLD));
		return null;
	}

	static void close(ServerWorld world, Justice j, FactionManager fm, Justice.Auction a) {
		j.auctions.remove(a.lot);
		j.prisoners.remove(a.lot);
		j.markDirty();
		Settlement s = fm.settlement(a.settlement);
		Faction f = fm.get(a.faction);
		if (s == null || f == null) return;
		LivingEntity lot = a.player ? world.getServer().getPlayerManager().getPlayer(a.lot)
				: world.getEntity(a.lot) instanceof ColonistEntity c ? c : null;

		// The town's rich bid against any player bid.
		UUID best = null;
		int bestOffer = Math.max(a.minimum - 1, a.bestBid);
		for (Map.Entry<UUID, Faction.Citizen> e : f.citizens.entrySet()) {
			Faction.Citizen c = e.getValue();
			if (c.settlement != s.id || c.child || c.bonded || c.kindness >= 60 || c.ambition < 40 || e.getKey().equals(a.lot)) continue;
			int willing = (int) Math.min(c.wealth, a.minimum * (1 + c.ambition / 100.0) * (a.player ? 1.5 : 1.0));
			if (willing > bestOffer) {
				bestOffer = willing;
				best = e.getKey();
			}
		}
		UUID owner = null;
		boolean ownerPlayer = false;
		String ownerName = "";
		int price = a.minimum;
		if (best != null) {
			Faction.Citizen c = f.citizens.get(best);
			price = bestOffer;
			c.wealth -= price;
			s.treasury += price;
			owner = best;
			ownerName = c.fullName();
		} else if (a.bidder != null) {
			ServerPlayerEntity buyer = world.getServer().getPlayerManager().getPlayer(a.bidder);
			if (buyer != null && Inventories.remove(buyer.getInventory(), st -> st.isOf(Items.EMERALD), a.bestBid, true) >= a.bestBid) {
				Inventories.remove(buyer.getInventory(), st -> st.isOf(Items.EMERALD), a.bestBid, false);
				price = a.bestBid;
				s.treasury += price;
				owner = buyer.getUuid();
				ownerPlayer = true;
				ownerName = buyer.getName().getString();
			}
		}

		Justice.Slave sl = new Justice.Slave();
		sl.who = a.lot;
		sl.player = a.player;
		sl.name = a.name;
		sl.faction = f.id;
		sl.settlement = s.id;
		sl.owner = owner;
		sl.ownerPlayer = ownerPlayer;
		sl.ownerName = ownerName;
		sl.price = price;
		sl.estate = estateOf(world, sl, s);
		j.slaves.put(sl.who, sl);
		j.markDirty();
		if (!a.player) {
			Faction.Citizen c = f.citizens.get(a.lot);
			if (c != null) c.bonded = true;
		}
		if (lot != null) Courts.teleport(lot, sl.estate);
		FactionManager.broadcastNear(world, a.where, 256, Text.literal("🔨 " + a.name + " was sold to ").formatted(Formatting.DARK_RED)
				.append(Text.literal(owner == null ? "the town of " + s.name : ownerName).formatted(Formatting.GOLD))
				.append(Text.literal(" for " + price + " emeralds.").formatted(Formatting.DARK_RED)));
		if (lot instanceof ServerPlayerEntity sp) {
			sp.sendMessage(Text.literal("⛓ You now belong to " + (owner == null ? "the town of " + s.name : ownerName)
					+ ". Stay within " + ESTATE_RADIUS + " blocks of the estate at " + sl.estate.toShortString()
					+ ". You will be given a task each day. Your freedom costs " + freedomPrice(sl) + " emeralds (/colony freedom).").formatted(Formatting.RED), false);
		}
	}

	/** Where a slave is bound: the owner's house (an estate, if they have built one), or the town plaza. */
	static BlockPos estateOf(ServerWorld world, Justice.Slave sl, Settlement s) {
		if (sl.owner != null && sl.ownerPlayer) {
			if (!sl.estate.equals(BlockPos.ORIGIN)) return sl.estate;
			ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(sl.owner);
			if (p != null) return p.getBlockPos().toImmutable();
		} else if (sl.owner != null && world.getEntity(sl.owner) instanceof ColonistEntity c && c.home != null) {
			return net.colonists.ai.task.BuildStructureTask.doorstep(c, c.home);
		}
		BlockPos camp = s.builtOf("work_camp");
		if (camp != null) return s.site(camp).at(net.colonists.build.Blueprints.WORK_CAMP.workstation);
		return s.cityCenter != null ? s.cityCenter : sl.estate;
	}

	public static int freedomPrice(Justice.Slave sl) {
		return Math.max(10, (int) (sl.price * 1.5));
	}

	// ---------------------------------------------------------------- daily life (every second)

	static void tick(MinecraftServer server, ServerWorld world, Justice j, FactionManager fm, long now) {
		for (Justice.Auction a : new ArrayList<>(j.auctions.values())) {
			if (now >= a.closes) close(world, j, fm, a);
		}
		long day = world.getTimeOfDay() / 24000L;
		for (Justice.Slave sl : new ArrayList<>(j.slaves.values())) {
			Faction f = fm.get(sl.faction);
			if (f == null || !f.slavery) {
				free(world, j, fm, sl, "Slavery has been abolished. You are free!");
				continue;
			}
			if (!sl.player) continue; // colonist slaves live at the estate through their planner
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(sl.who);
			if (p == null || !p.isAlive()) continue;
			Settlement s = fm.settlement(sl.settlement);
			if (s != null && now % 200 == 0) sl.estate = estateOf(world, sl, s);
			double d = horizontal(p.getBlockPos(), sl.estate);

			// Refuge in a free town.
			if (d > ESTATE_RADIUS) {
				Settlement haven = Courts.territoryAt(fm, p.getBlockPos(), now);
				if (haven != null && haven.faction != null && haven.faction != f && !haven.faction.slavery) {
					fm.changeRelation(server, haven.faction.id, f.id, -15);
					j.clear(f.id, sl.who);
					free(world, j, fm, sl, "You have found refuge in " + haven.name + ". The " + haven.faction.name + " refuse to hand you back.");
					FactionManager.broadcast(server, Text.literal("🕊 " + sl.name + " fled slavery among the ").formatted(Formatting.GOLD)
							.append(f.displayName()).append(Text.literal(" and found refuge in ").formatted(Formatting.GOLD)).append(haven.displayName())
							.append(Text.literal(". The two peoples are at odds over it.").formatted(Formatting.GOLD)));
					continue;
				}
				if (!sl.runaway) {
					if (sl.warned == 0) {
						sl.warned = now;
						p.sendMessage(Text.literal("⚠ You have strayed from your master's estate. Go back, or you will be hunted as a runaway!").formatted(Formatting.RED), false);
					} else if (now - sl.warned > 200) {
						sl.runaway = true;
						Justice.Record r = j.recordFor(f.id, sl.who);
						r.name = sl.name;
						r.player = true;
						r.heat += Law.Crime.RUNAWAY.heat;
						r.charges.add(Law.Crime.RUNAWAY.label);
						r.lastSeen = now;
						FactionManager.broadcastNear(world, sl.estate, 256, Text.literal("🏃 " + sl.name + " has run away from "
								+ (sl.ownerName.isEmpty() ? "the town" : sl.ownerName) + "! The guards are after them.").formatted(Formatting.GOLD));
					}
				}
			} else {
				sl.warned = 0;
			}
			if (sl.runaway) {
				p.sendMessage(Text.literal("🏃 Runaway slave — reach a town that forbids slavery to be free").formatted(Formatting.RED), true);
				continue;
			}

			// The day's task.
			if (sl.taskDay != day) {
				if (sl.taskDay >= 0 && sl.taskDone < sl.taskCount) {
					sl.missed++;
					p.damage(world.getDamageSources().generic(), 4f);
					p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 2400, 1));
					p.sendMessage(Text.literal("Your master is displeased that yesterday's task was not done.").formatted(Formatting.DARK_RED), false);
				}
				Object[] t = TASKS[world.getRandom().nextInt(TASKS.length)];
				sl.taskItem = Registries.ITEM.getId((Item) t[0]).toString();
				sl.taskCount = (Integer) t[1];
				sl.taskDone = 0;
				sl.taskDay = day;
				p.sendMessage(Text.literal("📜 Today's task: " + t[2] + sl.taskCount + " " + itemName(sl.taskItem) + " to the estate.").formatted(Formatting.YELLOW), false);
			}
			if (sl.taskDone < sl.taskCount && d <= 6) deliver(world, j, fm, sl, p, s);
			if (now % 100 == 0) {
				p.sendMessage(Text.literal("⛓ Slave of " + (sl.ownerName.isEmpty() ? "the town" : sl.ownerName) + " — task: " + sl.taskDone + "/"
						+ sl.taskCount + " " + itemName(sl.taskItem) + " — " + (int) d + "/" + ESTATE_RADIUS + " blocks from the estate").formatted(Formatting.GRAY), true);
			}
		}
		j.markDirty();
	}

	static String itemName(String id) {
		Item item = Registries.ITEM.get(Identifier.of(id));
		return item.getName().getString().toLowerCase();
	}

	private static void deliver(ServerWorld world, Justice j, FactionManager fm, Justice.Slave sl, ServerPlayerEntity p, @Nullable Settlement s) {
		Item item = Registries.ITEM.get(Identifier.of(sl.taskItem));
		int n = Inventories.remove(p.getInventory(), st -> st.isOf(item), sl.taskCount - sl.taskDone, false);
		if (n <= 0) return;
		sl.taskDone += n;
		ItemStack goods = new ItemStack(item, n);
		ColonistEntity owner = sl.owner != null && !sl.ownerPlayer && world.getEntity(sl.owner) instanceof ColonistEntity c ? c : null;
		if (owner != null && owner.chest != null && world.getBlockEntity(owner.chest) instanceof Inventory chest) {
			goods = net.colonists.entity.ColonistBrain.insert(chest, goods);
		} else if (sl.ownerPlayer) {
			ServerPlayerEntity master = world.getServer().getPlayerManager().getPlayer(sl.owner);
			if (master != null) {
				master.giveItemStack(goods);
				goods = ItemStack.EMPTY;
			}
		}
		if (!goods.isEmpty() && s != null) {
			for (Inventory inv : Stockpile.chests(world, s)) {
				goods = net.colonists.entity.ColonistBrain.insert(inv, goods);
				if (goods.isEmpty()) break;
			}
		}
		if (owner != null) Economy.earn(owner, Math.max(1, n / 8));
		if (sl.taskDone >= sl.taskCount) {
			p.sendMessage(Text.literal("Task done. Your master is satisfied — for today.").formatted(Formatting.GREEN), false);
			if (owner != null) owner.say("Good work. Same again tomorrow.");
		}
	}

	// ---------------------------------------------------------------- freedom

	static void free(ServerWorld world, Justice j, FactionManager fm, Justice.Slave sl, String message) {
		j.slaves.remove(sl.who);
		j.markDirty();
		Faction f = fm.get(sl.faction);
		if (f != null && f.citizens.containsKey(sl.who)) f.citizens.get(sl.who).bonded = false;
		ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(sl.who);
		if (p != null) p.sendMessage(Text.literal("🕊 " + message).formatted(Formatting.GREEN), false);
		if (world.getEntity(sl.who) instanceof ColonistEntity c) c.say("Free! I'm free!");
	}

	/** /colony freedom buy: pay the price to the owner (or the town). */
	@Nullable
	public static String buyFreedom(ServerPlayerEntity player, @Nullable UUID forWhom) {
		ServerWorld world = (ServerWorld) player.getWorld();
		Justice j = Justice.get(world.getServer());
		FactionManager fm = FactionManager.get(world.getServer());
		Justice.Slave sl = j.slaves.get(forWhom == null ? player.getUuid() : forWhom);
		if (sl == null) return forWhom == null ? "You are not enslaved." : "That person is not enslaved.";
		int price = freedomPrice(sl);
		if (Inventories.remove(player.getInventory(), st -> st.isOf(Items.EMERALD), price, true) < price) return "Freedom costs " + price + " emeralds.";
		Inventories.remove(player.getInventory(), st -> st.isOf(Items.EMERALD), price, false);
		pay(world, fm, sl, price);
		j.clear(sl.faction, sl.who);
		if (forWhom != null) net.colonists.legend.Legends.earn(world.getServer(), player.getUuid(), net.colonists.legend.History.PIETY, 15);
		free(world, j, fm, sl, sl.name + "'s freedom has been bought for " + price + " emeralds.");
		return null;
	}

	private static void pay(ServerWorld world, FactionManager fm, Justice.Slave sl, int amount) {
		if (sl.owner != null && sl.ownerPlayer) {
			ServerPlayerEntity master = world.getServer().getPlayerManager().getPlayer(sl.owner);
			if (master != null) master.giveItemStack(new ItemStack(Items.EMERALD, amount));
			return;
		}
		Faction f = fm.get(sl.faction);
		if (f != null && sl.owner != null && f.citizens.containsKey(sl.owner)) {
			f.citizens.get(sl.owner).wealth += amount;
			return;
		}
		Settlement s = fm.settlement(sl.settlement);
		if (s != null) s.treasury += amount;
	}

	/** A colonist died: free them if they were a slave; settle their own slaves according to their will. */
	static void onDeath(ServerWorld world, Justice j, FactionManager fm, ColonistEntity dead) {
		if (j.slaves.remove(dead.getUuid()) != null) j.markDirty();
		for (Justice.Slave sl : new ArrayList<>(j.slaves.values())) {
			if (!dead.getUuid().equals(sl.owner)) continue;
			if (dead.kindness >= 50) {
				free(world, j, fm, sl, dead.fullName() + " has died, and their will sets you free.");
			} else {
				sl.owner = null;
				sl.ownerPlayer = false;
				sl.ownerName = "";
				Settlement s = fm.settlement(sl.settlement);
				if (s != null) sl.estate = estateOf(world, sl, s);
				ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(sl.who);
				if (p != null) p.sendMessage(Text.literal("Your master " + dead.fullName() + " has died. You now belong to the town.").formatted(Formatting.RED), false);
			}
		}
	}

	/** A runaway caught by the guards is dragged back, and their price for freedom goes up. */
	static boolean returnRunaway(ServerWorld world, Justice j, LivingEntity e) {
		Justice.Slave sl = j.slaves.get(e.getUuid());
		if (sl == null) return false;
		sl.runaway = false;
		sl.warned = 0;
		sl.missed++;
		sl.price += 10;
		j.markDirty();
		Courts.teleport(e, sl.estate);
		if (e instanceof ServerPlayerEntity p) {
			p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 6000, 1));
			p.sendMessage(Text.literal("You have been dragged back to the estate in chains. Your freedom now costs " + freedomPrice(sl) + " emeralds.").formatted(Formatting.DARK_RED), false);
		}
		return true;
	}

	/** /colony estate: a player who owns slaves moves their estate here. */
	public static int setEstate(ServerPlayerEntity owner) {
		Justice j = Justice.get(owner.getServer());
		int n = 0;
		for (Justice.Slave sl : j.slaves.values()) {
			if (owner.getUuid().equals(sl.owner) && sl.ownerPlayer) {
				sl.estate = owner.getBlockPos().toImmutable();
				n++;
			}
		}
		j.markDirty();
		return n;
	}
}
