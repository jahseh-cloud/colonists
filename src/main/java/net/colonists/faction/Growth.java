package net.colonists.faction;

import net.colonists.ColonistsMod;
import net.colonists.entity.ColonistEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

/**
 * Towns grow on their own: every day a share of their people (set by the realm's era) is added, newcomers from the
 * countryside and children of the town, whether or not anyone is there to watch. A town nobody has loaded keeps its
 * new people waiting at the gate until its chunks load, then they walk in.
 */
public final class Growth {
	private Growth() {}

	/** Daily growth by era: Timber, Stone, Iron, Grand. */
	public static final double[] RATE = {0.02, 0.03, 0.04, 0.05};

	/** Today's growth rate for a town, with everything that speeds or slows it. */
	public static double rate(MinecraftServer server, Settlement s) {
		Faction f = s.faction;
		double base = RATE[Math.max(0, Math.min(3, f.era))];
		double r = base;
		// The age of the world: plenty speeds growth, a dark age slows it (within limits).
		r *= Math.max(0.75, Math.min(1.5, 1.0 / Math.max(0.3, net.colonists.legend.Ages.births(net.colonists.legend.History.get(server)))));
		if (net.colonists.legend.Crises.golden(server, f.id)) r *= 1.5;
		if (net.colonists.legend.Wonders.holds(server, f, net.colonists.legend.Wonders.Type.GARDENS)) r *= 1.3;
		if (f.atWar) r *= 0.8;
		int pop = Math.max(1, s.population());
		if (s.foodInStock < pop / 4) r *= 0.7; // hungry towns grow slower
		// Short of houses: some newcomers hold off until more go up.
		int homeless = Math.max(0, s.colonists() - s.homes.size() * 2);
		if (homeless * 10 > pop * 4) r *= 0.6;
		return Math.max(base * 0.4, r);
	}

	/** Once a minute: a new day's growth, and anyone waiting at the gate walks in if the town is loaded. */
	public static void tick(MinecraftServer server) {
		ServerWorld world = server.getOverworld();
		FactionManager fm = FactionManager.get(server);
		long day = world.getTimeOfDay() / 24000L;
		int cap = world.getGameRules().getInt(ColonistsMod.MAX_PER_FACTION);
		boolean changed = false;
		for (Settlement s : fm.allSettlements()) {
			if (s.cityCenter == null || s.faction == null || s.faction.freeVillage || s.camp || s.colonists() < 2) continue;
			if (s.lastGrowthDay < 0) s.lastGrowthDay = day;
			if (s.lastGrowthDay < day) {
				changed |= grow(server, s, (int) Math.min(365, day - s.lastGrowthDay), cap) > 0 || true;
				s.lastGrowthDay = day;
			}
			if (s.pendingNewcomers > 0 && world.isChunkLoaded(s.cityCenter.getX() >> 4, s.cityCenter.getZ() >> 4)) {
				int n = arrive(world, s, Math.min(8, s.pendingNewcomers));
				if (n > 0) {
					changed = true;
					for (PlayerEntity p : world.getPlayers()) {
						if (p.getBlockPos().getSquaredDistance(s.cityCenter) < 160 * 160) {
							p.sendMessage(Text.literal("🌱 " + (n == 1 ? "A newcomer settles" : n + " newcomers settle") + " in " + s.name + " (pop. " + s.population() + ").").formatted(Formatting.GREEN), false);
						}
					}
				}
			}
		}
		if (changed) fm.markDirty();
	}

	/** Some days of growth for a town (newcomers are counted, and wait at the gate). Returns how many were added. */
	public static int grow(MinecraftServer server, Settlement s, int days, int cap) {
		int added = 0;
		for (int d = 0; d < days; d++) {
			if (cap > 0 && s.faction.members.size() + s.pendingNewcomers >= cap) break;
			s.growthCarry += s.population() * rate(server, s);
			int n = (int) Math.floor(s.growthCarry);
			s.growthCarry -= n;
			s.pendingNewcomers += n;
			added += n;
		}
		return added;
	}

	/** People arrive at the plaza: mostly grown folk looking for work, some children. */
	static int arrive(ServerWorld world, Settlement s, int n) {
		Random r = world.getRandom();
		int done = 0;
		for (int i = 0; i < n; i++) {
			ColonistEntity c = ColonistsMod.COLONIST.create(world);
			if (c == null) break;
			BlockPos at = s.cityCenter;
			int x = at.getX() + r.nextInt(7) - 3, z = at.getZ() + r.nextInt(7) - 3;
			int y = City.surfaceYOr(world, x, z, at.getY());
			c.refreshPositionAndAngles(x + 0.5, y, z + 0.5, r.nextFloat() * 360f, 0f);
			c.pendingFaction = s.faction.id;
			c.pendingSettlement = s.id;
			c.initialize(world, world.getLocalDifficulty(c.getBlockPos()), SpawnReason.EVENT, null);
			if (r.nextInt(10) < 3) c.setChild(true);
			world.spawnEntity(c);
			done++;
		}
		s.pendingNewcomers -= done;
		return done;
	}
}
