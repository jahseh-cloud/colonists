package net.colonists.faction;

import net.colonists.build.Blueprint;
import net.colonists.build.Blueprints;
import net.colonists.build.Site;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Organic city growth. Streets are pathfound across the land from the plaza, preferring gentle ground,
 * valley floors and river banks and avoiding steep climbs, so a settlement strings out along a river or
 * fills a valley instead of stamping a grid over a hillside. Buildings are then placed along the streets,
 * turned to face them, on whichever roadside spot is flattest, closest to the centre and nicest to live on.
 */
public final class CityGrowth {
	private CityGrowth() {}

	public static final int FIELD_SIZE = 9;

	// ---------------------------------------------------------------- footprints

	/** World bounds {minX, minZ, maxX, maxZ} of everything standing on a claimed lot (doorstep row included). */
	public static int[] footprint(Settlement f, BlockPos origin) {
		Site site = f.site(origin);
		if (f.farms.contains(origin)) return site.rect(0, -1, FIELD_SIZE - 1, FIELD_SIZE - 1);
		Blueprint bp = Blueprints.get(f.buildingTypes.getOrDefault(origin, "cabin"));
		return site.rect(0, -1, bp.lotWidth() - 1, bp.depth - 1);
	}

	public static List<int[]> footprints(Settlement f) {
		List<int[]> list = new ArrayList<>();
		Set<BlockPos> lots = new HashSet<>(f.buildingTypes.keySet());
		lots.addAll(f.homes.keySet());
		lots.addAll(f.farms);
		for (BlockPos o : lots) list.add(footprint(f, o));
		return list;
	}

	private static boolean overlaps(int[] a, int[] b, int margin) {
		return a[0] - margin <= b[2] && a[2] + margin >= b[0] && a[1] - margin <= b[3] && a[3] + margin >= b[1];
	}

	private static boolean inPlaza(Settlement f, int x, int z, int margin) {
		BlockPos c = f.cityCenter;
		return c != null && Math.abs(x - c.getX()) <= City.PLAZA_HALF + margin && Math.abs(z - c.getZ()) <= City.PLAZA_HALF + margin;
	}

	/** True if a world column is inside any lot of any faction (with a margin). */
	public static boolean occupied(FactionManager fm, int x, int z, int margin) {
		int[] p = {x, z, x, z};
		for (Settlement f : fm.allSettlements()) {
			if (inPlaza(f, x, z, margin)) return true;
			for (int[] r : footprints(f)) if (overlaps(r, p, margin)) return true;
		}
		return false;
	}

	// ---------------------------------------------------------------- claiming lots

	public enum Purpose { BUILDING, FIELD }

	/**
	 * Claims the best roadside lot for a blueprint (or a field) and records it. Returns the site with its
	 * floor height, or null if nothing suitable is reachable right now (the street network is extended
	 * for next time).
	 */
	@Nullable
	public static Site claim(ServerWorld world, FactionManager fm, Settlement f, @Nullable Blueprint bp, Purpose purpose, Random random) {
		if (f.cityCenter == null) return null;
		if (f.roads.isEmpty()) seedRoads(world, fm, f, random);
		Site site = bestLot(world, fm, f, bp, purpose, random);
		if (site == null) {
			// Nowhere left along the streets: lay a new one, following the lie of the land.
			if (extendRoads(world, fm, f, random)) site = bestLot(world, fm, f, bp, purpose, random);
		}
		if (site == null) return null;
		BlockPos o = site.origin();
		f.rotations.put(o, site.rotation());
		if (purpose == Purpose.FIELD) f.farms.add(o);
		else if (bp != null) f.buildingTypes.put(o, bp.id);
		fm.markDirty();
		return site;
	}

