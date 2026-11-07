package net.colonists.ai;

import net.colonists.entity.ColonistBrain;
import net.colonists.entity.ColonistEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.List;

/** Breaks one block at a time at a believable speed (tool-dependent), with cracks, sounds and drops into the inventory. */
public final class Digger {
	private BlockPos target;
	private float progress;
	private int ticks;

	/** The tool a colonist would use on this block (their best one of the right kind, or bare hands). */
	public static ItemStack toolFor(ColonistEntity c, BlockState state) {
		if (state.isIn(BlockTags.PICKAXE_MINEABLE)) return ColonistBrain.best(c.getInventory(), ColonistBrain.PICKAXES);
		if (state.isIn(BlockTags.AXE_MINEABLE)) return ColonistBrain.best(c.getInventory(), ColonistBrain.AXES);
		return ItemStack.EMPTY;
	}

	/** Can this colonist break the block and get its drops? */
	public static boolean canHarvest(ColonistEntity c, BlockState state) {
		return !state.isToolRequired() || toolFor(c, state).isSuitableFor(state);
	}

	/**
	 * Works on the block for one tick. Returns true once it is gone (or was already gone).
	 * Unbreakable blocks return true immediately without breaking anything; callers should check first.
	 */
	public boolean dig(ColonistEntity c, BlockPos pos) {
		ServerWorld world = (ServerWorld) c.getWorld();
		BlockState state = world.getBlockState(pos);
		if (state.isAir()) {
			this.reset(c);
			return true;
		}
		if (!pos.equals(this.target)) {
			this.reset(c);
			this.target = pos.toImmutable();
		}
		float hardness = state.getHardness(world, pos);
		if (hardness < 0) {
			this.reset(c);
			return true;
		}
		ItemStack tool = toolFor(c, state);
		boolean correct = !state.isToolRequired() || tool.isSuitableFor(state);
		float speed = Math.max(1.0f, tool.getMiningSpeedMultiplier(state));
		this.progress += hardness == 0 ? 1.0f : speed / hardness / (correct ? 30.0f : 100.0f);
		this.ticks++;
		if (this.ticks % 5 == 0) c.swingHand(Hand.MAIN_HAND);
		if (this.ticks % 4 == 0) world.playSound(null, pos, state.getSoundGroup().getHitSound(), SoundCategory.BLOCKS, 0.4f, 0.8f);
		world.setBlockBreakingInfo(c.getId(), pos, MathHelper.clamp((int) (this.progress * 10.0f) - 1, 0, 9));
		if (this.progress < 1.0f) return false;

		BlockEntity be = world.getBlockEntity(pos);
		List<ItemStack> drops = Block.getDroppedStacks(state, world, pos, be, c, tool);
		world.setBlockBreakingInfo(c.getId(), pos, -1);
		world.breakBlock(pos, false, c);
		for (ItemStack drop : drops) ColonistBrain.give(c, drop);
		c.swingHand(Hand.MAIN_HAND);
		this.target = null;
		this.progress = 0;
		return true;
	}

	/** Breaks a block instantly (used for the rest of a felled tree). */
	public static void breakNow(ColonistEntity c, BlockPos pos) {
		ServerWorld world = (ServerWorld) c.getWorld();
		BlockState state = world.getBlockState(pos);
		if (state.isAir()) return;
		BlockEntity be = world.getBlockEntity(pos);
		for (ItemStack drop : Block.getDroppedStacks(state, world, pos, be, c, toolFor(c, state))) ColonistBrain.give(c, drop);
		world.breakBlock(pos, false, c);
	}

	public void reset(ColonistEntity c) {
		if (this.target != null) c.getWorld().setBlockBreakingInfo(c.getId(), this.target, -1);
		this.target = null;
		this.progress = 0;
	}
}
