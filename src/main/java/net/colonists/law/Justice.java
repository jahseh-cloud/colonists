package net.colonists.law;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything the courts and markets remember: who is wanted where, who is in prison, who is enslaved to whom,
 * open slave auctions, and players' shops.
 */
public class Justice extends PersistentState {
	private static final Type<Justice> TYPE = new Type<>(Justice::new, Justice::fromNbt, null);

	public static Justice get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "colonists_justice");
	}

	// ---------------------------------------------------------------- records

	/** A criminal record in one faction. */
	public static class Record {
		public String name = "";
		public boolean player;
		public int heat;
		public final List<String> charges = new ArrayList<>();
		public long lastSeen;
		public long lastCrimeTick;
		public String lastCrime = "";
		/** Banished: attacked on sight inside the faction's towns. */
		public boolean exiled;
		public long exiledUntil;

		NbtCompound toNbt() {
			NbtCompound n = new NbtCompound();
			n.putString("Name", this.name);
			n.putBoolean("Player", this.player);
			n.putInt("Heat", this.heat);
			NbtList c = new NbtList();
			for (String s : this.charges) c.add(NbtString.of(s));
			n.put("Charges", c);
			n.putLong("Seen", this.lastSeen);
			n.putBoolean("Exiled", this.exiled);
			n.putLong("ExiledUntil", this.exiledUntil);
			return n;
		}

		static Record fromNbt(NbtCompound n) {
			Record r = new Record();
			r.name = n.getString("Name");
			r.player = n.getBoolean("Player");
			r.heat = n.getInt("Heat");
			NbtList c = n.getList("Charges", NbtElement.STRING_TYPE);
			for (int i = 0; i < c.size(); i++) r.charges.add(c.getString(i));
			r.lastSeen = n.getLong("Seen");
			r.exiled = n.getBoolean("Exiled");
			r.exiledUntil = n.getLong("ExiledUntil");
			return r;
		}
	}

	/** faction id → (person → record) */
	public final Map<String, Map<UUID, Record>> records = new HashMap<>();

	@Nullable
	public Record record(String faction, UUID who) {
		Map<UUID, Record> m = this.records.get(faction);
		return m == null ? null : m.get(who);
	}

	public Record recordFor(String faction, UUID who) {
		return this.records.computeIfAbsent(faction, k -> new HashMap<>()).computeIfAbsent(who, k -> new Record());
	}

	public int heat(String faction, UUID who) {
		Record r = record(faction, who);
		return r == null ? 0 : r.heat;
	}

	public boolean exiled(String faction, UUID who) {
		Record r = record(faction, who);
		return r != null && r.exiled;
	}

	public void clear(String faction, UUID who) {
		Map<UUID, Record> m = this.records.get(faction);
		if (m != null) m.remove(who);
		markDirty();
	}

	// ---------------------------------------------------------------- prisoners

	public enum Sentence {
		JAIL("prison"), CAMP("the work camp"), DEATH("death by hanging"), SLAVE("slavery");

		public final String label;

		Sentence(String label) {
			this.label = label;
		}
	}

	public static class Prisoner {
		public UUID who;
		public boolean player;
		public String name = "";
		public String faction = "";
		public int settlement = -1;
		public Sentence sentence = Sentence.JAIL;
		public long releaseTime;
		public int quota;
		public int delivered;
		public BlockPos place = BlockPos.ORIGIN;
		public long executeAt;

		NbtCompound toNbt() {
			NbtCompound n = new NbtCompound();
			n.putUuid("Who", this.who);
			n.putBoolean("Player", this.player);
			n.putString("Name", this.name);
			n.putString("Faction", this.faction);
			n.putInt("Settlement", this.settlement);
			n.putString("Sentence", this.sentence.name());
			n.putLong("Release", this.releaseTime);
			n.putInt("Quota", this.quota);
			n.putInt("Delivered", this.delivered);
			n.putLong("Place", this.place.asLong());
			n.putLong("Execute", this.executeAt);
			return n;
		}

		static Prisoner fromNbt(NbtCompound n) {
			Prisoner p = new Prisoner();
			p.who = n.getUuid("Who");
			p.player = n.getBoolean("Player");
			p.name = n.getString("Name");
			p.faction = n.getString("Faction");
			p.settlement = n.getInt("Settlement");
			try {
				p.sentence = Sentence.valueOf(n.getString("Sentence"));
			} catch (IllegalArgumentException e) {
				p.sentence = Sentence.JAIL;
			}
			p.releaseTime = n.getLong("Release");
			p.quota = n.getInt("Quota");
			p.delivered = n.getInt("Delivered");
			p.place = BlockPos.fromLong(n.getLong("Place"));
			p.executeAt = n.getLong("Execute");
			return p;
		}
	}

	public final Map<UUID, Prisoner> prisoners = new LinkedHashMap<>();

	// ---------------------------------------------------------------- slaves

	public static class Slave {
		public UUID who;
		public boolean player;
		public String name = "";
		public String faction = "";
		public int settlement = -1;
		/** Owner (a colonist or a player), or null when owned by the town itself. */
		@Nullable public UUID owner;
		public boolean ownerPlayer;
		public String ownerName = "";
		/** Centre of the estate the slave is bound to. */
		public BlockPos estate = BlockPos.ORIGIN;
		public int price;
		/** Today's task: item id, how many, how many handed over, which day it was set. */
		public String taskItem = "";
		public int taskCount;
		public int taskDone;
		public long taskDay = -1;
		public int missed;
		public boolean runaway;
		public long warned;

		NbtCompound toNbt() {
			NbtCompound n = new NbtCompound();
			n.putUuid("Who", this.who);
			n.putBoolean("Player", this.player);
			n.putString("Name", this.name);
			n.putString("Faction", this.faction);
			n.putInt("Settlement", this.settlement);
			if (this.owner != null) n.putUuid("Owner", this.owner);
			n.putBoolean("OwnerPlayer", this.ownerPlayer);
			n.putString("OwnerName", this.ownerName);
			n.putLong("Estate", this.estate.asLong());
			n.putInt("Price", this.price);
			n.putString("TaskItem", this.taskItem);
			n.putInt("TaskCount", this.taskCount);
			n.putInt("TaskDone", this.taskDone);
			n.putLong("TaskDay", this.taskDay);
			n.putInt("Missed", this.missed);
			n.putBoolean("Runaway", this.runaway);
			return n;
		}

		static Slave fromNbt(NbtCompound n) {
			Slave s = new Slave();
			s.who = n.getUuid("Who");
			s.player = n.getBoolean("Player");
			s.name = n.getString("Name");
			s.faction = n.getString("Faction");
			s.settlement = n.getInt("Settlement");
			if (n.containsUuid("Owner")) s.owner = n.getUuid("Owner");
			s.ownerPlayer = n.getBoolean("OwnerPlayer");
			s.ownerName = n.getString("OwnerName");
			s.estate = BlockPos.fromLong(n.getLong("Estate"));
			s.price = n.getInt("Price");
			s.taskItem = n.getString("TaskItem");
			s.taskCount = n.getInt("TaskCount");
			s.taskDone = n.getInt("TaskDone");
			s.taskDay = n.getLong("TaskDay");
			s.missed = n.getInt("Missed");
			s.runaway = n.getBoolean("Runaway");
			return s;
		}
	}

	public final Map<UUID, Slave> slaves = new LinkedHashMap<>();

	public int slavesOwnedBy(UUID owner) {
		int n = 0;
		for (Slave s : this.slaves.values()) if (owner.equals(s.owner)) n++;
		return n;
	}

	// ---------------------------------------------------------------- auctions

	public static class Auction {
		public UUID lot;
		public boolean player;
		public String name = "";
		public String faction = "";
		public int settlement = -1;
		public BlockPos where = BlockPos.ORIGIN;
		public long closes;
		public int minimum;
		public int bestBid;
		@Nullable public UUID bidder;
		public boolean bidderPlayer;
		public String bidderName = "";

		NbtCompound toNbt() {
			NbtCompound n = new NbtCompound();
			n.putUuid("Lot", this.lot);
			n.putBoolean("Player", this.player);
			n.putString("Name", this.name);
			n.putString("Faction", this.faction);
			n.putInt("Settlement", this.settlement);
			n.putLong("Where", this.where.asLong());
			n.putLong("Closes", this.closes);
			n.putInt("Min", this.minimum);
			n.putInt("Bid", this.bestBid);
			if (this.bidder != null) n.putUuid("Bidder", this.bidder);
			n.putBoolean("BidderPlayer", this.bidderPlayer);
			n.putString("BidderName", this.bidderName);
			return n;
		}

		static Auction fromNbt(NbtCompound n) {
			Auction a = new Auction();
			a.lot = n.getUuid("Lot");
			a.player = n.getBoolean("Player");
			a.name = n.getString("Name");
			a.faction = n.getString("Faction");
			a.settlement = n.getInt("Settlement");
			a.where = BlockPos.fromLong(n.getLong("Where"));
			a.closes = n.getLong("Closes");
			a.minimum = n.getInt("Min");
			a.bestBid = n.getInt("Bid");
			if (n.containsUuid("Bidder")) a.bidder = n.getUuid("Bidder");
			a.bidderPlayer = n.getBoolean("BidderPlayer");
			a.bidderName = n.getString("BidderName");
			return a;
		}
	}

	public final Map<UUID, Auction> auctions = new LinkedHashMap<>();

	// ---------------------------------------------------------------- player shops

	public static class Shop {
		public BlockPos pos = BlockPos.ORIGIN;
		public UUID owner;
		public String ownerName = "";
		public int settlement = -1;
		/** Emeralds asked per batch of items. */
		public int price;
		public int batch;
		public int earned;

		NbtCompound toNbt() {
			NbtCompound n = new NbtCompound();
			n.putLong("Pos", this.pos.asLong());
			n.putUuid("Owner", this.owner);
			n.putString("OwnerName", this.ownerName);
			n.putInt("Settlement", this.settlement);
			n.putInt("Price", this.price);
			n.putInt("Batch", this.batch);
			n.putInt("Earned", this.earned);
			return n;
		}

		static Shop fromNbt(NbtCompound n) {
			Shop s = new Shop();
			s.pos = BlockPos.fromLong(n.getLong("Pos"));
			s.owner = n.getUuid("Owner");
			s.ownerName = n.getString("OwnerName");
			s.settlement = n.getInt("Settlement");
			s.price = n.getInt("Price");
			s.batch = Math.max(1, n.getInt("Batch"));
			s.earned = n.getInt("Earned");
			return s;
		}
	}

	public final Map<BlockPos, Shop> shops = new LinkedHashMap<>();

	// ---------------------------------------------------------------- save & load

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		NbtList recs = new NbtList();
		for (Map.Entry<String, Map<UUID, Record>> f : this.records.entrySet()) {
			for (Map.Entry<UUID, Record> e : f.getValue().entrySet()) {
				NbtCompound n = e.getValue().toNbt();
				n.putString("Faction", f.getKey());
				n.putUuid("Who", e.getKey());
				recs.add(n);
			}
		}
		nbt.put("Records", recs);
		NbtList pr = new NbtList();
		for (Prisoner p : this.prisoners.values()) pr.add(p.toNbt());
		nbt.put("Prisoners", pr);
		NbtList sl = new NbtList();
		for (Slave s : this.slaves.values()) sl.add(s.toNbt());
		nbt.put("Slaves", sl);
		NbtList au = new NbtList();
		for (Auction a : this.auctions.values()) au.add(a.toNbt());
		nbt.put("Auctions", au);
		NbtList sh = new NbtList();
		for (Shop s : this.shops.values()) sh.add(s.toNbt());
		nbt.put("Shops", sh);
		return nbt;
	}

	public static Justice fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		Justice j = new Justice();
		NbtList recs = nbt.getList("Records", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < recs.size(); i++) {
			NbtCompound n = recs.getCompound(i);
			j.records.computeIfAbsent(n.getString("Faction"), k -> new HashMap<>()).put(n.getUuid("Who"), Record.fromNbt(n));
		}
		NbtList pr = nbt.getList("Prisoners", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < pr.size(); i++) {
			Prisoner p = Prisoner.fromNbt(pr.getCompound(i));
			j.prisoners.put(p.who, p);
		}
		NbtList sl = nbt.getList("Slaves", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < sl.size(); i++) {
			Slave s = Slave.fromNbt(sl.getCompound(i));
			j.slaves.put(s.who, s);
		}
		NbtList au = nbt.getList("Auctions", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < au.size(); i++) {
			Auction a = Auction.fromNbt(au.getCompound(i));
			j.auctions.put(a.lot, a);
		}
		NbtList sh = nbt.getList("Shops", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < sh.size(); i++) {
			Shop s = Shop.fromNbt(sh.getCompound(i));
			j.shops.put(s.pos, s);
		}
		return j;
	}
}
