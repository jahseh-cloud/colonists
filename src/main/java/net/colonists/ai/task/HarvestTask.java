package net.colonists.ai.task;

import net.colonists.ai.BlockSearch;
import net.colonists.entity.ColonistBrain;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.FactionManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.List;

/** Chops trees, mines stone or ore, or pulls up grass for seeds. */
public class HarvestTask extends Task {
	public enum Kind {
		WOOD("Chopping wood", ColonistEntity.ToolKind.AXE, 20, 10),
		STONE("Mining stone", ColonistEntity.ToolKind.PICKAXE, 14, 8),
		ORE("Prospecting for ore", ColonistEntity.ToolKind.PICKAXE, 16, 12),
		SEEDS("Gathering seeds", ColonistEntity.ToolKind.NONE, 16, 4);

		final String label;
		final ColonistEntity.ToolKind tool;
		final int horizontal, vertical;

		Kind(String label, ColonistEntity.ToolKind tool, int horizontal, int vertical) {
			this.label = label;
			this.tool = tool;
			this.horizontal = horizontal;
			this.vertical = vertical;
		}
	}

	private final Kind kind;
	private final int wanted;
	private int collected;
	private BlockPos target;
	private float progress;
	private int searchDelay;

	public HarvestTask(ColonistEntity colonist, Kind kind, int wanted) {
		super(colonist);
		this.kind = kind;
		this.wanted = wanted;
	}

	@Override
	public String describe() {
		return this.kind.label;
	}

	@Override
	public ColonistEntity.ToolKind tool() {
		return this.kind.tool;
	}

	private ItemStack toolFor() {
		return switch (this.kind) {
			case WOOD -> ColonistBrain.best(this.colonist.getInventory(), ColonistBrain.AXES);
			case STONE, ORE -> ColonistBrain.best(this.colonist.getInventory(), ColonistBrain.PICKAXES);
			case SEEDS -> ItemStack.EMPTY;
		};
	}

	private boolean matches(BlockState state, BlockPos pos) {
		return switch (this.kind) {
			case WOOD -> state.isIn(BlockTags.LOGS);
			case STONE -> state.isIn(BlockTags.BASE_STONE_OVERWORLD);
			case ORE -> state.isIn(BlockTags.COAL_ORES) || state.isIn(BlockTags.IRON_ORES) || state.isIn(BlockTags.GOLD_ORES)
					|| state.isIn(BlockTags.DIAMOND_ORES) || state.isIn(BlockTags.EMERALD_ORES);
			case SEEDS -> state.isOf(Blocks.SHORT_GRASS) || state.isOf(Blocks.TALL_GRASS);
		};
	}

	/** The slower checks: is it really a tree, can we reach and harvest it, is it someone's house? */
	private boolean worthIt(BlockState state, BlockPos pos) {
		ServerWorld world = (ServerWorld) this.colonist.getWorld();
		FactionManager fm = this.colonist.factions();
		if (fm != null && fm.isNearAnyHome(pos) && this.kind != Kind.SEEDS) return false;
		if (state.isToolRequired() && !this.toolFor().isSuitableFor(state)) return false;
		return switch (this.kind) {
			case WOOD -> isNaturalTree(world, pos);
			case STONE, ORE -> BlockSearch.exposed(world, pos) && pos.getY() <= this.colonist.getBlockY() + 3;
			case SEEDS -> true;
		};
	}

