package net.siegeworks;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleFactory;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameRules;
import net.siegeworks.block.NaphthaFireBlock;
import net.siegeworks.entity.BallistaBoltEntity;
import net.siegeworks.entity.BallistaEntity;
import net.siegeworks.entity.BatteringRamEntity;
import net.siegeworks.entity.CartEntity;
import net.siegeworks.entity.CatapultEntity;
import net.siegeworks.entity.CatapultStoneEntity;
import net.siegeworks.entity.NaphthaPotEntity;
import net.siegeworks.entity.SiegeEngineEntity;
import net.siegeworks.entity.SiegeTowerEntity;
import net.siegeworks.item.EngineItem;
import net.siegeworks.item.NaphthaPotItem;
import net.siegeworks.net.EnginePayloads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Siegeworks implements ModInitializer {
	public static final String MOD_ID = "siegeworks";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}

	/** Siege weapons break blocks. Turn off for servers that want sieges without the rubble. */
	public static final GameRules.Key<GameRules.BooleanRule> BLOCK_DAMAGE =
			GameRuleRegistry.register("siegeworksBlockDamage", GameRules.Category.MISC, GameRuleFactory.createBooleanRule(true));

	// ---------------------------------------------------------------- blocks

	public static final Block NAPHTHA_FIRE = Registry.register(Registries.BLOCK, id("naphtha_fire"), new NaphthaFireBlock(
			AbstractBlock.Settings.create().replaceable().noCollision().breakInstantly().luminance(s -> 15)
					.sounds(BlockSoundGroup.WOOL).pistonBehavior(PistonBehavior.DESTROY).dropsNothing()));

	// ---------------------------------------------------------------- entities

	public static final EntityType<BallistaEntity> BALLISTA = engine("ballista", BallistaEntity::new, 1.5f, 1.3f);
	public static final EntityType<CatapultEntity> CATAPULT = engine("catapult", CatapultEntity::new, 2.2f, 2.0f);
	public static final EntityType<BatteringRamEntity> BATTERING_RAM = engine("battering_ram", BatteringRamEntity::new, 2.0f, 2.0f);
	public static final EntityType<SiegeTowerEntity> SIEGE_TOWER = engine("siege_tower", SiegeTowerEntity::new, 2.8f, 7.9f);
	public static final EntityType<CartEntity> CART = engine("cart", CartEntity::new, 1.6f, 1.1f);
	public static final EntityType<net.siegeworks.entity.SiphonEntity> SIPHON = engine("siphon", net.siegeworks.entity.SiphonEntity::new, 1.3f, 1.4f);

	public static final EntityType<BallistaBoltEntity> BALLISTA_BOLT = Registry.register(Registries.ENTITY_TYPE, id("ballista_bolt"),
			EntityType.Builder.<BallistaBoltEntity>create(BallistaBoltEntity::new, SpawnGroup.MISC).dimensions(0.5f, 0.5f)
					.maxTrackingRange(8).trackingTickInterval(20).build("ballista_bolt"));
	public static final EntityType<CatapultStoneEntity> CATAPULT_STONE = Registry.register(Registries.ENTITY_TYPE, id("catapult_stone"),
			EntityType.Builder.<CatapultStoneEntity>create(CatapultStoneEntity::new, SpawnGroup.MISC).dimensions(0.6f, 0.6f)
					.maxTrackingRange(10).trackingTickInterval(5).build("catapult_stone"));
	public static final EntityType<NaphthaPotEntity> NAPHTHA_POT_ENTITY = Registry.register(Registries.ENTITY_TYPE, id("naphtha_pot"),
			EntityType.Builder.<NaphthaPotEntity>create(NaphthaPotEntity::new, SpawnGroup.MISC).dimensions(0.25f, 0.25f)
					.maxTrackingRange(6).trackingTickInterval(10).build("naphtha_pot"));

	private static <T extends Entity> EntityType<T> engine(String name, EntityType.EntityFactory<T> factory, float w, float h) {
		return Registry.register(Registries.ENTITY_TYPE, id(name),
				EntityType.Builder.create(factory, SpawnGroup.MISC).dimensions(w, h).maxTrackingRange(10).trackingTickInterval(2).build(name));
	}

	// ---------------------------------------------------------------- items

	public static final Item BALLISTA_ITEM = item("ballista", new EngineItem(BALLISTA, new Item.Settings().maxCount(1)));
	public static final Item CATAPULT_ITEM = item("catapult", new EngineItem(CATAPULT, new Item.Settings().maxCount(1)));
	public static final Item BATTERING_RAM_ITEM = item("battering_ram", new EngineItem(BATTERING_RAM, new Item.Settings().maxCount(1)));
	public static final Item SIEGE_TOWER_ITEM = item("siege_tower", new EngineItem(SIEGE_TOWER, new Item.Settings().maxCount(1)));
	public static final Item CART_ITEM = item("cart", new EngineItem(CART, new Item.Settings().maxCount(1)));
	public static final Item SIPHON_ITEM = item("siphon", new EngineItem(SIPHON, new Item.Settings().maxCount(1)));
	public static final Item HAND_SIPHON = item("hand_siphon", new net.siegeworks.item.HandSiphonItem(new Item.Settings().maxDamage(250)));
	public static final Item BOLT = item("ballista_bolt", new Item(new Item.Settings().maxCount(16)));
	public static final Item STONE = item("catapult_stone", new Item(new Item.Settings().maxCount(16)));
	public static final Item NAPHTHA_POT = item("naphtha_pot", new NaphthaPotItem(new Item.Settings().maxCount(16)));

	private static Item item(String name, Item item) {
		return Registry.register(Registries.ITEM, id(name), item);
	}

	public static final ItemGroup GROUP = Registry.register(Registries.ITEM_GROUP, id("siegeworks"), FabricItemGroup.builder()
			.icon(() -> new ItemStack(CATAPULT_ITEM))
			.displayName(Text.translatable("itemGroup.siegeworks"))
			.entries((ctx, e) -> {
				for (Item i : new Item[]{BALLISTA_ITEM, CATAPULT_ITEM, BATTERING_RAM_ITEM, SIEGE_TOWER_ITEM, CART_ITEM, SIPHON_ITEM, HAND_SIPHON, BOLT, STONE, NAPHTHA_POT}) e.add(i);
			}).build());

	@Override
	public void onInitialize() {
		EnginePayloads.register();
		ServerPlayNetworking.registerGlobalReceiver(EnginePayloads.Fire.ID, (payload, ctx) -> {
			if (ctx.player().getVehicle() instanceof SiegeEngineEntity e) e.fireKey(ctx.player());
		});
		ServerPlayNetworking.registerGlobalReceiver(EnginePayloads.Crank.ID, (payload, ctx) -> {
			if (ctx.player().getVehicle() instanceof SiegeEngineEntity e) e.crankKey(ctx.player());
		});
		// Covered engines shelter their crews from arrows and bolts.
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
				!(entity.getVehicle() instanceof SiegeEngineEntity e && e.shelters() && source.isIn(DamageTypeTags.IS_PROJECTILE)));
		SelfTest.register();
		LOGGER.info("Siegeworks ready: ballistae, catapults, rams, towers, carts and Naphtha.");
	}
}
