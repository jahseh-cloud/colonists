package net.colonists.legend;

import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Settlement;
import net.colonists.law.Law;
import net.colonists.legend.History.Religion;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Religions: how they begin, spread and split, and what their tenets make believers do. */
public final class Faith {
	private Faith() {}

	private static final int[] COLORS = {0xE8C547, 0xC0C0D8, 0x8E5B3A, 0xD2553A, 0x5E8C4A, 0x3E7CB1, 0x9A5BB0, 0xE07A1F,
			0x6B6B6B, 0xF0F0E0, 0x2E8B7A, 0xB03060, 0x7A8F3A, 0x4A4A9A, 0xC09050, 0x40A0C0};

	// ---------------------------------------------------------------- creating faiths

	static Religion create(History h, Random random, @Nullable Religion parent, String founder, long day) {
		Religion r = new Religion();
		String name = History.faithName(random);
		if (parent != null && random.nextInt(3) == 0) name = (random.nextBoolean() ? "The Reformed " : "The True ") + stripThe(parent.name);
		r.name = uniqueName(h, name);
		r.id = r.name.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
		while (h.religions.containsKey(r.id)) r.id += "_" + random.nextInt(10);
		r.color = COLORS[h.religions.size() % COLORS.length];
		r.parent = parent == null ? "" : parent.id;
		r.founder = founder;
		r.foundedDay = day;
		Tenet dropped = null;
		if (parent != null) {
			// A reform keeps the old teachings but one: one is dropped, and a new one taken up.
			r.tenets.addAll(parent.tenets);
			List<Tenet> old = new ArrayList<>(r.tenets);
			if (!old.isEmpty()) {
				dropped = old.get(random.nextInt(old.size()));
				r.tenets.remove(dropped);
			}
		}
		while (r.tenets.size() < 2) {
			Tenet t = Tenet.values()[random.nextInt(Tenet.values().length)];
			if (r.tenets.contains(t) || t == dropped || r.tenets.stream().anyMatch(o -> o.clashesWith(t))) continue;
			r.tenets.add(t);
		}
		r.nextProphetDay = day + 25 + random.nextInt(16);
		h.religions.put(r.id, r);
		h.markDirty();
		return r;
	}

	private static String uniqueName(History h, String name) {
		String n = name;
		int i = 2;
		while (true) {
			String cand = n;
			if (h.religions.values().stream().noneMatch(r -> r.name.equals(cand))) return cand;
			n = name + " " + roman(i++);
		}
	}

	private static String roman(int i) {
		return switch (i) {
			case 2 -> "II";
			case 3 -> "III";
			case 4 -> "IV";
			case 5 -> "V";
			default -> String.valueOf(i);
		};
	}

	static String stripThe(String s) {
		return s.startsWith("The ") ? s.substring(4) : s;
	}

	/** The world begins with a few old faiths. */
	static void ensure(MinecraftServer server, History h, Random random, long day) {
		if (!h.religions.isEmpty()) return;
		for (int i = 0; i < 3; i++) {
			Religion r = create(h, random, null, "time immemorial", day);
			r.nextProphetDay = day + 8 + random.nextInt(18); // the first prophets come sooner
		}
	}

	@Nullable
	public static Religion of(History h, @Nullable Faction f) {
		return f == null ? null : h.religion(f.religion);
	}

	public static boolean has(History h, @Nullable Faction f, Tenet t) {
		Religion r = of(h, f);
		return r != null && r.has(t);
	}

	// ---------------------------------------------------------------- what the faith asks of a realm

	/** Bends a law book to the faith: forbidden laws go, required laws come. */
	public static void constrain(History h, Faction f, EnumSet<Law> laws) {
		Religion r = of(h, f);
		if (r == null) return;
		for (Tenet t : r.tenets) {
			laws.removeAll(t.forbids());
			laws.addAll(t.requires());
		}
	}

	/** True/false if the faith commands or forbids keeping captives, null if it does not care. */
	@Nullable
	public static Boolean captivity(History h, Faction f) {
		Religion r = of(h, f);
		if (r == null) return null;
		for (Tenet t : r.tenets) if (t.captivity() != null) return t.captivity();
		return null;
	}

	/** Why the faith will not allow a law change (for a player ruler), or null. */
	@Nullable
	public static String objection(History h, Faction f, @Nullable Law law, boolean turningOn) {
		Religion r = of(h, f);
		if (r == null) return null;
		for (Tenet t : r.tenets) {
			if (law == null) {
				Boolean c = t.captivity();
				if (c != null && c != turningOn) return r.name + " teaches \"" + t.label + "\".";
			} else if ((turningOn && t.forbids().contains(law)) || (!turningOn && t.requires().contains(law))) {
				return r.name + " teaches \"" + t.label + "\".";
			}
		}
		return null;
	}

