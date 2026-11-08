package net.colonists.player;

import net.colonists.economy.Economy;
import net.colonists.entity.ColonistBrain;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Settlement;
import net.colonists.faction.Stockpile;
import net.colonists.law.Courts;
import net.colonists.law.Justice;
import net.colonists.legend.History;
import net.colonists.legend.Legends;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Work for hire, posted on every town's notice board: supplies, clearing monsters, bounties, war service, mercenary
 * contracts, courier runs, spying, assassination, and stone for a wonder.
 */
public final class Contracts {
	private Contracts() {}

	public static final int MAX_ACTIVE = 3;

	public enum Type {
		SUPPLY("Supply"), CLEAR("Clear the wilds"), BOUNTY("Bounty"), WAR("War service"), MERCENARY("Mercenary contract"),
		COURIER("Courier"), SPY("Espionage"), ASSASSINATE("Assassination"), WONDER("Stone for the wonder");

		public final String label;

		Type(String label) {
			this.label = label;
		}
	}

	static Type type(PlayerData.Contract c) {
		try {
			return Type.valueOf(c.type);
		} catch (IllegalArgumentException e) {
			return Type.SUPPLY;
		}
	}

	public static String describe(MinecraftServer server, PlayerData.Contract c) {
		FactionManager fm = FactionManager.get(server);
		Settlement town = fm.settlement(c.town);
		String from = town == null ? "a lost town" : town.name;
		long day = server.getOverworld().getTimeOfDay() / 24000L;
		String due = c.deadline > 0 ? " — " + Math.max(0, c.deadline - day) + " days left" : "";
		String what = switch (type(c)) {
			case SUPPLY -> "Bring " + c.needed + " " + c.targetName + " to " + from;
			case CLEAR -> "Slay " + c.needed + " monsters near " + from;
			case BOUNTY -> "Bring down the outlaw " + c.targetName;
			case WAR -> "Kill " + c.needed + " soldiers of the " + c.targetName;
			case MERCENARY -> "Fight for " + from + " against the " + c.targetName + " for " + c.needed + " days";
			case COURIER -> "Carry a sealed letter from " + from + " to " + c.targetName;
			case SPY -> "Watch the plaza of " + c.targetName + " unseen for " + c.needed + " seconds";
			case ASSASSINATE -> "Assassinate " + c.targetName;
			case WONDER -> "Bring " + c.needed + " stone for the wonder of " + from;
		};
		String prog = c.needed > 1 && c.started > 0 ? " (" + c.progress + "/" + c.needed + ")" : "";
		return type(c).label + ": " + what + prog + ". Pays " + c.reward + " emeralds" + due + ".";
	}

	// ---------------------------------------------------------------- the notice boards (regenerated each day)

	public static List<PlayerData.Contract> board(MinecraftServer server, Settlement s) {
		PlayerData d = PlayerData.get(server);
		long day = server.getOverworld().getTimeOfDay() / 24000L;
		if (d.boardsDay != day) {
			d.boards.clear();
			d.boardsDay = day;
		}
		return d.boards.computeIfAbsent(s.id, k -> generate(server, s, day));
	}

