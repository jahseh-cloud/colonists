package net.colonists;

import net.colonists.command.ColonyCommand;
import net.colonists.entity.CaptiveEntity;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.FactionManager;
import net.fabricmc.api.ModInitializer;
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
	public static final GameRules.Key<GameRules.IntRule> MAX_PER_FACTION =
			GameRuleRegistry.register("colonistsMaxPerFaction", GameRules.Category.SPAWNING, GameRuleFactory.createIntRule(30, 1, 200));

	public static EntityType<ColonistEntity> COLONIST;
	public static EntityType<CaptiveEntity> CAPTIVE;
	public static Item COLONIST_SPAWN_EGG;

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

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> ColonyCommand.register(dispatcher));

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTicks() % 1200 == 0) {
				FactionManager.get(server).tickDiplomacy(server);
			}
		});

		LOGGER.info("Colonists loaded: Steve has friends now.");
	}
}
