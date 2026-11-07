package net.colonists.faction;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The street plan every colonist city follows.
 *
 * <pre>
 *   Streets run east-west every 14 blocks (z = cz + 10k), avenues north-south every 33 blocks (x = cx + 33m).
 *   Between two avenues sit three 10-wide lots facing the street to their north:
 *
 *      ===== street (3 wide) ==================================
 *      [house 5x5] . [plot 3x4] .  [house] . [plot] .  [house] ...
 *
 *   The plaza (9x9: well, bell, banner, stockpile chests) sits where avenue 0 meets street 0.
 *   Lots are 10x10. Some outer lots are communal fields (9x9, with a water channel across the middle).
 * </pre>
 */
public final class City {
	private City() {}

	public static final int STREET_SPACING = 14;
	public static final int LOT_DEPTH = 10;
	public static final int AVENUE_SPACING = 33;
	public static final int LOT_WIDTH = 10;
	public static final int MAX_RADIUS = 80;
	public static final int PLAZA_HALF = 4;

	/** A lot. {@code origin} is its north-west corner at y = 0 (the real floor height is found when it is built on). */
	public record Lot(int m, int k, int j, BlockPos origin, boolean farm) {
		public int streetZ(BlockPos center) {
			return center.getZ() + STREET_SPACING * this.k;
		}

		public int avenueX(BlockPos center) {
			return center.getX() + AVENUE_SPACING * this.m;
		}
	}

	private static final Map<Long, List<Lot>> CACHE = new HashMap<>();

	/** All lots of a city, nearest to the plaza first. */
	public static List<Lot> lots(BlockPos center) {
		return CACHE.computeIfAbsent(center.asLong(), key -> {
			List<Lot> lots = new ArrayList<>();
			for (int m = -3; m <= 2; m++) {
				for (int k = -8; k <= 8; k++) {
					for (int j = 0; j < 3; j++) {
						int x = center.getX() + AVENUE_SPACING * m + 2 + LOT_WIDTH * j;
						int z = center.getZ() + STREET_SPACING * k + 2;
						if (overlapsPlaza(center, x, z)) continue;
						double d = Math.hypot(x + 4 - center.getX(), z + 4 - center.getZ());
						if (d > MAX_RADIUS) continue;
						boolean farm = d > 26 && Math.floorMod(m * 7 + k * 3 + j * 5, 4) == 0;
						lots.add(new Lot(m, k, j, new BlockPos(x, 0, z), farm));
					}
				}
			}
			lots.sort(Comparator.comparingDouble(l -> Math.hypot(l.origin.getX() + 4 - center.getX(), l.origin.getZ() + 4 - center.getZ())));
			return lots;
		});
	}

	private static boolean overlapsPlaza(BlockPos c, int x, int z) {
		int px0 = c.getX() - PLAZA_HALF - 1, px1 = c.getX() + PLAZA_HALF + 1;
		int pz0 = c.getZ() - PLAZA_HALF - 1, pz1 = c.getZ() + PLAZA_HALF + 1;
		return x <= px1 && x + LOT_WIDTH - 1 >= px0 && z <= pz1 && z + LOT_DEPTH - 1 >= pz0;
	}

	@Nullable
	public static Lot lotAt(BlockPos center, BlockPos origin) {
		for (Lot l : lots(center)) {
			if (l.origin.getX() == origin.getX() && l.origin.getZ() == origin.getZ()) return l;
		}
		return null;
	}

	public static boolean sameColumn(BlockPos a, BlockPos b) {
		return a.getX() == b.getX() && a.getZ() == b.getZ();
	}

	// ---------------------------------------------------------------- plaza layout

	public static BlockPos wellPos(BlockPos c) {
		return c;
	}

	public static BlockPos bellPos(BlockPos c) {
		return c.add(3, 0, -3);
	}

	public static BlockPos bannerPos(BlockPos c) {
		return c.add(-3, 0, -3);
	}

	public static List<BlockPos> stockpileSlots(BlockPos c) {
		return List.of(c.add(-3, 0, 3), c.add(-1, 0, 3), c.add(1, 0, 3), c.add(3, 0, 3));
	}

	// ---------------------------------------------------------------- roads

