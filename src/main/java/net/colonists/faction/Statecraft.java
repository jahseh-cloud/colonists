package net.colonists.faction;

import net.colonists.entity.ColonistEntity;
import net.colonists.law.Justice;
import net.colonists.law.Law;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * Ruling as a player: how the army and the court feel about you, whether they carry out your orders, and the
 * four roads to power (founding a faction, winning an election, a coup, or being named heir).
 */
public final class Statecraft {
	private Statecraft() {}

	public enum Order {
		TAX, GARRISON, AUTONOMY, FUND, COLONIZE, WAR, PEACE_WHITE, PEACE_CEDE_THEIRS, PEACE_CEDE_OURS, PEACE_VASSAL,
		ALLY, DEMAND_VASSAL, LAW, CAPTIVITY, GOVERNMENT, BONUS, COUP, STAND, ABDICATE, GIFT_LORDS
	}

	public static final String[] PROJECTS = {"wall", "prison", "work_camp", "gallows", "market", "barracks", "tavern",
			"smithy", "bakery", "barn", "animal_pen", "lumber_camp", "town_hall"};
	public static final int FUND_COST = 25;

	// ---------------------------------------------------------------- opinions

	static int avgGuardAggression(Faction f) {
		int sum = 0, n = 0;
		for (Faction.Citizen c : f.citizens.values()) {
			if (c.job.equals("GUARD")) {
				sum += c.aggression;
				n++;
			}
		}
		return n == 0 ? 50 : sum / n;
	}

	static int avgKindness(Faction f) {
		int sum = 0, n = 0;
		for (Faction.Citizen c : f.citizens.values()) {
			if (!c.child) {
				sum += c.kindness;
				n++;
			}
		}
		return n == 0 ? 50 : sum / n;
	}

	/** Once a minute: opinions drift toward what the state of the realm deserves. */
	static void tickOpinions(MinecraftServer server, FactionManager fm, Faction f) {
		int arrears = 0, treasury = 0, taxPush = 0;
		for (Settlement s : f.settlements.values()) {
			arrears = Math.max(arrears, s.arrears);
			treasury += s.treasury;
			if (s.taxRate >= 0) taxPush += s.taxRate - 10;
		}
		int rep = 0;
		if (f.rulerPlayer != null) {
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(f.rulerPlayer);
			if (p != null) rep = fm.standing(p, f.id);
		}
		int army = 50 - arrears * 10 - f.warWeariness * 2 + rep / 6;
		int aggression = avgGuardAggression(f);
		if (f.atWar && aggression > 55) army += 6;
		if (!f.atWar && aggression > 70) army -= 4;
		int court = (net.colonists.legend.Wonders.holds(server, f, net.colonists.legend.Wonders.Type.PALACE) ? 55 : 50) + Math.min(12, treasury / 20) - arrears * 5 - taxPush / Math.max(1, f.settlements.size()) + rep / 6;
		boolean harsh = f.laws.contains(Law.DEATH_PENALTY) || f.slavery;
		int kindness = avgKindness(f);
		if (harsh) court += (50 - kindness) / 4;
		f.armyOpinion = MathHelper.clamp((f.armyOpinion * 3 + army) / 4, 0, 100);
		// The landed lords sit at court: where there are manors, their mood weighs heaviest.
		int lords = net.colonists.farm.Manors.lordsFavour(f);
		if (lords >= 0) court = (court + lords * 2) / 3;
		f.courtOpinion = MathHelper.clamp((f.courtOpinion * 3 + court) / 4, 0, 100);
	}

	/** How much the realm backs an order (0-100). The government decides who matters most. */
	public static int support(FactionManager fm, Faction f, Order order, @Nullable Faction other) {
		double army, court, people;
		switch (f.government) {
			case MONARCHY -> {
				army = 0.5;
				court = 0.3;
				people = 0.2;
			}
			case REPUBLIC -> {
				army = 0.2;
				court = 0.5;
				people = 0.3;
			}
			default -> {
				army = 0.45;
				court = 0.15;
				people = 0.4;
			}
		}
		double s = f.armyOpinion * army + f.courtOpinion * court + f.approval * people;
		int aggression = avgGuardAggression(f);
		switch (order) {
			case WAR -> {
				s += (aggression - 50) / 2.0 - f.warWeariness * 2;
				if (other != null && other.members.size() < f.members.size()) s += 8;
				if (other != null && fm.relation(f.id, other.id) >= FactionManager.ALLY) s -= 25; // betraying an ally
			}
			case PEACE_WHITE, PEACE_CEDE_OURS -> s += f.warWeariness * 3 - (aggression - 50) / 3.0 - (order == Order.PEACE_CEDE_OURS ? 10 : 0);
			case COLONIZE -> s += 10;
			case GOVERNMENT -> s -= 15;
			case LAW, CAPTIVITY -> s -= 5;
			case TAX -> s -= 5;
			default -> s += 50; // routine administration
		}
		return MathHelper.clamp((int) Math.round(s), 0, 100);
	}

