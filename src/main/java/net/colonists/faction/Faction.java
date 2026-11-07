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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A realm: a people with a government, a ruler, laws and diplomacy, holding one or more settlements.
 * One settlement is a city-state; several make a kingdom; many make an empire.
 */
public class Faction {
	public enum Government {
		CHIEFDOM("Chiefdom", "Chief"),
		MONARCHY("Monarchy", "Monarch"),
		REPUBLIC("Republic", "Consul"),
		PLAYER("Player-led", "Founder"),
		FREE("Free village", "Elders");

		public final String label, title;

		Government(String label, String title) {
			this.label = label;
			this.title = title;
		}
	}

	/** How big a single settlement has grown. */
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
		public int settlement = -1;
		/** Emeralds this citizen owns (the authoritative wallet, kept even while their chunk is unloaded). */
		public int wealth;
		/** Bonded: owned by someone (no wages, no office). */
		public boolean bonded;

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
			n.putInt("Settlement", this.settlement);
			n.putInt("Wealth", this.wealth);
			n.putBoolean("Bonded", this.bonded);
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
			c.settlement = n.contains("Settlement") ? n.getInt("Settlement") : -1;
			c.wealth = n.getInt("Wealth");
			c.bonded = n.getBoolean("Bonded");
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
	/** The realm's settlements by world-unique id. */
	public final Map<Integer, Settlement> settlements = new LinkedHashMap<>();
	/** Id of the capital settlement (-1 until the first is founded). */
	public int capital = -1;
	/** Set when a player founded this faction; that player rules it. */
	public UUID founderPlayer;

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
	public long lastColonyDay = Long.MIN_VALUE;
	/** The enemy settlement this faction's armies are marching on (-1 = none). */
	public int warGoal = -1;
	/** A player ruling this faction (founder, elected, usurper or heir), or null. */
	@Nullable public UUID rulerPlayer;
	/** A player named heir to the throne by a monarch. */
	@Nullable public UUID heirPlayer;
	/** Players standing in the next election. */
	public final Set<UUID> playerCandidates = new HashSet<>();
	/** 0-100: what the soldiers and the court (nobles, council or senate) think of the ruler. */
	public int armyOpinion = 55, courtOpinion = 55;
	/** A colony the ruler has ordered: where, and which settlement sends the settlers. */
	@Nullable public BlockPos orderedColony;
	public int orderedColonyFrom = -1;

	/** A vanilla village of free men, given a place in the politics of the world. */
	public boolean freeVillage;

	public boolean playerRuled() {
		return this.rulerPlayer != null;
	}

	/** The law book. */
	public final java.util.EnumSet<net.colonists.law.Law> laws = java.util.EnumSet.of(net.colonists.law.Law.THEFT,
			net.colonists.law.Law.VANDALISM, net.colonists.law.Law.ASSAULT, net.colonists.law.Law.MURDER, net.colonists.law.Law.POACHING);
	/** Recomputed every diplomacy tick: is this faction at war with anyone. */
	public transient boolean atWar;
	public final Map<UUID, UUID> playerVotes = new HashMap<>();
	/** Recent deaths as "day:cause" entries (newest last, capped), for the death report. */
	public final java.util.List<String> deathLog = new java.util.ArrayList<>();

	public Faction(String id, String name, Formatting color) {
		this.id = id;
		this.name = name;
		this.color = color;
	}

	public MutableText displayName() {
		return Text.literal(this.name).formatted(this.color);
	}

	// ---------------------------------------------------------------- settlements

	@Nullable
	public Settlement capital() {
		Settlement s = this.settlements.get(this.capital);
		if (s == null && !this.settlements.isEmpty()) {
			s = this.settlements.values().iterator().next();
			this.capital = s.id;
		}
		return s;
	}

	public void addSettlement(Settlement s) {
		s.faction = this;
		this.settlements.put(s.id, s);
		if (this.capital < 0 || !this.settlements.containsKey(this.capital)) this.capital = s.id;
	}

	/** The faction's settlement closest to a position (the capital if none has a centre). */
	@Nullable
	public Settlement nearestTo(BlockPos pos) {
		Settlement best = null;
		double bestD = Double.MAX_VALUE;
		for (Settlement s : this.settlements.values()) {
			if (s.cityCenter == null) continue;
			double d = s.cityCenter.getSquaredDistance(pos);
			if (d < bestD) {
				bestD = d;
				best = s;
			}
		}
		return best != null ? best : this.capital();
	}

	/** Capital rank: what drives government reform, mines, and so on. */
	public Rank rank() {
		Settlement c = this.capital();
		return c == null ? Rank.VILLAGE : c.rank;
	}

	/** Kingdom, Empire... by how much the realm holds. */
	public String realmTitle() {
		int n = this.settlements.size();
		boolean cityState = this.settlements.values().stream().anyMatch(s -> s.rank == Rank.CITY_STATE);
		if (n >= 4 && cityState) return "Empire";
		if (n >= 2) return "Kingdom";
		Settlement c = this.capital();
		return c == null ? "Band" : c.rank.label;
	}

