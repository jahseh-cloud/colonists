package net.colonists.faction;

import net.colonists.entity.ColonistEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * City-state politics, run once a minute: rulers and succession, elections, approval, coups and revolutions,
 * laws, ruler-driven diplomacy (wars, peace, surrender into tributaries, independence), and tribute.
 */
public final class Politics {
	private Politics() {}

	public static final long ELECTION_INTERVAL = 72000; // three Minecraft days

	private static final Map<ColonistEntity.Job, Double> JOB_SHARE = Map.of(
			ColonistEntity.Job.FARMER, 0.22,
			ColonistEntity.Job.WOODCUTTER, 0.16,
			ColonistEntity.Job.MINER, 0.15,
			ColonistEntity.Job.BUILDER, 0.15,
			ColonistEntity.Job.GUARD, 0.14,
			ColonistEntity.Job.RANCHER, 0.07,
			ColonistEntity.Job.BLACKSMITH, 0.06,
			ColonistEntity.Job.COOK, 0.06,
			ColonistEntity.Job.MERCHANT, 0.04);

	/** Jobs that need their workplace built first. */
	private static final Map<ColonistEntity.Job, String> WORKPLACE = Map.of(
			ColonistEntity.Job.RANCHER, "animal_pen",
			ColonistEntity.Job.BLACKSMITH, "smithy",
			ColonistEntity.Job.COOK, "bakery",
			ColonistEntity.Job.MERCHANT, "market");

	/** The job the city most needs filled. */
	public static ColonistEntity.Job chooseJob(@Nullable Settlement f, UUID self) {
		if (f == null) return ColonistEntity.Job.WOODCUTTER;
		Map<String, Integer> counts = new HashMap<>();
		int adults = 0;
		for (Map.Entry<UUID, Faction.Citizen> e : f.faction.citizens.entrySet()) {
			if (e.getKey().equals(self) || e.getValue().child || e.getValue().settlement != f.id) continue;
			adults++;
			counts.merge(e.getValue().job, 1, Integer::sum);
		}
		// Food first: the founder farms, the second settler builds, the third cuts timber.
		if (adults == 0) return ColonistEntity.Job.FARMER;
		// The ruler's garrison order comes first.
		if (f.garrison >= 0 && counts.getOrDefault("GUARD", 0) < f.garrison && counts.getOrDefault("FARMER", 0) > 0) return ColonistEntity.Job.GUARD;
		if (counts.getOrDefault("BUILDER", 0) == 0) return ColonistEntity.Job.BUILDER;
		if (counts.getOrDefault("WOODCUTTER", 0) == 0) return ColonistEntity.Job.WOODCUTTER;
		ColonistEntity.Job best = ColonistEntity.Job.FARMER;
		double bestGap = -Double.MAX_VALUE;
		for (Map.Entry<ColonistEntity.Job, Double> e : JOB_SHARE.entrySet()) {
			if (e.getKey() == ColonistEntity.Job.GUARD && f.garrison >= 0) continue;
			String workplace = WORKPLACE.get(e.getKey());
			if (workplace != null && !f.hasBuilt(workplace)) continue;
			// Specialists: one per workplace is plenty.
			if (workplace != null && counts.getOrDefault(e.getKey().name(), 0) >= f.buildingsOf(workplace).size()) continue;
			double gap = e.getValue() * (adults + 1) - counts.getOrDefault(e.getKey().name(), 0);
			if (gap > bestGap) {
				bestGap = gap;
				best = e.getKey();
			}
		}
		return best;
	}