	/** Rolls whether an order is carried out. Half-hearted support means a coin toss. */
	static boolean obeyed(int support, Random random) {
		if (support >= 50) return true;
		if (support < 35) return false;
		return random.nextInt(15) < support - 35;
	}

	// ---------------------------------------------------------------- the four roads to power

	/** A player takes the throne (or the consulship, or the chief's seat). */
	static void crown(MinecraftServer server, Faction f, ServerPlayerEntity p, String how) {
		f.rulerPlayer = p.getUuid();
		f.ruler = null;
		f.rulerName = p.getName().getString();
		f.heirPlayer = null;
		f.playerCandidates.remove(p.getUuid());
		net.colonists.player.PlayerData.of(server, p.getUuid()).add("ruled", 1);
		net.colonists.player.PlayerData.of(server, p.getUuid()).deed(server.getOverworld().getTimeOfDay() / 24000L, how + " and became ruler of the " + f.name);
		net.colonists.legend.Legends.event(server, net.colonists.legend.History.EventType.CROWNED, f, null, null).person = f.rulerName;
		f.armyOpinion = Math.max(f.armyOpinion, 50);
		f.courtOpinion = Math.max(f.courtOpinion, 50);
		FactionManager.broadcast(server, Text.literal("👑 " + f.rulerName + " " + how + " and now rules the ").formatted(Formatting.GOLD)
				.append(f.displayName()).append(Text.literal(" as " + f.government.title + ".").formatted(Formatting.GOLD)));
	}

	/** The army (or the people) remove a player ruler they can no longer stomach. */
	static void depose(MinecraftServer server, Faction f, String why) {
		String name = f.rulerName;
		f.rulerPlayer = null;
		f.ruler = null;
		FactionManager.broadcast(server, Text.literal("🗡 " + name + " has been " + why + " by the ").formatted(Formatting.RED)
				.append(f.displayName()).append(Text.literal("!").formatted(Formatting.RED)));
	}

	/** Called by Politics every minute for player-ruled factions: opinions, the risk of a coup, and elections. */
	static void playerRule(MinecraftServer server, FactionManager fm, Faction f, long now, Random random) {
		ServerPlayerEntity p = server.getPlayerManager().getPlayer(f.rulerPlayer);
		if (p != null) f.rulerName = p.getName().getString();
		if (!f.id.equals(fm.playerFaction(f.rulerPlayer)) && !f.rulerPlayer.equals(f.founderPlayer)) {
			depose(server, f, "removed for abandoning their people");
			return;
		}
		if (f.armyOpinion < 20 && random.nextInt(6) == 0) {
			depose(server, f, "overthrown in a military coup");
			return;
		}
		if (f.approval < 15 && f.courtOpinion < 25 && random.nextInt(8) == 0) {
			depose(server, f, "driven out by a popular uprising");
		}
	}

	/** A Republic's election, with player candidates standing against the colonists' favourites. */
	static boolean playerElection(MinecraftServer server, FactionManager fm, Faction f, Map<UUID, Integer> votes) {
		UUID bestPlayer = null;
		int bestVotes = -1;
		int voters = 0;
		for (Faction.Citizen c : f.citizens.values()) if (!c.child) voters++;
		java.util.Set<UUID> candidates = new java.util.HashSet<>(f.playerCandidates);
		if (f.rulerPlayer != null) candidates.add(f.rulerPlayer);
		for (UUID cand : candidates) {
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(cand);
			int rep = p == null ? 0 : fm.standing(p, f.id);
			double incumbency = cand.equals(f.rulerPlayer) ? (f.approval - 50) / 100.0 : 0;
			int v = (int) Math.round(voters * MathHelper.clamp(rep / 150.0 + f.courtOpinion / 400.0 + incumbency, 0, 0.9));
			if (v > bestVotes) {
				bestVotes = v;
				bestPlayer = cand;
			}
		}
		f.playerCandidates.clear();
		int bestColonist = votes.values().stream().max(Integer::compare).orElse(0);
		if (bestPlayer == null || bestVotes <= bestColonist) {
			if (f.rulerPlayer != null) {
				f.rulerPlayer = null;
				FactionManager.broadcast(server, Text.literal("🗳 The ").append(f.displayName())
						.append(Text.literal(" have voted their player consul out of office.").formatted(Formatting.AQUA)));
			}
			return false;
		}
		ServerPlayerEntity winner = server.getPlayerManager().getPlayer(bestPlayer);
		if (winner == null) return false;
		boolean re = bestPlayer.equals(f.rulerPlayer);
		crown(server, f, winner, re ? "is re-elected with " + bestVotes + " votes" : "wins the election with " + bestVotes + " votes");
		return true;
	}

