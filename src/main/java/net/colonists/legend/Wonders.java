package net.colonists.legend;

import net.colonists.build.Blueprint;
import net.colonists.build.Blueprints;
import net.colonists.build.Site;
import net.colonists.build.WonderBlueprints;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Settlement;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The wonders of the world: one of each, raced for by the great realms, raised block by block over many days, held by
 * whoever holds their city, and lost when they fall into ruin or are razed.
 */
public final class Wonders {
	private Wonders() {}

	public enum Type {
		CATHEDRAL(WonderBlueprints.CATHEDRAL, 2, "Its faith spreads faster and its prophets come sooner."),
		LIBRARY(WonderBlueprints.LIBRARY, 2, "Its realm's people become legends more readily; chroniclers flock to it."),
		PALACE(WonderBlueprints.PALACE, 3, "Loyalty rises in every town of the realm; the court is content."),
		FORGE(WonderBlueprints.FORGE, 2, "Its realm's soldiers fight in fine-forged steel (they shrug off blows)."),
		MAUSOLEUM(WonderBlueprints.MAUSOLEUM, 2, "Its realm's legends are never forgotten: their kin inherit their renown."),
		PANTHEON(WonderBlueprints.PANTHEON, 2, "All gods are honoured: no holy war is waged on its realm, and faiths live side by side."),
		BAZAAR(WonderBlueprints.BAZAAR, 2, "Trade flourishes: the realm earns more, and newcomers arrive."),
		OBSERVATORY(WonderBlueprints.OBSERVATORY, 3, "The heavens are read: its realm sees the turning of the ages coming, and adventurers thrive."),
		COLOSSUS(WonderBlueprints.COLOSSUS, 3, "A sight to behold: trade and production soar."),
		GARDENS(WonderBlueprints.GARDENS, 2, "Plenty and beauty: children come sooner and the people are happy."),
		ARENA(WonderBlueprints.ARENA, 2, "Games for the people: approval rises, and heroes are made faster."),
		GREAT_WALL(WonderBlueprints.GREAT_WALL, 2, "Its city is nigh impregnable: sieges there go three times slower."),
		HARBOR(WonderBlueprints.HARBOR, 2, "Colonies and caravans reach much farther."),
		SHRINE(WonderBlueprints.SHRINE, 1, "A place of pilgrimage for a prophet's faith: piety and conversion spread from it.");

		public final Blueprint blueprint;
		public final int era;
		public final String effect;

		Type(Blueprint blueprint, int era, String effect) {
			this.blueprint = blueprint;
			this.era = era;
			this.effect = effect;
		}

		public String label() {
			return this.blueprint.name;
		}

		@Nullable
		public static Type of(String blueprintId) {
			for (Type t : values()) if (t.blueprint.id.equals(blueprintId)) return t;
			return null;
		}
	}

	// ---------------------------------------------------------------- who holds what

	@Nullable
	public static History.Wonder record(MinecraftServer server, Type t) {
		return History.get(server).wonders.get(t.name());
	}

	/** Whether this realm holds this wonder, standing and whole. */
	public static boolean holds(MinecraftServer server, @Nullable Faction f, Type t) {
		if (f == null) return false;
		History.Wonder w = record(server, t);
		if (w == null || w.ruined) return false;
		Settlement s = FactionManager.get(server).settlement(w.settlement);
		return s != null && s.faction == f;
	}

	public static int heldBy(MinecraftServer server, Faction f) {
		int n = 0;
		for (Type t : Type.values()) if (holds(server, f, t)) n++;
		return n;
	}

	/** Whether this town is the seat of a standing wonder of this type. */
	public static boolean seatOf(MinecraftServer server, Settlement s, Type t) {
		History.Wonder w = record(server, t);
		return w != null && !w.ruined && w.settlement == s.id;
	}

	// ---------------------------------------------------------------- commissioning (once a minute)

	static void tick(MinecraftServer server, FactionManager fm, History h, Random random, long day) {
		ServerWorld world = server.getOverworld();
		for (Faction f : fm.all()) {
			if (f.freeVillage || f.rulerPlayer != null) continue;
			Settlement cap = f.capital();
			if (cap == null || cap.rank.ordinal() < Faction.Rank.CITY.ordinal() || f.era < 1) continue;
			Type current = typeOf(f.wonderProject);
			// Abandon a race already lost (unless we can still build a "lesser copy"... we don't).
			if (current != null && h.wonders.containsKey(current.name()) && !h.wonders.get(current.name()).builder.equals(f.id)) {
				f.wonderProject = "";
				current = null;
			}
			if (current == null && random.nextInt(20) == 0) {
				Type pick = choose(server, h, f, random);
				if (pick != null) {
					f.wonderProject = pick.name();
					cap.priority = pick.blueprint.id;
					History.Event e = h.log(day, History.EventType.WONDER_BEGUN);
					e.actor = f.id;
					e.actorName = f.name;
					e.place = cap.name;
					e.extra = pick.label();
					if (cap.cityCenter != null) {
						e.x = cap.cityCenter.getX();
						e.z = cap.cityCenter.getZ();
					}
					FactionManager.broadcast(server, Text.literal("🏛 The ").formatted(Formatting.AQUA).append(f.displayName())
							.append(Text.literal(" begin raising the " + pick.label() + " in " + cap.name + ".").formatted(Formatting.AQUA)));
				}
			} else if (current != null && cap.buildingsOf(current.blueprint.id).isEmpty() && cap.priority.isEmpty()) {
				cap.priority = current.blueprint.id; // the lot was not found yet; try again
			}
		}
		// Every five minutes: are the wonders still standing?
		if (day >= 0 && world.getTime() % 6000 < 1200) integrity(server, fm, h, day);
	}

