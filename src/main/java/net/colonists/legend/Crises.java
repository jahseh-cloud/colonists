package net.colonists.legend;

import net.colonists.ColonistsMod;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.CityGrowth;
import net.colonists.faction.Empire;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Settlement;
import net.colonists.legend.History.Crisis;
import net.colonists.legend.History.Kind;
import net.colonists.legend.History.Legend;
import net.colonists.world.WorldEdge;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Crises: the great figures who shake the whole world.
 * <ul>
 * <li><b>The Messenger</b> — the mightiest of prophets. He founds a new faith and a realm unlike any other; after him rule the Caliphs,
 * until the faithful split.</li>
 * <li><b>The Khan</b> — a horde out of the wilds, the strongest army in the world. It conquers without mercy; when he dies, his
 * empire breaks apart. Whoever kills him is remembered as the Bane of the Khan.</li>
 * <li><b>The Imperator</b> — after a great realm fractures, a passionate heir sets out to make it whole again, bringing his faith with him.</li>
 * <li><b>The Great</b> — the young ruler of a small nation who takes on the mightiest empire in the world. He will not grow old.</li>
 * </ul>
 */
public final class Crises {
	private Crises() {}

	public enum Type {
		MESSENGER("The Messenger", Kind.MESSENGER, 15, 90), KHAN("The Khan", Kind.KHAN, 10, 45),
		IMPERATOR("The Imperator", Kind.IMPERATOR, 0, 0), GREAT("The Great", Kind.GREAT, 12, 40);

		public final String label;
		public final Kind kind;
		/** Earliest day it may happen, and the days that must pass before it can happen again. */
		public final int earliest, cooldown;

		Type(String label, Kind kind, int earliest, int cooldown) {
			this.label = label;
			this.kind = kind;
			this.earliest = earliest;
			this.cooldown = cooldown;
		}
	}

	static long day(MinecraftServer server) {
		return server.getOverworld().getTimeOfDay() / 24000L;
	}

	@Nullable
	static Crisis active(History h, Type t) {
		for (Crisis c : h.crises) if (c.active() && c.type.equals(t.name())) return c;
		return null;
	}

	static long lastStart(History h, Type t) {
		long last = Long.MIN_VALUE / 2;
		for (Crisis c : h.crises) if (c.type.equals(t.name())) last = Math.max(last, c.started);
		return last;
	}

	// ---------------------------------------------------------------- once a minute

	public static void tick(MinecraftServer server, FactionManager fm, History h, Random random) {
		long day = day(server);
		int running = (int) h.crises.stream().filter(c -> c.active() && c.figure != null).count();
		// New crises, rarely, and more often when the age invites them.
		if (running < 2) {
			for (Type t : new Type[]{Type.MESSENGER, Type.KHAN, Type.GREAT}) {
				if (day < t.earliest || active(h, t) != null || day - lastStart(h, t) < t.cooldown) continue;
				int odds = switch (t) {
					case MESSENGER -> h.faith > 0 ? 240 : 900;
					case KHAN -> h.strife > 0 ? 200 : 700;
					default -> 500;
				};
				if (random.nextInt(odds) == 0 && start(server, t, null) != null) break;
			}
		}
		// Restorers rise from the ruins of broken empires.
		for (History.Fracture fr : h.fractures) {
			if (!fr.claimed && fr.restorerDay >= 0 && day >= fr.restorerDay && active(h, Type.IMPERATOR) == null) {
				fr.claimed = true;
				startImperator(server, fm, h, fr, day, random);
			}
		}
		for (Crisis c : new ArrayList<>(h.crises)) {
			if (!c.active()) continue;
			Type t;
			try {
				t = Type.valueOf(c.type);
			} catch (IllegalArgumentException e) {
				continue;
			}
			Faction realm = fm.get(c.faction);
			if (realm == null) {
				end(server, h, c, "and their realm is gone");
				continue;
			}
			// No weariness for the realms of the great.
			realm.warWeariness = 0;
			switch (t) {
				case KHAN -> khan(server, fm, h, c, realm, day, random);
				case GREAT -> great(server, fm, h, c, realm, day, random);
				case IMPERATOR -> imperator(server, fm, h, c, realm, day, random);
				case MESSENGER -> messenger(server, fm, h, c, realm, day, random);
			}
		}
		h.markDirty();
	}

