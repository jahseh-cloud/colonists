package net.colonists.clan;

import net.colonists.build.Blueprint;
import net.colonists.build.Blueprint.Mat;
import net.colonists.build.Blueprints;
import net.colonists.build.Site;
import net.colonists.faction.City;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * What an illager war camp raises as it grows: longhouses of dark timber, watchtowers, a forge, an armory, a pen for
 * war beasts, a palisade of sharpened logs, and at last the war chief's great hall. Clans build as one: a building goes up
 * whole, in dark oak and cobblestone, when the camp has gathered enough.
 */
public final class ClanBuildings {
	private ClanBuildings() {}

	public static final Blueprint LONGHOUSE = Blueprints.registerWonder(Blueprint.builder("clan_longhouse", "Longhouse", 11, 7)
			.walls(0, 3, Mat.PLANKS, true)
			.gableRoof(4, Mat.STAIRS, Mat.PLANKS, Mat.PLANKS)
			.floor(Mat.PLANKS)
			.door(5)
			.fixed(1, 0, 5, Blocks.RED_BED.getDefaultState())
			.fixed(9, 0, 5, Blocks.CAMPFIRE.getDefaultState())
			.fixed(5, 0, 5, Blocks.BARREL.getDefaultState())
			.build());

	public static final Blueprint WATCHTOWER = Blueprints.registerWonder(watchtower());

