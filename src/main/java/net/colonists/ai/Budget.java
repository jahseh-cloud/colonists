package net.colonists.ai;

import net.minecraft.world.World;

/**
 * Spreads the expensive thinking over time: only so many colonists may plan their next move, and only so many
 * may run the pathfinder, in any one server tick. The rest wait a tick or two, which nobody notices, and a
 * crowded city no longer stutters when a hundred colonists all finish their chores at once.
 */
public final class Budget {
	public static int PLANS_PER_TICK = 6;
	public static int PATHS_PER_TICK = 10;

	private static long tick = Long.MIN_VALUE;
	private static int plans, paths;
	/** Totals, for the selftest's report. */
	public static long totalPlans, deferredPlans, totalPaths, deferredPaths;

	private Budget() {}

	private static void roll(World world) {
		long now = world.getTime();
		if (now != tick) {
			tick = now;
			plans = 0;
			paths = 0;
		}
	}

	public static synchronized boolean plan(World world) {
		roll(world);
		if (plans >= PLANS_PER_TICK) {
			deferredPlans++;
			return false;
		}
		plans++;
		totalPlans++;
		return true;
	}

	public static synchronized boolean path(World world) {
		roll(world);
		if (paths >= PATHS_PER_TICK) {
			deferredPaths++;
			return false;
		}
		paths++;
		totalPaths++;
		return true;
	}
}
