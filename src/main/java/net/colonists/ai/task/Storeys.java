package net.colonists.ai.task;

import net.colonists.build.Blueprint;
import net.colonists.build.Site;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.Settlement;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * Getting between the floors of a tall house. The vanilla pathfinder never plans a climb, so when a colonist and
 * where they are going are on different storeys of their home, this takes over: walk to the foot (or the top) of the
 * ladder, climb it, and step off onto the right floor.
 */
final class Storeys {
	private Storeys() {}

	/** The house's ladder, in world coordinates. */
	private record House(BlockPos base, int top, Direction front, int minX, int maxX, int minZ, int maxZ, int height) {
		boolean inside(BlockPos p) {
			return p.getX() >= this.minX && p.getX() <= this.maxX && p.getZ() >= this.minZ && p.getZ() <= this.maxZ
					&& p.getY() >= this.base.getY() - 1 && p.getY() <= this.base.getY() + this.height;
		}

		boolean inColumn(ColonistEntity c) {
			return c.getBlockX() == this.base.getX() && c.getBlockZ() == this.base.getZ()
					&& c.getBlockY() >= this.base.getY() && c.getBlockY() <= this.top + 1;
		}
	}

	@Nullable
	private static House house(ColonistEntity c) {
		BlockPos home = c.home != null ? c.home : c.parentHome;
		Settlement f = c.settlement();
		if (home == null || f == null) return null;
		Blueprint bp = BuildStructureTask.blueprintAt(f, home);
		if (bp.ladder == null || bp.ladderFacing == null) return null;
		Site site = f.site(home);
		BlockPos a = site.at(0, 0, 0), b = site.at(bp.width - 1, 0, bp.depth - 1);
		BlockPos base = site.at(bp.ladder);
		if (!c.getWorld().getBlockState(base).isOf(net.minecraft.block.Blocks.LADDER)) return null;
		return new House(base, base.getY() + bp.ladderTop - bp.ladder.getY(), site.dir(bp.ladderFacing),
				Math.min(a.getX(), b.getX()), Math.max(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()), Math.max(a.getZ(), b.getZ()), bp.height);
	}

	/** Moves the colonist this tick if a climb is involved; false = ordinary walking will do. */
	static boolean route(Task t, ColonistEntity c, BlockPos pos) {
		House h = house(c);
		if (h == null || t.routeTicks > 600) { // no ladder (yet), or this isn't working: walk as usual
			t.climbing = false;
			return false;
		}
		int ground = h.base().getY();
		int level = c.getBlockY();
		boolean targetInside = h.inside(pos);
		int goal = targetInside ? pos.getY() : ground;
		if (h.inColumn(c)) {
			t.climbing = true;
			t.routeTicks++;
			c.getNavigation().stop();
			double cx = h.base().getX() + 0.5 - c.getX(), cz = h.base().getZ() + 0.5 - c.getZ();
			if (level < goal) { // up
				Vec3d off = level >= goal - 1 ? Vec3d.of(h.front().getVector()).multiply(0.18) : Vec3d.ZERO;
				c.setVelocity(cx * 0.3 + off.x, 0.22, cz * 0.3 + off.z);
				c.activity = c.activity.startsWith("Climbing") ? c.activity : "Climbing the ladder";
				return true;
			}
			if (level > goal) { // down
				c.setVelocity(cx * 0.3, -0.15, cz * 0.3);
				return true;
			}
			if (level > ground) { // arrived on an upper floor: step off in front of the ladder
				Vec3d off = Vec3d.of(h.front().getVector()).multiply(0.2);
				c.setVelocity(off.x, 0.05, off.z);
				return true;
			}
			t.climbing = false;
			return false;
		}
		t.climbing = false;
		boolean colonistInside = h.inside(c.getBlockPos());
		boolean needed = targetInside ? Math.abs(level - goal) >= 3 && (colonistInside || level - ground < 3)
				: colonistInside && level - ground >= 3;
		if (!needed) return false;
		t.routeTicks++;
		// Head for the spot in front of the ladder on this floor (or at the foot of it), then step in.
		int at = level - ground >= 3 && colonistInside ? level : ground;
		BlockPos front = h.base().offset(h.front()).withY(at);
		double dx = front.getX() + 0.5 - c.getX(), dz = front.getZ() + 0.5 - c.getZ();
		if (dx * dx + dz * dz < 1.0 && Math.abs(c.getY() - at) < 1.2) {
			c.getNavigation().stop();
			c.getMoveControl().moveTo(h.base().getX() + 0.5, at, h.base().getZ() + 0.5, 0.6);
			return true;
		}
		if (c.getNavigation().isIdle() || c.age % 20 == 0) {
			var path = c.getNavigation().findPathTo(front, 0);
			if (path != null) c.getNavigation().startMovingAlong(path, 0.8);
		}
		return true;
	}
}