	static List<PlayerData.Contract> generate(MinecraftServer server, Settlement s, long day) {
		ServerWorld world = server.getOverworld();
		Random r = world.getRandom();
		FactionManager fm = FactionManager.get(server);
		Faction f = s.faction;
		List<PlayerData.Contract> out = new ArrayList<>();
		if (f == null || s.cityCenter == null) return out;
		List<Inventory> stores = Stockpile.chests(world, s);
		// Supplies the town lacks.
		for (Economy.Good g : Economy.Good.values()) {
			int stock = Stockpile.count(stores, g.is);
			if (stock >= g.target || r.nextInt(3) != 0) continue;
			int n = Math.min(32, Math.max(8, g.target - stock));
			out.add(make(Type.SUPPLY, s, net.minecraft.registry.Registries.ITEM.getId(g.usual).toString(), g.usual.getName().getString().toLowerCase(), n,
					Math.min(30, 2 + (int) Math.ceil(n * 1.3 / Economy.perEmerald(s, g, stock))), day + 3));
			if (out.size() >= 2) break;
		}
		out.add(make(Type.CLEAR, s, "", "", 5 + r.nextInt(8), 6 + r.nextInt(8), day + 2));
		// Outlaws wanted here.
		Map<UUID, Justice.Record> wanted = Justice.get(server).records.get(f.id);
		if (wanted != null) {
			for (Map.Entry<UUID, Justice.Record> e : wanted.entrySet()) {
				if (e.getValue().player || e.getValue().heat < 2) continue;
				out.add(make(Type.BOUNTY, s, e.getKey().toString(), e.getValue().name, 1, 6 * e.getValue().heat, day + 3));
				break;
			}
		}
		// War work.
		for (Faction o : fm.all()) {
			if (o == f || o.settlements.isEmpty()) continue;
			int rel = fm.relation(f.id, o.id);
			if (rel <= FactionManager.WAR) {
				int n = 3 + r.nextInt(4);
				out.add(make(Type.WAR, s, o.id, o.name, n, 10 + 6 * n, day + 3));
				out.add(make(Type.MERCENARY, s, o.id, o.name, 3, 30 + r.nextInt(15), day + 4));
				break;
			}
		}
		// Letters to carry.
		Settlement dest = null;
		for (Settlement o : fm.allSettlements()) {
			if (o == s || o.cityCenter == null || o.faction == null) continue;
			double dist = Math.sqrt(o.cityCenter.getSquaredDistance(s.cityCenter));
			if (dist < 150 || dist > 1400 || fm.relation(f.id, o.faction.id) <= FactionManager.WAR) continue;
			if (dest == null || r.nextInt(3) == 0) dest = o;
		}
		if (dest != null) {
			double dist = Math.sqrt(dest.cityCenter.getSquaredDistance(s.cityCenter));
			out.add(make(Type.COURIER, s, String.valueOf(dest.id), dest.name, 1, 4 + (int) (dist / 40), day + 2));
		}
		// Intrigue against rivals.
		Settlement rival = null;
		for (Settlement o : fm.allSettlements()) {
			if (o.faction == null || o.faction == f || o.cityCenter == null || o.faction.freeVillage) continue;
			if (fm.relation(f.id, o.faction.id) < 0) rival = o;
		}
		if (rival != null && r.nextInt(2) == 0) out.add(make(Type.SPY, s, String.valueOf(rival.id), rival.name, 30, 25, day + 2));
		if (rival != null && rival.faction.ruler != null && fm.relation(f.id, rival.faction.id) <= -30 && r.nextInt(3) == 0) {
			Faction.Citizen target = rival.faction.citizens.get(rival.faction.ruler);
			if (target != null) out.add(make(Type.ASSASSINATE, s, rival.faction.ruler.toString(), target.fullName() + ", ruler of the " + rival.faction.name, 1, 60, day + 3));
		}
		// Stone for a wonder.
		if (!f.wonderProject.isEmpty() && s.isCapital()) out.add(make(Type.WONDER, s, "stone", "stone", 128, 20, day + 4));
		return out;
	}

	private static PlayerData.Contract make(Type t, Settlement s, String target, String name, int needed, int reward, long deadline) {
		PlayerData.Contract c = new PlayerData.Contract();
		c.type = t.name();
		c.town = s.id;
		c.target = target;
		c.targetName = name;
		c.needed = needed;
		c.reward = Math.max(1, reward);
		c.deadline = deadline;
		return c;
	}

	// ---------------------------------------------------------------- taking work

	@Nullable
	public static String accept(ServerPlayerEntity p, Settlement s, int index) {
		MinecraftServer server = p.getServer();
		PlayerData.Profile pr = PlayerData.of(server, p.getUuid());
		if (pr.contracts.size() >= MAX_ACTIVE) return "You already have " + MAX_ACTIVE + " contracts. Finish or abandon one first.";
		FactionManager fm = FactionManager.get(server);
		if (s.faction != null && fm.standing(p, s.faction.id) <= FactionManager.WAR) return "Nobody here will hire you.";
		List<PlayerData.Contract> board = board(server, s);
		if (index < 1 || index > board.size()) return "There is no contract number " + index + " here.";
		PlayerData.Contract c = board.remove(index - 1).copy();
		c.started = server.getOverworld().getTimeOfDay() / 24000L;
		c.progress = 0;
		pr.contracts.add(c);
		pr.deed(c.started, "took a contract in " + s.name + ": " + type(c).label.toLowerCase());
		p.sendMessage(Text.literal("📜 Contract taken. " + describe(server, c)).formatted(Formatting.GOLD), false);
		return null;
	}

	public static String abandon(ServerPlayerEntity p, int index) {
		MinecraftServer server = p.getServer();
		PlayerData.Profile pr = PlayerData.of(server, p.getUuid());
		if (index < 1 || index > pr.contracts.size()) return "You have no contract number " + index + ".";
		PlayerData.Contract c = pr.contracts.remove(index - 1);
		fail(server, p, pr, c, type(c) == Type.MERCENARY ? "You desert your post." : "You give up the contract.", type(c) == Type.MERCENARY);
		return null;
	}

	// ---------------------------------------------------------------- progress

