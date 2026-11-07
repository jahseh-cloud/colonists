package net.colonists.build;

import net.colonists.entity.ColonistBrain;
import net.colonists.faction.City;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/** Turns blueprint material roles into real blocks, paid for out of a colonist's inventory. */
public final class Materials {
	private Materials() {}

	public static final Predicate<ItemStack> STONE_ITEMS = s -> s.isOf(Items.COBBLESTONE) || s.isOf(Items.COBBLED_DEEPSLATE)
			|| s.isOf(Items.BLACKSTONE) || s.isOf(Items.STONE) || s.isOf(Items.ANDESITE) || s.isOf(Items.DIORITE)
			|| s.isOf(Items.GRANITE) || s.isOf(Items.STONE_BRICKS) || s.isOf(Items.MOSSY_COBBLESTONE) || s.isOf(Items.TUFF);
	public static final Predicate<ItemStack> GLASS_ITEMS = s -> s.isOf(Items.GLASS) || s.isOf(Items.GLASS_PANE);
	public static final Predicate<ItemStack> FILL_ITEMS = s -> s.isOf(Items.DIRT) || s.isOf(Items.GRAVEL) || s.isOf(Items.SAND)
			|| s.isOf(Items.COARSE_DIRT) || STONE_ITEMS.test(s);

	public static boolean isStoneLike(BlockState s) {
		return s.isOf(Blocks.COBBLESTONE) || s.isOf(Blocks.COBBLED_DEEPSLATE) || s.isOf(Blocks.BLACKSTONE) || s.isOf(Blocks.STONE)
				|| s.isOf(Blocks.ANDESITE) || s.isOf(Blocks.DIORITE) || s.isOf(Blocks.GRANITE) || s.isOf(Blocks.STONE_BRICKS)
				|| s.isOf(Blocks.MOSSY_COBBLESTONE) || s.isOf(Blocks.TUFF) || s.isOf(Blocks.DEEPSLATE);
	}

	/** What an entry is paid with (null = free). */
	@Nullable
	public static Predicate<ItemStack> costOf(Blueprint.Entry e) {
		return switch (e.mat()) {
			case PLANKS, STAIRS, SLAB -> s -> ColonistBrain.PLANKS.test(s) || ColonistBrain.LOGS.test(s);
			case LOG -> s -> ColonistBrain.LOGS.test(s) || ColonistBrain.PLANKS.test(s);
			case STONE, STONE_STAIRS -> STONE_ITEMS;
			case GLASS -> s -> GLASS_ITEMS.test(s) || ColonistBrain.PLANKS.test(s);
			case FIXED -> e.cost();
		};
	}

	public static int countOf(Blueprint.Entry e) {
		return e.mat() == Blueprint.Mat.FIXED ? e.costCount() : 1;
	}

	/** Is the block already there good enough? */
	public static boolean satisfied(Blueprint.Entry e, BlockState s, boolean hasGlass) {
		return switch (e.mat()) {
			case PLANKS, LOG -> s.isIn(BlockTags.PLANKS) || s.isIn(BlockTags.LOGS);
			case STONE -> isStoneLike(s);
			case STAIRS, STONE_STAIRS -> s.isIn(BlockTags.STAIRS);
			case SLAB -> s.isIn(BlockTags.SLABS);
			case GLASS -> s.isOf(Blocks.GLASS_PANE) || s.isOf(Blocks.GLASS) || (s.isIn(BlockTags.PLANKS) && !hasGlass);
			case FIXED -> {
				BlockState f = e.fixed();
				if (f == null || f.isAir()) yield s.isAir();
				if (f.getBlock() instanceof DoorBlock) yield s.getBlock() instanceof DoorBlock;
				yield s.isOf(f.getBlock());
			}
		};
	}

	/** Should a builder do something at this spot? (Never touches blocks that are neither natural nor ours to upgrade.) */
	public static boolean needed(Blueprint.Entry e, World world, BlockPos pos, boolean hasGlass) {
		BlockState s = world.getBlockState(pos);
		if (satisfied(e, s, hasGlass)) return false;
		if (e.mat() == Blueprint.Mat.GLASS && s.isIn(BlockTags.PLANKS)) return true; // upgrade a boarded window
		return s.isReplaceable() || City.isNatural(s);
	}

