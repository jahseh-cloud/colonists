package net.colonists.faction;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A city-state: its people, its city, its government, and its standing with players. */
public class Faction {
	public enum Government {
		CHIEFDOM("Chiefdom", "Chief"),
		MONARCHY("Monarchy", "Monarch"),
		REPUBLIC("Republic", "Consul"),
		PLAYER("Player-led", "Founder");

		public final String label, title;

		Government(String label, String title) {
			this.label = label;
			this.title = title;
		}
	}

	/** How big the settlement has grown. */
	public enum Rank {
		VILLAGE("Village"), TOWN("Town"), CITY("City"), CITY_STATE("City-State");

		public final String label;

		Rank(String label) {
			this.label = label;
		}
	}

	/** What the faction remembers about each member, so politics works even when the member's chunk is unloaded. */
	public static class Citizen {
		public String name = "";
		public String surname = "";
		public int ambition, aggression, kindness;
		public boolean child;
		public String job = "NONE";
		public int popularity;

		public String fullName() {
			return this.surname.isEmpty() ? this.name : this.name + " " + this.surname;
		}

		NbtCompound toNbt() {
			NbtCompound n = new NbtCompound();
			n.putString("Name", this.name);
			n.putString("Surname", this.surname);
			n.putInt("Amb", this.ambition);
			n.putInt("Agg", this.aggression);
			n.putInt("Kind", this.kindness);
			n.putBoolean("Child", this.child);
			n.putString("Job", this.job);
			n.putInt("Pop", this.popularity);
			return n;
		}

		static Citizen fromNbt(NbtCompound n) {
			Citizen c = new Citizen();
			c.name = n.getString("Name");
			c.surname = n.getString("Surname");
			c.ambition = n.getInt("Amb");
			c.aggression = n.getInt("Agg");
			c.kindness = n.getInt("Kind");
			c.child = n.getBoolean("Child");
			c.job = n.getString("Job");
			c.popularity = n.getInt("Pop");
			return c;
		}
	}

	public final String id;
	public String name;
	public Formatting color;
	/** UUIDs of colonist members. */
	public final Set<UUID> members = new HashSet<>();
	public final Map<UUID, Citizen> citizens = new HashMap<>();
	/** Personal reputation of players with this faction (-100..100). */
	public final Map<UUID, Integer> playerRep = new HashMap<>();
	/** House lots, mapped to the owning colonist (null = vacant). */
	public final Map<BlockPos, UUID> homes = new LinkedHashMap<>();
	/** Blueprint id for every claimed building lot (homes and public buildings). */
	public final Map<BlockPos, String> buildingTypes = new LinkedHashMap<>();
	/** Which way each building lot is turned (missing = facing north). */
	public final Map<BlockPos, net.minecraft.util.BlockRotation> rotations = new HashMap<>();
	/** Road columns (x, z with y = 0), grown organically outward from the plaza. */
	public final Set<BlockPos> roads = new LinkedHashSet<>();
	/** Lots whose building has been completed at least once. */
	public final Set<BlockPos> built = new HashSet<>();
	/** Communal field lots. */
	public final Set<BlockPos> farms = new LinkedHashSet<>();
	/** Lots that turned out to be unbuildable (steep, flooded, or someone else's building). Stored with y = 0. */
	public final Set<BlockPos> blockedLots = new HashSet<>();
	/** Set when a player founded this faction; that player rules it. */
	public UUID founderPlayer;

	// City
	@Nullable public BlockPos cityCenter;
	/** North-west corner of the city quarry, at the height of its rim; null until a miner picks a spot. */
	@Nullable public BlockPos quarry;
	public int quarrySize;
	/** Floor height where the mine tunnel starts (fixed once digging begins), or Integer.MIN_VALUE. */
	public int mineY = Integer.MIN_VALUE;
	public int foodInStock;

	// Politics
	public Government government = Government.CHIEFDOM;
	@Nullable public UUID ruler;
	public String rulerName = "";
	public String dynasty = "";
	public boolean slavery;
	public int approval = 60;
	public int warWeariness;
	public int captives;
	public String overlord = "";
	public long lastElection;
	public boolean revolt;

	// Settlement rank and the city wall (planned once the city reaches 15 houses).
	public Rank rank = Rank.VILLAGE;
	public boolean wallPlanned;
	public int wallMinX, wallMinZ, wallMaxX, wallMaxZ;
	public int wallProgress;
	public boolean wallBuilt;

	public Rank computeRank() {
		int houses = this.homes.size();
		if (houses >= 15) return this.wallBuilt ? Rank.CITY_STATE : Rank.CITY;
		if (houses >= 6) return Rank.TOWN;
		return Rank.VILLAGE;
	}
	public final Map<UUID, UUID> playerVotes = new HashMap<>();
	/** Recent deaths as "day:cause" entries (newest last, capped), for the death report. */
	public final java.util.List<String> deathLog = new java.util.ArrayList<>();

	public void recordDeath(long day, String cause) {
		this.deathLog.add(day + ":" + cause);
		while (this.deathLog.size() > 60) this.deathLog.remove(0);
	}

