package net.colonists.ai.task;

import net.colonists.faction.Settlement;
import net.colonists.ai.Digger;
import net.colonists.entity.ColonistBrain;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.City;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Digs the city quarry: a square pit off to the side of town, taken down one layer at a time, with a
 * one-block staircase along its north edge so miners can walk in and out, and a fence around the rim.
 * Bigger settlements dig deeper.
 */
public class QuarryTask extends Task {
	private final Digger digger = new Digger();
	private BlockPos target;
	private boolean rimJob;
	private int dug;

	public QuarryTask(ColonistEntity colonist) {
		super(colonist);
	}

	@Override
	public String describe() {
		return "Working the quarry";
	}

	@Override
	public ColonistEntity.ToolKind tool() {
		return ColonistEntity.ToolKind.PICKAXE;
	}

	public static int sizeFor(Faction.Rank rank) {
		return switch (rank) {
			case VILLAGE -> 7;
			case TOWN -> 9;
			case CITY -> 11;
			case CITY_STATE -> 13;
		};
	}

	/** How deep (below the rim) the quarry is allowed to go: more ambitious as the settlement grows. */
	public static int depthFor(Settlement f) {
		int d = switch (f.rank) {
			case VILLAGE -> 4;
			case TOWN -> 7;
			case CITY -> 10;
			case CITY_STATE -> 13;
		};
		return Math.min(d, f.quarrySize - 2);
	}

	/** The stair cells along the north row keep their blocks below a given depth. */
	private static boolean isStair(Settlement f, int x, int z, int depth) {
		return z == f.quarry.getZ() && depth >= x - f.quarry.getX();
	}

	@Nullable
	private static BlockPos nextCell(ColonistEntity c, Settlement f) {
		World world = c.getWorld();
		BlockPos q = f.quarry;
		int size = f.quarrySize;
		int depth = depthFor(f);
		for (int k = 0; k < depth; k++) {
			int y = q.getY() - 1 - k;
			BlockPos best = null;
			double bestD = Double.MAX_VALUE;
			for (int x = q.getX(); x < q.getX() + size; x++) {
				for (int z = q.getZ(); z < q.getZ() + size; z++) {
					if (isStair(f, x, z, k)) continue;
					BlockPos p = new BlockPos(x, y, z);
					if (!world.isChunkLoaded(x >> 4, z >> 4) || c.isBlacklisted(p)) continue;
					BlockState s = world.getBlockState(p);
					if (s.isAir() || !City.isNatural(s) && s.getFluidState().isEmpty()) continue;
					if (s.getFluidState().isEmpty() && !Digger.canHarvest(c, s) && s.isToolRequired()) continue;
					double d = c.squaredDistanceTo(p.toCenterPos());
					if (d < bestD) {
						bestD = d;
						best = p;
					}
				}
			}
			if (best != null) return best;
		}
		// Above the rim: hillocks inside the quarry square are levelled too.
		for (int y = q.getY() + 3; y >= q.getY(); y--) {
			for (int x = q.getX(); x < q.getX() + size; x++) {
				for (int z = q.getZ(); z < q.getZ() + size; z++) {
					BlockPos p = new BlockPos(x, y, z);
					BlockState s = world.getBlockState(p);
					if (!s.isAir() && City.isNatural(s) && !s.isReplaceable() && !c.isBlacklisted(p)) return p;
				}
			}
		}
		return null;
	}

	/** Fence posts around the rim, leaving a gap at the top of the stairs; torches on the corners. */
	@Nullable
	private static BlockPos nextRim(ColonistEntity c, Settlement f) {
		if (ColonistBrain.count(c.getInventory(), ColonistBrain.PLANKS) == 0) return null;
		World world = c.getWorld();
		BlockPos q = f.quarry;
		int s = f.quarrySize;
		for (int x = q.getX() - 1; x <= q.getX() + s; x++) {
			for (int z = q.getZ() - 1; z <= q.getZ() + s; z++) {
				boolean edge = x == q.getX() - 1 || x == q.getX() + s || z == q.getZ() - 1 || z == q.getZ() + s;
				if (!edge || (z == q.getZ() - 1 && (x == q.getX() || x == q.getX() + 1))) continue; // the way in
				if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
				BlockPos p = new BlockPos(x, City.surfaceY(world, x, z), z);
				if (p.getY() > q.getY() + 2 || p.getY() < q.getY() - 2) continue;
				BlockState below = world.getBlockState(p.down());
				if (below.isIn(net.minecraft.registry.tag.BlockTags.FENCES) || !below.isSolidBlock(world, p.down())) continue;
				if (world.getBlockState(p).isReplaceable() && !c.isBlacklisted(p)) return p;
			}
		}
		return null;
	}

	public static boolean hasWork(ColonistEntity c, Settlement f) {
		return f.quarry != null && !c.onSearchCooldown("QUARRY") && nextCell(c, f) != null;
	}

