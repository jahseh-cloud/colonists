package net.colonists.net;

import net.colonists.ColonistsMod;
import net.colonists.faction.City;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
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
						   int minX, int minZ, int maxX, int maxZ, int standing, boolean walled) {
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
		}
	}

	private static CityMapPayload read(RegistryByteBuf buf) {
		int n = buf.readVarInt();
		List<CityInfo> list = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			list.add(new CityInfo(buf.readString(), buf.readString(), buf.readInt(), buf.readString(), buf.readString(),
					buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
					buf.readInt(), buf.readBoolean()));
		}
		return new CityMapPayload(list);
	}

	/** Builds the map data as seen by one player. */
	public static CityMapPayload build(FactionManager fm, ServerPlayerEntity player) {
		List<CityInfo> list = new ArrayList<>();
		for (Faction f : fm.all()) {
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
				List<BlockPos> claimed = new ArrayList<>(f.homes.keySet());
				claimed.addAll(f.farms);
				for (BlockPos o : claimed) {
					minX = Math.min(minX, o.getX() - 4);
					maxX = Math.max(maxX, o.getX() + City.LOT_WIDTH + 3);
					minZ = Math.min(minZ, o.getZ() - 6);
					maxZ = Math.max(maxZ, o.getZ() + 8);
				}
			}
			Integer rgb = f.color.getColorValue();
			String ruler = f.rulerName.isEmpty() ? "" : f.government.title + " " + f.rulerName;
			list.add(new CityInfo(f.id, f.name, rgb == null ? 0xFFFFFF : rgb, f.rank.label, ruler, c.getX(), c.getY(), c.getZ(),
					minX, minZ, maxX, maxZ, fm.standing(player, f.id), f.wallBuilt));
		}
		return new CityMapPayload(list);
	}
}
