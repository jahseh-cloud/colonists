package net.colonists.build;

import net.colonists.build.Blueprint.Mat;
import net.colonists.faction.Faction;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Every building colonists know how to build. */
public final class Blueprints {
	private Blueprints() {}

	public static final Map<String, Blueprint> ALL = new LinkedHashMap<>();

	private static final Predicate<ItemStack> PLANKS = s -> s.isIn(ItemTags.PLANKS);
	private static final Predicate<ItemStack> LOGS = s -> s.isIn(ItemTags.LOGS);
	private static final Predicate<ItemStack> STONE = s -> s.isIn(ItemTags.STONE_TOOL_MATERIALS);
	private static final Predicate<ItemStack> TORCH = s -> s.isOf(Items.TORCH);
	private static final Predicate<ItemStack> IRON = s -> s.isOf(Items.IRON_INGOT);
	private static final Predicate<ItemStack> WHEAT = s -> s.isOf(Items.WHEAT);

	private static BlockState facing(net.minecraft.block.Block b, Direction d) {
		return b.getDefaultState().with(Properties.HORIZONTAL_FACING, d);
	}

	private static Blueprint.Builder torch(Blueprint.Builder b, int x, int z) {
		return b.fixed(x, 0, z, Blocks.TORCH.getDefaultState(), TORCH, 1, true);
	}

	private static Blueprint.Builder chestAt(Blueprint.Builder b, int x, int z) {
		return b.fixed(x, 0, z, facing(Blocks.CHEST, Direction.NORTH), PLANKS, 8, true).chest(x, z);
	}

	private static Blueprint.Builder craftingTable(Blueprint.Builder b, int x, int z) {
		return b.fixed(x, 0, z, Blocks.CRAFTING_TABLE.getDefaultState(), PLANKS, 4, true);
	}

	private static Blueprint.Builder furnace(Blueprint.Builder b, int x, int z, Direction d) {
		return b.fixed(x, 0, z, facing(Blocks.FURNACE, d), STONE, 8, true);
	}

	public static Blueprint registerWonder(Blueprint bp) {
		return register(bp);
	}

	private static Blueprint register(Blueprint bp) {
		ALL.put(bp.id, bp);
		return bp;
	}

	// ------------------------------------------------------------------ homes

	/** Village home: a little log-and-plank cabin with a vegetable garden beside it. */
	public static final Blueprint CABIN = register(build(() -> {
		Blueprint.Builder b = Blueprint.builder("cabin", "Cabin", 5, 5)
				.walls(0, 2, Mat.PLANKS, true)
				.window(0, 1, 2).window(4, 1, 2)
				.gableRoof(2, Mat.STAIRS, Mat.PLANKS, Mat.PLANKS)
				.door(2).bed(1, 2).garden();
		chestAt(b, 3, 1);
		torch(b, 3, 3);
		return b;
	}));

	/** Town home: stone footing, taller plank walls, more windows, a kitchen corner. */
	public static final Blueprint COTTAGE = register(build(() -> {
		Blueprint.Builder b = Blueprint.builder("cottage", "Cottage", 7, 5)
				.walls(0, 0, Mat.STONE, false)
				.walls(1, 3, Mat.PLANKS, true)
				.window(0, 2, 2).window(6, 2, 2).window(1, 2, 0).window(5, 2, 0).window(2, 2, 4).window(4, 2, 4)
				.gableRoof(3, Mat.STAIRS, Mat.PLANKS, Mat.PLANKS)
				.floor(Mat.PLANKS)
				.door(3).bed(1, 2);
		chestAt(b, 5, 1);
		craftingTable(b, 5, 3);
		furnace(b, 4, 3, Direction.NORTH);
		torch(b, 2, 1);
		return b;
	}));

