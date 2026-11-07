package net.colonists.ai.task;

import net.colonists.ai.Digger;
import net.colonists.entity.ColonistBrain;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.City;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Towns and bigger settlements run a mine: from the bottom corner of the quarry a two-high staircase tunnel
 * heads south and down to ore depth, then a long main gallery with side branches every six blocks.
 * Miners light it with torches, plug any water or lava they hit, and follow ore veins they uncover.
 */
public class MineTask extends Task {
	private final Digger digger = new Digger();
	private BlockPos target;
	private int dug;

	public MineTask(ColonistEntity colonist) {
		super(colonist);
	}

	@Override
	public String describe() {
		return "Working down in the mine";
	}

	@Override
	public ColonistEntity.ToolKind tool() {
		return ColonistEntity.ToolKind.PICKAXE;
	}

	@Override
	public boolean stopsAtNight() {
		return false; // it is always dark down there
	}

	private static int targetY(Faction.Rank rank) {
		return switch (rank) {
			case VILLAGE, TOWN -> 24;
			case CITY -> 12;
			case CITY_STATE -> -16;
		};
	}

	private static int galleryLength(Faction.Rank rank) {
		return switch (rank) {
			case VILLAGE, TOWN -> 24;
			case CITY -> 40;
			case CITY_STATE -> 64;
		};
	}

	/** The tunnel's floor cells, in digging order. Each cell is dug two high (three on the stairs). */
	static List<BlockPos> route(Faction f) {
		List<BlockPos> cells = new ArrayList<>();
		BlockPos q = f.quarry;
		int x = q.getX() + f.quarrySize - 2;
		int z = q.getZ() + f.quarrySize - 1;
		int y = f.mineY;
		// Staircase down, heading south.
		int target = targetY(f.rank);
		while (y > target && cells.size() < 160) {
			z++;
			y--;
			cells.add(new BlockPos(x, y, z));
		}
		// Main gallery with branches.
		int length = galleryLength(f.rank);
		for (int i = 1; i <= length; i++) {
			BlockPos main = new BlockPos(x, y, z + i);
			cells.add(main);
			if (i % 6 == 0) {
				for (int side = -1; side <= 1; side += 2) {
					for (int b = 1; b <= 10; b++) cells.add(main.add(side * b, 0, 0));
				}
			}
		}
		return cells;
	}

	private static boolean diggable(World world, BlockPos p) {
		BlockState s = world.getBlockState(p);
		return !s.isAir() && City.isNatural(s) && s.getHardness(world, p) >= 0 && s.getFluidState().isEmpty();
	}

	public static boolean isOre(BlockState s) {
		return s.isIn(BlockTags.COAL_ORES) || s.isIn(BlockTags.IRON_ORES) || s.isIn(BlockTags.GOLD_ORES) || s.isIn(BlockTags.DIAMOND_ORES)
				|| s.isIn(BlockTags.REDSTONE_ORES) || s.isIn(BlockTags.LAPIS_ORES) || s.isIn(BlockTags.COPPER_ORES) || s.isIn(BlockTags.EMERALD_ORES);
	}

	/** The next block to dig: ore beside the tunnel first, then the tunnel itself. */
	@Nullable
	static BlockPos next(ColonistEntity c, Faction f) {
		World world = c.getWorld();
		List<BlockPos> route = route(f);
		int stairCells = Math.max(0, Math.min(160, f.mineY - targetY(f.rank)));
		int index = 0;
		for (BlockPos cell : route) {
			if (!world.isChunkLoaded(cell.getX() >> 4, cell.getZ() >> 4)) return null;
			boolean stairs = index < stairCells;
			index++;
			int height = stairs ? 3 : 2;
			for (int h = 0; h < height; h++) {
				BlockPos p = cell.up(h);
				if (c.isBlacklisted(p)) continue;
				if (diggable(world, p) && Digger.canHarvest(c, world.getBlockState(p))) return p;
			}
			// Follow ore that the tunnel has exposed.
			for (int h = 0; h < 2; h++) {
				for (Direction d : Direction.values()) {
					BlockPos o = cell.up(h).offset(d);
					BlockState s = world.getBlockState(o);
					if (isOre(s) && Digger.canHarvest(c, s) && !c.isBlacklisted(o)) return o;
				}
			}
		}
		return null;
	}

	public static boolean hasWork(ColonistEntity c, Faction f) {
		return f.quarry != null && f.rank.ordinal() >= Faction.Rank.TOWN.ordinal() && !c.onSearchCooldown("MINE")
				&& ColonistBrain.tier(c.getInventory(), ColonistBrain.PICKAXES) >= ColonistBrain.STONE;
	}

	@Override
	public void start() {
		FactionManager fm = this.colonist.factions();
		Faction f = fm == null ? null : fm.get(this.colonist.getFactionId());
		if (f == null || f.quarry == null) {
			this.done = true;
			return;
		}
		if (f.mineY == Integer.MIN_VALUE) {
			f.mineY = f.quarry.getY() - QuarryTask.depthFor(f);
			fm.markDirty();
			this.colonist.say("Time to dig deeper. We're starting a mine!");
		}
	}

	@Override
	public void tick() {
		if (this.done) return;
		FactionManager fm = this.colonist.factions();
		Faction f = fm == null ? null : fm.get(this.colonist.getFactionId());
		if (f == null || f.quarry == null || f.mineY == Integer.MIN_VALUE) {
			this.done = true;
			return;
		}
		ServerWorld world = (ServerWorld) this.colonist.getWorld();
		if (this.target == null) {
			this.target = next(this.colonist, f);
			if (this.target == null) {
				this.colonist.searchCooldown("MINE", 3600);
				this.done = true;
				return;
			}
			this.resetMovement();
		}
		if (!this.approach(this.target, 4.0, 1.0)) {
			if (this.stuck()) {
				this.colonist.blacklist(this.target, 2400);
				this.digger.reset(this.colonist);
				this.target = null;
			}
			return;
		}
		// Safety first: seal off water and lava around the block before opening it.
		for (Direction d : Direction.values()) {
			BlockPos n = this.target.offset(d);
			if (!world.getBlockState(n).getFluidState().isEmpty()) {
				ColonistBrain.take(this.colonist.getInventory(), net.colonists.build.Materials.STONE_ITEMS, 1);
				world.setBlockState(n, Blocks.COBBLESTONE.getDefaultState(), Block.NOTIFY_ALL);
			}
		}
		if (this.digger.dig(this.colonist, this.target)) {
			BlockPos below = this.target.down();
			BlockState floor = world.getBlockState(below);
			if (floor.isAir() || !floor.getFluidState().isEmpty()) {
				// Bridge holes in the tunnel floor.
				ColonistBrain.take(this.colonist.getInventory(), net.colonists.build.Materials.STONE_ITEMS, 1);
				world.setBlockState(below, Blocks.COBBLESTONE.getDefaultState(), Block.NOTIFY_ALL);
			}
			// A torch every few blocks, on the tunnel floor.
			if ((Math.abs(this.target.getZ()) + Math.abs(this.target.getX())) % 7 == 0 && world.getBlockState(this.target).isAir()
					&& world.getBlockState(below).isSolidBlock(world, below)
					&& ColonistBrain.take(this.colonist.getInventory(), net.minecraft.item.Items.TORCH, 1)) {
				world.setBlockState(this.target, Blocks.TORCH.getDefaultState(), Block.NOTIFY_ALL);
			}
			this.target = null;
			if (++this.dug >= 20) this.done = true;
		}
	}

	@Override
	public void stop() {
		this.digger.reset(this.colonist);
	}
}
