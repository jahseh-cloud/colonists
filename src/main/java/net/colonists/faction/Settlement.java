package net.colonists.faction;

import net.colonists.build.Site;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One village, town or city belonging to a faction: its plaza, streets, buildings, fields, quarry and wall.
 * A faction with several settlements is an empire. Settlements keep a world-unique id, so when one is
 * conquered and changes hands, its people (even those in unloaded chunks) find out they have a new ruler.
 */
public class Settlement {
	private static final String[] PREFIX = {"River", "Oak", "Stone", "Ash", "Mill", "Wolf", "Ember", "Frost", "Elm", "Briar",
			"Thorn", "Glen", "Cold", "High", "Fair", "Red", "Grey", "Green", "Iron", "Salt", "Willow", "Alder", "Raven", "Bright"};
	private static final String[] SUFFIX = {"ford", "ton", "wick", "dale", "bury", "stead", "haven", "hollow", "mere", "field",
			"gate", "wood", "crest", "brook", "holm", "moor", "port", "well", "bridge", "hill"};

	public final int id;
	public String name;
	/** Owning faction (not saved; set whenever the settlement is attached to a faction). */
	public transient Faction faction;
	public long foundedDay;
	/** 0-100: how content the settlement is with its current ruler (conquered places start low). */
	public int loyalty = 100;
	/** Faction this settlement was taken from, or "" (for reconquest wars and rebellions). */
	public String formerOwner = "";
	/** Siege state (not saved): how far an occupying army has got toward taking the plaza. */
	public transient int occupation;
	public transient String occupier = "";
	public transient long lastSiegeTick;

	@Nullable public BlockPos cityCenter;
	public final Map<BlockPos, UUID> homes = new LinkedHashMap<>();
	public final Map<BlockPos, String> buildingTypes = new LinkedHashMap<>();
	public final Map<BlockPos, BlockRotation> rotations = new HashMap<>();
	public final Set<BlockPos> roads = new LinkedHashSet<>();
	public final Set<BlockPos> built = new HashSet<>();
	public final Set<BlockPos> farms = new LinkedHashSet<>();
	/** The manors on the outskirts: lords' estates of large fields that feed this town. */
	public final java.util.List<net.colonists.farm.Manor> manors = new java.util.ArrayList<>();
	/** Passive growth: the last day counted, the fraction of a person carried over, and newcomers waiting to walk in. */
	public long lastGrowthDay = -1;
	public double growthCarry;
	public int pendingNewcomers;
	public final Set<BlockPos> blockedLots = new HashSet<>();
	@Nullable public BlockPos quarry;
	public int quarrySize;
	public int mineY = Integer.MIN_VALUE;
	public int foodInStock;
	/** Emeralds in the town treasury (taxes, fines, sales) and the last day wages were paid. */
	public int treasury = 20;
	public long lastPayday = -1;
	/** Unpaid wage days in a row (an unpaid town grows restless). */
	public int arrears;
	/** Ruler's orders: tax rate in percent (-1 = the government's usual), guards wanted (-1 = the usual share),
	 *  self-rule (lower taxes, quicker loyalty), and a building to put first. */
	public int taxRate = -1;
	public int garrison = -1;
	public boolean autonomy;
	public String priority = "";
	/** A vanilla village: its buildings are the villagers' own, so colonists do not build or dig here.
	 *  freemen = villagers counted on the last survey; grievance = how restless its colonist guests are. */
	public boolean village;
	public int freemen;
	public int grievance;
	public long lastNewcomerDay = -1;
	/** How far this village's chroniclers have written (event index) and how many volumes. */
	public long chronicled = -1;
	public int volumes;
	public int vMinX, vMinZ, vMaxX, vMaxZ;
	private transient int[] boundsCache;
	private transient long boundsTime = Long.MIN_VALUE;

	public Faction.Rank rank = Faction.Rank.VILLAGE;
	public boolean wallPlanned;
	public int wallMinX, wallMinZ, wallMaxX, wallMaxZ;
	public int wallProgress;
	public boolean wallBuilt;

	public Settlement(int id, String name) {
		this.id = id;
		this.name = name;
	}

	public static String randomName(Random random) {
		return PREFIX[random.nextInt(PREFIX.length)] + SUFFIX[random.nextInt(SUFFIX.length)];
	}

