package net.colonists.legend;

import net.colonists.entity.ColonistEntity;
import net.colonists.faction.Empire;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Settlement;
import net.colonists.legend.History.Kind;
import net.colonists.legend.History.Legend;
import net.colonists.legend.History.Religion;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Legends: adventurers, heroes, emperors and prophets (wandering prophets, prophet kings and prophet conquerors).
 * Renown is earned by deeds; whether a person becomes a legend is part hard work, part fortune.
 */
public final class Legends {
	private Legends() {}

	static final int ADVENTURE_NEEDED = 40, VALOUR_NEEDED = 60, RULE_NEEDED = 90;

	private static final String[] ADVENTURER_TITLES = {"the Wanderer", "the Far-Traveller", "Pathfinder", "the Restless", "Wayfinder", "of the Long Road"};
	private static final String[] HERO_TITLES = {"the Bold", "Breaker-of-Walls", "the Lion", "Shieldbearer", "the Unbroken", "the Valiant", "Ironheart"};
	private static final String[] EMPEROR_TITLES = {"the Great", "the Magnificent", "Builder of Empires", "the Conqueror", "the Lawgiver", "the Golden"};
	private static final String[] PROPHET_TITLES = {"the Seer", "the Voice", "the Lightbringer", "the Dreamer", "the Herald", "the Witness"};

	/** Player positions a minute ago (for adventuring renown). */
	private static final Map<UUID, BlockPos> LAST_POS = new HashMap<>();

	// ---------------------------------------------------------------- the record

	/** Writes an event into the history of the world. */
	public static History.Event event(MinecraftServer server, History.EventType type, @Nullable Faction actor, @Nullable Faction target, @Nullable Settlement place) {
		History h = History.get(server);
		History.Event e = h.log(day(server.getOverworld()), type);
		if (actor != null) {
			e.actor = actor.id;
			e.actorName = actor.name;
		}
		if (target != null) {
			e.target = target.id;
			e.targetName = target.name;
		}
		if (place != null) {
			e.place = place.name;
			if (place.cityCenter != null) {
				e.x = place.cityCenter.getX();
				e.z = place.cityCenter.getZ();
			}
		} else if (actor != null && actor.center() != null) {
			e.x = actor.center().getX();
			e.z = actor.center().getZ();
		}
		return e;
	}

	// ---------------------------------------------------------------- renown hooks

	public static void earn(MinecraftServer server, UUID who, int kind, int amount) {
		History h = History.get(server);
		h.renown(who)[kind] += amount;
		h.markDirty();
	}

	/** A soldier killed an enemy soldier in war. */
	public static void onKill(MinecraftServer server, Entity killer, LivingEntity victim) {
		earn(server, killer.getUuid(), History.VALOUR, killer instanceof PlayerEntity ? 5 : 6);
	}

	/** A colonist died: legends are mourned (or celebrated by their enemies). */
	public static void onDeath(ServerWorld world, ColonistEntity dead, @Nullable Entity killer) {
		History h = History.get(world.getServer());
		Legend l = h.living(dead.getUuid());
		h.renown.remove(dead.getUuid());
		if (l == null) return;
		long day = day(world);
		l.alive = false;
		l.died = day;
		FactionManager fm = FactionManager.get(world.getServer());
		Faction own = fm.get(dead.getFactionId());
		if (own != null) own.armyOpinion = Math.max(0, own.armyOpinion - (l.kind == Kind.HERO ? 8 : 4));
		if (killer instanceof ColonistEntity k) {
			Faction them = fm.get(k.getFactionId());
			if (them != null) them.armyOpinion = Math.min(100, them.armyOpinion + 5);
		}
		for (Religion r : h.religions.values()) {
			if (dead.getUuid().equals(r.prophet)) r.prophet = null;
		}
		History.Event e = h.log(day, History.EventType.LEGEND_DIES);
		e.person = l.styled();
		e.legend = l.who;
		e.actor = l.faction;
		e.actorName = l.factionName;
		e.extra = l.kind.label;
		e.x = dead.getBlockX();
		e.z = dead.getBlockZ();
		if (killer != null) e.targetName = killer instanceof ColonistEntity k ? k.fullName() : killer.getName().getString();
		FactionManager.broadcast(world.getServer(), Text.literal("🕯 " + l.styled() + ", " + l.kind.label.toLowerCase() + " of the " + l.factionName
				+ ", has died" + (e.targetName.isEmpty() ? "." : " at the hands of " + e.targetName + ".")).formatted(Formatting.GRAY));
		h.markDirty();
	}

