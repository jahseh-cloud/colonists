package net.colonists.ai.task;

import net.colonists.faction.Settlement;
import net.colonists.entity.ColonistBrain;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.City;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Stockpile;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.function.Predicate;

/** Trips to the city stockpile on the plaza: dropping off surplus, or picking up food or building materials. */
public class StockpileTask extends Task {
	public enum Mode { DEPOSIT, WITHDRAW }

	private final Mode mode;
	private final Predicate<ItemStack> want;
	private final int amount;
	private final String label;

	public StockpileTask(ColonistEntity colonist, Mode mode, Predicate<ItemStack> want, int amount, String label) {
		super(colonist);
		this.mode = mode;
		this.want = want;
		this.amount = amount;
		this.label = label;
	}

	public static StockpileTask deposit(ColonistEntity c) {
		return new StockpileTask(c, Mode.DEPOSIT, s -> false, 0, "Hauling goods to the city stockpile");
	}

	@Override
	public String describe() {
		return this.label;
	}

	@Override
	public void tick() {
		ServerWorld world = (ServerWorld) this.colonist.getWorld();
		FactionManager fm = this.colonist.factions();
		Settlement f = this.colonist.settlement();
		if (f == null || f.cityCenter == null) {
			this.done = true;
			return;
		}
		List<BlockPos> slots = City.stockpileSlots(f.cityCenter);
		BlockPos target = null;
		for (BlockPos slot : slots) {
			if (world.getBlockState(slot).isOf(Blocks.CHEST)) {
				target = slot;
				break;
			}
		}
		if (target == null) {
			if (this.mode == Mode.WITHDRAW) {
				this.done = true;
				return;
			}
			target = slots.get(0);
		}
		if (!this.approach(target, 3.5, 1.0)) {
			if (this.stuck()) this.done = true;
			return;
		}

		// Make sure there is room: build another chest in the next free slot when the existing ones are full.
		List<Inventory> chests = Stockpile.chests(world, f);
		if (this.mode == Mode.DEPOSIT && (chests.isEmpty() || allFull(chests))) {
			for (BlockPos slot : slots) {
				if (world.getBlockState(slot).isReplaceable() && ColonistBrain.take(this.colonist.getInventory(), ColonistBrain.PLANKS, 8)) {
					world.setBlockState(slot, Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.NORTH), Block.NOTIFY_ALL);
					world.playSound(null, slot, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 1.0f, 1.0f);
					break;
				}
			}
			chests = Stockpile.chests(world, f);
		}
		if (chests.isEmpty()) {
			this.done = true;
			return;
		}

		world.playSound(null, target, SoundEvents.BLOCK_CHEST_OPEN, SoundCategory.BLOCKS, 0.6f, 1.0f);
		this.colonist.swingHand(Hand.MAIN_HAND);
		if (this.mode == Mode.DEPOSIT) {
			Stockpile.depositSurplus(this.colonist.getInventory(), chests);
		} else {
			for (ItemStack s : Stockpile.take(chests, this.want, this.amount)) ColonistBrain.give(this.colonist, s);
		}
		f.foodInStock = Stockpile.count(chests, ColonistBrain.FOOD);
		fm.markDirty();
		world.playSound(null, target, SoundEvents.BLOCK_CHEST_CLOSE, SoundCategory.BLOCKS, 0.6f, 1.0f);
		this.done = true;
	}

	private static boolean allFull(List<Inventory> chests) {
		for (Inventory inv : chests) {
			for (int i = 0; i < inv.size(); i++) if (inv.getStack(i).isEmpty()) return false;
		}
		return true;
	}
}