	/** Death counts by cause over the last {@code days} in-game days. */
	public Map<String, Integer> recentDeaths(long today, int days) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String e : this.deathLog) {
			int i = e.indexOf(':');
			try {
				if (today - Long.parseLong(e.substring(0, i)) <= days) counts.merge(e.substring(i + 1), 1, Integer::sum);
			} catch (NumberFormatException ignored) {
			}
		}
		return counts;
	}

	public Faction(String id, String name, Formatting color) {
		this.id = id;
		this.name = name;
		this.color = color;
	}

	public MutableText displayName() {
		return Text.literal(this.name).formatted(this.color);
	}

	@Nullable
	public BlockPos center() {
		if (this.cityCenter != null) return this.cityCenter;
		return this.homes.isEmpty() ? null : this.homes.keySet().iterator().next();
	}

	public net.colonists.build.Site site(BlockPos origin) {
		return new net.colonists.build.Site(origin, this.rotations.getOrDefault(origin, net.minecraft.util.BlockRotation.NONE));
	}

	public int adults() {
		int n = 0;
		for (Citizen c : this.citizens.values()) if (!c.child) n++;
		return n;
	}

	/** Lots holding a public building of this type (built or under construction). */
	public java.util.List<BlockPos> buildingsOf(String type) {
		java.util.List<BlockPos> list = new java.util.ArrayList<>();
		this.buildingTypes.forEach((pos, t) -> {
			if (t.equals(type)) list.add(pos);
		});
		return list;
	}

	public boolean hasBuilt(String type) {
		for (BlockPos p : buildingsOf(type)) if (this.built.contains(p)) return true;
		return false;
	}

	public int countJob(String job) {
		int n = 0;
		for (Citizen c : this.citizens.values()) if (!c.child && c.job.equals(job)) n++;
		return n;
	}

	@Nullable
	public Citizen rulerCitizen() {
		return this.ruler == null ? null : this.citizens.get(this.ruler);
	}

	public NbtCompound toNbt() {
		NbtCompound nbt = new NbtCompound();
		nbt.putString("Id", this.id);
		nbt.putString("Name", this.name);
		nbt.putString("Color", this.color.getName());
		NbtList memberList = new NbtList();
		for (UUID uuid : this.members) {
			NbtCompound m = new NbtCompound();
			m.putUuid("U", uuid);
			Citizen c = this.citizens.get(uuid);
			if (c != null) m.put("C", c.toNbt());
			memberList.add(m);
		}
		nbt.put("Members", memberList);
		NbtList repList = new NbtList();
		for (Map.Entry<UUID, Integer> e : this.playerRep.entrySet()) {
			NbtCompound r = new NbtCompound();
			r.putUuid("U", e.getKey());
			r.putInt("V", e.getValue());
			repList.add(r);
		}
		nbt.put("Rep", repList);
		NbtList homeList = new NbtList();
		for (Map.Entry<BlockPos, UUID> e : this.homes.entrySet()) {
			NbtCompound h = new NbtCompound();
			h.putLong("Pos", e.getKey().asLong());
			if (e.getValue() != null) h.putUuid("Owner", e.getValue());
			homeList.add(h);
		}
		nbt.put("Homes", homeList);
		nbt.putLongArray("Farms", this.farms.stream().mapToLong(BlockPos::asLong).toArray());
		NbtCompound types = new NbtCompound();
		this.buildingTypes.forEach((pos, type) -> types.putString(Long.toString(pos.asLong()), type));
		nbt.put("Buildings", types);
		nbt.putLongArray("Built", this.built.stream().mapToLong(BlockPos::asLong).toArray());
		NbtCompound rot = new NbtCompound();
		this.rotations.forEach((pos, r) -> rot.putString(Long.toString(pos.asLong()), r.name()));
		nbt.put("Rotations", rot);
		nbt.putLongArray("Roads", this.roads.stream().mapToLong(BlockPos::asLong).toArray());
		nbt.putLongArray("Blocked", this.blockedLots.stream().mapToLong(BlockPos::asLong).toArray());
		if (this.founderPlayer != null) nbt.putUuid("Founder", this.founderPlayer);
		if (this.cityCenter != null) nbt.putLong("Center", this.cityCenter.asLong());
		nbt.putInt("Food", this.foodInStock);
		if (this.quarry != null) nbt.putLong("Quarry", this.quarry.asLong());
		nbt.putInt("QuarrySize", this.quarrySize);
		nbt.putInt("MineY", this.mineY);
		nbt.putString("Gov", this.government.name());
		if (this.ruler != null) nbt.putUuid("Ruler", this.ruler);
		nbt.putString("RulerName", this.rulerName);
		nbt.putString("Dynasty", this.dynasty);
		nbt.putBoolean("Slavery", this.slavery);
		nbt.putInt("Approval", this.approval);
		nbt.putInt("Weariness", this.warWeariness);
		nbt.putInt("Captives", this.captives);
		nbt.putString("Overlord", this.overlord);
		nbt.putLong("LastElection", this.lastElection);
		nbt.putBoolean("Revolt", this.revolt);
		NbtList deaths = new NbtList();
		for (String d : this.deathLog) deaths.add(net.minecraft.nbt.NbtString.of(d));
		nbt.put("Deaths", deaths);
		nbt.putString("Rank", this.rank.name());
		nbt.putBoolean("WallPlanned", this.wallPlanned);
		nbt.putIntArray("Wall", new int[]{this.wallMinX, this.wallMinZ, this.wallMaxX, this.wallMaxZ});
		nbt.putInt("WallProgress", this.wallProgress);
		nbt.putBoolean("WallBuilt", this.wallBuilt);
		return nbt;
	}

	public static Faction fromNbt(NbtCompound nbt) {
		Formatting color = Formatting.byName(nbt.getString("Color"));
		Faction f = new Faction(nbt.getString("Id"), nbt.getString("Name"), color == null ? Formatting.WHITE : color);
		NbtList memberList = nbt.getList("Members", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < memberList.size(); i++) {
			NbtCompound m = memberList.getCompound(i);
			UUID uuid = m.getUuid("U");
			f.members.add(uuid);
			f.citizens.put(uuid, m.contains("C") ? Citizen.fromNbt(m.getCompound("C")) : new Citizen());
		}
		NbtList repList = nbt.getList("Rep", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < repList.size(); i++) {
			NbtCompound r = repList.getCompound(i);
			f.playerRep.put(r.getUuid("U"), r.getInt("V"));
		}
		NbtList homeList = nbt.getList("Homes", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < homeList.size(); i++) {
			NbtCompound h = homeList.getCompound(i);
			f.homes.put(BlockPos.fromLong(h.getLong("Pos")), h.containsUuid("Owner") ? h.getUuid("Owner") : null);
		}
		for (long l : nbt.getLongArray("Farms")) f.farms.add(BlockPos.fromLong(l));
		for (long l : nbt.getLongArray("Blocked")) f.blockedLots.add(BlockPos.fromLong(l));
		NbtCompound types = nbt.getCompound("Buildings");
		for (String k : types.getKeys()) {
			try {
				f.buildingTypes.put(BlockPos.fromLong(Long.parseLong(k)), types.getString(k));
			} catch (NumberFormatException ignored) {
			}
		}
		for (long l : nbt.getLongArray("Built")) f.built.add(BlockPos.fromLong(l));
		NbtCompound rot = nbt.getCompound("Rotations");
		for (String k : rot.getKeys()) {
			try {
				f.rotations.put(BlockPos.fromLong(Long.parseLong(k)), net.minecraft.util.BlockRotation.valueOf(rot.getString(k)));
			} catch (IllegalArgumentException ignored) {
			}
		}
		for (long l : nbt.getLongArray("Roads")) f.roads.add(BlockPos.fromLong(l));
		if (nbt.containsUuid("Founder")) f.founderPlayer = nbt.getUuid("Founder");
		if (nbt.contains("Center")) f.cityCenter = BlockPos.fromLong(nbt.getLong("Center"));
		f.foodInStock = nbt.getInt("Food");
		if (nbt.contains("Quarry")) f.quarry = BlockPos.fromLong(nbt.getLong("Quarry"));
		f.quarrySize = nbt.getInt("QuarrySize");
		f.mineY = nbt.contains("MineY") ? nbt.getInt("MineY") : Integer.MIN_VALUE;
		try {
			f.government = Government.valueOf(nbt.getString("Gov"));
		} catch (IllegalArgumentException e) {
			f.government = f.founderPlayer != null ? Government.PLAYER : Government.CHIEFDOM;
		}
		if (nbt.containsUuid("Ruler")) f.ruler = nbt.getUuid("Ruler");
		f.rulerName = nbt.getString("RulerName");
		f.dynasty = nbt.getString("Dynasty");
		f.slavery = nbt.getBoolean("Slavery");
		f.approval = nbt.contains("Approval") ? nbt.getInt("Approval") : 60;
		f.warWeariness = nbt.getInt("Weariness");
		f.captives = nbt.getInt("Captives");
		f.overlord = nbt.getString("Overlord");
		f.lastElection = nbt.getLong("LastElection");
		f.revolt = nbt.getBoolean("Revolt");
		NbtList deaths = nbt.getList("Deaths", NbtElement.STRING_TYPE);
		for (int i = 0; i < deaths.size(); i++) f.deathLog.add(deaths.getString(i));
		try {
			f.rank = Rank.valueOf(nbt.getString("Rank"));
		} catch (IllegalArgumentException e) {
			f.rank = Rank.VILLAGE;
		}
		f.wallPlanned = nbt.getBoolean("WallPlanned");
		int[] wall = nbt.getIntArray("Wall");
		if (wall.length == 4) {
			f.wallMinX = wall[0];
			f.wallMinZ = wall[1];
			f.wallMaxX = wall[2];
			f.wallMaxZ = wall[3];
		}
		f.wallProgress = nbt.getInt("WallProgress");
		f.wallBuilt = nbt.getBoolean("WallBuilt");
		return f;
	}
}
