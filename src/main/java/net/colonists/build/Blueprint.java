package net.colonists.build;

import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A building design. Coordinates are relative to the lot origin (north-west corner, y = floor level).
 * Every building faces north: the front door is in the z = 0 wall, opening onto the street.
 *
 * Most blocks are given as material roles (planks, logs, stone, stairs...) so a building comes out in
 * whatever wood and stone its builder actually has.
 */
public final class Blueprint {
	public enum Mat {
		PLANKS, LOG, STONE, STAIRS, SLAB, STONE_STAIRS, GLASS, FIXED
	}

	/**
	 * One block of the design.
	 *
	 * @param facing   for stairs: the side the tall back faces (the direction the slope climbs)
	 * @param cost     what the builder must hand over for a FIXED block (null = free)
	 * @param optional skipped (and retried later) when the builder cannot pay for it
	 */
	public record Entry(int x, int y, int z, Mat mat, @Nullable BlockState fixed, @Nullable Direction facing,
						boolean upsideDown, @Nullable Predicate<ItemStack> cost, int costCount, boolean optional) {
		public BlockPos at(Site site) {
			return site.at(this.x, this.y, this.z);
		}
	}

	public final String id;
	public final String name;
	public final int width, depth, height;
	public final List<Entry> entries;
	/** x of the front door (in the z = 0 wall), or -1 for open structures. */
	public final int doorX;
	public final List<BlockPos> beds; // relative foot positions; beds point south
	@Nullable public final BlockPos chest;
	@Nullable public final BlockPos workstation;
	/** Whether a small vegetable garden goes beside it (only narrow houses leave room for one). */
	public final boolean garden;

	private Blueprint(Builder b) {
		this.id = b.id;
		this.name = b.name;
		this.width = b.width;
		this.depth = b.depth;
		this.height = b.maxY + 1;
		this.entries = List.copyOf(b.entries);
		this.doorX = b.doorX;
		this.beds = List.copyOf(b.beds);
		this.chest = b.chest;
		this.workstation = b.workstation;
		this.garden = b.garden;
	}

	/** Rough wood (planks) a builder should carry to finish this building. */
	public int woodCost() {
		int n = 0;
		for (Entry e : this.entries) {
			if (e.mat() == Mat.PLANKS || e.mat() == Mat.LOG || e.mat() == Mat.STAIRS || e.mat() == Mat.SLAB || e.mat() == Mat.GLASS) n++;
		}
		return n;
	}

	/** Stone blocks the building needs. */
	public int stoneCost() {
		int n = 0;
		for (Entry e : this.entries) if (e.mat() == Mat.STONE || e.mat() == Mat.STONE_STAIRS) n++;
		return n;
	}

	public BlockPos doorstep(Site site) {
		return site.at(this.doorX < 0 ? this.width / 2 : this.doorX, 0, -1);
	}

	public BlockPos inside(Site site) {
		return site.at(this.width / 2, 0, this.depth / 2);
	}

	/** East edge of the lot (the garden sits beside narrow houses). */
	public int lotWidth() {
		return this.garden ? 9 : this.width;
	}

	public static Builder builder(String id, String name, int width, int depth) {
		return new Builder(id, name, width, depth);
	}

	public static final class Builder {
		final String id, name;
		final int width, depth;
		final List<Entry> entries = new ArrayList<>();
		int maxY;
		int doorX = -1;
		final List<BlockPos> beds = new ArrayList<>();
		BlockPos chest, workstation;
		boolean garden;

		Builder(String id, String name, int width, int depth) {
			this.id = id;
			this.name = name;
			this.width = width;
			this.depth = depth;
		}

		private Builder add(Entry e) {
			// Later entries replace earlier ones at the same spot (e.g. a window punched into a wall).
			this.entries.removeIf(o -> o.x == e.x && o.y == e.y && o.z == e.z);
			this.entries.add(e);
			this.maxY = Math.max(this.maxY, e.y);
			return this;
		}

		public Builder block(int x, int y, int z, Mat mat) {
			return add(new Entry(x, y, z, mat, null, null, false, null, 0, false));
		}

		public Builder stairs(int x, int y, int z, Mat mat, Direction facing, boolean upsideDown) {
			return add(new Entry(x, y, z, mat, null, facing, upsideDown, null, 0, false));
		}

