package net.colonists.clan;

import net.colonists.ColonistsMod;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.City;
import net.colonists.faction.CityGrowth;
import net.colonists.faction.Empire;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Settlement;
import net.colonists.legend.History;
import net.colonists.world.WorldEdge;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PatrolEntity;
import net.minecraft.entity.mob.PillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Illager clans: barbarian states of war camps, peopled by pillagers, vindicators and evokers under a war chief.
 * <ul>
 * <li>They rise at pillager outposts, and out of the wilds.</li>
 * <li>They farm nothing. They hunt, log, mine and, above all, plunder, and spend it all on war: more warriors, iron,
 * evokers, ravagers, and (with Siegeworks) rams and catapults.</li>
 * <li>Their camps grow longhouses, watchtowers, a forge, an armory, a war-beast pen, a palisade and a great hall.</li>
 * <li>They send war bands against the towns around them. A town that falls is sacked, held as a stronghold, or razed,
 * as the clan's fury decides.</li>
 * <li>Kill a war chief and his clan breaks apart. A clan that holds enough towns settles into a kingdom.</li>
 * </ul>
 */
public final class Clans {
	private Clans() {}

	public static final String TAG = "colonists_clan";
	static final String[] CLAN_NAMES = {"Black Axe", "Grey Wolf", "Red Hand", "Broken Shield", "Iron Tusk", "Raven Banner", "Bloody Spear",
			"Ash Crown", "Howling Moon", "Bone Totem", "Burning Eye", "Iron Fang"};
	static final String[] CHIEFS = {"Gorvald", "Brannoc", "Hrodulf", "Grimwald", "Vulgard", "Ragnor", "Theodric", "Ansgar", "Ulfgar", "Sigrad", "Odomar", "Wulfgar", "Hathulf", "Radagast"};
	static final String[] CAMP_A = {"Black", "Grim", "Blood", "Iron", "Raven", "Wolf", "Skull", "Ash", "Storm", "Thorn", "Gall", "Bane"};
	static final String[] CAMP_B = {"fang", "axe", "hold", "den", "rock", "mark", "burg", "tooth", "crag", "pike"};

	public static boolean isMember(Entity e, @Nullable Faction clan) {
		return e.getCommandTags().contains(TAG) && (clan == null || e.getCommandTags().contains("clan:" + clan.id));
	}

	@Nullable
	static Settlement campOf(FactionManager fm, Entity e) {
		for (String t : e.getCommandTags()) {
			if (t.startsWith("camp:")) {
				try {
					return fm.settlement(Integer.parseInt(t.substring(5)));
				} catch (NumberFormatException ignored) {
				}
			}
		}
		return null;
	}

	static long day(ServerWorld w) {
		return w.getTimeOfDay() / 24000L;
	}

	// ---------------------------------------------------------------- registration

	public static void register() {
		ServerLivingEntityEvents.AFTER_DEATH.register(Clans::onDeath);
		// A clan's own people spare the players who ride with it.
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((victim, source, amount) -> {
			if (!(victim instanceof ServerPlayerEntity p) || !(source.getAttacker() instanceof LivingEntity a) || !a.getCommandTags().contains(TAG)) return true;
			FactionManager fm = FactionManager.get(p.getServer());
			String mine = fm.playerFaction(p.getUuid());
			return mine == null || !a.getCommandTags().contains("clan:" + mine);
		});
	}

	// ---------------------------------------------------------------- the clans' year

	/** Every 20 ticks: war bands. Every 100: camps that are loaded. Once a minute: the days of every camp. */
	public static void tick(MinecraftServer server) {
		ServerWorld world = server.getOverworld();
		FactionManager fm = FactionManager.get(server);
		int t = server.getTicks();
		if (t % 20 == 3) for (Settlement s : camps(fm)) if (!s.band.isEmpty()) tickBand(world, fm, s);
		if (t % 100 == 7) for (Settlement s : camps(fm)) tickCamp(world, fm, s);
		if (t % 200 == 13) survey(world, fm);
		if (t % 1200 == 1000) {
			long day = day(world);
			for (Settlement s : camps(fm)) days(world, fm, s, day);
			wilds(world, fm, day);
			for (Faction f : new ArrayList<>(fm.all())) if (f.clan) settle(server, fm, f);
			// Clans make no peace: they are at war with every settled realm (which may march on their camps in turn).
			for (Faction c : clans(fm)) for (Faction o : fm.all()) if (!o.clan && fm.relation(c.id, o.id) > -90) fm.setRelation(server, c.id, o.id, -100);
			fm.markDirty();
		}
	}

	public static List<Settlement> camps(FactionManager fm) {
		List<Settlement> list = new ArrayList<>();
		for (Settlement s : fm.allSettlements()) if (s.camp && s.faction != null && s.faction.clan && s.cityCenter != null) list.add(s);
		return list;
	}

	public static List<Faction> clans(FactionManager fm) {
		List<Faction> list = new ArrayList<>();
		for (Faction f : fm.all()) if (f.clan) list.add(f);
		return list;
	}

	// ---------------------------------------------------------------- founding

	/** Outposts: wherever a few pillagers keep a tower and no clan holds the land, a clan takes root. */
	static void survey(ServerWorld world, FactionManager fm) {
		for (ServerPlayerEntity p : world.getPlayers()) {
			List<PillagerEntity> loose = world.getEntitiesByClass(PillagerEntity.class, p.getBoundingBox().expand(96, 48, 96),
					e -> e.isAlive() && !e.getCommandTags().contains(TAG) && !e.hasActiveRaid() && !e.isPatrolLeader());
			for (PillagerEntity first : loose) {
				List<PillagerEntity> band = loose.stream().filter(o -> o.squaredDistanceTo(first) < 24 * 24).toList();
				if (band.size() < 2) continue;
				BlockPos at = first.getBlockPos();
				if (!clearOfOthers(fm, at, 180, 100)) continue;
				BlockPos center = at.withY(City.surfaceY(world, at.getX(), at.getZ()));
				Faction clan = found(world, fm, center, 4 + band.size(), null);
				Settlement camp = clan.capital();
				for (PillagerEntity e : band) enlist(e, clan, camp);
				return;
			}
		}
	}

	/** Out of the wilds: a few clans come to be on their own, far from the towns. */
	static void wilds(ServerWorld world, FactionManager fm, long day) {
		int towns = (int) fm.allSettlements().stream().filter(s -> !s.camp).count();
		int want = MathHelper.clamp(towns / 5, 1, 6);
		if (day < 2 || clans(fm).size() >= want || world.getRandom().nextInt(3) != 0) return;
		BlockPos site = openLand(world, fm, world.getRandom());
		if (site != null) found(world, fm, site, 8, null);
	}

