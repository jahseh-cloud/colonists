package net.siegeworks.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.siegeworks.Siegeworks;
import net.siegeworks.block.NaphthaFireBlock;
import org.jetbrains.annotations.Nullable;

/**
 * The Naphtha siphon: a bronze tube on a wheeled stand, fed from a tank and driven by a bellows. Pour in Naphtha pots,
 * pump up the pressure, and hold the release to throw a jet of fire. Pumping past full risks bursting the tank.
 */
public class SiphonEntity extends SiegeEngineEntity {
	private static final TrackedData<Integer> FUEL = DataTracker.registerData(SiphonEntity.class, TrackedDataHandlerRegistry.INTEGER);
	public static final int FUEL_PER_POT = 40, MAX_FUEL = 160;
	private int sprayFor, sprayTick;

	public SiphonEntity(EntityType<? extends SiphonEntity> type, World world) {
		super(type, world);
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		super.initDataTracker(builder);
		builder.add(FUEL, 0);
	}

	public int fuel() {
		return this.dataTracker.get(FUEL);
	}

	public void setFuel(int f) {
		this.dataTracker.set(FUEL, MathHelper.clamp(f, 0, MAX_FUEL));
	}

	@Override
	public float maxHealth() {
		return 45;
	}

	@Override
	public int seats() {
		return 1;
	}

	@Override
	public Item item() {
		return Siegeworks.SIPHON_ITEM;
	}

	@Override
	protected Vec3d seat(int index) {
		return new Vec3d(0, 0.1, -1.1);
	}

	@Override
	protected boolean turret() {
		return true;
	}

	@Override
	protected float turnSpeed() {
		return 2.5f;
	}

	@Override
	protected int weight() {
		return 0;
	}

	@Override
	protected int ammoFor(ItemStack stack) {
		return stack.isOf(Siegeworks.NAPHTHA_POT) ? AMMO_NAPHTHA : AMMO_NONE;
	}

	/** Pour a pot into the tank, or work the bellows. */
	@Override
	public void work(LivingEntity who, ItemStack held) {
		if (held.isOf(Siegeworks.NAPHTHA_POT) && fuel() + FUEL_PER_POT <= MAX_FUEL) {
			setFuel(fuel() + FUEL_PER_POT);
			if (!(who instanceof PlayerEntity p && p.getAbilities().creativeMode)) held.decrement(1);
			getWorld().playSound(null, getBlockPos(), SoundEvents.ITEM_BUCKET_EMPTY_LAVA, SoundCategory.NEUTRAL, 0.8f, 0.7f);
			return;
		}
		crank(who);
	}

	/** One stroke of the bellows. Past full pressure the tank may burst. */
	@Override
	public void crank(@Nullable LivingEntity who) {
		if (crankCooldown > 0) return;
		crankCooldown = 6;
		if (who instanceof PlayerEntity p) p.addExhaustion(0.3f);
		if (tension() >= 1f) {
			if (fuel() > 0 && getRandom().nextInt(3) == 0) {
				burst();
				return;
			}
			getWorld().playSound(null, getBlockPos(), SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.NEUTRAL, 0.6f, 1.6f);
			if (who instanceof PlayerEntity p) p.sendMessage(Text.literal("The tank groans. Pump again and it may burst!").formatted(Formatting.RED), true);
			return;
		}
		setTension(tension() + 0.2f);
		getWorld().playSound(null, getBlockPos(), SoundEvents.ENTITY_HORSE_BREATHE, SoundCategory.NEUTRAL, 1f, 0.5f + tension() * 0.3f);
	}

	private void burst() {
		if (!(getWorld() instanceof ServerWorld w)) return;
		w.playSound(null, getBlockPos(), SoundEvents.ENTITY_DRAGON_FIREBALL_EXPLODE, SoundCategory.NEUTRAL, 1.5f, 0.8f);
		NaphthaFireBlock.spread(w, getPos().add(0, 0.5, 0), 3.0);
		setFuel(0);
		setTension(0);
		damage(w.getDamageSources().onFire(), 30f);
	}

	/** The release is held: keep spraying while the packets keep coming. */
	@Override
	protected void release(@Nullable LivingEntity by) {
		if (fuel() <= 0 || tension() < 0.05f) {
			if (sprayFor == 0) getWorld().playSound(null, getBlockPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.NEUTRAL, 0.6f, 1.5f);
			return;
		}
		sprayFor = 4;
	}

	@Override
	protected void serverTick() {
		boolean spraying = sprayFor > 0 && fuel() > 0 && tension() >= 0.05f;
		if (spraying) {
			sprayFor--;
			sprayTick++;
			setFuel(fuel() - 1);
			setTension(tension() - 0.012f);
			Vec3d dir = Vec3d.fromPolar(aim(), getYaw());
			Vec3d muzzle = getPos().add(0, 1.05, 0).add(dir.multiply(1.3));
			LivingEntity owner = rider();
			Spray.tick((ServerWorld) getWorld(), this, owner, muzzle, dir, 6 + 7 * tension(), sprayTick, 9);
		} else sprayFor = 0;
		if (spraying != flag()) setFlag(spraying);
	}

	@Override
	protected void autoWork(Vec3d target) {
		faceTowards(target, 25f);
		double d = Math.sqrt(squaredDistanceTo(target));
		setAim(MathHelper.clamp((float) -Math.toDegrees(Math.atan2(target.y - getY() - 1, d)), -40f, 30f));
		if (fuel() < FUEL_PER_POT) setFuel(fuel() + FUEL_PER_POT);
		else if (tension() < 0.8f) crank(null);
		else if (d < 13) sprayFor = 12;
	}

	@Override
	protected void dropContents() {
		int pots = fuel() / FUEL_PER_POT;
		if (pots > 0) dropStack(new ItemStack(Siegeworks.NAPHTHA_POT, pots));
	}

	/** Bellows: how far it is pumped (client). */
	@Override
	protected void clientTick() {
		anim = approach(anim, tension(), 0.08f);
	}

	@Override
	public String[] keyHints() {
		return new String[]{"pour / pump", "spray (hold)"};
	}

	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		setFuel(nbt.getInt("Fuel"));
	}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		nbt.putInt("Fuel", fuel());
	}

	@Override
	public Text status() {
		int pct = Math.round(tension() * 100);
		return Text.literal("Naphtha siphon  ").formatted(Formatting.GOLD)
				.append(Text.literal("Tank " + fuel() * 100 / MAX_FUEL + "%").formatted(fuel() == 0 ? Formatting.RED : Formatting.GREEN))
				.append(Text.literal("  Pressure " + pct + "%").formatted(pct >= 100 ? Formatting.RED : Formatting.YELLOW));
	}
}