	@Nullable
	private static Site bestLot(ServerWorld world, FactionManager fm, Settlement f, @Nullable Blueprint bp, Purpose purpose, Random random) {
		int width = purpose == Purpose.FIELD ? FIELD_SIZE : bp.lotWidth();
		int depth = purpose == Purpose.FIELD ? FIELD_SIZE : bp.depth;
		int doorX = purpose == Purpose.FIELD ? FIELD_SIZE / 2 : (bp.doorX < 0 ? bp.width / 2 : bp.doorX);
		int tolerance = purpose == Purpose.FIELD ? 1 : net.colonists.ai.task.BuildStructureTask.tolerance(f.rank);
		// Hamlets keep a yard between houses; towns and cities build wall to wall.
		int margin = purpose == Purpose.FIELD || f.rank == Faction.Rank.VILLAGE ? 1 : 0;
		List<BlockPos> roads = new ArrayList<>(f.roads);
		List<int[]> taken = new ArrayList<>();
		for (Settlement other : fm.allSettlements()) taken.addAll(footprints(other));
		Site best = null;
		double bestScore = -Double.MAX_VALUE;
		int tries = Math.min(96, roads.size());
		for (int t = 0; t < tries; t++) {
			BlockPos r = roads.get(random.nextInt(roads.size()));
			for (Direction d : Direction.Type.HORIZONTAL) {
				BlockPos step = r.offset(d);
				if (f.roads.contains(step)) continue;
				// The building faces back toward the road; its doorstep is the roadside block.
				BlockRotation rot = Site.facing(d.getOpposite());
				BlockPos origin = step.subtract(new BlockPos(doorX, 0, -1).rotate(rot));
				Site site = new Site(origin, rot);
				int[] rect = site.rect(0, -1, width - 1, depth - 1);
				if (!loaded(world, rect)) continue;
				boolean clash = inPlaza(f, rect[0], rect[1], 1) || inPlaza(f, rect[2], rect[3], 1);
				for (int[] other : taken) {
					if (overlaps(rect, other, margin)) {
						clash = true;
						break;
					}
				}
				if (clash || touchesRoad(f, rect)) continue;
				if (!net.colonists.world.WorldEdge.playable(rect[0], rect[1], 8) || !net.colonists.world.WorldEdge.playable(rect[2], rect[3], 8)) continue;
				Integer floor = City.floorFor(world, rect[0], rect[1], rect[2], rect[3], tolerance);
				if (floor == null) continue;
				double score = score(world, f, rect, floor, purpose);
				// Neighbours: buildings huddle together along a street rather than scattering.
				if (purpose == Purpose.BUILDING) for (int[] other : taken) if (overlaps(rect, other, 2)) score += 4;
				if (score > bestScore) {
					bestScore = score;
					best = new Site(origin.withY(floor), rot);
				}
			}
		}
		return best;
	}

	private static boolean touchesRoad(Settlement f, int[] rect) {
		for (int x = rect[0]; x <= rect[2]; x++) {
			for (int z = rect[1]; z <= rect[3]; z++) {
				if (f.roads.contains(new BlockPos(x, 0, z))) return true;
			}
		}
		return false;
	}

	private static boolean loaded(World world, int[] rect) {
		return world.isChunkLoaded(rect[0] >> 4, rect[1] >> 4) && world.isChunkLoaded(rect[2] >> 4, rect[3] >> 4)
				&& world.isChunkLoaded(rect[0] >> 4, rect[3] >> 4) && world.isChunkLoaded(rect[2] >> 4, rect[1] >> 4);
	}

	/** Higher is better: close to the centre, level, near water, down in a valley. Fields like the outskirts. */
	private static double score(ServerWorld world, Settlement f, int[] rect, int floor, Purpose purpose) {
		BlockPos c = f.cityCenter;
		double cx = (rect[0] + rect[2]) / 2.0, cz = (rect[1] + rect[3]) / 2.0;
		double dist = Math.hypot(cx - c.getX(), cz - c.getZ());
		int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
		for (int x = rect[0]; x <= rect[2]; x += 2) {
			for (int z = rect[1]; z <= rect[3]; z += 2) {
				int h = City.surfaceY(world, x, z);
				min = Math.min(min, h);
				max = Math.max(max, h);
			}
		}
		double score = purpose == Purpose.FIELD ? dist * 0.2 - Math.max(0, reach(f) + 6 - dist) * 0.8 : -dist * 1.6;
		score -= (max - min) * 3.0;
		score += waterNearby(world, (int) cx, (int) cz, 9) * (purpose == Purpose.FIELD ? 2.5 : 1.5);
		score += Math.min(8, valleyDepth(world, (int) cx, (int) cz, floor)) * 1.2;
		return score;
	}

