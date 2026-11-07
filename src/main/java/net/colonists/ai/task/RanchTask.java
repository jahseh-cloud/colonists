package net.colonists.ai.task;

import net.colonists.entity.ColonistBrain;
import net.colonists.entity.ColonistEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.entity.Leashable;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A rancher keeps the city's animal pen stocked: leads wild animals in on a lead, breeds them,
 * shears the sheep, and culls the herd for meat when it grows too big.
 */
public class RanchTask extends Task {
	enum Goal { FETCH, BREED, SHEAR, CULL }

	private final BlockPos pen; // lot origin of the 9x9 pen; gate at (4, 0, 0)
	private Goal goal;
	private AnimalEntity animal;
	private boolean leading;

	public RanchTask(ColonistEntity colonist, BlockPos pen) {
		super(colonist);
		this.pen = pen;
	}

	@Override
	public String describe() {
		if (this.goal == null) return "Tending the animals";
		return switch (this.goal) {
			case FETCH -> "Bringing in livestock";
			case BREED -> "Breeding livestock";
			case SHEAR -> "Shearing sheep";
			case CULL -> "Butchering";
		};
	}

	@Override
	public ColonistEntity.ToolKind tool() {
		return this.goal == Goal.CULL ? ColonistEntity.ToolKind.WEAPON : ColonistEntity.ToolKind.NONE;
	}

	private Box inside() {
		return new Box(this.pen.getX() + 1, this.pen.getY() - 1, this.pen.getZ() + 1, this.pen.getX() + 8, this.pen.getY() + 3, this.pen.getZ() + 8);
	}

	private BlockPos gate() {
		return this.pen.add(4, 0, 0);
	}

	private List<AnimalEntity> penned() {
		return this.colonist.getWorld().getEntitiesByClass(AnimalEntity.class, this.inside(), RanchTask::livestock);
	}

	private static boolean livestock(AnimalEntity a) {
		return a.isAlive() && (a instanceof CowEntity || a instanceof SheepEntity || a instanceof PigEntity || a instanceof ChickenEntity);
	}

	private static boolean breedingItem(AnimalEntity a, net.minecraft.item.ItemStack s) {
		return a.isBreedingItem(s);
	}

	private void setGate(boolean open) {
		ServerWorld world = (ServerWorld) this.colonist.getWorld();
		BlockState s = world.getBlockState(this.gate());
		if (s.getBlock() instanceof FenceGateBlock && s.get(Properties.OPEN) != open) {
			world.setBlockState(this.gate(), s.with(Properties.OPEN, open), 2);
		}
	}

	@Override
	public void start() {
		List<AnimalEntity> herd = this.penned();
		Map<Class<?>, Integer> byType = new HashMap<>();
		for (AnimalEntity a : herd) byType.merge(a.getClass(), 1, Integer::sum);
		ServerWorld world = (ServerWorld) this.colonist.getWorld();

		if (herd.size() > 12) {
			// Cull an adult of the biggest group.
			Class<?> biggest = byType.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
			this.animal = herd.stream().filter(a -> a.getClass() == biggest && !a.isBaby()).findFirst().orElse(null);
			this.goal = Goal.CULL;
		} else if (herd.stream().anyMatch(a -> a instanceof SheepEntity s && s.isShearable())) {
			this.animal = herd.stream().filter(a -> a instanceof SheepEntity s && s.isShearable()).findFirst().orElse(null);
			this.goal = Goal.SHEAR;
		} else if (herd.size() >= 2 && herd.size() < 10 && this.findBreedingPair(herd) != null) {
			this.goal = Goal.BREED;
		} else if (herd.size() < 6) {
			this.animal = world.getEntitiesByClass(AnimalEntity.class, this.colonist.getBoundingBox().expand(40, 8, 40),
							a -> livestock(a) && !a.isBaby() && !a.hasCustomName() && !a.isLeashed() && !this.inside().contains(a.getPos())
									&& (this.colonist.factions() == null || !this.colonist.factions().isNearAnyHome(a.getBlockPos())))
					.stream().min(Comparator.comparingDouble(this.colonist::squaredDistanceTo)).orElse(null);
			this.goal = Goal.FETCH;
		}
		if (this.goal == null || (this.goal != Goal.BREED && this.animal == null)) {
			this.colonist.searchCooldown("RANCH", 1200);
			this.done = true;
		}
	}

