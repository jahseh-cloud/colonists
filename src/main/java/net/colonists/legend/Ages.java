package net.colonists.legend;

import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Settlement;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.random.Random;

/**
 * The ages of the world. Milestone ages arrive once, in order (Founding, Kingdoms, Faith, Empires, Wonders); three
 * cycles swing back and forth with the state of the world (Golden ↔ Dark, Faith ↔ Reason, Strife ↔ Peace). Each realm
 * also climbs through its own eras (Timber, Stone, Iron, Grand).
 */
public final class Ages {
	private Ages() {}

	public static final String[] MILESTONES = {"Founding", "Kingdoms", "Faith", "Empires", "Wonders"};
	public static final String[] ERAS = {"Timber", "Stone", "Iron", "Grand"};
	/** Days a cycle must hold before it can turn again. */
	static final int MIN_DAYS = 4;

	public static String name(History h) {
		String core = h.strife > 0 ? "Strife" : h.faith > 0 ? "Faith" : h.faith < 0 ? "Reason" : h.strife < 0 && h.milestone < 3 ? "Peace" : MILESTONES[h.milestone];
		String prefix = h.prosperity > 0 ? "Golden " : h.prosperity < 0 ? "Dark " : "";
		return "the " + prefix + "Age of " + core;
	}

	// ---------------------------------------------------------------- effects

	public static double production(History h) {
		return h.prosperity > 0 ? 1.15 : h.prosperity < 0 ? 0.85 : 1;
	}

	public static double births(History h) {
		return h.prosperity > 0 ? 0.85 : h.prosperity < 0 ? 1.2 : 1;
	}

	/** Change in rulers' appetite for war. */
	public static int zeal(History h) {
		return (h.faith > 0 ? 10 : h.faith < 0 ? -10 : 0) + (h.strife > 0 ? 12 : h.strife < 0 ? -15 : 0);
	}

	/** Days added to (or taken from) the wait for the next prophet. */
	public static int prophetDelay(History h) {
		return (h.faith > 0 ? -5 : h.faith < 0 ? 10 : 0) + (h.prosperity < 0 ? -4 : 0);
	}

	public static int loyaltyDrift(History h) {
		return h.prosperity < 0 ? -1 : h.prosperity > 0 ? 1 : 0;
	}

	/** Fortune's odds for legends (1 in this many per minute, once deserving). */
	public static int legendOdds(History h) {
		return h.prosperity > 0 ? 25 : h.prosperity < 0 ? 50 : 40;
	}

	// ---------------------------------------------------------------- once a minute