	/** When a new workplace opens and nobody works there, someone from a crowded trade takes it. */
	@Nullable
	public static ColonistEntity.Job reconsiderJob(@Nullable Settlement f, UUID self, ColonistEntity.Job current) {
		if (f == null || current == ColonistEntity.Job.NONE || WORKPLACE.containsKey(current)) return null;
		if (f.garrison >= 0) {
			int guards = f.countJob("GUARD");
			if (current == ColonistEntity.Job.GUARD && guards > f.garrison) return chooseJob(f, self) == ColonistEntity.Job.GUARD ? ColonistEntity.Job.FARMER : chooseJob(f, self);
			if (current != ColonistEntity.Job.GUARD && current != ColonistEntity.Job.FARMER && current != ColonistEntity.Job.BUILDER
					&& guards < f.garrison && f.countJob(current.name()) > 1) return ColonistEntity.Job.GUARD;
		}
		if (f.countJob(current.name()) <= 1) return null;
		for (Map.Entry<ColonistEntity.Job, String> e : WORKPLACE.entrySet()) {
			if (f.hasBuilt(e.getValue()) && f.countJob(e.getKey().name()) == 0) return e.getKey();
		}
		return null;
	}

	public static void tick(MinecraftServer server, FactionManager fm) {
		Random random = server.getOverworld().getRandom();
		long now = server.getOverworld().getTime();
		List<Faction> all = fm.all();
		for (Faction f : all) {
			if (f.freeVillage) {
				// Free villages are governed by their elders: no throne to fight over, no laws to rewrite.
				f.rulerName = "the Elders";
				if (f.warWeariness > 0 && random.nextInt(2) == 0) f.warWeariness--;
				continue;
			}
			Statecraft.tickOpinions(server, fm, f);
			if (f.rulerPlayer != null) {
				Statecraft.playerRule(server, fm, f, now, random);
			}
			if (f.rulerPlayer != null) {
				// A player rules: the realm lives on, but the ruler writes the laws and steers the state.
				for (Settlement st : new java.util.ArrayList<>(f.settlements.values())) growth(server, st);
				updateApproval(f, random);
				if (f.government == Faction.Government.REPUBLIC && now - f.lastElection > ELECTION_INTERVAL) election(server, fm, f, now);
				unrest(server, fm, f, now, random);
				if (f.warWeariness > 0 && random.nextInt(2) == 0) f.warWeariness--;
				continue;
			}
			ensureRuler(server, fm, f, now, random);
			Statecraft.considerHeir(server, fm, f);
			for (Settlement st : new java.util.ArrayList<>(f.settlements.values())) growth(server, st);
			updateApproval(f, random);
			if (f.government == Faction.Government.REPUBLIC && now - f.lastElection > ELECTION_INTERVAL) election(server, fm, f, now);
			reforms(server, fm, f, now, random);
			laws(server, f);
			unrest(server, fm, f, now, random);
			if (f.warWeariness > 0 && random.nextInt(2) == 0) f.warWeariness--;
		}
		Empire.tick(server, fm, random);
		Villages.tick(server, fm, random);
		net.colonists.legend.Legends.tick(server, fm, random);
		if (now % 6000 < 1200) net.colonists.legend.Chronicles.tick(server, fm);
		net.colonists.economy.Economy.tick(server, fm);
		foreignPolicy(server, fm, fm.all(), random);
		if (now % 12000 < 1200) tribute(server, fm, all);
		fm.markDirty();
	}

	// ---------------------------------------------------------------- rulers

