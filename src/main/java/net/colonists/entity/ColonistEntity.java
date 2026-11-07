package net.colonists.entity;

import net.colonists.ColonistsMod;
import net.colonists.ai.RetreatGoal;
import net.colonists.ai.WorkGoal;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.minecraft.block.BedBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityData;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.InventoryOwner;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.FleeEntityGoal;
import net.minecraft.entity.ai.goal.LongDoorInteractGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.particle.ItemStackParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An autonomous, player-shaped NPC. It keeps its own inventory, gets hungry, gathers, crafts,
 * builds a home, farms, sleeps, fights, chats with neighbours, and belongs to a faction.
 */
public class ColonistEntity extends PathAwareEntity implements InventoryOwner {
	private static final TrackedData<Integer> SKIN = DataTracker.registerData(ColonistEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Boolean> CHILD = DataTracker.registerData(ColonistEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	/** One Minecraft day from birth to adulthood. */
	public static final int GROW_UP_TICKS = 24000;

	private static final String[] SURNAMES = {"Oakley", "Stone", "Miller", "Fletcher", "Brook", "Ashford", "Hale", "Thorne",
			"Carver", "Wells", "Marsh", "Cobb", "Flint", "Hollow", "Reed", "Barrow", "Tanner", "Holt", "Pike", "Smith",
			"Gale", "Rook", "Ember", "Frost", "Moss", "Birch", "Copper", "Dale", "Wolfe", "Hart"};

	public enum Job {
		NONE("Child"), FARMER("Farmer"), RANCHER("Rancher"), WOODCUTTER("Lumberjack"), MINER("Miner"),
		BLACKSMITH("Blacksmith"), COOK("Cook"), BUILDER("Builder"), GUARD("Guard");

		public final String label;

		Job(String label) {
			this.label = label;
		}
	}
	public static final int SKIN_COUNT = 9;

	private static final String[] FIRST_NAMES = {"Steve", "Alex", "Ari", "Efe", "Kai", "Makena", "Noor", "Sunny", "Zuri",
			"Bjorn", "Mira", "Oskar", "Lena", "Tobias", "Ivy", "Rowan", "Hazel", "Felix", "Nora", "Jasper", "Willa",
			"Otto", "Freya", "Silas", "Elsie", "Hugo", "Tessa", "Arlo", "Maple", "Gus", "Clover", "Bram", "Juniper",
			"Wren", "Edmund", "Pip", "Greta", "Milo", "Sage", "Ansel", "Dottie", "Ronan", "Petra", "Cyrus", "Lark"};

	public enum ToolKind { NONE, WEAPON, AXE, PICKAXE, HOE, BUILD }

	private final SimpleInventory inventory = new SimpleInventory(27);
	private String factionId = "";
	private String firstName = "";
	/** Faction to join on spawn (set by commands / births before initialize). */
	@Nullable public String pendingFaction;

	public int hunger = 20;
	private String surname = "";
	public int ambition, aggression, kindness;
	public Job job = Job.NONE;
	@Nullable public UUID partner;
	public String partnerName = "";
	@Nullable public UUID parentA, parentB;
	public int growUpTicks;
	public long lastBirthTime;
	/** In-game day this colonist was born, and how many days they will live. */
	public long birthDay = Long.MIN_VALUE;
	public int lifespanDays;
	private boolean diedOfOldAge;
	private final Map<UUID, Integer> affinity = new HashMap<>();
	/** Children (and grown children without a house yet) sleep at their parents' house. */
	@Nullable public BlockPos parentHome;
	public int fuel;
	private int hungerTimer;
	private int eatCooldown;
	private int lastPlayerHitAge = -100;

	@Nullable public BlockPos home;
	@Nullable public BlockPos bed;
	@Nullable public BlockPos chest;
	@Nullable public BlockPos buildSite;

	public String activity = "Idle";
	/** What the last building job ran short of ("wood" or "stone"), so the planner can fetch it. */
	public String needs = "";
	public long lastSocialTime;
	public ToolKind wantedTool = ToolKind.WEAPON;
	private final Map<BlockPos, Long> blacklist = new HashMap<>();
	private final Map<String, Long> searchCooldowns = new HashMap<>();

	public ColonistEntity(EntityType<? extends ColonistEntity> type, World world) {
		super(type, world);
		this.setCanPickUpLoot(true);
		if (this.getNavigation() instanceof MobNavigation nav) {
			nav.setCanPathThroughDoors(true);
			nav.setCanEnterOpenDoors(true);
		}
		this.getNavigation().setCanSwim(true);
		this.setEquipmentDropChance(EquipmentSlot.MAINHAND, 0.0f); // held item is a display copy of an inventory tool
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			this.setEquipmentDropChance(slot, 2.0f);
		}
	}

	public static DefaultAttributeContainer.Builder createColonistAttributes() {
		return MobEntity.createMobAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 1.0)
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 32.0);
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		super.initDataTracker(builder);
		builder.add(SKIN, 0);
		builder.add(CHILD, false);
	}

	@Override
	protected void initGoals() {
		this.goalSelector.add(0, new SwimGoal(this));
		this.goalSelector.add(1, new RetreatGoal(this));
		this.goalSelector.add(1, new FleeEntityGoal<>(this, CreeperEntity.class, 6.0f, 1.0, 1.3));
		this.goalSelector.add(2, new MeleeAttackGoal(this, 1.2, true));
		this.goalSelector.add(3, new LongDoorInteractGoal(this, true));
		this.goalSelector.add(4, new WorkGoal(this));
		this.goalSelector.add(6, new WanderAroundFarGoal(this, 0.8));
		this.goalSelector.add(7, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
		this.goalSelector.add(7, new LookAtEntityGoal(this, ColonistEntity.class, 8.0f));
		this.goalSelector.add(8, new LookAroundGoal(this));

		this.targetSelector.add(1, new RevengeGoal(this));
		// Defend friends: go after anything that is going after one of us.
		this.targetSelector.add(2, new ActiveTargetGoal<>(this, MobEntity.class, 5, true, false,
				e -> e instanceof MobEntity m && m.getTarget() != null && this.isFriendly(m.getTarget()) && !this.isFriendly(e)));
		this.targetSelector.add(3, new ActiveTargetGoal<>(this, ColonistEntity.class, 10, true, false, this::isEnemy));
		this.targetSelector.add(3, new ActiveTargetGoal<>(this, PlayerEntity.class, 10, true, false, this::isEnemy));
		this.targetSelector.add(4, new ActiveTargetGoal<>(this, HostileEntity.class, 10, true, false,
				e -> !(e instanceof CreeperEntity) && !(e instanceof Angerable) && this.squaredDistanceTo(e) < 16 * 16));
	}

	// ---------------------------------------------------------------- identity & factions

	@Override
	public EntityData initialize(ServerWorldAccess world, LocalDifficulty difficulty, SpawnReason spawnReason, @Nullable EntityData entityData) {
		EntityData data = super.initialize(world, difficulty, spawnReason, entityData);
		this.ensureIdentity();
		return data;
	}

	private void ensureIdentity() {
		if (!(this.getWorld() instanceof ServerWorld sw)) return;
		if (this.firstName.isEmpty()) {
			this.firstName = FIRST_NAMES[this.random.nextInt(FIRST_NAMES.length)];
			this.dataTracker.set(SKIN, this.random.nextInt(SKIN_COUNT));
			this.setPersistent();
			if (this.surname.isEmpty()) this.surname = SURNAMES[this.random.nextInt(SURNAMES.length)];
			if (this.birthDay == Long.MIN_VALUE) this.birthDay = today(sw) - 1 - this.random.nextInt(6); // settlers arrive as young adults
			if (!this.isBaby() && this.pendingFaction == null) {
				// New settlers bring provisions and seed for the first harvest.
				ColonistBrain.give(this, new ItemStack(Items.BREAD, 6));
				ColonistBrain.give(this, new ItemStack(Items.WHEAT_SEEDS, 8));
			}
			if (this.ambition == 0 && this.aggression == 0 && this.kindness == 0) {
				this.ambition = 10 + this.random.nextInt(81);
				this.aggression = 10 + this.random.nextInt(81);
				this.kindness = 10 + this.random.nextInt(81);
			}
		}
		FactionManager fm = FactionManager.get(sw.getServer());
		if (fm.get(this.factionId) == null) {
			String join = this.pendingFaction;
			if (fm.get(join) == null) {
				join = null;
				int max = sw.getGameRules().getInt(ColonistsMod.MAX_PER_FACTION);
				ColonistEntity nearest = sw.getEntitiesByClass(ColonistEntity.class, this.getBoundingBox().expand(48),
								c -> c != this && fm.get(c.factionId) != null)
						.stream().min(Comparator.comparingDouble(this::squaredDistanceTo)).orElse(null);
				if (nearest != null && (max <= 0 || fm.memberCount(nearest.factionId) < max)) join = nearest.factionId;
			}
			Faction f = fm.get(join);
			if (f == null) {
				f = fm.createFaction(this.random, null);
				FactionManager.broadcast(sw.getServer(), Text.literal(this.firstName + " has founded a new faction: ")
						.formatted(Formatting.YELLOW).append(f.displayName()));
			}
			this.setFaction(f.id);
		}
		this.pendingFaction = null;
		if (!this.isBaby() && this.job == Job.NONE) this.job = net.colonists.faction.Politics.chooseJob(fm.get(this.factionId), this.getUuid());
		this.syncCitizen();
		this.refreshName();
	}

	/** Copies this colonist's details into the faction's records (used by politics while chunks are unloaded). */
	public void syncCitizen() {
		FactionManager fm = this.factions();
		Faction f = fm == null ? null : fm.get(this.factionId);
		if (f == null) return;
		Faction.Citizen c = f.citizens.computeIfAbsent(this.getUuid(), u -> new Faction.Citizen());
		c.name = this.firstName;
		c.surname = this.surname;
		c.ambition = this.ambition;
		c.aggression = this.aggression;
		c.kindness = this.kindness;
		c.child = this.isBaby();
		c.job = this.job.name();
		int liked = 0;
		for (int v : this.affinity.values()) liked += Math.max(0, v);
		c.popularity = Math.min(100, liked / 4);
		fm.markDirty();
	}

	public String fullName() {
		return this.surname.isEmpty() ? this.firstName : this.firstName + " " + this.surname;
	}

	public String getSurname() {
		return this.surname;
	}

	public boolean isRuler() {
		FactionManager fm = this.factions();
		Faction f = fm == null ? null : fm.get(this.factionId);
		return f != null && this.getUuid().equals(f.ruler);
	}

	// ---------------------------------------------------------------- family

	@Override
	public boolean isBaby() {
		return this.dataTracker.get(CHILD);
	}

	public void setChild(boolean child) {
		this.dataTracker.set(CHILD, child);
		this.growUpTicks = child ? GROW_UP_TICKS : 0;
	}

	@Override
	public void onTrackedDataSet(TrackedData<?> data) {
		super.onTrackedDataSet(data);
		if (CHILD.equals(data)) this.calculateDimensions();
	}

	public static long today(World world) {
		return world.getTimeOfDay() / 24000L;
	}

	public long ageDays() {
		return this.birthDay == Long.MIN_VALUE ? 0 : today(this.getWorld()) - this.birthDay;
	}

	private void ensureLifespan(ServerWorld sw) {
		if (this.birthDay == Long.MIN_VALUE) this.birthDay = today(sw) - this.random.nextInt(6);
		if (this.lifespanDays <= 0) {
			int min = sw.getGameRules().getInt(ColonistsMod.LIFESPAN_MIN);
			int max = Math.max(min, sw.getGameRules().getInt(ColonistsMod.LIFESPAN_MAX));
			this.lifespanDays = min + this.random.nextInt(max - min + 1);
		}
	}

	/** Old age: the house passes to a spouse or child who has none, belongings go into the house chest. */
	private void dieOfOldAge(ServerWorld sw) {
		this.diedOfOldAge = true;
		FactionManager fm = FactionManager.get(sw.getServer());
		if (this.chest != null && sw.getBlockEntity(this.chest) instanceof net.minecraft.inventory.Inventory box) {
			for (int i = 0; i < this.inventory.size(); i++) {
				ItemStack rest = ColonistBrain.insert(box, this.inventory.getStack(i));
				this.inventory.setStack(i, rest);
			}
		}
		FactionManager.broadcastNear(sw, this.getBlockPos(), 160, Text.literal("🕯 " + this.fullName() + " of the ")
				.formatted(Formatting.GRAY).append(fm.get(this.factionId) == null ? Text.literal("wilds") : fm.get(this.factionId).displayName())
				.append(Text.literal(" has passed away of old age, " + this.ageDays() + " days old.").formatted(Formatting.GRAY)));
		this.kill();
	}

	/** Makes this colonist the newborn child of two parents. */
	public void bornTo(ColonistEntity a, @Nullable ColonistEntity b) {
		this.setChild(true);
		this.birthDay = today(a.getWorld());
		this.parentA = a.getUuid();
		this.parentB = b == null ? null : b.getUuid();
		this.surname = a.surname;
		this.parentHome = a.home != null ? a.home : (b != null ? b.home : null);
		int ambition = b == null ? a.ambition : (a.ambition + b.ambition) / 2;
		int aggression = b == null ? a.aggression : (a.aggression + b.aggression) / 2;
		int kindness = b == null ? a.kindness : (a.kindness + b.kindness) / 2;
		this.ambition = MathHelper.clamp(ambition + this.random.nextInt(31) - 15, 1, 100);
		this.aggression = MathHelper.clamp(aggression + this.random.nextInt(31) - 15, 1, 100);
		this.kindness = MathHelper.clamp(kindness + this.random.nextInt(31) - 15, 1, 100);
		this.job = Job.NONE;
	}

	public boolean isFamily(ColonistEntity other) {
		UUID me = this.getUuid(), them = other.getUuid();
		if (me.equals(other.parentA) || me.equals(other.parentB) || them.equals(this.parentA) || them.equals(this.parentB)) return true;
		return this.parentA != null && (this.parentA.equals(other.parentA) || this.parentA.equals(other.parentB));
	}

	public int affinityWith(UUID other) {
		return this.affinity.getOrDefault(other, 0);
	}

	public void addAffinity(UUID other, int amount) {
		this.affinity.merge(other, amount, Integer::sum);
		if (this.affinity.size() > 24) {
			UUID weakest = this.affinity.entrySet().stream().min(Map.Entry.comparingByValue()).get().getKey();
			this.affinity.remove(weakest);
		}
	}

	public void marry(ColonistEntity other) {
		this.partner = other.getUuid();
		this.partnerName = other.fullName();
		other.partner = this.getUuid();
		other.partnerName = this.fullName();
		this.lastBirthTime = other.lastBirthTime = this.getWorld().getTime();
		if (this.getWorld() instanceof ServerWorld sw) {
			sw.spawnParticles(ParticleTypes.HEART, this.getX(), this.getEyeY() + 0.5, this.getZ(), 8, 0.5, 0.4, 0.5, 0.0);
			sw.spawnParticles(ParticleTypes.HEART, other.getX(), other.getEyeY() + 0.5, other.getZ(), 8, 0.5, 0.4, 0.5, 0.0);
			FactionManager.broadcastNear(sw, this.getBlockPos(), 96, Text.literal("💍 " + this.fullName() + " and " + other.fullName()
					+ " are now married!").formatted(Formatting.LIGHT_PURPLE));
		}
	}

	public String getFactionId() {
		return this.factionId;
	}

	public String getFirstName() {
		return this.firstName;
	}

	public int getSkin() {
		return this.dataTracker.get(SKIN);
	}

	@Nullable
	public FactionManager factions() {
		return this.getWorld() instanceof ServerWorld sw ? FactionManager.get(sw.getServer()) : null;
	}

	@Nullable
	public MinecraftServer server() {
		return this.getWorld() instanceof ServerWorld sw ? sw.getServer() : null;
	}

	public void setFaction(String id) {
		FactionManager fm = this.factions();
		if (fm == null) return;
		if (!this.factionId.isEmpty() && !this.factionId.equals(id)) {
			fm.removeMember(this.server(), this.factionId, this.getUuid());
			// A house belongs to the faction that built it, so leaving means moving out.
			this.home = null;
			this.bed = null;
			this.chest = null;
			this.buildSite = null;
		}
		this.factionId = id;
		fm.addMember(id, this.getUuid());
		this.syncCitizen();
		this.refreshName();
	}

	public void refreshName() {
		FactionManager fm = this.factions();
		Faction f = fm == null ? null : fm.get(this.factionId);
		MutableText name = Text.literal((f != null && this.getUuid().equals(f.ruler) ? "♔ " : "") + this.fullName());
		if (f != null) {
			name = name.formatted(f.color).append(Text.literal(" [" + f.name + "]").formatted(Formatting.GRAY));
		}
		this.setCustomName(name);
		this.setCustomNameVisible(true);
	}

	public Formatting nameColor() {
		FactionManager fm = this.factions();
		Faction f = fm == null ? null : fm.get(this.factionId);
		return f == null ? Formatting.WHITE : f.color;
	}

	/** Same faction, an allied faction, or a player who is a member/ally. */
	public boolean isFriendly(@Nullable LivingEntity e) {
		if (e == null) return false;
		if (e == this) return true;
		FactionManager fm = this.factions();
		if (fm == null) return false;
		if (e instanceof ColonistEntity c) return fm.relation(this.factionId, c.factionId) >= FactionManager.ALLY;
		if (e instanceof PlayerEntity p) return fm.standing(p, this.factionId) >= FactionManager.ALLY;
		if (e instanceof TameableEntity t && t.getOwner() instanceof PlayerEntity owner) return this.isFriendly(owner);
		return false;
	}

	public boolean isEnemy(LivingEntity e) {
		FactionManager fm = this.factions();
		if (fm == null || e == this || !e.isAlive()) return false;
		if (this.isBaby()) return false;
		if (e instanceof ColonistEntity c) return !c.isBaby() && fm.relation(this.factionId, c.factionId) <= FactionManager.WAR;
		if (e instanceof PlayerEntity p) return !p.isCreative() && !p.isSpectator() && fm.standing(p, this.factionId) <= FactionManager.WAR;
		return false;
	}

	@Override
	public boolean canTarget(LivingEntity target) {
		if (this.isBaby() || this.isFriendly(target)) return false;
		return super.canTarget(target);
	}

	// ---------------------------------------------------------------- ticking

	@Override
	public void tick() {
		super.tick();
		if (!(this.getWorld() instanceof ServerWorld sw)) return;
		if (this.firstName.isEmpty() || (this.age % 200 == 0 && this.factions() != null && this.factions().get(this.factionId) == null)) {
			this.ensureIdentity();
		}
		// The AI is frozen while asleep, so waking up has to happen here.
		if (this.isSleeping() && (sw.isDay() || this.getTarget() != null || this.hunger <= 3)) {
			this.wakeUp();
		}
		this.tickNeeds(sw);
		int phase = this.age + this.getId();
		if (phase % 100 == 0) ColonistBrain.craft(this);
		if (phase % 10 == 0) this.updateHeldItem();
		if (phase % 40 == 0) this.attractHostiles(sw);
		if (phase % 600 == 0) {
			long now = sw.getTime();
			this.blacklist.values().removeIf(t -> t < now);
			this.searchCooldowns.values().removeIf(t -> t < now);
		}
		if (phase % 2400 == 0) Society.tick(this, sw);
		if (phase % 600 == 0) {
			this.syncCitizen();
			this.refreshName();
		}
		if (this.isBaby() && --this.growUpTicks <= 0) this.growUp(sw);
		if (phase % 1200 == 0) {
			this.ensureLifespan(sw);
			if (!this.isBaby() && this.ageDays() >= this.lifespanDays && !this.diedOfOldAge) this.dieOfOldAge(sw);
		}
	}

	private void growUp(ServerWorld sw) {
		this.setChild(false);
		FactionManager fm = FactionManager.get(sw.getServer());
		this.job = net.colonists.faction.Politics.chooseJob(fm.get(this.factionId), this.getUuid());
		this.syncCitizen();
		this.say("I'm all grown up! I'll be a " + this.job.label.toLowerCase() + ".");
	}

	/** Vanilla monsters only hunt players and villagers; make them notice colonists too. */
	private void attractHostiles(ServerWorld sw) {
		// Only colonists caught out in the open: indoors (no sky overhead) they are safe.
		if (this.isSleeping() || !sw.isSkyVisible(this.getBlockPos().up())) return;
		for (HostileEntity monster : sw.getEntitiesByClass(HostileEntity.class, this.getBoundingBox().expand(12, 5, 12),
				m -> m.getTarget() == null && !(m instanceof Angerable) && m.canSee(this))) {
			if (this.random.nextInt(6) == 0) monster.setTarget(this);
		}
	}

	private void tickNeeds(ServerWorld sw) {
		// About 2.5 in-game days from full to starving.
		if (++this.hungerTimer >= (this.isSleeping() ? 6000 : 3000)) {
			this.hungerTimer = 0;
			if (this.hunger > 0) this.hunger--;
		}
		if (this.hunger <= 0 && this.age % 200 == 0) {
			this.damage(this.getDamageSources().starve(), 1.0f);
		}
		if (this.hunger >= 14 && this.getHealth() < this.getMaxHealth() && this.age % 60 == 0) {
			this.heal(1.0f);
		}
		if (this.eatCooldown > 0) {
			this.eatCooldown--;
		} else if (this.hunger <= 14 && !this.isSleeping() && this.tryEat(sw)) {
			this.eatCooldown = 40;
		}
	}

	private boolean tryEat(ServerWorld sw) {
		ItemStack best = ItemStack.EMPTY;
		int bestNutrition = 0;
		for (int i = 0; i < this.inventory.size(); i++) {
			ItemStack s = this.inventory.getStack(i);
			FoodComponent food = s.get(DataComponentTypes.FOOD);
			if (food == null) continue;
			boolean desperate = this.hunger <= 4;
			if (ColonistBrain.isBadFood(s) && !(desperate && s.isOf(Items.ROTTEN_FLESH))) continue;
			if (food.nutrition() > bestNutrition) {
				best = s;
				bestNutrition = food.nutrition();
			}
		}
		if (best.isEmpty()) return false;
		sw.spawnParticles(new ItemStackParticleEffect(ParticleTypes.ITEM, best.copyWithCount(1)),
				this.getX(), this.getEyeY() - 0.2, this.getZ(), 6, 0.15, 0.1, 0.15, 0.05);
		sw.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_GENERIC_EAT, SoundCategory.NEUTRAL, 0.8f, 1.0f);
		best.decrement(1);
		this.inventory.markDirty();
		this.hunger = Math.min(20, this.hunger + bestNutrition);
		this.swingHand(Hand.MAIN_HAND);
		return true;
	}

	public void updateHeldItem() {
		ItemStack want;
		if (this.getTarget() != null) {
			want = ColonistBrain.bestWeapon(this.inventory);
		} else {
			want = switch (this.wantedTool) {
				case AXE -> ColonistBrain.best(this.inventory, ColonistBrain.AXES);
				case PICKAXE -> ColonistBrain.best(this.inventory, ColonistBrain.PICKAXES);
				case HOE -> ColonistBrain.best(this.inventory, ColonistBrain.HOES);
				case BUILD -> ColonistBrain.findFirst(this.inventory, ColonistBrain.PLANKS).copyWithCount(1);
				case WEAPON -> ColonistBrain.bestWeapon(this.inventory);
				case NONE -> ItemStack.EMPTY;
			};
		}
		if (!ItemStack.areItemsEqual(this.getMainHandStack(), want)) {
			this.equipStack(EquipmentSlot.MAINHAND, want.isEmpty() ? ItemStack.EMPTY : want.copyWithCount(1));
		}
	}

	// ---------------------------------------------------------------- memory helpers used by tasks

	public boolean isBlacklisted(BlockPos pos) {
		Long until = this.blacklist.get(pos);
		return until != null && until > this.getWorld().getTime();
	}

	public void blacklist(BlockPos pos, int ticks) {
		this.blacklist.put(pos.toImmutable(), this.getWorld().getTime() + ticks);
	}

	public boolean onSearchCooldown(String key) {
		Long until = this.searchCooldowns.get(key);
		return until != null && until > this.getWorld().getTime();
	}

	public void searchCooldown(String key, int ticks) {
		this.searchCooldowns.put(key, this.getWorld().getTime() + ticks);
	}

	public boolean hasValidBed() {
		return this.bed != null && this.getWorld().getBlockState(this.bed).getBlock() instanceof BedBlock;
	}

	/** Says something in chat to players nearby. */
	public void say(String message) {
		if (!(this.getWorld() instanceof ServerWorld sw) || !sw.getGameRules().getBoolean(ColonistsMod.CHATTER)) return;
		Text text = Text.literal("<").append(Text.literal(this.firstName).formatted(this.nameColor())).append("> " + message);
		for (ServerPlayerEntity p : sw.getPlayers(p -> p.squaredDistanceTo(this) < 20 * 20)) {
			p.sendMessage(text, false);
		}
	}

	private void tell(ServerPlayerEntity player, String message) {
		player.sendMessage(Text.literal("<").append(Text.literal(this.firstName).formatted(this.nameColor())).append("> " + message), false);
	}

	// ---------------------------------------------------------------- inventory & loot

	@Override
	public SimpleInventory getInventory() {
		return this.inventory;
	}

	@Override
	public boolean canGather(ItemStack stack) {
		return this.inventory.canInsert(stack);
	}

	@Override
	protected void loot(ItemEntity item) {
		InventoryOwner.pickUpItem(this, this, item);
	}

	/** Picks up an item directly (works even when the mobGriefing rule blocks normal mob pickup). */
	public void pickUp(ItemEntity item) {
		if (item.isAlive() && !item.cannotPickup() && this.canGather(item.getStack())) this.loot(item);
	}

	// ---------------------------------------------------------------- interaction

	@Override
	protected ActionResult interactMob(PlayerEntity player, Hand hand) {
		ItemStack held = player.getStackInHand(hand);
		if (hand != Hand.MAIN_HAND || held.getItem() instanceof SpawnEggItem || held.isOf(Items.NAME_TAG)) {
			return super.interactMob(player, hand);
		}
		if (this.getWorld().isClient) return ActionResult.SUCCESS;
		if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
		FactionManager fm = this.factions();
		if (fm == null) return ActionResult.PASS;

		if (held.isEmpty()) {
			this.sendStatus(sp, fm);
			return ActionResult.SUCCESS;
		}
		if (player.isSneaking() && held.isOf(Items.EMERALD)) {
			this.tryRecruit(sp, fm, held);
			return ActionResult.SUCCESS;
		}
		if (fm.standing(player, this.factionId) <= FactionManager.WAR) {
			this.tell(sp, "I want nothing from you.");
			return ActionResult.SUCCESS;
		}
		// Gift: hand over the whole stack.
		int before = held.getCount();
		ItemStack rest = this.inventory.addStack(held.copy());
		int given = before - rest.getCount();
		if (given <= 0) {
			this.tell(sp, "My pockets are full, sorry.");
			return ActionResult.SUCCESS;
		}
		int value = Math.min(25, giftValue(held, given));
		if (!player.getAbilities().creativeMode) held.decrement(given);
		if (value > 0) fm.changeRep(this.server(), player, this.factionId, value);
		((ServerWorld) this.getWorld()).spawnParticles(ParticleTypes.HAPPY_VILLAGER, this.getX(), this.getEyeY() + 0.3, this.getZ(), 8, 0.4, 0.3, 0.4, 0.0);
		String[] thanks = value >= 10 ? new String[]{"This is generous! The " + factionName() + " won't forget it.", "Wow, thank you!"}
				: value > 0 ? new String[]{"Thanks, friend.", "That's kind of you.", "Much appreciated."}
				: new String[]{"Uh... thanks, I guess.", "I'll find a use for it."};
		this.tell(sp, thanks[this.random.nextInt(thanks.length)]);
		return ActionResult.SUCCESS;
	}

	private static int giftValue(ItemStack stack, int count) {
		double each;
		if (stack.isOf(Items.DIAMOND)) each = 10;
		else if (stack.isOf(Items.EMERALD)) each = 6;
		else if (stack.isOf(Items.GOLD_INGOT)) each = 4;
		else if (stack.isOf(Items.IRON_INGOT)) each = 3;
		else if (ColonistBrain.isTool(stack)) each = 6;
		else if (stack.contains(DataComponentTypes.FOOD)) each = Math.max(0.5, stack.get(DataComponentTypes.FOOD).nutrition() / 3.0);
		else if (stack.isIn(ItemTags.LOGS) || stack.isIn(ItemTags.WOOL)) each = 0.5;
		else each = 0.1;
		return (int) Math.round(each * count);
	}

	private void tryRecruit(ServerPlayerEntity player, FactionManager fm, ItemStack emeralds) {
		String pf = fm.playerFaction(player.getUuid());
		if (pf == null) {
			this.tell(player, "Recruit me to what? You don't have a faction. (/colony found <name>)");
			return;
		}
		if (pf.equals(this.factionId)) {
			this.tell(player, "We're already on the same side!");
			return;
		}
		int standing = fm.standing(player, this.factionId);
		if (standing < 40) {
			this.tell(player, "I barely know you. (" + standing + " standing, need 40)");
			return;
		}
		String old = this.factionId;
		if (!player.getAbilities().creativeMode) emeralds.decrement(1);
		this.setFaction(pf);
		fm.changeRelation(this.server(), old, pf, -8);
		this.setTarget(null);
		this.tell(player, "Alright, I'm with you. For the " + factionName() + "!");
	}

	private String factionName() {
		FactionManager fm = this.factions();
		Faction f = fm == null ? null : fm.get(this.factionId);
		return f == null ? "colony" : f.name;
	}

	private void sendStatus(ServerPlayerEntity player, FactionManager fm) {
		Faction f = fm.get(this.factionId);
		int standing = fm.standing(player, this.factionId);
		MutableText header = Text.literal("— ").formatted(Formatting.DARK_GRAY)
				.append(Text.literal(this.firstName).formatted(this.nameColor(), Formatting.BOLD));
		if (f != null) header.append(Text.literal(" of the ").formatted(Formatting.GRAY)).append(f.displayName());
		header.append(Text.literal(" — " + this.activity).formatted(Formatting.GRAY));
		player.sendMessage(header, false);
		player.sendMessage(Text.literal(String.format("Health %d/%d   Food %d/20   Home: %s   Bed: %s",
				(int) Math.ceil(this.getHealth()), (int) this.getMaxHealth(), this.hunger,
				this.home == null ? (this.buildSite != null ? "building" : "none") : this.home.toShortString(),
				this.hasValidBed() ? "yes" : "no")).formatted(Formatting.WHITE), false);
		player.sendMessage(Text.literal(String.format("%s   Age %d of %d days   Spouse: %s   Ambition %d · Aggression %d · Kindness %d",
				this.isBaby() ? "Child (grows up in " + (this.growUpTicks / 1200) + " min)" : this.job.label,
				this.ageDays(), this.lifespanDays,
				this.partner == null ? "none" : this.partnerName, this.ambition, this.aggression, this.kindness)).formatted(Formatting.WHITE), false);
		player.sendMessage(Text.literal("Your standing: ").formatted(Formatting.GRAY)
				.append(Text.literal(FactionManager.describe(standing) + " (" + standing + ")").formatted(FactionManager.describeColor(standing))), false);
		Map<String, Integer> counts = new HashMap<>();
		for (int i = 0; i < this.inventory.size(); i++) {
			ItemStack s = this.inventory.getStack(i);
			if (!s.isEmpty()) counts.merge(s.getName().getString(), s.getCount(), Integer::sum);
		}
		List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
		list.sort((a, b) -> b.getValue() - a.getValue());
		StringBuilder sb = new StringBuilder("Carrying: ");
		if (list.isEmpty()) sb.append("nothing");
		for (int i = 0; i < Math.min(8, list.size()); i++) {
			if (i > 0) sb.append(", ");
			sb.append(list.get(i).getValue()).append(" ").append(list.get(i).getKey());
		}
		player.sendMessage(Text.literal(sb.toString()).formatted(Formatting.GRAY), false);
	}

	// ---------------------------------------------------------------- combat & death

	@Override
	public boolean damage(DamageSource source, float amount) {
		boolean hurt = super.damage(source, amount);
		if (hurt && this.getWorld() instanceof ServerWorld sw) {
			FactionManager fm = FactionManager.get(sw.getServer());
			Entity attacker = source.getAttacker();
			if (attacker instanceof ColonistEntity c && !c.factionId.equals(this.factionId)) {
				fm.changeRelation(sw.getServer(), this.factionId, c.factionId, -3);
			} else if (attacker instanceof ServerPlayerEntity p && this.age - this.lastPlayerHitAge > 10) {
				this.lastPlayerHitAge = this.age;
				fm.changeRep(sw.getServer(), p, this.factionId, -8);
				String pf = fm.playerFaction(p.getUuid());
				if (pf != null && !pf.equals(this.factionId)) fm.changeRelation(sw.getServer(), pf, this.factionId, -2);
			}
			if (attacker instanceof LivingEntity le && le != this && !this.isFriendly(le)) {
				for (ColonistEntity friend : sw.getEntitiesByClass(ColonistEntity.class, this.getBoundingBox().expand(16),
						c -> c != this && c.getTarget() == null && c.isFriendly(this) && !c.isFriendly(le) && !c.isSleeping())) {
					friend.setTarget(le);
				}
			}
		}
		return hurt;
	}

	@Override
	public void onDeath(DamageSource source) {
		boolean alreadyDead = this.dead;
		super.onDeath(source);
		if (alreadyDead || !(this.getWorld() instanceof ServerWorld sw)) return;
		FactionManager fm = FactionManager.get(sw.getServer());
		Entity killer = source.getAttacker();
		if (killer instanceof ColonistEntity c && !c.factionId.equals(this.factionId)) {
			fm.changeRelation(sw.getServer(), this.factionId, c.factionId, -15);
		} else if (killer instanceof ServerPlayerEntity p) {
			fm.changeRep(sw.getServer(), p, this.factionId, -35);
			for (Faction other : fm.all()) {
				if (fm.relation(this.factionId, other.id) <= FactionManager.WAR) fm.changeRep(sw.getServer(), p, other.id, 8);
			}
		}
		if (!this.diedOfOldAge) {
			Text deathMessage = source.getDeathMessage(this);
			for (ServerPlayerEntity p : sw.getPlayers(p -> p.squaredDistanceTo(this) < 64 * 64)) {
				p.sendMessage(Text.literal("☠ ").formatted(Formatting.DARK_GRAY).append(deathMessage), false);
			}
		}
		Faction mine = fm.get(this.factionId);
		if (mine != null) mine.recordDeath(today(sw), this.diedOfOldAge ? "old age" : deathCause(source));
		if (mine != null && (killer instanceof ColonistEntity || killer instanceof PlayerEntity)) {
			mine.warWeariness += 3;
			fm.markDirty();
		}
		for (int i = 0; i < this.inventory.size(); i++) {
			ItemStack stack = this.inventory.removeStack(i);
			if (!stack.isEmpty()) ItemScatterer.spawn(sw, this.getX(), this.getY(), this.getZ(), stack);
		}
		BlockPos house = this.home;
		fm.removeMember(sw.getServer(), this.factionId, this.getUuid());
		// Inheritance: a spouse or child without a house of their own moves in.
		if (house != null) {
			ColonistEntity heir = sw.getEntitiesByClass(ColonistEntity.class, this.getBoundingBox().expand(96),
							o -> o != this && o.home == null && o.getFactionId().equals(this.factionId)
									&& (this.getUuid().equals(o.partner) || this.isFamily(o)))
					.stream().min(Comparator.comparingInt(o -> o.isBaby() ? 1 : 0)).orElse(null);
			if (heir != null) {
				heir.home = house;
				heir.bed = this.bed;
				heir.chest = this.chest;
				heir.buildSite = null;
				fm.claimHome(this.factionId, house, heir.getUuid());
				heir.say("I'll look after the house now.");
			}
		}
	}

	private static String deathCause(DamageSource source) {
		Entity attacker = source.getAttacker();
		if (attacker instanceof ColonistEntity) return "war";
		if (attacker instanceof PlayerEntity) return "players";
		if (attacker != null) return attacker.getType().getName().getString().toLowerCase();
		String id = source.getName();
		return switch (id) {
			case "starve" -> "starvation";
			case "fall" -> "falls";
			case "drown" -> "drowning";
			case "lava", "inFire", "onFire", "hotFloor" -> "fire and lava";
			case "inWall" -> "suffocation";
			default -> id;
		};
	}

	// ---------------------------------------------------------------- save & load

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		nbt.put("ColonistInventory", this.inventory.toNbtList(this.getRegistryManager()));
		nbt.putString("Faction", this.factionId);
		nbt.putString("FirstName", this.firstName);
		nbt.putInt("Skin", this.getSkin());
		nbt.putInt("Hunger", this.hunger);
		nbt.putInt("Fuel", this.fuel);
		nbt.putLong("LastSocial", this.lastSocialTime);
		putPos(nbt, "Home", this.home);
		putPos(nbt, "Bed", this.bed);
		putPos(nbt, "Chest", this.chest);
		putPos(nbt, "BuildSite", this.buildSite);
		putPos(nbt, "ParentHome", this.parentHome);
		nbt.putString("Surname", this.surname);
		nbt.putInt("Ambition", this.ambition);
		nbt.putInt("Aggression", this.aggression);
		nbt.putInt("Kindness", this.kindness);
		nbt.putString("Job", this.job.name());
		nbt.putBoolean("Child", this.isBaby());
		nbt.putInt("GrowUp", this.growUpTicks);
		nbt.putLong("LastBirth", this.lastBirthTime);
		if (this.birthDay != Long.MIN_VALUE) nbt.putLong("BirthDay", this.birthDay);
		nbt.putInt("Lifespan", this.lifespanDays);
		if (this.partner != null) nbt.putUuid("Partner", this.partner);
		nbt.putString("PartnerName", this.partnerName);
		if (this.parentA != null) nbt.putUuid("ParentA", this.parentA);
		if (this.parentB != null) nbt.putUuid("ParentB", this.parentB);
		NbtCompound aff = new NbtCompound();
		this.affinity.forEach((k, v) -> aff.putInt(k.toString(), v));
		nbt.put("Affinity", aff);
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		this.inventory.readNbtList(nbt.getList("ColonistInventory", NbtElement.COMPOUND_TYPE), this.getRegistryManager());
		this.factionId = nbt.getString("Faction");
		this.firstName = nbt.getString("FirstName");
		this.dataTracker.set(SKIN, nbt.getInt("Skin"));
		if (nbt.contains("Hunger")) this.hunger = nbt.getInt("Hunger");
		this.fuel = nbt.getInt("Fuel");
		this.lastSocialTime = nbt.getLong("LastSocial");
		this.home = readPos(nbt, "Home");
		this.bed = readPos(nbt, "Bed");
		this.chest = readPos(nbt, "Chest");
		this.buildSite = readPos(nbt, "BuildSite");
		this.parentHome = readPos(nbt, "ParentHome");
		this.surname = nbt.getString("Surname");
		this.ambition = nbt.getInt("Ambition");
		this.aggression = nbt.getInt("Aggression");
		this.kindness = nbt.getInt("Kindness");
		try {
			this.job = Job.valueOf(nbt.getString("Job"));
		} catch (IllegalArgumentException e) {
			this.job = Job.NONE;
		}
		this.dataTracker.set(CHILD, nbt.getBoolean("Child"));
		this.growUpTicks = nbt.getInt("GrowUp");
		this.lastBirthTime = nbt.getLong("LastBirth");
		this.birthDay = nbt.contains("BirthDay") ? nbt.getLong("BirthDay") : Long.MIN_VALUE;
		this.lifespanDays = nbt.getInt("Lifespan");
		this.partner = nbt.containsUuid("Partner") ? nbt.getUuid("Partner") : null;
		this.partnerName = nbt.getString("PartnerName");
		this.parentA = nbt.containsUuid("ParentA") ? nbt.getUuid("ParentA") : null;
		this.parentB = nbt.containsUuid("ParentB") ? nbt.getUuid("ParentB") : null;
		NbtCompound aff = nbt.getCompound("Affinity");
		for (String k : aff.getKeys()) {
			try {
				this.affinity.put(UUID.fromString(k), aff.getInt(k));
			} catch (IllegalArgumentException ignored) {
			}
		}
	}

	private static void putPos(NbtCompound nbt, String key, @Nullable BlockPos pos) {
		if (pos != null) nbt.putLong(key, pos.asLong());
	}

	@Nullable
	private static BlockPos readPos(NbtCompound nbt, String key) {
		return nbt.contains(key) ? BlockPos.fromLong(nbt.getLong(key)) : null;
	}
}