	public static int waterAround(World world, int x, int z, int radius) {
		return waterNearby(world, x, z, radius);
	}

	/** Lays a street from the nearest point of the town's network to a spot (a manor house out in the fields). */
	public static boolean connect(ServerWorld world, FactionManager fm, Settlement f, BlockPos target) {
		if (f.roads.isEmpty()) return false;
		BlockPos from = null;
		double best = Double.MAX_VALUE;
		for (BlockPos r : f.roads) {
			double d = r.getSquaredDistance(target.getX(), 0, target.getZ());
			if (d < best) {
				best = d;
				from = r;
			}
		}
		if (from == null || best < 4) return false;
		List<BlockPos> path = path(world, fm, f, from, new BlockPos(target.getX(), 0, target.getZ()));
		if (path == null) return false;
		for (BlockPos p : path) addStrip(f, p);
		fm.markDirty();
		return true;
	}

	/** How many sampled spots within a radius are water (rivers and lakes attract settlement). */
	static int waterNearby(World world, int x, int z, int radius) {
		int n = 0;
		for (int dx = -radius; dx <= radius; dx += 3) {
			for (int dz = -radius; dz <= radius; dz += 3) {
				int px = x + dx, pz = z + dz;
				if (!world.isChunkLoaded(px >> 4, pz >> 4)) continue;
				int y = City.surfaceY(world, px, pz);
				BlockState s = world.getBlockState(new BlockPos(px, y - 1, pz));
				if (!s.getFluidState().isEmpty()) n++;
			}
		}
		return Math.min(n, 6);
	}

	/** How far below the surrounding ridge line a spot sits. */
	static int valleyDepth(World world, int x, int z, int floor) {
		int total = 0, samples = 0;
		for (int i = 0; i < 8; i++) {
			double a = i * Math.PI / 4;
			int px = x + (int) (Math.cos(a) * 24), pz = z + (int) (Math.sin(a) * 24);
			if (!world.isChunkLoaded(px >> 4, pz >> 4)) continue;
			total += City.surfaceY(world, px, pz);
			samples++;
		}
		return samples == 0 ? 0 : Math.max(0, total / samples - floor);
	}

	/**
	 * Where to found a city near a colonist: a level, dry spot for the plaza, preferably by water and down
	 * in a valley. Null if nothing nearby is suitable.
	 */
	@Nullable
	public static BlockPos bestCenter(ServerWorld world, BlockPos near, Random random) {
		BlockPos best = null;
		double bestScore = -Double.MAX_VALUE;
		for (int i = 0; i < 40; i++) {
			int x = near.getX() + random.nextInt(65) - 32, z = near.getZ() + random.nextInt(65) - 32;
			if (!net.colonists.world.WorldEdge.playable(x, z, 40)) continue;
			Integer floor = City.floorFor(world, x - City.PLAZA_HALF, z - City.PLAZA_HALF, x + City.PLAZA_HALF, z + City.PLAZA_HALF, 2);
			if (floor == null) continue;
			double score = waterNearby(world, x, z, 14) * 2.0 + Math.min(10, valleyDepth(world, x, z, floor)) * 1.5
					- Math.hypot(x - near.getX(), z - near.getZ()) * 0.1;
			if (score > bestScore) {
				bestScore = score;
				best = new BlockPos(x, floor, z);
			}
		}
		return best;
	}

	// ---------------------------------------------------------------- streets

	/** The first streets: out from two opposite sides of the plaza, along the easiest axis. */
	static void seedRoads(ServerWorld world, FactionManager fm, Settlement f, Random random) {
		BlockPos c = f.cityCenter;
		for (Direction d : Direction.Type.HORIZONTAL) {
			BlockPos start = c.offset(d, City.PLAZA_HALF + 1);
			for (int i = 0; i <= 1; i++) addStrip(f, start.offset(d, i));
			growFrom(world, fm, f, start.offset(d, 1), d, random);
		}
	}