	/** City home: stone ground floor, timber upper storey, plank floor. */
	public static final Blueprint STONE_HOUSE = register(build(() -> {
		Blueprint.Builder b = Blueprint.builder("stone_house", "Stone house", 7, 6)
				.walls(0, 1, Mat.STONE, false)
				.walls(2, 4, Mat.PLANKS, true)
				.window(0, 1, 2).window(6, 1, 3).window(2, 1, 0).window(4, 1, 0)
				.window(0, 3, 2).window(0, 3, 3).window(6, 3, 2).window(6, 3, 3).window(2, 3, 0).window(4, 3, 0).window(3, 3, 5)
				.gableRoof(4, Mat.STAIRS, Mat.PLANKS, Mat.PLANKS)
				.floor(Mat.PLANKS)
				.door(3).bed(1, 2).bed(5, 3);
		chestAt(b, 1, 1);
		craftingTable(b, 5, 1);
		furnace(b, 3, 4, Direction.NORTH);
		torch(b, 3, 2);
		return b;
	}));

	/** City-state home for the well-off (and the ruler): wide, two beds, stone stair roof. */
	public static final Blueprint MANOR = register(build(() -> {
		Blueprint.Builder b = Blueprint.builder("manor", "Manor", 9, 7)
				.walls(0, 1, Mat.STONE, false)
				.walls(2, 4, Mat.PLANKS, true)
				.window(2, 1, 0).window(6, 1, 0).window(0, 1, 3).window(8, 1, 3)
				.window(1, 3, 0).window(3, 3, 0).window(5, 3, 0).window(7, 3, 0)
				.window(0, 3, 2).window(0, 3, 4).window(8, 3, 2).window(8, 3, 4).window(3, 3, 6).window(5, 3, 6)
				.gableRoof(4, Mat.STONE_STAIRS, Mat.STONE, Mat.PLANKS)
				.floor(Mat.PLANKS)
				.door(4).bed(1, 3).bed(7, 3);
		chestAt(b, 1, 1);
		chestAt(b, 7, 1);
		craftingTable(b, 6, 5);
		furnace(b, 5, 5, Direction.NORTH);
		torch(b, 2, 5);
		torch(b, 6, 2);
		return b;
	}));

	/** Town home where land is short: narrow, two storeys, built shoulder to shoulder with its neighbours. */
	public static final Blueprint ROW_HOUSE = register(build(() -> {
		Blueprint.Builder b = Blueprint.builder("row_house", "Row house", 5, 6)
				.walls(0, 0, Mat.STONE, false)
				.walls(1, 5, Mat.PLANKS, true)
				.box(1, 3, 1, 3, 3, 4, Mat.PLANKS)
				.window(1, 1, 0).window(3, 1, 0).window(1, 4, 0).window(2, 4, 0).window(3, 4, 0)
				.window(1, 1, 5).window(3, 1, 5).window(2, 4, 5)
				.gableRoof(6, Mat.STAIRS, Mat.PLANKS, Mat.PLANKS)
				.floor(Mat.PLANKS)
				.door(2).bed(1, 2).bed(3, 3);
		chestAt(b, 3, 1);
		torch(b, 1, 4);
		return b;
	}));

	/** City home in the crowded core: a tall, narrow townhouse of three storeys, stone below and timber above. */
	public static final Blueprint TOWNHOUSE = register(build(() -> {
		Blueprint.Builder b = Blueprint.builder("townhouse", "Townhouse", 5, 7)
				.walls(0, 2, Mat.STONE, false)
				.walls(3, 9, Mat.PLANKS, true)
				.box(1, 3, 1, 3, 3, 5, Mat.PLANKS)
				.box(1, 7, 1, 3, 7, 5, Mat.PLANKS)
				.window(1, 1, 0).window(3, 1, 0)
				.window(1, 5, 0).window(2, 5, 0).window(3, 5, 0).window(1, 8, 0).window(3, 8, 0)
				.window(1, 1, 6).window(3, 1, 6).window(1, 5, 6).window(3, 5, 6).window(2, 8, 6)
				.window(0, 5, 3).window(4, 5, 3).window(0, 8, 3).window(4, 8, 3)
				.gableRoof(10, Mat.STAIRS, Mat.PLANKS, Mat.PLANKS)
				.floor(Mat.PLANKS)
				.door(2).bed(1, 3).bed(3, 4);
		chestAt(b, 3, 1);
		craftingTable(b, 1, 5);
		torch(b, 1, 1);
		return b;
	}));

