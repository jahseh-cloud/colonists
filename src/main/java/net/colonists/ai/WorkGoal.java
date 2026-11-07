package net.colonists.ai;

import net.colonists.ColonistsMod;
import net.colonists.ai.task.BuildHouseTask;
import net.colonists.ai.task.ChildTask;
import net.colonists.ai.task.MigrateTask;
import net.colonists.ai.task.PatrolTask;
import net.colonists.ai.task.PublicWorksTask;
import net.colonists.ai.task.RaidTask;
import net.colonists.ai.task.StockpileTask;
import net.colonists.ai.task.CollectItemsTask;
import net.colonists.ai.task.FarmTask;
import net.colonists.ai.task.HarvestTask;
import net.colonists.ai.task.ShelterTask;
import net.colonists.ai.task.SleepTask;
import net.colonists.ai.task.SocializeTask;
import net.colonists.ai.task.StoreTask;
import net.colonists.ai.task.Task;
import net.colonists.entity.ColonistBrain;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.City;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Stockpile;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.RabbitEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.Vec3d;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.EnumSet;

/**
 * The colonist's "free will": whenever it is not fighting or fleeing, it asks the planner what matters most
 * right now (sleep, food, shelter, tools, home, farm, friends, stockpiles) and works on that.
 */
public class WorkGoal extends Goal {
	private final ColonistEntity colonist;
	private Task task;
	private int idleCooldown;

	public WorkGoal(ColonistEntity colonist) {
		this.colonist = colonist;
		this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
	}

	@Override
	public boolean canStart() {
		if (this.colonist.getTarget() != null || this.colonist.isSleeping()) return false;
		if (this.idleCooldown > 0) {
			this.idleCooldown--;
			return false;
		}
		this.task = plan(this.colonist);
		if (this.task == null) {
			this.idleCooldown = 30 + this.colonist.getRandom().nextInt(30);
			this.colonist.activity = this.colonist.getTarget() != null ? "Hunting" : "Wandering";
			return false;
		}
		return true;
	}

	@Override
	public boolean shouldContinue() {
		if (this.task == null || this.task.isDone() || this.colonist.getTarget() != null) return false;
		if (this.task.ticks >= this.task.maxTicks()) return false;
		return !(this.task.stopsAtNight() && isNight(this.colonist) && this.colonist.home != null);
	}

	@Override
	public void start() {
		this.colonist.activity = this.task.describe();
		this.colonist.wantedTool = this.task.tool();
		this.colonist.updateHeldItem();
		this.task.start();
	}

	@Override
	public void tick() {
		this.task.ticks++;
		this.task.tick();
		if (this.task.ticks % 20 == 0) this.colonist.activity = this.task.describe();
	}

	@Override
	public void stop() {
		if (this.task != null) this.task.stop();
		this.task = null;
		this.colonist.getNavigation().stop();
		this.colonist.wantedTool = ColonistEntity.ToolKind.WEAPON;
		this.idleCooldown = 5;
	}

	@Override
	public boolean shouldRunEveryTick() {
		return true;
	}

	private static boolean isNight(ColonistEntity c) {
		return c.getWorld().isNight() && c.getWorld().getDimension().bedWorks();
	}