	// ---------------------------------------------------------------- beginnings

	/** Starts a crisis now (from the world, or an operator's command). Returns null if it could not begin. */
	@Nullable
	public static String start(MinecraftServer server, Type t, @Nullable String reason) {
		FactionManager fm = FactionManager.get(server);
		History h = History.get(server);
		Random random = server.getOverworld().getRandom();
		long day = day(server);
		return switch (t) {
			case KHAN -> startKhan(server, fm, h, day, random);
			case GREAT -> startGreat(server, fm, h, day, random);
			case MESSENGER -> startMessenger(server, fm, h, day, random);
			case IMPERATOR -> {
				History.Fracture fr = h.fractures.stream().filter(x -> !x.claimed).reduce((a, b) -> b).orElse(null);
				if (fr == null) {
					// No broken empire yet: the largest realm's capital will do as the "old empire".
					Faction big = fm.all().stream().filter(f -> !f.freeVillage && h.crises.stream().noneMatch(c -> c.active() && c.faction.equals(f.id)))
							.max(Comparator.comparingInt((Faction f) -> f.settlements.size())).orElse(null);
					if (big == null) yield null;
					fr = new History.Fracture();
					fr.name = big.name;
					fr.day = day;
					for (Settlement s : big.settlements.values()) fr.settlements.add(s.id);
					h.fractures.add(fr);
				}
				fr.claimed = true;
				yield startImperator(server, fm, h, fr, day, random);
			}
		};
	}

	static Crisis open(History h, Type t, UUID figure, String name, Faction realm, long day) {
		Crisis c = new Crisis();
		c.type = t.name();
		c.figure = figure;
		c.figureName = name;
		c.faction = realm.id;
		c.started = day;
		c.lastDay = day;
		h.crises.add(c);
		return c;
	}

	static void herald(MinecraftServer server, History h, Crisis c, Legend l, Faction realm, String line, long day) {
		History.Event e = h.log(day, History.EventType.CRISIS);
		e.person = l.styled();
		e.legend = l.who;
		e.actor = realm.id;
		e.actorName = realm.name;
		e.extra = line;
		BlockPos at = center(realm);
		if (at != null) {
			e.x = at.getX();
			e.z = at.getZ();
		}
		FactionManager.broadcast(server, Text.literal("☄ CRISIS — " + Type.valueOf(c.type).label.toUpperCase() + ": ").formatted(Formatting.DARK_RED, Formatting.BOLD)
				.append(Text.literal(l.styled() + " of the " + realm.name + ". " + line).formatted(Formatting.RED)));
	}

	@Nullable
	static Faction largest(FactionManager fm) {
		return fm.all().stream().filter(f -> !f.freeVillage).max(Comparator.comparingInt((Faction f) -> f.settlements.size()).thenComparingInt(f -> f.members.size())).orElse(null);
	}

