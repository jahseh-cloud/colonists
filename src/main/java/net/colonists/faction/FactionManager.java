package net.colonists.faction;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.PersistentState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * World-saved registry of every faction, the relations between them, and players' allegiances.
 * Relations run from -100 (bitter war) to +100 (sworn allies).
 */
public class FactionManager extends PersistentState {
	public static final int WAR = -50;
	public static final int ALLY = 50;

	private static final String[] ADJECTIVES = {"Iron", "Red", "Stone", "Oak", "Silver", "Ash", "Golden", "Black",
			"Storm", "Moss", "Frost", "Ember", "Copper", "Birch", "Deep", "Wild", "River", "Hollow", "Bright", "Dusk"};
	private static final String[] NOUNS = {"Wolves", "Ravens", "Bears", "Foxes", "Hammers", "Pines", "Lanterns", "Picks",
			"Shields", "Owls", "Stags", "Boars", "Hawks", "Roots", "Anvils", "Torches", "Badgers", "Crows", "Oaks", "Spades"};
	private static final Formatting[] COLORS = {Formatting.RED, Formatting.BLUE, Formatting.GREEN, Formatting.GOLD,
			Formatting.DARK_PURPLE, Formatting.AQUA, Formatting.YELLOW, Formatting.DARK_AQUA, Formatting.LIGHT_PURPLE,
			Formatting.DARK_GREEN, Formatting.DARK_RED, Formatting.DARK_BLUE};

	private static final Type<FactionManager> TYPE = new Type<>(FactionManager::new, FactionManager::fromNbt, null);

	private final Map<String, Faction> factions = new LinkedHashMap<>();
	private final Map<String, Integer> relations = new HashMap<>();
	private final Map<UUID, String> playerFactions = new HashMap<>();
	/** Chunks this mod has force-loaded to keep cities alive (packed chunk positions). */
	private final java.util.Set<Long> forced = new java.util.HashSet<>();