	private static void ensureRuler(MinecraftServer server, FactionManager fm, Faction f, long now, Random random) {
		if (f.ruler != null && f.citizens.containsKey(f.ruler)) {
			Faction.Citizen r = f.citizens.get(f.ruler);
			f.rulerName = r.fullName();
			return;
		}
		boolean hadRuler = f.ruler != null;
		String oldName = f.rulerName;
		if (hadRuler && f.government == Faction.Government.MONARCHY && Statecraft.heirSucceeds(server, fm, f)) return;
		if (f.government == Faction.Government.REPUBLIC) {
			election(server, fm, f, now);
			return;
		}
		UUID heir = null;
		if (f.government == Faction.Government.MONARCHY && !f.dynasty.isEmpty()) {
			heir = best(f, c -> !c.child && !c.bonded && c.surname.equals(f.dynasty), c -> c.ambition + c.popularity);
		}
		if (heir == null) {
			heir = best(f, c -> !c.child && !c.bonded, c -> c.ambition + c.aggression / 2 + c.popularity);
			if (heir != null && f.government == Faction.Government.MONARCHY) f.dynasty = f.citizens.get(heir).surname;
		}
		if (heir == null) return;
		setRuler(f, heir);
		net.colonists.legend.Legends.event(server, net.colonists.legend.History.EventType.CROWNED, f, null, null).person = f.rulerName;
		if (hadRuler || f.citizens.size() > 1) {
			MutableText msg = Text.literal("👑 ").append(Text.literal(f.rulerName).formatted(Formatting.BOLD))
					.append(Text.literal(hadRuler ? " succeeds " + oldName + " as " : " becomes ").formatted(Formatting.YELLOW))
					.append(Text.literal(f.government.title + " of the ").formatted(Formatting.YELLOW)).append(f.displayName());
			FactionManager.broadcast(server, msg);
		}
	}

	private static void setRuler(Faction f, UUID ruler) {
		f.ruler = ruler;
		Faction.Citizen c = f.citizens.get(ruler);
		f.rulerName = c == null ? "?" : c.fullName();
		f.approval = Math.max(f.approval, 55);
	}

	@Nullable
	private static UUID best(Faction f, java.util.function.Predicate<Faction.Citizen> filter,
							 java.util.function.ToIntFunction<Faction.Citizen> score) {
		return f.citizens.entrySet().stream().filter(e -> filter.test(e.getValue()))
				.max(Comparator.comparingInt(e -> score.applyAsInt(e.getValue()))).map(Map.Entry::getKey).orElse(null);
	}

	/** Every adult votes for the candidate closest to their own temperament; members who are players vote too. */
	public static void election(MinecraftServer server, FactionManager fm, Faction f, long now) {
		List<Map.Entry<UUID, Faction.Citizen>> candidates = f.citizens.entrySet().stream().filter(e -> !e.getValue().child && !e.getValue().bonded)
				.sorted(Comparator.comparingInt((Map.Entry<UUID, Faction.Citizen> e) -> -(e.getValue().ambition + e.getValue().popularity)))
				.limit(3).toList();
		if (candidates.isEmpty() && f.playerCandidates.isEmpty() && f.rulerPlayer == null) return;
		Map<UUID, Integer> votes = new HashMap<>();
		for (Faction.Citizen voter : f.citizens.values()) {
			if (voter.child) continue;
			UUID choice = null;
			int bestScore = Integer.MIN_VALUE;
			for (Map.Entry<UUID, Faction.Citizen> cand : candidates) {
				Faction.Citizen c = cand.getValue();
				int score = c.popularity * 2 - Math.abs(c.aggression - voter.aggression) - Math.abs(c.kindness - voter.kindness);
				if (score > bestScore) {
					bestScore = score;
					choice = cand.getKey();
				}
			}
			if (choice != null) votes.merge(choice, 1, Integer::sum);
		}
		for (UUID choice : f.playerVotes.values()) {
			if (f.citizens.containsKey(choice)) votes.merge(choice, 3, Integer::sum);
		}
		f.playerVotes.clear();
		f.lastElection = now;
		if (Statecraft.playerElection(server, fm, f, votes) || candidates.isEmpty()) return;
		UUID winner = votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(candidates.get(0).getKey());
		boolean reelected = winner.equals(f.ruler);
		setRuler(f, winner);
		f.lastElection = now;
		FactionManager.broadcast(server, Text.literal("🗳 ").append(f.displayName())
				.append(Text.literal(" election: " + f.rulerName + (reelected ? " is re-elected" : " wins") + " with "
						+ votes.getOrDefault(winner, 0) + " votes.").formatted(Formatting.AQUA)));
	}

	// ---------------------------------------------------------------- approval, reforms, laws

