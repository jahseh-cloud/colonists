package net.siegeworks.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.siegeworks.Siegeworks;
import org.jetbrains.annotations.Nullable;

/** A giant crossbow on a wheeled stand. Load a bolt, wind the winch four times, aim, release. */
public class BallistaEntity extends SiegeEngineEntity {
	public BallistaEntity(EntityType<? extends BallistaEntity> type, World world) {
		super(type, world);
	}

	@Override
	public float maxHealth() {
		return 40;
	}

	@Override
	public int seats() {
		return 1;
	}

	@Override
	public Item item() {
		return Siegeworks.BALLISTA_ITEM;
	}

	@Override
	protected Vec3d seat(int index) {
		return new Vec3d(0, 0.1, -1.15);
	}

	@Override
	protected boolean turret() {
		return true;
	}

	@Override
	protected float turnSpeed() {
		return 3f;
	}

	@Override
	protected int weight() {
		return -1;
	}

	@Override
	protected int ammoFor(ItemStack stack) {
		return stack.isOf(Siegeworks.BOLT) ? AMMO_BOLT : AMMO_NONE;
	}

	@Override
	protected int defaultAutoAmmo() {
		return AMMO_BOLT;
	}

	@Override
	protected void release(@Nullable LivingEntity by) {
		if (ammo() != AMMO_BOLT || tension() < 0.25f) {
			getWorld().playSound(null, getBlockPos(), SoundEvents.BLOCK_DISPENSER_FAIL, SoundCategory.NEUTRAL, 1f, 0.6f);
			return;
		}
		float t = tension();
		Vec3d dir = Vec3d.fromPolar(aim(), getYaw());
		Vec3d muzzle = getPos().add(0, 0.95, 0).add(dir.multiply(1.1));
		BallistaBoltEntity bolt = new BallistaBoltEntity(getWorld(), muzzle.x, muzzle.y, muzzle.z);
		if (by != null) bolt.setOwner(by);
		float speed = 1.6f + 3.0f * t;
		bolt.setVelocity(dir.x, dir.y, dir.z, speed, 0.4f);
		bolt.setDamage((5.0 + 11.0 * t) / speed);
		bolt.pierce(4);
		bolt.setCritical(t >= 1f);
		getWorld().spawnEntity(bolt);
		getWorld().playSound(null, getBlockPos(), SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.NEUTRAL, 1.5f, 0.45f);
		getWorld().playSound(null, getBlockPos(), SoundEvents.BLOCK_WOODEN_TRAPDOOR_CLOSE, SoundCategory.NEUTRAL, 1.2f, 0.6f);
		setAmmo(AMMO_NONE);
		setTension(0);
		countShot();
		addVelocity(-dir.x * 0.08, 0, -dir.z * 0.08);
	}

	@Override
	protected void autoWork(Vec3d target) {
		// Aim straight at the target, a little high for the drop.
		double dist = Math.sqrt(squaredDistanceTo(target));
		double dy = target.y - (getY() + 0.95);
		setAim(MathHelper.clamp((float) -(Math.toDegrees(Math.atan2(dy, dist)) + dist * 0.12), -60f, 30f));
		super.autoWork(target);
	}

	@Override
	protected void clientTick() {
		anim = approach(anim, swing > 0 ? 0 : tension(), swing > 0 ? 1f : 0.06f); // how far the string is drawn
	}

	@Override
	public String[] keyHints() {
		return new String[]{"load / crank", "loose"};
	}

	@Override
	public Text status() {
		return bar("Ballista", ammo() == AMMO_BOLT ? "Bolt loaded" : "Empty");
	}
}
