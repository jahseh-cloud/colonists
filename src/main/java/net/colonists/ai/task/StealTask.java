package net.colonists.ai.task;

import net.colonists.build.Blueprint;
import net.colonists.entity.ColonistBrain;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.Settlement;
import net.colonists.law.Courts;
import net.colonists.law.Law;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/** A poor, hungry, not-so-kind colonist helps themselves from a neighbour's larder. If anyone sees, it's theft. */
public class StealTask extends Task {
	private final BlockPos chest;

	private StealTask(ColonistEntity colonist, BlockPos chest) {
		super(colonist);
		this.chest = chest;
	}

	@Nullable
	public static StealTask find(ColonistEntity c, Settlement f) {
		ServerWorld world = (ServerWorld) c.getWorld();
		BlockPos best = null;
		double bestD = 64 * 64;
		for (Map.Entry<BlockPos, UUID> e : f.homes.entrySet()) {
			UUID owner = e.getValue();
			if (owner == null || owner.equals(c.getUuid()) || owner.equals(c.partner) || !f.built.contains(e.getKey())) continue;
			Blueprint bp = BuildStructureTask.blueprintAt(f, e.getKey());
			if (bp.chest == null) continue;
			BlockPos pos = f.site(e.getKey()).at(bp.chest);
			if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
			if (!(world.getBlockEntity(pos) instanceof Inventory inv) || ColonistBrain.count(inv, ColonistBrain.FOOD) == 0) continue;
			double d = pos.getSquaredDistance(c.getBlockPos());
			if (d < bestD) {
				bestD = d;
				best = pos;
			}
		}
		return best == null ? null : new StealTask(c, best);
	}

	@Override
	public String describe() {
		return "Sneaking around the neighbours' houses";
	}

	@Override
	public int maxTicks() {
		return 1200;
	}

	@Override
	public void tick() {
		if (!this.approach(this.chest, 2.0, 0.9)) return;
		ServerWorld world = (ServerWorld) this.colonist.getWorld();
		if (world.getBlockEntity(this.chest) instanceof Inventory inv) {
			int taken = 0;
			for (int i = 0; i < inv.size() && taken < 4; i++) {
				ItemStack s = inv.getStack(i);
				if (s.isEmpty() || !ColonistBrain.FOOD.test(s)) continue;
				ItemStack part = s.split(Math.min(4 - taken, s.getCount()));
				taken += part.getCount();
				ItemStack rest = this.colonist.getInventory().addStack(part);
				if (!rest.isEmpty()) s.increment(rest.getCount());
			}
			inv.markDirty();
			this.colonist.swingHand(Hand.MAIN_HAND);
			if (taken > 0) Courts.report(world, this.colonist.settlement(), this.colonist, Law.Crime.THEFT, true);
		}
		this.done = true;
	}
}