	private static void updateApproval(Faction f, Random random) {
		Faction.Citizen ruler = f.rulerCitizen();
		int adults = Math.max(1, f.adults());
		int target = 50;
		if (ruler != null) target += (ruler.kindness - 50) / 4;
		target += f.totalHomes() >= adults ? 8 : -8;
		target += f.totalFood() == 0 ? -8 : Math.min(15, f.totalFood() / 6);
		target -= f.warWeariness * 2;
		if (f.slavery && f.captives > 0) {
			int kindness = 0;
			for (Faction.Citizen c : f.citizens.values()) kindness += c.kindness;
			kindness /= Math.max(1, f.citizens.size());
			target -= Math.max(0, kindness - 40) / 3;
		}
		if (!f.overlord.isEmpty()) target -= 10; // nobody enjoys paying tribute
		target += random.nextInt(7) - 3;
		f.approval = MathHelper.clamp((f.approval * 3 + target) / 4, 0, 100);
	}

	private static void reforms(MinecraftServer server, FactionManager fm, Faction f, long now, Random random) {
		Faction.Citizen ruler = f.rulerCitizen();
		if (ruler == null) return;
		// Only a walled City-State outgrows rule by a chief: it becomes a kingdom or a republic.
		if (f.government == Faction.Government.CHIEFDOM
				&& (f.settlements.values().stream().anyMatch(st -> st.rank == Faction.Rank.CITY_STATE) || f.settlements.size() >= 3)) {
			if (ruler.kindness > 60 && ruler.ambition < 60) {
				changeGovernment(server, f, Faction.Government.REPUBLIC, "The people of the ", " have founded a Republic!");
				election(server, fm, f, now);
			} else {
				f.dynasty = ruler.surname;
				changeGovernment(server, f, Faction.Government.MONARCHY, "", " is now a Kingdom, ruled by House " + ruler.surname + ".");
			}
		}
	}

	/** Settlement rank: Village, Town (6 houses), City (15 houses, walls begin), City-State (15 houses and a finished wall). */
	private static void growth(MinecraftServer server, Settlement f) {
		if (f.cityCenter == null) return;
		if (f.homes.size() >= 15 && !f.wallPlanned) {
			City.planWall(f);
			FactionManager.broadcast(server, Text.literal("🧱 ").formatted(Formatting.GRAY).append(f.displayName())
					.append(Text.literal(" has begun building a city wall.").formatted(Formatting.GRAY)));
		}
		if (f.wallPlanned) {
			int progress = City.wallProgress(server.getOverworld(), f);
			if (progress >= 0) {
				f.wallProgress = progress;
				if (progress >= 95) f.wallBuilt = true;
				else if (progress < 70) f.wallBuilt = false;
			}
		}
		Faction.Rank rank = f.computeRank();
		if (rank == f.rank) return;
		boolean up = rank.ordinal() > f.rank.ordinal();
		f.rank = rank;
		String message = switch (rank) {
			case VILLAGE -> " has dwindled to a village.";
			case TOWN -> up ? " has grown into a Town!" : " is down to a Town.";
			case CITY -> up ? " has grown into a City!" : "'s wall has fallen into ruin. They are a City once more.";
			case CITY_STATE -> " has finished its wall and become a City-State!";
		};
		FactionManager.broadcast(server, Text.literal(up ? "🏰 " : "").formatted(up ? Formatting.YELLOW : Formatting.GRAY)
				.append(f.displayName()).append(Text.literal(message).formatted(up ? Formatting.YELLOW : Formatting.GRAY)));
	}

	private static void changeGovernment(MinecraftServer server, Faction f, Faction.Government g, String before, String after) {
		f.government = g;
		FactionManager.broadcast(server, Text.literal("📜 " + before).formatted(Formatting.GOLD).append(f.displayName())
				.append(Text.literal(after).formatted(Formatting.GOLD)));
	}