	@Nullable
	private AnimalEntity[] findBreedingPair(List<AnimalEntity> herd) {
		for (AnimalEntity a : herd) {
			if (a.isBaby() || a.isInLove() || a.getBreedingAge() != 0) continue;
			for (AnimalEntity b : herd) {
				if (b == a || b.getClass() != a.getClass() || b.isBaby() || b.isInLove() || b.getBreedingAge() != 0) continue;
				if (ColonistBrain.count(this.colonist.getInventory(), s -> breedingItem(a, s)) >= 2) return new AnimalEntity[]{a, b};
			}
		}
		return null;
	}

	@Override
	public void tick() {
		if (this.done) return;
		ServerWorld world = (ServerWorld) this.colonist.getWorld();
		switch (this.goal) {
			case FETCH -> {
				if (this.animal == null || !this.animal.isAlive()) {
					this.done = true;
					return;
				}
				if (!this.leading) {
					if (this.approach(this.animal, 2.5, 1.0)) {
						((Leashable) this.animal).attachLeash(this.colonist, true);
						this.leading = true;
						this.resetMovement();
					} else if (this.stuck()) {
						this.done = true;
					}
					return;
				}
				// Walk the animal to the gate, open it, lead it in.
				BlockPos target = this.colonist.squaredDistanceTo(this.gate().toCenterPos()) > 9 ? this.gate().north() : this.pen.add(4, 0, 4);
				if (this.colonist.squaredDistanceTo(this.gate().toCenterPos()) < 16) this.setGate(true);
				if (this.approach(target, 1.5, 0.9) && target.equals(this.pen.add(4, 0, 4))) {
					((Leashable) this.animal).detachLeash(true, false);
					this.leading = false;
					this.goal = Goal.BREED; // walk back out and close up
					this.animal = null;
				} else if (this.stuck()) {
					((Leashable) this.animal).detachLeash(true, false);
					this.done = true;
				}
			}
			case BREED -> {
				List<AnimalEntity> herd = this.penned();
				AnimalEntity[] pair = this.findBreedingPair(herd);
				if (pair == null) {
					this.leavePen();
					return;
				}
				if (this.approach(pair[0], 3.0, 0.9)) {
					for (AnimalEntity a : pair) {
						ColonistBrain.take(this.colonist.getInventory(), s -> breedingItem(a, s), 1);
						a.lovePlayer(null);
						world.spawnParticles(ParticleTypes.HEART, a.getX(), a.getEyeY() + 0.3, a.getZ(), 3, 0.3, 0.2, 0.3, 0.0);
					}
					this.colonist.swingHand(Hand.MAIN_HAND);
				}
			}
			case SHEAR -> {
				if (!(this.animal instanceof SheepEntity sheep) || !sheep.isShearable()) {
					this.leavePen();
					return;
				}
				if (this.approach(sheep, 2.5, 0.9)) {
					sheep.sheared(SoundCategory.NEUTRAL);
					this.colonist.swingHand(Hand.MAIN_HAND);
					this.animal = null;
					this.goal = Goal.BREED;
				}
			}
			case CULL -> {
				if (this.animal == null || !this.animal.isAlive()) {
					this.leavePen();
					return;
				}
				this.colonist.setTarget(this.animal); // the melee goal takes it from here
				this.done = true;
			}
		}
	}

	private void leavePen() {
		BlockPos outside = this.gate().north(2);
		if (this.colonist.squaredDistanceTo(outside.toCenterPos()) < 4 || this.stuck()) {
			this.setGate(false);
			this.done = true;
			return;
		}
		if (this.colonist.squaredDistanceTo(this.gate().toCenterPos()) < 16) this.setGate(true);
		this.approach(outside, 1.2, 1.0);
	}

	@Override
	public void stop() {
		if (this.leading && this.animal != null && this.animal.isAlive()) ((Leashable) this.animal).detachLeash(true, false);
		if (this.colonist.squaredDistanceTo(this.gate().toCenterPos()) > 9) this.setGate(false);
	}

	public static boolean usesItems(ColonistEntity c) {
		return ColonistBrain.count(c.getInventory(), s -> s.isOf(Items.WHEAT) || s.isOf(Items.CARROT) || s.isOf(Items.WHEAT_SEEDS)) > 0;
	}
}
