package net.siegeworks.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.siegeworks.Siegeworks;
import org.jetbrains.annotations.Nullable;

/**
 * A wheeled tower, taller than most walls. One crew member drives it from the bottom; five more ride on the top floor,
 * safe from arrows. At the wall, wind the drawbridge down and leap across.
 */
public class SiegeTowerEntity extends SiegeEngineEntity {
	public static final double DECK = 5.65, BRIDGE = 2.4;
	private boolean autoDrive;

	public SiegeTowerEntity(EntityType<? extends SiegeTowerEntity> type, World world) {
		super(type, world);
	}

	@Override
	public float maxHealth() {
		return 160;
	}

	@Override
	public int seats() {
		return 6;
	}

	@Override
	public Item item() {
		return Siegeworks.SIEGE_TOWER_ITEM;
	}

	@Override
	protected Vec3d seat(int index) {
		return switch (index) {
			case 0 -> new Vec3d(0, 0.6, -0.7);
			case 1 -> new Vec3d(-0.6, DECK, 0.5);
			case 2 -> new Vec3d(0.6, DECK, 0.5);
			case 3 -> new Vec3d(-0.6, DECK, -0.6);
			case 4 -> new Vec3d(0.6, DECK, -0.6);
			default -> new Vec3d(0, DECK, 0);
		};
	}

	@Override
	protected float driveSpeed() {
		return 0.04f;
	}

	@Override
	protected float turnSpeed() {
		return 1f;
	}

	@Override
	public boolean shelters() {
		return true;
	}

	@Override
	protected int weight() {
		return 3;
	}

	@Override
	protected double pushFactor() {
		return 0.08;
	}

	@Override
	public boolean isCollidable() {
		return true;
	}

	public boolean bridgeDown() {
		return flag();
	}

	/** The crank winds the drawbridge down, or back up. */
	@Override
	public void crank(@Nullable LivingEntity who) {
		if (crankCooldown > 0) return;
		crankCooldown = 30;
		setFlag(!flag());
		if (who instanceof PlayerEntity p) p.addExhaustion(0.8f);
		getWorld().playSound(null, getBlockPos(), SoundEvents.BLOCK_CHAIN_PLACE, SoundCategory.NEUTRAL, 1.5f, 0.5f);
		getWorld().playSound(null, getBlockPos(), flag() ? SoundEvents.BLOCK_WOODEN_TRAPDOOR_OPEN : SoundEvents.BLOCK_WOODEN_TRAPDOOR_CLOSE, SoundCategory.NEUTRAL, 1.5f, 0.5f);
	}

	@Override
	public void work(LivingEntity who, ItemStack held) {
		crank(who);
	}

	/** Sends a rider across the drawbridge. */
	public void cross(LivingEntity rider) {
		release(rider);
	}

	/** Across the drawbridge! (The rider leaps onto the wall at its end.) */
	@Override
	protected void release(@Nullable LivingEntity by) {
		if (by == null) return;
		if (!bridgeDown()) {
			if (by instanceof PlayerEntity p) p.sendMessage(Text.literal("Lower the drawbridge first (crank key).").formatted(Formatting.RED), true);
			return;
		}
		Vec3d to = local(by == getFirstPassenger() ? 0 : (getRandom().nextFloat() - 0.5) * 1.6, DECK + 0.1, getWidth() / 2 + BRIDGE - 0.3);
		by.stopRiding();
		if (by instanceof ServerPlayerEntity sp) sp.requestTeleport(to.x, to.y, to.z);
		else by.refreshPositionAfterTeleport(to.x, to.y, to.z);
		getWorld().playSound(null, by.getBlockPos(), SoundEvents.BLOCK_WOOD_STEP, SoundCategory.NEUTRAL, 1f, 1f);
	}

	@Override
	protected void serverTick() {
		if (autoDrive && rider() == null) {
			Vec3d f = forward();
			setVelocity(f.x * 0.04, getVelocity().y, f.z * 0.04);
		}
	}

	@Override
	protected void autoWork(Vec3d target) {
		faceTowards(target, 10f);
		double dx = target.x - getX(), dz = target.z - getZ();
		boolean there = horizontalCollision || dx * dx + dz * dz < 16;
		autoDrive = !there;
		if (there && !bridgeDown()) crank(null);
		if (there) for (Entity p : getPassengerList()) if (!(p instanceof PlayerEntity) && p instanceof LivingEntity l && p != getFirstPassenger()) release(l);
	}

	/** Drawbridge angle: 0 up, 90 down. */
	@Override
	protected void clientTick() {
		anim = approach(anim, bridgeDown() ? 90f : 0f, 4f);
	}

	@Override
	public String[] keyHints() {
		return new String[]{"drawbridge", "cross the bridge"};
	}

	@Override
	public Text status() {
		return Text.literal("Siege tower  ").formatted(Formatting.GOLD)
				.append(Text.literal(bridgeDown() ? "Drawbridge DOWN" : "Drawbridge up").formatted(bridgeDown() ? Formatting.GREEN : Formatting.YELLOW))
				.append(Text.literal("  " + getPassengerList().size() + "/6 aboard").formatted(Formatting.AQUA));
	}
}
