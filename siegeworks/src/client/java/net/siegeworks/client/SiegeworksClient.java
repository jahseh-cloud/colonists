package net.siegeworks.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.FlyingItemEntityRenderer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.siegeworks.Siegeworks;
import net.siegeworks.entity.SiegeEngineEntity;
import net.siegeworks.net.EnginePayloads;
import org.lwjgl.glfw.GLFW;

public class SiegeworksClient implements ClientModInitializer {
	public static KeyBinding FIRE, CRANK;

	@Override
	public void onInitializeClient() {
		FIRE = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.siegeworks.fire", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.categories.siegeworks"));
		CRANK = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.siegeworks.crank", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.siegeworks"));

		EntityRendererRegistry.register(Siegeworks.BALLISTA, EngineRenderers.Ballista::new);
		EntityRendererRegistry.register(Siegeworks.CATAPULT, EngineRenderers.Catapult::new);
		EntityRendererRegistry.register(Siegeworks.BATTERING_RAM, EngineRenderers.Ram::new);
		EntityRendererRegistry.register(Siegeworks.SIEGE_TOWER, EngineRenderers.Tower::new);
		EntityRendererRegistry.register(Siegeworks.CART, EngineRenderers.Cart::new);
		EntityRendererRegistry.register(Siegeworks.BALLISTA_BOLT, BoltRenderer::new);
		EntityRendererRegistry.register(Siegeworks.CATAPULT_STONE, ctx -> new FlyingItemEntityRenderer<>(ctx, 3.0f, false));
		EntityRendererRegistry.register(Siegeworks.NAPHTHA_POT_ENTITY, ctx -> new FlyingItemEntityRenderer<>(ctx, 1.0f, false));

		BlockRenderLayerMap.INSTANCE.putBlock(Siegeworks.NAPHTHA_FIRE, RenderLayer.getCutout());
		ColorProviderRegistry.BLOCK.register((state, world, pos, tint) -> 0xC8FF6E, Siegeworks.NAPHTHA_FIRE);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null) return;
			boolean riding = client.player.getVehicle() instanceof SiegeEngineEntity;
			while (FIRE.wasPressed()) if (riding) ClientPlayNetworking.send(new EnginePayloads.Fire());
			while (CRANK.wasPressed()) if (riding) ClientPlayNetworking.send(new EnginePayloads.Crank());
			if (riding && client.player.age % 5 == 0) {
				SiegeEngineEntity e = (SiegeEngineEntity) client.player.getVehicle();
				String[] hints = e.keyHints();
				MutableText line = e.status().copy();
				if (hints[0] != null) line.append(Text.literal("  [" + CRANK.getBoundKeyLocalizedText().getString() + "] " + hints[0]).formatted(Formatting.GRAY));
				if (hints[1] != null) line.append(Text.literal("  [" + FIRE.getBoundKeyLocalizedText().getString() + "] " + hints[1]).formatted(Formatting.GRAY));
				client.player.sendMessage(line, true);
			}
		});
	}
}