	private static boolean isNaturalTree(ServerWorld world, BlockPos log) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int dy = 0; dy <= 8; dy++) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					BlockState s = world.getBlockState(m.set(log.getX() + dx, log.getY() + dy, log.getZ() + dz));
					if (s.isIn(BlockTags.LEAVES) && s.contains(LeavesBlock.PERSISTENT) && !s.get(LeavesBlock.PERSISTENT)) return true;
				}
			}
		}
		return false;
	}

	@Override
	public void tick() {
		ServerWorld world = (ServerWorld) this.colonist.getWorld();
		if (this.target != null && !this.matches(world.getBlockState(this.target), this.target)) {
			world.setBlockBreakingInfo(this.colonist.getId(), this.target, -1);
			this.target = null;
		}
		if (this.target == null) {
			if (this.searchDelay-- > 0) return;
			this.searchDelay = 10;
			this.target = BlockSearch.nearest(this.colonist, this.kind.horizontal, this.kind.vertical, this::matches, this::worthIt);
			this.progress = 0;
			this.resetMovement();
			if (this.target == null) {
				this.colonist.searchCooldown(this.kind.name(), 1200);
				this.done = true;
			}
			return;
		}

		if (!this.approach(this.target, 4.5, 1.0)) {
			if (this.stuck()) {
				this.colonist.blacklist(this.target, 2400);
				world.setBlockBreakingInfo(this.colonist.getId(), this.target, -1);
				this.target = null;
			}
			return;
		}

		BlockState state = world.getBlockState(this.target);
		ItemStack tool = this.toolFor();
		float hardness = state.getHardness(world, this.target);
		if (hardness < 0) {
			this.colonist.blacklist(this.target, 24000);
			this.target = null;
			return;
		}
		boolean correctTool = !state.isToolRequired() || tool.isSuitableFor(state);
		float speed = Math.max(1.0f, tool.getMiningSpeedMultiplier(state));
		this.progress += hardness == 0 ? 1.0f : speed / hardness / (correctTool ? 30.0f : 100.0f);

		if (this.ticks % 5 == 0) this.colonist.swingHand(Hand.MAIN_HAND);
		if (this.ticks % 4 == 0) {
			world.playSound(null, this.target, state.getSoundGroup().getHitSound(), SoundCategory.BLOCKS, 0.4f, 0.8f);
		}
		world.setBlockBreakingInfo(this.colonist.getId(), this.target, MathHelper.clamp((int) (this.progress * 10.0f) - 1, 0, 9));

		if (this.progress >= 1.0f) {
			BlockEntity blockEntity = world.getBlockEntity(this.target);
			List<ItemStack> drops = Block.getDroppedStacks(state, world, this.target, blockEntity, this.colonist, tool);
			world.setBlockBreakingInfo(this.colonist.getId(), this.target, -1);
			world.breakBlock(this.target, false, this.colonist);
			for (ItemStack drop : drops) ColonistBrain.give(this.colonist, drop);
			if (this.kind == Kind.WOOD) this.fellRestOfTree(world, this.target);
			// Woodcutters replant: a sapling goes where the trunk stood.
			if (this.kind == Kind.WOOD && world.getBlockState(this.target.down()).isIn(BlockTags.DIRT) && world.getBlockState(this.target).isAir()) {
				ItemStack sapling = ColonistBrain.takeOne(this.colonist.getInventory(), s -> s.isIn(net.minecraft.registry.tag.ItemTags.SAPLINGS));
				if (!sapling.isEmpty()) {
					Block block = Block.getBlockFromItem(sapling.getItem());
					if (block != Blocks.AIR) world.setBlockState(this.target, block.getDefaultState(), Block.NOTIFY_ALL);
				}
			}
			this.colonist.swingHand(Hand.MAIN_HAND);
			this.collected++;
			this.target = null;
			this.progress = 0;
			this.searchDelay = 0;
			if (this.collected >= this.wanted) this.done = true;
		}
	}

	/** Timber! Once the trunk is cut, every log connected to it comes down too; the leaves decay on their own. */
	private void fellRestOfTree(ServerWorld world, BlockPos base) {
		java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
		java.util.Set<BlockPos> seen = new java.util.HashSet<>();
		queue.add(base);
		seen.add(base);
		int felled = 0;
		while (!queue.isEmpty() && felled < 96) {
			BlockPos p = queue.poll();
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = 0; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						BlockPos n = p.add(dx, dy, dz);
						if (!seen.add(n) || Math.abs(n.getX() - base.getX()) > 8 || Math.abs(n.getZ() - base.getZ()) > 8) continue;
						if (!world.getBlockState(n).isIn(BlockTags.LOGS)) continue;
						FactionManager fm = this.colonist.factions();
						if (fm != null && fm.isNearAnyHome(n)) continue; // never someone's log pillar
						net.colonists.ai.Digger.breakNow(this.colonist, n);
						felled++;
						queue.add(n);
					}
				}
			}
		}
		if (felled > 3) world.playSound(null, base, net.minecraft.sound.SoundEvents.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 1.0f, 0.6f);
		this.collected += felled / 4; // a whole tree counts for more than one log
	}

	@Override
	public void stop() {
		if (this.target != null) {
			this.colonist.getWorld().setBlockBreakingInfo(this.colonist.getId(), this.target, -1);
		}
	}
}