	/** A home suited to the colonist's standing: rank sets the style, wealth buys better, slave owners build estates. */
	public static Blueprint homeFor(Faction.Rank rank, boolean ruler, int wealth, boolean slaver) {
		return homeFor(rank, ruler, wealth, slaver, false, false);
	}

	/**
	 * A home built for need. Where land near the centre is short, people build up instead of out (row houses in towns,
	 * three-storey townhouses in cities); families want room for two beds; the rich and the rulers build big.
	 */
	public static Blueprint homeFor(Faction.Rank rank, boolean ruler, int wealth, boolean slaver, boolean crowded, boolean family) {
		int r = rank.ordinal();
		if (slaver && r >= Faction.Rank.TOWN.ordinal()) return ESTATE;
		if ((ruler || wealth >= 120) && r >= Faction.Rank.CITY.ordinal()) return MANOR;
		if (rank == Faction.Rank.VILLAGE) return family || wealth >= 50 ? COTTAGE : CABIN;
		if (crowded) return r >= Faction.Rank.CITY.ordinal() ? TOWNHOUSE : ROW_HOUSE;
		if (family || wealth >= 50) return STONE_HOUSE;
		return COTTAGE;
	}

	public static Blueprint homeFor(Faction.Rank rank, boolean ruler) {
		if (ruler && rank.ordinal() >= Faction.Rank.CITY.ordinal()) return MANOR;
		return switch (rank) {
			case VILLAGE -> CABIN;
			case TOWN -> COTTAGE;
			case CITY -> STONE_HOUSE;
			case CITY_STATE -> MANOR;
		};
	}

	// ------------------------------------------------------------------ workplaces & public buildings

	public static final Blueprint TOWN_HALL = register(build(() -> {
		Blueprint.Builder b = Blueprint.builder("town_hall", "Town hall", 9, 7)
				.walls(0, 0, Mat.STONE, false)
				.walls(1, 3, Mat.PLANKS, true)
				.window(2, 2, 0).window(6, 2, 0).window(0, 2, 2).window(0, 2, 4).window(8, 2, 2).window(8, 2, 4)
				.window(2, 2, 6).window(4, 2, 6).window(6, 2, 6)
				.gableRoof(3, Mat.STAIRS, Mat.PLANKS, Mat.PLANKS)
				.floor(Mat.PLANKS)
				.door(4)
				.fixed(4, 0, 5, facing(Blocks.LECTERN, Direction.NORTH), PLANKS, 4, true)
				.workstation(4, 0, 5);
		for (int x = 2; x <= 6; x += 2) {
			b.stairs(x, 0, 3, Mat.STAIRS, Direction.SOUTH, false); // benches facing the lectern
		}
		chestAt(b, 1, 5);
		torch(b, 1, 1);
		torch(b, 7, 1);
		return b;
	}));

	/** An open-fronted shed with a log pile, where lumberjacks work. */
	public static final Blueprint LUMBER_CAMP = register(build(() -> {
		Blueprint.Builder b = Blueprint.builder("lumber_camp", "Lumber camp", 7, 5);
		for (int y = 0; y <= 2; y++) {
			b.block(0, y, 0, Mat.LOG).block(6, y, 0, Mat.LOG).block(0, y, 4, Mat.LOG).block(6, y, 4, Mat.LOG);
		}
		b.box(1, 0, 4, 5, 2, 4, Mat.PLANKS)   // back wall
				.box(0, 0, 1, 0, 1, 3, Mat.PLANKS) // side walls, half height
				.box(6, 0, 1, 6, 1, 3, Mat.PLANKS)
				.flatRoof(3, Mat.SLAB)
				.box(1, 0, 3, 3, 1, 3, Mat.LOG)   // the log pile
				.workstation(5, 0, 2);
		chestAt(b, 5, 3);
		craftingTable(b, 4, 3);
		return b;
	}));