	private static void laws(MinecraftServer server, Faction f) {
		Faction.Citizen ruler = f.rulerCitizen();
		if (ruler == null) return;
		lawBook(server, f, ruler);
		Boolean faithSays = net.colonists.legend.Faith.captivity(net.colonists.legend.History.get(server), f);
		if (!f.slavery && ((ruler.kindness < 35 && ruler.aggression > 50) || (Boolean.TRUE.equals(faithSays) && ruler.kindness < 55)) && !Boolean.FALSE.equals(faithSays)) {
			f.slavery = true;
			FactionManager.broadcast(server, Text.literal("⛓ " + f.rulerName + " has decreed that the ").formatted(Formatting.DARK_RED)
					.append(f.displayName()).append(Text.literal(" will take captives from villager settlements.").formatted(Formatting.DARK_RED)));
		} else if (f.slavery && (ruler.kindness > 65 || f.approval < 20 || Boolean.FALSE.equals(faithSays))) {
			f.slavery = false;
			FactionManager.broadcast(server, Text.literal("🕊 The ").formatted(Formatting.GREEN).append(f.displayName())
					.append(Text.literal(" have abolished captivity. All captives are to be released.").formatted(Formatting.GREEN)));
		}
	}

	/** The ruler writes the law book in their own image. */
	private static void lawBook(MinecraftServer server, Faction f, Faction.Citizen ruler) {
		java.util.EnumSet<net.colonists.law.Law> want = java.util.EnumSet.of(net.colonists.law.Law.THEFT, net.colonists.law.Law.VANDALISM,
				net.colonists.law.Law.ASSAULT, net.colonists.law.Law.MURDER);
		if (f.rank().ordinal() >= Faction.Rank.TOWN.ordinal() || ruler.kindness < 60) want.add(net.colonists.law.Law.POACHING);
		if (ruler.aggression > 60 || f.government == Faction.Government.MONARCHY) want.add(net.colonists.law.Law.TRESPASS);
		if (f.government == Faction.Government.MONARCHY || ruler.aggression > 70) want.add(net.colonists.law.Law.TREASON);
		boolean hadDeath = f.laws.contains(net.colonists.law.Law.DEATH_PENALTY);
		// The death penalty sticks around until a kind ruler abolishes it.
		if ((ruler.aggression > 65 && ruler.kindness < 45) || (hadDeath && ruler.kindness <= 65)) want.add(net.colonists.law.Law.DEATH_PENALTY);
		net.colonists.legend.Faith.constrain(net.colonists.legend.History.get(server), f, want);
		if (want.equals(f.laws)) return;
		for (net.colonists.law.Law l : net.colonists.law.Law.values()) {
			boolean before = f.laws.contains(l), after = want.contains(l);
			if (before == after || l.ordinal() < net.colonists.law.Law.POACHING.ordinal()) continue;
			FactionManager.broadcast(server, Text.literal("📜 " + f.rulerName + " of the ").formatted(after ? Formatting.GOLD : Formatting.GREEN)
					.append(f.displayName()).append(Text.literal((after ? " decrees a new law: " : " repeals the law of ") + l.label.toLowerCase()
							+ (after ? " (" + l.description + ")." : ".")).formatted(after ? Formatting.GOLD : Formatting.GREEN)));
		}
		f.laws.clear();
		f.laws.addAll(want);
	}

