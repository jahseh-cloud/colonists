package net.colonists.ai.task;

import net.colonists.ai.BlockSearch;
import net.colonists.ai.Digger;
import net.colonists.entity.ColonistBrain;
import net.colonists.entity.ColonistEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

/** A hungry colonist with nothing to eat picks ripe berries and melons in the wild. */
public class ForageTask extends Task {
	private BlockPos target;
	private int picked;
	private final Digger digger = new Digger();

	public ForageTask(ColonistEntity colonist) {
		super(colonist);
	}

	@Override
	public String describe() {
		return "Foraging for food";
	}

	@Override
	public ColonistEntity.ToolKind tool() {
		return ColonistEntity.ToolKind.NONE;
	}

	private static boolean ripe(BlockState s) {
		return (s.isOf(Blocks.SWEET_BERRY_BUSH) && s.get(SweetBerryBushBlock.AGE) >= 2) || s.isOf(Blocks.MELON);
	}

	public static boolean available(ColonistEntity c) {
		return !c.onSearchCooldown("FORAGE") && BlockSearch.nearest(c, 20, 6, (s, p) -> ripe(s), null) != null;
	}

	@Override
	public void tick() {
		ServerWorld world = (ServerWorld) this.colonist.getWorld();
		if (this.target == null || !ripe(world.getBlockState(this.target))) {
			this.target = BlockSearch.nearest(this.colonist, 20, 6, (s, p) -> ripe(s), null);
			this.resetMovement();
			if (this.target == null || this.picked >= 4) {
				this.colonist.searchCooldown("FORAGE", 1200);
				this.done = true;
				return;
			}
		}
		if (!this.approach(this.target, 3.5, 1.0)) {
			if (this.stuck()) {
				this.colonist.blacklist(this.target, 2400);
				this.target = null;
			}
			return;
		}
		BlockState s = world.getBlockState(this.target);
		if (s.isOf(Blocks.SWEET_BERRY_BUSH)) {
			int age = s.get(SweetBerryBushBlock.AGE);
			ColonistBrain.give(this.colonist, new ItemStack(Items.SWEET_BERRIES, 1 + this.colonist.getRandom().nextInt(age == 3 ? 3 : 2)));
			world.setBlockState(this.target, s.with(SweetBerryBushBlock.AGE, 1), Block.NOTIFY_LISTENERS);
			world.playSound(null, this.target, SoundEvents.BLOCK_SWEET_BERRY_BUSH_PICK_BERRIES, SoundCategory.BLOCKS, 1.0f, 1.0f);
			this.colonist.swingHand(Hand.MAIN_HAND);
			this.target = null;
			this.picked++;
		} else if (this.digger.dig(this.colonist, this.target)) {
			this.target = null;
			this.picked++;
		}
	}
}