	@Nullable
	static Type typeOf(String name) {
		if (name == null || name.isEmpty()) return null;
		try {
			return Type.valueOf(name);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** A realm picks the wonder that suits its character, faith and era. */
	@Nullable
	static Type choose(MinecraftServer server, History h, Faction f, Random random) {
		List<Type> options = new ArrayList<>();
		List<Integer> weights = new ArrayList<>();
		Faction.Citizen ruler = f.rulerCitizen();
		int aggression = ruler == null ? 50 : ruler.aggression, kindness = ruler == null ? 50 : ruler.kindness;
		History.Religion faith = Faith.of(h, f);
		for (Type t : Type.values()) {
			if (h.wonders.containsKey(t.name()) || f.era < t.era) continue;
			int w = 10;
			switch (t) {
				case CATHEDRAL -> w += faith == null ? -5 : 15;
				case ARENA, GREAT_WALL, FORGE -> w += (aggression - 50) / 3;
				case LIBRARY, OBSERVATORY, GARDENS -> w += (kindness - 50) / 3;
				case PALACE -> w += f.settlements.size() * 3;
				case PANTHEON -> w += faith != null && faith.has(Tenet.PEACE) ? 15 : 0;
				case BAZAAR, COLOSSUS, HARBOR -> w += faith != null && faith.has(Tenet.PROSPERITY) ? 15 : 5;
				case SHRINE -> {
					if (faith == null || h.legends.values().stream().noneMatch(l -> l.kind.prophet() && l.religion.equals(faith.id) && l.faction.equals(f.id))) continue;
					w += 20;
				}
				default -> {
				}
			}
			if (faith != null && faith.has(Tenet.ASCETIC) && (t == Type.PALACE || t == Type.COLOSSUS || t == Type.BAZAAR)) continue;
			if (w <= 0) continue;
			options.add(t);
			weights.add(w);
		}
		if (options.isEmpty()) return null;
		int total = weights.stream().mapToInt(Integer::intValue).sum(), roll = random.nextInt(total);
		for (int i = 0; i < options.size(); i++) {
			roll -= weights.get(i);
			if (roll < 0) return options.get(i);
		}
		return options.get(0);
	}

	// ---------------------------------------------------------------- built, ruined, razed

	/** Called when builders finish a wonder (or finish repairing one). */
	public static void onBuilt(MinecraftServer server, Settlement s, BlockPos origin, Blueprint bp) {
		Type t = Type.of(bp.id);
		if (t == null || s.faction == null) return;
		History h = History.get(server);
		long day = server.getOverworld().getTimeOfDay() / 24000L;
		History.Wonder w = h.wonders.get(t.name());
		if (w != null && !(w.settlement == s.id && w.origin == origin.asLong())) {
			if (!w.ruined) return; // someone else already raised it; this is merely a fine building
			if (w.settlement != s.id) return;
		}
		boolean restored = w != null;
		if (w == null) {
			w = new History.Wonder();
			w.type = t.name();
			w.builtDay = day;
			w.builder = s.faction.id;
			h.wonders.put(t.name(), w);
		}
		w.settlement = s.id;
		w.origin = origin.asLong();
		BlockRotation rot = s.rotations.get(origin);
		w.rotation = rot == null ? "NONE" : rot.name();
		w.ruined = false;
		if (s.faction.wonderProject.equals(t.name())) s.faction.wonderProject = "";
		History.Event e = h.log(day, History.EventType.WONDER_BUILT);
		e.actor = s.faction.id;
		e.actorName = s.faction.name;
		e.place = s.name;
		e.extra = t.label() + (restored ? "|restored" : "");
		e.x = origin.getX();
		e.z = origin.getZ();
		h.markDirty();
		FactionManager.broadcast(server, Text.literal(restored ? "🏛 The " + t.label() + " in " + s.name + " has been restored!"
				: "🏛 A WONDER OF THE WORLD: the ").formatted(Formatting.GOLD, Formatting.BOLD).append(restored ? Text.empty()
				: Text.literal(t.label() + " stands complete in " + s.name + ", raised by the ").formatted(Formatting.GOLD)
				.append(s.faction.displayName()).append(Text.literal("! " + t.effect).formatted(Formatting.GOLD))));
	}

	/** Fraction of a wonder's blocks still in place. */
	static double standing(ServerWorld world, Settlement s, BlockPos origin, Blueprint bp) {
		Site site = s.site(origin);
		int ok = 0, total = 0;
		for (Blueprint.Entry e : bp.entries) {
			if (e.mat() == Blueprint.Mat.FIXED && e.fixed() != null && e.fixed().isAir()) continue;
			total++;
			BlockState st = world.getBlockState(e.at(site));
			if (!st.isAir() && !st.isLiquid()) ok++;
		}
		return total == 0 ? 1 : ok / (double) total;
	}

	private static void integrity(MinecraftServer server, FactionManager fm, History h, long day) {
		ServerWorld world = server.getOverworld();
		for (History.Wonder w : h.wonders.values()) {
			if (w.ruined) continue;
			Settlement s = fm.settlement(w.settlement);
			Type t = typeOf(w.type);
			if (s == null || t == null) continue;
			BlockPos o = BlockPos.fromLong(w.origin);
			if (!world.isChunkLoaded(o.getX() >> 4, o.getZ() >> 4)) continue;
			if (standing(world, s, o, t.blueprint) < 0.6) {
				w.ruined = true;
				History.Event e = h.log(day, History.EventType.WONDER_RUINED);
				e.actor = s.faction == null ? "" : s.faction.id;
				e.actorName = s.faction == null ? "" : s.faction.name;
				e.place = s.name;
				e.extra = t.label();
				e.x = o.getX();
				e.z = o.getZ();
				FactionManager.broadcast(server, Text.literal("🏚 The " + t.label() + " in " + s.name + " lies in ruins!").formatted(Formatting.GRAY));
			}
		}
	}

	/** On conquest: a zealot or a brute may raze the wonders of the fallen. */
	public static void onConquest(MinecraftServer server, Settlement s, Faction winner, Faction loser) {
		History h = History.get(server);
		ServerWorld world = server.getOverworld();
		Random random = world.getRandom();
		for (History.Wonder w : new ArrayList<>(h.wonders.values())) {
			if (w.settlement != s.id || w.ruined) continue;
			Type t = typeOf(w.type);
			if (t == null) continue;
			boolean infidel = !winner.religion.isEmpty() && !winner.religion.equals(loser.religion);
			boolean zealot = Legends.realmHas(server, winner, History.Kind.PROPHET_CONQUEROR) || !winner.holyWar.isEmpty();
			Faction.Citizen ruler = winner.rulerCitizen();
			boolean brute = ruler != null && ruler.kindness < 25 && random.nextInt(3) == 0;
			boolean holyTarget = t == Type.CATHEDRAL || t == Type.SHRINE || t == Type.PANTHEON;
			if (!((zealot && infidel && holyTarget) || brute)) continue;
			raze(server, h, s, w, t, winner);
		}
	}

	static void raze(MinecraftServer server, History h, Settlement s, History.Wonder w, Type t, Faction by) {
		ServerWorld world = server.getOverworld();
		BlockPos o = BlockPos.fromLong(w.origin);
		Site site = s.site(o);
		Random random = world.getRandom();
		if (world.isChunkLoaded(o.getX() >> 4, o.getZ() >> 4)) {
			for (Blueprint.Entry e : t.blueprint.entries) {
				if (random.nextInt(10) < 7) world.setBlockState(e.at(site), Blocks.AIR.getDefaultState(), 2);
			}
		}
		w.ruined = true;
		// Razed, not merely damaged: the builders will not rush to restore it.
		s.buildingTypes.remove(o);
		s.built.remove(o);
		long day = world.getTimeOfDay() / 24000L;
		History.Event e = h.log(day, History.EventType.WONDER_RAZED);
		e.actor = by.id;
		e.actorName = by.name;
		e.place = s.name;
		e.extra = t.label();
		e.x = o.getX();
		e.z = o.getZ();
		FactionManager.broadcast(server, Text.literal("🔥 The ").formatted(Formatting.DARK_RED).append(by.displayName())
				.append(Text.literal(" raze the " + t.label() + " of " + s.name + "!").formatted(Formatting.DARK_RED)));
	}

	// ---------------------------------------------------------------- the Great Forge's steel (every five seconds)

	public static void aura(MinecraftServer server) {
		ServerWorld world = server.getOverworld();
		FactionManager fm = FactionManager.get(server);
		History.Wonder w = History.get(server).wonders.get(Type.FORGE.name());
		if (w == null || w.ruined) return;
		Settlement s = fm.settlement(w.settlement);
		if (s == null || s.faction == null) return;
		String owner = s.faction.id;
		for (ColonistEntity c : world.getEntitiesByType(net.colonists.ColonistsMod.COLONIST, c -> c.isAlive() && c.isSoldier() && c.getFactionId().equals(owner))) {
			c.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 140, 0, true, false));
		}
	}

	static Map<String, History.Wonder> all(MinecraftServer server) {
		return History.get(server).wonders;
	}
}
