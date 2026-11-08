package net.colonists.ai.task;

import net.colonists.ai.ColonistBrain;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.City;
import net.colonists.farm.Manor;
import net.colonists.farm.Manor.Field;
import net.colonists.farm.Manors;
import net.colonists.farm.Manors.Crop;
import net.colonists.farm.Manors.Role;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * A laborer's day on a manor field: dig the irrigation channels, till, sow, and bring in the harvest (and resow it).
 * The manor's stores provide the seed; the crop goes into the laborer's pack, and from there to the town's stores.
 */
public class ManorFieldTask extends Task {
	enum Action { CLEAR, WATER, TILL, PLANT, HARVEST }

	record Job(BlockPos pos, Action action) {}

	private final Manor manor;
	private final Field field;
	@Nullable private Job job;
	private int cooldown, worked;

	public ManorFieldTask(ColonistEntity colonist, Manor manor, Field field) {
		super(colonist);
		this.manor = manor;
		this.field = field;
	}

	@Override
	public String describe() {
		return "Working the " + Crop.of(this.field.crop).label + " at " + this.manor.name;
	}

	@Override
	public ColonistEntity.ToolKind tool() {
		return ColonistEntity.ToolKind.HOE;
	}

	@Override
	public int maxTicks() {
		return 2400;
	}

	@Override
	public boolean stopsAtNight() {
		return true;
	}

	// ---------------------------------------------------------------- what a spot needs

	@Nullable
	static Action actionAt(World world, Field f, int x, int z) {
		if (!world.isChunkLoaded(x >> 4, z >> 4)) return null;
		Role role = Manors.role(f, x, z);
		Crop crop = Crop.of(f.crop);
		BlockPos pos = new BlockPos(x, Manors.columnY(world, x, z), z);
		BlockState here = world.getBlockState(pos);
		BlockState ground = world.getBlockState(pos.down());
		boolean plant = here.isReplaceable() && !here.isAir() && here.getFluidState().isEmpty();
		switch (role) {
			case CHANNEL -> {
				if (!here.isAir() && here.getFluidState().isEmpty() && City.isNatural(here)) return Action.CLEAR;
				if (ground.getFluidState().isEmpty() && (ground.isIn(BlockTags.DIRT) || ground.isOf(Blocks.FARMLAND) || ground.isIn(BlockTags.SAND))) return Action.WATER;
				return null;
			}
			case EMPTY -> {
				if (crop.fruit() != null && here.isOf(crop.fruit())) return Action.HARVEST;
				return plant ? Action.CLEAR : null;
			}
			default -> {
			}
		}
		switch (crop.layout) {
			case ROWS, GOURD -> {
				if (here.getBlock() instanceof CropBlock cb) return cb.isMature(here) ? Action.HARVEST : null;
				if (here.getBlock() instanceof net.minecraft.block.StemBlock || here.getBlock() instanceof net.minecraft.block.AttachedStemBlock) return null;
				if (plant) return Action.CLEAR;
				if (!here.isAir()) return null;
				if (ground.isOf(Blocks.FARMLAND)) return Action.PLANT;
				if (ground.isOf(Blocks.GRASS_BLOCK) || ground.isOf(Blocks.DIRT) || ground.isOf(Blocks.COARSE_DIRT) || ground.isOf(Blocks.DIRT_PATH)) return Action.TILL;
				return null;
			}
			case CANE -> {
				if (here.isOf(Blocks.SUGAR_CANE)) return world.getBlockState(pos.up()).isOf(Blocks.SUGAR_CANE) ? Action.HARVEST : null;
				if (plant) return Action.CLEAR;
				if (here.isAir() && Blocks.SUGAR_CANE.getDefaultState().canPlaceAt(world, pos)) return Action.PLANT;
				return null;
			}
			case BERRY -> {
				if (here.isOf(Blocks.SWEET_BERRY_BUSH)) return here.get(SweetBerryBushBlock.AGE) >= 3 ? Action.HARVEST : null;
				if (plant) return Action.CLEAR;
				if (here.isAir() && (ground.isIn(BlockTags.DIRT) || ground.isOf(Blocks.FARMLAND))) return Action.PLANT;
				return null;
			}
		}
		return null;
	}

	/** Anything to do in this field? (Sampled: fields are large.) */
	public static boolean hasWork(World world, Field f, net.minecraft.util.math.random.Random random) {
		for (int i = 0; i < 40; i++) {
			int x = f.x0 + random.nextInt(f.width()), z = f.z0 + random.nextInt(f.depth());
			if (actionAt(world, f, x, z) != null) return true;
		}
		return false;
	}

	// ---------------------------------------------------------------- working

	@Override
	public void tick() {
		if (this.cooldown-- > 0) return;
		ServerWorld world = (ServerWorld) this.colonist.getWorld();
		if (this.job == null || actionAt(world, this.field, this.job.pos.getX(), this.job.pos.getZ()) != this.job.action) {
			this.job = this.pick(world);
			if (this.job == null) {
				this.done = true;
				return;
			}
			this.resetMovement();
		}
		if (!this.approach(this.job.pos, 3.5, 0.9)) {
			if (this.stuck()) {
				this.colonist.blacklist(this.job.pos, 1200);
				this.job = null;
			}
			return;
		}
		apply(world, this.manor, this.field, this.job.pos, this.job.action, this.colonist);
		this.colonist.swingHand(Hand.MAIN_HAND);
		this.job = null;
		this.cooldown = 6;
		if (++this.worked >= 160) this.done = true; // a shift's work; time to see to other needs
	}

