package net.colonists;

import net.colonists.economy.Economy;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Settlement;
import net.colonists.faction.Statecraft;
import net.colonists.faction.Villages;
import net.colonists.law.Courts;
import net.colonists.law.Justice;
import net.colonists.law.Law;
import net.colonists.legend.Chronicles;
import net.colonists.legend.History;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * A headless self-test, run on CI with -Dcolonists.selftest=true: sets up two colonist peoples and a village,
 * fast-forwards three days, forces a war, crimes, arrests, prophets and chronicles, round-trips every save,
 * and reports. Any exception (here or in an entity tick) fails the run.
 */
public final class SelfTest {
	private SelfTest() {}

	private static final long LENGTH = 5 * 24000L;
	private static long start = -1;
	private static BlockPos a, b, v;
	private static final List<String> failures = new ArrayList<>();
	private static final List<String> passed = new ArrayList<>();

	public static void register() {
		if (!Boolean.getBoolean("colonists.selftest")) return;
		ColonistsMod.LOGGER.info("COLONISTS SELFTEST: enabled");
		ServerLifecycleEvents.SERVER_STARTED.register(server -> server.execute(() -> step("setup", () -> setup(server))));
		ServerTickEvents.END_SERVER_TICK.register(SelfTest::tick);
	}

	private static BlockPos surface(ServerWorld w, int x, int z) {
		w.getChunk(x >> 4, z >> 4);
		return new BlockPos(x, w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z), z);
	}

	private static void forceArea(ServerWorld w, BlockPos p, int r) {
		for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) w.setChunkForced((p.getX() >> 4) + dx, (p.getZ() >> 4) + dz, true);
	}

	private static void setup(MinecraftServer server) {
		ServerWorld w = server.getOverworld();
		BlockPos spawn = w.getSpawnPos();
		a = surface(w, spawn.getX(), spawn.getZ());
		b = surface(w, spawn.getX() + 220, spawn.getZ());
		v = surface(w, spawn.getX() - 220, spawn.getZ());
		forceArea(w, a, 4);
		forceArea(w, b, 4);
		forceArea(w, v, 3);
		spawnColonists(w, a, 10);
		spawnColonists(w, b, 10);
		// A small village: a bell, a lectern, villagers (one a librarian) who meet at the bell.
		w.setBlockState(v, Blocks.BELL.getDefaultState());
		w.setBlockState(v.add(3, 0, 0), Blocks.LECTERN.getDefaultState());
		for (int i = 0; i < 6; i++) {
			VillagerEntity villager = EntityType.VILLAGER.create(w);
			if (villager == null) continue;
			BlockPos at = surface(w, v.getX() + i - 3, v.getZ() + 2);
			villager.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
			if (i == 0) villager.setVillagerData(villager.getVillagerData().withProfession(VillagerProfession.LIBRARIAN).withLevel(2));
			w.spawnEntity(villager);
			villager.getBrain().remember(MemoryModuleType.MEETING_POINT, GlobalPos.create(w.getRegistryKey(), v));
		}
		Villages.EXTRA_SURVEY.add(v);
		start = w.getTime();
		server.getTickManager().startSprint((int) LENGTH + 2000);
		ColonistsMod.LOGGER.info("COLONISTS SELFTEST: set up at {} / {} / {}", a, b, v);
	}

	private static void spawnColonists(ServerWorld w, BlockPos at, int n) {
		for (int i = 0; i < n; i++) {
			ColonistEntity c = ColonistsMod.COLONIST.create(w);
			if (c == null) continue;
			BlockPos p = surface(w, at.getX() + (i % 4) * 2 - 3, at.getZ() + (i / 4) * 2 - 3);
			c.refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
			c.initialize(w, w.getLocalDifficulty(p), SpawnReason.COMMAND, null);
			w.spawnEntity(c);
		}
	}

	private interface Step {
		void run() throws Exception;
	}

	private static void step(String name, Step s) {
		try {
			s.run();
			passed.add(name);
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST: ok {}", name);
		} catch (Throwable t) {
			failures.add(name + ": " + t);
			ColonistsMod.LOGGER.error("COLONISTS SELFTEST FAILURE in " + name, t);
		}
	}

	private static List<ColonistEntity> colonistsNear(ServerWorld w, BlockPos p) {
		return w.getEntitiesByClass(ColonistEntity.class, new net.minecraft.util.math.Box(p).expand(96, 48, 96), ColonistEntity::isAlive);
	}

	private static void tick(MinecraftServer server) {
		if (start < 0) return;
		ServerWorld w = server.getOverworld();
		long t = w.getTime() - start;
		FactionManager fm = FactionManager.get(server);
		if (t == 24000) step("war", () -> {
			List<ColonistEntity> ca = colonistsNear(w, a), cb = colonistsNear(w, b);
			if (ca.isEmpty() || cb.isEmpty()) throw new IllegalStateException("colonists vanished: " + ca.size() + " / " + cb.size());
			String fa = ca.get(0).getFactionId(), fb = cb.get(0).getFactionId();
			if (fa.equals(fb)) throw new IllegalStateException("both groups joined one faction");
			fm.setRelation(server, fa, fb, -70);
		});
		if (t == 30000) step("crime-and-arrest", () -> {
			List<ColonistEntity> ca = colonistsNear(w, a), cb = colonistsNear(w, b);
			if (ca.isEmpty() || cb.isEmpty()) return;
			ColonistEntity thief = cb.get(0), guard = ca.get(0);
			Settlement s = fm.get(guard.getFactionId()).nearestTo(guard.getBlockPos());
			fm.setRelation(server, guard.getFactionId(), thief.getFactionId(), 0);
			Courts.report(w, s, thief, Law.Crime.THEFT, false);
			if (Justice.get(server).heat(guard.getFactionId(), thief.getUuid()) <= 0) throw new IllegalStateException("crime not recorded");
			Courts.arrest(w, guard, thief, 0);
			fm.setRelation(server, guard.getFactionId(), thief.getFactionId(), -70);
		});
		if (t == 36000) step("prophets", () -> {
			History h = History.get(server);
			for (History.Religion r : h.religions.values()) r.nextProphetDay = 0;
			if (h.religions.isEmpty()) throw new IllegalStateException("no religions were created");
		});
		if (t == 40000) step("save-roundtrip", () -> {
			var reg = server.getRegistryManager();
			NbtCompound n1 = fm.writeNbt(new NbtCompound(), reg);
			FactionManager fm2 = FactionManager.fromNbt(n1, reg);
			if (fm2.all().size() != fm.all().size()) throw new IllegalStateException("factions lost in save: " + fm.all().size() + " → " + fm2.all().size());
			Justice j = Justice.get(server);
			Justice.fromNbt(j.writeNbt(new NbtCompound(), reg), reg);
			History h = History.get(server);
			History h2 = History.fromNbt(h.writeNbt(new NbtCompound(), reg), reg);
			if (h2.religions.size() != h.religions.size() || h2.events.size() != h.events.size()) throw new IllegalStateException("history lost in save");
		});
		if (t == 44000) step("economy-atlas-chronicles", () -> {
			for (Settlement s : fm.allSettlements()) {
				Economy.offers(w, s);
				if (s.treasury < 0) throw new IllegalStateException(s.name + " has a negative treasury");
			}
			net.colonists.atlas.AtlasServer.tile(w, 0, 0, 16);
			net.colonists.atlas.AtlasServer.tile(w, -3, 2, 64);
			for (Faction f : fm.all()) Statecraft.support(fm, f, Statecraft.Order.WAR, null);
			Chronicles.tick(server, fm);
		});
		if (t == 50000) step("player-rule", () -> {
			net.fabricmc.fabric.api.entity.FakePlayer fp = net.fabricmc.fabric.api.entity.FakePlayer.get(w);
			fp.refreshPositionAndAngles(a.getX() + 0.5, a.getY(), a.getZ() + 0.5, 0, 0);
			Faction f = fm.all().stream().filter(x -> !x.freeVillage && x.capital() != null).findFirst().orElseThrow();
			Settlement cap = f.capital();
			fm.setPlayerFaction(fp.getUuid(), f.id);
			f.rulerPlayer = fp.getUuid();
			f.armyOpinion = f.courtOpinion = f.approval = 95;
			cap.treasury += 100;
			Settlement foreign = fm.allSettlements().stream().filter(x -> x.faction != f).findFirst().orElse(null);
			int fid = foreign == null ? -1 : foreign.id;
			List<String> results = new ArrayList<>();
			Object[][] orders = {
					{Statecraft.Order.TAX, cap.id, 2}, {Statecraft.Order.TAX, cap.id, -4}, {Statecraft.Order.GARRISON, cap.id, 1},
					{Statecraft.Order.AUTONOMY, cap.id, 0}, {Statecraft.Order.AUTONOMY, cap.id, 0}, {Statecraft.Order.FUND, cap.id, 1},
					{Statecraft.Order.FUND, cap.id, 0}, {Statecraft.Order.BONUS, 0, 5}, {Statecraft.Order.CAPTIVITY, 0, 0},
					{Statecraft.Order.ALLY, fid, 0}, {Statecraft.Order.DEMAND_VASSAL, fid, 0}, {Statecraft.Order.WAR, fid, 0},
					{Statecraft.Order.PEACE_WHITE, fid, 0}, {Statecraft.Order.PEACE_CEDE_THEIRS, fid, 0}, {Statecraft.Order.GOVERNMENT, 0, 2},
					{Statecraft.Order.GOVERNMENT, 0, 1}};
			for (Object[] o : orders) results.add(o[0] + "=" + Statecraft.order(fp, (Statecraft.Order) o[0], (Integer) o[1], (Integer) o[2], 0, 0).getString());
			for (Law l : Law.values()) results.add("LAW " + l + "=" + Statecraft.order(fp, Statecraft.Order.LAW, 0, l.ordinal(), 0, 0).getString());
			results.add("COLONIZE=" + Statecraft.order(fp, Statecraft.Order.COLONIZE, cap.id, 0, cap.cityCenter.getX() + 90, cap.cityCenter.getZ() + 40).getString());
			net.colonists.atlas.AtlasServer.build(fp);
			net.colonists.net.CityMapPayload.build(fm, fp);
			net.colonists.law.Slavery.bid(fp, 50);
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST orders: {}", String.join(" ; ", results));
			Statecraft.order(fp, Statecraft.Order.ABDICATE, 0, 0, 0, 0);
			fm.setPlayerFaction(fp.getUuid(), null);
		});
		if (t == 100000) step("chronicles-again", () -> Chronicles.tick(server, fm));
		if (t == LENGTH) {
			step("invariants", () -> {
				for (Faction f : fm.all()) {
					for (Faction.Citizen c : f.citizens.values()) {
						Settlement s = fm.settlement(c.settlement);
						if (c.settlement >= 0 && s != null && s.faction != f) throw new IllegalStateException(c.fullName() + " lives in a town of another faction");
					}
				}
			});
			History h = History.get(server);
			int villages = (int) fm.allSettlements().stream().filter(s -> s.village).count();
			String summary0 = "factions=" + fm.all().size() + " settlements=" + fm.allSettlements().size() + " villages=" + villages
					+ " religions=" + h.religions.size() + " legends=" + h.legends.size() + " events=" + h.events.size()
					+ " prisoners=" + Justice.get(server).prisoners.size() + " colonists=" + w.getEntitiesByType(ColonistsMod.COLONIST, c -> true).size();
			String summary = summary0;
			if (villages == 0) failures.add("village was not discovered");
			java.util.Map<String, Integer> deaths = new java.util.TreeMap<>();
			long today = w.getTimeOfDay() / 24000L;
			for (Faction f : fm.all()) f.recentDeaths(today, 30).forEach((k, n) -> deaths.merge(k, n, Integer::sum));
			int volumes = fm.allSettlements().stream().mapToInt(x -> x.volumes).sum();
			summary += " deaths=" + deaths + " chronicleVolumes=" + volumes;
			for (Settlement x : fm.allSettlements()) {
				summary += " | " + x.name + "(" + (x.faction == null ? "?" : x.faction.name) + ", pop " + x.population() + ", homes " + x.homes.size()
						+ ", treasury " + x.treasury + ", rank " + x.rank.label + ")";
			}
			if (failures.isEmpty()) {
				ColonistsMod.LOGGER.info("COLONISTS SELFTEST PASSED: {} ({})", summary, String.join(", ", passed));
			} else {
				ColonistsMod.LOGGER.error("COLONISTS SELFTEST FAILED: {} — {}", summary, String.join(" | ", failures));
			}
			for (History.Event e : h.events) ColonistsMod.LOGGER.info("COLONISTS HISTORY: day {} {} {} {} {} {}", e.day + 1, e.type, e.person, e.actorName, e.targetName, e.place);
			start = -1;
			server.stop(false);
		}
	}
}
