package net.colonists.faction;

import net.colonists.ColonistsMod;
import net.colonists.entity.CaptiveEntity;
import net.colonists.entity.ColonistEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Vanilla villages as small realms of free men: surveyed as players come near, given borders, a name and a place in
 * diplomacy and on the maps. Colonists drift in to live among them, and in time may stay loyal, leave to found
 * a people of their own, or seize the village.
 */
public final class Villages {
	private Villages() {}

	/** Colonists in a village the free men tolerate before getting uneasy. */
	static final int WELCOME = 2;

	/** Every 10 seconds: survey the villages near players. */
	public static void survey(MinecraftServer server) {
		ServerWorld world = server.getOverworld();
		FactionManager fm = FactionManager.get(server);
		Map<BlockPos, List<VillagerEntity>> byBell = new HashMap<>();
		List<net.minecraft.util.math.Box> areas = new ArrayList<>();
		for (ServerPlayerEntity p : world.getPlayers()) areas.add(p.getBoundingBox().expand(128, 48, 128));
		for (BlockPos extra : EXTRA_SURVEY) areas.add(new net.minecraft.util.math.Box(extra).expand(128, 48, 128));
		java.util.Set<UUID> seen = new java.util.HashSet<>();
		for (net.minecraft.util.math.Box area : areas) {
			for (VillagerEntity v : world.getEntitiesByClass(VillagerEntity.class, area, VillagerEntity::isAlive)) {
				if (!seen.add(v.getUuid())) continue;
				Optional<GlobalPos> meet = meetingPoint(v);
				if (meet.isEmpty() || !meet.get().dimension().equals(world.getRegistryKey())) continue;
				byBell.computeIfAbsent(meet.get().pos().toImmutable(), k -> new ArrayList<>()).add(v);
			}
		}
		for (Map.Entry<BlockPos, List<VillagerEntity>> e : byBell.entrySet()) {
			BlockPos bell = e.getKey();
			List<VillagerEntity> villagers = e.getValue();
			Settlement s = villageAt(fm, bell);
			if (s == null) {
				if (villagers.size() < 2 || tooCloseToTown(fm, bell)) continue;
				s = discover(server, world, fm, bell);
			}
			update(world, s, bell, villagers);
		}
	}

	/** Extra places to survey even with no player near (used by the self-test). */
	public static final List<BlockPos> EXTRA_SURVEY = new ArrayList<>();

