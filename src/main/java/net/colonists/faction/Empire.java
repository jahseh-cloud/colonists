package net.colonists.faction;

import net.colonists.entity.ColonistEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The grand-strategy layer: colonisation, war goals, conquest, treaties, loyalty and rebellion.
 * Runs every diplomacy tick (once a minute) whether or not anyone is watching.
 */
public final class Empire {
	private Empire() {}

	/** Adults a settlement needs before it sends out colonists. */
	static final int COLONY_POPULATION = 12;
	/** Minecraft days between new colonies for one faction. */
	static final int COLONY_COOLDOWN_DAYS = 4;
	static final int COLONY_MIN_DIST = 50, COLONY_MAX_DIST = 300;
	/** Siege points needed to take a plaza (one point per besieging soldier per second). */
	public static final int SIEGE_POINTS = 90;

	static void tick(MinecraftServer server, FactionManager fm, Random random) {
		ServerWorld world = server.getOverworld();
		long now = world.getTime();
		long day = world.getTimeOfDay() / 24000L;
		List<Faction> all = new ArrayList<>(fm.all());

		for (Faction f : all) {
			f.atWar = false;
			for (Faction o : all) {
				if (o != f && !o.settlements.isEmpty() && fm.relation(f.id, o.id) <= FactionManager.WAR) {
					f.atWar = true;
					break;
				}
			}
		}

		for (Faction f : all) {
			chooseWarGoal(server, fm, f, all);
			for (Settlement s : new ArrayList<>(f.settlements.values())) {
				if (now - s.lastSiegeTick > 400) {
					s.occupation = 0;
					s.occupier = "";
				}
				loyalty(server, fm, f, s, random);
			}
			if (f.orderedColony != null) orderedColony(server, fm, f, random);
			else if (f.rulerPlayer == null && !f.freeVillage) colonize(server, fm, f, day, random);
		}
	}

	// ---------------------------------------------------------------- colonies

	private static void colonize(MinecraftServer server, FactionManager fm, Faction f, long day, Random random) {
		if (f.atWar || f.revolt || day - f.lastColonyDay < COLONY_COOLDOWN_DAYS || f.settlements.size() >= 16) return;
		if (!server.getOverworld().getGameRules().getBoolean(net.colonists.ColonistsMod.GROW)) return;
		Settlement mother = null;
		for (Settlement s : f.settlements.values()) {
			if (s.cityCenter == null || s.adults() < COLONY_POPULATION || s.homes.size() < 6) continue;
			if (mother == null || s.adults() > mother.adults()) mother = s;
		}
		if (mother == null || random.nextInt(3) != 0) return;
		ServerWorld world = server.getOverworld();

		BlockPos site = null;
		for (int attempt = 0; attempt < 4 && site == null; attempt++) {
			double angle = random.nextDouble() * Math.PI * 2;
			int dist = COLONY_MIN_DIST + random.nextInt(COLONY_MAX_DIST - COLONY_MIN_DIST + 1);
			BlockPos near = mother.cityCenter.add((int) (Math.cos(angle) * dist), 0, (int) (Math.sin(angle) * dist));
			if (!world.getWorldBorder().contains(near)) continue;
			BlockPos c = CityGrowth.bestCenter(world, near, random);
			if (c == null) continue;
			Settlement other = fm.nearestSettlement(c, null);
			if (other != null && other.cityCenter != null && horizontalDist(other.cityCenter, c) < COLONY_MIN_DIST) continue;
			site = c;
		}
		if (site == null) return;

		// Pick the settlers: the homeless first, then a builder, a farmer, a guard; couples travel together.
		List<ColonistEntity> pool = new ArrayList<>();
		for (Map.Entry<UUID, Faction.Citizen> e : f.citizens.entrySet()) {
			if (e.getValue().settlement != mother.id || e.getValue().child || e.getKey().equals(f.ruler)) continue;
			if (world.getEntity(e.getKey()) instanceof ColonistEntity c && c.isAlive()) pool.add(c);
		}
		if (pool.size() < 6) return;
		pool.sort(Comparator.comparingInt(c -> settlerPriority(c)));
		List<ColonistEntity> chosen = new ArrayList<>();
		for (ColonistEntity c : pool) {
			if (chosen.size() >= 5) break;
			if (chosen.contains(c)) continue;
			chosen.add(c);
			if (c.partner != null && chosen.size() < 6 && world.getEntity(c.partner) instanceof ColonistEntity p
					&& pool.contains(p) && !chosen.contains(p)) chosen.add(p);
		}

		Settlement colony = fm.foundSettlement(server, f, site, false);
		f.lastColonyDay = day;
		for (ColonistEntity c : chosen) {
			for (Map.Entry<BlockPos, UUID> h : mother.homes.entrySet()) if (c.getUuid().equals(h.getValue())) h.setValue(null);
			c.home = null;
			c.bed = null;
			c.chest = null;
			c.buildSite = null;
			c.joinSettlement(colony);
			c.getInventory().addStack(new ItemStack(Items.BREAD, 4));
			c.say("We're off to found " + colony.name + "!");
		}
		fm.markDirty();
		FactionManager.broadcast(server, Text.literal("⛺ Settlers from ").formatted(Formatting.YELLOW).append(mother.displayName())
				.append(Text.literal(" set out to found ").formatted(Formatting.YELLOW)).append(colony.displayName())
				.append(Text.literal(" for the ").formatted(Formatting.YELLOW)).append(f.displayName())
				.append(Text.literal(" at " + site.getX() + ", " + site.getZ() + ".").formatted(Formatting.YELLOW)));
	}