	public static final Blueprint BARN = register(build(() -> {
		Blueprint.Builder b = Blueprint.builder("barn", "Barn", 9, 7)
				.walls(0, 3, Mat.PLANKS, true)
				.opening(3, 5, 0, 2, 0) // wide barn doors
				.window(0, 2, 3).window(8, 2, 3)
				.gableRoof(3, Mat.STAIRS, Mat.PLANKS, Mat.PLANKS)
				.fixed(1, 0, 5, Blocks.HAY_BLOCK.getDefaultState(), WHEAT, 3, true)
				.fixed(2, 0, 5, Blocks.HAY_BLOCK.getDefaultState(), WHEAT, 3, true)
				.fixed(1, 1, 5, Blocks.HAY_BLOCK.getDefaultState(), WHEAT, 3, true)
				.fixed(7, 0, 5, Blocks.COMPOSTER.getDefaultState(), PLANKS, 3, true)
				.workstation(7, 0, 5);
		chestAt(b, 6, 5);
		torch(b, 4, 4);
		return b;
	}));

	/** A fenced paddock with a gate onto the street. */
	public static final Blueprint ANIMAL_PEN = register(build(() -> {
		Blueprint.Builder b = Blueprint.builder("animal_pen", "Animal pen", 9, 9);
		for (int x = 0; x < 9; x++) {
			for (int z = 0; z < 9; z++) {
				if (x != 0 && x != 8 && z != 0 && z != 8) continue;
				if (x == 4 && z == 0) {
					b.fixed(x, 0, z, facing(Blocks.OAK_FENCE_GATE, Direction.NORTH), PLANKS, 2, false);
				} else {
					b.fixed(x, 0, z, Blocks.OAK_FENCE.getDefaultState(), PLANKS, 1, false);
				}
			}
		}
		b.fixed(0, 1, 0, Blocks.TORCH.getDefaultState(), TORCH, 1, true)
				.fixed(8, 1, 0, Blocks.TORCH.getDefaultState(), TORCH, 1, true)
				.fixed(4, 0, 7, Blocks.HAY_BLOCK.getDefaultState(), WHEAT, 3, true)
				.workstation(4, 0, 4);
		return b;
	}));

	public static final Blueprint SMITHY = register(build(() -> {
		Blueprint.Builder b = Blueprint.builder("smithy", "Smithy", 7, 5)
				.walls(0, 2, Mat.STONE, false)
				.opening(2, 4, 0, 1, 0) // open forge front
				.window(0, 1, 2).window(6, 1, 2)
				.gableRoof(2, Mat.STONE_STAIRS, Mat.STONE, Mat.STONE)
				.floor(Mat.STONE)
				.fixed(3, 0, 2, facing(Blocks.ANVIL, Direction.EAST), IRON, 5, true)
				.workstation(3, 0, 2);
		furnace(b, 1, 3, Direction.SOUTH);
		furnace(b, 2, 3, Direction.SOUTH);
		chestAt(b, 5, 3);
		torch(b, 5, 1);
		return b;
	}));

	public static final Blueprint BAKERY = register(build(() -> {
		Blueprint.Builder b = Blueprint.builder("bakery", "Bakery", 7, 5)
				.walls(0, 0, Mat.STONE, false)
				.walls(1, 2, Mat.PLANKS, true)
				.window(2, 1, 0).window(4, 1, 0).window(0, 1, 2).window(6, 1, 2)
				.gableRoof(2, Mat.STAIRS, Mat.PLANKS, Mat.PLANKS)
				.door(1)
				.fixed(3, 0, 3, facing(Blocks.SMOKER, Direction.NORTH), LOGS, 4, true)
				.fixed(4, 0, 3, Blocks.BARREL.getDefaultState(), PLANKS, 6, true)
				.workstation(3, 0, 3);
		furnace(b, 2, 3, Direction.NORTH);
		chestAt(b, 5, 3);
		torch(b, 5, 1);
		return b;
	}));