	static Optional<GlobalPos> meetingPoint(VillagerEntity v) {
		try {
			return v.getBrain().getOptionalRegisteredMemory(MemoryModuleType.MEETING_POINT);
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	static Optional<GlobalPos> homeOf(VillagerEntity v) {
		try {
			return v.getBrain().getOptionalRegisteredMemory(MemoryModuleType.HOME);
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	@Nullable
	static Settlement villageAt(FactionManager fm, BlockPos bell) {
		for (Settlement s : fm.allSettlements()) {
			if (s.village && s.cityCenter != null && s.cityCenter.getSquaredDistance(bell) < 48 * 48) return s;
		}
		return null;
	}

	private static boolean tooCloseToTown(FactionManager fm, BlockPos bell) {
		Settlement near = fm.nearestSettlement(bell, null);
		return near != null && !near.village && near.cityCenter != null && near.cityCenter.getSquaredDistance(bell) < 40 * 40;
	}

	private static Settlement discover(MinecraftServer server, ServerWorld world, FactionManager fm, BlockPos bell) {
		Random random = world.getRandom();
		String name = Settlement.randomName(random);
		Faction f = fm.createFaction(random, "Free Men of " + name);
		f.freeVillage = true;
		f.government = Faction.Government.FREE;
		f.rulerName = "the Elders";
		f.laws.remove(net.colonists.law.Law.POACHING);
		Settlement s = fm.foundSettlement(server, f, bell, false, name);
		s.village = true;
		s.vMinX = bell.getX() - 24;
		s.vMaxX = bell.getX() + 24;
		s.vMinZ = bell.getZ() - 24;
		s.vMaxZ = bell.getZ() + 24;
		fm.markDirty();
		ServerPlayerEntity finder = world.getClosestPlayer(bell.getX(), bell.getY(), bell.getZ(), 160, false) instanceof ServerPlayerEntity sp ? sp : null;
		if (finder != null) net.colonists.legend.Legends.earn(server, finder.getUuid(), net.colonists.legend.History.ADVENTURE, 8);
		FactionManager.broadcastNear(world, bell, 160, Text.literal("🏡 You have come upon the free village of ").formatted(Formatting.GREEN)
				.append(s.displayName()).append(Text.literal(".").formatted(Formatting.GREEN)));
		// A few colonists already live among the villagers.
		if (world.getGameRules().getBoolean(ColonistsMod.GROW)) {
			int n = 2 + random.nextInt(2);
			for (int i = 0; i < n; i++) spawnGuest(world, f, s, bell);
		}
		return s;
	}

	private static void update(ServerWorld world, Settlement s, BlockPos bell, List<VillagerEntity> villagers) {
		s.freemen = villagers.size();
		int minX = bell.getX() - 16, maxX = bell.getX() + 16, minZ = bell.getZ() - 16, maxZ = bell.getZ() + 16;
		for (VillagerEntity v : villagers) {
			BlockPos p = v.getBlockPos();
			Optional<GlobalPos> home = homeOf(v);
			if (home.isPresent()) p = home.get().pos();
			if (Math.abs(p.getX() - bell.getX()) > 72 || Math.abs(p.getZ() - bell.getZ()) > 72) continue;
			minX = Math.min(minX, p.getX() - 6);
			maxX = Math.max(maxX, p.getX() + 6);
			minZ = Math.min(minZ, p.getZ() - 6);
			maxZ = Math.max(maxZ, p.getZ() + 6);
		}
		s.vMinX = minX;
		s.vMaxX = maxX;
		s.vMinZ = minZ;
		s.vMaxZ = maxZ;
		String owner = s.faction == null || s.faction.freeVillage ? s.name : s.name + " (" + s.faction.name + ")";
		for (VillagerEntity v : villagers) {
			if (v.hasCustomName() && !v.getCustomName().getString().startsWith("Free ") && !v.getCustomName().getString().startsWith("Subject ")) continue;
			boolean man = (v.getUuid().getLeastSignificantBits() & 1) == 0;
			String title = s.faction != null && s.faction.freeVillage ? (man ? "Free Man" : "Free Woman") : (man ? "Subject" : "Subject");
			String want = title + " of " + owner;
			if (!v.hasCustomName() || !v.getCustomName().getString().equals(want)) {
				v.setCustomName(Text.literal(want));
				v.setCustomNameVisible(false);
			}
		}
	}

	static void spawnGuest(ServerWorld world, Faction f, Settlement s, BlockPos near) {
		ColonistEntity c = ColonistsMod.COLONIST.create(world);
		if (c == null) return;
		Random r = world.getRandom();
		int x = near.getX() + r.nextInt(13) - 6, z = near.getZ() + r.nextInt(13) - 6;
		int y = City.surfaceYOr(world, x, z, near.getY());
		c.refreshPositionAndAngles(x + 0.5, y, z + 0.5, r.nextFloat() * 360f, 0f);
		c.pendingFaction = f.id;
		c.pendingSettlement = s.id;
		c.initialize(world, world.getLocalDifficulty(c.getBlockPos()), SpawnReason.EVENT, null);
		world.spawnEntity(c);
	}

	/** Once a minute: newcomers arrive, grievances build, and colonist guests decide what to do. */
	public static void tick(MinecraftServer server, FactionManager fm, Random random) {
		ServerWorld world = server.getOverworld();
		long day = world.getTimeOfDay() / 24000L;
		for (Settlement s : new ArrayList<>(fm.allSettlements())) {
			if (!s.village || s.cityCenter == null || s.faction == null) continue;
			BlockPos bell = s.cityCenter;
			boolean loaded = world.isChunkLoaded(bell.getX() >> 4, bell.getZ() >> 4);
			int guests = s.colonists();
			if (!s.faction.freeVillage) continue; // already part of a colonist realm

			// Newcomers drift in, a day at a time.
			if (loaded && s.lastNewcomerDay != day && world.getGameRules().getBoolean(ColonistsMod.GROW)) {
				s.lastNewcomerDay = day;
				if (guests < Math.max(WELCOME, s.freemen / 2) && random.nextInt(2) == 0) {
					spawnGuest(world, s.faction, s, bell);
					FactionManager.broadcastNear(world, bell, 96, Text.literal("A colonist has come to live in ").formatted(Formatting.GRAY)
							.append(s.displayName()).append(Text.literal(".").formatted(Formatting.GRAY)));
				}
			}

			// Grievances: being outnumbered by guests, raids, deaths (added elsewhere); slowly forgotten.
			if (guests > s.freemen && s.freemen > 0) s.grievance += 3;
			if (loaded && world.getRaidAt(bell) != null) s.grievance += 5;
			if (s.grievance > 0 && random.nextInt(2) == 0) s.grievance--;
			if (guests < 4 || !loaded) continue;

			List<ColonistEntity> here = world.getEntitiesByClass(ColonistEntity.class, new net.minecraft.util.math.Box(bell).expand(96, 48, 96),
					c -> c.isAlive() && !c.isBaby() && c.settlementId == s.id && c.getFactionId().equals(s.faction.id));
			if (here.size() < 4) continue;
			ColonistEntity leader = here.stream().max(Comparator.comparingInt(c -> c.ambition + c.aggression / 2)).orElse(null);
			if (leader == null) continue;

			// A: seize the village.
			if (leader.ambition > 65 && leader.aggression > 55 && random.nextInt(1200) < 3 + s.grievance) {
				takeOver(server, world, fm, s, leader, here);
				continue;
			}
			// B: leave to found a people of their own.
			if (leader.ambition > 55 && leader.kindness >= 35 && random.nextInt(1500) < 3 + s.grievance) {
				leave(server, fm, s, leader, here, random);
			}
			// C: otherwise they stay loyal to the free men, for now.
		}
	}

	/** A colonist guest of the village was killed: the others take it badly. */
	public static void onGuestDeath(FactionManager fm, ColonistEntity dead) {
		Settlement s = fm.settlement(dead.settlementId);
		if (s != null && s.village) s.grievance += 15;
	}

	private static void takeOver(MinecraftServer server, ServerWorld world, FactionManager fm, Settlement s, ColonistEntity leader, List<ColonistEntity> here) {
		Faction old = s.faction;
		Faction rebels = fm.createFaction(world.getRandom(), null);
		fm.transferSettlement(s, rebels);
		rebels.ruler = leader.getUuid();
		rebels.rulerName = leader.fullName();
		s.loyalty = 40;
		s.grievance = 0;
		for (ColonistEntity c : here) {
			c.setTarget(null);
			c.syncCitizen();
		}
		boolean cruel = leader.kindness < 35;
		int taken = 0;
		if (cruel) {
			rebels.slavery = true;
			List<VillagerEntity> villagers = world.getEntitiesByClass(VillagerEntity.class, new net.minecraft.util.math.Box(s.cityCenter).expand(64, 32, 64),
					v -> v.isAlive() && !v.isBaby());
			int max = Math.min(6, villagers.size() / 2);
			for (VillagerEntity v : villagers) {
				if (taken >= max) break;
				ColonistEntity captor = here.get(taken % here.size());
				CaptiveEntity.capture(world, v, captor, rebels);
				taken++;
			}
		}
		// The village's golems don't take kindly to it.
		for (IronGolemEntity g : world.getEntitiesByClass(IronGolemEntity.class, new net.minecraft.util.math.Box(s.cityCenter).expand(64, 32, 64), g -> g.isAlive())) {
			g.setTarget(leader);
		}
		leader.say(cruel ? "This village is ours now. Put them to work!" : "From today, we rule here. The villagers will be looked after — and pay their dues.");
		FactionManager.broadcast(server, Text.literal("⚔ " + leader.fullName() + " and " + (here.size() - 1) + " other colonists seize the free village of ")
				.formatted(Formatting.GOLD).append(s.displayName()).append(Text.literal(" and proclaim the ").formatted(Formatting.GOLD))
				.append(rebels.displayName()).append(Text.literal(cruel ? "! " + taken + " villagers are put in chains." : "! The villagers become their subjects.")
						.formatted(Formatting.GOLD)));
		net.colonists.legend.Legends.event(server, net.colonists.legend.History.EventType.VILLAGE_SEIZED, rebels, old, s).person = leader.fullName();
		fm.dissolveIfEmpty(server, old);
		fm.markDirty();
	}

	private static void leave(MinecraftServer server, FactionManager fm, Settlement s, ColonistEntity leader, List<ColonistEntity> here, Random random) {
		Faction pioneers = fm.createFaction(random, null);
		List<ColonistEntity> party = new ArrayList<>(here);
		party.remove(leader);
		party.sort(Comparator.comparingInt(c -> -leader.affinityWith(c.getUuid())));
		int n = Math.min(3, party.size());
		leader.setFaction(pioneers.id);
		leader.say("There's a whole world out there. Who's with me?");
		for (int i = 0; i < n; i++) party.get(i).setFaction(pioneers.id);
		net.colonists.legend.Legends.event(server, net.colonists.legend.History.EventType.EXODUS, pioneers, s.faction, s).person = leader.fullName();
		FactionManager.broadcast(server, Text.literal("🧭 " + leader.fullName() + " leaves the free village of ").formatted(Formatting.YELLOW)
				.append(s.displayName()).append(Text.literal(" with " + n + " companions to found the ").formatted(Formatting.YELLOW))
				.append(pioneers.displayName()).append(Text.literal(".").formatted(Formatting.YELLOW)));
		fm.markDirty();
	}

	/** Who a villager belongs to, for raids: villages of one's own realm are not raided. */
	@Nullable
	public static Settlement villageOf(FactionManager fm, VillagerEntity v) {
		Optional<GlobalPos> meet = meetingPoint(v);
		return villageAt(fm, meet.map(GlobalPos::pos).orElse(v.getBlockPos()));
	}
}