	/**
	 * Takes payment and returns the block to place, or null if the builder cannot pay.
	 * For FIXED entries with a cost, the cost is taken here too.
	 */
	@Nullable
	public static BlockState pay(Blueprint.Entry e, Inventory inv) {
		switch (e.mat()) {
			case FIXED -> {
				Predicate<ItemStack> cost = e.cost();
				if (cost != null && !ColonistBrain.take(inv, cost, e.costCount())) return null;
				return e.fixed();
			}
			case GLASS -> {
				if (ColonistBrain.take(inv, GLASS_ITEMS, 1)) return Blocks.GLASS_PANE.getDefaultState();
				ItemStack p = ColonistBrain.takeOne(inv, ColonistBrain.PLANKS);
				return p.isEmpty() ? null : blockOf(p, Blocks.OAK_PLANKS).getDefaultState();
			}
			case STONE, STONE_STAIRS -> {
				ItemStack s = ColonistBrain.takeOne(inv, STONE_ITEMS);
				if (s.isEmpty()) return null;
				Block base = blockOf(s, Blocks.COBBLESTONE);
				if (e.mat() == Blueprint.Mat.STONE) return base.getDefaultState();
				return shaped(variant(base, "_stairs", Blocks.COBBLESTONE_STAIRS).getDefaultState(), e);
			}
			default -> {
				Predicate<ItemStack> wood = e.mat() == Blueprint.Mat.LOG ? ColonistBrain.LOGS : ColonistBrain.PLANKS;
				ItemStack w = ColonistBrain.takeOne(inv, wood);
				if (w.isEmpty()) w = ColonistBrain.takeOne(inv, e.mat() == Blueprint.Mat.LOG ? ColonistBrain.PLANKS : ColonistBrain.LOGS);
				if (w.isEmpty()) return null;
				Block block = blockOf(w, Blocks.OAK_PLANKS);
				if (e.mat() == Blueprint.Mat.STAIRS || e.mat() == Blueprint.Mat.SLAB) {
					Block planks = block.getDefaultState().isIn(BlockTags.LOGS) ? planksFor(block) : block;
					if (e.mat() == Blueprint.Mat.STAIRS) return shaped(variant(planks, "_stairs", Blocks.OAK_STAIRS).getDefaultState(), e);
					return variant(planks, "_slab", Blocks.OAK_SLAB).getDefaultState();
				}
				if (e.mat() == Blueprint.Mat.PLANKS && block.getDefaultState().isIn(BlockTags.LOGS)) return planksFor(block).getDefaultState();
				return block.getDefaultState();
			}
		}
	}

	private static BlockState shaped(BlockState stairs, Blueprint.Entry e) {
		if (e.facing() != null && stairs.contains(Properties.HORIZONTAL_FACING)) stairs = stairs.with(Properties.HORIZONTAL_FACING, e.facing());
		if (stairs.contains(Properties.BLOCK_HALF)) stairs = stairs.with(Properties.BLOCK_HALF, e.upsideDown() ? BlockHalf.TOP : BlockHalf.BOTTOM);
		return stairs;
	}

	public static Block blockOf(ItemStack stack, Block fallback) {
		Block b = Block.getBlockFromItem(stack.getItem());
		return b == Blocks.AIR ? fallback : b;
	}

	/** oak_planks -> oak_stairs, cobblestone -> cobblestone_stairs, and so on. */
	public static Block variant(Block base, String suffix, Block fallback) {
		Identifier id = Registries.BLOCK.getId(base);
		String path = id.getPath();
		if (path.endsWith("_planks")) path = path.substring(0, path.length() - "_planks".length());
		if (path.endsWith("_bricks")) path = path.substring(0, path.length() - 1); // stone_bricks -> stone_brick_stairs
		Block b = Registries.BLOCK.get(Identifier.of(id.getNamespace(), path + suffix));
		return b == Blocks.AIR ? fallback : b;
	}

	/** oak_log -> oak_planks (best effort). */
	public static Block planksFor(Block log) {
		Identifier id = Registries.BLOCK.getId(log);
		String path = id.getPath().replace("stripped_", "").replace("_log", "").replace("_wood", "").replace("_stem", "").replace("_hyphae", "");
		Block b = Registries.BLOCK.get(Identifier.of(id.getNamespace(), path + "_planks"));
		return b == Blocks.AIR ? Blocks.OAK_PLANKS : b;
	}

	/** Some blocks (fences, panes, stairs) need to look at their neighbours to get their shape right. */
	public static BlockState settle(BlockState state, World world, BlockPos pos) {
		return Block.postProcessState(state, world, pos);
	}
}
