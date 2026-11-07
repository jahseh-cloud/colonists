package net.colonists.atlas;

import net.colonists.ColonistsMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

import java.util.ArrayList;
import java.util.List;

/** Network messages for the political atlas. */
public final class AtlasPayloads {
	private AtlasPayloads() {}

	/** Client → server: send me the map data (kind 0), or a terrain tile (kind 1). */
	public record Request(int kind, int tx, int tz, int cell) implements CustomPayload {
		public static final Id<Request> ID = new Id<>(ColonistsMod.id("atlas_request"));
		public static final PacketCodec<RegistryByteBuf, Request> CODEC = PacketCodec.of(
				(p, buf) -> {
					buf.writeVarInt(p.kind);
					buf.writeInt(p.tx);
					buf.writeInt(p.tz);
					buf.writeVarInt(p.cell);
				},
				buf -> new Request(buf.readVarInt(), buf.readInt(), buf.readInt(), buf.readVarInt()));

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/** Client → server: an order from the ruler (or a bid for power). */
	public record Order(String order, int a, int b, int x, int z) implements CustomPayload {
		public static final Id<Order> ID = new Id<>(ColonistsMod.id("atlas_order"));
		public static final PacketCodec<RegistryByteBuf, Order> CODEC = PacketCodec.of(
				(p, buf) -> {
					buf.writeString(p.order);
					buf.writeInt(p.a);
					buf.writeInt(p.b);
					buf.writeInt(p.x);
					buf.writeInt(p.z);
				},
				buf -> new Order(buf.readString(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt()));

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/** Server → client: open the atlas (from the item). */
	public record Open() implements CustomPayload {
		public static final Id<Open> ID = new Id<>(ColonistsMod.id("atlas_open"));
		public static final PacketCodec<RegistryByteBuf, Open> CODEC = PacketCodec.of((p, buf) -> {}, buf -> new Open());

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/** Server → client: one square of terrain, 32 x 32 cells of `cell` blocks, as terrain kinds. */
	public record Tile(int tx, int tz, int cell, byte[] data) implements CustomPayload {
		public static final int SIZE = 32;
		public static final Id<Tile> ID = new Id<>(ColonistsMod.id("atlas_tile"));
		public static final PacketCodec<RegistryByteBuf, Tile> CODEC = PacketCodec.of(
				(p, buf) -> {
					buf.writeInt(p.tx);
					buf.writeInt(p.tz);
					buf.writeVarInt(p.cell);
					buf.writeByteArray(p.data);
				},
				buf -> new Tile(buf.readInt(), buf.readInt(), buf.readVarInt(), buf.readByteArray()));

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	public record FactionInfo(String id, String name, int color, String title, String government, String ruler, boolean playerRuler,
							  int relation, boolean atWar, int approval, int army, int court, int warGoal, int laws, boolean slavery,
							  int treasury, int people, String overlord, boolean mine) {
	}

	public record TownInfo(int id, String faction, String name, String rank, int x, int z, int minX, int minZ, int maxX, int maxZ,
						   int population, int loyalty, int treasury, int guards, boolean besieged, boolean capital,
						   int taxRate, int garrison, boolean autonomy, String priority) {
	}

	/** Server → client: the whole political picture, as this player may see it. */
	public record Data(String myFaction, boolean ruler, boolean member, int coupChance, boolean canStand, boolean heir,
					   int armySupportWar, List<FactionInfo> factions, List<TownInfo> towns) implements CustomPayload {
		public static final Id<Data> ID = new Id<>(ColonistsMod.id("atlas_data"));
		public static final PacketCodec<RegistryByteBuf, Data> CODEC = PacketCodec.of(Data::write, Data::read);

		private void write(RegistryByteBuf buf) {
			buf.writeString(this.myFaction);
			buf.writeBoolean(this.ruler);
			buf.writeBoolean(this.member);
			buf.writeInt(this.coupChance);
			buf.writeBoolean(this.canStand);
			buf.writeBoolean(this.heir);
			buf.writeVarInt(this.armySupportWar);
			buf.writeVarInt(this.factions.size());
			for (FactionInfo f : this.factions) {
				buf.writeString(f.id());
				buf.writeString(f.name());
				buf.writeInt(f.color());
				buf.writeString(f.title());
				buf.writeString(f.government());
				buf.writeString(f.ruler());
				buf.writeBoolean(f.playerRuler());
				buf.writeInt(f.relation());
				buf.writeBoolean(f.atWar());
				buf.writeVarInt(f.approval());
				buf.writeVarInt(f.army());
				buf.writeVarInt(f.court());
				buf.writeInt(f.warGoal());
				buf.writeVarInt(f.laws());
				buf.writeBoolean(f.slavery());
				buf.writeVarInt(f.treasury());
				buf.writeVarInt(f.people());
				buf.writeString(f.overlord());
				buf.writeBoolean(f.mine());
			}
			buf.writeVarInt(this.towns.size());
			for (TownInfo t : this.towns) {
				buf.writeVarInt(t.id());
				buf.writeString(t.faction());
				buf.writeString(t.name());
				buf.writeString(t.rank());
				buf.writeInt(t.x());
				buf.writeInt(t.z());
				buf.writeInt(t.minX());
				buf.writeInt(t.minZ());
				buf.writeInt(t.maxX());
				buf.writeInt(t.maxZ());
				buf.writeVarInt(t.population());
				buf.writeVarInt(t.loyalty());
				buf.writeVarInt(t.treasury());
				buf.writeVarInt(t.guards());
				buf.writeBoolean(t.besieged());
				buf.writeBoolean(t.capital());
				buf.writeInt(t.taxRate());
				buf.writeInt(t.garrison());
				buf.writeBoolean(t.autonomy());
				buf.writeString(t.priority());
			}
		}

		private static Data read(RegistryByteBuf buf) {
			String my = buf.readString();
			boolean ruler = buf.readBoolean(), member = buf.readBoolean();
			int coup = buf.readInt();
			boolean stand = buf.readBoolean(), heir = buf.readBoolean();
			int sup = buf.readVarInt();
			int nf = buf.readVarInt();
			List<FactionInfo> fs = new ArrayList<>(nf);
			for (int i = 0; i < nf; i++) {
				fs.add(new FactionInfo(buf.readString(), buf.readString(), buf.readInt(), buf.readString(), buf.readString(), buf.readString(),
						buf.readBoolean(), buf.readInt(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readInt(),
						buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readString(), buf.readBoolean()));
			}
			int nt = buf.readVarInt();
			List<TownInfo> ts = new ArrayList<>(nt);
			for (int i = 0; i < nt; i++) {
				ts.add(new TownInfo(buf.readVarInt(), buf.readString(), buf.readString(), buf.readString(), buf.readInt(), buf.readInt(),
						buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
						buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readInt(), buf.readInt(), buf.readBoolean(), buf.readString()));
			}
			return new Data(my, ruler, member, coup, stand, heir, sup, fs, ts);
		}

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}
}