	/** The Khan: a horde rides out of the wilds, far from any town. */
	@Nullable
	static String startKhan(MinecraftServer server, FactionManager fm, History h, long day, Random random) {
		ServerWorld world = server.getOverworld();
		BlockPos camp = null;
		List<Settlement> towns = fm.allSettlements().stream().filter(x -> x.cityCenter != null).toList();
		int r = WorldEdge.radius > 0 ? WorldEdge.radius - 150 : 1200;
		for (int i = 0; i < 10 && camp == null; i++) {
			BlockPos near;
			if (!towns.isEmpty()) {
				// Out of the wilds beyond the known world: some way off from a town, in any direction.
				BlockPos from = towns.get(random.nextInt(towns.size())).cityCenter;
				double ang = random.nextDouble() * Math.PI * 2, dist = 260 + random.nextInt(340);
				near = new BlockPos(WorldEdge.clamp(from.getX() + (int) (Math.cos(ang) * dist)), 64, WorldEdge.clamp(from.getZ() + (int) (Math.sin(ang) * dist)));
			} else near = new BlockPos(random.nextInt(2 * r) - r, 64, random.nextInt(2 * r) - r);
			if (!WorldEdge.playable(near.getX(), near.getZ(), 60)) continue;
			for (int cx = -2; cx <= 2; cx++) for (int cz = -2; cz <= 2; cz++) world.getChunk((near.getX() >> 4) + cx, (near.getZ() >> 4) + cz);
			BlockPos c = CityGrowth.bestCenter(world, near, random);
			if (c == null) continue;
			Settlement other = fm.nearestSettlement(c, null);
			if (other != null && other.cityCenter != null && other.cityCenter.getSquaredDistance(c) < 180 * 180) continue;
			camp = c;
		}
		if (camp == null) return "No open land for a horde.";
		String surname = HORDE_NAMES[random.nextInt(HORDE_NAMES.length)];
		Faction horde = fm.createFaction(random, "Horde of " + surname);
		horde.government = Faction.Government.HORDE;
		horde.laws.add(net.colonists.law.Law.DEATH_PENALTY);
		horde.slavery = true;
		Settlement s = fm.foundSettlement(server, horde, camp, false, surname + "'s Camp");
		fm.updateForcedChunks(server);
		world.getChunk(camp.getX() >> 4, camp.getZ() >> 4);
		ColonistEntity khan = warrior(world, horde, s, camp, true);
		if (khan == null) return "The horde could not gather.";
		khan.setSurname(surname);
		khan.ambition = 95;
		khan.aggression = 95;
		khan.kindness = 10;
		khan.syncCitizen();
		for (int i = 0; i < 11; i++) warrior(world, horde, s, camp, false);
		horde.ruler = khan.getUuid();
		horde.rulerName = khan.fullName();
		Legend l = Legends.raise(server, fm, h, khan.getUuid(), Kind.KHAN, day, random);
		l.title = "Khan";
		Crisis c = open(h, Type.KHAN, khan.getUuid(), l.styled(), horde, day);
		herald(server, h, c, l, horde, "A horde rides out of the wilds. No city is safe.", day);
		return null;
	}

	private static final String[] HORDE_NAMES = {"Temur", "Batu", "Arslan", "Ogedai", "Kublai", "Toghrul", "Subutai", "Altan", "Berke", "Hulagu"};

	@Nullable
	static ColonistEntity warrior(ServerWorld world, Faction f, Settlement s, BlockPos at, boolean leader) {
		ColonistEntity c = ColonistsMod.COLONIST.create(world);
		if (c == null) return null;
		Random r = world.getRandom();
		int x = at.getX() + r.nextInt(9) - 4, z = at.getZ() + r.nextInt(9) - 4;
		int y = net.colonists.faction.City.surfaceYOr(world, x, z, at.getY());
		c.refreshPositionAndAngles(x + 0.5, y, z + 0.5, r.nextFloat() * 360, 0);
		c.pendingFaction = f.id;
		c.pendingSettlement = s.id;
		c.initialize(world, world.getLocalDifficulty(c.getBlockPos()), SpawnReason.EVENT, null);
		c.job = ColonistEntity.Job.GUARD;
		c.aggression = Math.max(c.aggression, 70);
		c.getInventory().addStack(new ItemStack(leader ? Items.DIAMOND_SWORD : Items.IRON_SWORD));
		c.getInventory().addStack(new ItemStack(Items.BREAD, 12));
		c.equipStack(EquipmentSlot.CHEST, new ItemStack(leader ? Items.DIAMOND_CHESTPLATE : Items.IRON_CHESTPLATE));
		c.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
		world.spawnEntity(c);
		c.syncCitizen();
		return c;
	}

	/** The Great: the young, ambitious ruler of a small nation turns on the mightiest empire. */
	@Nullable
	static String startGreat(MinecraftServer server, FactionManager fm, History h, long day, Random random) {
		Faction big = largest(fm);
		if (big == null || big.settlements.size() < 3) return "There is no great empire to defy.";
		BlockPos bc = center(big);
		Faction hero = null;
		for (Faction f : fm.all()) {
			if (f == big || f.freeVillage || f.rulerPlayer != null || f.settlements.isEmpty() || f.settlements.size() > 2) continue;
			Faction.Citizen r = f.rulerCitizen();
			if (r == null || center(f) == null || bc == null || center(f).getSquaredDistance(bc) > 1100 * 1100) continue;
			if (hero == null || r.ambition > hero.rulerCitizen().ambition) hero = f;
		}
		if (hero == null) return "No small nation dares.";
		Faction.Citizen r = hero.rulerCitizen();
		r.ambition = Math.max(r.ambition, 90);
		r.aggression = Math.max(r.aggression, 75);
		Legend l = Legends.raise(server, fm, h, hero.ruler, Kind.GREAT, day, random);
		l.title = "the Great";
		Crisis c = open(h, Type.GREAT, hero.ruler, l.styled(), hero, day);
		c.fateDay = day + 10 + random.nextInt(7); // he will not grow old
		c.targets.add(-1);
		c.oldName = big.id;
		fm.setRelation(server, hero.id, big.id, -85);
		Settlement goal = nearestOf(big, center(hero));
		if (goal != null) hero.warGoal = goal.id;
		herald(server, h, c, l, hero, "Ruler of a small nation, he has declared war on the mighty " + big.name + ".", day);
		return null;
	}