	public static final Blueprint TAVERN = register(build(() -> {
		Blueprint.Builder b = Blueprint.builder("tavern", "Tavern", 9, 7)
				.walls(0, 0, Mat.STONE, false)
				.walls(1, 3, Mat.PLANKS, true)
				.window(2, 2, 0).window(6, 2, 0).window(0, 2, 3).window(8, 2, 3).window(4, 2, 6)
				.gableRoof(3, Mat.STAIRS, Mat.PLANKS, Mat.PLANKS)
				.floor(Mat.PLANKS)
				.door(4)
				.fixed(1, 0, 5, Blocks.BARREL.getDefaultState(), PLANKS, 6, true)
				.fixed(2, 0, 5, Blocks.BARREL.getDefaultState(), PLANKS, 6, true)
				.fixed(3, 0, 5, Blocks.BARREL.getDefaultState(), PLANKS, 6, true)
				// two tables with stools
				.fixed(2, 0, 2, Blocks.OAK_FENCE.getDefaultState(), PLANKS, 1, true)
				.fixed(2, 1, 2, Blocks.OAK_PRESSURE_PLATE.getDefaultState(), PLANKS, 1, true)
				.fixed(6, 0, 2, Blocks.OAK_FENCE.getDefaultState(), PLANKS, 1, true)
				.fixed(6, 1, 2, Blocks.OAK_PRESSURE_PLATE.getDefaultState(), PLANKS, 1, true)
				.stairs(1, 0, 2, Mat.STAIRS, Direction.WEST, false)
				.stairs(5, 0, 2, Mat.STAIRS, Direction.WEST, false)
				.workstation(4, 0, 4)
				.bed(7, 4);
		torch(b, 4, 1);
		torch(b, 4, 5);
		return b;
	}));

	public static final Blueprint BARRACKS = register(build(() -> {
		Blueprint.Builder b = Blueprint.builder("barracks", "Barracks", 9, 7)
				.walls(0, 3, Mat.STONE, false)
				.window(2, 2, 0).window(6, 2, 0).window(0, 2, 3).window(8, 2, 3)
				.gableRoof(3, Mat.STONE_STAIRS, Mat.STONE, Mat.STONE)
				.floor(Mat.PLANKS)
				.door(4)
				.bed(1, 2).bed(3, 3).bed(5, 3).bed(7, 2)
				.workstation(4, 0, 2);
		chestAt(b, 1, 1);
		chestAt(b, 7, 1);
		torch(b, 4, 4);
		return b;
	}));

	/** Open-air market stalls under slab awnings. */
	public static final Blueprint MARKET = register(build(() -> {
		Blueprint.Builder b = Blueprint.builder("market", "Market", 9, 5);
		for (int stall = 0; stall < 3; stall++) {
			int x0 = stall * 3;
			for (int y = 0; y <= 1; y++) {
				b.fixed(x0, y, 0, Blocks.OAK_FENCE.getDefaultState(), PLANKS, 1, false);
				b.fixed(x0 + 2, y, 0, Blocks.OAK_FENCE.getDefaultState(), PLANKS, 1, false);
			}
			b.box(x0, 2, 0, x0 + 2, 2, 1, Mat.SLAB);
			b.fixed(x0 + 1, 0, 1, Blocks.BARREL.getDefaultState(), PLANKS, 6, true);
		}
		b.workstation(4, 0, 2);
		chestAt(b, 4, 3);
		torch(b, 0, 3);
		torch(b, 8, 3);
		return b;
	}));

	// ------------------------------------------------------------------ justice

	/** Stone gaol: a corridor in front, three barred cells along the back. */
	public static final Blueprint PRISON = register(build(() -> {
		Blueprint.Builder b = Blueprint.builder("prison", "Prison", 10, 7)
				.walls(0, 2, Mat.STONE, false)
				.flatRoof(3, Mat.STONE)
				.floor(Mat.STONE)
				.window(0, 1, 2).window(9, 1, 2)
				.door(4);
		for (int x = 1; x <= 8; x++) {
			if (x == 3 || x == 6) {
				b.box(x, 0, 3, x, 2, 5, Mat.STONE);
			} else {
				for (int y = 0; y <= 2; y++) b.fixed(x, y, 3, Blocks.IRON_BARS.getDefaultState(), STONE, 1, false);
			}
		}
		b.cell(1, 5).cell(4, 5).cell(7, 5).workstation(7, 0, 1);
		torch(b, 2, 1);
		torch(b, 7, 2);
		return b;
	}));

