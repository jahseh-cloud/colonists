package net.siegeworks.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.siegeworks.Siegeworks;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Everything a siege engine shares: a wooden body that can be broken and burned, a crew who load it by hand and wind its
 * winch crank by crank, seats, towing by horse, and an optional AI crew (any mob tagged {@code siegeworks_crew}, or
 * Colonists' colonists) who work it against a target written into its NBT as {@code AutoTarget}.
 */
public abstract class SiegeEngineEntity extends Entity {
	private static final TrackedData<Float> HEALTH = DataTracker.registerData(SiegeEngineEntity.class, TrackedDataHandlerRegistry.FLOAT);
	private static final TrackedData<Float> TENSION = DataTracker.registerData(SiegeEngineEntity.class, TrackedDataHandlerRegistry.FLOAT);
	private static final TrackedData<Integer> AMMO = DataTracker.registerData(SiegeEngineEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Integer> SHOTS = DataTracker.registerData(SiegeEngineEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Float> AIM = DataTracker.registerData(SiegeEngineEntity.class, TrackedDataHandlerRegistry.FLOAT);
	private static final TrackedData<Integer> HURT = DataTracker.registerData(SiegeEngineEntity.class, TrackedDataHandlerRegistry.INTEGER);
	protected static final TrackedData<Boolean> FLAG = DataTracker.registerData(SiegeEngineEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

	public static final int AMMO_NONE = 0, AMMO_BOLT = 1, AMMO_STONE = 2, AMMO_RUBBLE = 3, AMMO_NAPHTHA = 4;

	@Nullable
	protected UUID hitch;
	protected int crankCooldown;
	@Nullable
	public BlockPos autoTarget;
	protected int autoTimer;

	// client-side interpolation and animation
	private int lerpSteps;
	private double lerpX, lerpY, lerpZ;
	private float lerpYaw;
	/** Distance rolled, for spinning wheels (client). */
	public float rolled, prevRolled;
	/** One moving part per engine (arm angle, ram swing, string pull, drawbridge), animated on the client. */
	public float anim, prevAnim;
	protected int lastShots = -1, swing;

	protected SiegeEngineEntity(EntityType<?> type, World world) {
		super(type, world);
		this.intersectionChecked = true;
	}

	// ---------------------------------------------------------------- what each engine is

	public abstract float maxHealth();

	public abstract int seats();

	public abstract Item item();

	/** Seat offsets, engine-local (+Z is the front). */
	protected abstract Vec3d seat(int index);

	/** Blocks per tick when the driver walks it forward (0 = it must be pushed or towed). */
	protected float driveSpeed() {
		return 0;
	}

	/** Degrees per tick the driver can turn it, or the turret can traverse. */
	protected float turnSpeed() {
		return 2f;
	}

	/** True for engines that swing round to follow where the rider looks. */
	protected boolean turret() {
		return false;
	}

	/** Engines that hide their crews from arrows. */
	public boolean shelters() {
		return false;
	}

	/** How much towing slows a horse (slowness amplifier, -1 for none). */
	protected int weight() {
		return 0;
	}

	/** Which ammunition this stack is for this engine, or AMMO_NONE. */
	protected int ammoFor(ItemStack stack) {
		return AMMO_NONE;
	}

	/** How far one turn of the crank winds it. */
	protected float crankStep() {
		return 0.25f;
	}

	/** Releases the engine. */
	protected abstract void release(@Nullable LivingEntity by);

	/** An AI crew working the engine against a target (every 10 ticks). */
	protected void autoWork(Vec3d target) {
		faceTowards(target, turnSpeed() * 10);
		if (ammo() == AMMO_NONE) setAmmo(defaultAutoAmmo());
		else if (tension() < 1f) crank(null);
		else release(null);
	}

	protected int defaultAutoAmmo() {
		return AMMO_NONE;
	}

	public abstract Text status();

	/** What the crank key and the fire key do on this engine (for the rider's status line). */
	public String[] keyHints() {
		return new String[]{"load / crank", "release"};
	}

	protected static float approach(float from, float to, float step) {
		return from < to ? Math.min(to, from + step) : Math.max(to, from - step);
	}

	// ---------------------------------------------------------------- tracked state

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		builder.add(HEALTH, this.maxHealth());
		builder.add(TENSION, 0f);
		builder.add(AMMO, AMMO_NONE);
		builder.add(SHOTS, 0);
		builder.add(AIM, 0f);
		builder.add(HURT, 0);
		builder.add(FLAG, false);
	}

	public float health() {
		return this.dataTracker.get(HEALTH);
	}

	public float tension() {
		return this.dataTracker.get(TENSION);
	}

	public void setTension(float t) {
		this.dataTracker.set(TENSION, MathHelper.clamp(t, 0f, 1f));
	}

	public int ammo() {
		return this.dataTracker.get(AMMO);
	}

	public void setAmmo(int a) {
		this.dataTracker.set(AMMO, a);
	}

	/** Counts up each time the engine is released, so clients can animate it. */
	public int shots() {
		return this.dataTracker.get(SHOTS);
	}

	protected void countShot() {
		this.dataTracker.set(SHOTS, shots() + 1);
	}

	/** Aim pitch in degrees (negative = up), set by the rider's look. */
	public float aim() {
		return this.dataTracker.get(AIM);
	}

	public void setAim(float a) {
		this.dataTracker.set(AIM, a);
	}

	public int hurtTime() {
		return this.dataTracker.get(HURT);
	}

	public boolean flag() {
		return this.dataTracker.get(FLAG);
	}

	public void setFlag(boolean b) {
		this.dataTracker.set(FLAG, b);
	}

	public static boolean blockDamage(World world) {
		return world.getGameRules().getBoolean(Siegeworks.BLOCK_DAMAGE);
	}

	// ---------------------------------------------------------------- the crew's hands

	/** Load (holding ammunition) or turn the crank: a crew member's sneak-use, or a rider's crank key. */
	public void work(LivingEntity who, ItemStack held) {
		int a = ammoFor(held);
		if (a != AMMO_NONE && ammo() == AMMO_NONE) {
			setAmmo(a);
			if (!(who instanceof PlayerEntity p && p.getAbilities().creativeMode)) held.decrement(1);
			getWorld().playSound(null, getBlockPos(), SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.NEUTRAL, 1f, 0.7f);
			return;
		}
		crank(who);
	}

	/** One turn of the winch. */
	public void crank(@Nullable LivingEntity who) {
		if (crankCooldown > 0) return;
		crankCooldown = 8;
		if (tension() >= 1f) return;
		setTension(tension() + crankStep());
		if (who instanceof PlayerEntity p) p.addExhaustion(0.4f);
		getWorld().playSound(null, getBlockPos(), SoundEvents.BLOCK_CHAIN_STEP, SoundCategory.NEUTRAL, 0.9f, 0.5f + tension() * 0.4f);
	}

	public void fireKey(PlayerEntity rider) {
		release(rider);
	}

	public void crankKey(PlayerEntity rider) {
		work(rider, rider.getMainHandStack());
	}

	@Override
	public ActionResult interact(PlayerEntity player, Hand hand) {
		if (hand != Hand.MAIN_HAND || this.hasPassenger(player)) return ActionResult.PASS;
		if (getWorld().isClient) return ActionResult.SUCCESS;
		ItemStack held = player.getMainHandStack();
		// Riding a horse: hitch or unhitch it.
		if (player.getVehicle() instanceof AbstractHorseEntity horse) {
			if (horse.getUuid().equals(hitch)) {
				hitch = null;
				player.sendMessage(Text.literal("Unhitched.").formatted(Formatting.GRAY), true);
			} else {
				hitch = horse.getUuid();
				player.sendMessage(Text.literal("Hitched to your horse. Ride on to tow it.").formatted(Formatting.GOLD), true);
			}
			getWorld().playSound(null, getBlockPos(), SoundEvents.ENTITY_LEASH_KNOT_PLACE, SoundCategory.NEUTRAL, 1f, 1f);
			return ActionResult.SUCCESS;
		}
		if (player.isSneaking()) {
			sneakUse(player, held);
			return ActionResult.SUCCESS;
		}
		if (getPassengerList().size() < seats() && player.startRiding(this)) return ActionResult.SUCCESS;
		return ActionResult.PASS;
	}

	protected void sneakUse(PlayerEntity player, ItemStack held) {
		work(player, held);
		player.sendMessage(status(), true);
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return getPassengerList().size() < seats();
	}

	@Override
	protected void updatePassengerPosition(Entity passenger, PositionUpdater updater) {
		int i = getPassengerList().indexOf(passenger);
		if (i < 0) return;
		Vec3d off = seat(i).rotateY((float) Math.toRadians(-getYaw()));
		Vec3d attach = passenger.getVehicleAttachmentPos(this);
		updater.accept(passenger, getX() + off.x - attach.x, getY() + off.y - attach.y, getZ() + off.z - attach.z);
	}

	@Nullable
	public PlayerEntity rider() {
		return getFirstPassenger() instanceof PlayerEntity p ? p : null;
	}

	// ---------------------------------------------------------------- body

	@Override
	public boolean canHit() {
		return !isRemoved();
	}

	@Override
	public boolean isPushable() {
		return true;
	}

	@Override
	public float getStepHeight() {
		return 1.0f;
	}

	/** Heavy: pushing an engine moves it slowly. */
	@Override
	public void pushAwayFrom(Entity entity) {
		if (entity.isConnectedThroughVehicle(this) || entity.noClip || this.noClip) return;
		double dx = entity.getX() - getX(), dz = entity.getZ() - getZ();
		double d = Math.max(Math.abs(dx), Math.abs(dz));
		if (d < 0.01) return;
		d = Math.sqrt(d);
		dx /= d;
		dz /= d;
		double k = Math.min(1.0, 1.0 / d) * 0.05;
		if (!getWorld().isClient && hitch == null) addVelocity(-dx * k * pushFactor(), 0, -dz * k * pushFactor());
		if (!entity.hasPassengers() && entity.isPushable()) entity.addVelocity(dx * k * 0.6, 0, dz * k * 0.6);
	}

	/** How readily it rolls when pushed. */
	protected double pushFactor() {
		return 0.6;
	}

	@Override
	public boolean damage(DamageSource source, float amount) {
		if (getWorld().isClient || isRemoved()) return !isRemoved();
		if (isInvulnerableTo(source)) return false;
		if (source.getAttacker() instanceof PlayerEntity p && p.getAbilities().creativeMode) {
			breakApart(false);
			return true;
		}
		if (source.isIn(DamageTypeTags.IS_FIRE)) amount *= 2f; // wood burns
		if (source.isIn(DamageTypeTags.IS_EXPLOSION)) amount *= 2f;
		this.dataTracker.set(HEALTH, health() - amount);
		this.dataTracker.set(HURT, 10);
		getWorld().playSound(null, getBlockPos(), SoundEvents.BLOCK_WOOD_HIT, SoundCategory.NEUTRAL, 1f, 0.8f);
		if (health() <= 0) breakApart(true);
		return true;
	}

	protected void breakApart(boolean drop) {
		if (getWorld() instanceof ServerWorld sw) {
			sw.playSound(null, getBlockPos(), SoundEvents.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.NEUTRAL, 1f, 0.8f);
			if (drop && sw.getGameRules().getBoolean(net.minecraft.world.GameRules.DO_ENTITY_DROPS)) {
				dropStack(new ItemStack(item()));
				dropContents();
			}
		}
		discard();
	}

	protected void dropContents() {
		int a = ammo();
		if (a == AMMO_BOLT) dropStack(new ItemStack(Siegeworks.BOLT));
		else if (a == AMMO_STONE) dropStack(new ItemStack(Siegeworks.STONE));
		else if (a == AMMO_RUBBLE) dropStack(new ItemStack(net.minecraft.item.Items.COBBLESTONE));
		else if (a == AMMO_NAPHTHA) dropStack(new ItemStack(Siegeworks.NAPHTHA_POT));
	}

	@Override
	public ItemStack getPickBlockStack() {
		return new ItemStack(item());
	}

	// ---------------------------------------------------------------- movement

	public Vec3d forward() {
		float r = getYaw() * MathHelper.RADIANS_PER_DEGREE;
		return new Vec3d(-MathHelper.sin(r), 0, MathHelper.cos(r));
	}

	/** A point given in engine-local coordinates (+Z front), in the world. */
	public Vec3d local(double x, double y, double z) {
		return getPos().add(new Vec3d(x, y, z).rotateY((float) Math.toRadians(-getYaw())));
	}

	protected void faceTowards(Vec3d target, float maxStep) {
		double dx = target.x - getX(), dz = target.z - getZ();
		float want = (float) (MathHelper.atan2(-dx, dz) * MathHelper.DEGREES_PER_RADIAN);
		float diff = MathHelper.wrapDegrees(want - getYaw());
		setYaw(getYaw() + MathHelper.clamp(diff, -maxStep, maxStep));
	}

	@Nullable
	protected Entity hitched() {
		if (hitch == null || !(getWorld() instanceof ServerWorld sw)) return null;
		Entity e = sw.getEntity(hitch);
		if (e == null || !e.isAlive() || e.squaredDistanceTo(this) > 16 * 16) {
			hitch = null;
			return null;
		}
		return e;
	}

	protected double hitchLength() {
		return getWidth() / 2 + 1.6;
	}

	@Override
	public void tick() {
		super.tick();
		int hurt = hurtTime();
		if (hurt > 0 && !getWorld().isClient) this.dataTracker.set(HURT, hurt - 1);
		if (getWorld().isClient) {
			lerpTick();
			prevRolled = rolled;
			double dx = getX() - this.prevX, dz = getZ() - this.prevZ;
			double moved = Math.sqrt(dx * dx + dz * dz);
			if (moved > 0.001) {
				Vec3d f = forward();
				rolled += (float) (moved * Math.signum(dx * f.x + dz * f.z + 1e-6));
			}
			prevAnim = anim;
			if (lastShots != shots()) {
				if (lastShots >= 0) swing = 8;
				lastShots = shots();
			}
			if (swing > 0) swing--;
			clientTick();
			return;
		}
		if (crankCooldown > 0) crankCooldown--;
		Vec3d v = getVelocity();
		Entity horse = hitched();
		PlayerEntity driver = rider();
		if (horse != null) {
			Vec3d to = horse.getPos().subtract(getPos());
			double dist = Math.sqrt(to.x * to.x + to.z * to.z);
			double len = hitchLength();
			if (dist > len) {
				double pull = Math.min(dist - len, 0.9);
				v = new Vec3d(to.x / dist * pull, v.y, to.z / dist * pull);
				faceTowards(horse.getPos(), 12f);
			} else v = new Vec3d(v.x * 0.5, v.y, v.z * 0.5);
			if (weight() >= 0 && horse instanceof LivingEntity l) l.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 30, weight(), true, false));
		} else if (driver != null && driveSpeed() > 0) {
			float fwd = driver.forwardSpeed, side = driver.sidewaysSpeed;
			setYaw(getYaw() - side * turnSpeed());
			Vec3d f = forward();
			double speed = fwd * driveSpeed() * (fwd < 0 ? 0.5 : 1);
			v = new Vec3d(f.x * speed, v.y, f.z * speed);
		}
		if (turret() && driver != null && horse == null) {
			float diff = MathHelper.wrapDegrees(driver.getYaw() - getYaw());
			setYaw(getYaw() + MathHelper.clamp(diff, -turnSpeed(), turnSpeed()));
			setAim(MathHelper.clamp(driver.getPitch(), -60f, 30f));
		}
		if (!hasNoGravity()) v = v.add(0, -0.08, 0);
		move(MovementType.SELF, v);
		v = getVelocity();
		double friction = isOnGround() ? 0.55 : 0.95;
		setVelocity(v.x * friction, v.y * 0.98, v.z * friction);
		// An AI crew works the engine.
		if (autoTarget != null && driver == null && ++autoTimer >= 10) {
			autoTimer = 0;
			if (hasCrew()) autoWork(Vec3d.ofCenter(autoTarget));
		}
		serverTick();
	}

	protected void serverTick() {}

	protected void clientTick() {}

	/** Any non-player crew standing by: mobs tagged siegeworks_crew, or colonists. */
	public boolean hasCrew() {
		List<LivingEntity> near = getWorld().getEntitiesByClass(LivingEntity.class, getBoundingBox().expand(5), e -> e.isAlive() && !(e instanceof PlayerEntity)
				&& (e.getCommandTags().contains("siegeworks_crew") || Registries.ENTITY_TYPE.getId(e.getType()).getNamespace().equals("colonists")));
		return !near.isEmpty();
	}

	@Override
	public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int steps) {
		this.lerpX = x;
		this.lerpY = y;
		this.lerpZ = z;
		this.lerpYaw = yaw;
		this.lerpSteps = 6;
	}

	private void lerpTick() {
		if (lerpSteps <= 0) return;
		double nx = getX() + (lerpX - getX()) / lerpSteps;
		double ny = getY() + (lerpY - getY()) / lerpSteps;
		double nz = getZ() + (lerpZ - getZ()) / lerpSteps;
		setYaw(getYaw() + MathHelper.wrapDegrees(lerpYaw - getYaw()) / lerpSteps);
		lerpSteps--;
		setPosition(nx, ny, nz);
	}

	// ---------------------------------------------------------------- saving

	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {
		if (nbt.contains("Health")) this.dataTracker.set(HEALTH, nbt.getFloat("Health"));
		setTension(nbt.getFloat("Tension"));
		setAmmo(nbt.getInt("Ammo"));
		setFlag(nbt.getBoolean("Flag"));
		hitch = nbt.containsUuid("Hitch") ? nbt.getUuid("Hitch") : null;
		int[] t = nbt.getIntArray("AutoTarget");
		autoTarget = t.length == 3 ? new BlockPos(t[0], t[1], t[2]) : null;
	}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {
		nbt.putFloat("Health", health());
		nbt.putFloat("Tension", tension());
		nbt.putInt("Ammo", ammo());
		nbt.putBoolean("Flag", flag());
		if (hitch != null) nbt.putUuid("Hitch", hitch);
		if (autoTarget != null) nbt.putIntArray("AutoTarget", new int[]{autoTarget.getX(), autoTarget.getY(), autoTarget.getZ()});
	}

	// ---------------------------------------------------------------- shared status line

	protected Text bar(String name, String ammoName) {
		int pct = Math.round(tension() * 100);
		return Text.literal(name + "  ").formatted(Formatting.GOLD)
				.append(Text.literal(ammoName).formatted(ammo() == AMMO_NONE ? Formatting.RED : Formatting.GREEN))
				.append(Text.literal("  Tension " + pct + "%").formatted(pct >= 100 ? Formatting.GREEN : Formatting.YELLOW));
	}
}