	/** The Messenger: the mightiest prophet, with a new faith and a realm of the faithful. */
	@Nullable
	static String startMessenger(MinecraftServer server, FactionManager fm, History h, long day, Random random) {
		UUID chosen = null;
		Faction home = null;
		int best = -1;
		for (Faction f : fm.all()) {
			if (f.freeVillage || f.rulerPlayer != null || f.settlements.isEmpty()) continue;
			for (Map.Entry<UUID, Faction.Citizen> e : f.citizens.entrySet()) {
				Faction.Citizen c = e.getValue();
				if (c.child || c.bonded) continue;
				int score = c.kindness + c.ambition + random.nextInt(60);
				if (score > best) {
					best = score;
					chosen = e.getKey();
					home = f;
				}
			}
		}
		if (chosen == null) return "No one hears the call.";
		Faction.Citizen who = home.citizens.get(chosen);
		History.Religion parent = Faith.of(h, home);
		History.Religion faith = Faith.create(h, random, parent, who.fullName(), day);
		// The strongest faith: three teachings, and always the sword or the way of peace.
		Tenet core = random.nextInt(3) == 0 ? Tenet.PEACE : Tenet.SWORD;
		faith.tenets.removeIf(t -> t.clashesWith(core));
		faith.tenets.add(core);
		while (faith.tenets.size() < 3) {
			Tenet t = Tenet.values()[random.nextInt(Tenet.values().length)];
			if (!faith.tenets.contains(t) && faith.tenets.stream().noneMatch(o -> o.clashesWith(t))) faith.tenets.add(t);
		}
		for (History.Religion r : h.religions.values()) if (chosen.equals(r.prophet)) r.prophet = null;
		faith.prophet = chosen;
		who.religion = faith.id;
		home.ruler = chosen;
		home.rulerName = who.fullName() + " the Messenger";
		home.government = Faction.Government.CALIPHATE;
		home.name = "Faithful of " + Faith.stripThe(faith.name).replaceAll("^(Way|Church|Faith|Order|Covenant|Path|Children|Keepers|Reformed|True) of the ", "");
		Faith.convertRealm(server, fm, h, home, faith.id, day);
		for (Faction.Citizen c : home.citizens.values()) c.religion = faith.id;
		Legend l = Legends.raise(server, fm, h, chosen, Kind.MESSENGER, day, random);
		l.title = "the Messenger";
		l.religion = faith.id;
		Crisis c = open(h, Type.MESSENGER, chosen, l.styled(), home, day);
		c.lastRuler = chosen;
		herald(server, h, c, l, home, "He brings " + faith.name + " (" + Legends.tenets(faith) + ") and the faithful gather to him from every land.", day);
		return null;
	}

	@Nullable
	static String startImperator(MinecraftServer server, FactionManager fm, History h, History.Fracture fr, long day, Random random) {
		// The fragment that holds the most of the old realm, with a ruler of passion, takes up the cause.
		Faction heir = null;
		int held = -1;
		for (Faction f : fm.all()) {
			if (f.freeVillage || f.rulerPlayer != null || f.rulerCitizen() == null) continue;
			Legend already = h.legends.get(f.ruler);
			if (already != null && already.alive && already.kind.crisis()) continue; // one great destiny is enough
			int n = 0;
			for (int id : fr.settlements) {
				Settlement s = fm.settlement(id);
				if (s != null && s.faction == f) n++;
			}
			if (n > held || (n == held && heir != null && f.rulerCitizen().ambition > heir.rulerCitizen().ambition)) {
				held = n;
				heir = f;
			}
		}
		if (heir == null || held <= 0) return "No heir of the old realm remains.";
		Faction.Citizen r = heir.rulerCitizen();
		r.ambition = Math.max(r.ambition, 85);
		Legend l = Legends.raise(server, fm, h, heir.ruler, Kind.IMPERATOR, day, random);
		l.title = "Restitutor";
		Crisis c = open(h, Type.IMPERATOR, heir.ruler, l.styled(), heir, day);
		c.targets.addAll(fr.settlements);
		c.oldName = fr.name;
		History.Religion faith = Faith.of(h, heir);
		herald(server, h, c, l, heir, "He swears to make the realm of the " + fr.name + " whole again" + (faith == null ? "." : ", and to bring " + faith.name + " to every corner of it."), day);
		return null;
	}