	private static Blueprint watchtower() {
		Blueprint.Builder b = Blueprint.builder("clan_watchtower", "Watchtower", 5, 5);
		for (int y = 0; y <= 9; y++) for (int x : new int[]{0, 4}) for (int z : new int[]{0, 4}) b.block(x, y, z, Mat.LOG);
		b.box(0, 10, 0, 4, 10, 4, Mat.PLANKS);
		for (int x = 0; x <= 4; x++) {
			b.fixed(x, 11, 0, Blocks.DARK_OAK_FENCE.getDefaultState());
			b.fixed(x, 11, 4, Blocks.DARK_OAK_FENCE.getDefaultState());
		}
		for (int z = 1; z <= 3; z++) {
			b.fixed(0, 11, z, Blocks.DARK_OAK_FENCE.getDefaultState());
			b.fixed(4, 11, z, Blocks.DARK_OAK_FENCE.getDefaultState());
		}
		for (int y = 1; y <= 9; y++) b.fixed(2, y, 3, Blocks.LADDER.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.NORTH));
		b.fixed(2, 12, 2, Blocks.CAMPFIRE.getDefaultState());
		return b.build();
	}

	public static final Blueprint FORGE = Blueprints.registerWonder(Blueprint.builder("clan_forge", "War forge", 7, 5)
			.walls(0, 2, Mat.STONE, true)
			.opening(2, 4, 0, 1, 0)
			.flatRoof(3, Mat.PLANKS)
			.fixed(1, 0, 3, Blocks.BLAST_FURNACE.getDefaultState())
			.fixed(2, 0, 3, Blocks.ANVIL.getDefaultState())
			.fixed(4, 0, 3, Blocks.SMITHING_TABLE.getDefaultState())
			.fixed(5, 0, 3, Blocks.GRINDSTONE.getDefaultState())
			.fixed(5, 0, 1, Blocks.LAVA_CAULDRON.getDefaultState())
			.build());

	public static final Blueprint ARMORY = Blueprints.registerWonder(Blueprint.builder("clan_armory", "Armory", 7, 5)
			.walls(0, 2, Mat.PLANKS, true)
			.gableRoof(3, Mat.STAIRS, Mat.PLANKS, Mat.PLANKS)
			.floor(Mat.PLANKS)
			.door(3)
			.fixed(1, 0, 3, Blocks.FLETCHING_TABLE.getDefaultState())
			.fixed(2, 0, 3, Blocks.BARREL.getDefaultState())
			.fixed(4, 0, 3, Blocks.BARREL.getDefaultState())
			.fixed(5, 0, 3, Blocks.BARREL.getDefaultState())
			.fixed(5, 0, 1, Blocks.TARGET.getDefaultState())
			.build());

	public static final Blueprint WAR_PEN = Blueprints.registerWonder(warPen());

	private static Blueprint warPen() {
		Blueprint.Builder b = Blueprint.builder("clan_war_pen", "War-beast pen", 9, 9);
		for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) {
			boolean edge = x == 0 || x == 8 || z == 0 || z == 8;
			if (edge && !(z == 0 && x >= 3 && x <= 5)) b.fixed(x, 0, z, Blocks.DARK_OAK_FENCE.getDefaultState());
		}
		b.fixed(1, 0, 7, Blocks.HAY_BLOCK.getDefaultState());
		b.fixed(7, 0, 7, Blocks.HAY_BLOCK.getDefaultState());
		b.fixed(4, 0, 7, Blocks.BONE_BLOCK.getDefaultState());
		return b.build();
	}

	public static final Blueprint GREAT_HALL = Blueprints.registerWonder(greatHall());

	private static Blueprint greatHall() {
		Blueprint.Builder b = Blueprint.builder("clan_great_hall", "War chief's hall", 13, 9)
				.walls(0, 4, Mat.PLANKS, true)
				.gableRoof(5, Mat.STAIRS, Mat.PLANKS, Mat.PLANKS)
				.floor(Mat.PLANKS)
				.opening(5, 7, 0, 2, 0);
		for (int z = 2; z <= 6; z += 2) b.fixed(6, 0, z, Blocks.CAMPFIRE.getDefaultState());
		b.fixed(6, 0, 7, Blocks.DARK_OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.NORTH)); // the high seat
		b.fixed(1, 2, 4, Blocks.SKELETON_SKULL.getDefaultState());
		b.fixed(11, 2, 4, Blocks.SKELETON_SKULL.getDefaultState());
		return b.build();
	}

	/** The order a camp raises its buildings, with where each stands (offset from the camp's centre) and its cost. */
	public static final Blueprint[] ORDER = {LONGHOUSE, WATCHTOWER, FORGE, ARMORY, WAR_PEN, GREAT_HALL};
	static final int[][] OFFSETS = {{-13, -9}, {-2, -17}, {4, -9}, {4, 4}, {-13, 3}, {12, -4}};
	public static final int[] COST = {20, 40, 60, 80, 110, 150};

	// ---------------------------------------------------------------- raising things

	/** Raises building number {@code level} of a camp (and, at level 2, the palisade). Returns false if its ground is not loaded. */
	public static boolean raise(ServerWorld world, BlockPos center, int level) {
		Blueprint bp = ORDER[level];
		int[] off = OFFSETS[level];
		BlockPos o = new BlockPos(center.getX() + off[0], 0, center.getZ() + off[1]);
		if (!world.isChunkLoaded(o.getX() >> 4, o.getZ() >> 4) || !world.isChunkLoaded((o.getX() + bp.width) >> 4, (o.getZ() + bp.depth) >> 4)) return false;
		Integer floor = City.floorFor(world, o.getX(), o.getZ() - 1, o.getX() + bp.width - 1, o.getZ() + bp.depth - 1, 4);
		int y = floor != null ? floor : City.surfaceY(world, o.getX() + bp.width / 2, o.getZ() + bp.depth / 2);
		stamp(world, new Site(o.withY(y), BlockRotation.NONE), bp);
		if (level == 1) palisade(world, center, 24);
		return true;
	}

	/** Places a whole blueprint at once, in dark timber, on a cleared and footed plot. */
	public static void stamp(ServerWorld world, Site site, Blueprint bp) {
		// Clear the plot and give it a footing.
		for (int x = -1; x <= bp.width; x++) {
			for (int z = -1; z <= bp.depth; z++) {
				BlockPos base = site.at(x, 0, z);
				for (int y = 0; y <= bp.height + 3; y++) world.setBlockState(base.up(y), Blocks.AIR.getDefaultState(), 2);
				for (int y = 1; y <= 4; y++) {
					BlockPos below = base.down(y);
					BlockState s = world.getBlockState(below);
					if (s.isAir() || s.isReplaceable() || !s.getFluidState().isEmpty()) world.setBlockState(below, y == 1 ? Blocks.COARSE_DIRT.getDefaultState() : Blocks.COBBLESTONE.getDefaultState(), 2);
					else break;
				}
			}
		}
		for (Blueprint.Entry e : bp.entries) {
			BlockState s = resolve(e);
			if (s == null) continue;
			world.setBlockState(site.at(e.x(), e.y(), e.z()), site.state(s), 2);
		}
	}

	static BlockState resolve(Blueprint.Entry e) {
		return switch (e.mat()) {
			case PLANKS -> Blocks.DARK_OAK_PLANKS.getDefaultState();
			case LOG -> Blocks.DARK_OAK_LOG.getDefaultState();
			case STONE -> Blocks.COBBLESTONE.getDefaultState();
			case GLASS -> Blocks.DARK_OAK_FENCE.getDefaultState();
			case SLAB -> Blocks.DARK_OAK_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM);
			case STAIRS -> Blocks.DARK_OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, e.facing() == null ? Direction.NORTH : e.facing())
					.with(StairsBlock.HALF, e.upsideDown() ? BlockHalf.TOP : BlockHalf.BOTTOM);
			case STONE_STAIRS -> Blocks.COBBLESTONE_STAIRS.getDefaultState().with(StairsBlock.FACING, e.facing() == null ? Direction.NORTH : e.facing())
					.with(StairsBlock.HALF, e.upsideDown() ? BlockHalf.TOP : BlockHalf.BOTTOM);
			case FIXED -> e.fixed() == null ? null : e.fixed().isOf(Blocks.OAK_DOOR) ? Blocks.AIR.getDefaultState() : e.fixed();
		};
	}

	/** A ring of sharpened dark-oak stakes around the camp, three high, with four gaps for gates. */
	public static void palisade(ServerWorld world, BlockPos center, int radius) {
		int steps = (int) (Math.PI * 2 * radius);
		for (int i = 0; i < steps; i++) {
			double a = i * Math.PI * 2 / steps;
			// Gates at the four quarters.
			double q = (a + Math.PI / 4) % (Math.PI / 2);
			if (Math.abs(q - Math.PI / 4) < 0.12) continue;
			int x = center.getX() + (int) Math.round(Math.cos(a) * radius), z = center.getZ() + (int) Math.round(Math.sin(a) * radius);
			if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
			int y = City.surfaceY(world, x, z);
			BlockState below = world.getBlockState(new BlockPos(x, y - 1, z));
			if (!below.getFluidState().isEmpty()) continue;
			for (int h = 0; h < 3; h++) world.setBlockState(new BlockPos(x, y + h, z), Blocks.DARK_OAK_LOG.getDefaultState(), 2);
			world.setBlockState(new BlockPos(x, y + 3, z), Blocks.DARK_OAK_FENCE.getDefaultState(), 2);
		}
	}
}