	/** How much more (or less) eager believers are to go to war. */
	public static int warlike(History h, Faction f) {
		Religion r = of(h, f);
		if (r == null) return 0;
		int n = 0;
		if (r.has(Tenet.SWORD)) n += 25;
		if (r.has(Tenet.DOMINION)) n += 8;
		if (r.has(Tenet.PEACE)) n -= 35;
		if (r.has(Tenet.ASCETIC)) n -= 5;
		return n;
	}

	public static double production(History h, Faction f) {
		Religion r = of(h, f);
		if (r == null) return 1;
		if (r.has(Tenet.PROSPERITY)) return 1.25;
		if (r.has(Tenet.ASCETIC)) return 0.9;
		return 1;
	}

	public static double birthInterval(History h, Faction f) {
		return has(h, f, Tenet.FERTILITY) ? 0.6 : 1;
	}

	public static int colonyCooldown(History h, Faction f, int usual) {
		return has(h, f, Tenet.WANDERING) ? Math.max(1, usual / 2) : usual;
	}

	// ---------------------------------------------------------------- spreading (once a minute)

	static void tick(MinecraftServer server, FactionManager fm, History h, Random random, long day) {
		ensure(server, h, random, day);
		List<Faction> all = fm.all();
		for (Faction f : all) {
			if (h.religion(f.religion) == null) f.religion = adopt(fm, h, f, random);
			Map<String, Integer> counts = new HashMap<>();
			int adults = 0;
			for (Faction.Citizen c : f.citizens.values()) {
				if (h.religion(c.religion) == null) c.religion = f.religion;
				// The state's faith wins hearts slowly.
				if (!c.religion.equals(f.religion) && random.nextInt(60) == 0) c.religion = f.religion;
				if (!c.child) {
					adults++;
					counts.merge(c.religion, 1, Integer::sum);
				}
			}
			// The realm converts when most of its people (and its ruler) already have.
			Faction.Citizen ruler = f.rulerCitizen();
			for (Map.Entry<String, Integer> e : counts.entrySet()) {
				if (e.getKey().equals(f.religion) || adults < 3) continue;
				boolean rulerToo = ruler == null || ruler.religion.equals(e.getKey());
				if (e.getValue() * 100 >= adults * 60 && rulerToo && f.rulerPlayer == null) {
					convertRealm(server, fm, h, f, e.getKey(), day);
					break;
				}
			}
			// Holy wars end with the war.
			if (!f.holyWar.isEmpty()) {
				Faction foe = fm.get(f.holyWar);
				if (foe == null || fm.relation(f.id, foe.id) > FactionManager.WAR) {
					f.holyWar = "";
					f.greatHolyWar = false;
				}
			}
		}
		// Faiths with no believers left fade into memory.
		h.religions.values().removeIf(r -> r.prophet == null && all.stream().noneMatch(f -> f.religion.equals(r.id)
				|| f.citizens.values().stream().anyMatch(c -> c.religion.equals(r.id))) && h.religions.size() > 1);
	}

	/** A faction with no faith takes that of its people, its neighbours, or any. */
	static String adopt(FactionManager fm, History h, Faction f, Random random) {
		Map<String, Integer> counts = new HashMap<>();
		for (Faction.Citizen c : f.citizens.values()) if (h.religion(c.religion) != null) counts.merge(c.religion, 1, Integer::sum);
		if (!counts.isEmpty()) return counts.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
		BlockPos here = f.center();
		if (here != null) {
			Faction near = null;
			double best = Double.MAX_VALUE;
			for (Faction o : fm.all()) {
				if (o == f || h.religion(o.religion) == null || o.center() == null) continue;
				double d = o.center().getSquaredDistance(here);
				if (d < best) {
					best = d;
					near = o;
				}
			}
			if (near != null && best < 600 * 600 && random.nextInt(3) != 0) return near.religion;
		}
		List<String> ids = new ArrayList<>(h.religions.keySet());
		return ids.isEmpty() ? "" : ids.get(random.nextInt(ids.size()));
	}

	public static void convertRealm(MinecraftServer server, FactionManager fm, History h, Faction f, String religion, long day) {
		Religion r = h.religion(religion);
		if (r == null || religion.equals(f.religion)) return;
		f.religion = religion;
		History.Event e = h.log(day, History.EventType.CONVERSION);
		e.actor = f.id;
		e.actorName = f.name;
		e.extra = r.name;
		BlockPos c = f.center();
		if (c != null) {
			e.x = c.getX();
			e.z = c.getZ();
		}
		FactionManager.broadcast(server, Text.literal("☩ The ").formatted(Formatting.LIGHT_PURPLE).append(f.displayName())
				.append(Text.literal(" embrace " + r.name + ".").formatted(Formatting.LIGHT_PURPLE)));
	}

	/** People of a settlement (and, for a village, the village itself) turn toward a faith. */
	static void preach(FactionManager fm, History h, Settlement s, String religion, int chanceIn, Random random) {
		if (s.faction == null) return;
		for (Faction.Citizen c : s.faction.citizens.values()) {
			if (c.settlement == s.id && !c.religion.equals(religion) && random.nextInt(chanceIn) == 0) c.religion = religion;
		}
	}
}
