package net.colonists.build;

import net.colonists.build.Blueprint.Mat;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.Direction;

import java.util.function.Predicate;

/**
 * Blueprints for the world's wonders: huge structures (most about 25 blocks across and 20 or more high) raised block by
 * block by a realm's builders over many days. Each is drawn from an archetype (hall, tower, terraces, arena, gatehouse,
 * harbour, statue, shrine) with its own proportions and ornaments.
 */
public final class WonderBlueprints {
	private WonderBlueprints() {}

	private static final Predicate<ItemStack> STONE = s -> s.isIn(ItemTags.STONE_TOOL_MATERIALS);
	private static final Predicate<ItemStack> PLANKS = s -> s.isIn(ItemTags.PLANKS);
	private static final Predicate<ItemStack> GOLD = s -> s.isOf(Items.GOLD_INGOT);
	private static final Predicate<ItemStack> IRON = s -> s.isOf(Items.IRON_INGOT);

	public static final Blueprint CATHEDRAL = Blueprints.registerWonder(hall("wonder_cathedral", "Great Cathedral", 21, 31, 11, 18, Blocks.CHISELED_STONE_BRICKS, true, false));
	public static final Blueprint LIBRARY = Blueprints.registerWonder(library());
	public static final Blueprint PALACE = Blueprints.registerWonder(palace());
	public static final Blueprint FORGE = Blueprints.registerWonder(forge());
	public static final Blueprint MAUSOLEUM = Blueprints.registerWonder(hall("wonder_mausoleum", "Mausoleum", 19, 19, 8, 0, Blocks.POLISHED_DEEPSLATE, false, true));
	public static final Blueprint PANTHEON = Blueprints.registerWonder(pantheon());
	public static final Blueprint BAZAAR = Blueprints.registerWonder(bazaar());
	public static final Blueprint OBSERVATORY = Blueprints.registerWonder(tower("wonder_observatory", "Observatory", 15, 30, true));
	public static final Blueprint COLOSSUS = Blueprints.registerWonder(colossus());
	public static final Blueprint GARDENS = Blueprints.registerWonder(gardens());
	public static final Blueprint ARENA = Blueprints.registerWonder(arena());
	public static final Blueprint GREAT_WALL = Blueprints.registerWonder(gatehouse());
	public static final Blueprint HARBOR = Blueprints.registerWonder(harbor());
	public static final Blueprint SHRINE = Blueprints.registerWonder(shrine());

	public static void init() {
	}

	// ---------------------------------------------------------------- helpers

	private static void fixed(Blueprint.Builder b, int x, int y, int z, Block block) {
		b.fixed(x, y, z, block.getDefaultState(), STONE, 1, false);
	}

	private static void ornament(Blueprint.Builder b, int x, int y, int z, BlockState state, Predicate<ItemStack> cost, int n) {
		b.fixed(x, y, z, state, cost, n, true);
	}

	private static void free(Blueprint.Builder b, int x, int y, int z, BlockState state) {
		b.fixed(x, y, z, state);
	}

	private static void pillar(Blueprint.Builder b, int x, int z, int y0, int y1, Block block) {
		for (int y = y0; y <= y1; y++) fixed(b, x, y, z, block);
	}

	// ---------------------------------------------------------------- archetypes