	/** A player killed something: monsters, enemy soldiers, outlaws and marked men count toward contracts (and titles). */
	public static void onKill(MinecraftServer server, ServerPlayerEntity p, LivingEntity victim) {
		PlayerData.Profile pr = PlayerData.of(server, p.getUuid());
		FactionManager fm = FactionManager.get(server);
		if (victim instanceof ColonistEntity c) {
			String pf = fm.playerFaction(p.getUuid());
			boolean war = (pf != null && fm.relation(pf, c.getFactionId()) <= FactionManager.WAR) || fm.standing(p, c.getFactionId()) <= FactionManager.WAR;
			if (c.isSoldier() && war) {
				pr.add("soldiers", 1);
				pr.add("bane:" + c.getFactionId(), 1);
			} else if (!c.isSoldier() && Justice.get(server).heat(c.getFactionId(), c.getUuid()) <= 0) {
				pr.add("murders", 1);
			}
		}
		long day = server.getOverworld().getTimeOfDay() / 24000L;
		for (PlayerData.Contract k : new ArrayList<>(pr.contracts)) {
			Settlement town = fm.settlement(k.town);
			switch (type(k)) {
				case CLEAR -> {
					if (victim instanceof HostileEntity && town != null && town.cityCenter != null && victim.getBlockPos().getSquaredDistance(town.cityCenter) < 128 * 128) k.progress++;
				}
				case WAR -> {
					if (victim instanceof ColonistEntity c && c.isSoldier() && c.getFactionId().equals(k.target)) k.progress++;
				}
				case MERCENARY -> {
					if (victim instanceof ColonistEntity c && c.isSoldier() && c.getFactionId().equals(k.target)) k.reward += 3;
					if (victim instanceof ColonistEntity c && town != null && town.faction != null && c.getFactionId().equals(town.faction.id)) {
						pr.contracts.remove(k);
						fail(server, p, pr, k, "You turned your sword on your employers!", true);
						continue;
					}
				}
				case BOUNTY, ASSASSINATE -> {
					if (victim.getUuid().toString().equals(k.target)) k.progress = k.needed;
				}
				default -> {
				}
			}
			if (k.progress >= k.needed && type(k) != Type.MERCENARY) complete(server, p, pr, k, day);
		}
	}

