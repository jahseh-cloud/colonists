package net.colonists.ai;

import net.colonists.entity.ColonistEntity;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiPredicate;

/** Finds the nearest block matching a test, without loading new chunks. */
public final class BlockSearch {
	private BlockSearch() {}

	/**
	 * @param cheap     fast check run on every block
	 * @param expensive slower check run only on candidates that would be the new closest (may be null)
	 */
	@Nullable
	public static BlockPos nearest(ColonistEntity colonist, int horizontal, int vertical,
								   BiPredicate<BlockState, BlockPos> cheap, @Nullable BiPredicate<BlockState, BlockPos> expensive) {
		World world = colonist.getWorld();
		BlockPos origin = colonist.getBlockPos();
		BlockPos.Mutable m = new BlockPos.Mutable();
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int dx = -horizontal; dx <= horizontal; dx++) {
			for (int dz = -horizontal; dz <= horizontal; dz++) {
				int x = origin.getX() + dx, z = origin.getZ() + dz;
				if (!world.isChunkLoaded(ChunkSectionPos.getSectionCoord(x), ChunkSectionPos.getSectionCoord(z))) continue;
				for (int dy = -vertical; dy <= vertical; dy++) {
					// Vertical distance counts double: things at eye level are easier to reach.
					double distance = dx * dx + dz * dz + dy * dy * 2.0;
					if (distance >= bestDistance) continue;
					m.set(x, origin.getY() + dy, z);
					if (world.isOutOfHeightLimit(m)) continue;
					BlockState state = world.getBlockState(m);
					if (!cheap.test(state, m) || colonist.isBlacklisted(m)) continue;
					BlockPos candidate = m.toImmutable();
					if (expensive != null && !expensive.test(state, candidate)) continue;
					best = candidate;
					bestDistance = distance;
				}
			}
		}
		return best;
	}

	/** True if at least one side of the block touches air, so it can be reached without tunnelling. */
	public static boolean exposed(World world, BlockPos pos) {
		for (Direction d : Direction.values()) {
			if (world.getBlockState(pos.offset(d)).isAir()) return true;
		}
		return false;
	}
}