	/** A colony the ruler ordered from the atlas: settlers set out as soon as the town can spare five. */
	private static void orderedColony(MinecraftServer server, FactionManager fm, Faction f, Random random) {
		Settlement mother = fm.settlement(f.orderedColonyFrom);
		BlockPos want = f.orderedColony;
		if (mother == null || mother.faction != f || mother.cityCenter == null) {
			f.orderedColony = null;
			return;
		}
		ServerWorld world = server.getOverworld();
		BlockPos site = CityGrowth.bestCenter(world, new BlockPos(want.getX(), mother.cityCenter.getY(), want.getZ()), random);
		if (site == null) {
			f.orderedColony = null;
			tellRuler(server, f, "No settlers could find buildable ground near " + want.getX() + ", " + want.getZ() + ".");
			return;
		}
		List<ColonistEntity> pool = new ArrayList<>();
		for (Map.Entry<UUID, Faction.Citizen> e : f.citizens.entrySet()) {
			if (e.getValue().settlement != mother.id || e.getValue().child || e.getValue().bonded) continue;
			if (world.getEntity(e.getKey()) instanceof ColonistEntity c && c.isAlive()) pool.add(c);
		}
		if (pool.size() < 4) return; // wait until the town can spare people
		pool.sort(Comparator.comparingInt(Empire::settlerPriority));
		List<ColonistEntity> chosen = pool.subList(0, Math.min(5, pool.size() - 2));
		Settlement colony = fm.foundSettlement(server, f, site, false);
		f.orderedColony = null;
		for (ColonistEntity c : new ArrayList<>(chosen)) {
			for (Map.Entry<BlockPos, UUID> h : mother.homes.entrySet()) if (c.getUuid().equals(h.getValue())) h.setValue(null);
			c.home = null;
			c.bed = null;
			c.chest = null;
			c.buildSite = null;
			c.joinSettlement(colony);
			c.getInventory().addStack(new ItemStack(Items.BREAD, 4));
		}
		FactionManager.broadcast(server, Text.literal("⛺ By order of " + f.rulerName + ", settlers from ").formatted(Formatting.YELLOW)
				.append(mother.displayName()).append(Text.literal(" set out to found ").formatted(Formatting.YELLOW)).append(colony.displayName())
				.append(Text.literal(" at " + site.getX() + ", " + site.getZ() + ".").formatted(Formatting.YELLOW)));
	}

