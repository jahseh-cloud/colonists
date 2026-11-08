package net.siegeworks.entity;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.siegeworks.Siegeworks;
import net.siegeworks.block.NaphthaFireBlock;

/** A catapult shot: a stone that craters what it hits (strong blocks resist), or a pot of Naphtha. */
public class CatapultStoneEntity extends ThrownItemEntity {
	private int payload = SiegeEngineEntity.AMMO_STONE;
	private float power = 1f;

	public CatapultStoneEntity(EntityType<? extends CatapultStoneEntity> type, World world) {
		super(type, world);
	}

	public CatapultStoneEntity(World world, double x, double y, double z, int payload, float power) {
		super(Siegeworks.CATAPULT_STONE, x, y, z, world);
		this.payload = payload;
		this.power = power;
		setItem(new ItemStack(payload == SiegeEngineEntity.AMMO_NAPHTHA ? Siegeworks.NAPHTHA_POT : payload == SiegeEngineEntity.AMMO_RUBBLE ? Items.COBBLESTONE : Items.STONE));
	}

	@Override
	protected Item getDefaultItem() {
		return Items.STONE;
	}

	@Override
	protected double getGravity() {
		return 0.05;
	}

	@Override
	protected void onCollision(HitResult hit) {
		super.onCollision(hit);
		if (getWorld() instanceof ServerWorld sw) {
			impact(sw, hit.getPos());
			discard();
		}
	}

	public void impact(ServerWorld w, Vec3d at) {
		if (payload == SiegeEngineEntity.AMMO_NAPHTHA) {
			NaphthaFireBlock.spread(w, at, 3.2 + power);
			w.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.NEUTRAL, 2f, 0.6f);
			w.playSound(null, at.x, at.y, at.z, SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.NEUTRAL, 2f, 0.5f);
			return;
		}
		crater(w, at, power, this, getOwner());
	}

	/** Smash blocks and bodies around a point. Stronger blocks (higher blast resistance) are less likely to break. */
	public static int crater(ServerWorld w, Vec3d at, float power, Entity source, Entity owner) {
		double radius = 1.4 + 1.3 * power;
		int broken = 0;
		if (SiegeEngineEntity.blockDamage(w)) {
			BlockPos c = BlockPos.ofFloored(at);
			int r = (int) Math.ceil(radius);
			for (BlockPos p : BlockPos.iterate(c.add(-r, -r, -r), c.add(r, r, r))) {
				double d = Math.sqrt(p.getSquaredDistance(at));
				if (d > radius) continue;
				BlockState s = w.getBlockState(p);
				if (s.isAir() || !s.getFluidState().isEmpty() && s.getBlock() instanceof net.minecraft.block.FluidBlock) continue;
				float hardness = s.getHardness(w, p);
				float res = s.getBlock().getBlastResistance();
				if (hardness < 0 || res >= 600) continue;
				double chance = (radius - d) / radius * (2.2 + 2.5 * power) / (1.0 + res * 0.45);
				if (w.random.nextDouble() < chance) {
					w.breakBlock(p.toImmutable(), w.random.nextInt(4) == 0, owner);
					broken++;
				}
			}
		}
		double reach = radius + 1.5;
		for (Entity e : w.getOtherEntities(source, new net.minecraft.util.math.Box(at, at).expand(reach))) {
			double d = Math.sqrt(e.squaredDistanceTo(at));
			if (d > reach) continue;
			float dmg = (float) ((9 + 13 * power) * (1 - d / (reach + 0.5)));
			if (e instanceof SiegeEngineEntity) dmg *= 2.5f;
			if (e instanceof LivingEntity || e instanceof SiegeEngineEntity) e.damage(w.getDamageSources().thrown(source, owner), dmg);
			Vec3d push = e.getPos().subtract(at).normalize().multiply(0.6 * power * (1 - d / reach));
			if (!(e instanceof SiegeEngineEntity)) e.addVelocity(push.x, 0.25 * power, push.z);
		}
		w.spawnParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 2, 0.5, 0.5, 0.5, 0);
		w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.COBBLESTONE.getDefaultState()), at.x, at.y + 0.3, at.z, 40, radius / 2, 0.4, radius / 2, 0.15);
		w.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_DRAGON_FIREBALL_EXPLODE, SoundCategory.NEUTRAL, 1.6f, 0.6f);
		w.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_STONE_BREAK, SoundCategory.NEUTRAL, 2f, 0.5f);
		return broken;
	}

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		nbt.putInt("Payload", payload);
		nbt.putFloat("Power", power);
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		payload = nbt.getInt("Payload");
		power = nbt.contains("Power") ? nbt.getFloat("Power") : 1f;
	}
}
