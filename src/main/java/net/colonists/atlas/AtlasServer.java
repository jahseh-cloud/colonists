package net.colonists.atlas;

import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Settlement;
import net.colonists.faction.Statecraft;
import net.colonists.law.Law;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Serves the political atlas: map data, terrain tiles (sampled from the world generator, no chunks loaded), and orders. */
public final class AtlasServer {
	private AtlasServer() {}

	/** Terrain kinds sent to the client. */
	public static final byte DEEP_OCEAN = 1, OCEAN = 2, RIVER = 3, BEACH = 4, PLAINS = 5, FOREST = 6, TAIGA = 7, SNOW = 8,
			DESERT = 9, BADLANDS = 10, SAVANNA = 11, JUNGLE = 12, SWAMP = 13, MOUNTAIN = 14, MUSHROOM = 15, FROZEN_OCEAN = 16;

	private record Job(UUID player, int tx, int tz, int cell) {
	}

	private static final ArrayDeque<Job> QUEUE = new ArrayDeque<>();
	private static final Map<Long, byte[]> CACHE = new HashMap<>();

	public static void register() {
		PayloadTypeRegistry.playC2S().register(AtlasPayloads.Request.ID, AtlasPayloads.Request.CODEC);
		PayloadTypeRegistry.playC2S().register(AtlasPayloads.Order.ID, AtlasPayloads.Order.CODEC);
		PayloadTypeRegistry.playS2C().register(AtlasPayloads.Open.ID, AtlasPayloads.Open.CODEC);
		PayloadTypeRegistry.playS2C().register(AtlasPayloads.Tile.ID, AtlasPayloads.Tile.CODEC);
		PayloadTypeRegistry.playS2C().register(AtlasPayloads.Data.ID, AtlasPayloads.Data.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(AtlasPayloads.Request.ID, (payload, context) -> context.server().execute(() -> {
			ServerPlayerEntity p = context.player();
			if (payload.kind() == 0) {
				ServerPlayNetworking.send(p, build(p));
			} else if (QUEUE.size() < 4000) {
				int cell = Math.max(4, Math.min(256, payload.cell()));
				QUEUE.add(new Job(p.getUuid(), payload.tx(), payload.tz(), cell));
			}
		}));
		ServerPlayNetworking.registerGlobalReceiver(AtlasPayloads.Order.ID, (payload, context) -> context.server().execute(() -> {
			ServerPlayerEntity p = context.player();
			Statecraft.Order order;
			try {
				order = Statecraft.Order.valueOf(payload.order());
			} catch (IllegalArgumentException e) {
				return;
			}
			p.sendMessage(Statecraft.order(p, order, payload.a(), payload.b(), payload.x(), payload.z()), false);
			FactionManager.get(p.getServer()).markDirty();
			ServerPlayNetworking.send(p, build(p));
		}));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			// A few tiles per tick, so a freshly opened atlas never stalls the server.
			for (int i = 0; i < 3 && !QUEUE.isEmpty(); i++) {
				Job job = QUEUE.poll();
				ServerPlayerEntity p = server.getPlayerManager().getPlayer(job.player());
				if (p == null) continue;
				byte[] data = tile(server.getOverworld(), job.tx(), job.tz(), job.cell());
				ServerPlayNetworking.send(p, new AtlasPayloads.Tile(job.tx(), job.tz(), job.cell(), data));
			}
		});
	}

	// ---------------------------------------------------------------- terrain

	static byte[] tile(ServerWorld world, int tx, int tz, int cell) {
		long key = ((long) tx & 0xFFFFFL) | (((long) tz & 0xFFFFFL) << 20) | ((long) cell << 40);
		byte[] cached = CACHE.get(key);
		if (cached != null) return cached;
		int n = AtlasPayloads.Tile.SIZE;
		byte[] out = new byte[n * n];
		var source = world.getChunkManager().getChunkGenerator().getBiomeSource();
		MultiNoiseUtil.MultiNoiseSampler sampler = world.getChunkManager().getNoiseConfig().getMultiNoiseSampler();
		int seaQuart = 63 >> 2;
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				int x = (tx * n + i) * cell + cell / 2, z = (tz * n + j) * cell + cell / 2;
				RegistryEntry<Biome> b = source.getBiome(x >> 2, seaQuart, z >> 2, sampler);
				out[i + j * n] = kind(b);
			}
		}
		if (CACHE.size() > 6000) CACHE.clear();
		CACHE.put(key, out);
		return out;
	}

	static byte kind(RegistryEntry<Biome> b) {
		String id = b.getKey().map(k -> k.getValue().getPath()).orElse("plains");
		if (id.contains("frozen_ocean") || id.contains("deep_frozen")) return FROZEN_OCEAN;
		if (id.contains("deep") && id.contains("ocean")) return DEEP_OCEAN;
		if (id.contains("ocean") || id.contains("sea")) return OCEAN;
		if (id.contains("river")) return RIVER;
		if (id.contains("beach") || id.contains("shore")) return BEACH;
		if (id.contains("mushroom")) return MUSHROOM;
		if (id.contains("badlands") || id.contains("mesa")) return BADLANDS;
		if (id.contains("desert") || id.contains("dune")) return DESERT;
		if (id.contains("savanna")) return SAVANNA;
		if (id.contains("jungle") || id.contains("bamboo") || id.contains("rainforest")) return JUNGLE;
		if (id.contains("swamp") || id.contains("marsh") || id.contains("bog") || id.contains("mangrove")) return SWAMP;
		if (id.contains("peak") || id.contains("mountain") || id.contains("slope") || id.contains("hill") || id.contains("cave")
				|| id.contains("deep_dark") || id.contains("stony") || id.contains("alps") || id.contains("highland")) return MOUNTAIN;
		if (id.contains("snow") || id.contains("ice") || id.contains("frozen") || id.contains("tundra") || id.contains("glacier")) return SNOW;
		if (id.contains("taiga") || id.contains("pine") || id.contains("spruce") || id.contains("grove")) return TAIGA;
		if (id.contains("forest") || id.contains("wood") || id.contains("birch") || id.contains("grove")) return FOREST;
		return PLAINS;
	}

	// ---------------------------------------------------------------- the political picture

	public static AtlasPayloads.Data build(ServerPlayerEntity p) {
		FactionManager fm = FactionManager.get(p.getServer());
		long now = p.getServerWorld().getTime();
		String mine = fm.playerFaction(p.getUuid());
		Faction my = fm.get(mine);
		List<AtlasPayloads.FactionInfo> factions = new ArrayList<>();
		List<AtlasPayloads.TownInfo> towns = new ArrayList<>();
		for (Faction f : fm.all()) {
			Integer rgb = f.color.getColorValue();
			int laws = 0;
			for (Law l : f.laws) laws |= 1 << l.ordinal();
			int treasury = 0;
			for (Settlement s : f.settlements.values()) treasury += s.treasury;
			int relation = my == null ? fm.standing(p, f.id) : my == f ? 100 : fm.relation(my.id, f.id);
			factions.add(new AtlasPayloads.FactionInfo(f.id, f.name, rgb == null ? 0xFFFFFF : rgb, f.realmTitle(), f.government.label,
					f.rulerName, f.rulerPlayer != null, relation, f.atWar, f.approval, f.armyOpinion, f.courtOpinion, f.warGoal, laws,
					f.slavery, treasury, fm.memberCount(f.id), f.overlord, f == my));
			for (Settlement s : f.settlements.values()) {
				if (s.cityCenter == null) continue;
				int[] b = s.bounds(now);
				towns.add(new AtlasPayloads.TownInfo(s.id, f.id, s.name, s.rank.label, s.cityCenter.getX(), s.cityCenter.getZ(),
						b[0], b[1], b[2], b[3], s.population(), s.loyalty, s.treasury, s.countJob("GUARD"), s.occupation > 0,
						s.isCapital(), s.taxRate, s.garrison, s.autonomy, s.priority));
			}
		}
		boolean ruler = my != null && p.getUuid().equals(my.rulerPlayer);
		int coup = my == null ? -1 : Statecraft.coupChance(fm, my, p);
		boolean stand = my != null && !ruler && my.government == Faction.Government.REPUBLIC;
		boolean heir = my != null && p.getUuid().equals(my.heirPlayer);
		int sup = my == null ? 0 : Statecraft.support(fm, my, Statecraft.Order.WAR, null);
		return new AtlasPayloads.Data(mine == null ? "" : mine, ruler, my != null, coup, stand, heir, sup, factions, towns);
	}
}