	/** Decides what to do next, in priority order. Returning null means "nothing pressing; wander". */
	@Nullable
	static Task plan(ColonistEntity c) {
		ServerWorld world = (ServerWorld) c.getWorld();
		SimpleInventory inv = c.getInventory();
		FactionManager fm = FactionManager.get(world.getServer());
		Faction f = fm.get(c.getFactionId());
		boolean canBreak = world.getGameRules().getBoolean(ColonistsMod.BREAK_BLOCKS);
		boolean canBuild = world.getGameRules().getBoolean(ColonistsMod.BUILD);
		boolean city = f != null && f.cityCenter != null;

		// 0. A faction without a city founds one: here, or far enough from everyone else's.
		if (f != null && f.cityCenter == null && !c.isBaby() && canBuild) {
			BlockPos other = fm.nearestOtherCity(f, c.getBlockPos());
			if (other == null || other.getSquaredDistance(c.getBlockPos().withY(other.getY())) > 140 * 140) {
				int x = c.getBlockX(), z = c.getBlockZ();
				BlockPos center = new BlockPos(x, City.surfaceY(world, x, z), z);
				if (world.getBlockState(center.down()).getFluidState().isEmpty()) {
					fm.foundCity(world.getServer(), f, center);
					c.say("We'll build our city right here!");
					city = true;
				}
			} else {
				Vec3d away = c.getPos().subtract(Vec3d.ofCenter(other)).multiply(1, 0, 1);
				if (away.lengthSquared() < 1) away = new Vec3d(1, 0, 0);
				Vec3d target = Vec3d.ofCenter(other).add(away.normalize().multiply(160));
				return new MigrateTask(c, BlockPos.ofFloored(target));
			}
		}

		// 1. Night: guards keep watch in shifts; everyone else goes to bed, or at least indoors.
		if (isNight(c)) {
			if (city && c.job == ColonistEntity.Job.GUARD && c.getId() % 2 == 0) return new PatrolTask(c);
			if (c.hasValidBed()) return new SleepTask(c);
			if (c.home != null) return new ShelterTask(c, c.home);
			if (c.parentHome != null) return new ShelterTask(c, c.parentHome);
		}

		// 2. Children play.
		if (c.isBaby()) return new ChildTask(c);

		// 3. Hunger.
		int food = ColonistBrain.count(inv, ColonistBrain.FOOD);
		if (c.hunger <= 12 && food == 0) {
			if (StoreTask.chestHasFood(c)) return new StoreTask(c, true);
			if (city && f.foodInStock > 0 && !c.onSearchCooldown("STOCK_FOOD")) {
				c.searchCooldown("STOCK_FOOD", 600);
				return new StockpileTask(c, StockpileTask.Mode.WITHDRAW, ColonistBrain.FOOD, 8, "Getting food from the city stores");
			}
			if (c.home != null && FarmTask.matureCrops(c, c.home, false) > 0) return new FarmTask(c, c.home, false);
			AnimalEntity prey = findPrey(c);
			if (prey != null) {
				c.setTarget(prey);
				c.activity = "Hunting a " + prey.getName().getString();
				return null;
			}
		}

		// 4. Loose items nearby are free resources.
		if (!c.onSearchCooldown("ITEMS") && !CollectItemsTask.nearby(c).isEmpty()) {
			c.searchCooldown("ITEMS", 100);
			return new CollectItemsTask(c);
		}

		// 5. Full pockets: stash things at home or in the city stores.
		if (ColonistBrain.emptySlots(inv) <= 3 && canBuild) {
			if (city && Stockpile.surplus(inv) > 0) return StockpileTask.deposit(c);
			if (c.home != null) return new StoreTask(c, false);
		}

		// 6. A home of one's own, on a lot in the city.
		if (c.home == null && city) {
			if (c.buildSite == null) {
				BlockPos vacant = fm.claimVacantHome(c.getFactionId(), c.getUuid());
				if (vacant != null) {
					c.home = vacant;
					c.bed = null;
					c.say("There's an empty house on my street. I'll take it!");
				}
			}
			if (c.home == null && canBuild && !c.onSearchCooldown("BUILD_SITE")
					&& ColonistBrain.woodValue(inv) >= (c.buildSite == null ? BuildHouseTask.WOOD_NEEDED : 8)) {
				return new BuildHouseTask(c);
			}
			if (c.home == null && canBreak && ColonistBrain.woodValue(inv) < BuildHouseTask.WOOD_NEEDED
					&& !c.onSearchCooldown(HarvestTask.Kind.WOOD.name())) {
				if (city && f.cityCenter != null && ColonistBrain.woodValue(inv) < 40 && !c.onSearchCooldown("STOCK_WOOD")) {
					c.searchCooldown("STOCK_WOOD", 2400);
					return new StockpileTask(c, StockpileTask.Mode.WITHDRAW, s -> ColonistBrain.LOGS.test(s) || ColonistBrain.PLANKS.test(s),
							64, "Fetching timber from the city stores");
				}
				return new HarvestTask(c, HarvestTask.Kind.WOOD, 8);
			}
		} else if (c.home != null && canBuild && !c.onSearchCooldown("REPAIR")) {
			c.searchCooldown("REPAIR", 400);
			if (BuildHouseTask.hasWork(c)) return new BuildHouseTask(c);
		}

		// 7. The colonist's trade.
		Task work = city ? jobTask(c, f, canBreak, canBuild) : null;
		if (work != null) return work;

		// 8. The garden beside the house.
		if (c.home != null && canBreak && FarmTask.hasWork(c, c.home, false)) return new FarmTask(c, c.home, false);

		// 9. Socialise every now and then (this is how friendships and marriages happen).
		if (world.getTime() - c.lastSocialTime > 2000 && c.getRandom().nextInt(3) == 0) {
			ColonistEntity partner = findFriend(c, fm);
			if (partner != null) return new SocializeTask(c, partner);
			c.lastSocialTime = world.getTime() - 1000;
		}

		// 10. Basic stockpiling and the tool ladder, whatever the job.
		if (canBreak) {
			Task gather = gather(c, inv);
			if (gather != null) return gather;
		}

		// 11. Drop off whatever is left over.
		if (city && canBuild && Stockpile.surplus(inv) >= 24 && !c.onSearchCooldown("DEPOSIT")) {
			c.searchCooldown("DEPOSIT", 1200);
			return StockpileTask.deposit(c);
		}

		// 12. Keep some food in reserve.
		if (food < 4 && c.hunger < 18) {
			AnimalEntity prey = findPrey(c);
			if (prey != null) {
				c.setTarget(prey);
				c.activity = "Hunting a " + prey.getName().getString();
			}
		}
		return null;
	}