	/** Extra siege points a besieger brings (heroes and prophet conquerors take towns faster; so do holy warriors). */
	public static int siegePower(MinecraftServer server, ColonistEntity soldier, Faction realm) {
		History h = History.get(server);
		int p = 1;
		if (!realm.holyWar.isEmpty()) p += realm.greatHolyWar ? 2 : 1;
		Legend l = h.living(soldier.getUuid());
		if (l != null && (l.kind == Kind.HERO || l.kind == Kind.EMPEROR)) p += 1;
		if (l != null && l.kind == Kind.PROPHET_CONQUEROR) p += 3;
		if (soldier.getRandom().nextInt(3) == 0) earn(server, soldier.getUuid(), History.VALOUR, 1);
		return p;
	}

	@Nullable
	public static Legend living(MinecraftServer server, UUID who) {
		return History.get(server).living(who);
	}

	public static boolean realmHas(MinecraftServer server, Faction f, Kind kind) {
		for (Legend l : History.get(server).legends.values()) if (l.alive && l.kind == kind && l.faction.equals(f.id)) return true;
		return false;
	}

	// ---------------------------------------------------------------- once a minute

	public static void tick(MinecraftServer server, FactionManager fm, Random random) {
		ServerWorld world = server.getOverworld();
		History h = History.get(server);
		long day = day(world);
		Faith.tick(server, fm, h, random, day);

		// Rule: every minute on the throne of a kingdom or empire counts.
		for (Faction f : fm.all()) {
			UUID ruler = f.rulerPlayer != null ? f.rulerPlayer : f.ruler;
			if (ruler == null) continue;
			String title = f.realmTitle();
			if (title.equals("Empire")) h.renown(ruler)[History.RULE] += 2;
			else if (title.equals("Kingdom")) h.renown(ruler)[History.RULE] += 1;
		}
		// Players who roam the world.
		for (ServerPlayerEntity p : world.getPlayers()) {
			BlockPos was = LAST_POS.put(p.getUuid(), p.getBlockPos());
			if (was != null) {
				double moved = Math.hypot(was.getX() - p.getX(), was.getZ() - p.getZ());
				if (moved > 100 && moved < 3000) h.renown(p.getUuid())[History.ADVENTURE] += (int) Math.min(6, moved / 100);
			}
		}

		// Renown becomes legend: part hard work, part fortune.
		for (Map.Entry<UUID, int[]> e : new ArrayList<>(h.renown.entrySet())) {
			UUID who = e.getKey();
			int[] r = e.getValue();
			Legend l = h.legends.get(who);
			if (l != null && !l.alive) continue;
			int have = l == null ? 0 : l.kind.rank;
			Kind next = null;
			if (r[History.RULE] >= RULE_NEEDED && have < Kind.EMPEROR.rank && rulesEmpire(fm, who)) next = Kind.EMPEROR;
			else if (r[History.VALOUR] >= VALOUR_NEEDED && have < Kind.HERO.rank) next = Kind.HERO;
			else if (r[History.ADVENTURE] >= (wanderer(fm, h, who) ? ADVENTURE_NEEDED / 2 : ADVENTURE_NEEDED) && have < Kind.ADVENTURER.rank) next = Kind.ADVENTURER;
			if (next == null || random.nextInt(8) != 0) continue;
			raise(server, fm, h, who, next, day, random);
		}

		// Prophets, one per faith at most, every 25-40 days.
		for (Religion rel : new ArrayList<>(h.religions.values())) {
			if (rel.prophet != null && !stillAlive(server, fm, rel.prophet)) {
				Legend dead = h.legends.get(rel.prophet);
				if (dead != null && dead.alive) {
					dead.alive = false;
					dead.died = day;
				}
				rel.prophet = null;
			}
			if (rel.prophet == null) {
				if (rel.nextProphetDay < 0) rel.nextProphetDay = day + 25 + random.nextInt(16);
				if (day >= rel.nextProphetDay) prophetRises(server, fm, h, rel, day, random);
			} else {
				prophesy(server, fm, h, rel, day, random);
			}
		}
		resolveProphecies(server, fm, h, day);
		h.markDirty();
	}

