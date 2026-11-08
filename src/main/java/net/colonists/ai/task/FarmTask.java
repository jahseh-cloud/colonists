package net.colonists.ai.task;

import net.colonists.faction.Settlement;
import net.colonists.build.Site;
import net.colonists.entity.ColonistBrain;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.City;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tends a farm: either the small 3x4 plot beside a colonist's house, or a communal 9x9 field lot
 * (eight rows of crops around a water channel).
 */
public class FarmTask extends Task {
	private static final Map<Item, Block> CROPS = Map.of(
			Items.WHEAT_SEEDS, Blocks.WHEAT,
			Items.CARROT, Blocks.CARROTS,
			Items.POTATO, Blocks.POTATOES,
			Items.BEETROOT_SEEDS, Blocks.BEETROOTS);

	enum Action { HARVEST, CLEAR, WATER, TILL, PLANT }

	record Job(BlockPos crop, Action action) {}

	private final Site origin;
	private final boolean field;
	private Job job;
	private int cooldown;

	public FarmTask(ColonistEntity colonist, Site origin, boolean field) {
		super(colonist);
		this.origin = origin;
		this.field = field;
	}

	@Override
	public String describe() {
		return this.field ? "Working the fields" : "Tending the garden";
	}

	@Override
	public ColonistEntity.ToolKind tool() {
		return ColonistEntity.ToolKind.HOE;
	}

	/** Crop positions (farmland is one block below each). */
	static List<BlockPos> cells(Site origin, boolean field) {
		List<BlockPos> cells = new ArrayList<>();
		if (field) {
			for (int x = 0; x <= 8; x++) {
				for (int z = 0; z <= 8; z++) cells.add(origin.at(x, 0, z));
			}
		} else {
			for (int x = 6; x <= 8; x++) {
				for (int z = 1; z <= 4; z++) cells.add(origin.at(x, 0, z));
			}
		}
		return cells;
	}

	private static boolean isChannel(Site origin, BlockPos cell, boolean field) {
		if (!field) return false;
		for (int x = 0; x <= 8; x++) if (origin.at(x, 0, 4).equals(cell)) return true;
		return false;
	}

	private static boolean hasSeeds(ColonistEntity c) {
		return ColonistBrain.count(c.getInventory(), ColonistBrain.SEEDS) > 0;
	}

	@Nullable
	private static Action actionFor(World world, Site origin, BlockPos crop, boolean field, boolean seeds) {
		BlockState above = world.getBlockState(crop);
		BlockState ground = world.getBlockState(crop.down());
		if (isChannel(origin, crop, field)) {
			if (!above.isAir() && City.isNatural(above) && above.getFluidState().isEmpty()) return Action.CLEAR;
			if (ground.getFluidState().isEmpty() && City.isNatural(ground) && !ground.isOf(Blocks.FARMLAND)) return Action.WATER;
			return null;
		}
		if (above.getBlock() instanceof CropBlock cropBlock) {
			return cropBlock.isMature(above) ? Action.HARVEST : null;
		}
		if (!above.isAir()) {
			return City.isNatural(above) && above.getFluidState().isEmpty() && (field || above.isReplaceable()) ? Action.CLEAR : null;
		}
		if (ground.isOf(Blocks.FARMLAND)) return seeds ? Action.PLANT : null;
		if (seeds && (ground.isOf(Blocks.GRASS_BLOCK) || ground.isOf(Blocks.DIRT) || ground.isOf(Blocks.COARSE_DIRT))) return Action.TILL;
		return null;
	}

	public static boolean hasWork(ColonistEntity c, Site origin, boolean field) {
		boolean seeds = hasSeeds(c);
		for (BlockPos cell : cells(origin, field)) {
			if (actionFor(c.getWorld(), origin, cell, field, seeds) != null) return true;
		}
		return false;
	}

	public static int matureCrops(ColonistEntity c, Site origin, boolean field) {
		int n = 0;
		for (BlockPos cell : cells(origin, field)) {
			if (actionFor(c.getWorld(), origin, cell, field, false) == Action.HARVEST) n++;
		}
		return n;
	}