	@Nullable
	private static Task jobTask(ColonistEntity c, Faction f, boolean canBreak, boolean canBuild) {
		SimpleInventory inv = c.getInventory();
		boolean haul = canBuild && Stockpile.surplus(inv) >= 32 && !c.onSearchCooldown("DEPOSIT");
		switch (c.job) {
			case FARMER -> {
				if (!canBreak) return null;
				BlockPos field = FarmTask.pickField(c);
				if (field != null && (ColonistBrain.count(inv, ColonistBrain.SEEDS) > 0 || FarmTask.matureCrops(c, field, true) > 0
						|| FarmTask.hasWork(c, field, true))) {
					return new FarmTask(c, field, true);
				}
				if (ColonistBrain.count(inv, ColonistBrain.SEEDS) < 8 && !c.onSearchCooldown(HarvestTask.Kind.SEEDS.name())) {
					return new HarvestTask(c, HarvestTask.Kind.SEEDS, 8);
				}
			}
			case WOODCUTTER -> {
				if (canBreak && ColonistBrain.woodValue(inv) < 96 && !c.onSearchCooldown(HarvestTask.Kind.WOOD.name())) {
					return new HarvestTask(c, HarvestTask.Kind.WOOD, 10);
				}
			}
			case MINER -> {
				if (!canBreak) return null;
				int pick = ColonistBrain.tier(inv, ColonistBrain.PICKAXES);
				if (pick < 0) return null; // the general gathering step makes a pickaxe first
				int stone = ColonistBrain.count(inv, ColonistBrain.STONE_MATS);
				if (pick >= ColonistBrain.STONE && c.getRandom().nextInt(2) == 0 && !c.onSearchCooldown(HarvestTask.Kind.ORE.name())) {
					return new HarvestTask(c, HarvestTask.Kind.ORE, 6);
				}
				if (stone < 64 && !c.onSearchCooldown(HarvestTask.Kind.STONE.name())) {
					return new HarvestTask(c, HarvestTask.Kind.STONE, f.wallPlanned && !f.wallBuilt ? 24 : 12);
				}
			}
			case BUILDER -> {
				if (!canBuild) return null;
				boolean walling = f.wallPlanned && !f.wallBuilt;
				int stone = ColonistBrain.count(inv, ColonistBrain.STONE_MATS);
				if (stone < (walling ? 32 : 16) && !c.onSearchCooldown("STOCK_STONE")) {
					c.searchCooldown("STOCK_STONE", walling ? 600 : 2400);
					return new StockpileTask(c, StockpileTask.Mode.WITHDRAW, ColonistBrain.STONE_MATS, 64, "Fetching stone from the city stores");
				}
				if (walling && stone < 8 && canBreak && ColonistBrain.tier(inv, ColonistBrain.PICKAXES) >= 0
						&& !c.onSearchCooldown(HarvestTask.Kind.STONE.name())) {
					return new HarvestTask(c, HarvestTask.Kind.STONE, 24); // the stores are empty: quarry it ourselves
				}
				if (ColonistBrain.count(inv, Items.TORCH) < 4 && ColonistBrain.count(inv, ColonistBrain.COALS) == 0 && !c.onSearchCooldown("STOCK_COAL")) {
					c.searchCooldown("STOCK_COAL", 2400);
					return new StockpileTask(c, StockpileTask.Mode.WITHDRAW, ColonistBrain.COALS, 8, "Fetching coal for street lamps");
				}
				if (PublicWorksTask.hasWork(c)) return new PublicWorksTask(c);
			}
			case GUARD -> {
				int adults = Math.max(1, f.adults());
				if (f.slavery && f.captives < Math.max(2, adults / 2) && !c.onSearchCooldown("RAID") && c.getRandom().nextInt(3) == 0) {
					c.searchCooldown("RAID", 3600);
					VillagerEntity victim = RaidTask.findTarget(c);
					if (victim != null) return new RaidTask(c, victim);
				}
				if (c.getRandom().nextInt(2) == 0) return new PatrolTask(c);
			}
			case NONE -> {
			}
		}
		if (haul) {
			c.searchCooldown("DEPOSIT", 1200);
			return StockpileTask.deposit(c);
		}
		return null;
	}

