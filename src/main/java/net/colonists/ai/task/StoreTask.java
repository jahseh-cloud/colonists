package net.colonists.ai.task;

import net.colonists.entity.ColonistBrain;
import net.colonists.entity.ColonistEntity;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Stores surplus in a chest at home (building the chest if needed), or fetches food from it. */
public class StoreTask extends Task {
	private final boolean fetchFood;

	public StoreTask(ColonistEntity colonist, boolean fetchFood) {
		super(colonist);
		this.fetchFood = fetchFood;
	}

	@Override
	public String describe() {
		return this.fetchFood ? "Getting food from storage" : "Putting things away";
	}

	public static boolean chestHasFood(ColonistEntity c) {
		Inventory chest = chestInventory(c);
		return chest != null && ColonistBrain.count(chest, ColonistBrain.FOOD) > 0;
	}

	private static Inventory chestInventory(ColonistEntity c) {
		if (c.chest == null) return null;
		BlockEntity be = c.getWorld().getBlockEntity(c.chest);
		return be instanceof Inventory inv ? inv : null;
	}

	@Override
	public void tick() {
		ServerWorld world = (ServerWorld) this.colonist.getWorld();
		if (this.colonist.home == null) {
			this.done = true;
			return;
		}
		net.colonists.build.Blueprint bp = BuildStructureTask.blueprintFor(this.colonist, this.colonist.home);
		BlockPos spot = this.colonist.chest != null ? this.colonist.chest : (bp.chest != null ? BuildStructureTask.siteFor(this.colonist, this.colonist.home).at(bp.chest) : null);
		if (spot == null) {
			this.done = true;
			return;
		}
		if (!this.approach(spot, 3.0, 1.0)) {
			if (this.stuck()) this.done = true;
			return;
		}
		Inventory chest = chestInventory(this.colonist);
		if (chest == null) {
			// Build a chest from 8 planks.
			if (this.fetchFood || !world.getBlockState(spot).isReplaceable()
					|| !ColonistBrain.take(this.colonist.getInventory(), ColonistBrain.PLANKS, 8)) {
				this.done = true;
				return;
			}
			world.setBlockState(spot, Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, net.minecraft.util.math.Direction.WEST), Block.NOTIFY_ALL);
			world.playSound(null, spot, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 1.0f, 1.0f);
			this.colonist.chest = spot;
			chest = chestInventory(this.colonist);
			if (chest == null) {
				this.done = true;
				return;
			}
		}
		world.playSound(null, spot, SoundEvents.BLOCK_CHEST_OPEN, SoundCategory.BLOCKS, 0.6f, 1.0f);
		this.colonist.swingHand(Hand.MAIN_HAND);
		SimpleInventory inv = this.colonist.getInventory();
		if (this.fetchFood) {
			for (int i = 0; i < chest.size(); i++) {
				ItemStack s = chest.getStack(i);
				if (!s.isEmpty() && ColonistBrain.FOOD.test(s)) {
					ItemStack taken = s.split(Math.min(8, s.getCount()));
					ColonistBrain.give(this.colonist, taken);
					break;
				}
			}
			chest.markDirty();
		} else {
			net.colonists.faction.Stockpile.depositSurplus(inv, java.util.List.of(chest));
		}
		world.playSound(null, spot, SoundEvents.BLOCK_CHEST_CLOSE, SoundCategory.BLOCKS, 0.6f, 1.0f);
		this.done = true;
	}

}