	// ---------------------------------------------------------------- what they do

	private static void khan(MinecraftServer server, FactionManager fm, History h, Crisis c, Faction horde, long day, Random random) {
		ServerWorld world = server.getOverworld();
		Settlement camp = horde.capital();
		// The horde grows each day.
		if (day != c.lastDay && camp != null && camp.cityCenter != null && horde.members.size() < 40 && world.isChunkLoaded(camp.cityCenter.getX() >> 4, camp.cityCenter.getZ() >> 4)) {
			c.lastDay = day;
			for (int i = 0; i < 3; i++) warrior(world, horde, camp, camp.cityCenter, false);
			FactionManager.broadcastNear(world, camp.cityCenter, 400, Text.literal("More riders join the " + horde.name + ".").formatted(Formatting.GRAY));
		}
		// Always at war with the nearest realm.
		if (!horde.atWar || horde.warGoal < 0) {
			Settlement target = nearestForeign(fm, horde);
			if (target != null && target.faction != null) {
				fm.setRelation(server, horde.id, target.faction.id, -90);
				horde.warGoal = target.id;
				horde.atWar = true;
			}
		}
	}

	private static void great(MinecraftServer server, FactionManager fm, History h, Crisis c, Faction realm, long day, Random random) {
		Faction big = fm.get(c.oldName);
		if (big != null && !big.settlements.isEmpty()) {
			if (fm.relation(realm.id, big.id) > FactionManager.WAR) fm.setRelation(server, realm.id, big.id, -85);
			Settlement goal = fm.settlement(realm.warGoal);
			if (goal == null || goal.faction != big) {
				Settlement next = nearestOf(big, center(realm));
				if (next != null) realm.warGoal = next.id;
			}
		}
		// Conquered towns love him: their loyalty holds.
		for (Settlement s : realm.settlements.values()) s.loyalty = Math.max(s.loyalty, 60);
		// Fate.
		if (day >= c.fateDay && c.figure != null) {
			ServerWorld world = server.getOverworld();
			if (world.getEntity(c.figure) instanceof ColonistEntity e) {
				e.forcedDeathCause = "a fever";
				e.damage(world.getDamageSources().genericKill(), Float.MAX_VALUE);
			} else {
				// He dies far from anyone's sight; the news travels all the same.
				UUID fig = c.figure;
				Legend l = h.legends.get(fig);
				if (l != null) {
					l.alive = false;
					l.died = day;
				}
				onFigureDeath(server, fig, null);
				if (realm.citizens.containsKey(fig)) fm.removeMember(server, realm.id, fig);
				FactionManager.broadcast(server, Text.literal("🕯 " + c.figureName + " has died of a fever, far from home.").formatted(Formatting.GRAY));
			}
		}
	}