	/** Only the ruler of a true Empire may be remembered as an Emperor. */
	private static boolean rulesEmpire(FactionManager fm, UUID who) {
		for (Faction f : fm.all()) {
			if ((who.equals(f.ruler) || who.equals(f.rulerPlayer)) && f.realmTitle().equals("Empire")) return true;
		}
		return false;
	}

	private static boolean wanderer(FactionManager fm, History h, UUID who) {
		Faction f = factionOf(fm, who);
		return f != null && Faith.has(h, f, Tenet.WANDERING);
	}

	static boolean stillAlive(MinecraftServer server, FactionManager fm, UUID who) {
		if (server.getPlayerManager().getPlayer(who) != null) return true;
		Legend l = History.get(server).legends.get(who);
		if (l != null && l.player) return true; // players are offline, not dead
		return factionOf(fm, who) != null;
	}

	@Nullable
	static Faction factionOf(FactionManager fm, UUID who) {
		for (Faction f : fm.all()) if (f.citizens.containsKey(who)) return f;
		String pf = fm.playerFaction(who);
		return pf == null ? null : fm.get(pf);
	}

	@Nullable
	static String nameOf(MinecraftServer server, FactionManager fm, UUID who) {
		ServerPlayerEntity p = server.getPlayerManager().getPlayer(who);
		if (p != null) return p.getName().getString();
		for (Faction f : fm.all()) {
			Faction.Citizen c = f.citizens.get(who);
			if (c != null) return c.fullName();
		}
		Legend l = History.get(server).legends.get(who);
		return l == null ? null : l.name;
	}

	@Nullable
	static BlockPos whereIs(MinecraftServer server, FactionManager fm, UUID who) {
		ServerPlayerEntity p = server.getPlayerManager().getPlayer(who);
		if (p != null) return p.getBlockPos();
		for (Faction f : fm.all()) {
			Faction.Citizen c = f.citizens.get(who);
			if (c != null) {
				Settlement s = fm.settlement(c.settlement);
				return s != null ? s.cityCenter : f.center();
			}
		}
		return null;
	}

	static Legend raise(MinecraftServer server, FactionManager fm, History h, UUID who, Kind kind, long day, Random random) {
		String name = nameOf(server, fm, who);
		Faction f = factionOf(fm, who);
		Legend l = h.legends.computeIfAbsent(who, k -> new Legend());
		boolean upgrade = l.who != null;
		l.who = who;
		l.player = server.getPlayerManager().getPlayer(who) != null;
		l.name = name == null ? "Someone" : name;
		l.kind = kind;
		l.title = switch (kind) {
			case ADVENTURER -> pick(ADVENTURER_TITLES, random);
			case HERO -> pick(HERO_TITLES, random);
			case EMPEROR -> pick(EMPEROR_TITLES, random);
			case PROPHET, PROPHET_KING, PROPHET_CONQUEROR -> pick(PROPHET_TITLES, random);
		};
		l.faction = f == null ? "" : f.id;
		l.factionName = f == null ? "wilds" : f.name;
		l.religion = f == null ? "" : f.religion;
		l.alive = true;
		l.since = day;
		l.deeds.add("Day " + day + ": became " + article(kind) + " " + kind.label.toLowerCase());
		History.Event e = h.log(day, History.EventType.LEGEND_RISES);
		e.person = l.styled();
		e.legend = who;
		e.actor = l.faction;
		e.actorName = l.factionName;
		e.extra = kind.label;
		BlockPos at = whereIs(server, fm, who);
		if (at != null) {
			e.x = at.getX();
			e.z = at.getZ();
		}
		FactionManager.broadcast(server, Text.literal("✦ " + l.styled() + " of the " + l.factionName + " is now remembered as "
				+ article(kind) + " " + kind.label + "!").formatted(kind.prophet() ? Formatting.LIGHT_PURPLE : Formatting.GOLD));
		return l;
	}

