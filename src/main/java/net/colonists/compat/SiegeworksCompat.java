package net.colonists.compat;

import net.colonists.entity.ColonistEntity;
import net.colonists.faction.Faction;
import net.colonists.faction.Settlement;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Soft integration with the Siegeworks mod (if it is installed): armies on campaign bring up rams, catapults, ballistae and
 * siege towers, crew them, and work them against the town. Nothing here needs Siegeworks at compile time: engines are
 * found by entity id and told their target through their saved data ("AutoTarget"); any colonist standing by counts as crew.
 */
public final class SiegeworksCompat {
	private SiegeworksCompat() {}

	public static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("siegeworks");
	private static final String[] ENGINES = {"battering_ram", "battering_ram", "catapult", "catapult", "ballista", "siege_tower", "siphon"};

	static boolean isEngine(Entity e) {
		return Registries.ENTITY_TYPE.getId(e.getType()).getNamespace().equals("siegeworks") && e.getCommandTags().contains("colonists_siege");
	}

	/** The engines brought against this town. */
	public static List<Entity> enginesAt(ServerWorld world, Settlement s) {
		if (!PRESENT || s.cityCenter == null) return List.of();
		String tag = "colonists_siege_" + s.id;
		return world.getEntitiesByClass(Entity.class, new Box(s.cityCenter).expand(96, 32, 96), e -> e.isAlive() && isEngine(e) && e.getCommandTags().contains(tag));
	}

	/** An engine of this siege that no colonist is working yet, or null. */
	@Nullable
	public static Entity idleEngine(ServerWorld world, Settlement s, ColonistEntity soldier) {
		for (Entity e : enginesAt(world, s)) {
			if (e.squaredDistanceTo(soldier) > 48 * 48) continue;
			boolean manned = !world.getEntitiesByClass(ColonistEntity.class, e.getBoundingBox().expand(4), c -> c != soldier && c.isAlive()).isEmpty();
			if (!manned) return e;
		}
		return null;
	}

	/** The army brings up an engine beside this soldier. Returns it, or null. */
	@Nullable
	public static Entity bringEngine(ServerWorld world, Settlement s, Faction attacker, ColonistEntity soldier) {
		if (!PRESENT || s.cityCenter == null || enginesAt(world, s).size() >= 2) return null;
		String name = ENGINES[world.random.nextInt(ENGINES.length)];
		EntityType<?> type = Registries.ENTITY_TYPE.get(Identifier.of("siegeworks", name));
		Entity e = type.create(world);
		if (e == null) return null;
		double dx = soldier.getX() - s.cityCenter.getX(), dz = soldier.getZ() - s.cityCenter.getZ();
		double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
		int x = (int) Math.floor(soldier.getX() + dx / len * 3), z = (int) Math.floor(soldier.getZ() + dz / len * 3);
		int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
		float yaw = (float) Math.toDegrees(Math.atan2(-(s.cityCenter.getX() - x), s.cityCenter.getZ() - z));
		e.refreshPositionAndAngles(x + 0.5, y, z + 0.5, yaw, 0);
		if (!world.isSpaceEmpty(e, e.getBoundingBox().contract(0.1))) return null;
		target(e, s.cityCenter);
		e.addCommandTag("colonists_siege");
		e.addCommandTag("colonists_siege_" + s.id);
		world.spawnEntity(e);
		String nice = name.replace('_', ' ');
		net.colonists.faction.FactionManager.broadcastNear(world, s.cityCenter, 200, Text.literal("⚙ The " + attacker.name + " bring up a " + nice + " against " + s.name + "!").formatted(Formatting.GOLD));
		return e;
	}

	/** An engine for a clan's war band, at a spot of its choosing (its crew are tagged by the caller). */
	@Nullable
	public static Entity bringEngineAt(ServerWorld world, Settlement s, Faction attacker, BlockPos at) {
		if (!PRESENT || s.cityCenter == null || enginesAt(world, s).size() >= 2) return null;
		String name = world.random.nextBoolean() ? "battering_ram" : "catapult";
		EntityType<?> type = Registries.ENTITY_TYPE.get(Identifier.of("siegeworks", name));
		Entity e = type.create(world);
		if (e == null) return null;
		int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
		float yaw = (float) Math.toDegrees(Math.atan2(-(s.cityCenter.getX() - at.getX()), s.cityCenter.getZ() - at.getZ()));
		e.refreshPositionAndAngles(at.getX() + 0.5, y, at.getZ() + 0.5, yaw, 0);
		target(e, s.cityCenter);
		e.addCommandTag("colonists_siege");
		e.addCommandTag("colonists_siege_" + s.id);
		world.spawnEntity(e);
		net.colonists.faction.FactionManager.broadcastNear(world, s.cityCenter, 200, Text.literal("⚙ The " + attacker.name + " drag a " + name.replace('_', ' ') + " against " + s.name + "!").formatted(Formatting.DARK_RED));
		return e;
	}

	static void target(Entity e, @Nullable BlockPos at) {
		NbtCompound n = new NbtCompound();
		e.writeNbt(n);
		if (at == null) n.remove("AutoTarget");
		else n.putIntArray("AutoTarget", new int[]{at.getX(), at.getY(), at.getZ()});
		e.readNbt(n);
	}

	/** The town has fallen (or made peace): its engines stand down, left on the field for whoever wants them. */
	public static void standDown(ServerWorld world, Settlement s) {
		for (Entity e : enginesAt(world, s)) {
			target(e, null);
			e.removeCommandTag("colonists_siege_" + s.id);
		}
	}
}