	/** Every second for each player: deliveries, letters, spying, mercenary days, and deadlines. */
	public static void tick(MinecraftServer server) {
		ServerWorld world = server.getOverworld();
		FactionManager fm = FactionManager.get(server);
		long day = world.getTimeOfDay() / 24000L;
		for (ServerPlayerEntity p : world.getPlayers()) {
			PlayerData.Profile pr = PlayerData.get(server).profiles.get(p.getUuid());
			if (pr == null || pr.contracts.isEmpty()) continue;
			for (PlayerData.Contract k : new ArrayList<>(pr.contracts)) {
				if (!pr.contracts.contains(k)) continue;
				Settlement town = fm.settlement(k.town);
				if (day > k.deadline && type(k) != Type.MERCENARY) {
					pr.contracts.remove(k);
					fail(server, p, pr, k, "Time ran out.", false);
					continue;
				}
				switch (type(k)) {
					case SUPPLY, WONDER -> {
						if (town == null || town.cityCenter == null || p.getBlockPos().getSquaredDistance(town.cityCenter) > 18 * 18) break;
						net.minecraft.item.Item item = type(k) == Type.WONDER ? Items.COBBLESTONE
								: net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(k.target));
						java.util.function.Predicate<ItemStack> want = type(k) == Type.WONDER ? ColonistBrain.STONE_MATS : st -> st.isOf(item);
						int n = Inventories.remove(p.getInventory(), want, k.needed - k.progress, false);
						if (n > 0) {
							k.progress += n;
							depositGoods(world, town, item, n);
							p.sendMessage(Text.literal("Delivered " + n + " (" + k.progress + "/" + k.needed + ").").formatted(Formatting.GRAY), true);
						}
					}
					case COURIER -> {
						Settlement dest = fm.settlement(parse(k.target));
						if (dest != null && dest.cityCenter != null && p.getBlockPos().getSquaredDistance(dest.cityCenter) < 14 * 14) k.progress = 1;
					}
					case SPY -> {
						Settlement dest = fm.settlement(parse(k.target));
						if (dest == null || dest.cityCenter == null || dest.faction == null || p.getBlockPos().getSquaredDistance(dest.cityCenter) > 22 * 22) break;
						boolean seen = !world.getEntitiesByClass(ColonistEntity.class, p.getBoundingBox().expand(16, 6, 16),
								g -> g.job == ColonistEntity.Job.GUARD && g.getFactionId().equals(dest.faction.id) && g.canSee(p) && !p.isSneaking()).isEmpty();
						if (seen) {
							pr.contracts.remove(k);
							Courts.report(world, dest, p, net.colonists.law.Law.Crime.TRESPASS, false);
							fail(server, p, pr, k, "A guard spotted you snooping!", false);
							continue;
						}
						k.progress++;
						if (k.progress % 10 == 0) p.sendMessage(Text.literal("Watching unseen... " + k.progress + "/" + k.needed).formatted(Formatting.GRAY), true);
					}
					case MERCENARY -> {
						k.progress = (int) (day - k.started);
						Faction employer = town == null ? null : town.faction;
						if (employer == null || fm.relation(employer.id, k.target) > FactionManager.WAR) {
							// The war ended: paid in full for the days served.
							pr.add("mercenaryDays", k.progress);
							k.reward = Math.max(5, k.reward * Math.max(1, k.progress) / Math.max(1, k.needed));
							k.progress = k.needed;
						}
						if (k.progress >= k.needed) {
							pr.add("mercenaryDays", k.needed);
							complete(server, p, pr, k, day);
						}
						continue;
					}
					default -> {
					}
				}
				if (pr.contracts.contains(k) && k.progress >= k.needed) complete(server, p, pr, k, day);
			}
		}
	}

	private static int parse(String s) {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static void depositGoods(ServerWorld world, Settlement town, net.minecraft.item.Item item, int n) {
		List<Inventory> stores = Stockpile.chests(world, town);
		while (n > 0) {
			ItemStack goods = new ItemStack(item, Math.min(item.getMaxCount(), n));
			n -= goods.getCount();
			for (Inventory inv : stores) {
				goods = ColonistBrain.insert(inv, goods);
				if (goods.isEmpty()) break;
			}
		}
	}

	static void complete(MinecraftServer server, ServerPlayerEntity p, PlayerData.Profile pr, PlayerData.Contract k, long day) {
		pr.contracts.remove(k);
		FactionManager fm = FactionManager.get(server);
		Settlement town = fm.settlement(k.town);
		int paid = k.reward;
		if (town != null) {
			paid = Math.min(k.reward, Math.max(0, town.treasury) + k.reward / 3);
			town.treasury = Math.max(0, town.treasury - paid);
			if (town.faction != null) fm.changeRep(server, p, town.faction.id, type(k) == Type.WAR || type(k) == Type.MERCENARY || type(k) == Type.ASSASSINATE ? 10 : 5);
		}
		if (paid > 0) p.giveItemStack(new ItemStack(Items.EMERALD, paid));
		pr.add("contracts", 1);
		switch (type(k)) {
			case WAR, ASSASSINATE, BOUNTY, MERCENARY -> Legends.earn(server, p.getUuid(), History.VALOUR, 8);
			case COURIER, SPY -> Legends.earn(server, p.getUuid(), History.ADVENTURE, 6);
			case WONDER -> Legends.earn(server, p.getUuid(), History.PIETY, 6);
			default -> Legends.earn(server, p.getUuid(), History.ADVENTURE, 2);
		}
		pr.deed(day, "fulfilled a contract (" + type(k).label.toLowerCase() + (town == null ? "" : " for " + town.name) + ")");
		p.sendMessage(Text.literal("✔ Contract fulfilled: " + type(k).label + ". You are paid " + paid + " emeralds.").formatted(Formatting.GREEN), false);
	}

	static void fail(MinecraftServer server, ServerPlayerEntity p, PlayerData.Profile pr, PlayerData.Contract k, String why, boolean betrayal) {
		FactionManager fm = FactionManager.get(server);
		Settlement town = fm.settlement(k.town);
		if (town != null && town.faction != null) fm.changeRep(server, p, town.faction.id, betrayal ? -25 : -5);
		if (betrayal) pr.add("betrayals", 1);
		long day = server.getOverworld().getTimeOfDay() / 24000L;
		pr.deed(day, (betrayal ? "broke faith with " : "failed a contract for ") + (town == null ? "a town" : town.name));
		p.sendMessage(Text.literal("✘ Contract failed: " + type(k).label + ". " + why).formatted(Formatting.RED), false);
	}

	@Nullable
	public static Settlement nearestTown(ServerPlayerEntity p) {
		FactionManager fm = FactionManager.get(p.getServer());
		Settlement s = Courts.territoryAt(fm, p.getBlockPos(), p.getServerWorld().getTime());
		if (s != null) return s;
		Settlement near = fm.nearestSettlement(p.getBlockPos(), null);
		return near != null && near.cityCenter != null && near.cityCenter.getSquaredDistance(p.getBlockPos()) < 160 * 160 ? near : null;
	}
}