	static boolean clearOfOthers(FactionManager fm, BlockPos at, int fromCamps, int fromTowns) {
		for (Settlement s : fm.allSettlements()) {
			if (s.cityCenter == null) continue;
			double d = Math.sqrt(s.cityCenter.getSquaredDistance(at.getX(), s.cityCenter.getY(), at.getZ()));
			if (d < (s.camp ? fromCamps : fromTowns)) return false;
		}
		return true;
	}

	@Nullable
	public static BlockPos openLand(ServerWorld world, FactionManager fm, Random random) {
		List<Settlement> towns = fm.allSettlements().stream().filter(x -> x.cityCenter != null).toList();
		for (int i = 0; i < 24; i++) {
			BlockPos near;
			if (!towns.isEmpty()) {
				BlockPos from = towns.get(random.nextInt(towns.size())).cityCenter;
				double ang = random.nextDouble() * Math.PI * 2, dist = 220 + random.nextInt(260);
				near = new BlockPos(WorldEdge.clamp(from.getX() + (int) (Math.cos(ang) * dist)), 64, WorldEdge.clamp(from.getZ() + (int) (Math.sin(ang) * dist)));
			} else near = new BlockPos(random.nextInt(1600) - 800, 64, random.nextInt(1600) - 800);
			if (!WorldEdge.playable(near.getX(), near.getZ(), 60) || !clearOfOthers(fm, near, 220, 160)) continue;
			for (int cx = -2; cx <= 2; cx++) for (int cz = -2; cz <= 2; cz++) world.getChunk((near.getX() >> 4) + cx, (near.getZ() >> 4) + cz);
			BlockPos c = CityGrowth.bestCenter(world, near, random);
			if (c != null) return c;
		}
		return null;
	}

	/** A new clan (or a new camp of an existing one). */
	public static Faction found(ServerWorld world, FactionManager fm, BlockPos center, int warriors, @Nullable Faction parent) {
		MinecraftServer server = world.getServer();
		Random r = world.getRandom();
		Faction clan = parent;
		if (clan == null) {
			clan = fm.createFaction(r, "Clan of the " + CLAN_NAMES[r.nextInt(CLAN_NAMES.length)]);
			clan.clan = true;
			clan.government = Faction.Government.CLAN;
			clan.slavery = true;
			clan.laws.add(net.colonists.law.Law.DEATH_PENALTY);
			clan.rulerName = "Warchief " + CHIEFS[r.nextInt(CHIEFS.length)];
			clan.ruler = UUID.randomUUID();
			for (Faction o : fm.all()) if (o != clan && !o.clan) fm.setRelation(server, clan.id, o.id, -100);
		}
		String name = CAMP_A[r.nextInt(CAMP_A.length)] + CAMP_B[r.nextInt(CAMP_B.length)];
		Settlement camp = fm.foundSettlement(server, clan, center, false, name);
		camp.camp = true;
		camp.warriors = warriors;
		camp.fury = 50 + r.nextInt(40);
		fm.updateForcedChunks(server);
		FactionManager.broadcastNear(world, center, 400, Text.literal("⚔ " + (parent == null ? "A new clan gathers: the " + clan.name + ", under " + clan.rulerName
				: "The " + clan.name + " raise a new war camp") + ", at " + name + ".").formatted(Formatting.DARK_RED));
		History h = History.get(server);
		History.Event e = h.log(day(world), History.EventType.RAID);
		e.actor = clan.id;
		e.actorName = clan.name;
		e.extra = parent == null ? "gathered at " + name : "raised a new war camp at " + name;
		e.x = center.getX();
		e.z = center.getZ();
		return clan;
	}

	static void enlist(MobEntity e, Faction clan, Settlement camp) {
		e.addCommandTag(TAG);
		e.addCommandTag("clan:" + clan.id);
		e.addCommandTag("camp:" + camp.id);
		e.setPersistent();
	}

	// ---------------------------------------------------------------- warriors

	/** A warrior of the camp: crossbowmen and axemen, evokers once the camp has an armory, iron once it has a forge. */
	@Nullable
	static MobEntity warrior(ServerWorld world, Faction clan, Settlement camp, BlockPos at, boolean band) {
		Random r = world.getRandom();
		int lvl = camp.campLevel;
		int roll = r.nextInt(100);
		EntityType<? extends MobEntity> type = lvl >= 3 && roll < 8 ? EntityType.EVOKER : roll < 55 ? EntityType.PILLAGER : EntityType.VINDICATOR;
		MobEntity e = type.create(world);
		if (e == null) return null;
		int x = at.getX() + r.nextInt(7) - 3, z = at.getZ() + r.nextInt(7) - 3;
		e.refreshPositionAndAngles(x + 0.5, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5, r.nextFloat() * 360, 0);
		e.initialize(world, world.getLocalDifficulty(e.getBlockPos()), SpawnReason.EVENT, null);
		if (type == EntityType.VINDICATOR) e.equipStack(EquipmentSlot.MAINHAND, new ItemStack(lvl >= 4 ? Items.DIAMOND_AXE : Items.IRON_AXE));
		if (lvl >= 2) e.equipStack(EquipmentSlot.CHEST, new ItemStack(lvl >= 4 ? Items.IRON_CHESTPLATE : Items.CHAINMAIL_CHESTPLATE));
		if (lvl >= 4) e.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
		for (EquipmentSlot s : EquipmentSlot.values()) e.setEquipmentDropChance(s, 0.05f);
		enlist(e, clan, camp);
		if (band) e.addCommandTag("clan_band");
		if (e instanceof PatrolEntity pe) pe.setPatrolLeader(true);
		world.spawnEntity(e);
		return e;
	}