	/**
	 * How far the town may reach from its plaza: it grows with the number of buildings, so streets fill up before the
	 * town spreads (a hamlet stays a cluster; a city earns its suburbs).
	 */
	public static int reach(Settlement f) {
		int lots = f.homes.size() + f.buildingTypes.size();
		return (int) Math.min(City.MAX_RADIUS, 18 + 4.5 * Math.sqrt(lots));
	}

	/**
	 * Extends the network. Side streets branch off close to the centre first; only when the core has no room does a
	 * street push outward, and never beyond the town's reach.
	 */
	public static boolean extendRoads(ServerWorld world, FactionManager fm, Settlement f, Random random) {
		if (f.roads.isEmpty()) {
			seedRoads(world, fm, f, random);
			return true;
		}
		List<BlockPos> roads = new ArrayList<>(f.roads);
		int reach = reach(f);
		for (int attempt = 0; attempt < 4; attempt++) {
			// Last resort, when the core is truly full: push a street out past the old edge.
			int limit = attempt == 3 ? reach + 14 : reach;
			BlockPos start = null;
			double best = Double.MAX_VALUE;
			for (int i = 0; i < 32; i++) {
				BlockPos r = roads.get(random.nextInt(roads.size()));
				double d = Math.hypot(r.getX() - f.cityCenter.getX(), r.getZ() - f.cityCenter.getZ());
				if (d > limit - 6) continue;
				// Prefer the inner streets; later attempts look further out.
				double score = d * (attempt == 0 ? 1 : 0.3) + random.nextDouble() * 10;
				if (score < best) {
					best = score;
					start = r;
				}
			}
			if (start == null) return false;
			Direction outward = Direction.fromRotation(Math.toDegrees(Math.atan2(-(start.getX() - f.cityCenter.getX()), start.getZ() - f.cityCenter.getZ())));
			// A side street: off at right angles from the street it leaves (or straight on, at the edge).
			Direction dir = attempt < 2 ? (random.nextBoolean() ? outward.rotateYClockwise() : outward.rotateYCounterclockwise()) : outward;
			if (growFrom(world, fm, f, start, dir, random, limit)) return true;
		}
		return false;
	}

	private static boolean growFrom(ServerWorld world, FactionManager fm, Settlement f, BlockPos start, Direction generally, Random random) {
		return growFrom(world, fm, f, start, generally, random, City.MAX_RADIUS);
	}

	private static boolean growFrom(ServerWorld world, FactionManager fm, Settlement f, BlockPos start, Direction generally, Random random, int reach) {
		int y0 = City.surfaceYOr(world, start.getX(), start.getZ(), f.cityCenter.getY());
		BlockPos target = null;
		double bestScore = -Double.MAX_VALUE;
		for (int i = 0; i < 14; i++) {
			// Mostly outward, but free to bend along the terrain.
			double base = Math.toRadians(generally.asRotation()) + (random.nextDouble() - 0.5) * Math.PI * 0.6;
			int len = 10 + random.nextInt(7);
			int tx = start.getX() - (int) (Math.sin(base) * len), tz = start.getZ() + (int) (Math.cos(base) * len);
			if (Math.hypot(tx - f.cityCenter.getX(), tz - f.cityCenter.getZ()) > reach) continue;
			if (!world.isChunkLoaded(tx >> 4, tz >> 4) || occupied(fm, tx, tz, 2)) continue;
			int ty = City.surfaceY(world, tx, tz);
			BlockState ground = world.getBlockState(new BlockPos(tx, ty - 1, tz));
			if (!ground.getFluidState().isEmpty()) continue;
			int min = ty, max = ty;
			for (int dx = -4; dx <= 4; dx += 2) {
				for (int dz = -4; dz <= 4; dz += 2) {
					if (!world.isChunkLoaded((tx + dx) >> 4, (tz + dz) >> 4)) continue;
					int h = City.surfaceY(world, tx + dx, tz + dz);
					min = Math.min(min, h);
					max = Math.max(max, h);
				}
			}
			double score = -(max - min) * 3.0 - Math.abs(ty - y0) * 1.5
					+ waterNearby(world, tx, tz, 8) * 2.0 + Math.min(8, valleyDepth(world, tx, tz, ty)) * 1.5
					+ random.nextDouble() * 2;
			if (score > bestScore) {
				bestScore = score;
				target = new BlockPos(tx, 0, tz);
			}
		}
		if (target == null) return false;
		List<BlockPos> path = path(world, fm, f, start, target);
		if (path == null || path.size() < 4) return false;
		for (BlockPos p : path) addStrip(f, p);
		fm.markDirty();
		return true;
	}

