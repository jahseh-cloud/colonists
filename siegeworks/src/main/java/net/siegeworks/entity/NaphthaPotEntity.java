package net.siegeworks.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.Item;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.World;
import net.siegeworks.Siegeworks;
import net.siegeworks.block.NaphthaFireBlock;

/** A thrown clay pot of Naphtha: it bursts into a fire that water cannot put out. */
public class NaphthaPotEntity extends ThrownItemEntity {
	public NaphthaPotEntity(EntityType<? extends NaphthaPotEntity> type, World world) {
		super(type, world);
	}

	public NaphthaPotEntity(World world, LivingEntity owner) {
		super(Siegeworks.NAPHTHA_POT_ENTITY, owner, world);
	}

	@Override
	protected Item getDefaultItem() {
		return Siegeworks.NAPHTHA_POT;
	}

	@Override
	protected void onCollision(HitResult hit) {
		super.onCollision(hit);
		if (getWorld() instanceof ServerWorld sw) {
			NaphthaFireBlock.spread(sw, hit.getPos(), 2.2);
			sw.playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_DECORATED_POT_SHATTER, SoundCategory.NEUTRAL, 1.2f, 0.9f);
			sw.playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.NEUTRAL, 1.2f, 0.7f);
			discard();
		}
	}
}