	/** The war chief: a great vindicator with a diamond axe, who stays at the clan's first camp. */
	static void chief(ServerWorld world, Faction clan, Settlement camp) {
		if (clan.rulerPlayer != null || clan.ruler == null || world.getEntity(clan.ruler) != null) return;
		MobEntity e = EntityType.VINDICATOR.create(world);
		if (e == null) return;
		BlockPos c = camp.cityCenter;
		e.setUuid(clan.ruler);
		e.refreshPositionAndAngles(c.getX() + 0.5, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ()), c.getZ() + 0.5, 0, 0);
		e.initialize(world, world.getLocalDifficulty(c), SpawnReason.EVENT, null);
		e.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_AXE));
		e.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
		e.equipStack(EquipmentSlot.HEAD, net.colonists.faction.Banners.item(world.getRegistryManager(), clan));
		e.setEquipmentDropChance(EquipmentSlot.HEAD, 1f);
		var hp = e.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
		if (hp != null) hp.setBaseValue(80);
		e.setHealth(80);
		e.setCustomName(Text.literal(clan.rulerName).formatted(Formatting.DARK_RED));
		e.setCustomNameVisible(true);
		enlist(e, clan, camp);
		e.addCommandTag("clan_chief");
		world.spawnEntity(e);
	}

	// ---------------------------------------------------------------- a camp, while it is loaded

	public static void tickCamp(ServerWorld world, FactionManager fm, Settlement s) {
		BlockPos c = s.cityCenter;
		if (!world.isChunkLoaded(c.getX() >> 4, c.getZ() >> 4)) return;
		boolean peaceful = world.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL; // illagers cannot live in a peaceful world
		Faction clan = s.faction;
		// The camp's people, in the flesh: up to a dozen about the fires (the rest are out hunting and logging).
		List<MobEntity> here = world.getEntitiesByClass(MobEntity.class, new Box(c).expand(64, 32, 64),
				e -> e.isAlive() && e.getCommandTags().contains("camp:" + s.id) && !e.getCommandTags().contains("clan_band") && !e.getCommandTags().contains("clan_chief"));
		int want = peaceful ? 0 : Math.min(12, s.warriors);
		for (int i = here.size(); i < want && i < here.size() + 2; i++) warrior(world, clan, s, c, false);
		// Ravagers in the pen, once there is one.
		if (!peaceful && s.campLevel >= 5 && world.getEntitiesByClass(net.minecraft.entity.mob.RavagerEntity.class, new Box(c).expand(48), e -> e.getCommandTags().contains("camp:" + s.id)).isEmpty()) {
			var rav = EntityType.RAVAGER.create(world);
			if (rav != null) {
				rav.refreshPositionAndAngles(c.getX() - 9 + 0.5, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, c.getX() - 9, c.getZ() + 7), c.getZ() + 7 + 0.5, 0, 0);
				rav.initialize(world, world.getLocalDifficulty(c), SpawnReason.EVENT, null);
				enlist(rav, clan, s);
				world.spawnEntity(rav);
			}
		}
		// Stragglers come home.
		for (MobEntity e : here) {
			if (e.squaredDistanceTo(c.getX(), e.getY(), c.getZ()) > 36 * 36 && e instanceof PatrolEntity pe && e.getTarget() == null) {
				pe.setPatrolTarget(c);
				((net.colonists.mixin.PatrolEntityAccess) pe).colonists$setPatrolling(true);
			}
			// Never strike a player who rides with the clan.
			if (e.getTarget() instanceof PlayerEntity p && clan.id.equals(fm.playerFaction(p.getUuid()))) e.setTarget(null);
		}
		if (s.isCapital() && !peaceful) chief(world, clan, s);
		// Building: the next building goes up when the stores allow.
		if (s.campLevel < ClanBuildings.ORDER.length && s.stock >= ClanBuildings.COST[s.campLevel]) {
			if (ClanBuildings.raise(world, c, s.campLevel)) {
				s.stock -= ClanBuildings.COST[s.campLevel];
				FactionManager.broadcastNear(world, c, 200, Text.literal("🪓 The " + clan.name + " raise a " + ClanBuildings.ORDER[s.campLevel].name.toLowerCase()
						+ " at " + s.name + (s.campLevel == 1 ? ", and a palisade of stakes around the camp" : "") + ".").formatted(Formatting.GRAY));
				s.campLevel++;
				fm.markDirty();
			}
		}
		net.colonists.faction.Banners.fly(world, new BlockPos[]{c.north(2), c.south(2), c.east(2)}, clan.banner, clan.name);
	}

	// ---------------------------------------------------------------- the days

	/** Gathering, recruiting, and the itch for war. */
	public static void days(ServerWorld world, FactionManager fm, Settlement s, long day) {
		Random r = world.getRandom();
		if (s.lastRaidDay == -100) s.lastRaidDay = day;
		// Hunting, logging and mining; captives' labour.
		s.stock += Math.max(2, s.warriors * 2 / 3) + s.captives * 2;
		// Every spare scrap goes to war: new warriors.
		int cap = 10 + s.campLevel * 6;
		for (int i = 0; i < 6 && s.stock >= 12 && s.warriors < cap; i++) {
			s.stock -= 12;
			s.warriors++;
		}
		s.fury = Math.min(100, s.fury + 3);
		// A big camp sends warriors to raise another.
		if (s.warriors >= 22 && s.faction.settlements.values().stream().filter(x -> x.camp).count() < 3 && r.nextInt(4) == 0) {
			BlockPos site = nearby(world, fm, s.cityCenter, r);
			if (site != null) {
				s.warriors -= 10;
				found(world, fm, site, 10, s.faction);
			}
		}
		// War.
		boolean great = greatRaid(world.getServer(), s.faction);
		long rest = Math.max(1, 3 - s.campLevel / 3 - (s.fury >= 80 ? 1 : 0));
		if (s.band.isEmpty() && s.warriors >= 6 && (great || day - s.lastRaidDay >= rest)) {
			Settlement target = target(fm, s, great);
			if (target != null) launch(world, fm, s, target, great);
		}
	}

	@Nullable
	static BlockPos nearby(ServerWorld world, FactionManager fm, BlockPos from, Random r) {
		for (int i = 0; i < 10; i++) {
			double ang = r.nextDouble() * Math.PI * 2, dist = 130 + r.nextInt(110);
			BlockPos near = new BlockPos(WorldEdge.clamp(from.getX() + (int) (Math.cos(ang) * dist)), 64, WorldEdge.clamp(from.getZ() + (int) (Math.sin(ang) * dist)));
			if (!WorldEdge.playable(near.getX(), near.getZ(), 60) || !clearOfOthers(fm, near, 120, 110)) continue;
			if (!world.isChunkLoaded(near.getX() >> 4, near.getZ() >> 4)) continue;
			BlockPos c = CityGrowth.bestCenter(world, near, r);
			if (c != null) return c;
		}
		return null;
	}

	/** The nearest town worth plundering (in a Great Raid: the greatest realm's towns first). */
	@Nullable
	static Settlement target(FactionManager fm, Settlement camp, boolean great) {
		Settlement best = null;
		double bestScore = Double.MAX_VALUE;
		Faction biggest = null;
		if (great) for (Faction f : fm.all()) if (!f.clan && (biggest == null || f.settlements.size() > biggest.settlements.size())) biggest = f;
		for (Settlement s : fm.allSettlements()) {
			if (s.camp || s.faction == null || s.faction == camp.faction || s.faction.clan || s.cityCenter == null) continue;
			double d = Math.sqrt(s.cityCenter.getSquaredDistance(camp.cityCenter));
			if (d > (great ? 1400 : 650)) continue;
			// Rich towns tempt; strong ones give pause.
			double score = d - Math.min(80, s.treasury) - (s.faction == biggest ? 400 : 0) + s.countJob("GUARD") * 12;
			if (score < bestScore) {
				bestScore = score;
				best = s;
			}
		}
		return best;
	}

	/** Orders a war band out of a camp against a town. Public for the chief's command and the self-test. */
	public static String launch(ServerWorld world, FactionManager fm, Settlement camp, Settlement target, boolean great) {
		if (!camp.band.isEmpty()) return "That camp already has a war band out.";
		int size = Math.min(camp.warriors - 2, Math.min(great ? 24 : 18, 6 + camp.campLevel * 2 + (great ? 6 : 0)));
		if (size < 4) return "The camp has too few warriors to spare (" + camp.warriors + ").";
		long day = day(world);
		camp.lastRaidDay = day;
		camp.warriors -= size;
		camp.bandTarget = target.id;
		camp.bandSince = world.getTime();
		Faction clan = camp.faction;
		fm.setRelation(world.getServer(), clan.id, target.faction.id, -100);
		BlockPos c = target.cityCenter;
		if (!world.isChunkLoaded(c.getX() >> 4, c.getZ() >> 4) || world.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) {
			// Nobody is there to see it: the raid is decided by strength alone.
			resolve(world, fm, camp, target, size);
			return null;
		}
		// The band falls on the town from the direction of its camp, at the edge of the houses.
		double dx = camp.cityCenter.getX() - c.getX(), dz = camp.cityCenter.getZ() - c.getZ(), len = Math.max(1, Math.hypot(dx, dz));
		int reach = CityGrowth.reach(target) + 14;
		BlockPos at = new BlockPos(c.getX() + (int) (dx / len * reach), c.getY(), c.getZ() + (int) (dz / len * reach));
		for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) world.getChunk((at.getX() >> 4) + cx, (at.getZ() >> 4) + cz);
		for (int i = 0; i < size; i++) {
			MobEntity e = warrior(world, clan, camp, at, true);
			if (e == null) continue;
			camp.band.add(e.getUuid());
			if (e instanceof PatrolEntity pe) {
				pe.setPatrolTarget(c);
				((net.colonists.mixin.PatrolEntityAccess) pe).colonists$setPatrolling(true);
			}
		}
		// War beasts and engines from a camp that has them.
		if (camp.campLevel >= 5) {
			var rav = EntityType.RAVAGER.create(world);
			if (rav != null) {
				rav.refreshPositionAndAngles(at.getX() + 0.5, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()), at.getZ() + 0.5, 0, 0);
				rav.initialize(world, world.getLocalDifficulty(at), SpawnReason.EVENT, null);
				enlist(rav, clan, camp);
				rav.addCommandTag("clan_band");
				world.spawnEntity(rav);
				camp.band.add(rav.getUuid());
			}
		}
		if (camp.campLevel >= 3 && net.colonists.compat.SiegeworksCompat.PRESENT) {
			Entity engine = net.colonists.compat.SiegeworksCompat.bringEngineAt(world, target, clan, at);
			if (engine != null) {
				// Two crossbowmen crew it.
				int crew = 0;
				for (UUID u : camp.band) {
					if (crew >= 2) break;
					if (world.getEntity(u) instanceof PillagerEntity p) {
						p.addCommandTag("siegeworks_crew");
						p.addCommandTag("crew:" + engine.getUuid());
						crew++;
					}
				}
			}
		}
		FactionManager.broadcastNear(world, c, 300, Text.literal("⚔ A war band of the " + clan.name + " (" + size + " strong) falls upon " + target.name + "!").formatted(Formatting.DARK_RED, Formatting.BOLD));
		return null;
	}

	// ---------------------------------------------------------------- a war band in the field

	public static void tickBand(ServerWorld world, FactionManager fm, Settlement camp) {
		Settlement target = fm.settlement(camp.bandTarget);
		Faction clan = camp.faction;
		if (target == null || target.cityCenter == null) {
			endBand(world, camp, "");
			return;
		}
		BlockPos c = target.cityCenter;
		List<MobEntity> alive = new ArrayList<>();
		int unseen = 0;
		for (UUID u : new ArrayList<>(camp.band)) {
			Entity e = world.getEntity(u);
			if (e instanceof MobEntity m && m.isAlive()) alive.add(m);
			else if (e == null) unseen++;
			else camp.band.remove(u);
		}
		if (alive.isEmpty() && unseen == 0) {
			camp.fury = Math.min(100, camp.fury + 10);
			FactionManager.broadcastNear(world, c, 300, Text.literal("🛡 " + target.name + " has thrown back the war band of the " + clan.name + "!").formatted(Formatting.GREEN));
			endBand(world, camp, "");
			return;
		}
		if (alive.isEmpty()) {
			// The fight moved out of sight: decide it by strength.
			if (!world.isChunkLoaded(c.getX() >> 4, c.getZ() >> 4)) {
				int n = unseen;
				camp.band.clear();
				resolve(world, fm, camp, target, n);
			}
			return;
		}
		if (target.faction == clan || world.getTime() - camp.bandSince > 9000) {
			endBand(world, camp, target.faction == clan ? "" : "The war band of the " + clan.name + " withdraws from " + target.name + " with what it could carry.");
			return;
		}
		int atPlaza = 0;
		for (MobEntity m : alive) {
			// Siege crews stay by their engine.
			UUID crewOf = null;
			for (String tag : m.getCommandTags()) if (tag.startsWith("crew:")) crewOf = UUID.fromString(tag.substring(5));
			Entity engine = crewOf == null ? null : world.getEntity(crewOf);
			if (engine != null && engine.isAlive()) {
				if (m instanceof PatrolEntity pe) {
					pe.setPatrolTarget(engine.getBlockPos());
					((net.colonists.mixin.PatrolEntityAccess) pe).colonists$setPatrolling(true);
				}
				continue;
			}
			if (m.getTarget() == null || !m.getTarget().isAlive()) {
				ColonistEntity prey = world.getEntitiesByClass(ColonistEntity.class, m.getBoundingBox().expand(20, 8, 20),
						o -> o.isAlive() && !clan.id.equals(o.getFactionId())).stream().min(java.util.Comparator.comparingDouble(m::squaredDistanceTo)).orElse(null);
				if (prey != null) m.setTarget(prey);
				else if (m instanceof PatrolEntity pe && (!pe.hasPatrolTarget() || m.squaredDistanceTo(c.getX(), m.getY(), c.getZ()) > 40 * 40)) {
					pe.setPatrolTarget(c);
					((net.colonists.mixin.PatrolEntityAccess) pe).colonists$setPatrolling(true);
				}
			}
			if (m.getTarget() instanceof PlayerEntity p && clan.id.equals(fm.playerFaction(p.getUuid()))) m.setTarget(null);
			if (m.squaredDistanceTo(c.getX(), m.getY(), c.getZ()) < 16 * 16) atPlaza++;
		}
		// Holding the heart of the town with no soldier left to stop them: the town is falling.
		if (atPlaza >= 2) {
			boolean defended = !world.getEntitiesByClass(ColonistEntity.class, new Box(c).expand(40, 12, 40),
					o -> o.isAlive() && o.isSoldier() && target.faction != null && o.getFactionId().equals(target.faction.id)).isEmpty();
			if (!defended) Empire.besiege(world, fm, target, clan, 2 + camp.campLevel / 2 + (greatRaid(world.getServer(), clan) ? 2 : 0));
		}
	}

	/** The band comes home: survivors rejoin the camp. */
	static void endBand(ServerWorld world, Settlement camp, String message) {
		int back = 0;
		for (UUID u : camp.band) {
			Entity e = world.getEntity(u);
			if (e == null) back++;
			else if (e.isAlive()) {
				back++;
				e.discard();
			}
		}
		camp.warriors += back;
		camp.band.clear();
		camp.bandTarget = -1;
		if (!message.isEmpty() && camp.cityCenter != null) FactionManager.broadcastNear(world, camp.cityCenter, 400, Text.literal(message).formatted(Formatting.GRAY));
	}

	/** A raid nobody saw: the band's strength against the town's defences. */
	static void resolve(ServerWorld world, FactionManager fm, Settlement camp, Settlement target, int size) {
		Random r = world.getRandom();
		Faction clan = camp.faction;
		int strength = size * 2 + camp.campLevel * 3 + (greatRaid(world.getServer(), clan) ? 8 : 0) + r.nextInt(12);
		int defence = target.countJob("GUARD") * 3 + Math.max(0, target.garrison) + target.rank.ordinal() * 4 + (target.wallPlanned ? 10 : 0) + r.nextInt(12);
		int lost = Math.min(size, Math.max(1, defence / 4 + r.nextInt(3)));
		camp.warriors += size - lost;
		if (strength > defence) {
			Faction loser = target.faction;
			fm.transferSettlement(target, clan);
			onConquest(world.getServer(), target, clan, loser, camp);
		} else {
			camp.fury = Math.min(100, camp.fury + 10);
			FactionManager.broadcastNear(world, target.cityCenter, 600, Text.literal("🛡 " + target.name + " beat off a raid by the " + clan.name + ".").formatted(Formatting.GREEN));
		}
	}

	// ---------------------------------------------------------------- the fall of a town

	enum Fate { SACK, STRONGHOLD, RAZE }

	/** Called when a clan has taken a town (by siege or by strength): sack it, keep it, or burn it to the ground. */
	public static void onConquest(MinecraftServer server, Settlement s, Faction clan, @Nullable Faction loser, @Nullable Settlement camp) {
		ServerWorld world = server.getOverworld();
		FactionManager fm = FactionManager.get(server);
		Random r = world.getRandom();
		if (camp == null) {
			for (Settlement x : camps(fm)) if (x.faction == clan && (camp == null || x.bandTarget == s.id)) camp = x;
		}
		if (camp == null) return;
		int fury = camp.fury;
		int roll = r.nextInt(100);
		boolean loserGone = loser == null || fm.get(loser.id) == null || loser.settlements.isEmpty();
		Fate fate = loserGone ? Fate.STRONGHOLD : roll < fury / 4 ? Fate.RAZE : roll < fury / 4 + 25 ? Fate.STRONGHOLD : Fate.SACK;
		int loot = fate == Fate.RAZE ? s.treasury : Math.min(s.treasury, 20 + camp.campLevel * 10);
		s.treasury -= loot;
		camp.stock += loot * 2 + 20;
		int taken = 0;
		if (fate != Fate.STRONGHOLD) {
			taken = carryOff(world, fm, s, fate == Fate.RAZE ? 3 + r.nextInt(4) : 1 + r.nextInt(3));
			camp.captives += taken;
			burn(world, s, fate == Fate.RAZE ? 999 : 3);
			fm.transferSettlement(s, loser);
			s.loyalty = fate == Fate.RAZE ? 0 : 10;
			s.occupation = 0;
			camp.fury = Math.max(20, camp.fury - (fate == Fate.RAZE ? 30 : 15));
		} else {
			// The town is the clan's now; its people are put to work as thralls.
			for (Faction.Citizen c : clan.citizens.values()) if (c.settlement == s.id) c.bonded = true;
			s.loyalty = 5;
			camp.fury = Math.max(20, camp.fury - 10);
		}
		String what = switch (fate) {
			case SACK -> "sacked " + s.name + ", carrying off " + loot + " emeralds and " + taken + (taken == 1 ? " captive" : " captives");
			case RAZE -> "burned " + s.name + " to the ground, and dragged " + taken + " of its people away in chains";
			case STRONGHOLD -> "seized " + s.name + " and made it a stronghold; its people are thralls now";
		};
		FactionManager.broadcast(server, Text.literal((fate == Fate.RAZE ? "🔥 " : "⚔ ") + "The " + clan.name + " have " + what + "!").formatted(Formatting.DARK_RED, Formatting.BOLD));
		History.Event e = History.get(server).log(day(world), History.EventType.RAID);
		e.actor = clan.id;
		e.actorName = clan.name;
		e.target = loser == null ? "" : loser.id;
		e.targetName = s.name;
		e.extra = what;
		e.x = s.cityCenter.getX();
		e.z = s.cityCenter.getZ();
		// Members who rode with the band share the spoils.
		for (ServerPlayerEntity p : world.getPlayers()) {
			if (!clan.id.equals(fm.playerFaction(p.getUuid())) || p.squaredDistanceTo(s.cityCenter.getX(), p.getY(), s.cityCenter.getZ()) > 96 * 96) continue;
			fm.changeRep(server, p, clan.id, 15);
			net.colonists.player.PlayerData.of(server, p.getUuid()).bank += Math.max(5, loot / 4);
			p.sendMessage(Text.literal("Your share of the plunder: " + Math.max(5, loot / 4) + " emeralds (in your bank).").formatted(Formatting.GOLD), false);
		}
		if (camp.band.size() > 0 && camp.bandTarget == s.id) endBand(world, camp, "");
	}

	/** Captives: colonists of the town, carried off (out of the town and the world). */
	static int carryOff(ServerWorld world, FactionManager fm, Settlement s, int n) {
		Faction f = s.faction;
		if (f == null) return 0;
		int done = 0;
		for (UUID u : new ArrayList<>(f.citizens.keySet())) {
			if (done >= n) break;
			Faction.Citizen c = f.citizens.get(u);
			if (c == null || c.settlement != s.id || u.equals(f.ruler)) continue;
			if (world.getEntity(u) instanceof ColonistEntity col) col.discard();
			fm.removeMember(world.getServer(), f.id, u);
			done++;
		}
		return done;
	}

	/** Fire on the roofs of a town's buildings (only where the town is loaded). */
	static void burn(ServerWorld world, Settlement s, int buildings) {
		Random r = world.getRandom();
		List<BlockPos> lots = new ArrayList<>(s.homes.keySet());
		lots.addAll(s.buildingTypes.keySet());
		java.util.Collections.shuffle(lots, new java.util.Random(r.nextLong()));
		int done = 0;
		for (BlockPos o : lots) {
			if (done >= buildings) break;
			if (!world.isChunkLoaded(o.getX() >> 4, o.getZ() >> 4)) continue;
			for (int i = 0; i < 4; i++) {
				int x = o.getX() + r.nextInt(6), z = o.getZ() + r.nextInt(6);
				BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING, new BlockPos(x, 0, z));
				if (world.getBlockState(top).isAir()) world.setBlockState(top, AbstractFireBlock.getState(world, top));
			}
			done++;
		}
	}

	/** Enemies have overrun a war camp: it is burned, its warriors scattered, and the clan is smaller (or gone). */
	public static void burnCamp(MinecraftServer server, FactionManager fm, Settlement camp, Faction victor) {
		ServerWorld world = server.getOverworld();
		Faction clan = camp.faction;
		if (camp.cityCenter != null && world.isChunkLoaded(camp.cityCenter.getX() >> 4, camp.cityCenter.getZ() >> 4)) {
			Random r = world.getRandom();
			for (int i = 0; i < 40; i++) {
				int x = camp.cityCenter.getX() + r.nextInt(50) - 25, z = camp.cityCenter.getZ() + r.nextInt(50) - 25;
				BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING, new BlockPos(x, 0, z));
				if (world.getBlockState(top).isAir()) world.setBlockState(top, AbstractFireBlock.getState(world, top));
			}
		}
		camp.warriors = 0;
		endBand(world, camp, "");
		fm.removeSettlement(camp);
		FactionManager.broadcast(server, Text.literal("🔥 The " + victor.name + " have stormed and burned " + camp.name + ", a war camp of the " + clan.name + "!").formatted(Formatting.GOLD, Formatting.BOLD));
		History.Event e = History.get(server).log(day(world), History.EventType.RAID);
		e.actor = clan.id;
		e.actorName = clan.name;
		e.extra = "lost their war camp at " + camp.name + ", burned by the " + victor.name;
		UUID conqueror = victor.rulerPlayer != null ? victor.rulerPlayer : victor.ruler;
		if (conqueror != null) net.colonists.legend.Legends.earn(server, conqueror, History.VALOUR, 20);
		if (clan.settlements.isEmpty()) fm.dissolveIfEmpty(server, clan);
	}

	// ---------------------------------------------------------------- death of warriors and chiefs

	static void onDeath(LivingEntity dead, DamageSource source) {
		if (!(dead.getWorld() instanceof ServerWorld world)) return;
		MinecraftServer server = world.getServer();
		FactionManager fm = FactionManager.get(server);
		Entity killer = source.getAttacker();
		// Colonists slain by a player who rides with a clan: the clan's respect.
		if (dead instanceof ColonistEntity && killer instanceof ServerPlayerEntity p) {
			String mine = fm.playerFaction(p.getUuid());
			Faction f = mine == null ? null : fm.get(mine);
			if (f != null && f.clan) fm.changeRep(server, p, f.id, 4);
		}
		if (!dead.getCommandTags().contains(TAG)) return;
		Settlement camp = campOf(fm, dead);
		if (camp != null) {
			if (camp.band.remove(dead.getUuid())) {
				// a band member: the band is smaller now
			} else if (!dead.getCommandTags().contains("clan_chief")) camp.warriors = Math.max(0, camp.warriors - 1);
		}
		if (!dead.getCommandTags().contains("clan_chief") || camp == null) return;
		Faction clan = camp.faction;
		if (clan == null) return;
		FactionManager.broadcast(server, Text.literal("☠ " + clan.rulerName + " of the " + clan.name + " has fallen" + (killer instanceof PlayerEntity pk ? " to " + pk.getName().getString() : "") + "!").formatted(Formatting.GOLD, Formatting.BOLD));
		if (killer instanceof ServerPlayerEntity p) {
			net.colonists.player.PlayerData.Profile pr = net.colonists.player.PlayerData.of(server, p.getUuid());
			pr.add("chiefSlain", 1);
			pr.deed(day(world), "slew " + clan.rulerName + " of the " + clan.name);
			net.colonists.legend.Legends.earn(server, p.getUuid(), History.VALOUR, 40);
		}
		net.colonists.legend.Crises.onFigureDeath(server, dead.getUuid(), killer);
		// The clan breaks apart among the camp-chiefs; a lone camp takes a new chief, burning for vengeance.
		breakUp(server, fm, clan);
		clan.rulerName = "Warchief " + CHIEFS[world.getRandom().nextInt(CHIEFS.length)];
		clan.ruler = UUID.randomUUID();
		camp.fury = 100;
	}

	/** Splits a clan's camps into clans of their own. */
	public static void breakUp(MinecraftServer server, FactionManager fm, Faction clan) {
		Random r = server.getOverworld().getRandom();
		List<Settlement> camps = new ArrayList<>(clan.settlements.values());
		camps.removeIf(x -> !x.camp || x.isCapital());
		for (Settlement s : camps) {
			Faction split = fm.createFaction(r, "Clan of the " + CLAN_NAMES[r.nextInt(CLAN_NAMES.length)]);
			split.clan = true;
			split.government = Faction.Government.CLAN;
			split.slavery = true;
			split.rulerName = "Warchief " + CHIEFS[r.nextInt(CHIEFS.length)];
			split.ruler = UUID.randomUUID();
			fm.transferSettlement(s, split);
			for (Faction o : fm.all()) if (o != split && !o.clan) fm.setRelation(server, split.id, o.id, -100);
			// Re-tag the warriors in the flesh.
			if (s.cityCenter != null) for (MobEntity m : server.getOverworld().getEntitiesByClass(MobEntity.class, new Box(s.cityCenter).expand(80, 40, 80),
					e -> e.getCommandTags().contains("camp:" + s.id))) {
				m.removeCommandTag("clan:" + clan.id);
				m.addCommandTag("clan:" + split.id);
			}
		}
		if (!camps.isEmpty()) FactionManager.broadcast(server, Text.literal("The " + clan.name + " breaks apart into " + (camps.size() + 1) + " clans.").formatted(Formatting.GRAY));
	}

	// ---------------------------------------------------------------- settling down

	/** A clan that holds three towns of settled folk lays down the axe and becomes a kingdom (as the tribes took Rome). */
	static void settle(MinecraftServer server, FactionManager fm, Faction clan) {
		long towns = clan.settlements.values().stream().filter(s -> !s.camp).count();
		if (towns < 3 || greatRaid(server, clan)) return;
		Faction.Citizen best = null;
		UUID bestId = null;
		for (Map.Entry<UUID, Faction.Citizen> e : clan.citizens.entrySet()) {
			Faction.Citizen c = e.getValue();
			c.bonded = false; // thralls no longer
			if (!c.child && (best == null || c.ambition > best.ambition)) {
				best = c;
				bestId = e.getKey();
			}
		}
		String old = clan.name;
		clan.clan = false;
		clan.government = Faction.Government.MONARCHY;
		clan.name = "Kingdom of the " + old.replace("Clan of the ", "");
		if (clan.rulerPlayer == null && bestId != null) {
			clan.ruler = bestId;
			clan.rulerName = best.fullName();
		}
		for (Settlement s : clan.settlements.values()) {
			if (s.camp) {
				s.camp = false; // the old war camps become forts of the kingdom
				s.band.clear();
			}
		}
		for (Faction o : fm.all()) if (o != clan && !o.clan) fm.setRelation(server, clan.id, o.id, -20);
		FactionManager.broadcast(server, Text.literal("👑 The " + old + " have settled the lands they won. They are the " + clan.name + " now.").formatted(Formatting.GOLD, Formatting.BOLD));
		History.Event e = History.get(server).log(day(server.getOverworld()), History.EventType.RAID);
		e.actor = clan.id;
		e.actorName = old;
		e.extra = "laid down the axe and became the " + clan.name;
	}

	// ---------------------------------------------------------------- renegades

	/** Exiles and outlaws may flee to the nearest clan. Returns true if this colonist went. */
	public static boolean welcomeRenegade(ColonistEntity c) {
		if (!(c.getWorld() instanceof ServerWorld world) || world.getRandom().nextInt(2) != 0) return false;
		FactionManager fm = FactionManager.get(world.getServer());
		Settlement near = null;
		double best = 500 * 500;
		for (Settlement s : camps(fm)) {
			double d = c.getBlockPos().getSquaredDistance(s.cityCenter);
			if (d < best) {
				best = d;
				near = s;
			}
		}
		if (near == null) return false;
		near.warriors++;
		History.Event e = History.get(world.getServer()).log(day(world), History.EventType.RAID);
		e.actor = near.faction.id;
		e.actorName = near.faction.name;
		e.person = c.fullName();
		e.extra = "took in " + c.fullName() + ", an outcast, as a raider";
		FactionManager.broadcastNear(world, c.getBlockPos(), 128, Text.literal(c.fullName() + " has fled to the " + near.faction.name + ".").formatted(Formatting.DARK_RED));
		if (!c.getFactionId().isEmpty()) fm.removeMember(world.getServer(), c.getFactionId(), c.getUuid());
		c.discard();
		return true;
	}

	// ---------------------------------------------------------------- the Great Raid

	public static boolean greatRaid(MinecraftServer server, Faction clan) {
		for (History.Crisis c : History.get(server).crises) if (c.active() && c.type.equals("GREAT_RAID") && c.faction.equals(clan.id)) return true;
		return false;
	}

	/** The clans unite under one Barbarian King and go to war together. Returns null if it began. */
	@Nullable
	public static String startGreatRaid(MinecraftServer server) {
		FactionManager fm = FactionManager.get(server);
		List<Faction> clans = clans(fm);
		if (clans.size() < 2) return "There are not enough clans to unite.";
		Faction host = clans.stream().max(java.util.Comparator.comparingInt(f -> f.settlements.values().stream().mapToInt(s -> s.warriors).sum())).orElseThrow();
		for (Faction other : clans) {
			if (other == host) continue;
			for (Settlement s : new ArrayList<>(other.settlements.values())) {
				fm.transferSettlement(s, host);
				if (s.cityCenter != null) for (MobEntity m : server.getOverworld().getEntitiesByClass(MobEntity.class, new Box(s.cityCenter).expand(80, 40, 80),
						e -> e.getCommandTags().contains("camp:" + s.id))) {
					m.removeCommandTag("clan:" + other.id);
					m.addCommandTag("clan:" + host.id);
				}
			}
		}
		Random r = server.getOverworld().getRandom();
		String king = CHIEFS[r.nextInt(CHIEFS.length)] + " the Barbarian King";
		host.rulerName = king;
		host.ruler = UUID.randomUUID();
		host.name = "Great Host of " + king.split(" ")[0];
		for (Settlement s : host.settlements.values()) {
			s.warriors += s.warriors / 2;
			s.fury = 100;
		}
		long day = day(server.getOverworld());
		History h = History.get(server);
		History.Crisis c = new History.Crisis();
		c.type = "GREAT_RAID";
		c.figure = host.ruler;
		c.figureName = king;
		c.faction = host.id;
		c.started = day;
		c.lastDay = day;
		c.fateDay = day + 15;
		h.crises.add(c);
		History.Event e = h.log(day, History.EventType.CRISIS);
		e.person = king;
		e.actor = host.id;
		e.actorName = host.name;
		e.extra = "The clans have united under one king, and the whole host is on the march.";
		FactionManager.broadcast(server, Text.literal("☄ CRISIS — THE GREAT RAID: ").formatted(Formatting.DARK_RED, Formatting.BOLD)
				.append(Text.literal(clans.size() + " clans have united under " + king + ". Every town within reach of the " + host.name + " is in danger.").formatted(Formatting.RED)));
		return null;
	}

	/** Daily, while the Great Raid lasts: it ebbs when its time is up. */
	public static void greatRaidDay(MinecraftServer server, History.Crisis c, Faction host, long day) {
		if (day < c.fateDay) return;
		c.ended = day;
		FactionManager.broadcast(server, Text.literal("☄ The Great Raid of " + c.figureName + " has spent itself; the clans go their own ways again.").formatted(Formatting.GRAY));
		breakUp(server, FactionManager.get(server), host);
	}

	// ---------------------------------------------------------------- players

	/** /colony clan join: ride with the nearest clan. */
	public static String join(ServerPlayerEntity p) {
		FactionManager fm = FactionManager.get(p.getServer());
		Settlement camp = nearestCamp(fm, p.getBlockPos(), 64);
		if (camp == null) return "Walk into a clan's war camp first (within 64 blocks).";
		Faction clan = camp.faction;
		boolean outlaw = net.colonists.law.Justice.get(p.getServer()).wantedAnywhere(p.getUuid());
		if (fm.standing(p, clan.id) < -20 && !outlaw) return "The " + clan.name + " do not trust you (standing " + fm.standing(p, clan.id) + "). Bring them colonist blood, or an outlaw's name.";
		fm.setPlayerFaction(p.getUuid(), clan.id);
		fm.changeRep(p.getServer(), p, clan.id, outlaw ? 30 : 10);
		for (Faction o : fm.all()) if (!o.clan) fm.changeRep(p.getServer(), p, o.id, -20);
		FactionManager.broadcastNear(p.getServerWorld(), camp.cityCenter, 200, Text.literal(p.getName().getString() + " now rides with the " + clan.name + ".").formatted(Formatting.DARK_RED));
		return null;
	}

	/** /colony clan chief: one with enough standing is raised on a shield as war chief. */
	public static String becomeChief(ServerPlayerEntity p) {
		FactionManager fm = FactionManager.get(p.getServer());
		String id = fm.playerFaction(p.getUuid());
		Faction clan = id == null ? null : fm.get(id);
		if (clan == null || !clan.clan) return "You ride with no clan.";
		if (p.getUuid().equals(clan.rulerPlayer)) return "You are already war chief.";
		int standing = fm.standing(p, clan.id);
		if (standing < 100) return "The clan follows only the bloodiest. Your standing is " + standing + " of the 100 it takes.";
		// The old chief does not go quietly.
		if (clan.ruler != null && p.getServerWorld().getEntity(clan.ruler) instanceof MobEntity old && old.isAlive()) {
			old.setTarget(p);
			return "First you must kill " + clan.rulerName + ". He is coming for you.";
		}
		clan.rulerPlayer = p.getUuid();
		clan.rulerName = "Warchief " + p.getName().getString();
		FactionManager.broadcast(p.getServer(), Text.literal("🪓 " + p.getName().getString() + " is raised on a shield as war chief of the " + clan.name + "!").formatted(Formatting.DARK_RED, Formatting.BOLD));
		return null;
	}

	/** /colony clan raid <town>: the war chief sends a band. */
	public static String orderRaid(ServerPlayerEntity p, String townName) {
		FactionManager fm = FactionManager.get(p.getServer());
		String id = fm.playerFaction(p.getUuid());
		Faction clan = id == null ? null : fm.get(id);
		if (clan == null || !clan.clan || !p.getUuid().equals(clan.rulerPlayer)) return "Only a clan's war chief can send its war bands.";
		Settlement target = fm.allSettlements().stream().filter(s -> s.name.equalsIgnoreCase(townName) && !s.camp).findFirst().orElse(null);
		if (target == null) return "No town called " + townName + ".";
		if (target.faction == clan) return "That town is already yours.";
		Settlement best = null;
		for (Settlement s : clan.settlements.values()) {
			if (!s.camp || !s.band.isEmpty()) continue;
			if (best == null || s.cityCenter.getSquaredDistance(target.cityCenter) < best.cityCenter.getSquaredDistance(target.cityCenter)) best = s;
		}
		if (best == null) return "Every camp already has a band out.";
		return launch(p.getServerWorld(), fm, best, target, greatRaid(p.getServer(), clan));
	}

	@Nullable
	public static Settlement nearestCamp(FactionManager fm, BlockPos at, int within) {
		Settlement best = null;
		double bestD = within * (double) within;
		for (Settlement s : camps(fm)) {
			double d = s.cityCenter.getSquaredDistance(at.getX(), s.cityCenter.getY(), at.getZ());
			if (d < bestD) {
				bestD = d;
				best = s;
			}
		}
		return best;
	}

	public static List<Text> describe(MinecraftServer server, @Nullable ServerPlayerEntity p) {
		FactionManager fm = FactionManager.get(server);
		List<Text> lines = new ArrayList<>();
		List<Faction> clans = clans(fm);
		if (clans.isEmpty()) {
			lines.add(Text.literal("No clans ride the land yet.").formatted(Formatting.GRAY));
			return lines;
		}
		for (Faction f : clans) {
			lines.add(Text.literal("🪓 " + f.name + " — " + f.rulerName + (greatRaid(server, f) ? " (THE GREAT RAID)" : "")).formatted(Formatting.DARK_RED));
			for (Settlement s : f.settlements.values()) {
				String state = s.camp ? "war camp, " + s.warriors + " warriors, built up " + s.campLevel + "/6, fury " + s.fury
						+ (s.band.isEmpty() ? "" : ", band out against " + (fm.settlement(s.bandTarget) == null ? "?" : fm.settlement(s.bandTarget).name))
						: "stronghold, " + s.population() + " thralls";
				lines.add(Text.literal("   " + s.name + " (" + s.cityCenter.getX() + ", " + s.cityCenter.getZ() + "): " + state).formatted(Formatting.GRAY));
			}
			if (p != null) lines.add(Text.literal("   Your standing: " + fm.standing(p, f.id)).formatted(Formatting.DARK_GRAY));
		}
		return lines;
	}
}
