package net.siegeworks;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.siegeworks.block.NaphthaFireBlock;
import net.siegeworks.entity.BallistaBoltEntity;
import net.siegeworks.entity.BallistaEntity;
import net.siegeworks.entity.BatteringRamEntity;
import net.siegeworks.entity.CartEntity;
import net.siegeworks.entity.CatapultEntity;
import net.siegeworks.entity.CatapultStoneEntity;
import net.siegeworks.entity.SiegeEngineEntity;
import net.siegeworks.entity.SiegeTowerEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * A headless self-test, run on CI with -Dsiegeworks.selftest=true: builds a firing range in the sky, works every engine
 * the way a crew would, checks what it did to the walls, and stops the server.
 */
public final class SelfTest {
	private SelfTest() {}

	private static int tick = -1;
	private static BlockPos base;
	private static final List<String> failures = new ArrayList<>();
	private static BallistaEntity ballista;
	private static CatapultEntity catapult;
	private static BatteringRamEntity ram;
	private static SiegeTowerEntity tower;
	private static CartEntity cart;
	private static int stoneCount;

	public static void register() {
		if (!Boolean.getBoolean("siegeworks.selftest")) return;
		ServerTickEvents.END_SERVER_TICK.register(SelfTest::tick);
	}

	private static void check(boolean ok, String what) {
		Siegeworks.LOGGER.info("SIEGEWORKS SELFTEST {} {}", ok ? "ok  " : "FAIL", what);
		if (!ok) failures.add(what);
	}

	private static void step(String name, Runnable r) {
		try {
			r.run();
		} catch (Throwable t) {
			Siegeworks.LOGGER.error("SIEGEWORKS SELFTEST FAILURE in " + name, t);
			failures.add(name + ": " + t);
		}
	}

	private static void fill(ServerWorld w, BlockPos a, BlockPos b, BlockState s) {
		for (BlockPos p : BlockPos.iterate(a, b)) w.setBlockState(p, s, 2);
	}

	private static <T extends Entity> T spawn(ServerWorld w, EntityType<T> type, double x, double y, double z) {
		T e = type.create(w);
		e.refreshPositionAndAngles(x, y, z, 0, 0); // facing +Z (south)
		w.spawnEntity(e);
		return e;
	}