	/** A great hall: tall stone walls between pillars, long windows, a steep roof and (optionally) a spire. */
	static Blueprint hall(String id, String name, int w, int d, int wallH, int spireH, Block pillarBlock, boolean spire, boolean flat) {
		Blueprint.Builder b = Blueprint.builder(id, name, w, d).walls(0, wallH - 1, Mat.STONE, false).floor(Mat.STONE);
		for (int x = 0; x < w; x += 4) {
			pillar(b, x, 0, 0, wallH, pillarBlock);
			pillar(b, x, d - 1, 0, wallH, pillarBlock);
		}
		for (int z = 0; z < d; z += 4) {
			pillar(b, 0, z, 0, wallH, pillarBlock);
			pillar(b, w - 1, z, 0, wallH, pillarBlock);
		}
		for (int x = 2; x < w - 2; x += 4) {
			for (int y = 2; y < wallH - 2; y++) {
				b.window(x, y, d - 1);
				if (Math.abs(x - w / 2) > 2) b.window(x, y, 0);
			}
		}
		for (int z = 2; z < d - 2; z += 4) for (int y = 2; y < wallH - 2; y++) {
			b.window(0, y, z);
			b.window(w - 1, y, z);
		}
		if (flat) {
			b.flatRoof(wallH, Mat.STONE);
			for (int x = -1; x <= w; x += 2) {
				fixed(b, x, wallH + 1, -1, Blocks.STONE_BRICK_WALL);
				fixed(b, x, wallH + 1, d, Blocks.STONE_BRICK_WALL);
			}
		} else {
			b.gableRoof(wallH, Mat.STONE_STAIRS, Mat.STONE, Mat.STONE);
		}
		b.door(w / 2).opening(w / 2 - 1, w / 2 + 1, 0, 3, 0);
		if (spire) {
			int x0 = w / 2 - 2, z0 = d - 6;
			for (int y = 0; y < wallH + spireH; y++) {
				for (int x = x0; x <= x0 + 4; x++) for (int z = z0; z <= z0 + 4; z++) {
					boolean edge = x == x0 || x == x0 + 4 || z == z0 || z == z0 + 4;
					if (edge && y >= wallH - 1) b.block(x, y, z, (y % 5 == 3 && (x == w / 2 || z == z0 + 2)) ? Mat.GLASS : Mat.STONE);
				}
			}
			for (int i = 0; i < 4; i++) fixed(b, w / 2, wallH + spireH + i, z0 + 2, pillarBlock);
			ornament(b, w / 2, wallH + spireH + 4, z0 + 2, Blocks.GOLD_BLOCK.getDefaultState(), GOLD, 9);
		}
		// The altar (or heart) of the hall.
		for (int x = w / 2 - 2; x <= w / 2 + 2; x++) fixed(b, x, 0, d - 3, Blocks.POLISHED_ANDESITE);
		ornament(b, w / 2, 1, d - 3, Blocks.GOLD_BLOCK.getDefaultState(), GOLD, 9);
		for (int z = 3; z < d - 4; z += 5) {
			free(b, 2, 0, z, Blocks.LANTERN.getDefaultState());
			free(b, w - 3, 0, z, Blocks.LANTERN.getDefaultState());
		}
		b.workstation(w / 2, 0, d / 2);
		return b.build();
	}

	static Blueprint library() {
		Blueprint.Builder b = Blueprint.builder("wonder_library", "Great Library", 23, 21).walls(0, 9, Mat.STONE, false).floor(Mat.PLANKS);
		for (int x = 0; x < 23; x += 4) {
			pillar(b, x, 0, 0, 10, Blocks.QUARTZ_PILLAR);
			pillar(b, x, 20, 0, 10, Blocks.QUARTZ_PILLAR);
		}
		for (int x = 2; x < 21; x += 4) for (int y = 2; y <= 7; y++) {
			b.window(x, y, 20);
			if (Math.abs(x - 11) > 2) b.window(x, y, 0);
		}
		// Shelves to the rafters along the side walls, and a gallery floor.
		for (int z = 2; z < 19; z++) for (int y = 0; y <= 6; y++) {
			if (y == 3) continue;
			ornament(b, 1, y, z, Blocks.BOOKSHELF.getDefaultState(), PLANKS, 6);
			ornament(b, 21, y, z, Blocks.BOOKSHELF.getDefaultState(), PLANKS, 6);
		}
		for (int z = 2; z < 19; z++) {
			b.block(2, 3, z, Mat.SLAB);
			b.block(20, 3, z, Mat.SLAB);
		}
		for (int z = 4; z < 18; z += 4) for (int x = 7; x <= 15; x += 4) ornament(b, x, 0, z, Blocks.LECTERN.getDefaultState(), PLANKS, 4);
		b.gableRoof(10, Mat.STONE_STAIRS, Mat.STONE, Mat.STONE).door(11).opening(10, 12, 0, 3, 0).workstation(11, 0, 10);
		return b.build();
	}