	/** A monarch fond enough of a player member names them heir. */
	static void considerHeir(MinecraftServer server, FactionManager fm, Faction f) {
		if (f.government != Faction.Government.MONARCHY || f.heirPlayer != null || f.rulerCitizen() == null) return;
		Faction.Citizen king = f.rulerCitizen();
		if (king.kindness < 40) return;
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			if (f.id.equals(fm.playerFaction(p.getUuid())) && fm.standing(p, f.id) >= 80) {
				f.heirPlayer = p.getUuid();
				FactionManager.broadcast(server, Text.literal("📜 " + f.rulerName + " of the ").formatted(Formatting.GOLD).append(f.displayName())
						.append(Text.literal(" names " + p.getName().getString() + " heir to the throne.").formatted(Formatting.GOLD)));
				return;
			}
		}
	}

	/** On a monarch's death: a player heir takes the crown, if they are still a member. */
	static boolean heirSucceeds(MinecraftServer server, FactionManager fm, Faction f) {
		if (f.heirPlayer == null) return false;
		ServerPlayerEntity p = server.getPlayerManager().getPlayer(f.heirPlayer);
		if (p == null || !f.id.equals(fm.playerFaction(p.getUuid()))) {
			if (p == null) return false; // wait for them to come back online
			f.heirPlayer = null;
			return false;
		}
		crown(server, f, p, "inherits the crown");
		return true;
	}

	/** Odds that a player member's coup succeeds (0-100), or -1 if they cannot attempt one. */
	public static int coupChance(FactionManager fm, Faction f, ServerPlayerEntity p) {
		if (!f.id.equals(fm.playerFaction(p.getUuid())) || p.getUuid().equals(f.rulerPlayer)) return -1;
		int rep = fm.standing(p, f.id);
		if (rep < 50) return -1;
		int rulerHold = f.rulerPlayer != null ? (f.armyOpinion + f.approval) / 2 : f.approval;
		return MathHelper.clamp((rep - 30) + (60 - rulerHold), 5, 90);
	}

	// ---------------------------------------------------------------- orders

	/** Carries out (or fails to carry out) an order from the atlas. Returns the message for the player. */
	public static Text order(ServerPlayerEntity player, Order order, int a, int b, int x, int z) {
		MinecraftServer server = player.getServer();
		ServerWorld world = server.getOverworld();
		FactionManager fm = FactionManager.get(server);
		Random random = world.getRandom();
		String mineId = fm.playerFaction(player.getUuid());
		Faction f = fm.get(mineId);
		if (f == null) return err("You are not a member of any faction.");

		// The roads to power are open to members who are not (yet) in charge.
		if (order == Order.COUP) {
			int chance = coupChance(fm, f, player);
			if (chance < 0) return err("You need the standing of a trusted member (50+) to stage a coup.");
			if (random.nextInt(100) < chance) {
				if (f.government == Faction.Government.REPUBLIC) f.government = Faction.Government.MONARCHY;
				if (f.government == Faction.Government.MONARCHY) f.dynasty = player.getName().getString();
				crown(server, f, player, "seizes power in a coup");
				net.colonists.player.PlayerData.of(server, player.getUuid()).add("coups", 1);
				f.courtOpinion = Math.min(f.courtOpinion, 40);
				return ok("The palace is yours.");
			}
			fm.changeRep(server, player, f.id, -40);
			Justice j = Justice.get(server);
			Justice.Record r = j.recordFor(f.id, player.getUuid());
			r.name = player.getName().getString();
			r.player = true;
			r.heat += Law.Crime.TREASON.heat;
			r.charges.add("a failed coup");
			r.lastSeen = world.getTime();
			j.markDirty();
			FactionManager.broadcast(server, Text.literal("⚔ " + player.getName().getString() + "'s coup against the ").formatted(Formatting.RED)
					.append(f.displayName()).append(Text.literal(" has failed! The guards are hunting the traitor.").formatted(Formatting.RED)));
			return err("Your coup has failed.");
		}
		if (order == Order.STAND) {
			if (f.government != Faction.Government.REPUBLIC) return err("Only a Republic holds elections.");
			f.playerCandidates.add(player.getUuid());
			fm.markDirty();
			return ok("You are standing in the next election. Your standing with the people and the senate's opinion decide it.");
		}
		if (!player.getUuid().equals(f.rulerPlayer)) return err("Only the ruler can give orders.");
		if (order == Order.ABDICATE) {
			f.rulerPlayer = null;
			f.ruler = null;
			FactionManager.broadcast(server, Text.literal(player.getName().getString() + " has stepped down as ruler of the ").formatted(Formatting.GRAY).append(f.displayName()));
			return ok("You have stepped down.");
		}

		Settlement own = fm.settlement(a);
		Settlement target = fm.settlement(a);
		Faction other = target == null ? null : target.faction;
		int support = support(fm, f, order, other);
		switch (order) {
			case TAX, GARRISON, AUTONOMY, FUND -> {
				if (own == null || own.faction != f) return err("That is not your town.");
				if (own.autonomy && order != Order.AUTONOMY) return err(own.name + " governs itself. Revoke its autonomy first.");
			}
			default -> {
			}
		}
		// The lords of the manors will not be taxed without their consent.
		if (order == Order.TAX && b > 0) {
			int lords = net.colonists.farm.Manors.lordsFavour(f);
			if (lords >= 0 && lords < 40) return err("The lords of the manors refuse new taxes (their favour: " + lords + "). Win them back first.");
		}
		if (!obeyed(support, random)) {
			String who = f.government == Faction.Government.REPUBLIC ? "The senate" : f.government == Faction.Government.MONARCHY ? "Your generals and nobles" : "The elders";
			return err(who + " refuse (" + support + "% support).");
		}

		switch (order) {
			case TAX -> {
				int cur = own.taxRate >= 0 ? own.taxRate : (int) Math.round(net.colonists.economy.Economy.defaultTax(f) * 100);
				own.taxRate = MathHelper.clamp(cur + b, 0, 40);
				if (b > 0) {
					f.courtOpinion -= 3;
					f.approval -= 2;
					net.colonists.farm.Manors.moveFavour(f, -5);
				} else {
					f.courtOpinion += 2;
					f.approval += 1;
					net.colonists.farm.Manors.moveFavour(f, 3);
				}
				return ok("Taxes in " + own.name + " are now " + own.taxRate + "%.");
			}
			case GARRISON -> {
				int cur = own.garrison >= 0 ? own.garrison : own.countJob("GUARD");
				own.garrison = MathHelper.clamp(cur + b, 0, Math.max(1, own.adults()));
				if (b > 0) f.armyOpinion += 1;
				return ok(own.name + " will keep " + own.garrison + " guards.");
			}
			case AUTONOMY -> {
				own.autonomy = !own.autonomy;
				return ok(own.autonomy ? own.name + " now governs itself: lower taxes, quicker loyalty, but it ignores your orders."
						: own.name + " is once again under your direct rule.");
			}
			case FUND -> {
				String project = PROJECTS[Math.floorMod(b, PROJECTS.length)];
				if (own.treasury < FUND_COST) return err(own.name + "'s treasury cannot pay " + FUND_COST + " emeralds.");
				own.treasury -= FUND_COST;
				if (project.equals("wall")) {
					if (!own.wallPlanned) City.planWall(own);
				} else {
					own.priority = project;
				}
				return ok("Builders in " + own.name + " will put the " + project.replace('_', ' ') + " first.");
			}
			case COLONIZE -> {
				if (own == null || own.faction != f) return err("Choose one of your towns to send the settlers.");
				f.orderedColony = new BlockPos(x, 64, z);
				f.orderedColonyFrom = own.id;
				return ok("Settlers from " + own.name + " will set out for " + x + ", " + z + " as soon as enough can be spared.");
			}
			case WAR -> {
				if (other == null || other == f) return err("Choose a foreign town as your objective.");
				fm.setRelation(server, f.id, other.id, -70);
				f.warGoal = target.id;
				f.atWar = true;
				boolean holy = !f.religion.isEmpty() && !f.religion.equals(other.religion)
						&& net.colonists.legend.Faith.has(net.colonists.legend.History.get(server), f, net.colonists.legend.Tenet.SWORD);
				if (holy) net.colonists.legend.Legends.holyWar(server, fm, f, other, world.getTimeOfDay() / 24000L);
				else net.colonists.legend.Legends.event(server, net.colonists.legend.History.EventType.WAR, f, other, target);
				f.armyOpinion += avgGuardAggression(f) > 55 ? 4 : -2;
				FactionManager.broadcast(server, Text.literal("⚔ " + f.rulerName + " of the ").formatted(Formatting.RED).append(f.displayName())
						.append(Text.literal(" declares war on the ").formatted(Formatting.RED)).append(other.displayName())
						.append(Text.literal(" and marches on ").formatted(Formatting.RED)).append(target.displayName()).append(Text.literal("!").formatted(Formatting.RED)));
				return ok("War! Your armies march on " + target.name + ".");
			}
			case PEACE_WHITE, PEACE_CEDE_THEIRS, PEACE_CEDE_OURS, PEACE_VASSAL -> {
				if (other == null || other == f && order != Order.PEACE_CEDE_OURS) return err("Choose a town of the enemy.");
				if (order == Order.PEACE_CEDE_OURS) {
					// Offering one of our own towns: pick their faction from whoever we're at war with that wants it most.
					Faction enemy = fm.all().stream().filter(o -> Empire.atWarWith(fm, f, o)).findFirst().orElse(null);
					if (enemy == null || own == null || own.faction != f) return err("Choose one of your towns to offer, while at war.");
					Empire.conquer(server, fm, own, enemy, true);
					fm.setRelation(server, f.id, enemy.id, -15);
					return ok("Peace is bought: " + own.name + " is ceded to the " + enemy.name + ".");
				}
				if (!Empire.atWarWith(fm, f, other)) return err("You are not at war with the " + other.name + ".");
				boolean accept = switch (order) {
					case PEACE_WHITE -> other.warWeariness >= 3 || other.members.size() < f.members.size();
					case PEACE_CEDE_THEIRS -> other.warWeariness >= 6 && other.settlements.size() >= 2 && other.members.size() <= f.members.size();
					default -> other.warWeariness >= 6 && other.members.size() * 2 <= f.members.size();
				};
				if (!accept) return err("The " + other.name + " reject your terms.");
				fm.setRelation(server, f.id, other.id, -15);
				f.warGoal = -1;
				net.colonists.legend.Legends.event(server, net.colonists.legend.History.EventType.PEACE, f, other, null);
				if (order == Order.PEACE_CEDE_THEIRS) {
					Empire.conquer(server, fm, target, f, true);
				} else if (order == Order.PEACE_VASSAL) {
					other.overlord = f.id;
					fm.setRelation(server, f.id, other.id, 30);
				}
				FactionManager.broadcast(server, Text.literal("🕊 The ").formatted(Formatting.GREEN).append(f.displayName())
						.append(Text.literal(" and the ").formatted(Formatting.GREEN)).append(other.displayName())
						.append(Text.literal(" make peace" + (order == Order.PEACE_VASSAL ? "; the " + other.name + " become tributaries." : ".")).formatted(Formatting.GREEN)));
				return ok("Peace is made.");
			}
			case ALLY -> {
				if (other == null || other == f) return err("Choose a foreign town.");
				int rel = fm.relation(f.id, other.id);
				if (rel < 25 || Empire.atWarWith(fm, f, other)) {
					fm.changeRelation(server, f.id, other.id, -3);
					return err("The " + other.name + " don't trust you enough for an alliance (relations " + rel + ").");
				}
				fm.setRelation(server, f.id, other.id, 65);
				return ok("You are now allied with the " + other.name + ".");
			}
			case DEMAND_VASSAL -> {
				if (other == null || other == f) return err("Choose a foreign town.");
				if (other.members.size() * 3 <= f.members.size() && fm.relation(f.id, other.id) > -40) {
					other.overlord = f.id;
					fm.setRelation(server, f.id, other.id, 25);
					FactionManager.broadcast(server, Text.literal("The ").formatted(Formatting.GOLD).append(other.displayName())
							.append(Text.literal(" bow to the ").formatted(Formatting.GOLD)).append(f.displayName()).append(Text.literal(" and pay tribute.").formatted(Formatting.GOLD)));
					return ok("The " + other.name + " accept your overlordship.");
				}
				fm.changeRelation(server, f.id, other.id, -15);
				return err("The " + other.name + " are insulted by your demand.");
			}
			case LAW -> {
				Law[] laws = Law.values();
				if (b < 0 || b >= laws.length) return err("No such law.");
				Law law = laws[b];
				boolean on = !f.laws.contains(law);
				String objection = net.colonists.legend.Faith.objection(net.colonists.legend.History.get(server), f, law, on);
				if (objection != null) return err("Your faith forbids it: " + objection);
				if (on) f.laws.add(law);
				else f.laws.remove(law);
				if (law == Law.DEATH_PENALTY) f.approval += on ? (50 - avgKindness(f)) / 5 : (avgKindness(f) - 50) / 5;
				return ok((on ? "Decreed: " : "Repealed: ") + law.label + ".");
			}
			case CAPTIVITY -> {
				String objection = net.colonists.legend.Faith.objection(net.colonists.legend.History.get(server), f, null, !f.slavery);
				if (objection != null) return err("Your faith forbids it: " + objection);
				f.slavery = !f.slavery;
				f.approval += f.slavery ? -(avgKindness(f) - 40) / 4 : (avgKindness(f) - 40) / 4;
				return ok(f.slavery ? "Captivity is now practised." : "Captivity is abolished; all slaves will go free.");
			}
			case GOVERNMENT -> {
				Faction.Government[] govs = {Faction.Government.CHIEFDOM, Faction.Government.MONARCHY, Faction.Government.REPUBLIC};
				Faction.Government g = govs[Math.floorMod(b, govs.length)];
				if (g == f.government) return err("That is already your government.");
				f.government = g;
				if (g == Faction.Government.MONARCHY) f.dynasty = player.getName().getString();
				if (g == Faction.Government.REPUBLIC) f.lastElection = world.getTime();
				f.courtOpinion -= 10;
				FactionManager.broadcast(server, Text.literal("📜 " + f.rulerName + " proclaims the ").formatted(Formatting.GOLD).append(f.displayName())
						.append(Text.literal(" a " + g.label + ".").formatted(Formatting.GOLD)));
				return ok("Your realm is now a " + g.label + ".");
			}
			case GIFT_LORDS -> {
				List<net.colonists.farm.Manor> lords = net.colonists.farm.Manors.ofRealm(f);
				if (lords.isEmpty()) return err("Your realm has no landed lords.");
				Settlement cap = f.capital();
				if (cap == null || cap.treasury < b || b <= 0) return err("The treasury cannot pay that.");
				cap.treasury -= b;
				for (net.colonists.farm.Manor m : lords) {
					Faction.Citizen c = f.citizens.get(m.lord);
					if (c != null) c.wealth += b / lords.size();
				}
				net.colonists.farm.Manors.moveFavour(f, Math.max(3, b * 12 / (10 * lords.size())));
				f.courtOpinion = Math.min(100, f.courtOpinion + 3);
				return ok("The lords of the manors accept your gift of " + b + " emeralds. Their favour is now " + net.colonists.farm.Manors.lordsFavour(f) + ".");
			}
			case BONUS -> {
				Settlement cap = f.capital();
				if (cap == null || cap.treasury < b || b <= 0) return err("The treasury cannot pay that.");
				cap.treasury -= b;
				int guards = Math.max(1, f.countJob("GUARD"));
				for (Faction.Citizen c : f.citizens.values()) if (c.job.equals("GUARD")) c.wealth += b / guards;
				f.armyOpinion = Math.min(100, f.armyOpinion + Math.max(1, b * 10 / guards));
				return ok("The army is paid a bonus of " + b + " emeralds. They cheer your name.");
			}
			default -> {
				return err("Unknown order.");
			}
		}
	}

	private static Text ok(String s) {
		return Text.literal("✔ " + s).formatted(Formatting.GREEN);
	}

	private static Text err(String s) {
		return Text.literal("✘ " + s).formatted(Formatting.RED);
	}

	/** A ruled faction's colonists are this player's soldiers too: used to pick a war goal they announce. */
	public static boolean rules(Faction f, UUID player) {
		return player.equals(f.rulerPlayer);
	}
}