	private static void unrest(MinecraftServer server, FactionManager fm, Faction f, long now, Random random) {
		// Captives rise up when the city is weak or unhappy.
		if (f.slavery && f.captives > 0 && !f.revolt
				&& (f.approval < 30 || f.captives > f.countJob("GUARD") * 3) && random.nextInt(10) == 0) {
			f.revolt = true;
			FactionManager.broadcast(server, Text.literal("🔥 The captives held by the ").formatted(Formatting.GOLD)
					.append(f.displayName()).append(Text.literal(" rise up and break free!").formatted(Formatting.GOLD)));
		}
		if (f.captives <= 0) f.revolt = false;

		// Coups and revolutions when the ruler is hated (player rulers answer to their army instead: see Statecraft).
		if (f.approval >= 25 || f.ruler == null || f.rulerPlayer != null || random.nextInt(5) != 0) return;
		UUID plotter = best(f, c -> !c.child && !c.bonded && c.ambition > 60, c -> c.ambition + c.popularity);
		if (plotter == null || plotter.equals(f.ruler)) return;
		Faction.Citizen p = f.citizens.get(plotter);
		String deposed = f.rulerName;
		if (p.aggression > 60) {
			f.government = Faction.Government.MONARCHY;
			f.dynasty = p.surname;
			setRuler(f, plotter);
			FactionManager.broadcast(server, Text.literal("🗡 Coup in the ").formatted(Formatting.RED).append(f.displayName())
					.append(Text.literal("! " + f.rulerName + " overthrows " + deposed + " and seizes the throne.").formatted(Formatting.RED)));
		} else {
			f.government = Faction.Government.REPUBLIC;
			FactionManager.broadcast(server, Text.literal("✊ Revolution in the ").formatted(Formatting.GOLD).append(f.displayName())
					.append(Text.literal("! " + deposed + " is deposed and a Republic is declared.").formatted(Formatting.GOLD)));
			election(server, fm, f, now);
		}
		f.approval = 55;
	}

	// ---------------------------------------------------------------- foreign policy

	private static void foreignPolicy(MinecraftServer server, FactionManager fm, List<Faction> all, Random random) {
		for (Faction a : all) {
			Faction.Citizen ra = a.rulerCitizen();
			if (ra == null || a.founderPlayer != null) continue;
			for (Faction b : all) {
				if (a == b) continue;
				int rel = fm.relation(a.id, b.id);
				boolean neighbours = closestTowns(a, b) < 350;
				Faction.Citizen rb = b.rulerCitizen();

				// Kind rulers despise slavers.
				if (b.slavery && ra.kindness > 65 && random.nextInt(3) == 0) fm.changeRelation(server, a.id, b.id, -1);

				// Aggressive (or ambitious) rulers pick fights with weaker neighbours to take their land.
				// Faith matters: a religion of the sword makes for warlike rulers (above all against unbelievers); one of peace, for gentle ones.
				net.colonists.legend.History hist = net.colonists.legend.History.get(server);
				int zeal = net.colonists.legend.Faith.warlike(hist, a);
				boolean infidel = !a.religion.isEmpty() && !a.religion.equals(b.religion);
				boolean holy = infidel && net.colonists.legend.Faith.has(hist, a, net.colonists.legend.Tenet.SWORD);
				if (holy) zeal += 15;
				// Young realms need a few days of peace to find their feet; free villages are only worth a war to a real town.
				long today = server.getOverworld().getTimeOfDay() / 24000L;
				boolean settled = a.capital() != null && b.capital() != null && today - a.capital().foundedDay >= 3 && today - b.capital().foundedDay >= 3;
				boolean worthy = !b.freeVillage || a.rank().ordinal() >= Faction.Rank.TOWN.ordinal();
				if (settled && worthy && a.overlord.isEmpty() && neighbours && (ra.aggression + zeal > 70 || ra.ambition + zeal > 80) && !a.atWar && rel > FactionManager.WAR && rel < 10 + zeal / 2
						&& (b.members.size() < a.members.size() || holy) && !b.id.equals(a.overlord) && !a.id.equals(b.overlord)
						&& random.nextInt(zeal >= 0 ? 25 : 120) == 0) {
					fm.setRelation(server, a.id, b.id, -70);
					if (holy) net.colonists.legend.Legends.holyWar(server, fm, a, b, server.getOverworld().getTimeOfDay() / 24000L);
					else net.colonists.legend.Legends.event(server, net.colonists.legend.History.EventType.WAR, a, b, null);
					FactionManager.broadcast(server, Text.literal("⚔ " + a.rulerName + " of the ").formatted(Formatting.RED)
							.append(a.displayName()).append(Text.literal(holy ? " declares a HOLY WAR on the " : " declares war on the ").formatted(Formatting.RED))
							.append(b.displayName()).append(Text.literal("!").formatted(Formatting.RED)));
					continue;
				}

				if (rel <= FactionManager.WAR) {
					// A beaten empire buys peace by ceding the town under attack.
					if (b.warWeariness >= 6 && a.warWeariness < b.warWeariness && random.nextInt(5) == 0
							&& Empire.sueForPeace(server, fm, a, b)) {
						continue;
					}
					// The losing side surrenders and becomes a tributary.
					if (b.members.size() * 2 <= a.members.size() && b.warWeariness >= 8 && b.overlord.isEmpty() && random.nextInt(4) == 0) {
						b.overlord = a.id;
						fm.setRelation(server, a.id, b.id, 30);
						FactionManager.broadcast(server, Text.literal("🏳 The ").formatted(Formatting.GOLD).append(b.displayName())
								.append(Text.literal(" surrender and become a tributary of the ").formatted(Formatting.GOLD))
								.append(a.displayName()).append(Text.literal(".").formatted(Formatting.GOLD)));
						continue;
					}
					// Exhausted sides make peace.
					if (a.warWeariness >= 10 && b.warWeariness >= 6 && rb != null && random.nextInt(6) == 0) {
						fm.setRelation(server, a.id, b.id, -20);
					}
				}
			}
			// Tributaries that grow strong enough fight for independence.
			Faction lord = fm.get(a.overlord);
			if (lord == null) {
				a.overlord = "";
			} else if (a.members.size() * 5 >= lord.members.size() * 4 && ra.ambition > 60 && random.nextInt(20) == 0) {
				a.overlord = "";
				fm.setRelation(server, a.id, lord.id, -70);
				FactionManager.broadcast(server, Text.literal("⚔ The ").formatted(Formatting.RED).append(a.displayName())
						.append(Text.literal(" refuse to pay tribute and rise against the ").formatted(Formatting.RED))
						.append(lord.displayName()).append(Text.literal("!").formatted(Formatting.RED)));
			}
		}
	}