	static void tellRuler(MinecraftServer server, Faction f, String msg) {
		if (f.rulerPlayer == null) return;
		net.minecraft.server.network.ServerPlayerEntity p = server.getPlayerManager().getPlayer(f.rulerPlayer);
		if (p != null) p.sendMessage(Text.literal(msg).formatted(Formatting.GOLD), false);
	}

	private static int settlerPriority(ColonistEntity c) {
		int p = c.home == null ? 0 : 10;
		switch (c.job) {
			case BUILDER -> p += 0;
			case FARMER -> p += 1;
			case GUARD -> p += 2;
			case WOODCUTTER -> p += 3;
			default -> p += 5;
		}
		return p;
	}

	// ---------------------------------------------------------------- war

	public static boolean atWarWith(FactionManager fm, Faction a, @Nullable Faction b) {
		return b != null && a != b && fm.relation(a.id, b.id) <= FactionManager.WAR;
	}

	/** Every army needs an objective: the nearest enemy settlement. Wars are fought for land, not for blood. */
	private static void chooseWarGoal(MinecraftServer server, FactionManager fm, Faction f, List<Faction> all) {
		Settlement goal = fm.settlement(f.warGoal);
		if (goal != null && atWarWith(fm, f, goal.faction)) return;
		f.warGoal = -1;
		if (!f.atWar || f.freeVillage) return;
		BlockPos home = f.center();
		if (home == null) return;
		Settlement best = null;
		double bestD = Double.MAX_VALUE;
		for (Faction o : all) {
			if (!atWarWith(fm, f, o)) continue;
			for (Settlement s : o.settlements.values()) {
				if (s.cityCenter == null) continue;
				// Nearest to any of our own towns.
				for (Settlement mine : f.settlements.values()) {
					if (mine.cityCenter == null) continue;
					double d = horizontalDist(mine.cityCenter, s.cityCenter);
					if (d < bestD) {
						bestD = d;
						best = s;
					}
				}
			}
		}
		if (best == null) return;
		f.warGoal = best.id;
		FactionManager.broadcast(server, Text.literal("🚩 The armies of the ").formatted(Formatting.RED).append(f.displayName())
				.append(Text.literal(" march on ").formatted(Formatting.RED)).append(best.displayName())
				.append(Text.literal(" (" + best.faction.name + ")!").formatted(Formatting.RED)));
	}

	/** Called by besieging soldiers each second they hold the plaza unopposed. */
	public static void besiege(ServerWorld world, FactionManager fm, Settlement s, Faction attacker) {
		long now = world.getTime();
		if (!attacker.id.equals(s.occupier)) {
			s.occupier = attacker.id;
			s.occupation = 0;
			FactionManager.broadcast(world.getServer(), Text.literal("🔥 ").formatted(Formatting.GOLD).append(s.displayName())
					.append(Text.literal(" is under siege by the ").formatted(Formatting.GOLD)).append(attacker.displayName())
					.append(Text.literal("!").formatted(Formatting.GOLD)));
		}
		s.lastSiegeTick = now;
		if (++s.occupation >= SIEGE_POINTS) conquer(world.getServer(), fm, s, attacker, false);
	}

