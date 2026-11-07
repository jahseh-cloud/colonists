package net.colonists.client;

import net.colonists.ColonistsMod;
import net.colonists.client.map.ClientCities;
import net.colonists.client.map.XaeroCompat;
import net.colonists.net.CityMapPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class ColonistsClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(ColonistsMod.COLONIST, ColonistRenderer::new);
		EntityRendererRegistry.register(ColonistsMod.CAPTIVE, CaptiveRenderer::new);

		// City data for map mods.
		ClientPlayNetworking.registerGlobalReceiver(CityMapPayload.ID, (payload, context) -> ClientCities.update(payload.cities()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ClientCities::clear));
		net.colonists.client.atlas.AtlasClient.init();
		FabricLoader loader = FabricLoader.getInstance();
		if (loader.isModLoaded("xaerominimap") || loader.isModLoaded("xaerominimapfair")) XaeroCompat.init();
	}
}