	/** Distance between the two closest towns of two factions. */
	private static double closestTowns(Faction a, Faction b) {
		double best = Double.MAX_VALUE;
		for (Settlement x : a.settlements.values()) {
			for (Settlement y : b.settlements.values()) {
				if (x.cityCenter != null && y.cityCenter != null) best = Math.min(best, Empire.horizontalDist(x.cityCenter, y.cityCenter));
			}
		}
		return best;
	}

	/** Tributaries send part of their stockpile to their overlord every half day (when both stockpiles are loaded). */
	private static void tribute(MinecraftServer server, FactionManager fm, List<Faction> all) {
		ServerWorld world = server.getOverworld();
		for (Faction vassal : all) {
			Faction lord = fm.get(vassal.overlord);
			if (lord == null) continue;
			Settlement vc = vassal.capital(), lc = lord.capital();
			if (vc == null || lc == null) continue;
			List<Inventory> from = Stockpile.chests(world, vc);
			List<Inventory> to = Stockpile.chests(world, lc);
			if (from.isEmpty() || to.isEmpty()) continue;
			int sent = 0;
			for (ItemStack s : Stockpile.take(from, st -> st.contains(net.minecraft.component.DataComponentTypes.FOOD)
					|| st.isOf(Items.IRON_INGOT) || st.isOf(Items.WHEAT) || st.isIn(ItemTags.LOGS) || st.isOf(Items.COBBLESTONE), 24)) {
				sent += s.getCount();
				ItemStack rest = s;
				for (Inventory inv : to) rest = net.colonists.entity.ColonistBrain.insert(inv, rest);
			}
			int coin = vc.treasury / 10;
			vc.treasury -= coin;
			lc.treasury += coin;
			if (sent > 0) {
				FactionManager.broadcastNear(world, vc.cityCenter, 128, Text.literal("The ").formatted(Formatting.GRAY)
						.append(vassal.displayName()).append(Text.literal(" send " + sent + " goods in tribute to the ").formatted(Formatting.GRAY))
						.append(lord.displayName()));
			}
		}
	}
}