	public static FactionManager get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "colonists_factions");
	}

	// ---------------------------------------------------------------- factions

	public Faction createFaction(Random random, @Nullable String forcedName) {
		String name;
		String id;
		int attempts = 0;
		do {
			if (forcedName != null) {
				name = attempts == 0 ? forcedName : forcedName + " " + (attempts + 1);
			} else {
				name = ADJECTIVES[random.nextInt(ADJECTIVES.length)] + " " + NOUNS[random.nextInt(NOUNS.length)];
			}
			id = name.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
			if (id.isEmpty()) id = "faction_" + factions.size();
			attempts++;
		} while (factions.containsKey(id) && attempts < 50);
		Formatting color = COLORS[factions.size() % COLORS.length];
		Faction f = new Faction(id, name, color);
		factions.put(id, f);
		markDirty();
		return f;
	}

	@Nullable
	public Faction get(@Nullable String id) {
		return id == null || id.isEmpty() ? null : factions.get(id);
	}

	public List<Faction> all() {
		return new ArrayList<>(factions.values());
	}

	public int memberCount(String id) {
		Faction f = get(id);
		if (f == null) return 0;
		int players = 0;
		for (String pf : playerFactions.values()) if (pf.equals(id)) players++;
		return f.members.size() + players;
	}

	public void addMember(String id, UUID colonist) {
		Faction f = get(id);
		if (f != null && f.members.add(colonist)) markDirty();
	}

	public void removeMember(MinecraftServer server, String id, UUID colonist) {
		Faction f = get(id);
		if (f == null) return;
		f.members.remove(colonist);
		f.citizens.remove(colonist);
		for (Settlement st : f.settlements.values()) {
			for (Map.Entry<BlockPos, UUID> e : st.homes.entrySet()) {
				if (colonist.equals(e.getValue())) e.setValue(null);
			}
		}
		markDirty();
		if (memberCount(id) == 0 && !f.freeVillage) {
			factions.remove(id);
			relations.keySet().removeIf(k -> k.startsWith(id + "|") || k.endsWith("|" + id));
			broadcast(server, Text.literal("The ").append(f.displayName()).append(" have fallen. Their last member is gone.").formatted(Formatting.GRAY));
		}
	}

	// ---------------------------------------------------------------- homes

	public void claimHome(Settlement st, BlockPos origin, UUID owner) {
		st.homes.put(origin.toImmutable(), owner);
		markDirty();
	}

	/** Gives the colonist an empty house in their settlement, if any. */
	@Nullable
	public BlockPos claimVacantHome(Settlement st, UUID owner) {
		for (Map.Entry<BlockPos, UUID> e : st.homes.entrySet()) {
			if (e.getValue() == null) {
				e.setValue(owner);
				markDirty();
				return e.getKey();
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- settlements

	private int nextSettlementId = 1;
	private final Map<Integer, String> settlementOwners = new HashMap<>();

	public int newSettlementId() {
		markDirty();
		return this.nextSettlementId++;
	}

	@Nullable
	public Settlement settlement(int id) {
		Faction f = get(this.settlementOwners.get(id));
		Settlement s = f == null ? null : f.settlements.get(id);
		if (s == null) {
			// Index may be stale after a conquest; rebuild it.
			this.reindex();
			f = get(this.settlementOwners.get(id));
			s = f == null ? null : f.settlements.get(id);
		}
		return s;
	}

	public void reindex() {
		this.settlementOwners.clear();
		for (Faction f : factions.values()) {
			for (Settlement s : f.settlements.values()) {
				s.faction = f;
				this.settlementOwners.put(s.id, f.id);
			}
		}
	}

	public List<Settlement> allSettlements() {
		List<Settlement> list = new ArrayList<>();
		for (Faction f : factions.values()) list.addAll(f.settlements.values());
		return list;
	}

	/** Founds a new settlement for a faction around a plaza centre (y = first air block above ground). */
	public Settlement foundSettlement(MinecraftServer server, Faction f, BlockPos center, boolean announce) {
		Random random = server.getOverworld().getRandom();
		Settlement s = new Settlement(newSettlementId(), Settlement.randomName(random));
		s.cityCenter = center.toImmutable();
		s.foundedDay = server.getOverworld().getTimeOfDay() / 24000L;
		f.addSettlement(s);
		this.settlementOwners.put(s.id, f.id);
		markDirty();
		if (announce) {
			broadcast(server, Text.literal("🏙 The ").formatted(Formatting.YELLOW).append(f.displayName())
					.append(Text.literal(" have founded " + (f.settlements.size() == 1 ? "" : "a new settlement, ")).formatted(Formatting.YELLOW))
					.append(s.displayName()).append(Text.literal(" at " + center.getX() + ", " + center.getZ() + ".").formatted(Formatting.YELLOW)));
		}
		return s;
	}

	/** Removes a faction that has no settlements and no members left (conquered to the last town). */
	public boolean dissolveIfEmpty(MinecraftServer server, Faction f) {
		if (!f.settlements.isEmpty() || memberCount(f.id) > 0 || !factions.containsKey(f.id)) return false;
		factions.remove(f.id);
		relations.keySet().removeIf(k -> k.startsWith(f.id + "|") || k.endsWith("|" + f.id));
		for (Faction o : factions.values()) if (o.overlord.equals(f.id)) o.overlord = "";
		broadcast(server, Text.literal("☠ The ").append(f.displayName()).append(" are no more. Their last town has fallen.").formatted(Formatting.GRAY));
		markDirty();
		return true;
	}

	/** Hands a settlement to another faction (conquest, cession, secession). Its people follow when they next wake. */
	public void transferSettlement(Settlement s, Faction to) {
		Faction from = s.faction;
		if (from == to) return;
		if (from != null) {
			from.settlements.remove(s.id);
			if (from.capital == s.id) from.capital = from.settlements.isEmpty() ? -1 : from.settlements.keySet().iterator().next();
			for (Map.Entry<UUID, Faction.Citizen> e : new ArrayList<>(from.citizens.entrySet())) {
				if (e.getValue().settlement == s.id) {
					from.citizens.remove(e.getKey());
					from.members.remove(e.getKey());
					to.citizens.put(e.getKey(), e.getValue());
					to.members.add(e.getKey());
				}
			}
			s.formerOwner = from.id;
		}
		to.addSettlement(s);
		this.settlementOwners.put(s.id, to.id);
		markDirty();
	}

	/** True if pos lies inside (or right next to) any colonist building, field or plaza. */
	public boolean isNearAnyHome(BlockPos pos) {
		for (Faction f : factions.values()) {
			for (Settlement st : f.settlements.values()) {
				if (st.cityCenter != null && Math.abs(pos.getX() - st.cityCenter.getX()) <= City.PLAZA_HALF + 1
						&& Math.abs(pos.getZ() - st.cityCenter.getZ()) <= City.PLAZA_HALF + 1
						&& Math.abs(pos.getY() - st.cityCenter.getY()) <= 6) return true;
				java.util.Set<BlockPos> lots = new java.util.HashSet<>(st.homes.keySet());
				lots.addAll(st.farms);
				lots.addAll(st.buildingTypes.keySet());
				for (BlockPos o : lots) {
					int[] r = CityGrowth.footprint(st, o);
					if (pos.getX() >= r[0] - 1 && pos.getX() <= r[2] + 1 && pos.getZ() >= r[1] - 1 && pos.getZ() <= r[3] + 1
							&& pos.getY() >= o.getY() - 7 && pos.getY() <= o.getY() + 10) return true;
				}
			}
		}
		return false;
	}

	/** Nearest settlement centre (of any faction, optionally excluding one settlement), or null. */
	@Nullable
	public Settlement nearestSettlement(BlockPos pos, @Nullable Settlement except) {
		Settlement best = null;
		double bestD = Double.MAX_VALUE;
		for (Settlement s : allSettlements()) {
			if (s == except || s.cityCenter == null) continue;
			double d = s.cityCenter.getSquaredDistance(pos.getX(), s.cityCenter.getY(), pos.getZ());
			if (d < bestD) {
				bestD = d;
				best = s;
			}
		}
		return best;
	}

	// ---------------------------------------------------------------- relations

	private static String key(String a, String b) {
		return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
	}

	public int relation(@Nullable String a, @Nullable String b) {
		if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
		if (a.equals(b)) return 100;
		return relations.getOrDefault(key(a, b), 0);
	}

	public void changeRelation(MinecraftServer server, String a, String b, int delta) {
		if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.equals(b)) return;
		Faction fa = get(a), fb = get(b);
		if (fa == null || fb == null) return;
		int old = relation(a, b);
		int now = MathHelper.clamp(old + delta, -100, 100);
		if (now == old) return;
		relations.put(key(a, b), now);
		markDirty();
		if (old > WAR && now <= WAR) {
			broadcast(server, Text.literal("⚔ ").formatted(Formatting.RED).append(fa.displayName())
					.append(Text.literal(" and ").formatted(Formatting.RED)).append(fb.displayName())
					.append(Text.literal(" are now at WAR!").formatted(Formatting.RED)));
		} else if (old <= WAR && now > WAR) {
			broadcast(server, Text.literal("☮ ").formatted(Formatting.GREEN).append(fa.displayName())
					.append(Text.literal(" and ").formatted(Formatting.GREEN)).append(fb.displayName())
					.append(Text.literal(" have made peace.").formatted(Formatting.GREEN)));
		} else if (old < ALLY && now >= ALLY) {
			broadcast(server, Text.literal("🤝 ").formatted(Formatting.AQUA).append(fa.displayName())
					.append(Text.literal(" and ").formatted(Formatting.AQUA)).append(fb.displayName())
					.append(Text.literal(" have formed an alliance!").formatted(Formatting.AQUA)));
		} else if (old >= ALLY && now < ALLY) {
			broadcast(server, Text.literal("The alliance between ").formatted(Formatting.GRAY).append(fa.displayName())
					.append(Text.literal(" and ").formatted(Formatting.GRAY)).append(fb.displayName())
					.append(Text.literal(" has broken.").formatted(Formatting.GRAY)));
		}
	}

	public void setRelation(MinecraftServer server, String a, String b, int value) {
		changeRelation(server, a, b, value - relation(a, b));
	}

	// ---------------------------------------------------------------- players

	@Nullable
	public String playerFaction(UUID player) {
		String id = playerFactions.get(player);
		if (id != null && !factions.containsKey(id)) {
			playerFactions.remove(player);
			markDirty();
			return null;
		}
		return id;
	}

	public void setPlayerFaction(UUID player, @Nullable String id) {
		if (id == null) playerFactions.remove(player);
		else playerFactions.put(player, id);
		markDirty();
	}

	/** How a faction feels about a player: personal reputation plus diplomacy with the player's own faction. */
	public int standing(PlayerEntity player, String factionId) {
		Faction f = get(factionId);
		if (f == null) return 0;
		String pf = playerFaction(player.getUuid());
		if (factionId.equals(pf)) return 100;
		int value = f.playerRep.getOrDefault(player.getUuid(), 0);
		if (pf != null) value += relation(pf, factionId);
		return MathHelper.clamp(value, -100, 100);
	}

	public void changeRep(MinecraftServer server, PlayerEntity player, String factionId, int delta) {
		Faction f = get(factionId);
		if (f == null || delta == 0) return;
		int oldStanding = standing(player, factionId);
		int rep = MathHelper.clamp(f.playerRep.getOrDefault(player.getUuid(), 0) + delta, -100, 100);
		f.playerRep.put(player.getUuid(), rep);
		markDirty();
		int newStanding = standing(player, factionId);
		if (player instanceof ServerPlayerEntity sp) {
			if (oldStanding > WAR && newStanding <= WAR) {
				sp.sendMessage(Text.literal("The ").formatted(Formatting.RED).append(f.displayName())
						.append(Text.literal(" now consider you an ENEMY.").formatted(Formatting.RED)), false);
			} else if (oldStanding <= WAR && newStanding > WAR) {
				sp.sendMessage(Text.literal("The ").append(f.displayName()).append(" no longer want you dead."), false);
			} else if (oldStanding < ALLY && newStanding >= ALLY) {
				sp.sendMessage(Text.literal("The ").formatted(Formatting.GREEN).append(f.displayName())
						.append(Text.literal(" now consider you a friend.").formatted(Formatting.GREEN)), false);
			}
			// Members who betray their own faction get thrown out.
			if (factionId.equals(playerFaction(player.getUuid())) && rep <= WAR) {
				setPlayerFaction(player.getUuid(), null);
				sp.sendMessage(Text.literal("You have been exiled from the ").formatted(Formatting.RED).append(f.displayName()), false);
			}
		}
	}

	public static String describe(int value) {
		if (value >= 100) return "Member";
		if (value >= ALLY) return "Allied";
		if (value >= 15) return "Friendly";
		if (value > -15) return "Neutral";
		if (value > WAR) return "Tense";
		return "At war";
	}

	public static Formatting describeColor(int value) {
		if (value >= ALLY) return Formatting.GREEN;
		if (value >= 15) return Formatting.DARK_GREEN;
		if (value > -15) return Formatting.GRAY;
		if (value > WAR) return Formatting.GOLD;
		return Formatting.RED;
	}

	// ---------------------------------------------------------------- diplomacy

	/** Called once a minute: relations drift, neighbours bicker, common enemies bring factions together. */
	public void tickDiplomacy(MinecraftServer server) {
		Random random = server.getOverworld().getRandom();
		List<Faction> list = all();
		for (int i = 0; i < list.size(); i++) {
			for (int j = i + 1; j < list.size(); j++) {
				Faction a = list.get(i), b = list.get(j);
				if (random.nextInt(5) != 0) continue; // each pair reconsiders roughly every five minutes
				int rel = relation(a.id, b.id);
				int delta = random.nextInt(5) - 2;
				BlockPos ca = a.center(), cb = b.center();
				if (ca != null && cb != null && ca.getSquaredDistance(cb) < 80 * 80) delta -= 1; // border tension
				for (Faction c : list) {
					if (c == a || c == b) continue;
					if (relation(a.id, c.id) <= WAR && relation(b.id, c.id) <= WAR) delta += 3; // common enemy
				}
				if (rel > 60) delta -= 1;  // alliances cool off without upkeep
				if (rel < -60) delta += 1; // wars are exhausting
				if (rel < -25 && random.nextInt(12) == 0) {
					delta -= 20;
					broadcast(server, Text.literal("A border skirmish breaks out between the ").formatted(Formatting.GOLD)
							.append(a.displayName()).append(Text.literal(" and the ").formatted(Formatting.GOLD))
							.append(b.displayName()).append(Text.literal(".").formatted(Formatting.GOLD)));
				}
				if (a.rulerPlayer != null || b.rulerPlayer != null) delta = Math.min(delta, 0) / 2; // players steer their own diplomacy
				// Rulers' temperaments colour relations.
				Faction.Citizen ra = a.rulerCitizen(), rb = b.rulerCitizen();
				if (ra != null && rb != null) {
					if (ra.aggression + rb.aggression > 130) delta -= 1;
					if (ra.kindness + rb.kindness > 130) delta += 1;
				}
				if (a.id.equals(b.overlord) || b.id.equals(a.overlord)) delta = Math.max(delta, 0);
				changeRelation(server, a.id, b.id, delta);
			}
		}
		Politics.tick(server, this);
		this.updateForcedChunks(server);
	}

	/** Keeps the core of every city loaded (bigger cities keep more), and lets go of the rest. */
	public void updateForcedChunks(MinecraftServer server) {
		ServerWorld world = server.getOverworld();
		java.util.Set<Long> want = new java.util.HashSet<>();
		if (world.getGameRules().getBoolean(net.colonists.ColonistsMod.KEEP_LOADED)) {
			for (Settlement f : allSettlements()) {
				if (f.cityCenter == null) continue;
				if (f.village && f.colonists() == 0) continue; // a village with no colonists lives its vanilla life
				int r = f.village ? 1 : switch (f.rank) {
					case VILLAGE -> 1;
					case TOWN -> 2;
					case CITY -> 3;
					case CITY_STATE -> 4;
				};
				int cx = f.cityCenter.getX() >> 4, cz = f.cityCenter.getZ() >> 4;
				for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) want.add(net.minecraft.util.math.ChunkPos.toLong(cx + dx, cz + dz));
				if (f.quarry != null) {
					int qx = f.quarry.getX() >> 4, qz = f.quarry.getZ() >> 4;
					for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) want.add(net.minecraft.util.math.ChunkPos.toLong(qx + dx, qz + dz));
				}
			}
		}
		for (Long key : new java.util.ArrayList<>(this.forced)) {
			if (!want.contains(key)) {
				world.setChunkForced(net.minecraft.util.math.ChunkPos.getPackedX(key), net.minecraft.util.math.ChunkPos.getPackedZ(key), false);
				this.forced.remove(key);
			}
		}
		for (Long key : want) {
			if (this.forced.add(key)) {
				world.setChunkForced(net.minecraft.util.math.ChunkPos.getPackedX(key), net.minecraft.util.math.ChunkPos.getPackedZ(key), true);
			}
		}
		markDirty();
	}

	public static void broadcast(MinecraftServer server, Text text) {
		server.getPlayerManager().broadcast(text, false);
	}

	public static void broadcastNear(ServerWorld world, BlockPos pos, double radius, Text text) {
		for (ServerPlayerEntity p : world.getPlayers(p -> p.getBlockPos().getSquaredDistance(pos) < radius * radius)) {
			p.sendMessage(text, false);
		}
	}

	// ---------------------------------------------------------------- save / load

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		NbtList list = new NbtList();
		for (Faction f : factions.values()) list.add(f.toNbt());
		nbt.put("Factions", list);
		NbtCompound rel = new NbtCompound();
		relations.forEach(rel::putInt);
		nbt.put("Relations", rel);
		NbtList players = new NbtList();
		playerFactions.forEach((uuid, id) -> {
			NbtCompound p = new NbtCompound();
			p.putUuid("U", uuid);
			p.putString("F", id);
			players.add(p);
		});
		nbt.put("Players", players);
		nbt.putLongArray("Forced", this.forced.stream().mapToLong(Long::longValue).toArray());
		nbt.putInt("NextSettlement", this.nextSettlementId);
		return nbt;
	}

	public static FactionManager fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		FactionManager m = new FactionManager();
		NbtList list = nbt.getList("Factions", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < list.size(); i++) {
			Faction f = Faction.fromNbt(list.getCompound(i), () -> m.nextSettlementId++);
			m.factions.put(f.id, f);
		}
		NbtCompound rel = nbt.getCompound("Relations");
		for (String k : rel.getKeys()) m.relations.put(k, rel.getInt(k));
		NbtList players = nbt.getList("Players", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < players.size(); i++) {
			NbtCompound p = players.getCompound(i);
			m.playerFactions.put(p.getUuid("U"), p.getString("F"));
		}
		for (long l : nbt.getLongArray("Forced")) m.forced.add(l);
		if (nbt.contains("NextSettlement")) m.nextSettlementId = Math.max(m.nextSettlementId, nbt.getInt("NextSettlement"));
		for (Faction f : m.factions.values()) for (int sid : f.settlements.keySet()) m.nextSettlementId = Math.max(m.nextSettlementId, sid + 1);
		m.reindex();
		return m;
	}
}
