package net.colonists.entity;

import net.colonists.ColonistsMod;
import net.colonists.faction.City;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Stockpile;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.EscapeDangerGoal;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtOps;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerDataContainer;
import net.minecraft.village.VillagerProfession;
import net.minecraft.village.VillagerType;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * A villager taken captive by a city-state. It keeps the villager's look and remembers the original villager
 * completely, so that freeing it (by a player, by escaping, by revolt, by abolition, or by the fall of its captors)
 * restores the very same villager with its profession and trades.
 */
public class CaptiveEntity extends PathAwareEntity implements VillagerDataContainer {
	private static final TrackedData<VillagerData> VILLAGER_DATA = DataTracker.registerData(CaptiveEntity.class, TrackedDataHandlerRegistry.VILLAGER_DATA);

	private NbtCompound villagerNbt = new NbtCompound();
	public String ownerFaction = "";
	@Nullable public UUID captor;
	private int workTimer;

	public CaptiveEntity(EntityType<? extends CaptiveEntity> type, World world) {
		super(type, world);
	}

	public static DefaultAttributeContainer.Builder createCaptiveAttributes() {
		return MobEntity.createMobAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.45)
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 48.0);
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		super.initDataTracker(builder);
		builder.add(VILLAGER_DATA, new VillagerData(VillagerType.PLAINS, VillagerProfession.NONE, 1));
	}

	@Override
	public VillagerData getVillagerData() {
		return this.dataTracker.get(VILLAGER_DATA);
	}

	@Override
	public void setVillagerData(VillagerData data) {
		this.dataTracker.set(VILLAGER_DATA, data);
	}

	@Override
	protected void initGoals() {
		this.goalSelector.add(0, new SwimGoal(this));
		this.goalSelector.add(1, new EscapeDangerGoal(this, 0.8));
		this.goalSelector.add(2, new LaborGoal(this));
		this.goalSelector.add(3, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
		this.goalSelector.add(4, new LookAroundGoal(this));
	}

	// ---------------------------------------------------------------- capture & release

	public static void capture(ServerWorld world, VillagerEntity villager, ColonistEntity captor, Faction faction) {
		CaptiveEntity captive = ColonistsMod.CAPTIVE.create(world);
		if (captive == null) return;
		captive.villagerNbt = villager.writeNbt(new NbtCompound());
		captive.setVillagerData(villager.getVillagerData());
		captive.refreshPositionAndAngles(villager.getX(), villager.getY(), villager.getZ(), villager.getYaw(), 0f);
		captive.ownerFaction = faction.id;
		captive.captor = captor.getUuid();
		captive.setPersistent();
		captive.refreshName(faction);
		villager.discard();
		world.spawnEntity(captive);
		world.spawnParticles(ParticleTypes.ANGRY_VILLAGER, captive.getX(), captive.getEyeY() + 0.3, captive.getZ(), 5, 0.3, 0.2, 0.3, 0.0);
		faction.captives++;
		FactionManager.get(world.getServer()).markDirty();
	}

	public void refreshName(@Nullable Faction f) {
		this.setCustomName(Text.literal("Captive" + (f == null ? "" : " of the " + f.name)).formatted(Formatting.GRAY));
		this.setCustomNameVisible(true);
	}

	/** Turns back into the original villager. */
	public void free(ServerWorld world) {
		VillagerEntity villager = EntityType.VILLAGER.create(world);
		if (villager != null) {
			if (this.villagerNbt.contains("Pos")) villager.readNbt(this.villagerNbt);
			villager.refreshPositionAndAngles(this.getX(), this.getY(), this.getZ(), this.getYaw(), 0f);
			villager.setVillagerData(this.getVillagerData());
			villager.setHealth(Math.max(1.0f, this.getHealth()));
			this.discard();
			world.spawnEntity(villager);
		} else {
			this.discard();
		}
		world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, this.getX(), this.getEyeY() + 0.3, this.getZ(), 10, 0.4, 0.3, 0.4, 0.0);
		FactionManager fm = FactionManager.get(world.getServer());
		Faction f = fm.get(this.ownerFaction);
		if (f != null) {
			f.captives = Math.max(0, f.captives - 1);
			fm.markDirty();
		}
	}

	// ---------------------------------------------------------------- ticking

	@Override
	public void tick() {
		super.tick();
		if (!(this.getWorld() instanceof ServerWorld world) || this.isRemoved()) return;
		if ((this.age + this.getId()) % 100 != 0) return;
		FactionManager fm = FactionManager.get(world.getServer());
		Faction f = fm.get(this.ownerFaction);
		if (f == null || !f.slavery || f.revolt) {
			// Captors gone, captivity abolished, or a revolt: walk free.
			this.free(world);
			return;
		}
		// Unwatched captives slip away.
		if ((this.age + this.getId()) % 2400 == 0 && this.random.nextInt(4) == 0) {
			boolean watched = !world.getEntitiesByClass(ColonistEntity.class, this.getBoundingBox().expand(24),
					c -> c.getFactionId().equals(this.ownerFaction) && !c.isBaby()).isEmpty();
			if (!watched) {
				FactionManager.broadcastNear(world, this.getBlockPos(), 64, Text.literal("A captive has escaped from the ")
						.formatted(Formatting.GREEN).append(f.displayName()).append(Text.literal("!").formatted(Formatting.GREEN)));
				this.free(world);
			}
		}
	}

	/** Forced labour: a share of produce goes straight into the city's stockpile. */
	void work(ServerWorld world, Faction f) {
		this.swingHand(Hand.MAIN_HAND);
		if (++this.workTimer < 30) return; // called every 40 ticks while at work, so about once a minute
		this.workTimer = 0;
		List<Inventory> chests = Stockpile.chests(world, f);
		if (chests.isEmpty()) return;
		VillagerProfession job = this.getVillagerData().getProfession();
		ItemStack produce;
		if (job == VillagerProfession.FARMER || job == VillagerProfession.NONE || job == VillagerProfession.NITWIT) produce = new ItemStack(Items.WHEAT, 3);
		else if (job == VillagerProfession.MASON || job == VillagerProfession.TOOLSMITH || job == VillagerProfession.WEAPONSMITH
				|| job == VillagerProfession.ARMORER) produce = new ItemStack(Items.COBBLESTONE, 6);
		else if (job == VillagerProfession.FISHERMAN) produce = new ItemStack(Items.COD, 2);
		else if (job == VillagerProfession.BUTCHER) produce = new ItemStack(Items.PORKCHOP, 1);
		else if (job == VillagerProfession.SHEPHERD) produce = new ItemStack(Items.WHITE_WOOL, 1);
		else produce = new ItemStack(Items.OAK_LOG, 2);
		for (Inventory inv : chests) {
			produce = ColonistBrain.insert(inv, produce);
			if (produce.isEmpty()) break;
		}
	}

	// ---------------------------------------------------------------- interaction

	@Override
	protected ActionResult interactMob(PlayerEntity player, Hand hand) {
		if (hand != Hand.MAIN_HAND || !player.getStackInHand(hand).isEmpty()) return super.interactMob(player, hand);
		if (!(this.getWorld() instanceof ServerWorld world) || !(player instanceof ServerPlayerEntity sp)) return ActionResult.SUCCESS;
		FactionManager fm = FactionManager.get(world.getServer());
		Faction f = fm.get(this.ownerFaction);
		if (f != null && fm.standing(player, f.id) >= 100 && !player.isSneaking()) {
			sp.sendMessage(Text.literal("A captive of the ").formatted(Formatting.GRAY).append(f.displayName())
					.append(Text.literal(". Sneak + right-click to set them free.").formatted(Formatting.GRAY)), false);
			return ActionResult.SUCCESS;
		}
		if (f != null) {
			fm.changeRep(world.getServer(), player, f.id, -15);
			for (Faction other : fm.all()) {
				if (other != f && !other.slavery && other.founderPlayer == null) fm.changeRep(world.getServer(), player, other.id, 4);
			}
		}
		sp.sendMessage(Text.literal("You set the captive free.").formatted(Formatting.GREEN), false);
		this.free(world);
		return ActionResult.SUCCESS;
	}

	@Override
	public void onDeath(DamageSource source) {
		boolean alreadyDead = this.dead;
		super.onDeath(source);
		if (!alreadyDead && this.getWorld() instanceof ServerWorld world) {
			FactionManager fm = FactionManager.get(world.getServer());
			Faction f = fm.get(this.ownerFaction);
			if (f != null) {
				f.captives = Math.max(0, f.captives - 1);
				fm.markDirty();
			}
		}
	}

	// ---------------------------------------------------------------- save & load

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		nbt.put("Villager", this.villagerNbt);
		nbt.putString("Owner", this.ownerFaction);
		if (this.captor != null) nbt.putUuid("Captor", this.captor);
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		this.villagerNbt = nbt.getCompound("Villager");
		this.ownerFaction = nbt.getString("Owner");
		this.captor = nbt.containsUuid("Captor") ? nbt.getUuid("Captor") : null;
		if (this.villagerNbt.contains("VillagerData")) {
			VillagerData.CODEC.parse(NbtOps.INSTANCE, this.villagerNbt.get("VillagerData")).result().ifPresent(this::setVillagerData);
		}
	}

	// ---------------------------------------------------------------- AI

	/** Follow the captor home, then work the fields (or the plaza) by day and stay on the plaza at night. */
	static class LaborGoal extends Goal {
		private final CaptiveEntity captive;
		private int timer;

		LaborGoal(CaptiveEntity captive) {
			this.captive = captive;
			this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
		}

		@Override
		public boolean canStart() {
			return true;
		}

		@Override
		public boolean shouldContinue() {
			return true;
		}

		@Override
		public void tick() {
			if (--this.timer > 0) return;
			this.timer = 40;
			if (!(this.captive.getWorld() instanceof ServerWorld world)) return;
			Faction f = FactionManager.get(world.getServer()).get(this.captive.ownerFaction);
			if (f == null) return;
			BlockPos center = f.cityCenter;

			Entity captor = this.captive.captor == null ? null : world.getEntity(this.captive.captor);
			boolean home = center != null && this.captive.getBlockPos().getSquaredDistance(center) < 28 * 28;
			if (captor != null && captor.isAlive() && !home && this.captive.squaredDistanceTo(captor) < 64 * 64) {
				if (this.captive.squaredDistanceTo(captor) > 9) this.captive.getNavigation().startMovingTo(captor, 1.0);
				return;
			}
			if (center == null) return;
			BlockPos goal = center.north(2);
			boolean working = world.isDay();
			if (working && !f.farms.isEmpty()) {
				BlockPos field = f.farms.iterator().next();
				for (BlockPos farm : f.farms) {
					if (farm.getSquaredDistance(this.captive.getBlockPos()) < field.getSquaredDistance(this.captive.getBlockPos())) field = farm;
				}
				goal = field.add(4, 0, 2);
			}
			double distance = this.captive.getBlockPos().getSquaredDistance(goal);
			if (distance > 6 * 6) {
				walkToward(goal, distance);
			} else if (working) {
				this.captive.getLookControl().lookAt(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5);
				this.captive.work(world, f);
			}
		}

		private void walkToward(BlockPos goal, double squaredDistance) {
			if (squaredDistance > 40 * 40) {
				// Too far to path in one go: head for a waypoint in the right direction.
				Vec3d from = this.captive.getPos();
				Vec3d dir = Vec3d.ofCenter(goal).subtract(from).normalize().multiply(24);
				int x = (int) (from.x + dir.x), z = (int) (from.z + dir.z);
				int y = City.surfaceYOr(this.captive.getWorld(), x, z, this.captive.getBlockY());
				this.captive.getNavigation().startMovingTo(x + 0.5, y, z + 0.5, 0.8);
			} else {
				this.captive.getNavigation().startMovingTo(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 0.8);
			}
		}
	}
}