	private static void imperator(MinecraftServer server, FactionManager fm, History h, Crisis c, Faction realm, long day, Random random) {
		int held = 0, total = 0;
		Settlement next = null;
		double best = Double.MAX_VALUE;
		BlockPos home = center(realm);
		for (int id : c.targets) {
			Settlement s = fm.settlement(id);
			if (s == null || s.cityCenter == null) continue;
			total++;
			if (s.faction == realm) {
				held++;
				// The faith comes with the legions.
				if (!realm.religion.isEmpty()) Faith.preach(fm, h, s, realm.religion, 8, random);
				continue;
			}
			double d = home == null ? 0 : home.getSquaredDistance(s.cityCenter);
			if (d < best) {
				best = d;
				next = s;
			}
		}
		if (total > 0 && held * 100 >= total * 80) {
			History.Event e = h.log(day, History.EventType.RESTORATION);
			Legend l = c.figure == null ? null : h.legends.get(c.figure);
			e.person = l == null ? c.figureName : l.styled();
			e.legend = c.figure;
			e.actor = realm.id;
			e.actorName = realm.name;
			e.targetName = c.oldName;
			FactionManager.broadcast(server, Text.literal("🏛 " + e.person + " has restored the realm of the " + c.oldName + "!").formatted(Formatting.GOLD, Formatting.BOLD));
			realm.armyOpinion = 100;
			realm.courtOpinion = 100;
			for (Settlement s : realm.settlements.values()) s.loyalty = 100;
			end(server, h, c, "and the old realm stands whole again");
			return;
		}
		if (next != null && next.faction != null) {
			if (fm.relation(realm.id, next.faction.id) > FactionManager.WAR) {
				fm.setRelation(server, realm.id, next.faction.id, -80);
				boolean holy = !realm.religion.isEmpty() && !realm.religion.equals(next.faction.religion);
				if (holy) Legends.holyWar(server, fm, realm, next.faction, day);
			}
			realm.warGoal = next.id;
		}
	}

	private static void messenger(MinecraftServer server, FactionManager fm, History h, Crisis c, Faction realm, long day, Random random) {
		History.Religion faith = h.religion(realm.religion);
		if (c.figure != null) {
			// The Messenger's word reaches every land.
			Settlement seat = realm.capital();
			if (seat != null && seat.cityCenter != null && faith != null) {
				for (Settlement s : fm.allSettlements()) {
					if (s.cityCenter == null || s.faction == null) continue;
					double d = Math.sqrt(s.cityCenter.getSquaredDistance(seat.cityCenter));
					if (d > 1000) continue;
					Faith.preach(fm, h, s, faith.id, d < 400 ? 6 : 14, random);
					if (s.faction.freeVillage && !s.faction.religion.equals(faith.id) && random.nextInt(12) == 0) {
						Faith.preach(fm, h, s, faith.id, 1, random);
						Faith.convertRealm(server, fm, h, s.faction, faith.id, day);
					}
					// The faithful come over to him of their own will.
					if (s.faction != realm && !s.isCapital() && s.loyalty < 70 && Legends.followers(s, faith.id) >= 60 && random.nextInt(25) == 0) {
						Empire.conquer(server, fm, s, realm, true);
					}
				}
			}
			if (!realm.atWar && random.nextInt(5) == 0 && faith != null && faith.has(Tenet.SWORD)) {
				Faction foe = Legends.nearestInfidel(server, fm, realm);
				Legend l = c.figure == null ? null : h.legends.get(c.figure);
				if (foe != null && l != null) Legends.greatHolyWar(server, fm, h, realm, foe, l, day);
			}
			realm.approval = Math.max(realm.approval, 80);
			return;
		}
		// After him: the Caliphs.
		UUID ruler = realm.ruler;
		if (ruler != null && !ruler.equals(c.lastRuler)) {
			c.lastRuler = ruler;
			c.caliphs++;
			realm.government = Faction.Government.CALIPHATE;
			Faction.Citizen r = realm.citizens.get(ruler);
			String nth = switch (c.caliphs) {
				case 1 -> "first";
				case 2 -> "second";
				case 3 -> "third";
				case 4 -> "fourth";
				default -> c.caliphs + "th";
			};
			History.Event e = h.log(day, History.EventType.CALIPH);
			e.person = r == null ? realm.rulerName : r.fullName();
			e.actor = realm.id;
			e.actorName = realm.name;
			e.extra = nth;
			FactionManager.broadcast(server, Text.literal("☪ " + e.person + " becomes the " + nth + " Caliph of the " + realm.name + ".").formatted(Formatting.LIGHT_PURPLE));
			// By the third Caliph, the faithful quarrel over the succession.
			if (c.caliphs >= 3 && realm.settlements.size() >= 2 && random.nextInt(2) == 0 && faith != null) schism(server, fm, h, c, realm, faith, day, random);
		}
	}

