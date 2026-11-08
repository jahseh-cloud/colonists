package net.siegeworks.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.siegeworks.Siegeworks;

/** The two hands of a siege crew: the release (fire) and the winch (load / crank). */
public final class EnginePayloads {
	private EnginePayloads() {}

	public record Fire() implements CustomPayload {
		public static final CustomPayload.Id<Fire> ID = new CustomPayload.Id<>(Siegeworks.id("fire"));
		public static final PacketCodec<RegistryByteBuf, Fire> CODEC = PacketCodec.unit(new Fire());

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	public record Crank() implements CustomPayload {
		public static final CustomPayload.Id<Crank> ID = new CustomPayload.Id<>(Siegeworks.id("crank"));
		public static final PacketCodec<RegistryByteBuf, Crank> CODEC = PacketCodec.unit(new Crank());

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	public static void register() {
		PayloadTypeRegistry.playC2S().register(Fire.ID, Fire.CODEC);
		PayloadTypeRegistry.playC2S().register(Crank.ID, Crank.CODEC);
	}
}
