package net.siegeworks.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.siegeworks.Siegeworks;
import org.jetbrains.annotations.Nullable;

/**
 * A torsion catapult. Load a stone (or cobblestone, or a Naphtha pot), wind the arm down six turns, and release.
 * The rider's look sets the angle (level = low and flat, up = high lob); the winding sets the force.
 */
public class CatapultEntity extends SiegeEngineEntity {
	public CatapultEntity(EntityType<? extends CatapultEntity> type, World world) {
		super(type, world);
	}

	@Override
	public float maxHealth() {
		return 60;
	}

	@Override
	public int seats() {
		return 2;
	}

	@Override
	public Item item() {
		return Siegeworks.CATAPULT_ITEM;
	}

	@Override
	protected Vec3d seat(int index) {
		return index == 0 ? new Vec3d(0, 0.2, -1.75) : new Vec3d(0.9, 0.2, -1.5);
	}

	@Override
	protected boolean turret() {
		return true;
	}

	@Override
	protected float turnSpeed() {
		return 1.2f;
	}

	@Override
	protected int weight() {
		return 1;
	}

	@Override
	protected float crankStep() {
		return 1f / 6f;
	}

	@Override
	protected double pushFactor() {
		return 0.35;
	}

	@Override
	protected int ammoFor(ItemStack stack) {
		if (stack.isOf(Siegeworks.STONE)) return AMMO_STONE;
		if (stack.isOf(Items.COBBLESTONE)) return AMMO_RUBBLE;
		if (stack.isOf(Siegeworks.NAPHTHA_POT)) return AMMO_NAPHTHA;
		return AMMO_NONE;
	}

	@Override
	protected int defaultAutoAmmo() {
		return getRandom().nextInt(4) == 0 ? AMMO_NAPHTHA : AMMO_STONE;
	}

	/** Launch elevation in degrees from the aim. */
	public float elevation() {
		return MathHelper.clamp(30f - aim() * 0.7f, 15f, 70f);
	}

	@Override
	protected void release(@Nullable LivingEntity by) {
		int ammo = ammo();
		if (ammo == AMMO_NONE || tension() < 0.2f) {
			getWorld().playSound(null, getBlockPos(), SoundEvents.BLOCK_DISPENSER_FAIL, SoundCategory.NEUTRAL, 1f, 0.5f);
			return;
		}
		float t = tension();
		Vec3d dir = Vec3d.fromPolar(-elevation(), getYaw());
		Vec3d from = local(0, 2.9, 0.4);
		CatapultStoneEntity shot = new CatapultStoneEntity(getWorld(), from.x, from.y, from.z, ammo, ammo == AMMO_RUBBLE ? 0.7f * t : t);
		if (by != null) shot.setOwner(by);
		float speed = 0.9f + 1.4f * t;
		shot.setVelocity(dir.x, dir.y, dir.z, speed, 1.2f);
		getWorld().spawnEntity(shot);
		getWorld().playSound(null, getBlockPos(), SoundEvents.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, SoundCategory.NEUTRAL, 1.5f, 0.5f);
		getWorld().playSound(null, getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.NEUTRAL, 1.5f, 0.4f);
		setAmmo(AMMO_NONE);
		setTension(0);
		countShot();
	}

	@Override
	protected void autoWork(Vec3d target) {
		faceTowards(target, 12f);
		setAim(-21.5f); // a 45 degree lob
		if (ammo() == AMMO_NONE) {
			setAmmo(defaultAutoAmmo());
			return;
		}
		double dx = target.x - getX(), dz = target.z - getZ();
		double range = Math.sqrt(dx * dx + dz * dz);
		double v = Math.sqrt(range * 0.05) * 1.12;
		float need = MathHelper.clamp((float) ((v - 0.9) / 1.4), 0.2f, 1f);
		if (tension() + 0.01f < need) crank(null);
		else release(null);
	}

	/** Arm angle: 27 degrees forward at rest against the bar, down to -75 fully wound. */
	@Override
	protected void clientTick() {
		float target = 27f - 102f * tension();
		anim = target > anim ? Math.min(target, anim + 34f) : Math.max(target, anim - 5f);
	}

	@Override
	public Text status() {
		String a = switch (ammo()) {
			case AMMO_STONE -> "Stone loaded";
			case AMMO_RUBBLE -> "Rubble loaded";
			case AMMO_NAPHTHA -> "Naphtha loaded";
			default -> "Empty";
		};
		return bar("Catapult", a).copy().append(Text.literal("  Angle " + Math.round(elevation()) + "°").formatted(net.minecraft.util.Formatting.AQUA));
	}
}