	/** The farm lot a farmer should work on: an existing field with work, or a newly claimed one. */
	@Nullable
	public static Site pickField(ColonistEntity c) {
		FactionManager fm = c.factions();
		Settlement f = c.settlement();
		if (f == null || f.cityCenter == null) return null;
		for (BlockPos farm : f.farms) {
			Site site = f.site(farm);
			if (c.getWorld().isChunkLoaded(farm.getX() >> 4, farm.getZ() >> 4) && hasWork(c, site, true)) return site;
		}
		// Before the manors: a couple of small common fields; once there are manors, they feed the town.
		if (!f.manors.isEmpty() || f.farms.size() >= 2 || f.farms.size() * 4 >= Math.max(4, f.population()) || c.onSearchCooldown("FIELD")) return null;
		c.searchCooldown("FIELD", 1200);
		Site site = net.colonists.faction.CityGrowth.claim((ServerWorld) c.getWorld(), fm, f, null,
				net.colonists.faction.CityGrowth.Purpose.FIELD, c.getRandom());
		if (site != null) c.say("We need more food. I'm starting a new field.");
		return site;
	}


	@Override
	public void tick() {
		if (this.cooldown-- > 0) return;
		ServerWorld world = (ServerWorld) this.colonist.getWorld();
		boolean seeds = hasSeeds(this.colonist);
		if (this.job == null || actionFor(world, this.origin, this.job.crop, this.field, seeds) != this.job.action) {
			this.job = null;
			double best = Double.MAX_VALUE;
			for (BlockPos cell : cells(this.origin, this.field)) {
				Action a = actionFor(world, this.origin, cell, this.field, seeds);
				if (a == null || this.colonist.isBlacklisted(cell)) continue;
				// Water the channel first so new farmland stays moist.
				double d = this.colonist.squaredDistanceTo(cell.toCenterPos()) - (a == Action.WATER ? 1000 : 0);
				if (d < best) {
					best = d;
					this.job = new Job(cell, a);
				}
			}
			if (this.job == null) {
				this.done = true;
				return;
			}
			this.resetMovement();
		}
		if (!this.approach(this.job.crop, 3.5, 0.9)) {
			if (this.stuck()) {
				this.colonist.blacklist(this.job.crop, 1200);
				this.job = null;
			}
			return;
		}
		BlockPos crop = this.job.crop;
		switch (this.job.action) {
			case HARVEST -> {
				BlockState state = world.getBlockState(crop);
				for (ItemStack drop : Block.getDroppedStacks(state, world, crop, null, this.colonist, this.colonist.getMainHandStack())) {
					ColonistBrain.give(this.colonist, drop);
				}
				world.breakBlock(crop, false, this.colonist);
				this.plant(world, crop); // replant straight away
			}
			case CLEAR -> {
				BlockState state = world.getBlockState(crop);
				for (ItemStack drop : Block.getDroppedStacks(state, world, crop, null, this.colonist, ItemStack.EMPTY)) {
					ColonistBrain.give(this.colonist, drop);
				}
				world.breakBlock(crop, false, this.colonist);
			}
			case WATER -> {
				world.setBlockState(crop.down(), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
				world.playSound(null, crop, SoundEvents.ITEM_BUCKET_EMPTY, SoundCategory.BLOCKS, 1.0f, 1.0f);
			}
			case TILL -> {
				world.setBlockState(crop.down(), Blocks.FARMLAND.getDefaultState(), Block.NOTIFY_ALL);
				world.playSound(null, crop, SoundEvents.ITEM_HOE_TILL, SoundCategory.BLOCKS, 1.0f, 1.0f);
			}
			case PLANT -> this.plant(world, crop);
		}
		this.colonist.swingHand(Hand.MAIN_HAND);
		this.job = null;
		this.cooldown = 8;
	}

	private void plant(ServerWorld world, BlockPos crop) {
		if (!world.getBlockState(crop.down()).isOf(Blocks.FARMLAND)) return;
		Item seed = null;
		int most = 0;
		for (Item item : CROPS.keySet()) {
			int n = ColonistBrain.count(this.colonist.getInventory(), item);
			if (n > most) {
				most = n;
				seed = item;
			}
		}
		if (seed == null || !ColonistBrain.take(this.colonist.getInventory(), seed, 1)) return;
		world.setBlockState(crop, CROPS.get(seed).getDefaultState(), Block.NOTIFY_ALL);
		world.playSound(null, crop, SoundEvents.ITEM_CROP_PLANT, SoundCategory.BLOCKS, 1.0f, 1.0f);
	}
}
