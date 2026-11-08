package net.colonists.farm;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A manor: a lord's farming estate on the outskirts of a town. It owns its own land (a growing patchwork of large
 * fields), is worked by laborers, and feeds the town it belongs to.
 */
public class Manor {
	public int id;
	public String name = "";
	@Nullable public UUID lord;
	public String lordName = "";
	/** The manor house lot (a town lot of type "manor"), once chosen. */
	@Nullable public BlockPos seat;
	public final List<Field> fields = new ArrayList<>();
	public final Set<UUID> laborers = new LinkedHashSet<>();
	/** Crops brought in since the last sale to the town, and in all. */
	public int harvested, harvestedTotal;
	public long founded, lastPayday = -1;
	/** 0-100: what this lord thinks of the crown (the landed nobility's mood). */
	public int favour = 60;

	/** One field: a rectangle of land given over to one crop. */
	public static class Field {
		public int x0, z0, x1, z1;
		public String crop = "wheat";

		public Field() {}

		public Field(int x0, int z0, int x1, int z1, String crop) {
			this.x0 = Math.min(x0, x1);
			this.z0 = Math.min(z0, z1);
			this.x1 = Math.max(x0, x1);
			this.z1 = Math.max(z0, z1);
			this.crop = crop;
		}

		public int width() {
			return x1 - x0 + 1;
		}

		public int depth() {
			return z1 - z0 + 1;
		}

		/** "Smith Manor": named for the family that holds it. */
	public String title() {
		if (lordName.isEmpty()) return name.isEmpty() ? "A fallow manor" : name;
		String[] parts = lordName.trim().split(" ");
		return parts[parts.length - 1] + " Manor";
	}

	public int area() {
			return width() * depth();
		}

		public boolean contains(int x, int z, int margin) {
			return x >= x0 - margin && x <= x1 + margin && z >= z0 - margin && z <= z1 + margin;
		}

		public boolean overlaps(int ax0, int az0, int ax1, int az1, int margin) {
			return ax0 - margin <= x1 && ax1 + margin >= x0 && az0 - margin <= z1 && az1 + margin >= z0;
		}

		public BlockPos center() {
			return new BlockPos((x0 + x1) / 2, 0, (z0 + z1) / 2);
		}

		NbtCompound toNbt() {
			NbtCompound n = new NbtCompound();
			n.putIntArray("R", new int[]{x0, z0, x1, z1});
			n.putString("Crop", crop);
			return n;
		}

		static Field fromNbt(NbtCompound n) {
			int[] r = n.getIntArray("R");
			Field f = r.length == 4 ? new Field(r[0], r[1], r[2], r[3], n.getString("Crop")) : new Field();
			if (f.crop.isEmpty()) f.crop = "wheat";
			return f;
		}
	}

	public int area() {
		int a = 0;
		for (Field f : fields) a += f.area();
		return a;
	}

	@Nullable
	public Field fieldAt(int x, int z) {
		for (Field f : fields) if (f.contains(x, z, 0)) return f;
		return null;
	}

	public NbtCompound toNbt() {
		NbtCompound n = new NbtCompound();
		n.putInt("Id", id);
		n.putString("Name", name);
		if (lord != null) n.putUuid("Lord", lord);
		n.putString("LordName", lordName);
		if (seat != null) n.putLong("Seat", seat.asLong());
		NbtList fl = new NbtList();
		for (Field f : fields) fl.add(f.toNbt());
		n.put("Fields", fl);
		NbtList ls = new NbtList();
		for (UUID u : laborers) ls.add(NbtString.of(u.toString()));
		n.put("Laborers", ls);
		n.putInt("Harvested", harvested);
		n.putInt("HarvestedTotal", harvestedTotal);
		n.putLong("Founded", founded);
		n.putLong("Payday", lastPayday);
		n.putInt("Favour", favour);
		return n;
	}

	public static Manor fromNbt(NbtCompound n) {
		Manor m = new Manor();
		m.id = n.getInt("Id");
		m.name = n.getString("Name");
		if (n.containsUuid("Lord")) m.lord = n.getUuid("Lord");
		m.lordName = n.getString("LordName");
		if (n.contains("Seat")) m.seat = BlockPos.fromLong(n.getLong("Seat"));
		for (NbtElement e : n.getList("Fields", NbtElement.COMPOUND_TYPE)) m.fields.add(Field.fromNbt((NbtCompound) e));
		for (NbtElement e : n.getList("Laborers", NbtElement.STRING_TYPE)) {
			try {
				m.laborers.add(UUID.fromString(e.asString()));
			} catch (IllegalArgumentException ignored) {
			}
		}
		m.harvested = n.getInt("Harvested");
		m.harvestedTotal = n.getInt("HarvestedTotal");
		m.founded = n.getLong("Founded");
		m.lastPayday = n.contains("Payday") ? n.getLong("Payday") : -1;
		m.favour = n.contains("Favour") ? n.getInt("Favour") : 60;
		return m;
	}
}
