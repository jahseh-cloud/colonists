package net.colonists.player;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Everything the world remembers about each player: deeds and titles, money, property, contracts, retinue, family and faith. */
public class PlayerData extends PersistentState {
	private static final Type<PlayerData> TYPE = new Type<>(PlayerData::new, PlayerData::fromNbt, null);

	public static PlayerData get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "colonists_players");
	}

	public static Profile of(MinecraftServer server, UUID who) {
		PlayerData d = get(server);
		d.markDirty();
		return d.profiles.computeIfAbsent(who, k -> new Profile());
	}

	// ---------------------------------------------------------------- a player's profile

	public static class Contract {
		public String type = "";
		public int town = -1;
		/** Target: an item id, a faction id, a settlement id or a colonist uuid, depending on the type. */
		public String target = "";
		public String targetName = "";
		public int needed, progress;
		public int reward;
		public long deadline;
		public long started;

		NbtCompound toNbt() {
			NbtCompound n = new NbtCompound();
			n.putString("Type", this.type);
			n.putInt("Town", this.town);
			n.putString("Target", this.target);
			n.putString("TargetName", this.targetName);
			n.putInt("Needed", this.needed);
			n.putInt("Progress", this.progress);
			n.putInt("Reward", this.reward);
			n.putLong("Deadline", this.deadline);
			n.putLong("Started", this.started);
			return n;
		}

		static Contract fromNbt(NbtCompound n) {
			Contract c = new Contract();
			c.type = n.getString("Type");
			c.town = n.getInt("Town");
			c.target = n.getString("Target");
			c.targetName = n.getString("TargetName");
			c.needed = n.getInt("Needed");
			c.progress = n.getInt("Progress");
			c.reward = n.getInt("Reward");
			c.deadline = n.getLong("Deadline");
			c.started = n.getLong("Started");
			return c;
		}

		public Contract copy() {
			return fromNbt(this.toNbt());
		}
	}

	public static class Property {
		public int town = -1;
		public long lot;
		/** "home" or a building type (tavern, smithy...). */
		public String kind = "home";
		public boolean rented;

		NbtCompound toNbt() {
			NbtCompound n = new NbtCompound();
			n.putInt("Town", this.town);
			n.putLong("Lot", this.lot);
			n.putString("Kind", this.kind);
			n.putBoolean("Rented", this.rented);
			return n;
		}

		static Property fromNbt(NbtCompound n) {
			Property p = new Property();
			p.town = n.getInt("Town");
			p.lot = n.getLong("Lot");
			p.kind = n.getString("Kind");
			p.rented = n.getBoolean("Rented");
			return p;
		}
	}

	public static class Profile {
		public String name = "";
		/** Emeralds held by the town bankers for this player (rent, business income, wages). */
		public int bank;
		public final Map<String, Integer> stats = new HashMap<>();
		public final List<String> titles = new ArrayList<>();
		public final List<String> deeds = new ArrayList<>();
		public final List<Contract> contracts = new ArrayList<>();
		public final List<Property> properties = new ArrayList<>();
		public final List<UUID> retinue = new ArrayList<>();
		@Nullable public UUID spouse;
		public String spouseName = "";
		public final List<UUID> children = new ArrayList<>();
		public String religion = "";
		public long lastPilgrimageDay = -1;
		public long lastPaidDay = -1;

		public int stat(String key) {
			return this.stats.getOrDefault(key, 0);
		}

		public void add(String key, int n) {
			this.stats.merge(key, n, Integer::sum);
		}

		public void deed(long day, String text) {
			this.deeds.add("Day " + (day + 1) + ": " + text);
			if (this.deeds.size() > 200) this.deeds.remove(0);
		}

		NbtCompound toNbt() {
			NbtCompound n = new NbtCompound();
			n.putString("Name", this.name);
			n.putInt("Bank", this.bank);
			NbtCompound st = new NbtCompound();
			this.stats.forEach(st::putInt);
			n.put("Stats", st);
			n.put("Titles", strings(this.titles));
			n.put("Deeds", strings(this.deeds));
			NbtList c = new NbtList();
			for (Contract x : this.contracts) c.add(x.toNbt());
			n.put("Contracts", c);
			NbtList p = new NbtList();
			for (Property x : this.properties) p.add(x.toNbt());
			n.put("Properties", p);
			NbtList r = new NbtList();
			for (UUID u : this.retinue) r.add(NbtString.of(u.toString()));
			n.put("Retinue", r);
			if (this.spouse != null) n.putUuid("Spouse", this.spouse);
			n.putString("SpouseName", this.spouseName);
			NbtList ch = new NbtList();
			for (UUID u : this.children) ch.add(NbtString.of(u.toString()));
			n.put("Children", ch);
			n.putString("Religion", this.religion);
			n.putLong("Pilgrimage", this.lastPilgrimageDay);
			n.putLong("Paid", this.lastPaidDay);
			return n;
		}

		static Profile fromNbt(NbtCompound n) {
			Profile p = new Profile();
			p.name = n.getString("Name");
			p.bank = n.getInt("Bank");
			NbtCompound st = n.getCompound("Stats");
			for (String k : st.getKeys()) p.stats.put(k, st.getInt(k));
			readStrings(n.getList("Titles", NbtElement.STRING_TYPE), p.titles);
			readStrings(n.getList("Deeds", NbtElement.STRING_TYPE), p.deeds);
			NbtList c = n.getList("Contracts", NbtElement.COMPOUND_TYPE);
			for (int i = 0; i < c.size(); i++) p.contracts.add(Contract.fromNbt(c.getCompound(i)));
			NbtList pr = n.getList("Properties", NbtElement.COMPOUND_TYPE);
			for (int i = 0; i < pr.size(); i++) p.properties.add(Property.fromNbt(pr.getCompound(i)));
			for (String s : strings(n.getList("Retinue", NbtElement.STRING_TYPE))) p.retinue.add(UUID.fromString(s));
			if (n.containsUuid("Spouse")) p.spouse = n.getUuid("Spouse");
			p.spouseName = n.getString("SpouseName");
			for (String s : strings(n.getList("Children", NbtElement.STRING_TYPE))) p.children.add(UUID.fromString(s));
			p.religion = n.getString("Religion");
			p.lastPilgrimageDay = n.contains("Pilgrimage") ? n.getLong("Pilgrimage") : -1;
			p.lastPaidDay = n.contains("Paid") ? n.getLong("Paid") : -1;
			return p;
		}
	}

	private static NbtList strings(List<String> list) {
		NbtList l = new NbtList();
		for (String s : list) l.add(NbtString.of(s));
		return l;
	}

	private static List<String> strings(NbtList l) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < l.size(); i++) out.add(l.getString(i));
		return out;
	}

	private static void readStrings(NbtList l, List<String> into) {
		for (int i = 0; i < l.size(); i++) into.add(l.getString(i));
	}

	public final Map<UUID, Profile> profiles = new LinkedHashMap<>();

	/** Contracts each town offers today (town id → offers), regenerated daily. */
	public final Map<Integer, List<Contract>> boards = new HashMap<>();
	public long boardsDay = -1;

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		NbtList list = new NbtList();
		for (Map.Entry<UUID, Profile> e : this.profiles.entrySet()) {
			NbtCompound n = e.getValue().toNbt();
			n.putUuid("Uuid", e.getKey());
			list.add(n);
		}
		nbt.put("Players", list);
		return nbt;
	}

	public static PlayerData fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		PlayerData d = new PlayerData();
		NbtList list = nbt.getList("Players", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < list.size(); i++) {
			NbtCompound n = list.getCompound(i);
			d.profiles.put(n.getUuid("Uuid"), Profile.fromNbt(n));
		}
		return d;
	}
}