	/**
	 * Road columns (x, z packed as BlockPos with y = 0) that connect every claimed lot to the plaza:
	 * the street in front of each lot, the avenue from street 0 to that street, and street 0 out to that avenue.
	 */
	public static Set<BlockPos> roadColumns(Settlement f) {
		Set<BlockPos> roads = new LinkedHashSet<>(f.roads);
		BlockPos c = f.cityCenter;
		if (c != null) roads.removeIf(p -> Math.abs(p.getX() - c.getX()) <= PLAZA_HALF && Math.abs(p.getZ() - c.getZ()) <= PLAZA_HALF);
		return roads;
	}

	private static void addStrip(Set<BlockPos> out, int x0, int x1, int z0, int z1) {
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) out.add(new BlockPos(x, 0, z));
		}
	}

	/** Lamp posts stand in the gap between neighbouring lots, right on the street side. */
	public static BlockPos lampColumn(BlockPos lotOrigin) {
		return new BlockPos(lotOrigin.getX() + 9, 0, lotOrigin.getZ());
	}

	// ---------------------------------------------------------------- walls

	public static boolean isWallBlock(BlockState s) {
		return s.isOf(Blocks.COBBLESTONE) || s.isOf(Blocks.COBBLED_DEEPSLATE) || s.isOf(Blocks.BLACKSTONE)
				|| s.isOf(Blocks.STONE_BRICKS) || s.isOf(Blocks.MOSSY_COBBLESTONE);
	}

	public static final int WALL_HEIGHT = 3;

	/** A wall position is done if it holds wall stone, or any other solid block that is not a tree (a hillside counts). */
	public static boolean wallSatisfied(World world, BlockPos pos, BlockState s) {
		if (isWallBlock(s)) return true;
		return s.isSolidBlock(world, pos) && !s.isIn(BlockTags.LOGS) && !s.isIn(BlockTags.LEAVES);
	}

	/** Fixes the wall line around everything the city has claimed so far (plus the plaza), with room to spare. */
	public static void planWall(Settlement f) {
		BlockPos c = f.cityCenter;
		if (c == null) return;
		int minX = c.getX() - PLAZA_HALF, maxX = c.getX() + PLAZA_HALF, minZ = c.getZ() - PLAZA_HALF, maxZ = c.getZ() + PLAZA_HALF;
		for (int[] r : CityGrowth.footprints(f)) {
			minX = Math.min(minX, r[0] - 2);
			maxX = Math.max(maxX, r[2] + 2);
			minZ = Math.min(minZ, r[1] - 2);
			maxZ = Math.max(maxZ, r[3] + 2);
		}
		f.wallMinX = minX - 4;
		f.wallMaxX = maxX + 4;
		f.wallMinZ = minZ - 4;
		f.wallMaxZ = maxZ + 4;
		f.wallPlanned = true;
	}

	/** Columns on the wall line (x, z with y = 0), leaving gates wherever a road crosses it. */
	public static List<BlockPos> wallColumns(Settlement f) {
		List<BlockPos> cols = new ArrayList<>();
		if (!f.wallPlanned) return cols;
		Set<BlockPos> roads = roadColumns(f);
		for (int x = f.wallMinX; x <= f.wallMaxX; x++) {
			cols.add(new BlockPos(x, 0, f.wallMinZ));
			cols.add(new BlockPos(x, 0, f.wallMaxZ));
		}
		for (int z = f.wallMinZ + 1; z < f.wallMaxZ; z++) {
			cols.add(new BlockPos(f.wallMinX, 0, z));
			cols.add(new BlockPos(f.wallMaxX, 0, z));
		}
		// Gates: a road crossing the wall line, or one block away from it, keeps the column open.
		cols.removeIf(p -> roads.contains(p) || roads.contains(p.north()) || roads.contains(p.south())
				|| roads.contains(p.east()) || roads.contains(p.west()));
		return cols;
	}

	/**
	 * The y of the lowest wall block for a column: just above natural ground, looking through any wall
	 * blocks, logs or leaves already standing there.
	 */
	public static int wallBase(World world, int x, int z) {
		int y = surfaceY(world, x, z) - 1;
		BlockPos.Mutable m = new BlockPos.Mutable(x, y, z);
		int floor = world.getBottomY();
		while (y > floor) {
			BlockState s = world.getBlockState(m.setY(y));
			if (!(isWallBlock(s) || s.isIn(BlockTags.LOGS) || s.isIn(BlockTags.LEAVES) || s.isReplaceable())) break;
			y--;
		}
		return y + 1;
	}

	/** Share (0-100) of the wall's loaded columns that are fully built, or -1 if too little of it is loaded to tell. */
	public static int wallProgress(ServerWorld world, Settlement f) {
		List<BlockPos> cols = wallColumns(f);
		if (cols.isEmpty()) return -1;
		int checked = 0, built = 0;
		for (BlockPos col : cols) {
			if (!world.isChunkLoaded(col.getX() >> 4, col.getZ() >> 4)) continue;
			checked++;
			int base = wallBase(world, col.getX(), col.getZ());
			BlockState ground = world.getBlockState(new BlockPos(col.getX(), base - 1, col.getZ()));
			if (!ground.getFluidState().isEmpty()) {
				built++; // walls stop at water
				continue;
			}
			boolean ok = true;
			for (int i = 0; i < WALL_HEIGHT; i++) {
				BlockPos p = new BlockPos(col.getX(), base + i, col.getZ());
				if (!wallSatisfied(world, p, world.getBlockState(p))) {
					ok = false;
					break;
				}
			}
			if (ok) built++;
		}
		if (checked < cols.size() * 3 / 4) return -1;
		return built * 100 / checked;
	}

	// ---------------------------------------------------------------- terrain

	/** First air block above the ground at this column. */
	/** Like {@link #surfaceY} but never loads a chunk: returns fallback for unloaded columns. */
	public static int surfaceYOr(World world, int x, int z, int fallback) {
		return world.isChunkLoaded(x >> 4, z >> 4) ? surfaceY(world, x, z) : fallback;
	}

	public static int surfaceY(World world, int x, int z) {
		return world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z)).getY();
	}

	public static boolean isNatural(BlockState s) {
		return s.isReplaceable() || s.isIn(BlockTags.DIRT) || s.isIn(BlockTags.SAND) || s.isIn(BlockTags.BASE_STONE_OVERWORLD)
				|| s.isOf(Blocks.GRAVEL) || s.isIn(BlockTags.LOGS) || s.isIn(BlockTags.LEAVES) || s.isOf(Blocks.SNOW_BLOCK)
				|| s.isOf(Blocks.CLAY) || s.isOf(Blocks.ICE) || s.isIn(BlockTags.COAL_ORES) || s.isIn(BlockTags.IRON_ORES)
				|| s.isIn(BlockTags.COPPER_ORES) || s.isOf(Blocks.FARMLAND) || s.isOf(Blocks.DIRT_PATH)
				|| s.isOf(Blocks.PUMPKIN) || s.isOf(Blocks.MELON) || s.isOf(Blocks.SUGAR_CANE) || s.isOf(Blocks.CACTUS);
	}

	public static boolean isPavement(BlockState s) {
		return s.isOf(Blocks.DIRT_PATH) || s.isOf(Blocks.COBBLESTONE) || s.isOf(Blocks.STONE_BRICKS) || s.isOf(Blocks.GRAVEL)
				|| s.isIn(BlockTags.PLANKS) || s.isOf(Blocks.STONE) || s.isOf(Blocks.ANDESITE) || s.isOf(Blocks.SMOOTH_STONE)
				|| s.isIn(BlockTags.SLABS);
	}

	/**
	 * Picks the floor height for a rectangle and checks it is buildable: chunks loaded, no water, slopes of at most
	 * {@code tolerance}, and nothing but natural terrain in the way. Returns the floor y, or null.
	 */
	@Nullable
	public static Integer floorFor(ServerWorld world, int x0, int z0, int x1, int z1, int tolerance) {
		if (!world.isChunkLoaded(x0 >> 4, z0 >> 4) || !world.isChunkLoaded(x1 >> 4, z1 >> 4)
				|| !world.isChunkLoaded(x0 >> 4, z1 >> 4) || !world.isChunkLoaded(x1 >> 4, z0 >> 4)) return null;
		int floor = surfaceY(world, (x0 + x1) / 2, (z0 + z1) / 2);
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				int h = surfaceY(world, x, z);
				if (Math.abs(h - floor) > tolerance) return null;
				BlockState ground = world.getBlockState(new BlockPos(x, h - 1, z));
				if (!ground.getFluidState().isEmpty() || !isNatural(ground)) return null;
				for (int y = Math.min(h, floor) - 1; y <= floor + 4; y++) {
					BlockState s = world.getBlockState(new BlockPos(x, y, z));
					if (!isNatural(s) || (y >= floor && !s.getFluidState().isEmpty())) return null;
				}
			}
		}
		return floor;
	}
}