	/** The nearest spot with work: channels first, then the harvest, then the rest. Searches outward from the laborer. */
	@Nullable
	private Job pick(ServerWorld world) {
		Field f = this.field;
		int cx = Math.max(f.x0, Math.min(f.x1, this.colonist.getBlockX())), cz = Math.max(f.z0, Math.min(f.z1, this.colonist.getBlockZ()));
		int maxR = Math.max(f.width(), f.depth());
		Job best = null;
		int bestRank = Integer.MAX_VALUE;
		for (int r = 0; r <= maxR; r++) {
			// Walk the edge of the ring only.
			int side = Math.max(1, 2 * r);
			for (int i = 0; i < (r == 0 ? 1 : 4 * side); i++) {
				int x, z;
				if (r == 0) {
					x = cx;
					z = cz;
				} else {
					int edge = i / side, k = i % side;
					switch (edge) {
						case 0 -> { x = cx - r + k; z = cz - r; }
						case 1 -> { x = cx + r; z = cz - r + k; }
						case 2 -> { x = cx + r - k; z = cz + r; }
						default -> { x = cx - r; z = cz + r - k; }
					}
				}
				if (!f.contains(x, z, 0)) continue;
				Action a = actionAt(world, f, x, z);
				if (a == null) continue;
				BlockPos pos = new BlockPos(x, Manors.columnY(world, x, z), z);
				if (this.colonist.isBlacklisted(pos)) continue;
				int rank = a == Action.WATER ? 0 : a == Action.HARVEST ? 1 : 2;
				if (rank < bestRank) {
					bestRank = rank;
					best = new Job(pos, a);
					if (rank == 0) return best;
				}
			}
			// The nearest work of the best kind found so far, within a few rings.
			if (best != null && r >= 6) return best;
		}
		return best;
	}

	/** Does whatever a spot needs right now, as a laborer would (or with no one's hands, for the self-test). Returns false if it needs nothing. */
	public static boolean workSpot(ServerWorld world, Manor manor, Field field, int x, int z) {
		Action a = actionAt(world, field, x, z);
		if (a == null) return false;
		apply(world, manor, field, new BlockPos(x, Manors.columnY(world, x, z), z), a, null);
		return true;
	}

	static void apply(ServerWorld world, Manor manor, Field field, BlockPos pos, Action action, @Nullable ColonistEntity colonist) {
		Crop crop = Crop.of(field.crop);
		switch (action) {
			case CLEAR -> {
				for (ItemStack drop : Block.getDroppedStacks(world.getBlockState(pos), world, pos, null, colonist, ItemStack.EMPTY)) give(colonist, drop);
				world.breakBlock(pos, false, colonist);
			}
			case WATER -> {
				world.setBlockState(pos.down(), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
				world.playSound(null, pos, SoundEvents.ITEM_BUCKET_EMPTY, SoundCategory.BLOCKS, 1f, 1f);
			}
			case TILL -> {
				world.setBlockState(pos.down(), Blocks.FARMLAND.getDefaultState(), Block.NOTIFY_ALL);
				world.playSound(null, pos, SoundEvents.ITEM_HOE_TILL, SoundCategory.BLOCKS, 1f, 1f);
			}
			case PLANT -> {
				world.setBlockState(pos, crop.planted(), Block.NOTIFY_ALL);
				world.playSound(null, pos, SoundEvents.ITEM_CROP_PLANT, SoundCategory.BLOCKS, 1f, 1f);
			}
			case HARVEST -> {
				int got = 0;
				if (crop.layout == Manors.Layout.CANE) {
					for (BlockPos p = pos.up(); world.getBlockState(p).isOf(Blocks.SUGAR_CANE); p = p.up()) got++;
					world.breakBlock(pos.up(), false, colonist);
					give(colonist, new ItemStack(Items.SUGAR_CANE, got));
				} else if (crop.layout == Manors.Layout.BERRY) {
					got = 2 + world.random.nextInt(2);
					world.setBlockState(pos, world.getBlockState(pos).with(SweetBerryBushBlock.AGE, 1), Block.NOTIFY_ALL);
					give(colonist, new ItemStack(Items.SWEET_BERRIES, got));
					world.playSound(null, pos, SoundEvents.BLOCK_SWEET_BERRY_BUSH_PICK_BERRIES, SoundCategory.BLOCKS, 1f, 1f);
				} else {
					BlockState state = world.getBlockState(pos);
					for (ItemStack drop : Block.getDroppedStacks(state, world, pos, null, colonist, colonist == null ? ItemStack.EMPTY : colonist.getMainHandStack())) {
						// Seed is the manor's to keep; the produce goes to market.
						if (drop.isOf(Items.WHEAT_SEEDS) || drop.isOf(Items.BEETROOT_SEEDS)) continue;
						got += drop.getCount();
						give(colonist, drop);
					}
					world.breakBlock(pos, false, colonist);
					// Resow straight away (gourds regrow from their stems).
					if (crop.fruit() == null && world.getBlockState(pos.down()).isOf(Blocks.FARMLAND)) world.setBlockState(pos, crop.planted(), Block.NOTIFY_ALL);
				}
				manor.harvested += got;
				manor.harvestedTotal += got;
			}
		}
	}

	static void give(@Nullable ColonistEntity c, ItemStack stack) {
		if (c != null && !stack.isEmpty()) ColonistBrain.give(c, stack);
	}

	static boolean wet(World world, BlockPos p) {
		return world.getFluidState(p).isIn(FluidTags.WATER);
	}
}