	@Nullable
	public BlockPos center() {
		Settlement c = this.capital();
		return c == null ? null : c.center();
	}

	public int totalHomes() {
		int n = 0;
		for (Settlement s : this.settlements.values()) n += s.homes.size();
		return n;
	}

	public int totalFood() {
		int n = 0;
		for (Settlement s : this.settlements.values()) n += s.foodInStock;
		return n;
	}

	// ---------------------------------------------------------------- people

	public int adults() {
		int n = 0;
		for (Citizen c : this.citizens.values()) if (!c.child) n++;
		return n;
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

	// ---------------------------------------------------------------- save & load

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
		NbtList settlementList = new NbtList();
		for (Settlement s : this.settlements.values()) settlementList.add(s.toNbt());
		nbt.put("Settlements", settlementList);
		nbt.putInt("Capital", this.capital);
		if (this.founderPlayer != null) nbt.putUuid("Founder", this.founderPlayer);
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
		nbt.putLong("LastColony", this.lastColonyDay);
		nbt.putInt("WarGoal", this.warGoal);
		nbt.putBoolean("FreeVillage", this.freeVillage);
		if (this.rulerPlayer != null) nbt.putUuid("RulerPlayer", this.rulerPlayer);
		if (this.heirPlayer != null) nbt.putUuid("HeirPlayer", this.heirPlayer);
		nbt.putInt("ArmyOp", this.armyOpinion);
		nbt.putInt("CourtOp", this.courtOpinion);
		if (this.orderedColony != null) {
			nbt.putLong("OrderedColony", this.orderedColony.asLong());
			nbt.putInt("OrderedColonyFrom", this.orderedColonyFrom);
		}
		NbtList lawList = new NbtList();
		for (net.colonists.law.Law l : this.laws) lawList.add(net.minecraft.nbt.NbtString.of(l.name()));
		nbt.put("Laws", lawList);
		NbtList deaths = new NbtList();
		for (String d : this.deathLog) deaths.add(net.minecraft.nbt.NbtString.of(d));
		nbt.put("Deaths", deaths);
		return nbt;
	}

	/** @param nextId supplies fresh settlement ids when converting an old single-city save */
	public static Faction fromNbt(NbtCompound nbt, java.util.function.IntSupplier nextId) {
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
		if (nbt.contains("Settlements")) {
			NbtList list = nbt.getList("Settlements", NbtElement.COMPOUND_TYPE);
			for (int i = 0; i < list.size(); i++) f.addSettlement(Settlement.fromNbt(list.getCompound(i), nextId.getAsInt(), f.name));
			f.capital = nbt.getInt("Capital");
		} else if (nbt.contains("Center")) {
			// A save from before empires: the faction's city becomes its first settlement.
			Settlement s = Settlement.fromNbt(nbt, nextId.getAsInt(), f.name);
			f.addSettlement(s);
			for (Citizen c : f.citizens.values()) c.settlement = s.id;
		}
		if (nbt.containsUuid("Founder")) f.founderPlayer = nbt.getUuid("Founder");
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
		f.lastColonyDay = nbt.contains("LastColony") ? nbt.getLong("LastColony") : Long.MIN_VALUE;
		f.warGoal = nbt.contains("WarGoal") ? nbt.getInt("WarGoal") : -1;
		f.freeVillage = nbt.getBoolean("FreeVillage");
		if (nbt.containsUuid("RulerPlayer")) f.rulerPlayer = nbt.getUuid("RulerPlayer");
		if (nbt.containsUuid("HeirPlayer")) f.heirPlayer = nbt.getUuid("HeirPlayer");
		f.armyOpinion = nbt.contains("ArmyOp") ? nbt.getInt("ArmyOp") : 55;
		f.courtOpinion = nbt.contains("CourtOp") ? nbt.getInt("CourtOp") : 55;
		if (nbt.contains("OrderedColony")) {
			f.orderedColony = BlockPos.fromLong(nbt.getLong("OrderedColony"));
			f.orderedColonyFrom = nbt.getInt("OrderedColonyFrom");
		}
		// Older saves: player-founded factions were "Player-led"; their founder now simply rules.
		if (f.founderPlayer != null && f.rulerPlayer == null && f.government == Government.PLAYER) f.rulerPlayer = f.founderPlayer;
		if (f.government == Government.PLAYER) f.government = Government.CHIEFDOM;
		if (nbt.contains("Laws")) {
			f.laws.clear();
			NbtList lawList = nbt.getList("Laws", NbtElement.STRING_TYPE);
			for (int i = 0; i < lawList.size(); i++) {
				try {
					f.laws.add(net.colonists.law.Law.valueOf(lawList.getString(i)));
				} catch (IllegalArgumentException ignored) {
				}
			}
		}
		NbtList deaths = nbt.getList("Deaths", NbtElement.STRING_TYPE);
		for (int i = 0; i < deaths.size(); i++) f.deathLog.add(deaths.getString(i));
		return f;
	}
}