	public MutableText displayName() {
		return Text.literal(this.name).formatted(this.faction == null ? net.minecraft.util.Formatting.WHITE : this.faction.color);
	}

	/** The town's extent: its wall, or its buildings and fields plus a margin (minX, minZ, maxX, maxZ). */
	public int[] bounds(long now) {
		if (this.boundsCache != null && now - this.boundsTime < 200) return this.boundsCache;
		BlockPos c = this.cityCenter;
		int[] b;
		if (c == null) {
			b = new int[]{0, 0, -1, -1};
		} else if (this.village) {
			b = new int[]{this.vMinX, this.vMinZ, this.vMaxX, this.vMaxZ};
		} else if (this.wallPlanned) {
			b = new int[]{this.wallMinX, this.wallMinZ, this.wallMaxX, this.wallMaxZ};
		} else {
			b = new int[]{c.getX() - City.PLAZA_HALF - 6, c.getZ() - City.PLAZA_HALF - 6, c.getX() + City.PLAZA_HALF + 6, c.getZ() + City.PLAZA_HALF + 6};
			for (int[] r : CityGrowth.footprints(this)) {
				b[0] = Math.min(b[0], r[0] - 4);
				b[1] = Math.min(b[1], r[1] - 4);
				b[2] = Math.max(b[2], r[2] + 4);
				b[3] = Math.max(b[3], r[3] + 4);
			}
		}
		this.boundsCache = b;
		this.boundsTime = now;
		return b;
	}

	public boolean contains(BlockPos pos, long now, int margin) {
		int[] b = this.bounds(now);
		return pos.getX() >= b[0] - margin && pos.getX() <= b[2] + margin && pos.getZ() >= b[1] - margin && pos.getZ() <= b[3] + margin
				&& (this.cityCenter == null || Math.abs(pos.getY() - this.cityCenter.getY()) < 48);
	}

	/** Origin of the first finished building of a type, or null. */
	@Nullable
	public BlockPos builtOf(String type) {
		for (BlockPos o : this.buildingsOf(type)) if (this.built.contains(o)) return o;
		return null;
	}

	public boolean isCapital() {
		return this.faction != null && this.faction.capital == this.id;
	}

	@Nullable
	public BlockPos center() {
		if (this.cityCenter != null) return this.cityCenter;
		return this.homes.isEmpty() ? null : this.homes.keySet().iterator().next();
	}

	public Site site(BlockPos origin) {
		return new Site(origin, this.rotations.getOrDefault(origin, BlockRotation.NONE));
	}

	public List<BlockPos> buildingsOf(String type) {
		List<BlockPos> list = new ArrayList<>();
		this.buildingTypes.forEach((pos, t) -> {
			if (t.equals(type)) list.add(pos);
		});
		return list;
	}

	public boolean hasBuilt(String type) {
		for (BlockPos p : this.buildingsOf(type)) if (this.built.contains(p)) return true;
		return false;
	}

	public Faction.Rank computeRank() {
		int houses = this.homes.size();
		if (houses >= 15) return this.wallBuilt ? Faction.Rank.CITY_STATE : Faction.Rank.CITY;
		if (houses >= 6) return Faction.Rank.TOWN;
		return Faction.Rank.VILLAGE;
	}

	/** People who live here (from the faction's citizen records). */
	/** Colonists who live here (see population() for everyone, free men included). */
	public int colonists() {
		if (this.faction == null) return 0;
		int n = 0;
		for (Faction.Citizen c : this.faction.citizens.values()) if (c.settlement == this.id) n++;
		return n;
	}

	public int population() {
		if (this.faction == null) return 0;
		int n = (this.village ? this.freemen : 0) + this.pendingNewcomers;
		for (Faction.Citizen c : this.faction.citizens.values()) if (c.settlement == this.id) n++;
		return n;
	}

	public int adults() {
		if (this.faction == null) return 0;
		int n = 0;
		for (Faction.Citizen c : this.faction.citizens.values()) if (c.settlement == this.id && !c.child) n++;
		return n;
	}

	public int countJob(String job) {
		if (this.faction == null) return 0;
		int n = 0;
		for (Faction.Citizen c : this.faction.citizens.values()) if (c.settlement == this.id && !c.child && c.job.equals(job)) n++;
		return n;
	}

