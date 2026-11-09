package net.colonists.ai;

import net.colonists.entity.ColonistEntity;
import net.colonists.faction.Settlement;

/**
 * Each colonist's own day: when they work, when they down tools for the evening and when they sleep.
 * The town clock is shifted by the colonist's chronotype (early birds and night owls) and their trade:
 * farmers rise before dawn, miners keep long shifts underground, and the night watch sleeps by day.
 */
public final class Schedule {
	public enum Phase { WORK, EVENING, SLEEP }

	private Schedule() {}

	/** Guards on the night watch (half of a city's guards) keep the streets while the town sleeps. */
	public static boolean nightWatch(ColonistEntity c) {
		Settlement f = c.settlement();
		return c.job == ColonistEntity.Job.GUARD && c.getId() % 2 == 0 && f != null && f.cityCenter != null && !f.village;
	}

	/** Ticks this colonist's personal clock runs ahead of (positive) or behind the sun. */
	private static int offset(ColonistEntity c) {
		int shift = c.chronotype;
		if (c.job == ColonistEntity.Job.FARMER) shift += 700; // up before the sun
		return Math.max(-1200, Math.min(1200, shift));
	}

	/** The colonist's personal time of day, 0 = their dawn. */
	public static long clock(ColonistEntity c) {
		long t = c.getWorld().getTimeOfDay() % 24000L;
		return Math.floorMod(t + offset(c), 24000L);
	}

	public static Phase phase(ColonistEntity c) {
		if (!c.getWorld().getDimension().bedWorks()) return Phase.WORK;
		long t = clock(c);
		if (nightWatch(c)) {
			// On watch from dusk till dawn, a bite and a chat in the morning, sleep through the day.
			if (t >= 12000 || t < 300) return Phase.WORK;
			if (t < 1500) return Phase.EVENING;
			return t < 10500 ? Phase.SLEEP : Phase.WORK;
		}
		boolean miner = c.job == ColonistEntity.Job.MINER;
		int evening = miner ? 11300 : 10500;
		int bed = miner ? 13200 : 12700;
		if (t < evening) return Phase.WORK;
		if (t < bed) return Phase.EVENING;
		return t < 23300 ? Phase.SLEEP : Phase.WORK;
	}

	public static boolean sleepTime(ColonistEntity c) {
		return phase(c) == Phase.SLEEP;
	}

	/** Night watchmen rest at home by day rather than climbing into bed in broad daylight. */
	public static boolean restsByDay(ColonistEntity c) {
		return nightWatch(c) && c.getWorld().isDay();
	}
}