	@Nullable
	private static Task gather(ColonistEntity c, SimpleInventory inv) {
		int pick = ColonistBrain.tier(inv, ColonistBrain.PICKAXES);
		int stone = ColonistBrain.count(inv, ColonistBrain.STONE_MATS);
		int iron = ColonistBrain.count(inv, s -> s.isOf(Items.IRON_INGOT) || s.isOf(Items.RAW_IRON));
		if (pick < 0 && ColonistBrain.woodValue(inv) < 16 && !c.onSearchCooldown(HarvestTask.Kind.WOOD.name())) {
			return new HarvestTask(c, HarvestTask.Kind.WOOD, 4);
		}
		if (pick >= ColonistBrain.WOOD && stone < 8 && !c.onSearchCooldown(HarvestTask.Kind.STONE.name())) {
			return new HarvestTask(c, HarvestTask.Kind.STONE, 8);
		}
		if (pick >= ColonistBrain.STONE && iron < 8 && c.getRandom().nextInt(3) == 0 && !c.onSearchCooldown(HarvestTask.Kind.ORE.name())) {
			return new HarvestTask(c, HarvestTask.Kind.ORE, 4);
		}
		if (c.home != null && ColonistBrain.count(inv, ColonistBrain.SEEDS) < 4 && !c.onSearchCooldown(HarvestTask.Kind.SEEDS.name())) {
			return new HarvestTask(c, HarvestTask.Kind.SEEDS, 6);
		}
		if (ColonistBrain.woodValue(inv) < 32 && !c.onSearchCooldown(HarvestTask.Kind.WOOD.name())) {
			return new HarvestTask(c, HarvestTask.Kind.WOOD, 6);
		}
		return null;
	}

	@Nullable
	private static AnimalEntity findPrey(ColonistEntity c) {
		return c.getWorld().getEntitiesByClass(AnimalEntity.class, c.getBoundingBox().expand(20, 6, 20),
						a -> a.isAlive() && !a.isBaby() && !a.hasCustomName() && !a.isLeashed()
								&& (a instanceof CowEntity || a instanceof PigEntity || a instanceof SheepEntity
								|| a instanceof ChickenEntity || a instanceof RabbitEntity))
				.stream().min(Comparator.comparingDouble(c::squaredDistanceTo)).orElse(null);
	}

	@Nullable
	private static ColonistEntity findFriend(ColonistEntity c, FactionManager fm) {
		return c.getWorld().getEntitiesByClass(ColonistEntity.class, c.getBoundingBox().expand(24, 8, 24),
						o -> o != c && o.isAlive() && !o.isSleeping() && o.getTarget() == null
								&& fm.relation(c.getFactionId(), o.getFactionId()) > FactionManager.WAR)
				.stream()
				// Prefer our own people, then whoever is closest.
				.min(Comparator.comparingDouble(o -> c.squaredDistanceTo(o) + (o.getFactionId().equals(c.getFactionId()) ? 0 : 200)))
				.orElse(null);
	}
}