	private static String article(Kind k) {
		return k == Kind.ADVENTURER || k == Kind.EMPEROR ? "an" : "a";
	}

	private static String pick(String[] a, Random r) {
		return a[r.nextInt(a.length)];
	}

	// ---------------------------------------------------------------- prophets

	private static void prophetRises(MinecraftServer server, FactionManager fm, History h, Religion rel, long day, Random random) {
		// Candidates: believers, weighted by temperament; players by their piety.
		List<UUID> pool = new ArrayList<>();
		List<Integer> weights = new ArrayList<>();
		for (Faction f : fm.all()) {
			for (Map.Entry<UUID, Faction.Citizen> e : f.citizens.entrySet()) {
				Faction.Citizen c = e.getValue();
				if (c.child || c.bonded || !c.religion.equals(rel.id)) continue;
				pool.add(e.getKey());
				weights.add(1 + (c.kindness + c.ambition) / 25);
			}
		}
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			Faction pf = fm.get(fm.playerFaction(p.getUuid()));
			if (pf == null || !pf.religion.equals(rel.id)) continue;
			int piety = h.renown(p.getUuid())[History.PIETY];
			if (piety <= 0) continue;
			pool.add(p.getUuid());
			weights.add(Math.max(1, piety / 4));
		}
		rel.nextProphetDay = day + 25 + random.nextInt(16);
		if (pool.isEmpty()) return;
		int total = weights.stream().mapToInt(Integer::intValue).sum();
		int roll = random.nextInt(Math.max(1, total));
		UUID chosen = pool.get(0);
		for (int i = 0; i < pool.size(); i++) {
			roll -= weights.get(i);
			if (roll < 0) {
				chosen = pool.get(i);
				break;
			}
		}
		Faction home = factionOf(fm, chosen);
		Religion faith = rel;
		// Some prophets reform their faith, or break from it entirely.
		if (random.nextInt(100) < 35) {
			String name = nameOf(server, fm, chosen);
			faith = Faith.create(h, random, rel, name == null ? "a prophet" : name, day);
			History.Event e = h.log(day, History.EventType.NEW_FAITH);
			e.person = name == null ? "" : name;
			e.extra = faith.name;
			e.targetName = rel.name;
			e.actor = home == null ? "" : home.id;
			e.actorName = home == null ? "" : home.name;
			FactionManager.broadcast(server, Text.literal("☩ " + e.person + " breaks with " + rel.name + " and proclaims " + faith.name
					+ ", teaching " + tenets(faith) + ".").formatted(Formatting.LIGHT_PURPLE));
			for (Faction f : fm.all()) {
				Faction.Citizen c = f.citizens.get(chosen);
				if (c != null) c.religion = faith.id;
			}
			if (home != null) {
				Settlement s = fm.settlement(home.citizens.containsKey(chosen) ? home.citizens.get(chosen).settlement : -1);
				if (s != null) Faith.preach(fm, h, s, faith.id, 2, random);
				if (chosen.equals(home.ruler) || chosen.equals(home.rulerPlayer)) Faith.convertRealm(server, fm, h, home, faith.id, day);
			}
		}
		faith.prophet = chosen;
		Legend l = raise(server, fm, h, chosen, Kind.PROPHET, day, random);
		l.religion = faith.id;
		l.title = pick(PROPHET_TITLES, random);
		History.Event e = h.log(day, History.EventType.PROPHET);
		e.person = l.styled();
		e.legend = chosen;
		e.actor = l.faction;
		e.actorName = l.factionName;
		e.extra = faith.name;
		FactionManager.broadcast(server, Text.literal("☀ A prophet has risen among the faithful of " + faith.name + ": " + l.styled() + "!")
				.formatted(Formatting.LIGHT_PURPLE));
		ServerPlayerEntity p = server.getPlayerManager().getPlayer(chosen);
		if (p != null) {
			p.sendMessage(Text.literal("☀ You have had a vision. You are the prophet of " + faith.name + ". Where you go, the faith spreads. "
					+ "Rule a realm and you become a Prophet King; lead it to war and you become a Prophet Conqueror.").formatted(Formatting.LIGHT_PURPLE), false);
		}
	}

	static String tenets(Religion r) {
		List<String> s = new ArrayList<>();
		for (Tenet t : r.tenets) s.add("\"" + t.label + "\"");
		return String.join(" and ", s);
	}

	/** A living prophet: spreads the faith, may take a throne, and (if they rule and are warlike) wage holy war. */
	private static void prophesy(MinecraftServer server, FactionManager fm, History h, Religion rel, long day, Random random) {
		UUID who = rel.prophet;
		Legend l = h.legends.get(who);
		Faction f = factionOf(fm, who);
		if (l == null || f == null) return;
		boolean rules = who.equals(f.ruler) || who.equals(f.rulerPlayer);
		Faction.Citizen c = f.citizens.get(who);

		// The faithful raise their prophet to the throne.
		if (!rules && c != null && f.rulerPlayer == null && !f.freeVillage && f.religion.equals(rel.id) && f.approval < 60 && random.nextInt(120) == 0) {
			f.ruler = who;
			f.rulerName = c.fullName();
			rules = true;
			FactionManager.broadcast(server, Text.literal("👑 The faithful of the ").formatted(Formatting.LIGHT_PURPLE).append(f.displayName())
					.append(Text.literal(" crown their prophet, " + l.styled() + ".").formatted(Formatting.LIGHT_PURPLE)));
		}
		// What kind of prophet they are now.
		Kind kind = Kind.PROPHET;
		if (rules) {
			int aggression = c != null ? c.aggression : 50;
			kind = (rel.has(Tenet.SWORD) || aggression > 65) && !rel.has(Tenet.PEACE) ? Kind.PROPHET_CONQUEROR : Kind.PROPHET_KING;
		}
		if (kind != l.kind) {
			l.kind = kind;
			l.title = kind == Kind.PROPHET_CONQUEROR ? "the Sword of the " + Faith.stripThe(rel.name).replaceAll("^(Way|Church|Faith|Order|Covenant|Path|Children|Keepers) of the ", "")
					: kind == Kind.PROPHET_KING ? "the Prophet King" : l.title;
			History.Event e = h.log(day, History.EventType.LEGEND_RISES);
			e.person = l.styled();
			e.legend = who;
			e.actor = f.id;
			e.actorName = f.name;
			e.extra = kind.label;
			FactionManager.broadcast(server, Text.literal("✦ " + l.styled() + " is now a " + kind.label + "!").formatted(Formatting.LIGHT_PURPLE));
		}
		if (rules && f.rulerPlayer == null && f.government != Faction.Government.THEOCRACY) {
			f.government = Faction.Government.THEOCRACY;
			FactionManager.broadcast(server, Text.literal("☩ The ").formatted(Formatting.LIGHT_PURPLE).append(f.displayName())
					.append(Text.literal(" become a Theocracy under their prophet.").formatted(Formatting.LIGHT_PURPLE)));
		}
		if (f.religion.equals(rel.id)) f.approval = Math.min(100, f.approval + 1);

		// Preaching: the faith spreads through every town within a long walk of the prophet.
		BlockPos at = whereIs(server, fm, who);
		if (at != null) {
			for (Settlement s : fm.allSettlements()) {
				if (s.cityCenter == null || s.cityCenter.getSquaredDistance(at) > 400 * 400 || s.faction == null) continue;
				Faith.preach(fm, h, s, rel.id, 20, random);
				if (s.faction.freeVillage && !s.faction.religion.equals(rel.id) && random.nextInt(30) == 0) {
					Faith.convertRealm(server, fm, h, s.faction, rel.id, day);
				}
				// A Prophet King's faithful abroad may simply come over.
				if (l.kind == Kind.PROPHET_KING && s.faction != f && !s.isCapital() && s.loyalty < 60 && random.nextInt(40) == 0
						&& followers(s, rel.id) >= 70) {
					Empire.conquer(server, fm, s, f, true);
					History.Event e = h.log(day, History.EventType.CEDED);
					e.actor = f.id;
					e.actorName = f.name;
					e.place = s.name;
					e.person = l.styled();
					e.legend = who;
					e.extra = "faith";
					e.x = s.cityCenter.getX();
					e.z = s.cityCenter.getZ();
				}
			}
			// Miracles, now and then.
			if (random.nextInt(240) == 0) {
				ServerWorld world = server.getOverworld();
				if (world.isRaining()) world.setWeather(12000, 0, false, false);
				History.Event e = h.log(day, History.EventType.MIRACLE);
				e.person = l.styled();
				e.legend = who;
				e.actor = f.id;
				e.actorName = f.name;
				e.x = at.getX();
				e.z = at.getZ();
				f.approval = Math.min(100, f.approval + 5);
				FactionManager.broadcastNear(world, at, 300, Text.literal("✧ A miracle! " + l.styled() + " calls, and the heavens answer.").formatted(Formatting.LIGHT_PURPLE));
			}
		}

		// The Prophet Conqueror's great holy war.
		if (l.kind == Kind.PROPHET_CONQUEROR && !f.atWar && random.nextInt(8) == 0) {
			Faction foe = nearestInfidel(fm, f);
			if (foe != null) greatHolyWar(server, fm, h, f, foe, l, day);
		}
	}

	static int followers(Settlement s, String religion) {
		int n = 0, of = 0;
		for (Faction.Citizen c : s.faction.citizens.values()) {
			if (c.settlement != s.id) continue;
			n++;
			if (c.religion.equals(religion)) of++;
		}
		return n == 0 ? (s.faction.religion.equals(religion) ? 100 : 0) : of * 100 / n;
	}

	@Nullable
	static Faction nearestInfidel(FactionManager fm, Faction f) {
		BlockPos c = f.center();
		if (c == null) return null;
		Faction best = null;
		double bestD = 900 * 900;
		for (Faction o : fm.all()) {
			if (o == f || o.religion.equals(f.religion) || o.settlements.isEmpty() || o.center() == null) continue;
			double d = o.center().getSquaredDistance(c);
			if (d < bestD) {
				bestD = d;
				best = o;
			}
		}
		return best;
	}

	/** A holy war: the faithful fight without tiring, and conquered towns take the faith. */
	public static void holyWar(MinecraftServer server, FactionManager fm, Faction f, Faction foe, long day) {
		History h = History.get(server);
		f.holyWar = foe.id;
		History.Event e = h.log(day, History.EventType.HOLY_WAR);
		e.actor = f.id;
		e.actorName = f.name;
		e.target = foe.id;
		e.targetName = foe.name;
		Religion r = Faith.of(h, f);
		e.extra = r == null ? "" : r.name;
	}

	static void greatHolyWar(MinecraftServer server, FactionManager fm, History h, Faction f, Faction foe, Legend prophet, long day) {
		Religion r = Faith.of(h, f);
		fm.setRelation(server, f.id, foe.id, -90);
		f.holyWar = foe.id;
		f.greatHolyWar = true;
		f.atWar = true;
		List<String> allies = new ArrayList<>();
		for (Faction o : fm.all()) {
			if (o == f || o == foe || !o.religion.equals(f.religion) || o.settlements.isEmpty() || o.rulerPlayer != null) continue;
			fm.setRelation(server, o.id, foe.id, -70);
			o.holyWar = foe.id;
			o.greatHolyWar = true;
			allies.add(o.name);
		}
		History.Event e = h.log(day, History.EventType.HOLY_WAR);
		e.actor = f.id;
		e.actorName = f.name;
		e.target = foe.id;
		e.targetName = foe.name;
		e.person = prophet.styled();
		e.legend = prophet.who;
		e.extra = (r == null ? "" : r.name) + (allies.isEmpty() ? "" : "|" + String.join(", ", allies));
		FactionManager.broadcast(server, Text.literal("⚔☩ " + prophet.styled() + " proclaims a GREAT HOLY WAR against the ").formatted(Formatting.DARK_RED)
				.append(foe.displayName()).append(Text.literal("!" + (allies.isEmpty() ? "" : " The faithful " + String.join(", ", allies) + " take up arms.")).formatted(Formatting.DARK_RED)));
	}

	// ---------------------------------------------------------------- prophecies

	private static void resolveProphecies(MinecraftServer server, FactionManager fm, History h, long day) {
		Random random = server.getOverworld().getRandom();
		// New prophecies from living prophets.
		for (Religion rel : h.religions.values()) {
			if (rel.prophet == null || h.prophecies.stream().anyMatch(p -> p.religion.equals(rel.id)) || random.nextInt(60) != 0) continue;
			Legend l = h.legends.get(rel.prophet);
			List<Settlement> all = fm.allSettlements();
			if (l == null || all.isEmpty()) continue;
			Settlement target = all.get(random.nextInt(all.size()));
			if (target.faction == null) continue;
			History.Prophecy p = new History.Prophecy();
			p.religion = rel.id;
			p.prophet = l.styled();
			p.settlement = target.id;
			p.owner = target.faction.id;
			p.deadlineDay = day + 6 + random.nextInt(8);
			p.text = "Before " + (p.deadlineDay - day) + " days have passed, " + target.name + " shall fall from the hands of the " + target.faction.name + ".";
			h.prophecies.add(p);
			FactionManager.broadcast(server, Text.literal("📜 " + l.styled() + " prophesies: \"" + p.text + "\"").formatted(Formatting.LIGHT_PURPLE));
		}
		for (History.Prophecy p : new ArrayList<>(h.prophecies)) {
			Settlement s = fm.settlement(p.settlement);
			boolean fell = s == null || s.faction == null || !s.faction.id.equals(p.owner);
			if (fell) {
				h.prophecies.remove(p);
				History.Event e = h.log(day, History.EventType.PROPHECY_FULFILLED);
				e.person = p.prophet;
				e.place = s == null ? "a city now gone" : s.name;
				e.extra = p.text;
				Religion rel = h.religion(p.religion);
				if (rel != null && rel.prophet != null) h.renown(rel.prophet)[History.PIETY] += 20;
				FactionManager.broadcast(server, Text.literal("📜 The prophecy of " + p.prophet + " has come true!").formatted(Formatting.LIGHT_PURPLE));
				// Believers take heart; doubters wonder.
				if (rel != null) {
					for (Settlement t : fm.allSettlements()) if (t.faction != null && random.nextInt(4) == 0) Faith.preach(fm, h, t, rel.id, 10, random);
				}
			} else if (day > p.deadlineDay) {
				h.prophecies.remove(p);
			}
		}
	}

	// ---------------------------------------------------------------- the presence of legends (every 5 seconds)

	public static void aura(MinecraftServer server) {
		ServerWorld world = server.getOverworld();
		History h = History.get(server);
		for (Legend l : h.legends.values()) {
			if (!l.alive || (l.kind != Kind.HERO && l.kind != Kind.EMPEROR && l.kind != Kind.PROPHET_CONQUEROR)) continue;
			LivingEntity e = l.player ? server.getPlayerManager().getPlayer(l.who) : world.getEntity(l.who) instanceof ColonistEntity c ? c : null;
			if (e == null) continue;
			for (ColonistEntity ally : world.getEntitiesByClass(ColonistEntity.class, e.getBoundingBox().expand(16),
					o -> o.isAlive() && o.isSoldier() && o.getFactionId().equals(l.faction))) {
				ally.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 140, 0, true, false));
				if (l.kind == Kind.PROPHET_CONQUEROR) ally.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 140, 0, true, false));
			}
		}
	}

	static long day(ServerWorld world) {
		return world.getTimeOfDay() / 24000L;
	}
}
