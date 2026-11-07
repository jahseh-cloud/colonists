package net.colonists.ai.task;

import net.colonists.ai.BlockSearch;
import net.colonists.entity.ColonistBrain;
import net.colonists.entity.ColonistEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

/** Riverside and lakeside colonists fish from the shore when food runs short. */
public class FishTask extends Task {
	private BlockPos water;
	private int waiting;
	private int bite;
	private int caught;

	public FishTask(ColonistEntity colonist) {
		super(colonist);
	}

	@Override
	public String describe() {
		return "Fishing";
	}

	@Override
	public ColonistEntity.ToolKind tool() {
		return ColonistEntity.ToolKind.NONE;
	}

	@Override
	public int maxTicks() {
		return 2400;
	}

	private static boolean openWater(BlockState s, BlockPos p, ColonistEntity c) {
		return s.isOf(Blocks.WATER) && c.getWorld().getBlockState(p.up()).isAir();
	}

	public static boolean available(ColonistEntity c) {
		return !c.onSearchCooldown("FISH") && BlockSearch.nearest(c, 16, 5, (s, p) -> openWater(s, p, c), null) != null;
	}

	@Override
	public void start() {
		this.water = BlockSearch.nearest(this.colonist, 16, 5, (s, p) -> openWater(s, p, this.colonist), null);
		if (this.water == null) {
			this.colonist.searchCooldown("FISH", 2400);
			this.done = true;
		}
		this.bite = 160 + this.colonist.getRandom().nextInt(240);
	}

	@Override
	public void tick() {
		if (this.done || this.water == null) return;
		if (!this.approach(this.water, 4.0, 1.0)) {
			if (this.stuck()) {
				this.colonist.blacklist(this.water, 2400);
				this.done = true;
			}
			return;
		}
		ServerWorld world = (ServerWorld) this.colonist.getWorld();
		if (++this.waiting % 40 == 0) {
			world.spawnParticles(ParticleTypes.FISHING, this.water.getX() + 0.5, this.water.getY() + 1.0, this.water.getZ() + 0.5, 2, 0.2, 0.0, 0.2, 0.0);
		}
		if (this.waiting >= this.bite) {
			world.playSound(null, this.water, SoundEvents.ENTITY_FISHING_BOBBER_SPLASH, SoundCategory.NEUTRAL, 0.5f, 1.0f);
			this.colonist.swingHand(Hand.MAIN_HAND);
			ColonistBrain.give(this.colonist, new ItemStack(this.colonist.getRandom().nextInt(4) == 0 ? Items.SALMON : Items.COD));
			this.waiting = 0;
			this.bite = 160 + this.colonist.getRandom().nextInt(240);
			if (++this.caught >= 3) this.done = true;
		}
	}
}
