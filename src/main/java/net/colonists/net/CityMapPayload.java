package net.colonists.net;

import net.colonists.ColonistsMod;
import net.colonists.faction.City;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Settlement;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Server → client: every city's name, colour, rank, ruler, centre and territory, plus how it feels about this player. */
public record CityMapPayload(List<CityInfo> cities) implements CustomPayload {
	public static final CustomPayload.Id<CityMapPayload> ID = new CustomPayload.Id<>(ColonistsMod.id("cities"));
	public static final PacketCodec<RegistryByteBuf, CityMapPayload> CODEC = PacketCodec.of(CityMapPayload::write, CityMapPayload::read);

	public record CityInfo(String id, String name, int color, String rank, String ruler, int centerX, int centerY, int centerZ,
						   int minX, int minZ, int maxX, int maxZ, int standing, boolean walled, int population,
						   String realm, boolean capital, int loyalty, boolean besieged) {
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}

	private void write(RegistryByteBuf buf) {
		buf.writeVarInt(this.cities.size());
		for (CityInfo c : this.cities) {
			buf.writeString(c.id());
			buf.writeString(c.name());
			buf.writeInt(c.color());
			buf.writeString(c.rank());
			buf.writeString(c.ruler());
			buf.writeInt(c.centerX());
			buf.writeInt(c.centerY());
			buf.writeInt(c.centerZ());
			buf.writeInt(c.minX());
			buf.writeInt(c.minZ());
			buf.writeInt(c.maxX());
			buf.writeInt(c.maxZ());
			buf.writeInt(c.standing());
			buf.writeBoolean(c.walled());
			buf.writeVarInt(c.population());
			buf.writeString(c.realm());
			buf.writeBoolean(c.capital());
			buf.writeVarInt(c.loyalty());
			buf.writeBoolean(c.besieged());
		}
	}

	private static CityMapPayload read(RegistryByteBuf buf) {
		int n = buf.readVarInt();
		List<CityInfo> list = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			list.add(new CityInfo(buf.readString(), buf.readString(), buf.readInt(), buf.readString(), buf.readString(),
					buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
					buf.readInt(), buf.readBoolean(), buf.readVarInt(),
					buf.readString(), buf.readBoolean(), buf.readVarInt(), buf.readBoolean()));
		}
		return new CityMapPayload(list);
	}

	/** Builds the map data as seen by one player. */
	public static CityMapPayload build(FactionManager fm, ServerPlayerEntity player) {
		List<CityInfo> list = new ArrayList<>();
		for (Faction realm : fm.all()) {
			for (Settlement f : realm.settlements.values()) {
				BlockPos c = f.cityCenter;
				if (c == null) continue;
				int minX, minZ, maxX, maxZ;
				if (f.wallPlanned) {
					minX = f.wallMinX;
					minZ = f.wallMinZ;
					maxX = f.wallMaxX;
					maxZ = f.wallMaxZ;
				} else {
					minX = c.getX() - City.PLAZA_HALF - 6;
					maxX = c.getX() + City.PLAZA_HALF + 6;
					minZ = c.getZ() - City.PLAZA_HALF - 6;
					maxZ = c.getZ() + City.PLAZA_HALF + 6;
					for (int[] r : net.colonists.faction.CityGrowth.footprints(f)) {
						minX = Math.min(minX, r[0] - 4);
						maxX = Math.max(maxX, r[2] + 4);
						minZ = Math.min(minZ, r[1] - 4);
						maxZ = Math.max(maxZ, r[3] + 4);
					}
				}
				Integer rgb = realm.color.getColorValue();
				String ruler = realm.rulerName.isEmpty() ? "" : realm.government.title + " " + realm.rulerName;
				list.add(new CityInfo("s" + f.id, f.name, rgb == null ? 0xFFFFFF : rgb, f.rank.label, ruler, c.getX(), c.getY(), c.getZ(),
						minX, minZ, maxX, maxZ, fm.standing(player, realm.id), f.wallBuilt, f.population(),
						realm.settlements.size() >= 2 ? realm.realmTitle() + " of the " + realm.name : realm.name, f.isCapital(), f.loyalty, f.occupation > 0));
			}
		}
		return new CityMapPayload(list);
	}
}
