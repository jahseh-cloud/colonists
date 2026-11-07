package net.colonists.client.atlas;

import net.colonists.atlas.AtlasPayloads;
import net.colonists.atlas.AtlasServer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Client side of the political atlas: the key, the latest map data, and terrain tiles turned into textures. */
public final class AtlasClient {
	private AtlasClient() {}

	@Nullable static AtlasPayloads.Data data;
	private static final Map<Long, Identifier> TILES = new HashMap<>();
	private static final Map<Long, NativeImageBackedTexture> TEXTURES = new HashMap<>();
	private static final Set<Long> REQUESTED = new HashSet<>();
	private static KeyBinding key;

	public static void init() {
		key = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.colonists.atlas", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.colonists"));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (key.wasPressed()) {
				if (client.player != null && client.currentScreen == null) client.setScreen(new AtlasScreen());
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(AtlasPayloads.Open.ID, (payload, context) -> context.client().execute(() -> {
			MinecraftClient mc = context.client();
			if (!(mc.currentScreen instanceof AtlasScreen)) mc.setScreen(new AtlasScreen());
		}));
		ClientPlayNetworking.registerGlobalReceiver(AtlasPayloads.Data.ID, (payload, context) -> context.client().execute(() -> {
			data = payload;
			if (context.client().currentScreen instanceof AtlasScreen s) s.onData();
		}));
		ClientPlayNetworking.registerGlobalReceiver(AtlasPayloads.Tile.ID, (payload, context) -> context.client().execute(() -> store(payload)));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(AtlasClient::clear));
	}

	static long key(int tx, int tz, int cell) {
		return ((long) tx & 0xFFFFFL) | (((long) tz & 0xFFFFFL) << 20) | ((long) cell << 40);
	}

	/** The texture for a tile, asking the server for it the first time. */
	@Nullable
	static Identifier tile(int tx, int tz, int cell) {
		long k = key(tx, tz, cell);
		Identifier id = TILES.get(k);
		if (id == null && REQUESTED.add(k)) ClientPlayNetworking.send(new AtlasPayloads.Request(1, tx, tz, cell));
		return id;
	}

	static void requestData() {
		ClientPlayNetworking.send(new AtlasPayloads.Request(0, 0, 0, 0));
	}

	private static void store(AtlasPayloads.Tile t) {
		int n = AtlasPayloads.Tile.SIZE;
		if (t.data().length != n * n) return;
		NativeImage img = new NativeImage(n, n, false);
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				int rgb = color(t.data()[i + j * n]);
				int abgr = 0xFF000000 | ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF);
				img.setColor(i, j, abgr);
			}
		}
		NativeImageBackedTexture tex = new NativeImageBackedTexture(img);
		Identifier id = MinecraftClient.getInstance().getTextureManager().registerDynamicTexture("colonists_atlas", tex);
		long k = key(t.tx(), t.tz(), t.cell());
		TILES.put(k, id);
		TEXTURES.put(k, tex);
	}

	/** Parchment-and-ink colours for each kind of land. */
	static int color(byte kind) {
		return switch (kind) {
			case AtlasServer.DEEP_OCEAN -> 0x6E8C9C;
			case AtlasServer.OCEAN, AtlasServer.RIVER -> 0x8FA9B2;
			case AtlasServer.FROZEN_OCEAN -> 0xA8BCC2;
			case AtlasServer.BEACH -> 0xE4D5A6;
			case AtlasServer.PLAINS -> 0xCFC190;
			case AtlasServer.FOREST -> 0xA2A676;
			case AtlasServer.TAIGA -> 0x929A74;
			case AtlasServer.SNOW -> 0xECE6D6;
			case AtlasServer.DESERT -> 0xE2CD95;
			case AtlasServer.BADLANDS -> 0xC79B6E;
			case AtlasServer.SAVANNA -> 0xCAB67C;
			case AtlasServer.JUNGLE -> 0x86996A;
			case AtlasServer.SWAMP -> 0x91936D;
			case AtlasServer.MOUNTAIN -> 0xAAA18E;
			case AtlasServer.MUSHROOM -> 0xB8A0A8;
			default -> 0xD9CBA3;
		};
	}

	static void clear() {
		data = null;
		for (Identifier id : TILES.values()) MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
		TEXTURES.clear();
		TILES.clear();
		REQUESTED.clear();
	}
}