	// ---------------------------------------------------------------- save & load

	public NbtCompound toNbt() {
		NbtCompound nbt = new NbtCompound();
		nbt.putInt("SId", this.id);
		nbt.putString("SName", this.name);
		nbt.putLong("Founded", this.foundedDay);
		nbt.putInt("Loyalty", this.loyalty);
		nbt.putString("FormerOwner", this.formerOwner);
		if (this.cityCenter != null) nbt.putLong("Center", this.cityCenter.asLong());
		NbtList homeList = new NbtList();
		for (Map.Entry<BlockPos, UUID> e : this.homes.entrySet()) {
			NbtCompound h = new NbtCompound();
			h.putLong("Pos", e.getKey().asLong());
			if (e.getValue() != null) h.putUuid("Owner", e.getValue());
			homeList.add(h);
		}
		nbt.put("Homes", homeList);
		NbtCompound types = new NbtCompound();
		this.buildingTypes.forEach((pos, type) -> types.putString(Long.toString(pos.asLong()), type));
		nbt.put("Buildings", types);
		nbt.putLongArray("Built", this.built.stream().mapToLong(BlockPos::asLong).toArray());
		NbtCompound rot = new NbtCompound();
		this.rotations.forEach((pos, r) -> rot.putString(Long.toString(pos.asLong()), r.name()));
		nbt.put("Rotations", rot);
		nbt.putLongArray("Roads", this.roads.stream().mapToLong(BlockPos::asLong).toArray());
		nbt.putLongArray("Farms", this.farms.stream().mapToLong(BlockPos::asLong).toArray());
		net.minecraft.nbt.NbtList manorList = new net.minecraft.nbt.NbtList();
		for (net.colonists.farm.Manor m : this.manors) manorList.add(m.toNbt());
		nbt.put("Manors", manorList);
		nbt.putLong("GrowthDay", this.lastGrowthDay);
		nbt.putDouble("GrowthCarry", this.growthCarry);
		nbt.putInt("Newcomers", this.pendingNewcomers);
		nbt.putLongArray("Blocked", this.blockedLots.stream().mapToLong(BlockPos::asLong).toArray());
		if (this.quarry != null) nbt.putLong("Quarry", this.quarry.asLong());
		nbt.putInt("QuarrySize", this.quarrySize);
		nbt.putInt("MineY", this.mineY);
		nbt.putInt("Food", this.foodInStock);
		nbt.putInt("Treasury", this.treasury);
		nbt.putLong("Payday", this.lastPayday);
		nbt.putInt("Arrears", this.arrears);
		nbt.putInt("TaxRate", this.taxRate);
		nbt.putInt("Garrison", this.garrison);
		nbt.putBoolean("Autonomy", this.autonomy);
		nbt.putString("Priority", this.priority);
		nbt.putBoolean("Village", this.village);
		nbt.putInt("Freemen", this.freemen);
		nbt.putInt("Grievance", this.grievance);
		nbt.putLong("NewcomerDay", this.lastNewcomerDay);
		nbt.putLong("Chronicled", this.chronicled);
		nbt.putInt("Volumes", this.volumes);
		nbt.putIntArray("VBounds", new int[]{this.vMinX, this.vMinZ, this.vMaxX, this.vMaxZ});
		nbt.putString("Rank", this.rank.name());
		nbt.putBoolean("WallPlanned", this.wallPlanned);
		nbt.putIntArray("Wall", new int[]{this.wallMinX, this.wallMinZ, this.wallMaxX, this.wallMaxZ});
		nbt.putInt("WallProgress", this.wallProgress);
		nbt.putBoolean("WallBuilt", this.wallBuilt);
		return nbt;
	}

