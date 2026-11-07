package net.colonists.client;

import net.colonists.ColonistsMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class ColonistsClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(ColonistsMod.COLONIST, ColonistRenderer::new);
		EntityRendererRegistry.register(ColonistsMod.CAPTIVE, CaptiveRenderer::new);
	}
}
