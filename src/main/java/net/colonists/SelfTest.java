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
import net.colonists.player.PlayerData;
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
	private static int growthTown = -1, growthBefore;
	private static String clanId = "";
	private static int clanCamp = -1;
	private static java.util.UUID pitColonist;
	private static int pitBottom;
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
			ColonistEntity guard = ca.stream().filter(x -> fm.get(x.getFactionId()) != null && !fm.get(x.getFactionId()).freeVillage).findFirst().orElse(null);
			if (guard == null) return; // the group was conquered or scattered by the time we looked
			ColonistEntity thief = cb.stream().filter(x -> !x.getFactionId().equals(guard.getFactionId())).findFirst().orElse(cb.get(0));
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
			PlayerData.fromNbt(PlayerData.get(server).writeNbt(new NbtCompound(), reg), reg);
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
			// The player's own life: contracts, property, faith, retinue, titles, deeds.
			fm.changeRep(server, fp, f.id, 60);
			List<PlayerData.Contract> board = net.colonists.player.Contracts.board(server, cap);
			results.clear();
			for (PlayerData.Contract k : board) results.add(net.colonists.player.Contracts.describe(server, k));
			String took = board.isEmpty() ? "no work" : String.valueOf(net.colonists.player.Contracts.accept(fp, cap, 1));
			net.colonists.player.Contracts.tick(server);
			for (java.util.Map.Entry<BlockPos, java.util.UUID> e : cap.homes.entrySet()) {
				if (cap.built.contains(e.getKey())) {
					cap.homes.put(e.getKey(), null);
					fp.refreshPositionAndAngles(e.getKey().getX() + 1.5, e.getKey().getY(), e.getKey().getZ() + 1.5, 0, 0);
					fp.getInventory().insertStack(new net.minecraft.item.ItemStack(net.minecraft.item.Items.EMERALD, 64));
					results.add("buy=" + net.colonists.player.Household.buy(fp));
					results.add("rent=" + net.colonists.player.Household.rent(fp));
					break;
				}
			}
			History h0 = History.get(server);
			if (!h0.religions.isEmpty()) results.add("faith=" + net.colonists.player.Household.joinFaith(fp, h0.religions.keySet().iterator().next()));
			results.add("pray=" + net.colonists.player.Household.pray(fp));
			ColonistEntity follower = colonistsNear(w, cap.cityCenter).stream().filter(c -> !c.isRuler() && !c.isBaby()).findFirst().orElse(null);
			if (follower != null) {
				follower.liege = fp.getUuid();
				follower.liegeUntil = w.getTimeOfDay() / 24000L + 5;
				PlayerData.of(server, fp.getUuid()).retinue.add(follower.getUuid());
				follower.retainerKill();
			}
			net.colonists.player.Titles.tick(server);
			net.colonists.player.Household.tick(server);
			net.colonists.player.Household.bookOfDeeds(fp);
			net.colonists.player.Contracts.onKill(server, fp, follower != null ? follower : fp);
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST player life: took={} board=[{}] {}", took, String.join(" | ", results.subList(0, Math.min(results.size(), 12))), String.join(" ; ", results));
			if (follower != null) {
				follower.liege = null;
				PlayerData.of(server, fp.getUuid()).retinue.clear();
			}
			Statecraft.order(fp, Statecraft.Order.ABDICATE, 0, 0, 0, 0);
			fm.setPlayerFaction(fp.getUuid(), null);
		});
		if (t == 60000) step("wonders", () -> {
			Settlement cap = fm.all().stream().filter(x -> !x.freeVillage && x.capital() != null).map(Faction::capital).findFirst().orElseThrow();
			BlockPos origin = cap.cityCenter.add(40, 0, 40);
			net.colonists.legend.Wonders.onBuilt(server, cap, origin, net.colonists.legend.Wonders.Type.ARENA.blueprint);
			if (!net.colonists.legend.Wonders.holds(server, cap.faction, net.colonists.legend.Wonders.Type.ARENA)) throw new IllegalStateException("wonder not held after building");
			for (net.colonists.legend.Wonders.Type type : net.colonists.legend.Wonders.Type.values()) {
				if (type.blueprint.entries.isEmpty()) throw new IllegalStateException(type + " has an empty blueprint");
			}
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST wonder sizes: {}", java.util.Arrays.stream(net.colonists.legend.Wonders.Type.values())
					.map(x -> x.name() + "=" + x.blueprint.width + "x" + x.blueprint.depth + "x" + x.blueprint.height + "/" + x.blueprint.entries.size()).toList());
		});
		if (t == 70000) step("crises-start", () -> {
			net.colonists.legend.History h = net.colonists.legend.History.get(server);
			for (net.colonists.legend.Crises.Type type : net.colonists.legend.Crises.Type.values()) {
				String why = net.colonists.legend.Crises.start(server, type, null);
				ColonistsMod.LOGGER.info("COLONISTS SELFTEST crisis {}: {}", type, why == null ? "began" : why);
				if (why != null && (type == net.colonists.legend.Crises.Type.KHAN || type == net.colonists.legend.Crises.Type.MESSENGER))
					throw new IllegalStateException(type + " could not begin: " + why);
			}
			if (h.crises.stream().noneMatch(c -> c.active() && c.type.equals("KHAN"))) throw new IllegalStateException("no khan");
		});
		if (t == 72000) step("crises-end", () -> {
			net.colonists.legend.History h = net.colonists.legend.History.get(server);
			net.fabricmc.fabric.api.entity.FakePlayer fp = net.fabricmc.fabric.api.entity.FakePlayer.get(w);
			for (net.colonists.legend.History.Crisis c : new ArrayList<>(h.crises)) {
				if (!c.active() || c.figure == null) continue;
				java.util.UUID fig = c.figure;
				Faction realm = fm.get(c.faction);
				if (realm == null) continue;
				ColonistsMod.LOGGER.info("COLONISTS SELFTEST crisis {} realm {} has {} members, {} towns", c.type, realm.name, realm.members.size(), realm.settlements.size());
				if (c.type.equals("KHAN") || c.type.equals("MESSENGER")) {
					if (w.getEntity(fig) instanceof ColonistEntity e) {
						if (c.type.equals("KHAN")) e.damage(w.getDamageSources().playerAttack(fp), Float.MAX_VALUE);
						else e.damage(w.getDamageSources().genericKill(), Float.MAX_VALUE);
					}
					if (fig.equals(c.figure)) net.colonists.legend.Crises.onFigureDeath(server, fig, c.type.equals("KHAN") ? fp : null);
				}
			}
			if (h.crises.stream().anyMatch(c -> c.type.equals("KHAN") && c.active())) throw new IllegalStateException("khan still alive");
			if (net.colonists.player.PlayerData.of(server, fp.getUuid()).stat("khanSlain") <= 0) throw new IllegalStateException("no Bane of the Khan");
			net.colonists.legend.History.Crisis m = h.crises.stream().filter(c -> c.type.equals("MESSENGER")).findFirst().orElseThrow();
			if (m.figure != null) throw new IllegalStateException("messenger did not die");
			Faction cal = fm.get(m.faction);
			if (cal != null) {
				java.util.UUID heir = cal.citizens.keySet().stream().filter(u -> !u.equals(m.lastRuler)).findFirst().orElse(null);
				if (heir != null) {
					cal.ruler = heir;
					net.colonists.legend.Crises.tick(server, fm, h, w.getRandom());
					if (m.caliphs < 1) throw new IllegalStateException("no caliph after the messenger");
				}
			}
			Faction big = fm.all().stream().filter(x -> !x.freeVillage && x.settlements.size() >= 2).findFirst().orElse(null);
			if (big != null) {
				int before = fm.all().size();
				net.colonists.legend.Crises.fracture(server, fm, h, big, "Kingdom", 0, w.getRandom());
				if (fm.all().size() <= before) throw new IllegalStateException("fracture made no new realms");
			}
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST crises: {}", h.crises.stream().map(c -> c.type + "/" + c.figureName + "/" + (c.active() ? "active" : "ended") + "/caliphs=" + c.caliphs).toList());
		});
		if (t == 76000) step("crises-new", () -> {
			net.colonists.legend.History h = net.colonists.legend.History.get(server);
			net.fabricmc.fabric.api.entity.FakePlayer fp = net.fabricmc.fabric.api.entity.FakePlayer.get(w);
			// For each crisis: close the earlier ones and forge one bigger realm so the realm-sized crises have room to happen.
			String backed = null;
			for (String type : new String[]{"REVOLT", "GOLDEN", "HERETIC", "PRETENDER"}) {
				for (net.colonists.legend.History.Crisis c : h.crises) if (c.active()) c.ended = 1;
				Faction big = fm.all().stream().filter(x -> !x.freeVillage && !x.settlements.isEmpty()).max(java.util.Comparator.comparingInt(x -> x.settlements.size())).orElseThrow();
				for (Settlement o : new ArrayList<>(fm.allSettlements())) {
					if (big.settlements.size() >= 3) break;
					if (o.faction != null && o.faction != big && !o.faction.freeVillage) fm.transferSettlement(o, big);
				}
				for (Settlement o : big.settlements.values()) if (!o.isCapital()) o.loyalty = 20;
				big.approval = type.equals("GOLDEN") ? 90 : 30;
				for (Faction x : fm.all()) x.atWar = false;
				String why = net.colonists.legend.Crises.start(server, net.colonists.legend.Crises.Type.valueOf(type), null);
				ColonistsMod.LOGGER.info("COLONISTS SELFTEST crisis {} in a realm of {} towns: {}", type, big.settlements.size(), why == null ? "began" : why);
				if (why != null && !type.equals("HERETIC")) throw new IllegalStateException(type + " could not begin: " + why);
				for (int k = 0; k < 3; k++) net.colonists.legend.Crises.tick(server, fm, h, w.getRandom());
				if (type.equals("REVOLT")) {
					backed = net.colonists.legend.Crises.back(fp, true);
					if (backed != null) throw new IllegalStateException("could not back the revolt: " + backed);
				}
			}
			backed = net.colonists.legend.Crises.back(fp, false);
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST back rebels: {}", backed == null ? "ok" : backed);
			for (int i = 0; i < 5; i++) net.colonists.legend.Crises.tick(server, fm, h, w.getRandom());
			for (net.colonists.legend.History.Crisis c : new ArrayList<>(h.crises)) {
				if (c.active() && c.figure != null && (c.type.equals("HERETIC") || c.type.equals("REVOLT")) && w.getEntity(c.figure) instanceof ColonistEntity e)
					e.damage(w.getDamageSources().playerAttack(fp), Float.MAX_VALUE);
			}
			net.colonists.legend.Chronicles.tick(server, fm);
			fm.setPlayerFaction(fp.getUuid(), null);
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST crises now: {}", h.crises.stream().map(c -> c.type + "/" + c.figureName + "/" + (c.active() ? "active" : "ended")).toList());
		});
		if (t == 86000) step("manor-land", () -> {
			// Load the land around the biggest town so a manor has somewhere to go.
			Settlement big = fm.allSettlements().stream().filter(x -> !x.village && x.cityCenter != null).max(java.util.Comparator.comparingInt(Settlement::population)).orElseThrow();
			int cx = big.cityCenter.getX() >> 4, cz = big.cityCenter.getZ() >> 4;
			for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) w.setChunkForced(cx + dx, cz + dz, true);
		});
		if (t == 90000) step("cities-and-manors", () -> {
			// Compact towns: how far homes stand from the plaza, against each town's reach.
			for (Settlement st : fm.allSettlements()) {
				if (st.village || st.cityCenter == null) continue;
				double sum = 0, far = 0;
				for (BlockPos home : st.homes.keySet()) {
					double d = Math.hypot(home.getX() - st.cityCenter.getX(), home.getZ() - st.cityCenter.getZ());
					sum += d;
					far = Math.max(far, d);
				}
				int n = st.homes.size();
				ColonistsMod.LOGGER.info("COLONISTS SELFTEST town {}: {} pop {}, {} houses, reach {}, mean house distance {}, farthest {}", st.name, st.rank.label,
						st.population(), n, net.colonists.faction.CityGrowth.reach(st), n == 0 ? 0 : Math.round(sum / n), Math.round(far));
			}
			// Houses for need.
			if (net.colonists.build.Blueprints.homeFor(Faction.Rank.CITY, false, 10, false, true, false) != net.colonists.build.Blueprints.TOWNHOUSE)
				throw new IllegalStateException("a crowded city should build townhouses");
			if (net.colonists.build.Blueprints.homeFor(Faction.Rank.TOWN, false, 10, false, true, true) != net.colonists.build.Blueprints.ROW_HOUSE)
				throw new IllegalStateException("a crowded town should build row houses");
			if (net.colonists.build.Blueprints.homeFor(Faction.Rank.TOWN, false, 10, false, false, true) != net.colonists.build.Blueprints.STONE_HOUSE)
				throw new IllegalStateException("a family with room should build a two-storey house");
			// A manor: claim its land (wherever a town has room around it), and work a field from bare ground to a sown crop.
			Settlement big = null;
			net.colonists.farm.Manor.Field fld = null;
			net.colonists.farm.Manor m = new net.colonists.farm.Manor();
			for (Settlement cand : fm.allSettlements().stream().filter(x -> !x.village && x.cityCenter != null && x.faction != null)
					.sorted(java.util.Comparator.comparingInt(Settlement::population).reversed()).toList()) {
				for (int i = 0; i < 6 && fld == null; i++) fld = net.colonists.farm.Manors.claimField(w, fm, cand, m, w.getRandom());
				if (fld != null) {
					big = cand;
					break;
				}
			}
			if (fld == null) {
				long natural = fm.allSettlements().stream().mapToLong(x -> x.manors.size()).sum();
				ColonistsMod.LOGGER.warn("COLONISTS SELFTEST no open land for a test field this run ({} manors founded naturally)", natural);
				if (natural == 0) throw new IllegalStateException("no manor could be founded anywhere");
				return;
			}
			m.id = 99;
			m.name = "Test Grange";
			m.lord = big.faction.citizens.isEmpty() ? java.util.UUID.randomUUID() : big.faction.citizens.keySet().iterator().next();
			m.lordName = "the test lord";
			double d = Math.hypot(fld.center().getX() - big.cityCenter.getX(), fld.center().getZ() - big.cityCenter.getZ());
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST manor field: {} {}x{} ({} blocks) at {}, {} blocks from the plaza (reach {})", fld.crop, fld.width(), fld.depth(),
					fld.area(), fld.center(), Math.round(d), net.colonists.faction.CityGrowth.reach(big));
			if (fld.width() < 18 || fld.depth() < 18) throw new IllegalStateException("manor fields should be large");
			if (d < net.colonists.faction.CityGrowth.reach(big)) throw new IllegalStateException("the field should lie beyond the town");
			m.fields.add(fld);
			big.manors.add(m);
			int worked = 0;
			for (int pass = 0; pass < 4; pass++) {
				for (int x = fld.x0; x <= fld.x1; x++) for (int z = fld.z0; z <= fld.z1; z++) if (net.colonists.ai.task.ManorFieldTask.workSpot(w, m, fld, x, z)) worked++;
			}
			int crops = 0, sown = 0, water = 0;
			for (int x = fld.x0; x <= fld.x1; x++) {
				for (int z = fld.z0; z <= fld.z1; z++) {
					BlockPos at = new BlockPos(x, net.colonists.farm.Manors.columnY(w, x, z), z);
					switch (net.colonists.farm.Manors.role(fld, x, z)) {
						case CROP -> {
							crops++;
							net.minecraft.block.BlockState b = w.getBlockState(at);
							if (b.getBlock() instanceof net.minecraft.block.CropBlock || b.getBlock() instanceof net.minecraft.block.StemBlock
									|| b.isOf(net.minecraft.block.Blocks.SUGAR_CANE) || b.isOf(net.minecraft.block.Blocks.SWEET_BERRY_BUSH)) sown++;
						}
						case CHANNEL -> {
							if (w.getFluidState(at.down()).isIn(net.minecraft.registry.tag.FluidTags.WATER)) water++;
						}
						default -> {
						}
					}
				}
			}
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST manor field worked: {} jobs, {}/{} crop spots sown, {} channel spots watered", worked, sown, crops, water);
			if (sown * 2 < crops) throw new IllegalStateException("less than half the field was sown (" + sown + "/" + crops + ")");
			// The family's arms over the fields; the great house and its outbuildings marked out.
			m.banner = net.colonists.faction.Banners.random(w.getRandom());
			net.colonists.farm.Manors.flyArms(w, big, m);
			int arms = 0;
			for (BlockPos p : BlockPos.iterate(new BlockPos(fld.x0 - 2, 0, fld.z0 - 2), new BlockPos(fld.x1 + 2, 0, fld.z1 + 2))) {
				if (p.getX() > fld.x0 && p.getX() < fld.x1 && p.getZ() > fld.z0 && p.getZ() < fld.z1) continue;
				int y = net.colonists.faction.City.surfaceY(w, p.getX(), p.getZ());
				for (int dy = -2; dy <= 0; dy++) if (w.getBlockEntity(p.withY(y + dy)) instanceof net.minecraft.block.entity.BannerBlockEntity) arms++;
			}
			boolean seat = net.colonists.farm.Manors.placeSeat(w, fm, big, m, w.getRandom());
			boolean barn = seat && net.colonists.farm.Manors.placeOutbuilding(w, fm, big, m, w.getRandom());
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST manor arms flying: {}; manor house lot: {} ({}x{}); outbuildings: {}", arms,
					m.seat, net.colonists.build.Blueprints.MANOR_HOUSE.width, net.colonists.build.Blueprints.MANOR_HOUSE.depth,
					m.outbuildings.size());
			if (arms == 0) throw new IllegalStateException("the lord's banner should fly over his field");
			// The estate grows: a second field beside the first.
			net.colonists.farm.Manor.Field next = null;
			for (int i = 0; i < 6 && next == null; i++) next = net.colonists.farm.Manors.claimField(w, fm, big, m, w.getRandom());
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST manor second field: {}", next == null ? "none found" : next.crop + " " + next.width() + "x" + next.depth() + " at " + next.center());
			// The year turns: the estate ticks without trouble, and its land is saved.
			net.colonists.farm.Manors.tick(server);
			net.minecraft.nbt.NbtCompound saved = big.toNbt();
			Settlement back = Settlement.fromNbt(saved, big.id, big.name);
			if (back.manors.isEmpty() || back.manors.get(0).fields.isEmpty()) throw new IllegalStateException("manors were not saved");
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST manors now: {}", fm.allSettlements().stream().filter(x -> !x.manors.isEmpty())
					.map(x -> x.name + ":" + x.manors.stream().map(mm -> mm.name + "(" + mm.fields.size() + " fields, " + mm.laborers.size() + " laborers)").toList()).toList());
		});
		if (t == 92000) step("growth-and-lords", () -> {
			Settlement big = fm.allSettlements().stream().filter(x -> !x.village && x.cityCenter != null && x.colonists() >= 2)
					.max(java.util.Comparator.comparingInt(Settlement::population)).orElseThrow();
			growthTown = big.id;
			growthBefore = big.population() - big.pendingNewcomers;
			long day = w.getTimeOfDay() / 24000L;
			double rate = net.colonists.faction.Growth.rate(server, big);
			// Twenty days pass unseen; then the town loads and its newcomers walk in.
			big.foodInStock = Math.max(big.foodInStock, 100);
			int citizensBefore = big.faction.citizens.size();
			int added = net.colonists.faction.Growth.grow(server, big, 20, 0);
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST growth: twenty days add {}", added);
			net.colonists.faction.Growth.tick(server);
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST growth: {} at {}%/day, pop {} -> {} counted ({} waiting at the gate); day {} counted to {}, carry {}, realm citizens {} -> {}, loaded {}",
					big.name, Math.round(rate * 1000) / 10.0, growthBefore, big.population(), big.pendingNewcomers, day, big.lastGrowthDay,
					Math.round(big.growthCarry * 100) / 100.0, citizensBefore, big.faction.citizens.size(),
					w.isChunkLoaded(big.cityCenter.getX() >> 4, big.cityCenter.getZ() >> 4));
			// The lords.
			for (net.colonists.farm.Manor m : net.colonists.farm.Manors.ofRealm(big.faction)) m.favour = 8;
			int favour = net.colonists.farm.Manors.lordsFavour(big.faction);
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST lords of the {}: {} lords, favour {}, levies {}", big.faction.name,
					net.colonists.farm.Manors.ofRealm(big.faction).size(), favour, net.colonists.farm.Manors.levies(big.faction));
			if (!net.colonists.farm.Manors.ofRealm(big.faction).isEmpty() && favour >= 20) throw new IllegalStateException("lords' favour should be low");
			// The atlas shows every manor with its allegiance.
			net.colonists.atlas.AtlasPayloads.Data data = net.colonists.atlas.AtlasServer.build(net.fabricmc.fabric.api.entity.FakePlayer.get(w));
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST atlas manors: {}", data.manors().stream().map(m -> m.name() + " (" + m.allegiance() + ")").toList());
			if (data.manors().isEmpty()) throw new IllegalStateException("no manors on the atlas");
			if (data.manors().stream().noneMatch(m -> m.name().endsWith(" Manor") && m.allegiance().contains(" of "))) throw new IllegalStateException("manor label format");
			if (data.manors().stream().anyMatch(m -> m.banner().isEmpty())) throw new IllegalStateException("every manor should have its family's arms");
			// Angry barons may rise.
			net.colonists.farm.Manor angry = net.colonists.farm.Manors.ofRealm(big.faction).stream().findFirst().orElse(null);
			if (angry != null) ColonistsMod.LOGGER.info("COLONISTS SELFTEST barons' revolt: {}", net.colonists.legend.Crises.baronsRevolt(server, big.faction, angry.lord, angry.title()));
		});
		if (t == 92600) step("growth-arrived", () -> {
			Settlement big = fm.settlement(growthTown);
			if (big == null) return;
			int now = big.population() - big.pendingNewcomers;
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST growth arrived: {} people now {} (was {}), {} still waiting", big.name, now, growthBefore, big.pendingNewcomers);
			if (now <= growthBefore && big.pendingNewcomers == 0) throw new IllegalStateException("the town did not grow");
		});
		if (t == 94000) step("clans", () -> {
			// A clan gathers near the biggest town (in loaded country).
			Settlement town = fm.allSettlements().stream().filter(x -> !x.village && !x.camp && x.cityCenter != null && x.faction != null && !x.faction.clan)
					.max(java.util.Comparator.comparingInt(Settlement::population)).orElseThrow();
			BlockPos near = town.cityCenter.add(70, 0, 0);
			for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) w.setChunkForced((near.getX() >> 4) + dx, (near.getZ() >> 4) + dz, true);
			w.getChunk(near.getX() >> 4, near.getZ() >> 4);
			near = near.withY(net.colonists.faction.City.surfaceY(w, near.getX(), near.getZ()));
			Faction clan = net.colonists.clan.Clans.found(w, fm, near, 14, null);
			Settlement camp = clan.capital();
			clanId = clan.id;
			clanCamp = camp.id;
			if (!camp.camp || !clan.clan || fm.relation(clan.id, town.faction.id) > -90) throw new IllegalStateException("a clan should be founded at war with everyone");
			// Plunder enough to build, and see the camp grow and fill with warriors.
			camp.stock = 1000;
			for (int i = 0; i < 4; i++) net.colonists.clan.Clans.tickCamp(w, fm, camp);
			int warriors = w.getEntitiesByClass(net.minecraft.entity.mob.MobEntity.class, new net.minecraft.util.math.Box(camp.cityCenter).expand(64),
					e -> e.getCommandTags().contains("camp:" + camp.id)).size();
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST clan: the {} at {} ({}), camp level {}, {} warriors counted, {} in the flesh, chief {}", clan.name, camp.name,
					camp.cityCenter, camp.campLevel, camp.warriors, warriors, clan.rulerName);
			if (camp.campLevel < 1) throw new IllegalStateException("the camp should have raised its first buildings");
			// A war band against the town.
			String why = net.colonists.clan.Clans.launch(w, fm, camp, town, false);
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST war band: {} (band {}, target {})", why == null ? "out" : why, camp.band.size(), town.name);
			if (why != null && !why.contains("already")) throw new IllegalStateException("the war band did not go out: " + why);
			// A player rides with them.
			net.fabricmc.fabric.api.entity.FakePlayer fp = net.fabricmc.fabric.api.entity.FakePlayer.get(w);
			fp.refreshPositionAndAngles(camp.cityCenter.getX(), camp.cityCenter.getY(), camp.cityCenter.getZ(), 0, 0);
			String joined = net.colonists.clan.Clans.join(fp);
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST clan join: {}", joined == null ? "rides with the clan" : joined);
			fm.setPlayerFaction(fp.getUuid(), null);
		});
		if (t == 97000) step("clans-war", () -> {
			Faction clan = fm.get(clanId);
			Settlement camp = fm.settlement(clanCamp);
			if (clan == null || camp == null) throw new IllegalStateException("the clan vanished");
			int flesh = w.getEntitiesByClass(net.minecraft.entity.mob.MobEntity.class, new net.minecraft.util.math.Box(camp.cityCenter).expand(64),
					e -> e.getCommandTags().contains("camp:" + camp.id)).size();
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST clan warriors in the flesh: {}", flesh);
			if (flesh < 2) throw new IllegalStateException("the camp should have warriors in the flesh");
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST clan after the raid: {} warriors, band {}, fury {}, stock {}, captives {}, holds {}", camp.warriors, camp.band.size(),
					camp.fury, camp.stock, camp.captives, clan.settlements.values().stream().map(x -> x.name + (x.camp ? "(camp)" : "")).toList());
			// The fates of a fallen town, one of each.
			for (int i = 0; i < 3; i++) {
				Settlement victim = fm.allSettlements().stream().filter(x -> !x.camp && x.faction != null && !x.faction.clan && x.faction.settlements.size() >= 2 && !x.isCapital()).findFirst().orElse(null);
				if (victim == null) break;
				Faction loser = victim.faction;
				camp.fury = new int[]{0, 100, 60}[i];
				fm.transferSettlement(victim, clan);
				net.colonists.clan.Clans.onConquest(server, victim, clan, loser, camp);
				ColonistsMod.LOGGER.info("COLONISTS SELFTEST town fate: {} now belongs to {}", victim.name, victim.faction == null ? "nobody" : victim.faction.name);
			}
			// A second clan, and then the Great Raid.
			BlockPos far = camp.cityCenter.add(0, 0, 90);
			w.getChunk(far.getX() >> 4, far.getZ() >> 4);
			net.colonists.clan.Clans.found(w, fm, far.withY(net.colonists.faction.City.surfaceY(w, far.getX(), far.getZ())), 10, null);
			String raid = net.colonists.legend.Crises.start(server, net.colonists.legend.Crises.Type.GREAT_RAID, null);
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST great raid: {}; clans now {}", raid == null ? "began" : raid,
					net.colonists.clan.Clans.clans(fm).stream().map(f -> f.name + "(" + f.settlements.size() + ")").toList());
			if (raid != null) throw new IllegalStateException("the Great Raid should begin with two clans: " + raid);
			if (net.colonists.clan.Clans.clans(fm).size() != 1) throw new IllegalStateException("the clans should be one host");
			// The king falls: the host breaks apart.
			Faction host = net.colonists.clan.Clans.clans(fm).get(0);
			net.colonists.legend.Crises.onFigureDeath(server, host.ruler, null);
			net.colonists.clan.Clans.breakUp(server, fm, host);
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST after the king: {}", net.colonists.clan.Clans.clans(fm).stream().map(f -> f.name).toList());
			// A realm storms a camp: it is burned.
			Faction realm = fm.all().stream().filter(f -> !f.clan && !f.freeVillage && !f.settlements.isEmpty()).findFirst().orElseThrow();
			Settlement burn = net.colonists.clan.Clans.camps(fm).get(0);
			int before = net.colonists.clan.Clans.camps(fm).size();
			net.colonists.faction.Empire.conquer(server, fm, burn, realm, false);
			if (net.colonists.clan.Clans.camps(fm).size() != before - 1) throw new IllegalStateException("a stormed camp should burn");
		});
		if (t == 98000) step("pit", () -> {
			// Drop a colonist into a pit four deep, with walls all round, and see that it gets out.
			ColonistEntity c = w.getEntitiesByClass(ColonistEntity.class, new net.minecraft.util.math.Box(w.getSpawnPos()).expand(400, 200, 400),
					o -> o.isAlive() && !o.isBaby() && !o.isSleeping() && !o.hasVehicle()).stream().findFirst().orElse(null);
			if (c == null) return;
			BlockPos top = c.getBlockPos().withY(net.colonists.faction.City.surfaceY(w, c.getBlockX(), c.getBlockZ()));
			BlockPos bottom = top.down(4);
			for (int dy = 0; dy <= 5; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
				BlockPos p = bottom.add(dx, dy, dz);
				boolean shaft = dx == 0 && dz == 0 && dy >= 0;
				w.setBlockState(p, shaft ? net.minecraft.block.Blocks.AIR.getDefaultState() : net.minecraft.block.Blocks.STONE.getDefaultState());
			}
			w.setBlockState(bottom.down(), net.minecraft.block.Blocks.STONE.getDefaultState());
			c.refreshPositionAndAngles(bottom.getX() + 0.5, bottom.getY(), bottom.getZ() + 0.5, 0, 0);
			pitColonist = c.getUuid();
			pitBottom = bottom.getY();
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST pit: {} dropped into a pit at {}", c.fullName(), bottom);
		});
		if (t == 98600) step("pit-escape", () -> {
			if (pitColonist == null) return;
			if (!(w.getEntity(pitColonist) instanceof ColonistEntity c)) return;
			ColonistsMod.LOGGER.info("COLONISTS SELFTEST pit escape: {} now at y {} (pit floor {})", c.fullName(), c.getBlockY(), pitBottom);
			if (c.getBlockY() <= pitBottom + 1) throw new IllegalStateException("the colonist is still stuck in the pit");
		});
		if (t == 100000) step("chronicles-again", () -> Chronicles.tick(server, fm));
		if (t == LENGTH) {
			step("world-edge", () -> {
				int r = net.colonists.world.WorldEdge.radius;
				if (r <= 0) return;
				int beyond = 0, inside = 0;
				for (int dz = 0; dz < 16; dz++) {
					BlockPos far = surface(w, r + 40, dz * 4), near = surface(w, r - 200, dz * 4);
					if (far.getY() > w.getBottomY()) beyond++;
					if (near.getY() > w.getBottomY()) inside++;
				}
				ColonistsMod.LOGGER.info("COLONISTS SELFTEST edge: {} of 16 columns past the edge have ground, {} of 16 inside", beyond, inside);
				if (beyond > 0) throw new IllegalStateException("terrain past the world edge");
				if (inside == 0) throw new IllegalStateException("no terrain inside the world edge");
			});
			step("banners", () -> {
				int flying = 0;
				for (Settlement x : fm.allSettlements()) {
					if (x.cityCenter == null) continue;
					for (BlockPos p : BlockPos.iterate(x.cityCenter.add(-6, -3, -6), x.cityCenter.add(6, 3, 6))) {
						if (w.getBlockState(p).getBlock() instanceof net.minecraft.block.AbstractBannerBlock) {
							flying++;
							break;
						}
					}
				}
				ColonistsMod.LOGGER.info("COLONISTS SELFTEST banners: {} of {} towns fly a banner", flying, fm.allSettlements().size());
			});
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
			String summary = summary0 + " age=\"" + net.colonists.legend.Ages.name(h) + "\" wonders=" + h.wonders.keySet();
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