	static Blueprint palace() {
		int w = 27, d = 21, h = 9;
		Blueprint.Builder b = Blueprint.builder("wonder_palace", "Imperial Palace", w, d).walls(0, h - 1, Mat.STONE, false).floor(Mat.PLANKS);
		for (int x = 2; x < w - 2; x += 3) for (int y = 2; y <= 6; y++) {
			if (y == 4) continue;
			b.window(x, y, 0);
			b.window(x, y, d - 1);
		}
		b.flatRoof(h, Mat.STONE);
		// Corner towers with gilded caps.
		int[][] corners = {{0, 0}, {w - 5, 0}, {0, d - 5}, {w - 5, d - 5}};
		for (int[] c : corners) {
			for (int y = 0; y < h + 7; y++) for (int x = c[0]; x < c[0] + 5; x++) for (int z = c[1]; z < c[1] + 5; z++) {
				boolean edge = x == c[0] || x == c[0] + 4 || z == c[1] || z == c[1] + 4;
				if (edge) b.block(x, y, z, Mat.STONE);
			}
			for (int x = c[0]; x < c[0] + 5; x += 2) {
				fixed(b, x, h + 7, c[1], Blocks.STONE_BRICK_WALL);
				fixed(b, x, h + 7, c[1] + 4, Blocks.STONE_BRICK_WALL);
			}
			ornament(b, c[0] + 2, h + 7, c[1] + 2, Blocks.GOLD_BLOCK.getDefaultState(), GOLD, 9);
		}
		// A throne at the back of the great hall, on a red carpet.
		for (int z = 1; z < d - 3; z++) free(b, w / 2, 0, z, Blocks.RED_CARPET.getDefaultState());
		ornament(b, w / 2, 0, d - 3, Blocks.GOLD_BLOCK.getDefaultState(), GOLD, 9);
		b.stairs(w / 2, 1, d - 3, Mat.STONE_STAIRS, Direction.SOUTH, false);
		b.door(w / 2).opening(w / 2 - 1, w / 2 + 1, 0, 4, 0).workstation(w / 2, 0, d / 2);
		return b.build();
	}

