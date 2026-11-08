package net.siegeworks.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.siegeworks.Siegeworks;
import net.siegeworks.block.NaphthaFireBlock;
import org.jetbrains.annotations.Nullable;

/** A jet of burning Naphtha from a siphon: flames along the stream, burning bodies in its cone, fire where it lands. */
public final class Spray {
	private Spray() {}

	/**
	 * One tick of spraying.
	 *
	 * @param tick  a running counter (bodies burn every 5 ticks, fire lands every 4)
	 * @param cone  half-angle of the stream, in degrees
	 */
	public static void tick(ServerWorld w, Entity source, @Nullable LivingEntity owner, Vec3d from, Vec3d dir, double range, int tick, double cone) {
		dir = dir.normalize();
		// The visible jet.
		for (int i = 0; i < 6; i++) {
			Vec3d j = jitter(w, dir, cone * 0.6);
			double speed = range * (0.07 + w.random.nextDouble() * 0.03);
			w.spawnParticles(ParticleTypes.FLAME, from.x, from.y, from.z, 0, j.x, j.y, j.z, speed);
		}
		if (tick % 2 == 0) {
			Vec3d end = from.add(dir.multiply(range * 0.8));
			w.spawnParticles(ParticleTypes.LARGE_SMOKE, end.x, end.y + 0.5, end.z, 2, 0.6, 0.4, 0.6, 0.02);
		}
		if (tick % 10 == 0) w.playSound(null, from.x, from.y, from.z, SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.NEUTRAL, 0.8f, 0.5f + w.random.nextFloat() * 0.2f);
		if (tick % 5 == 0) w.playSound(null, from.x, from.y, from.z, SoundEvents.BLOCK_FIRE_AMBIENT, SoundCategory.NEUTRAL, 1.2f, 0.6f);
		// Bodies in the stream burn.
		if (tick % 5 == 0) {
			double cos = Math.cos(Math.toRadians(cone + 6));
			for (Entity e : w.getOtherEntities(source, new Box(from, from).expand(range), e -> e != owner && !e.isConnectedThroughVehicle(source))) {
				Vec3d to = e.getBoundingBox().getCenter().subtract(from);
				double d = to.length();
				if (d > range || d < 0.01 || to.normalize().dotProduct(dir) < cos) continue;
				if (!clear(w, source, from, e.getBoundingBox().getCenter())) continue;
				e.setOnFireFor(8f);
				e.damage(owner != null ? w.getDamageSources().mobAttack(owner) : w.getDamageSources().inFire(), 3f);
				e.damage(w.getDamageSources().onFire(), 1f);
			}
		}
		// Burning Naphtha lands where the stream falls: on walls, floors, and water.
		if (tick % 4 == 0) {
			Vec3d j = jitter(w, dir, cone);
			Vec3d to = from.add(j.multiply(range * (0.55 + w.random.nextDouble() * 0.45)));
			BlockHitResult hit = w.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, source));
			BlockPos at = hit.getType() == HitResult.Type.MISS ? BlockPos.ofFloored(to) : hit.getBlockPos().offset(hit.getSide());
			ignite(w, at);
		}
	}

	/** Sets one Naphtha fire at (or below) a spot. */
	public static boolean ignite(ServerWorld w, BlockPos at) {
		for (int dy = 0; dy >= -4; dy--) {
			BlockPos p = at.up(dy);
			if (!w.getBlockState(p).isAir() && !w.getBlockState(p).isReplaceable()) {
				// Solid below a blocked spot: try the face we came from.
				return false;
			}
			if (NaphthaFireBlock.canBurnAt(w, p) && w.getFluidState(p).isEmpty()) {
				w.setBlockState(p, Siegeworks.NAPHTHA_FIRE.getDefaultState());
				return true;
			}
		}
		return false;
	}

	static boolean clear(ServerWorld w, Entity source, Vec3d a, Vec3d b) {
		return w.raycast(new RaycastContext(a, b, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, source)).getType() == HitResult.Type.MISS;
	}

	static Vec3d jitter(ServerWorld w, Vec3d dir, double degrees) {
		double r = Math.toRadians(degrees);
		Vec3d j = dir.add((w.random.nextDouble() - 0.5) * 2 * r, (w.random.nextDouble() - 0.5) * 2 * r, (w.random.nextDouble() - 0.5) * 2 * r);
		return j.normalize();
	}
}