	private static void tick(MinecraftServer server) {
		tick++;
		ServerWorld w = server.getOverworld();
		FakePlayer crew = FakePlayer.get(w);
		if (tick == 20) step("range", () -> {
			int y = w.getTopY(Heightmap.Type.WORLD_SURFACE, 0, 0) + 20;
			base = new BlockPos(0, y, 0);
			for (int cx = -3; cx <= 3; cx++) for (int cz = -3; cz <= 8; cz++) w.setChunkForced(cx, cz, true);
			// A stone floor, with clear air above.
			fill(w, base.add(-30, 0, -10), base.add(30, 0, 110), Blocks.STONE.getDefaultState());
			fill(w, base.add(-30, 1, -10), base.add(30, 12, 110), Blocks.AIR.getDefaultState());
			// Lane 1 (x=-15): the ram's gate: oak planks and a door frame of stone bricks.
			fill(w, base.add(-17, 1, 4), base.add(-13, 3, 4), Blocks.OAK_PLANKS.getDefaultState());
			// Lane 2 (x=0): a glass pane screen for the ballista, then an obsidian backstop.
			fill(w, base.add(-2, 1, 6), base.add(2, 3, 6), Blocks.GLASS_PANE.getDefaultState());
			// Lane 4 (x=22): a pool for the Naphtha.
			fill(w, base.add(20, 0, 20), base.add(24, 0, 24), Blocks.WATER.getDefaultState());
			stoneCount = count(w, base.add(-30, 0, 10), base.add(30, 0, 110), Blocks.STONE);
			Siegeworks.LOGGER.info("SIEGEWORKS SELFTEST range built at {}", base);
		});
		if (base == null) return;
		double bx = base.getX(), by = base.getY() + 1, bz = base.getZ();
		if (tick == 40) step("spawn", () -> {
			ballista = spawn(w, Siegeworks.BALLISTA, bx + 0.5, by, bz + 0.5);
			catapult = spawn(w, Siegeworks.CATAPULT, bx + 10.5, by, bz + 0.5);
			ram = spawn(w, Siegeworks.BATTERING_RAM, bx - 14.5, by, bz + 2.4);
			tower = spawn(w, Siegeworks.SIEGE_TOWER, bx - 26.5, by, bz + 0.5);
			cart = spawn(w, Siegeworks.CART, bx + 22.5, by, bz - 5.5);
		});
		if (ballista == null) return;
		if (tick == 60) step("ballista", () -> {
			ItemStack bolt = new ItemStack(Siegeworks.BOLT, 2);
			ballista.work(crew, bolt);
			check(ballista.ammo() == SiegeEngineEntity.AMMO_BOLT && bolt.getCount() == 1, "ballista loads a bolt (and it is used up)");
			for (int i = 0; i < 4; i++) {
				ballista.crank(crew);
				for (int k = 0; k < 9; k++) ballista.tick();
			}
			check(ballista.tension() >= 1f, "four turns of the crank wind the ballista fully (" + ballista.tension() + ")");
			ballista.setAim(-2f);
			int before = w.getEntitiesByClass(BallistaBoltEntity.class, ballista.getBoundingBox().expand(5), e -> true).size();
			ballista.fireKey(crew);
			int after = w.getEntitiesByClass(BallistaBoltEntity.class, ballista.getBoundingBox().expand(5), e -> true).size();
			check(after > before && ballista.ammo() == SiegeEngineEntity.AMMO_NONE && ballista.tension() == 0, "ballista looses a bolt and resets");
		});
		if (tick == 100) step("ballista-glass", () -> {
			int panes = count(w, base.add(-2, 1, 6), base.add(2, 3, 6), Blocks.GLASS_PANE);
			check(panes < 15, "the bolt smashed through the glass screen (" + (15 - panes) + " panes broken)");
		});
		if (tick == 110) step("catapult", () -> {
			catapult.work(crew, new ItemStack(Siegeworks.STONE));
			for (int i = 0; i < 6; i++) {
				catapult.crank(crew);
				for (int k = 0; k < 9; k++) catapult.tick();
			}
			check(catapult.tension() >= 0.99f, "six turns wind the catapult (" + catapult.tension() + ")");
			catapult.setAim(-21.5f);
			check(Math.abs(catapult.elevation() - 45) < 1, "aim sets a 45 degree lob");
			catapult.fireKey(crew);
			check(!w.getEntitiesByClass(CatapultStoneEntity.class, catapult.getBoundingBox().expand(6), e -> true).isEmpty(), "the catapult hurls a stone");
		});
		if (tick == 260) step("catapult-impact", () -> {
			int now = count(w, base.add(-30, 0, 10), base.add(30, 0, 110), Blocks.STONE);
			check(now < stoneCount, "the stone cratered the floor (" + (stoneCount - now) + " blocks)");
			// With the gamerule off, nothing breaks.
			w.getGameRules().get(Siegeworks.BLOCK_DAMAGE).set(false, server);
			int broken = CatapultStoneEntity.crater(w, Vec3d.ofCenter(base.add(-5, 0, 60)).add(0, 0.5, 0), 1f, crew, crew);
			check(broken == 0, "siegeworksBlockDamage=false leaves blocks whole");
			w.getGameRules().get(Siegeworks.BLOCK_DAMAGE).set(true, server);
			int hit = CatapultStoneEntity.crater(w, Vec3d.ofCenter(base.add(5, 0, 60)).add(0, 0.5, 0), 1f, crew, crew);
			check(hit > 0, "a direct hit breaks stone (" + hit + ")");
		});
		if (tick == 270) step("ram", () -> {
			int planks = count(w, base.add(-17, 1, 4), base.add(-13, 3, 4), Blocks.OAK_PLANKS);
			for (int swing = 0; swing < 4; swing++) {
				ram.crank(crew);
				for (int k = 0; k < 9; k++) ram.tick();
				ram.crank(crew);
				for (int k = 0; k < 9; k++) ram.tick();
				ram.fireKey(crew);
				for (int k = 0; k < 7; k++) ram.tick();
			}
			int left = count(w, base.add(-17, 1, 4), base.add(-13, 3, 4), Blocks.OAK_PLANKS);
			check(left < planks, "four swings of the ram broke the gate (" + (planks - left) + " planks)");
		});
		if (tick == 280) step("tower", () -> {
			check(!tower.bridgeDown(), "the tower's drawbridge starts up");
			tower.crank(crew);
			check(tower.bridgeDown(), "the crank lowers the drawbridge");
			// (Fabric's fake players never ride, so a zombie volunteers.)
			net.minecraft.entity.mob.ZombieEntity z = EntityType.ZOMBIE.create(w);
			z.refreshPositionAndAngles(tower.getX(), tower.getY(), tower.getZ() - 2, 0, 0);
			w.spawnEntity(z);
			check(z.startRiding(tower, true) && tower.getPassengerList().size() == 1, "a crew member climbs aboard");
			tower.tick();
			tower.cross(z);
			check(!z.hasVehicle() && z.getY() > by + 5, "and leaps across the drawbridge (y=" + Math.round(z.getY() - by) + ")");
		});
		if (tick == 290) step("cart-and-naphtha", () -> {
			cart.cargo.setStack(0, new ItemStack(Items.WHEAT, 32));
			NbtCompound n = new NbtCompound();
			cart.writeNbt(n);
			CartEntity copy = Siegeworks.CART.create(w);
			copy.readNbt(n);
			check(copy.cargo.getStack(0).getCount() == 32, "the cart keeps its cargo through a save");
			int fires = NaphthaFireBlock.spread(w, Vec3d.ofCenter(base.add(22, 0, 22)).add(0, 0.6, 0), 2.5);
			boolean onWater = w.getBlockState(base.add(22, 1, 22)).isOf(Siegeworks.NAPHTHA_FIRE) || count(w, base.add(20, 1, 20), base.add(24, 1, 24), Siegeworks.NAPHTHA_FIRE) > 0;
			check(fires > 0 && onWater, "Naphtha burns on the water (" + fires + " fires)");
			// AI crews: a ballista with an AutoTarget and a tagged crewman works itself.
			BallistaEntity auto = spawn(w, Siegeworks.BALLISTA, bx - 5.5, by, bz - 6.5);
			NbtCompound a = new NbtCompound();
			auto.writeNbt(a);
			a.putIntArray("AutoTarget", new int[]{base.getX() - 5, base.getY() + 1, base.getZ() + 40});
			auto.readNbt(a);
			net.minecraft.entity.mob.ZombieEntity mate = EntityType.ZOMBIE.create(w);
			mate.refreshPositionAndAngles(bx - 5.5, by, bz - 8.5, 0, 0);
			mate.addCommandTag("siegeworks_crew");
			mate.setAiDisabled(true);
			w.spawnEntity(mate);
			check(auto.hasCrew(), "the crewman is found");
			for (int k = 0; k < 400 && auto.shots() == 0; k++) auto.tick();
			check(auto.shots() > 0, "an AI crew loads, cranks and fires on its own");
		});
		if (tick == 300) step("damage", () -> {
			CartEntity c2 = spawn(w, Siegeworks.CART, bx + 26.5, by, bz - 5.5);
			for (int i = 0; i < 6 && c2.isAlive(); i++) c2.damage(w.getDamageSources().generic(), 8f);
			check(!c2.isAlive(), "engines can be broken apart");
		});
		if (tick == 320) {
			if (failures.isEmpty()) Siegeworks.LOGGER.info("SIEGEWORKS SELFTEST PASSED");
			else Siegeworks.LOGGER.error("SIEGEWORKS SELFTEST FAILED: {}", failures);
			server.stop(false);
		}
	}

	private static int count(ServerWorld w, BlockPos a, BlockPos b, net.minecraft.block.Block block) {
		int n = 0;
		for (BlockPos p : BlockPos.iterate(a, b)) if (w.getBlockState(p).isOf(block)) n++;
		return n;
	}
}