	static void tick(MinecraftServer server, FactionManager fm, History h, Random random, long day) {
		java.util.List<Faction> all = fm.all();
		eras(server, fm, h, all, day);

		// Milestones, in order.
		boolean kingdom = all.stream().anyMatch(f -> f.settlements.size() >= 2 || f.settlements.values().stream().anyMatch(s -> s.rank == Faction.Rank.CITY_STATE));
		boolean faithful = h.religions.values().stream().anyMatch(r -> r.prophet != null) || all.stream().anyMatch(f -> !f.holyWar.isEmpty());
		boolean empire = all.stream().anyMatch(f -> f.realmTitle().equals("Empire"));
		boolean wonder = h.wonders.values().stream().anyMatch(w -> !w.ruined);
		boolean[] reached = {true, kingdom, faithful, empire, wonder};
		if (h.milestone + 1 < MILESTONES.length && reached[h.milestone + 1]) {
			h.milestone++;
			announce(server, h, day, "The " + MILESTONES[h.milestone].toLowerCase() + " milestone is reached");
		}

		// The state of the world.
		int towns = 0, approval = 0, treasury = 0;
		for (Settlement s : fm.allSettlements()) {
			if (s.faction == null) continue;
			towns++;
			approval += s.faction.approval;
			treasury += s.treasury;
		}
		int wars = 0;
		for (int i = 0; i < all.size(); i++) for (int j = i + 1; j < all.size(); j++) {
			if (!all.get(i).settlements.isEmpty() && !all.get(j).settlements.isEmpty() && fm.relation(all.get(i).id, all.get(j).id) <= FactionManager.WAR) wars++;
		}
		int conquests = 0, holy = 0, legendDeaths = 0;
		for (History.Event e : h.events) {
			if (day - e.day > 2) continue;
			if (e.type == History.EventType.CONQUERED || e.type == History.EventType.FALL || e.type == History.EventType.REBELLION) conquests++;
			if (e.type == History.EventType.HOLY_WAR || e.type == History.EventType.NEW_FAITH || e.type == History.EventType.MIRACLE) holy++;
			if (e.type == History.EventType.LEGEND_DIES) legendDeaths++;
		}
		boolean prophets = h.religions.values().stream().anyMatch(r -> r.prophet != null);
		if (prophets) h.lastProphetSeenDay = day;

		double prosperity = towns == 0 ? 0 : (approval / (double) towns - 50) + Math.min(25, treasury / (double) towns / 4) - wars * 6 - conquests * 6 - legendDeaths * 4;
		double faith = (prophets ? 25 : 0) + holy * 12 - (day - h.lastProphetSeenDay > 8 ? 35 : 0);
		double strife = wars * 14 + conquests * 10 - (wars == 0 ? 30 : 0);
		h.prosperityMeter += (prosperity - h.prosperityMeter) * 0.04;
		h.faithMeter += (faith - h.faithMeter) * 0.04;
		h.strifeMeter += (strife - h.strifeMeter) * 0.04;

		int p = turn(h.prosperity, h.prosperityMeter, day - h.prosperityDay);
		if (p != h.prosperity) {
			h.prosperity = p;
			h.prosperityDay = day;
			announce(server, h, day, p > 0 ? "Peace and plenty bring a golden age" : p < 0 ? "Hard times fall upon the world" : "The world finds its balance again");
		}
		int f = turn(h.faith, h.faithMeter, day - h.faithDay);
		if (f != h.faith) {
			h.faith = f;
			h.faithDay = day;
			announce(server, h, day, f > 0 ? "Prophets and holy wars stir the hearts of all peoples" : f < 0 ? "With no prophet for many days, folk turn to reason" : "The fervour fades");
		}
		int s = turn(h.strife, h.strifeMeter, day - h.strifeDay);
		if (s != h.strife) {
			h.strife = s;
			h.strifeDay = day;
			announce(server, h, day, s > 0 ? "War spreads across the land" : s < 0 ? "The swords are laid down across the world" : "The wars wind down");
		}
	}

	/** A cycle turns when its meter passes a threshold and it has held long enough. */
	private static int turn(int state, double meter, long held) {
		if (held < MIN_DAYS) return state;
		if (meter > 22) return 1;
		if (meter < -22) return -1;
		if (state != 0 && Math.abs(meter) < 6) return 0;
		return state;
	}

	private static void announce(MinecraftServer server, History h, long day, String why) {
		String name = name(h);
		History.Event e = h.log(day, History.EventType.AGE);
		e.extra = name;
		e.person = why;
		FactionManager.broadcast(server, Text.literal("⌛ " + why + ". So begins " + name + ".").formatted(Formatting.AQUA));
	}

	// ---------------------------------------------------------------- realm eras

	static void eras(MinecraftServer server, FactionManager fm, History h, java.util.List<Faction> all, long day) {
		for (Faction f : all) {
			if (f.freeVillage) continue;
			int era = 0;
			boolean town = false, cityWithSmithy = false, grand = false;
			int treasury = 0;
			for (Settlement s : f.settlements.values()) {
				treasury += s.treasury;
				if (s.rank.ordinal() >= Faction.Rank.TOWN.ordinal()) town = true;
				if (s.rank.ordinal() >= Faction.Rank.CITY.ordinal() && s.hasBuilt("smithy")) cityWithSmithy = true;
				if (s.rank == Faction.Rank.CITY_STATE) grand = true;
			}
			if (town) era = 1;
			if (cityWithSmithy) era = 2;
			if (grand && (treasury >= 150 || Wonders.heldBy(server, f) > 0)) era = 3;
			if (era > f.era) {
				f.era = era;
				History.Event e = h.log(day, History.EventType.ERA);
				e.actor = f.id;
				e.actorName = f.name;
				e.extra = ERAS[era];
				FactionManager.broadcast(server, Text.literal("⚒ The ").formatted(Formatting.AQUA).append(f.displayName())
						.append(Text.literal(" enter the " + ERAS[era] + " Era.").formatted(Formatting.AQUA)));
			}
		}
	}
}
