package net.colonists;

import net.colonists.command.ColonyCommand;
import net.colonists.entity.CaptiveEntity;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.FactionManager;
import net.colonists.net.CityMapPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleFactory;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ColonistsMod implements ModInitializer {
	public static final String MOD_ID = "colonists";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Game rules so server owners can tune how much freedom colonists get.
	public static final GameRules.Key<GameRules.BooleanRule> BREAK_BLOCKS =
			GameRuleRegistry.register("colonistsBreakBlocks", GameRules.Category.MOBS, GameRuleFactory.createBooleanRule(true));
	public static final GameRules.Key<GameRules.BooleanRule> BUILD =
			GameRuleRegistry.register("colonistsBuild", GameRules.Category.MOBS, GameRuleFactory.createBooleanRule(true));
	public static final GameRules.Key<GameRules.BooleanRule> CHATTER =
			GameRuleRegistry.register("colonistsChatter", GameRules.Category.CHAT, GameRuleFactory.createBooleanRule(true));
	public static final GameRules.Key<GameRules.BooleanRule> GROW =
			GameRuleRegistry.register("colonistsGrow", GameRules.Category.SPAWNING, GameRuleFactory.createBooleanRule(true));
	/** 0 = no limit: cities grow as big as births, housing and food allow. */
	public static final GameRules.Key<GameRules.IntRule> MAX_PER_FACTION =
			GameRuleRegistry.register("colonistsMaxPerFaction", GameRules.Category.SPAWNING, GameRuleFactory.createIntRule(0, 0, 100000));
	public static final GameRules.Key<GameRules.IntRule> LIFESPAN_MIN =
			GameRuleRegistry.register("colonistsLifespanMinDays", GameRules.Category.SPAWNING, GameRuleFactory.createIntRule(25, 2, 10000));
	public static final GameRules.Key<GameRules.IntRule> LIFESPAN_MAX =
			GameRuleRegistry.register("colonistsLifespanMaxDays", GameRules.Category.SPAWNING, GameRuleFactory.createIntRule(30, 2, 10000));
	/** Keep the heart of every city loaded so it lives on while no player is near. */
	public static final GameRules.Key<GameRules.BooleanRule> KEEP_LOADED =
			GameRuleRegistry.register("colonistsKeepCitiesLoaded", GameRules.Category.MISC, GameRuleFactory.createBooleanRule(true));

	/** Whether convicted players can be sold into slavery. */
	public static final GameRules.Key<GameRules.BooleanRule> ENSLAVE_PLAYERS =
			GameRuleRegistry.register("colonistsEnslavePlayers", GameRules.Category.PLAYER, GameRuleFactory.createBooleanRule(true));

	public static EntityType<ColonistEntity> COLONIST;
	public static EntityType<CaptiveEntity> CAPTIVE;
	public static Item COLONIST_SPAWN_EGG;
	public static Item POLITICAL_ATLAS;

	private static void sendCityMap(MinecraftServer server, ServerPlayerEntity player) {
		if (ServerPlayNetworking.canSend(player, CityMapPayload.ID)) {
			ServerPlayNetworking.send(player, CityMapPayload.build(FactionManager.get(server), player));
		}
	}

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		COLONIST = Registry.register(Registries.ENTITY_TYPE, id("colonist"),
				EntityType.Builder.<ColonistEntity>create(ColonistEntity::new, SpawnGroup.MISC)
						.dimensions(0.6f, 1.8f)
						.maxTrackingRange(10)
						.build("colonist"));
		FabricDefaultAttributeRegistry.register(COLONIST, ColonistEntity.createColonistAttributes());
		CAPTIVE = Registry.register(Registries.ENTITY_TYPE, id("captive"),
				EntityType.Builder.<CaptiveEntity>create(CaptiveEntity::new, SpawnGroup.MISC)
						.dimensions(0.6f, 1.95f)
						.maxTrackingRange(10)
						.build("captive"));
		FabricDefaultAttributeRegistry.register(CAPTIVE, CaptiveEntity.createCaptiveAttributes());

		COLONIST_SPAWN_EGG = Registry.register(Registries.ITEM, id("colonist_spawn_egg"),
				new SpawnEggItem(COLONIST, 0x2F7FB5, 0x6B4A2B, new Item.Settings()));
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.SPAWN_EGGS).register(entries -> entries.add(COLONIST_SPAWN_EGG));
		POLITICAL_ATLAS = Registry.register(Registries.ITEM, id("political_atlas"),
				new net.colonists.atlas.PoliticalAtlasItem(new Item.Settings().maxCount(1)));
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.add(POLITICAL_ATLAS));
		net.colonists.atlas.AtlasServer.register();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			ColonyCommand.register(dispatcher);
			net.colonists.command.LawCommand.register(dispatcher);
			net.colonists.command.LegendCommand.register(dispatcher);
			net.colonists.command.PlayerCommand.register(dispatcher);
		});

		// Map mods (JourneyMap, Xaero's) on the client get city data from here.
		PayloadTypeRegistry.playS2C().register(CityMapPayload.ID, CityMapPayload.CODEC);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> sendCityMap(server, handler.getPlayer())));

		net.colonists.world.WorldEdge.loadConfig();
		net.colonists.build.WonderBlueprints.init();
		net.colonists.law.Courts.register();
		SelfTest.register();
		net.colonists.law.Slavery.register();

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTicks() % 1200 == 0) {
				FactionManager.get(server).tickDiplomacy(server);
			}
			if (server.getTicks() % 200 == 100) net.colonists.faction.Villages.survey(server);
			if (server.getTicks() % 20 == 10) net.colonists.player.Contracts.tick(server);
			if (server.getTicks() % 1200 == 900) {
				try {
					net.colonists.farm.Manors.tick(server);
				} catch (RuntimeException e) {
					LOGGER.error("Colonists: the manors failed to tick", e);
				}
			}
			if (server.getTicks() % 1200 == 600) {
				net.colonists.player.Titles.tick(server);
				net.colonists.player.Household.tick(server);
			}
			if (server.getTicks() % 100 == 50) {
				net.colonists.legend.Legends.aura(server);
				net.colonists.legend.Wonders.aura(server);
			}
			if (server.getTicks() % 200 == 0) {
				for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) sendCityMap(server, player);
			}
		});

		LOGGER.info("Colonists loaded: Steve has friends now.");
	}
}
