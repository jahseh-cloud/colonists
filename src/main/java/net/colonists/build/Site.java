package net.colonists.build;

import net.minecraft.block.BlockState;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Where a blueprint stands: its origin and which way it is turned. Blueprints are designed facing north
 * (door in the z = 0 wall); a site's rotation turns the whole design, blocks and all, to face its street.
 */
public record Site(BlockPos origin, BlockRotation rotation) {
	public static Site of(BlockPos origin) {
		return new Site(origin, BlockRotation.NONE);
	}

	/** Rotation that makes a north-facing design face the given direction. */
	public static BlockRotation facing(Direction front) {
		return switch (front) {
			case EAST -> BlockRotation.CLOCKWISE_90;
			case SOUTH -> BlockRotation.CLOCKWISE_180;
			case WEST -> BlockRotation.COUNTERCLOCKWISE_90;
			default -> BlockRotation.NONE;
		};
	}

	public BlockPos at(int x, int y, int z) {
		return this.origin.add(new BlockPos(x, y, z).rotate(this.rotation));
	}

	public BlockPos at(BlockPos local) {
		return this.origin.add(local.rotate(this.rotation));
	}

	public BlockState state(BlockState s) {
		return s.rotate(this.rotation);
	}

	public Direction dir(Direction d) {
		return this.rotation.rotate(d);
	}

	/** The direction the front door looks out of. */
	public Direction front() {
		return this.dir(Direction.NORTH);
	}

	/** World-space bounds {minX, minZ, maxX, maxZ} of a local rectangle. */
	public int[] rect(int x0, int z0, int x1, int z1) {
		BlockPos a = this.at(x0, 0, z0), b = this.at(x1, 0, z1);
		return new int[]{Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), Math.max(a.getZ(), b.getZ())};
	}

	public boolean contains(Vec3d p, int x0, int z0, int x1, int z1, int y0, int y1) {
		int[] r = this.rect(x0, z0, x1, z1);
		return p.x >= r[0] && p.x < r[2] + 1 && p.z >= r[1] && p.z < r[3] + 1 && p.y >= this.origin.getY() + y0 && p.y < this.origin.getY() + y1;
	}
}