	private static void schism(MinecraftServer server, FactionManager fm, History h, Crisis c, Faction realm, History.Religion faith, long day, Random random) {
		History.Religion branch = Faith.create(h, random, faith, "the partisans of a rival Caliph", day);
		Faction rivals = fm.createFaction(random, "Caliphate of " + Faith.stripThe(branch.name).replaceAll("^(Way|Church|Faith|Order|Covenant|Path|Children|Keepers|Reformed|True) of the ", ""));
		rivals.government = Faction.Government.CALIPHATE;
		rivals.religion = branch.id;
		BlockPos cap = center(realm);
		List<Settlement> towns = new ArrayList<>(realm.settlements.values());
		towns.removeIf(Settlement::isCapital);
		if (cap != null) towns.sort(Comparator.comparingDouble(s -> s.cityCenter == null ? 0 : -s.cityCenter.getSquaredDistance(cap)));
		int n = Math.max(1, towns.size() / 2);
		History.Fracture fr = new History.Fracture();
		fr.name = realm.name;
		fr.day = day;
		for (Settlement s : realm.settlements.values()) fr.settlements.add(s.id);
		for (int i = 0; i < n && i < towns.size(); i++) {
			fm.transferSettlement(towns.get(i), rivals);
			for (Faction.Citizen ci : rivals.citizens.values()) ci.religion = branch.id;
		}
		fm.setRelation(server, realm.id, rivals.id, -60);
		fr.restorerDay = day + 6 + random.nextInt(10);
		h.fractures.add(fr);
		History.Event e = h.log(day, History.EventType.SCHISM);
		e.actor = realm.id;
		e.actorName = realm.name;
		e.extra = rivals.name + " (" + branch.name + ")";
		FactionManager.broadcast(server, Text.literal("☪ SCHISM! The faithful of the " + realm.name + " are torn apart: the " + rivals.name + " follow " + branch.name + ".").formatted(Formatting.DARK_RED));
		c.ended = day; // the age of the first Caliphs ends
	}

	// ---------------------------------------------------------------- endings

	/** A crisis figure died (or vanished). Called from Legends.onDeath. */
	public static void onFigureDeath(MinecraftServer server, UUID who, @Nullable net.minecraft.entity.Entity killer) {
		History h = History.get(server);
		FactionManager fm = FactionManager.get(server);
		long day = day(server);
		for (Crisis c : new ArrayList<>(h.crises)) {
			if (!c.active() || !who.equals(c.figure)) continue;
			Faction realm = fm.get(c.faction);
			Type t = Type.valueOf(c.type);
			if (killer instanceof ServerPlayerEntity p) {
				net.colonists.player.PlayerData.Profile pr = net.colonists.player.PlayerData.of(server, p.getUuid());
				if (t == Type.KHAN) {
					pr.add("khanSlain", 1);
					pr.deed(day, "slew " + c.figureName + " and broke the horde");
					FactionManager.broadcast(server, Text.literal("⚔ " + p.getName().getString() + " has slain " + c.figureName + "! Hail the Bane of the Khan!").formatted(Formatting.GOLD, Formatting.BOLD));
				} else {
					pr.add("slew:" + t.name(), 1);
					pr.deed(day, "slew " + c.figureName);
				}
				Legends.earn(server, p.getUuid(), History.VALOUR, 60);
			}
			switch (t) {
				case KHAN, GREAT -> {
					end(server, h, c, t == Type.KHAN ? "and the horde scatters" : "struck down in his prime");
					if (realm != null) fracture(server, fm, h, realm, t == Type.KHAN ? "Khanate" : "Kingdom", day, server.getOverworld().getRandom());
				}
				case IMPERATOR -> end(server, h, c, "his dream unfinished");
				case MESSENGER -> {
					// The Messenger is gone, but the record stays open: the Caliphs follow him.
					c.figure = null;
					c.lastRuler = who;
					if (realm != null) FactionManager.broadcast(server, Text.literal("☪ " + c.figureName + " has died. The faithful of the " + realm.name + " will choose a Caliph.").formatted(Formatting.LIGHT_PURPLE));
				}
			}
		}
	}

	static void end(MinecraftServer server, History h, Crisis c, String how) {
		c.ended = day(server);
		FactionManager.broadcast(server, Text.literal("☄ The crisis of " + c.figureName + " is over, " + how + ".").formatted(Formatting.GRAY));
	}

