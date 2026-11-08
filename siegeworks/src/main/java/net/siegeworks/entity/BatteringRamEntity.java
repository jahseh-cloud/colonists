package net.siegeworks.entity;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.siegeworks.Siegeworks;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A covered ram. Crank it back (two turns), release, and the iron head swings into whatever is in front. Every hit wears
 * the blocks down; gates and doors give way first, stone takes longer, obsidian never. Extra crew in the seats add weight.
 * The roof shelters the crew from arrows.
 */
public class BatteringRamEntity extends SiegeEngineEntity {
	private final Map<BlockPos, Float> wear = new HashMap<>();
	private int strikeIn;
	private boolean autoDrive;

	public BatteringRamEntity(EntityType<? extends BatteringRamEntity> type, World world) {
		super(type, world);
	}

	@Override
	public float maxHealth() {
		return 90;
	}

	@Override
	public int seats() {
		return 4;
	}

	@Override
	public Item item() {
		return Siegeworks.BATTERING_RAM_ITEM;
	}

	@Override
	protected Vec3d seat(int index) {
		return switch (index) {
			case 0 -> new Vec3d(0, 0.3, -1.25);
			case 1 -> new Vec3d(-0.6, 0.3, 0.2);
			case 2 -> new Vec3d(0.6, 0.3, 0.2);
			default -> new Vec3d(0.6, 0.3, -0.7);
		};
	}

	@Override
	protected float driveSpeed() {
		return 0.06f;
	}

	@Override
	protected float turnSpeed() {
		return 1.5f;
	}

	@Override
	public boolean shelters() {
		return true;
	}

	@Override
	protected int weight() {
		return 2;
	}

	@Override
	protected double pushFactor() {
		return 0.2;
	}

	@Override
	protected float crankStep() {
		return 0.5f;
	}

	@Override
	protected void release(@Nullable LivingEntity by) {
		if (tension() < 1f || strikeIn > 0) {
			getWorld().playSound(null, getBlockPos(), SoundEvents.BLOCK_DISPENSER_FAIL, SoundCategory.NEUTRAL, 1f, 0.5f);
			return;
		}
		setTension(0);
		strikeIn = 5;
		countShot();
	}

	@Override
	protected void serverTick() {
		if (strikeIn > 0 && --strikeIn == 0) strike();
		if (autoDrive && rider() == null) {
			Vec3d f = forward();
			setVelocity(f.x * 0.05, getVelocity().y, f.z * 0.05);
		}
	}

	/** How hard the ram hits: heavier with more hands on it. */
	public float power() {
		int crew = getPassengerList().size();
		return 1.2f + 0.6f * Math.max(0, crew - 1) + (autoTarget != null && rider() == null ? 0.6f : 0);
	}

	public int strike() {
		if (!(getWorld() instanceof ServerWorld w)) return 0;
		float power = power();
		double reach = getWidth() / 2 + 0.75;
		Set<BlockPos> hit = new LinkedHashSet<>();
		for (double side : new double[]{-0.5, 0.5}) for (double dy : new double[]{0.75, 1.6}) hit.add(BlockPos.ofFloored(local(side, dy, reach)));
		int broken = 0, k = 0;
		boolean stone = false, any = false;
		for (BlockPos p : hit) {
			k++;
			BlockState s = w.getBlockState(p);
			if (s.isAir() || !s.getFluidState().isEmpty()) continue;
			any = true;
			float hardness = s.getHardness(w, p);
			if (hardness < 0 || hardness >= 25 || !blockDamage(w)) {
				stone = true;
				continue;
			}
			boolean wood = s.isIn(BlockTags.DOORS) || s.isIn(BlockTags.FENCE_GATES) || s.isIn(BlockTags.TRAPDOORS) || s.isIn(BlockTags.LOGS) || s.isIn(BlockTags.PLANKS) || s.isIn(BlockTags.WOODEN_FENCES);
			if (!wood) stone = true;
			float toughness = Math.max(0.3f, hardness * (wood ? 0.6f : 1.6f));
			float progress = wear.getOrDefault(p, 0f) + power / toughness;
			w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, s), p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 12, 0.3, 0.3, 0.3, 0.1);
			if (progress >= 1f) {
				wear.remove(p);
				w.setBlockBreakingInfo(getId() * 4 + k, p, -1);
				w.breakBlock(p, w.random.nextInt(3) == 0, rider());
				broken++;
			} else {
				wear.put(p.toImmutable(), progress);
				w.setBlockBreakingInfo(getId() * 4 + k, p, (int) (progress * 10) - 1);
			}
		}
		// Anyone in the way takes the blow.
		Vec3d head = local(0, 1.1, reach - 0.2);
		for (Entity e : w.getOtherEntities(this, new Box(head, head).expand(0.9), e -> !e.isConnectedThroughVehicle(this))) {
			e.damage(rider() != null ? w.getDamageSources().playerAttack(rider()) : w.getDamageSources().generic(), 5f * power);
			Vec3d f = forward();
			e.addVelocity(f.x * 0.9, 0.3, f.z * 0.9);
			any = true;
		}
		w.playSound(null, getBlockPos(), stone ? SoundEvents.ENTITY_ZOMBIE_ATTACK_IRON_DOOR : SoundEvents.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, SoundCategory.NEUTRAL, any ? 1.6f : 0.6f, 0.6f);
		if (broken > 0) w.playSound(null, getBlockPos(), SoundEvents.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.NEUTRAL, 1.4f, 0.7f);
		return broken;
	}

	@Override
	protected void autoWork(Vec3d target) {
		faceTowards(target, 15f);
		double dx = target.x - getX(), dz = target.z - getZ();
		boolean close = dx * dx + dz * dz < 9;
		autoDrive = !horizontalCollision && !close;
		if (horizontalCollision) {
			if (tension() < 1f) crank(null);
			else release(null);
		}
	}

	/** How far the ram hangs forward (+) or is drawn back (-). */
	@Override
	protected void clientTick() {
		float target = swing > 4 ? 0.55f : swing > 0 ? 0.25f : -0.7f * tension();
		anim = approach(anim, target, swing > 4 ? 0.45f : 0.08f);
	}

	@Override
	public String[] keyHints() {
		return new String[]{"draw back", "swing"};
	}

	@Override
	public Text status() {
		return Text.literal("Battering ram  ").formatted(Formatting.GOLD)
				.append(Text.literal(tension() >= 1f ? "Drawn back" : tension() > 0 ? "Drawing back…" : "Hanging").formatted(tension() >= 1f ? Formatting.GREEN : Formatting.YELLOW))
				.append(Text.literal(String.format("  Weight %.1f", power())).formatted(Formatting.AQUA));
	}
}