	/** A fenced yard by the quarry where convicts break stone, with a lookout post in the corner. */
	public static final Blueprint WORK_CAMP = register(build(() -> {
		Blueprint.Builder b = Blueprint.builder("work_camp", "Work camp", 11, 11);
		for (int x = 0; x < 11; x++) {
			for (int z = 0; z < 11; z++) {
				boolean edge = x == 0 || z == 0 || x == 10 || z == 10;
				if (!edge || (x == 5 && z == 0)) continue;
				if (x <= 1 && z >= 9) continue; // the lookout post's corner
				b.fixed(x, 0, z, Blocks.OAK_FENCE.getDefaultState(), PLANKS, 1, false);
			}
		}
		b.fixed(5, 0, 0, facing(Blocks.OAK_FENCE_GATE, Direction.SOUTH), PLANKS, 2, false);
		b.box(0, 0, 9, 1, 2, 10, Mat.LOG);
		b.box(0, 3, 9, 1, 3, 10, Mat.PLANKS);
		chestAt(b, 8, 9);
		b.workstation(5, 0, 5);
		torch(b, 9, 1);
		return b;
	}));

	/** A plank scaffold on the plaza: posts, a crossbeam and a chain. */
	public static final Blueprint GALLOWS = register(build(() -> {
		Blueprint.Builder b = Blueprint.builder("gallows", "Gallows", 5, 5)
				.box(0, 0, 1, 4, 0, 4, Mat.PLANKS);
		for (int x = 1; x <= 3; x++) b.stairs(x, 0, 0, Mat.STAIRS, Direction.SOUTH, false);
		b.box(1, 1, 3, 1, 3, 3, Mat.LOG).box(3, 1, 3, 3, 3, 3, Mat.LOG).box(1, 4, 3, 3, 4, 3, Mat.PLANKS);
		b.fixed(2, 3, 3, Blocks.CHAIN.getDefaultState(), STONE, 1, true);
		b.workstation(2, 1, 3);
		return b;
	}));

	/** A slave-owner's estate: a long manor house with servants' quarters in the east wing. */
	public static final Blueprint ESTATE = register(build(() -> {
		Blueprint.Builder b = Blueprint.builder("estate", "Estate", 13, 9)
				.walls(0, 1, Mat.STONE, true)
				.walls(2, 4, Mat.PLANKS, true)
				.box(8, 0, 1, 8, 4, 7, Mat.STONE)
				.opening(8, 8, 0, 1, 4)
				.window(2, 1, 0).window(5, 1, 0).window(10, 1, 0).window(0, 1, 4).window(12, 1, 4)
				.window(2, 3, 0).window(4, 3, 0).window(6, 3, 0).window(10, 3, 0).window(0, 3, 3).window(0, 3, 5)
				.window(12, 3, 3).window(12, 3, 5).window(3, 3, 8).window(6, 3, 8).window(10, 3, 8)
				.gableRoof(4, Mat.STONE_STAIRS, Mat.STONE, Mat.PLANKS)
				.floor(Mat.PLANKS)
				.door(4)
				.bed(1, 4).bed(9, 2).bed(11, 2).bed(9, 6).bed(11, 6);
		chestAt(b, 1, 1);
		chestAt(b, 7, 1);
		craftingTable(b, 7, 7);
		furnace(b, 6, 7, Direction.NORTH);
		torch(b, 3, 6);
		torch(b, 10, 4);
		return b;
	}));

	/** Public buildings a settlement wants, in order, by rank (one of each). */
	public static final String[][] PROJECTS = {
			{"town_hall", "lumber_camp", "barn"},                          // village
			{"animal_pen", "smithy", "bakery", "tavern", "prison", "gallows"}, // town
			{"barracks", "market", "work_camp"},                            // city
			{}                                                              // city-state
	};

	public static Blueprint get(String id) {
		return ALL.getOrDefault(id, CABIN);
	}

	private static Blueprint build(java.util.function.Supplier<Blueprint.Builder> s) {
		return s.get().build();
	}
}