	/** The settlement changes hands with its houses, fields and people intact. */
	public static void conquer(MinecraftServer server, FactionManager fm, Settlement s, Faction winner, boolean treaty) {
		Faction loser = s.faction;
		if (loser == null || loser == winner) return;
		boolean wasCapital = s.isCapital();
		fm.transferSettlement(s, winner);
		s.loyalty = treaty ? 40 : 20;
		s.occupation = 0;
		s.occupier = "";
		loser.warWeariness += 4;
		winner.armyOpinion = Math.min(100, winner.armyOpinion + 8);
		loser.armyOpinion = Math.max(0, loser.armyOpinion - 10);
		if (winner.warGoal == s.id) winner.warGoal = -1;
		if (loser.warGoal == s.id) loser.warGoal = -1;
		FactionManager.broadcast(server, Text.literal(treaty ? "📜 By treaty, " : "🏴 ").formatted(Formatting.GOLD)
				.append(s.displayName()).append(Text.literal(treaty ? " is ceded to the " : " has been conquered by the ").formatted(Formatting.GOLD))
				.append(winner.displayName()).append(Text.literal(wasCapital ? " — the capital of the " : "").formatted(Formatting.GOLD))
				.append(wasCapital ? loser.displayName() : Text.empty()).append(Text.literal(wasCapital ? " has fallen!" : "!").formatted(Formatting.GOLD)));
		if (!fm.dissolveIfEmpty(server, loser) && wasCapital && !loser.settlements.isEmpty()) {
			Settlement newCap = loser.settlements.values().stream().max(Comparator.comparingInt(Settlement::population)).orElse(null);
			if (newCap != null) {
				loser.capital = newCap.id;
				FactionManager.broadcast(server, Text.literal("The ").formatted(Formatting.GRAY).append(loser.displayName())
						.append(Text.literal(" move their capital to ").formatted(Formatting.GRAY)).append(newCap.displayName()));
			}
		}
		fm.markDirty();
	}

	/** A peace that hands over the contested town instead of fighting to the last man. */
	static boolean sueForPeace(MinecraftServer server, FactionManager fm, Faction winner, Faction loser) {
		Settlement goal = fm.settlement(winner.warGoal);
		if (goal == null || goal.faction != loser || loser.settlements.size() < 2) return false;
		conquer(server, fm, goal, winner, true);
		fm.setRelation(server, winner.id, loser.id, -15);
		return true;
	}

	// ---------------------------------------------------------------- loyalty

	private static void loyalty(MinecraftServer server, FactionManager fm, Faction f, Settlement s, Random random) {
		if (s.isCapital()) {
			s.loyalty = 100;
			return;
		}
		Settlement cap = f.capital();
		double dist = cap == null || cap.cityCenter == null || s.cityCenter == null ? 0 : horizontalDist(cap.cityCenter, s.cityCenter);
		int drift = s.formerOwner.isEmpty() ? 2 : 1;
		if (s.autonomy) drift += 2;
		if (s.taxRate > 15) drift -= 1;
		if (s.countJob("GUARD") >= 2) drift++;
		if (f.approval < 35) drift -= 2;
		if (dist > 600) drift -= 1;
		if (s.occupation > 0) drift -= 1;
		s.loyalty = Math.max(0, Math.min(100, s.loyalty + (random.nextInt(3) == 0 ? drift : 0)));

		if (s.loyalty >= 12 || s.population() < 2 || random.nextInt(15) != 0) return;
		// Rebellion: back to the old masters if they still stand, otherwise independence.
		Faction old = fm.get(s.formerOwner);
		Faction to;
		if (old != null && old != f && fm.relation(old.id, f.id) < 0) {
			to = old;
		} else {
			to = fm.createFaction(random, null);
		}
		fm.transferSettlement(s, to);
		s.loyalty = 70;
		s.formerOwner = f.id;
		fm.setRelation(server, f.id, to.id, -60);
		FactionManager.broadcast(server, Text.literal("✊ ").formatted(Formatting.GOLD).append(s.displayName())
				.append(Text.literal(" rises against the ").formatted(Formatting.GOLD)).append(f.displayName())
				.append(Text.literal(to == old ? " and returns to the " : " and declares independence as the ").formatted(Formatting.GOLD))
				.append(to.displayName()).append(Text.literal("!").formatted(Formatting.GOLD)));
	}

	static double horizontalDist(BlockPos a, BlockPos b) {
		return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
	}

	/** True if a player is close enough to watch this spot (so armies and settlers walk instead of "travelling"). */
	public static boolean watched(ServerWorld world, Entity e, double range) {
		return world.getClosestPlayer(e.getX(), e.getY(), e.getZ(), range, false) != null;
	}
}