	/** A great realm breaks apart among its generals. The capital stays with the heirs; the rest go their own ways. */
	public static void fracture(MinecraftServer server, FactionManager fm, History h, Faction realm, String word, long day, Random random) {
		List<Settlement> towns = new ArrayList<>(realm.settlements.values());
		if (towns.size() < 2) return;
		History.Fracture fr = new History.Fracture();
		fr.name = realm.name;
		fr.day = day;
		for (Settlement s : towns) fr.settlements.add(s.id);
		towns.removeIf(Settlement::isCapital);
		int parts = Math.min(3, towns.size());
		List<Faction> successors = new ArrayList<>();
		for (int i = 0; i < parts; i++) {
			Faction succ = fm.createFaction(random, null);
			succ.name = word + " of " + towns.get(i).name;
			succ.religion = realm.religion;
			successors.add(succ);
		}
		for (int i = 0; i < towns.size(); i++) fm.transferSettlement(towns.get(i), successors.get(i % parts));
		for (Faction a : successors) {
			fm.setRelation(server, a.id, realm.id, -30);
			for (Faction b : successors) if (a != b) fm.setRelation(server, a.id, b.id, -20);
		}
		fr.restorerDay = day + 5 + random.nextInt(11);
		h.fractures.add(fr);
		History.Event e = h.log(day, History.EventType.FRACTURE);
		e.targetName = realm.name;
		e.extra = String.join(", ", successors.stream().map(f -> f.name).toList());
		BlockPos at = center(realm);
		if (at != null) {
			e.x = at.getX();
			e.z = at.getZ();
		}
		FactionManager.broadcast(server, Text.literal("💔 The realm of the " + realm.name + " breaks apart: " + e.extra + " go their own way.").formatted(Formatting.GOLD));
	}

	// ---------------------------------------------------------------- helpers

	@Nullable
	static BlockPos center(Faction f) {
		Settlement s = f.capital();
		if (s == null && !f.settlements.isEmpty()) s = f.settlements.values().iterator().next();
		return s == null ? null : s.center();
	}

	@Nullable
	static Settlement nearestOf(Faction f, @Nullable BlockPos from) {
		Settlement best = null;
		double bestD = Double.MAX_VALUE;
		for (Settlement s : f.settlements.values()) {
			if (s.cityCenter == null) continue;
			double d = from == null ? 0 : from.getSquaredDistance(s.cityCenter);
			if (d < bestD) {
				bestD = d;
				best = s;
			}
		}
		return best;
	}

	@Nullable
	static Settlement nearestForeign(FactionManager fm, Faction f) {
		BlockPos from = center(f);
		Settlement best = null;
		double bestD = Double.MAX_VALUE;
		for (Settlement s : fm.allSettlements()) {
			if (s.faction == null || s.faction == f || s.cityCenter == null) continue;
			double d = from == null ? 0 : from.getSquaredDistance(s.cityCenter);
			if (d < bestD) {
				bestD = d;
				best = s;
			}
		}
		return best;
	}

	/** Extra siege power for the armies of a crisis realm. */
	public static int siegeBonus(MinecraftServer server, Faction realm) {
		for (Crisis c : History.get(server).crises) {
			if (!c.active() || !c.faction.equals(realm.id) || c.figure == null && !c.type.equals(Type.MESSENGER.name())) continue;
			return switch (Type.valueOf(c.type)) {
				case KHAN -> 3;
				case GREAT -> 2;
				case IMPERATOR -> 2;
				case MESSENGER -> c.figure != null ? 3 : 1;
			};
		}
		return 0;
	}

	/** Crisis figures inspire everyone near them (every five seconds). */
	public static void aura(MinecraftServer server) {
		ServerWorld world = server.getOverworld();
		for (Crisis c : History.get(server).crises) {
			if (!c.active() || c.figure == null || !(world.getEntity(c.figure) instanceof ColonistEntity fig)) continue;
			fig.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 140, 1, true, false));
			fig.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 140, 0, true, false));
			for (ColonistEntity ally : world.getEntitiesByClass(ColonistEntity.class, fig.getBoundingBox().expand(24),
					o -> o.isAlive() && o.isSoldier() && o.getFactionId().equals(c.faction))) {
				ally.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 140, c.type.equals(Type.KHAN.name()) ? 1 : 0, true, false));
				if (c.type.equals(Type.KHAN.name()) || c.type.equals(Type.GREAT.name())) ally.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 140, 0, true, false));
			}
		}
	}
}