	/** Also reads the city fields of saves from before empires existed (they lived on the faction). */
	public static Settlement fromNbt(NbtCompound nbt, int fallbackId, String fallbackName) {
		Settlement s = new Settlement(nbt.contains("SId") ? nbt.getInt("SId") : fallbackId,
				nbt.contains("SName") ? nbt.getString("SName") : fallbackName);
		s.foundedDay = nbt.getLong("Founded");
		s.loyalty = nbt.contains("Loyalty") ? nbt.getInt("Loyalty") : 100;
		s.formerOwner = nbt.getString("FormerOwner");
		s.treasury = nbt.contains("Treasury") ? nbt.getInt("Treasury") : 20;
		s.lastPayday = nbt.contains("Payday") ? nbt.getLong("Payday") : -1;
		s.arrears = nbt.getInt("Arrears");
		s.taxRate = nbt.contains("TaxRate") ? nbt.getInt("TaxRate") : -1;
		s.garrison = nbt.contains("Garrison") ? nbt.getInt("Garrison") : -1;
		s.autonomy = nbt.getBoolean("Autonomy");
		s.priority = nbt.getString("Priority");
		s.village = nbt.getBoolean("Village");
		s.freemen = nbt.getInt("Freemen");
		s.grievance = nbt.getInt("Grievance");
		s.lastNewcomerDay = nbt.contains("NewcomerDay") ? nbt.getLong("NewcomerDay") : -1;
		s.chronicled = nbt.contains("Chronicled") ? nbt.getLong("Chronicled") : -1;
		s.volumes = nbt.getInt("Volumes");
		int[] vb = nbt.getIntArray("VBounds");
		if (vb.length == 4) {
			s.vMinX = vb[0];
			s.vMinZ = vb[1];
			s.vMaxX = vb[2];
			s.vMaxZ = vb[3];
		}
		if (nbt.contains("Center")) s.cityCenter = BlockPos.fromLong(nbt.getLong("Center"));
		NbtList homeList = nbt.getList("Homes", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < homeList.size(); i++) {
			NbtCompound h = homeList.getCompound(i);
			s.homes.put(BlockPos.fromLong(h.getLong("Pos")), h.containsUuid("Owner") ? h.getUuid("Owner") : null);
		}
		NbtCompound types = nbt.getCompound("Buildings");
		for (String k : types.getKeys()) {
			try {
				s.buildingTypes.put(BlockPos.fromLong(Long.parseLong(k)), types.getString(k));
			} catch (NumberFormatException ignored) {
			}
		}
		for (long l : nbt.getLongArray("Built")) s.built.add(BlockPos.fromLong(l));
		NbtCompound rot = nbt.getCompound("Rotations");
		for (String k : rot.getKeys()) {
			try {
				s.rotations.put(BlockPos.fromLong(Long.parseLong(k)), BlockRotation.valueOf(rot.getString(k)));
			} catch (IllegalArgumentException ignored) {
			}
		}
		for (long l : nbt.getLongArray("Roads")) s.roads.add(BlockPos.fromLong(l));
		for (long l : nbt.getLongArray("Farms")) s.farms.add(BlockPos.fromLong(l));
		for (net.minecraft.nbt.NbtElement e : nbt.getList("Manors", net.minecraft.nbt.NbtElement.COMPOUND_TYPE))
			s.manors.add(net.colonists.farm.Manor.fromNbt((net.minecraft.nbt.NbtCompound) e));
		s.lastGrowthDay = nbt.contains("GrowthDay") ? nbt.getLong("GrowthDay") : -1;
		s.growthCarry = nbt.getDouble("GrowthCarry");
		s.pendingNewcomers = nbt.getInt("Newcomers");
		for (long l : nbt.getLongArray("Blocked")) s.blockedLots.add(BlockPos.fromLong(l));
		if (nbt.contains("Quarry")) s.quarry = BlockPos.fromLong(nbt.getLong("Quarry"));
		s.quarrySize = nbt.getInt("QuarrySize");
		s.mineY = nbt.contains("MineY") ? nbt.getInt("MineY") : Integer.MIN_VALUE;
		s.foodInStock = nbt.getInt("Food");
		try {
			s.rank = Faction.Rank.valueOf(nbt.getString("Rank"));
		} catch (IllegalArgumentException e) {
			s.rank = Faction.Rank.VILLAGE;
		}
		s.wallPlanned = nbt.getBoolean("WallPlanned");
		int[] wall = nbt.getIntArray("Wall");
		if (wall.length == 4) {
			s.wallMinX = wall[0];
			s.wallMinZ = wall[1];
			s.wallMaxX = wall[2];
			s.wallMaxZ = wall[3];
		}
		s.wallProgress = nbt.getInt("WallProgress");
		s.wallBuilt = nbt.getBoolean("WallBuilt");
		return s;
	}
}