		public Builder fixed(int x, int y, int z, BlockState state) {
			return add(new Entry(x, y, z, Mat.FIXED, state, null, false, null, 0, false));
		}

		public Builder fixed(int x, int y, int z, BlockState state, Predicate<ItemStack> cost, int count, boolean optional) {
			return add(new Entry(x, y, z, Mat.FIXED, state, null, false, cost, count, optional));
		}

		/** Fills a box with one material. */
		public Builder box(int x0, int y0, int z0, int x1, int y1, int z1, Mat mat) {
			for (int y = y0; y <= y1; y++)
				for (int x = x0; x <= x1; x++)
					for (int z = z0; z <= z1; z++) block(x, y, z, mat);
			return this;
		}

		/** The four outer walls from y0 to y1, with log pillars at the corners. */
		public Builder walls(int y0, int y1, Mat mat, boolean logCorners) {
			for (int y = y0; y <= y1; y++) {
				for (int x = 0; x < this.width; x++) {
					for (int z = 0; z < this.depth; z++) {
						boolean edgeX = x == 0 || x == this.width - 1, edgeZ = z == 0 || z == this.depth - 1;
						if (!edgeX && !edgeZ) continue;
						block(x, y, z, logCorners && edgeX && edgeZ ? Mat.LOG : mat);
					}
				}
			}
			return this;
		}

		/** A floor (replacing the ground) across the inside of the building. */
		public Builder floor(Mat mat) {
			return box(1, -1, 1, this.width - 2, -1, this.depth - 2, mat);
		}

		/**
		 * A gable roof whose ridge runs east-west: stairs climbing from the north and south walls,
		 * an overhang of one block on every side, and the triangular gable ends filled in.
		 */
		public Builder gableRoof(int baseY, Mat stairs, Mat ridge, Mat gable) {
			int rows = (this.depth + 1) / 2 + 1;
			for (int i = 0; i < rows; i++) {
				int y = baseY + i;
				int north = -1 + i, south = this.depth - i;
				if (north > south) break;
				for (int x = -1; x <= this.width; x++) {
					if (north == south) {
						block(x, y, north, ridge);
					} else {
						stairs(x, y, north, stairs, Direction.SOUTH, false);
						stairs(x, y, south, stairs, Direction.NORTH, false);
					}
				}
				// Gable ends: wall in the triangle under the slope.
				if (i > 0) {
					for (int z = north + (north == south ? 0 : 1); z <= south - (north == south ? 0 : 1); z++) {
						if (north == south) continue;
						block(0, y, z, gable);
						block(this.width - 1, y, z, gable);
					}
				}
			}
			return this;
		}

		/** A flat roof (one layer) with an overhang. */
		public Builder flatRoof(int y, Mat mat) {
			return box(-1, y, -1, this.width, y, this.depth, mat);
		}

		public Builder door(int x) {
			this.doorX = x;
			// Placed specially (two halves); the FIXED state here is just the marker.
			return fixed(x, 0, 0, net.minecraft.block.Blocks.OAK_DOOR.getDefaultState(),
					s -> s.isIn(net.minecraft.registry.tag.ItemTags.PLANKS), 2, false)
					.air(x, 1, 0);
		}

		/** A spot that must be empty (doorways, the space above a door). */
		public Builder air(int x, int y, int z) {
			return fixed(x, y, z, net.minecraft.block.Blocks.AIR.getDefaultState());
		}

		public Builder opening(int x0, int x1, int y0, int y1, int z) {
			for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) air(x, y, z);
			return this;
		}

		public Builder window(int x, int y, int z) {
			return block(x, y, z, Mat.GLASS);
		}

		public Builder bed(int x, int z) {
			this.beds.add(new BlockPos(x, 0, z));
			return this;
		}

		public Builder chest(int x, int z) {
			this.chest = new BlockPos(x, 0, z);
			return this;
		}

		public Builder workstation(int x, int y, int z) {
			this.workstation = new BlockPos(x, y, z);
			return this;
		}

		public Builder garden() {
			this.garden = true;
			return this;
		}

		public Blueprint build() {
			return new Blueprint(this);
		}
	}
}