	static Blueprint forge() {
		Blueprint.Builder b = Blueprint.builder("wonder_forge", "Great Forge", 23, 17).walls(0, 7, Mat.STONE, false).floor(Mat.STONE);
		for (int x = 2; x < 21; x += 4) for (int y = 2; y <= 5; y++) b.window(x, y, 16);
		b.gableRoof(7, Mat.STONE_STAIRS, Mat.STONE, Mat.STONE);
		// Chimneys and a row of hearths, anvils between them.
		for (int x = 3; x < 20; x += 5) {
			for (int y = 0; y < 19; y++) fixed(b, x, y, 13, Blocks.BRICKS);
			b.fixed(x, 0, 12, Blocks.BLAST_FURNACE.getDefaultState().with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, Direction.NORTH), STONE, 8, true);
			ornament(b, x + 2, 0, 11, Blocks.ANVIL.getDefaultState(), IRON, 31);
		}
		b.door(11).opening(9, 13, 0, 4, 0).workstation(11, 0, 8);
		return b.build();
	}

	static Blueprint pantheon() {
		int w = 23, d = 25;
		Blueprint.Builder b = Blueprint.builder("wonder_pantheon", "Pantheon", w, d);
		// The rotunda: a round drum topped by a stepped dome with an open eye.
		int cx = 11, cz = 14, r = 10;
		for (int x = 0; x < w; x++) for (int z = 4; z < d; z++) {
			double dist = Math.hypot(x - cx, z - cz);
			if (dist <= r + 0.5) b.block(x, -1, z, Mat.STONE);
			if (dist <= r + 0.5 && dist > r - 0.7) for (int y = 0; y < 9; y++) b.block(x, y, z, Mat.STONE);
			for (int layer = 0; layer < 7; layer++) {
				double lr = r - layer * 1.5;
				if (lr < 1.5) break;
				if (dist <= lr + 0.5 && dist > lr - 1.2) b.block(x, 9 + layer, z, Mat.STONE);
			}
		}
		// A portico of eight columns in front.
		for (int x = 3; x <= 19; x += 2) pillar(b, x, 1, 0, 8, Blocks.QUARTZ_PILLAR);
		for (int x = 2; x <= 20; x++) for (int z = 0; z <= 4; z++) b.block(x, 9, z, Mat.STONE);
		for (int x = cx - 1; x <= cx + 1; x++) for (int y = 0; y < 4; y++) b.air(x, y, cz - r);
		b.workstation(cx, 0, cz);
		return b.build();
	}

	static Blueprint bazaar() {
		int w = 25, d = 19;
		Blueprint.Builder b = Blueprint.builder("wonder_bazaar", "Grand Bazaar", w, d).floor(Mat.STONE);
		for (int x = 0; x < w; x += 4) for (int z = 0; z < d; z += 4) pillar(b, x, z, 0, 5, Blocks.CUT_SANDSTONE);
		b.box(-1, 6, -1, w, 6, d, Mat.SLAB);
		for (int x = 2; x < w - 2; x += 4) for (int z = 2; z < d - 2; z += 4) {
			ornament(b, x, 0, z, Blocks.BARREL.getDefaultState(), PLANKS, 6);
			free(b, x, 1, z, Blocks.WHITE_CARPET.getDefaultState());
		}
		for (int x = 1; x < w; x += 6) free(b, x, 5, d / 2, Blocks.LANTERN.getDefaultState().with(net.minecraft.state.property.Properties.HANGING, true));
		b.workstation(w / 2, 0, d / 2);
		return b.build();
	}

	/** A tall tower on a broad base; optionally crowned with a glass dome. */
	static Blueprint tower(String id, String name, int base, int height, boolean dome) {
		Blueprint.Builder b = Blueprint.builder(id, name, base, base);
		b.box(0, -1, 0, base - 1, 0, base - 1, Mat.STONE);
		int c = base / 2;
		for (int y = 1; y < height; y++) for (int x = c - 3; x <= c + 3; x++) for (int z = c - 3; z <= c + 3; z++) {
			boolean edge = x == c - 3 || x == c + 3 || z == c - 3 || z == c + 3;
			if (edge) b.block(x, y, z, (y % 4 == 2 && (x == c || z == c)) ? Mat.GLASS : Mat.STONE);
		}
		for (int x = c - 1; x <= c + 1; x++) for (int y = 1; y <= 3; y++) b.air(x, y, c - 3);
		for (int x = c - 4; x <= c + 4; x++) for (int z = c - 4; z <= c + 4; z++) b.block(x, height, z, Mat.STONE);
		if (dome) {
			for (int x = c - 4; x <= c + 4; x++) for (int z = c - 4; z <= c + 4; z++) for (int y = 1; y <= 4; y++) {
				double dist = Math.sqrt(Math.pow(x - c, 2) + Math.pow(z - c, 2) + Math.pow(y * 1.1, 2));
				if (dist <= 4.6 && dist > 3.4) b.block(x, height + y, z, Mat.GLASS);
			}
			ornament(b, c, height + 1, c, Blocks.LANTERN.getDefaultState(), IRON, 1);
		} else {
			free(b, c, height + 1, c, Blocks.GLOWSTONE.getDefaultState());
		}
		b.workstation(c, 1, c);
		return b.build();
	}

	/** A great statue on a pedestal, arm raised with a flame. */
	static Blueprint colossus() {
		int w = 15;
		Blueprint.Builder b = Blueprint.builder("wonder_colossus", "Colossus", w, w);
		b.box(0, 0, 0, w - 1, 1, w - 1, Mat.STONE);
		int c = w / 2;
		for (int y = 2; y <= 11; y++) {
			b.box(c - 3, y, c - 1, c - 2, y, c, Mat.STONE); // legs
			b.box(c + 2, y, c - 1, c + 3, y, c, Mat.STONE);
		}
		b.box(c - 3, 12, c - 2, c + 3, 21, c + 1, Mat.STONE); // body
		b.box(c - 5, 13, c - 1, c - 4, 20, c, Mat.STONE);     // left arm, hanging
		b.box(c + 4, 20, c - 1, c + 5, 30, c, Mat.STONE);     // right arm, raised
		b.box(c - 2, 22, c - 2, c + 2, 26, c + 2, Mat.STONE); // head
		fixed(b, c - 1, 24, c - 3, Blocks.POLISHED_BLACKSTONE);
		fixed(b, c + 1, 24, c - 3, Blocks.POLISHED_BLACKSTONE);
		ornament(b, c + 4, 31, c - 1, Blocks.GOLD_BLOCK.getDefaultState(), GOLD, 9);
		free(b, c + 4, 32, c - 1, Blocks.GLOWSTONE.getDefaultState());
		b.workstation(c, 2, c - 4);
		return b.build();
	}

	/** Hanging gardens: stepped terraces overflowing with leaves and flowers. */
	static Blueprint gardens() {
		int w = 25;
		Blueprint.Builder b = Blueprint.builder("wonder_gardens", "Hanging Gardens", w, w);
		for (int level = 0; level < 5; level++) {
			int lo = level * 2, hi = w - 1 - level * 2, y0 = level * 4;
			for (int x = lo; x <= hi; x++) for (int z = lo; z <= hi; z++) {
				boolean rim = x == lo || x == hi || z == lo || z == hi;
				if (rim) for (int y = y0; y < y0 + 4; y++) b.block(x, y, z, Mat.STONE);
				if (x > lo && x < hi && z > lo && z < hi && (x <= lo + 2 || x >= hi - 2 || z <= lo + 2 || z >= hi - 2 || level == 4)) {
					b.block(x, y0 + 3, z, Mat.STONE);
					free(b, x, y0 + 4, z, Blocks.GRASS_BLOCK.getDefaultState());
					if ((x + z) % 3 == 0) free(b, x, y0 + 5, z, Blocks.OAK_LEAVES.getDefaultState().with(net.minecraft.state.property.Properties.PERSISTENT, true));
					else if ((x * 7 + z) % 5 == 0) free(b, x, y0 + 5, z, Blocks.POPPY.getDefaultState());
				}
				// Vines spill down the outer faces.
				if (rim && (x + z) % 4 == 0 && level < 4) free(b, x, y0 + 3, z, Blocks.MOSSY_STONE_BRICKS.getDefaultState());
			}
		}
		free(b, w / 2, 21, w / 2, Blocks.WATER.getDefaultState());
		b.workstation(w / 2, 0, 1);
		return b.build();
	}

	static Blueprint arena() {
		int w = 27;
		Blueprint.Builder b = Blueprint.builder("wonder_arena", "Arena", w, w);
		double c = (w - 1) / 2.0, r = c;
		for (int x = 0; x < w; x++) for (int z = 0; z < w; z++) {
			double dist = Math.hypot(x - c, z - c);
			if (dist > r + 0.3) continue;
			if (dist > r - 1.2) {
				boolean arch = ((int) Math.round(Math.toDegrees(Math.atan2(z - c, x - c)) + 360) % 30) < 6;
				for (int y = 0; y < 9; y++) {
					if (arch && y >= 1 && y <= 3) b.air(x, y, z);
					else b.block(x, y, z, Mat.STONE);
				}
			} else if (dist > r - 6) {
				int tier = (int) ((r - 1.2 - dist) / 1.2);
				int h = 7 - tier;
				for (int y = 0; y < Math.max(1, h); y++) b.block(x, y, z, Mat.STONE);
			} else {
				free(b, x, -1, z, Blocks.SAND.getDefaultState());
			}
		}
		b.workstation((int) c, 0, (int) c);
		return b.build();
	}

	/** The Great Wall's gatehouse: a massive curtain wall between two towers, battlements and a great gate. */
	static Blueprint gatehouse() {
		int w = 29, d = 9;
		Blueprint.Builder b = Blueprint.builder("wonder_wall", "Great Wall", w, d);
		for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
			boolean tower = x < 9 || x >= w - 9;
			int h = tower ? 16 : 11;
			boolean shell = tower ? (x == 0 || x == 8 || x == w - 9 || x == w - 1 || z == 0 || z == d - 1) : (z <= 1 || z >= d - 2);
			for (int y = 0; y < h; y++) if (shell || y == h - 1 || y == 0) b.block(x, y, z, Mat.STONE);
			if ((x + z) % 2 == 0 && (z == 0 || z == d - 1 || x == 0 || x == w - 1)) fixed(b, x, h, z, Blocks.STONE_BRICKS);
		}
		for (int x = w / 2 - 2; x <= w / 2 + 2; x++) for (int z = 0; z < d; z++) for (int y = 0; y <= 5; y++) b.air(x, y, z);
		for (int x = w / 2 - 2; x <= w / 2 + 2; x++) free(b, x, 5, 0, Blocks.IRON_BARS.getDefaultState());
		b.workstation(w / 2, 0, d / 2);
		return b.build();
	}

	static Blueprint harbor() {
		int w = 25, d = 17;
		Blueprint.Builder b = Blueprint.builder("wonder_harbor", "Grand Harbor", w, d);
		b.box(0, -1, 0, w - 1, -1, d - 1, Mat.STONE).box(0, 0, 0, w - 1, 0, d - 1, Mat.PLANKS);
		for (int x = 0; x < w; x += 3) {
			free(b, x, 1, 0, Blocks.OAK_FENCE.getDefaultState());
			free(b, x, 1, d - 1, Blocks.OAK_FENCE.getDefaultState());
		}
		// A lighthouse at the end of the quay.
		int cx = w - 5, cz = d / 2;
		for (int y = 1; y < 22; y++) for (int x = cx - 2; x <= cx + 2; x++) for (int z = cz - 2; z <= cz + 2; z++) {
			boolean edge = x == cx - 2 || x == cx + 2 || z == cz - 2 || z == cz + 2;
			if (edge) b.block(x, y, z, (y / 3) % 2 == 0 ? Mat.STONE : Mat.PLANKS);
		}
		for (int x = cx - 3; x <= cx + 3; x++) for (int z = cz - 3; z <= cz + 3; z++) b.block(x, 22, z, Mat.STONE);
		free(b, cx, 23, cz, Blocks.SEA_LANTERN.getDefaultState());
		for (int y = 1; y <= 2; y++) b.air(cx - 2, y, cz);
		b.workstation(4, 0, d / 2);
		return b.build();
	}

	static Blueprint shrine() {
		int w = 15;
		Blueprint.Builder b = Blueprint.builder("wonder_shrine", "Shrine of the Prophet", w, w);
		b.box(0, -1, 0, w - 1, -1, w - 1, Mat.STONE).box(1, 0, 1, w - 2, 0, w - 2, Mat.STONE);
		int c = w / 2;
		for (int a = 0; a < 360; a += 30) {
			int x = c + (int) Math.round(Math.cos(Math.toRadians(a)) * 5), z = c + (int) Math.round(Math.sin(Math.toRadians(a)) * 5);
			pillar(b, x, z, 1, 6, Blocks.QUARTZ_PILLAR);
		}
		for (int x = 1; x < w - 1; x++) for (int z = 1; z < w - 1; z++) if (Math.hypot(x - c, z - c) <= 6) b.block(x, 7, z, Mat.SLAB);
		ornament(b, c, 1, c, Blocks.GOLD_BLOCK.getDefaultState(), GOLD, 9);
		free(b, c, 2, c, Blocks.CANDLE.getDefaultState().with(net.minecraft.state.property.Properties.LIT, true));
		b.workstation(c, 1, c - 2);
		return b.build();
	}
}
