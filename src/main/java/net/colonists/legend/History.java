package net.colonists.legend;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.PersistentState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The memory of the world: its religions, its legends, its prophecies, and a record of what happened. */
public class History extends PersistentState {
	private static final Type<History> TYPE = new Type<>(History::new, History::fromNbt, null);

	public static History get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "colonists_history");
	}

	// ---------------------------------------------------------------- religions

	public static class Religion {
		public String id = "";
		public String name = "";
		public int color = 0xFFFFFF;
		public final EnumSet<Tenet> tenets = EnumSet.noneOf(Tenet.class);
		/** The faith this one split from or reformed (or ""). */
		public String parent = "";
		public String founder = "";
		public long foundedDay;
		/** The living prophet (a colonist or a player), if any. */
		@Nullable public UUID prophet;
		public long nextProphetDay = -1;

		public boolean has(Tenet t) {
			return this.tenets.contains(t);
		}

		NbtCompound toNbt() {
			NbtCompound n = new NbtCompound();
			n.putString("Id", this.id);
			n.putString("Name", this.name);
			n.putInt("Color", this.color);
			NbtList t = new NbtList();
			for (Tenet x : this.tenets) t.add(NbtString.of(x.name()));
			n.put("Tenets", t);
			n.putString("Parent", this.parent);
			n.putString("Founder", this.founder);
			n.putLong("Founded", this.foundedDay);
			if (this.prophet != null) n.putUuid("Prophet", this.prophet);
			n.putLong("NextProphet", this.nextProphetDay);
			return n;
		}

		static Religion fromNbt(NbtCompound n) {
			Religion r = new Religion();
			r.id = n.getString("Id");
			r.name = n.getString("Name");
			r.color = n.getInt("Color");
			NbtList t = n.getList("Tenets", NbtElement.STRING_TYPE);
			for (int i = 0; i < t.size(); i++) {
				try {
					r.tenets.add(Tenet.valueOf(t.getString(i)));
				} catch (IllegalArgumentException ignored) {
				}
			}
			r.parent = n.getString("Parent");
			r.founder = n.getString("Founder");
			r.foundedDay = n.getLong("Founded");
			if (n.containsUuid("Prophet")) r.prophet = n.getUuid("Prophet");
			r.nextProphetDay = n.getLong("NextProphet");
			return r;
		}
	}

	public final Map<String, Religion> religions = new LinkedHashMap<>();

	@Nullable
	public Religion religion(@Nullable String id) {
		return id == null ? null : this.religions.get(id);
	}

	// ---------------------------------------------------------------- legends

	public enum Kind {
		ADVENTURER("Adventurer", 1), HERO("Hero", 2), EMPEROR("Emperor", 3),
		PROPHET("Prophet", 4), PROPHET_KING("Prophet King", 5), PROPHET_CONQUEROR("Prophet Conqueror", 6);

		public final String label;
		public final int rank;

		Kind(String label, int rank) {
			this.label = label;
			this.rank = rank;
		}

		public boolean prophet() {
			return this.rank >= 4;
		}
	}

	public static class Legend {
		public UUID who;
		public boolean player;
		public String name = "";
		public Kind kind = Kind.ADVENTURER;
		public String title = "";
		public String faction = "";
		public String factionName = "";
		public String religion = "";
		public boolean alive = true;
		public long since;
		public long died = -1;
		public final List<String> deeds = new ArrayList<>();

		public String styled() {
			return this.name + " " + this.title;
		}

		NbtCompound toNbt() {
			NbtCompound n = new NbtCompound();
			n.putUuid("Who", this.who);
			n.putBoolean("Player", this.player);
			n.putString("Name", this.name);
			n.putString("Kind", this.kind.name());
			n.putString("Title", this.title);
			n.putString("Faction", this.faction);
			n.putString("FactionName", this.factionName);
			n.putString("Religion", this.religion);
			n.putBoolean("Alive", this.alive);
			n.putLong("Since", this.since);
			n.putLong("Died", this.died);
			NbtList d = new NbtList();
			for (String s : this.deeds) d.add(NbtString.of(s));
			n.put("Deeds", d);
			return n;
		}

		static Legend fromNbt(NbtCompound n) {
			Legend l = new Legend();
			l.who = n.getUuid("Who");
			l.player = n.getBoolean("Player");
			l.name = n.getString("Name");
			try {
				l.kind = Kind.valueOf(n.getString("Kind"));
			} catch (IllegalArgumentException e) {
				l.kind = Kind.ADVENTURER;
			}
			l.title = n.getString("Title");
			l.faction = n.getString("Faction");
			l.factionName = n.getString("FactionName");
			l.religion = n.getString("Religion");
			l.alive = n.getBoolean("Alive");
			l.since = n.getLong("Since");
			l.died = n.getLong("Died");
			NbtList d = n.getList("Deeds", NbtElement.STRING_TYPE);
			for (int i = 0; i < d.size(); i++) l.deeds.add(d.getString(i));
			return l;
		}
	}

	public final Map<UUID, Legend> legends = new LinkedHashMap<>();

	@Nullable
	public Legend living(UUID who) {
		Legend l = this.legends.get(who);
		return l != null && l.alive ? l : null;
	}

	/** Deeds that build toward legend: [adventure, valour, rule, piety]. */
	public final Map<UUID, int[]> renown = new HashMap<>();

	public int[] renown(UUID who) {
		return this.renown.computeIfAbsent(who, k -> new int[4]);
	}

	public static final int ADVENTURE = 0, VALOUR = 1, RULE = 2, PIETY = 3;

	// ---------------------------------------------------------------- prophecies

	public static class Prophecy {
		public String religion = "";
		public String prophet = "";
		public String text = "";
		/** The settlement foretold to fall, and by when. */
		public int settlement = -1;
		public String owner = "";
		public long deadlineDay;

		NbtCompound toNbt() {
			NbtCompound n = new NbtCompound();
			n.putString("Religion", this.religion);
			n.putString("Prophet", this.prophet);
			n.putString("Text", this.text);
			n.putInt("Settlement", this.settlement);
			n.putString("Owner", this.owner);
			n.putLong("Deadline", this.deadlineDay);
			return n;
		}

		static Prophecy fromNbt(NbtCompound n) {
			Prophecy p = new Prophecy();
			p.religion = n.getString("Religion");
			p.prophet = n.getString("Prophet");
			p.text = n.getString("Text");
			p.settlement = n.getInt("Settlement");
			p.owner = n.getString("Owner");
			p.deadlineDay = n.getLong("Deadline");
			return p;
		}
	}

	public final List<Prophecy> prophecies = new ArrayList<>();

	// ---------------------------------------------------------------- events

	public enum EventType {
		FOUNDED, CONQUERED, CEDED, WAR, HOLY_WAR, PEACE, CROWNED, REBELLION, EXECUTION, LEGEND_RISES, LEGEND_DIES,
		PROPHET, NEW_FAITH, CONVERSION, PROPHECY_FULFILLED, MIRACLE, VILLAGE_SEIZED, EXODUS, FALL
	}

	/** One thing that happened, with who did it (faction ids so chroniclers can take sides). */
	public static class Event {
		public long day;
		public EventType type = EventType.FOUNDED;
		public String actor = "", actorName = "";
		public String target = "", targetName = "";
		public String person = "";
		/** The legend involved (so chroniclers can praise or curse them). */
		@Nullable public UUID legend;
		public String place = "";
		public String extra = "";
		public int x, z;

		NbtCompound toNbt() {
			NbtCompound n = new NbtCompound();
			n.putLong("Day", this.day);
			n.putString("Type", this.type.name());
			n.putString("Actor", this.actor);
			n.putString("ActorName", this.actorName);
			n.putString("Target", this.target);
			n.putString("TargetName", this.targetName);
			n.putString("Person", this.person);
			if (this.legend != null) n.putUuid("Legend", this.legend);
			n.putString("Place", this.place);
			n.putString("Extra", this.extra);
			n.putInt("X", this.x);
			n.putInt("Z", this.z);
			return n;
		}

		static Event fromNbt(NbtCompound n) {
			Event e = new Event();
			e.day = n.getLong("Day");
			try {
				e.type = EventType.valueOf(n.getString("Type"));
			} catch (IllegalArgumentException ex) {
				e.type = EventType.FOUNDED;
			}
			e.actor = n.getString("Actor");
			e.actorName = n.getString("ActorName");
			e.target = n.getString("Target");
			e.targetName = n.getString("TargetName");
			e.person = n.getString("Person");
			if (n.containsUuid("Legend")) e.legend = n.getUuid("Legend");
			e.place = n.getString("Place");
			e.extra = n.getString("Extra");
			e.x = n.getInt("X");
			e.z = n.getInt("Z");
			return e;
		}
	}

	public final List<Event> events = new ArrayList<>();
	/** Running count of events ever recorded (chronicles remember how far they have written). */
	public long eventCount;

	public Event log(long day, EventType type) {
		Event e = new Event();
		e.day = day;
		e.type = type;
		this.events.add(e);
		this.eventCount++;
		if (this.events.size() > 600) this.events.remove(0);
		this.markDirty();
		return e;
	}

	/** Event index (counting from the first ever) of the oldest event still remembered. */
	public long firstIndex() {
		return this.eventCount - this.events.size();
	}

	// ---------------------------------------------------------------- religion names

	private static final String[] FAITH_PREFIX = {"The Way of the", "The Church of the", "The Faith of the", "The Order of the",
			"The Covenant of the", "The Path of the", "The Children of the", "The Keepers of the"};
	private static final String[] FAITH_NOUN = {"Sun", "Moon", "Deep Stone", "Ember", "Old Oak", "Tide", "Iron Star", "Ashen Flame",
			"Silent Hill", "First Bell", "Green Root", "Long Night", "White Stag", "Burning Wheel", "Hollow Mountain", "Rising Sea"};

	public static String faithName(Random random) {
		return FAITH_PREFIX[random.nextInt(FAITH_PREFIX.length)] + " " + FAITH_NOUN[random.nextInt(FAITH_NOUN.length)];
	}

	// ---------------------------------------------------------------- save & load

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		NbtList rel = new NbtList();
		for (Religion r : this.religions.values()) rel.add(r.toNbt());
		nbt.put("Religions", rel);
		NbtList leg = new NbtList();
		for (Legend l : this.legends.values()) leg.add(l.toNbt());
		nbt.put("Legends", leg);
		NbtList ren = new NbtList();
		for (Map.Entry<UUID, int[]> e : this.renown.entrySet()) {
			NbtCompound n = new NbtCompound();
			n.putUuid("Who", e.getKey());
			n.putIntArray("R", e.getValue());
			ren.add(n);
		}
		nbt.put("Renown", ren);
		NbtList pro = new NbtList();
		for (Prophecy p : this.prophecies) pro.add(p.toNbt());
		nbt.put("Prophecies", pro);
		NbtList ev = new NbtList();
		for (Event e : this.events) ev.add(e.toNbt());
		nbt.put("Events", ev);
		nbt.putLong("EventCount", this.eventCount);
		return nbt;
	}

	public static History fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		History h = new History();
		NbtList rel = nbt.getList("Religions", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < rel.size(); i++) {
			Religion r = Religion.fromNbt(rel.getCompound(i));
			h.religions.put(r.id, r);
		}
		NbtList leg = nbt.getList("Legends", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < leg.size(); i++) {
			Legend l = Legend.fromNbt(leg.getCompound(i));
			h.legends.put(l.who, l);
		}
		NbtList ren = nbt.getList("Renown", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < ren.size(); i++) {
			NbtCompound n = ren.getCompound(i);
			int[] r = n.getIntArray("R");
			if (r.length == 4) h.renown.put(n.getUuid("Who"), r);
		}
		NbtList pro = nbt.getList("Prophecies", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < pro.size(); i++) h.prophecies.add(Prophecy.fromNbt(pro.getCompound(i)));
		NbtList ev = nbt.getList("Events", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < ev.size(); i++) h.events.add(Event.fromNbt(ev.getCompound(i)));
		h.eventCount = nbt.contains("EventCount") ? nbt.getLong("EventCount") : h.events.size();
		return h;
	}
}
