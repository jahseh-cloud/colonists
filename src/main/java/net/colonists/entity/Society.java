package net.colonists.entity;

import net.colonists.ColonistsMod;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.minecraft.entity.SpawnReason;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/** Slow life events: births, newcomers to small towns, and splinter groups breaking away. */
final class Society {
	private Society() {}

	/** Ticks between births for a couple (two Minecraft days). */
	static final int BIRTH_INTERVAL = 30000;

	static void tick(ColonistEntity c, ServerWorld world) {
		FactionManager fm = FactionManager.get(world.getServer());
		Faction faction = fm.get(c.getFactionId());
		if (faction == null || c.isBaby()) return;
		boolean grow = world.getGameRules().getBoolean(ColonistsMod.GROW);
		int cap = world.getGameRules().getInt(ColonistsMod.MAX_PER_FACTION);
		boolean room = cap <= 0 || fm.memberCount(faction.id) < cap;

		// A newly opened workplace pulls someone out of a crowded trade.
		net.colonists.faction.Settlement town = c.settlement();
		ColonistEntity.Job switchTo = net.colonists.faction.Politics.reconsiderJob(town, c.getUuid(), c.job);
		if (switchTo != null && c.getRandom().nextInt(2) == 0) {
			c.job = switchTo;
			c.syncCitizen();
			c.say("I'm going to work as a " + switchTo.label.toLowerCase() + " from now on.");
		}

		// Brawls: a hothead sometimes comes to blows with someone they can't stand. That's assault, if anyone sees.
		if (c.aggression > 75 && c.getRandom().nextInt(300) == 0) {
			ColonistEntity rival = world.getEntitiesByClass(ColonistEntity.class, c.getBoundingBox().expand(6),
					o -> o != c && !o.isBaby() && o.getFactionId().equals(c.getFactionId()) && c.affinityWith(o.getUuid()) < -5).stream().findFirst().orElse(null);
			if (rival != null) {
				c.swingHand(net.minecraft.util.Hand.MAIN_HAND);
				c.lookAtEntity(rival, 30f, 30f);
				rival.damage(world.getDamageSources().generic(), 2f);
				c.say("That's for what you said about me, " + rival.getFirstName() + "!");
				net.colonists.law.Courts.report(world, c.settlement(), c, net.colonists.law.Law.Crime.ASSAULT, true);
			}
		}

		// Widowed: free to remarry.
		if (c.partner != null && !faction.citizens.containsKey(c.partner)) {
			c.partner = null;
			c.partnerName = "";
		}

		// Births: a married couple with a house and food to spare.
		if (grow && c.partner != null && c.home != null && room
				&& world.getTime() - c.lastBirthTime > BIRTH_INTERVAL * net.colonists.legend.Faith.birthInterval(net.colonists.legend.History.get(world.getServer()), faction)
						* net.colonists.legend.Ages.births(net.colonists.legend.History.get(world.getServer()))
						* (net.colonists.legend.Wonders.holds(world.getServer(), faction, net.colonists.legend.Wonders.Type.GARDENS) ? 0.7 : 1.0)
				&& (ColonistBrain.count(c.getInventory(), ColonistBrain.FOOD) >= 4 || (town != null && town.foodInStock >= 8)) && c.getRandom().nextInt(2) == 0) {
			ColonistEntity spouse = world.getEntitiesByClass(ColonistEntity.class, c.getBoundingBox().expand(48),
					o -> o.getUuid().equals(c.partner)).stream().findFirst().orElse(null);
			if (spouse != null) {
				BlockPos door = net.colonists.ai.task.BuildStructureTask.doorstep(c, c.home);
				ColonistEntity baby = ColonistsMod.COLONIST.create(world);
				if (baby != null) {
					ColonistBrain.take(c.getInventory(), ColonistBrain.FOOD, Math.min(4, ColonistBrain.count(c.getInventory(), ColonistBrain.FOOD)));
					baby.refreshPositionAndAngles(door.getX() + 0.5, door.getY(), door.getZ() + 0.5, c.getRandom().nextFloat() * 360f, 0f);
					baby.pendingFaction = faction.id;
					baby.pendingSettlement = c.settlementId;
					baby.bornTo(c, spouse);
					baby.initialize(world, world.getLocalDifficulty(door), SpawnReason.BREEDING, null);
					world.spawnEntity(baby);
					c.lastBirthTime = spouse.lastBirthTime = world.getTime();
					FactionManager.broadcastNear(world, door, 128, Text.literal("👶 " + baby.fullName() + " was born to "
							+ c.fullName() + " and " + spouse.fullName() + " of the ").formatted(Formatting.LIGHT_PURPLE)
							.append(faction.displayName()));
				}
			}
			return;
		}

		// Newcomers only drift into tiny settlements; after that the town grows by births.
		if (grow && c.home != null && town != null && town.population() < 4 && c.getRandom().nextInt(6) == 0
				&& ColonistBrain.count(c.getInventory(), ColonistBrain.FOOD) >= 4) {
			List<ColonistEntity> nearby = world.getEntitiesByClass(ColonistEntity.class, c.getBoundingBox().expand(64), e -> true);
			if (nearby.size() < 24) {
				BlockPos door = net.colonists.ai.task.BuildStructureTask.doorstep(c, c.home);
				ColonistEntity newcomer = ColonistsMod.COLONIST.create(world);
				if (newcomer != null) {
					ColonistBrain.take(c.getInventory(), ColonistBrain.FOOD, 4);
					newcomer.refreshPositionAndAngles(door.getX() + 0.5, door.getY(), door.getZ() + 0.5, c.getRandom().nextFloat() * 360f, 0f);
					newcomer.pendingFaction = faction.id;
					newcomer.pendingSettlement = c.settlementId;
					newcomer.initialize(world, world.getLocalDifficulty(door), SpawnReason.EVENT, null);
					world.spawnEntity(newcomer);
					FactionManager.broadcastNear(world, door, 96, Text.literal(newcomer.fullName() + " has settled with the ")
							.formatted(Formatting.YELLOW).append(faction.displayName()).append(Text.literal("!").formatted(Formatting.YELLOW)));
					c.say("Welcome to town, " + newcomer.getFirstName() + "!");
				}
			}
			return;
		}

		// Schism: in big, unhappy factions an ambitious homeless member breaks away to found a rival city-state.
		// Only an established realm (six days or more) that has lost its people's trust splits.
		int odds = faction.approval < 30 ? 25 : 150;
		net.colonists.faction.Settlement capital = faction.capital();
		long age = capital == null ? 0 : world.getTimeOfDay() / 24000L - capital.foundedDay;
		if (faction.founderPlayer == null && faction.rulerPlayer == null && !faction.freeVillage && age >= 6 && faction.approval < 45
				&& c.home == null && c.buildSite == null && !c.isRuler() && c.ambition > 55
				&& fm.memberCount(faction.id) >= 8 && c.getRandom().nextInt(odds) == 0) {
			Faction rebels = fm.createFaction(c.getRandom(), null);
			String oldId = faction.id;
			c.setFaction(rebels.id);
			fm.setRelation(world.getServer(), oldId, rebels.id, -15);
			FactionManager.broadcast(world.getServer(), Text.literal(c.fullName() + " has broken away from the ")
					.formatted(Formatting.GOLD).append(faction.displayName())
					.append(Text.literal(" to found the ").formatted(Formatting.GOLD)).append(rebels.displayName())
					.append(Text.literal("!").formatted(Formatting.GOLD)));
			c.say("I'm done taking orders. The " + rebels.name + " will build a city of our own!");
			// Like-minded friends follow.
			for (ColonistEntity friend : world.getEntitiesByClass(ColonistEntity.class, c.getBoundingBox().expand(32),
					o -> o != c && o.getFactionId().equals(oldId) && !o.isRuler() && o.home == null && !o.isBaby()
							&& c.affinityWith(o.getUuid()) >= 20)) {
				friend.setFaction(rebels.id);
			}
		}
	}
}