	/** Picks a quarry site off to the side of town: outside every lot and road, on dry, fairly even ground. */
	@Nullable
	public static BlockPos planSite(ColonistEntity c, Settlement f) {
		if (f.cityCenter == null) return null;
		ServerWorld world = (ServerWorld) c.getWorld();
		FactionManager fm = c.factions();
		int size = sizeFor(f.rank);
		int minX = f.cityCenter.getX() - 12, maxX = f.cityCenter.getX() + 12, minZ = f.cityCenter.getZ() - 12, maxZ = f.cityCenter.getZ() + 12;
		for (int[] r : net.colonists.faction.CityGrowth.footprints(f)) {
			minX = Math.min(minX, r[0]);
			maxX = Math.max(maxX, r[2]);
			minZ = Math.min(minZ, r[1]);
			maxZ = Math.max(maxZ, r[3]);
		}
		for (BlockPos r : f.roads) {
			minX = Math.min(minX, r.getX());
			maxX = Math.max(maxX, r.getX());
			minZ = Math.min(minZ, r.getZ());
			maxZ = Math.max(maxZ, r.getZ());
		}
		if (f.wallPlanned) {
			minX = Math.min(minX, f.wallMinX);
			maxX = Math.max(maxX, f.wallMaxX);
			minZ = Math.min(minZ, f.wallMinZ);
			maxZ = Math.max(maxZ, f.wallMaxZ);
		}
		for (int gap = 10; gap <= 34; gap += 8) {
			int[][] spots = {
					{maxX + gap, (minZ + maxZ) / 2 - size / 2}, {minX - gap - size, (minZ + maxZ) / 2 - size / 2},
					{(minX + maxX) / 2 - size / 2, maxZ + gap}, {(minX + maxX) / 2 - size / 2, minZ - gap - size},
					{maxX + gap, maxZ + gap}, {minX - gap - size, minZ - gap - size},
					{maxX + gap, minZ - gap - size}, {minX - gap - size, maxZ + gap}};
			for (int[] spot : spots) {
				int x0 = spot[0], z0 = spot[1];
				Integer floor = City.floorFor(world, x0, z0, x0 + size - 1, z0 + size - 1, 4);
				if (floor == null) continue;
				boolean clash = false;
				for (int x = x0 - 2; x <= x0 + size + 1 && !clash; x += 3) {
					for (int z = z0 - 2; z <= z0 + size + 1 && !clash; z += 3) {
						if (fm != null && fm.isNearAnyHome(new BlockPos(x, floor, z))) clash = true;
					}
				}
				if (clash) continue;
				int top = floor;
				for (int x = x0; x < x0 + size; x++) for (int z = z0; z < z0 + size; z++) top = Math.max(top, City.surfaceY(world, x, z));
				return new BlockPos(x0, top, z0);
			}
		}
		return null;
	}

	@Override
	public void start() {
		FactionManager fm = this.colonist.factions();
		Settlement f = this.colonist.settlement();
		if (f == null) {
			this.done = true;
			return;
		}
		if (f.quarry == null) {
			BlockPos site = planSite(this.colonist, f);
			if (site == null) {
				this.colonist.searchCooldown("QUARRY", 6000);
				this.done = true;
				return;
			}
			f.quarry = site;
			f.quarrySize = sizeFor(f.rank);
			fm.markDirty();
			FactionManager.broadcastNear((ServerWorld) this.colonist.getWorld(), site, 128, Text.literal("⛏ ").formatted(Formatting.GRAY)
					.append(f.displayName()).append(Text.literal(" has opened a quarry outside town.").formatted(Formatting.GRAY)));
		}
	}

	@Override
	public void tick() {
		if (this.done) return;
		FactionManager fm = this.colonist.factions();
		Settlement f = this.colonist.settlement();
		if (f == null || f.quarry == null) {
			this.done = true;
			return;
		}
		ServerWorld world = (ServerWorld) this.colonist.getWorld();
		if (this.target == null) {
			this.target = nextCell(this.colonist, f);
			this.rimJob = false;
			if (this.target == null) {
				this.target = nextRim(this.colonist, f);
				this.rimJob = true;
			}
			if (this.target == null) {
				this.colonist.searchCooldown("QUARRY", 2400);
				this.done = true;
				return;
			}
			this.resetMovement();
		}
		if (!this.approach(this.target, 4.5, 1.0)) {
			if (this.stuck()) {
				this.colonist.blacklist(this.target, 2400);
				this.digger.reset(this.colonist);
				this.target = null;
			}
			return;
		}
		if (this.rimJob) {
			if (ColonistBrain.take(this.colonist.getInventory(), ColonistBrain.PLANKS, 1)) {
				BlockPos q = f.quarry;
				int s = f.quarrySize;
				boolean corner = (this.target.getX() == q.getX() - 1 || this.target.getX() == q.getX() + s)
						&& (this.target.getZ() == q.getZ() - 1 || this.target.getZ() == q.getZ() + s);
				world.setBlockState(this.target, net.colonists.build.Materials.settle(Blocks.OAK_FENCE.getDefaultState(), world, this.target), Block.NOTIFY_ALL);
				if (corner && ColonistBrain.take(this.colonist.getInventory(), net.minecraft.item.Items.TORCH, 1)) {
					world.setBlockState(this.target.up(), Blocks.TORCH.getDefaultState(), Block.NOTIFY_ALL);
				}
			}
			this.target = null;
			return;
		}
		BlockState s = world.getBlockState(this.target);
		if (!s.getFluidState().isEmpty()) {
			// Plug springs with stone rather than flood the pit.
			ColonistBrain.take(this.colonist.getInventory(), net.colonists.build.Materials.STONE_ITEMS, 1);
			world.setBlockState(this.target, Blocks.COBBLESTONE.getDefaultState(), Block.NOTIFY_ALL);
			this.colonist.blacklist(this.target, 24000);
			this.target = null;
			return;
		}
		if (this.digger.dig(this.colonist, this.target)) {
			this.target = null;
			if (++this.dug >= 24) this.done = true;
		}
	}

	@Override
	public void stop() {
		this.digger.reset(this.colonist);
	}
}