	/** A three-wide street centred on a path cell, never cutting into a lot. */
	private static void addStrip(Settlement f, BlockPos p) {
		List<int[]> lots = footprints(f);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				int x = p.getX() + dx, z = p.getZ() + dz;
				int[] cell = {x, z, x, z};
				boolean blocked = false;
				for (int[] r : lots) {
					if (overlaps(r, cell, 0)) {
						blocked = true;
						break;
					}
				}
				if (!blocked && !inPlaza(f, x, z, 0) && !net.colonists.farm.Manors.onLand(f, x, z)) f.roads.add(new BlockPos(x, 0, z));
			}
		}
	}

	/**
	 * A* over the land surface: each step costs more the steeper it climbs, a climb of more than one block
	 * is impossible (no cliffs), and short stretches of water are allowed (to be bridged). Lots are obstacles.
	 */
	@Nullable
	static List<BlockPos> path(ServerWorld world, FactionManager fm, Settlement f, BlockPos from, BlockPos to) {
		record Node(long key, int x, int z, int y, double g, double fScore) {}
		Map<Long, Double> bestG = new HashMap<>();
		Map<Long, Long> cameFrom = new HashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.fScore, b.fScore));
		List<int[]> lots = new ArrayList<>();
		for (Settlement other : fm.allSettlements()) lots.addAll(footprints(other));
		int sy = City.surfaceYOr(world, from.getX(), from.getZ(), f.cityCenter.getY());
		long startKey = BlockPos.asLong(from.getX(), 0, from.getZ());
		open.add(new Node(startKey, from.getX(), from.getZ(), sy, 0, from.getManhattanDistance(to)));
		bestG.put(startKey, 0.0);
		int expanded = 0;
		long goalKey = BlockPos.asLong(to.getX(), 0, to.getZ());
		long reached = Long.MIN_VALUE;
		while (!open.isEmpty() && expanded++ < 4000) {
			Node n = open.poll();
			if (n.g > bestG.getOrDefault(n.key, Double.MAX_VALUE)) continue;
			if (n.key == goalKey || (Math.abs(n.x - to.getX()) <= 1 && Math.abs(n.z - to.getZ()) <= 1)) {
				reached = n.key;
				break;
			}
			for (Direction d : Direction.Type.HORIZONTAL) {
				int x = n.x + d.getOffsetX(), z = n.z + d.getOffsetZ();
				if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
				int[] cell = {x, z, x, z};
				boolean blocked = false;
				for (int[] r : lots) {
					if (overlaps(r, cell, 1)) {
						blocked = true;
						break;
					}
				}
				if (blocked) continue;
				int y = City.surfaceY(world, x, z);
				BlockState ground = world.getBlockState(new BlockPos(x, y - 1, z));
				boolean water = !ground.getFluidState().isEmpty();
				int climb = Math.abs(y - n.y);
				if (climb > 1 && !water) continue;
				if (!water && !City.isNatural(ground) && !City.isPavement(ground)) continue; // someone's building
				double cost = 1 + climb * 3.0 + (water ? 5.0 : 0) + (f.roads.contains(new BlockPos(x, 0, z)) ? -0.5 : 0);
				long key = BlockPos.asLong(x, 0, z);
				double g = n.g + cost;
				if (g < bestG.getOrDefault(key, Double.MAX_VALUE)) {
					bestG.put(key, g);
					cameFrom.put(key, n.key);
					open.add(new Node(key, x, z, water ? n.y : y, g, g + Math.abs(x - to.getX()) + Math.abs(z - to.getZ())));
				}
			}
		}
		if (reached == Long.MIN_VALUE) return null;
		List<BlockPos> path = new ArrayList<>();
		Long k = reached;
		while (k != null) {
			BlockPos p = BlockPos.fromLong(k);
			path.add(0, new BlockPos(p.getX(), 0, p.getZ()));
			k = cameFrom.get(k);
		}
		return path;
	}
}
