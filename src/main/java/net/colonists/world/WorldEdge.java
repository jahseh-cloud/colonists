package net.colonists.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.colonists.ColonistsMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

/**
 * The edge of the world. The overworld (and the End) end at ±half the world size, the Nether at an eighth of that;
 * beyond is open void. Near the edge the land crumbles away into floating fragments.
 * Configured in config/colonists.json ("worldSize": 3072; 0 = endless).
 */
public final class WorldEdge {
	private WorldEdge() {}

	/** Half the overworld's width (0 = no edge). */
	public static int radius = 1536;
	/** How far in from the edge the land starts to break up. */
	static final int BAND = 72;

	public static void loadConfig() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("colonists.json");
		try {
			if (!Files.exists(file)) {
				Files.writeString(file, "{\n  \"worldSize\": 3072\n}\n");
			}
			JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			int size = o.has("worldSize") ? o.get("worldSize").getAsInt() : 3072;
			radius = size <= 0 ? 0 : Math.max(256, size / 2);
		} catch (Exception e) {
			ColonistsMod.LOGGER.warn("Colonists: could not read config/colonists.json, using a 3072-block world", e);
			radius = 1536;
		}
		ColonistsMod.LOGGER.info("Colonists: the world ends {} blocks from the centre", radius == 0 ? "never" : radius);
	}

	/** The edge for a dimension's generator settings ("minecraft:nether" → an eighth). */
	public static int radiusFor(String settingsId) {
		if (radius <= 0) return 0;
		return settingsId.contains("nether") ? Math.max(32, radius / 8) : radius;
	}

	/** True if (x, z) in the overworld is at least `margin` blocks inside the edge. */
	public static boolean playable(int x, int z, int margin) {
		return radius <= 0 || Math.max(Math.abs(x), Math.abs(z)) <= radius - BAND - margin;
	}

	/** Pulls a coordinate back from the crumbling edge (for walking colonists). */
	public static int clamp(int v) {
		if (radius <= 0) return v;
		int lim = radius - BAND - 8;
		return Math.max(-lim, Math.min(lim, v));
	}

	public static boolean beyond(int x, int z) {
		return radius > 0 && Math.max(Math.abs(x), Math.abs(z)) > radius;
	}

	// ---------------------------------------------------------------- generation

	/** Called right after a chunk's terrain is shaped: clears what lies past the edge and crumbles the band before it. */
	public static void carve(Chunk chunk, int r) {
		if (r <= 0) return;
		ChunkPos cp = chunk.getPos();
		int x0 = cp.getStartX(), z0 = cp.getStartZ();
		if (Math.max(Math.max(Math.abs(x0), Math.abs(x0 + 15)), Math.max(Math.abs(z0), Math.abs(z0 + 15))) < r - BAND) return;
		ChunkSection[] sections = chunk.getSectionArray();
		int bottom = chunk.getBottomY();
		BlockState air = Blocks.AIR.getDefaultState();
		boolean changed = false;
		for (int lx = 0; lx < 16; lx++) {
			for (int lz = 0; lz < 16; lz++) {
				int x = x0 + lx, z = z0 + lz;
				int d = Math.max(Math.abs(x), Math.abs(z));
				if (d <= r - BAND) continue;
				double t = (d - (r - BAND)) / (double) BAND; // 0 at the start of the band, 1 at the edge
				double n = noise(x, z, 11.0) * 0.65 + noise(x + 917, z - 311, 4.0) * 0.35;
				int keepFrom; // lowest y kept in this column (Integer.MAX_VALUE = nothing)
				if (t < 1 && n > 0.08 + t * 0.95) {
					continue; // solid ground, for now
				} else if (t < 1.45 && n > t * 0.62 - 0.05) {
					// A floating fragment: only the top crust of the land remains.
					int top = topOf(sections, bottom, lx, lz);
					if (top == Integer.MIN_VALUE) continue;
					int thickness = 2 + (int) (noise(x - 77, z + 51, 3.0) * 10 * (1.4 - Math.min(1.3, t)));
					keepFrom = top - thickness;
				} else {
					keepFrom = Integer.MAX_VALUE;
				}
				for (int i = 0; i < sections.length; i++) {
					ChunkSection s = sections[i];
					if (s == null || s.isEmpty()) continue;
					int sy = bottom + i * 16;
					for (int ly = 0; ly < 16; ly++) {
						if (sy + ly >= keepFrom) break;
						if (!s.getBlockState(lx, ly, lz).isAir()) {
							s.setBlockState(lx, ly, lz, air, false);
							changed = true;
						}
					}
				}
			}
		}
		if (changed) Heightmap.populateHeightmaps(chunk, EnumSet.of(Heightmap.Type.OCEAN_FLOOR_WG, Heightmap.Type.WORLD_SURFACE_WG));
	}

	private static int topOf(ChunkSection[] sections, int bottom, int lx, int lz) {
		for (int i = sections.length - 1; i >= 0; i--) {
			ChunkSection s = sections[i];
			if (s == null || s.isEmpty()) continue;
			for (int ly = 15; ly >= 0; ly--) {
				BlockState st = s.getBlockState(lx, ly, lz);
				if (!st.isAir() && st.getFluidState().isEmpty()) return bottom + i * 16 + ly;
			}
		}
		return Integer.MIN_VALUE;
	}

	/** Smooth value noise in [0, 1). */
	static double noise(int x, int z, double scale) {
		double fx = x / scale, fz = z / scale;
		int ix = MathHelper.floor(fx), iz = MathHelper.floor(fz);
		double tx = fx - ix, tz = fz - iz;
		tx = tx * tx * (3 - 2 * tx);
		tz = tz * tz * (3 - 2 * tz);
		double a = hash(ix, iz), b = hash(ix + 1, iz), c = hash(ix, iz + 1), d = hash(ix + 1, iz + 1);
		return MathHelper.lerp(tz, MathHelper.lerp(tx, a, b), MathHelper.lerp(tx, c, d));
	}

	private static double hash(int x, int z) {
		long h = x * 341873128712L + z * 132897987541L + 0x5DEECE66DL;
		h = (h ^ (h >>> 31)) * 0x9E3779B97F4A7C15L;
		h ^= h >>> 29;
		return (h >>> 11) / (double) (1L << 53);
	}
}
