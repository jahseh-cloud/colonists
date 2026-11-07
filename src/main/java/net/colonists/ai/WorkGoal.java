package net.colonists.ai;

import net.colonists.ColonistsMod;
import net.colonists.ai.task.BuildStructureTask;
import net.colonists.ai.task.MineTask;
import net.colonists.ai.task.QuarryTask;
import net.colonists.ai.task.RanchTask;
import net.colonists.ai.task.WorkshopTask;
import net.colonists.build.Blueprint;
import net.colonists.build.Blueprints;
import net.colonists.build.Materials;
import net.colonists.build.Site;
import net.colonists.ai.task.ChildTask;
import net.colonists.ai.task.FishTask;
import net.colonists.ai.task.ForageTask;
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
				BlockPos center = net.colonists.faction.CityGrowth.bestCenter(world, c.getBlockPos(), c.getRandom());
				if (center != null) {
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
		if (c.hunger <= 14 && food == 0) {
			if (StoreTask.chestHasFood(c)) return new StoreTask(c, true);
			if (city && f.foodInStock > 0 && !c.onSearchCooldown("STOCK_FOOD")) {
				c.searchCooldown("STOCK_FOOD", 600);
				return new StockpileTask(c, StockpileTask.Mode.WITHDRAW, ColonistBrain.FOOD, 8, "Getting food from the city stores");
			}
			if (c.home != null && city && FarmTask.matureCrops(c, f.site(c.home), false) > 0) return new FarmTask(c, f.site(c.home), false);
			if (ForageTask.available(c)) return new ForageTask(c);
			if (FishTask.available(c)) return new FishTask(c);
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

		// 6. A home of one's own, on a lot in the city (the style depends on how big the settlement has grown).
		if (c.home == null && city) {
			if (c.buildSite == null) {
				BlockPos vacant = fm.claimVacantHome(c.getFactionId(), c.getUuid());
				if (vacant != null) {
					c.home = vacant;
					c.bed = null;
					c.say("There's an empty house on my street. I'll take it!");
				}
			}
			if (c.home == null && c.buildSite == null && canBuild && !c.onSearchCooldown("BUILD_SITE")) {
				Blueprint want = Blueprints.homeFor(f.rank, c.isRuler());
				if (ColonistBrain.woodValue(inv) >= Math.min(want.woodCost(), 48) && stone(inv) >= Math.min(want.stoneCost(), 12)) {
					Site site = BuildStructureTask.claimLot(c, f, want);
					if (site == null) {
						c.searchCooldown("BUILD_SITE", 2400);
					} else {
						c.buildSite = site.origin();
						fm.claimHome(f.id, site.origin(), c.getUuid());
						c.say("I've picked out a lot for my new " + want.name.toLowerCase() + ".");
					}
				}
			}
			Blueprint plan = c.buildSite != null ? BuildStructureTask.blueprintAt(f, c.buildSite) : Blueprints.homeFor(f.rank, c.isRuler());
			if (c.home == null && c.buildSite != null && canBuild && c.needs.isEmpty()
					&& ColonistBrain.woodValue(inv) >= 8 && (plan.stoneCost() == 0 || stone(inv) > 0)) {
				return new BuildStructureTask(c, f.site(c.buildSite), plan, true);
			}
			if (c.home == null) {
				Task supply = supply(c, f, canBreak, plan);
				if (supply != null) return supply;
			}
		} else if (c.home != null && canBuild && city && !c.onSearchCooldown("REPAIR")) {
			c.searchCooldown("REPAIR", 400);
			Blueprint bp = BuildStructureTask.blueprintAt(f, c.home);
			if (BuildStructureTask.hasWork(c, f.site(c.home), bp)) return new BuildStructureTask(c, f.site(c.home), bp, true);
		}

		// Better tools and armour from the city stores (made by the blacksmith).
		if (city && !c.onSearchCooldown("STOCK_TOOLS")) {
			c.searchCooldown("STOCK_TOOLS", 3000);
			if (Stockpile.count(Stockpile.chests(world, f), s -> ColonistBrain.isUpgrade(c, s)) > 0) {
				return new StockpileTask(c, StockpileTask.Mode.WITHDRAW, s -> ColonistBrain.isUpgrade(c, s), 2, "Picking up new gear from the stores");
			}
		}

		// 7. The colonist's trade.
		Task work = city ? jobTask(c, f, canBreak, canBuild) : null;
		if (work != null) return work;

		// 8. The garden beside the house.
		if (c.home != null && canBreak && city && BuildStructureTask.blueprintAt(f, c.home).garden
				&& FarmTask.hasWork(c, f.site(c.home), false)) return new FarmTask(c, f.site(c.home), false);

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

	private static int stone(SimpleInventory inv) {
		return ColonistBrain.count(inv, Materials.STONE_ITEMS);
	}

	/** Fetches whatever a building job ran short of: from the city stores if possible, otherwise from the land. */
	@Nullable
	private static Task supply(ColonistEntity c, Faction f, boolean canBreak, @Nullable Blueprint bp) {
		SimpleInventory inv = c.getInventory();
		boolean needWood = "wood".equals(c.needs) || (bp != null && ColonistBrain.woodValue(inv) < Math.min(bp.woodCost(), 48));
		boolean needStone = "stone".equals(c.needs) || (bp != null && bp.stoneCost() > 0 && stone(inv) < Math.min(bp.stoneCost(), 12));
		c.needs = "";
		if (needWood) {
			if (f.cityCenter != null && !c.onSearchCooldown("STOCK_WOOD")) {
				c.searchCooldown("STOCK_WOOD", 1200);
				if (Stockpile.count(Stockpile.chests((net.minecraft.server.world.ServerWorld) c.getWorld(), f),
						s -> ColonistBrain.LOGS.test(s) || ColonistBrain.PLANKS.test(s)) > 0) {
					return new StockpileTask(c, StockpileTask.Mode.WITHDRAW, s -> ColonistBrain.LOGS.test(s) || ColonistBrain.PLANKS.test(s),
							64, "Fetching timber from the city stores");
				}
			}
			if (canBreak && !c.onSearchCooldown(HarvestTask.Kind.WOOD.name())) return new HarvestTask(c, HarvestTask.Kind.WOOD, 8);
		}
		if (needStone) {
			if (f.cityCenter != null && !c.onSearchCooldown("STOCK_STONE")) {
				c.searchCooldown("STOCK_STONE", 1200);
				if (Stockpile.count(Stockpile.chests((net.minecraft.server.world.ServerWorld) c.getWorld(), f), Materials.STONE_ITEMS) > 0) {
					return new StockpileTask(c, StockpileTask.Mode.WITHDRAW, Materials.STONE_ITEMS, 64, "Fetching stone from the city stores");
				}
			}
			if (canBreak && ColonistBrain.tier(inv, ColonistBrain.PICKAXES) >= 0 && !c.onSearchCooldown(HarvestTask.Kind.STONE.name())) {
				return new HarvestTask(c, HarvestTask.Kind.STONE, 16);
			}
		}
		return null;
	}

	/** The city's next public building: finish one under construction, or claim a lot for the next one it needs. */
	@Nullable
	private static Task project(ColonistEntity c, Faction f, boolean canBreak) {
		if (c.onSearchCooldown("PROJECT")) return null;
		net.minecraft.server.world.ServerWorld world = (net.minecraft.server.world.ServerWorld) c.getWorld();
		for (java.util.Map.Entry<BlockPos, String> e : f.buildingTypes.entrySet()) {
			BlockPos o = e.getKey();
			if (f.homes.containsKey(o) || !world.isChunkLoaded(o.getX() >> 4, o.getZ() >> 4)) continue;
			Blueprint bp = Blueprints.get(e.getValue());
			boolean unfinished = !f.built.contains(o);
			if (!unfinished && !BuildStructureTask.hasWork(c, f.site(o), bp)) continue; // built and in good repair
			if (ColonistBrain.woodValue(inv(c)) >= 8 && (bp.stoneCost() == 0 || stone(inv(c)) > 0) && c.needs.isEmpty()) {
				return new BuildStructureTask(c, f.site(o), bp, false);
			}
			Task supply = supply(c, f, canBreak, bp);
			if (supply != null) return supply;
			c.searchCooldown("PROJECT", 600);
			return null;
		}
		int pop = f.members.size();
		for (int r = 0; r <= f.rank.ordinal(); r++) {
			for (String id : Blueprints.PROJECTS[r]) {
				if (!f.buildingsOf(id).isEmpty()) continue;
				if ((id.equals("town_hall") && pop < 2) || (id.equals("lumber_camp") && pop < 3) || (id.equals("barn") && pop < 4)) continue;
				Blueprint bp = Blueprints.get(id);
				Site site = BuildStructureTask.claimLot(c, f, bp);
				if (site == null) {
					c.searchCooldown("PROJECT", 2400);
					return null;
				}
				c.say("The " + f.name + " need a " + bp.name.toLowerCase() + ". I'll start on it.");
				return new BuildStructureTask(c, site, bp, false);
			}
		}
		c.searchCooldown("PROJECT", 1200);
		return null;
	}

	private static SimpleInventory inv(ColonistEntity c) {
		return c.getInventory();
	}

	@Nullable
	private static BlockPos nearestBuilt(ColonistEntity c, Faction f, String type) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos p : f.buildingsOf(type)) {
			if (!f.built.contains(p)) continue;
			double d = c.getBlockPos().getSquaredDistance(p);
			if (d < bestD) {
				bestD = d;
				best = p;
			}
		}
		return best;
	}

	@Nullable
	private static Task jobTask(ColonistEntity c, Faction f, boolean canBreak, boolean canBuild) {
		SimpleInventory inv = c.getInventory();
		boolean haul = canBuild && Stockpile.surplus(inv) >= 32 && !c.onSearchCooldown("DEPOSIT");
		switch (c.job) {
			case FARMER -> {
				if (!canBreak) return null;
				Site field = FarmTask.pickField(c);
				if (field != null && (ColonistBrain.count(inv, ColonistBrain.SEEDS) > 0 || FarmTask.matureCrops(c, field, true) > 0
						|| FarmTask.hasWork(c, field, true))) {
					return new FarmTask(c, field, true);
				}
				if (ColonistBrain.count(inv, ColonistBrain.SEEDS) < 8 && !c.onSearchCooldown(HarvestTask.Kind.SEEDS.name())) {
					return new HarvestTask(c, HarvestTask.Kind.SEEDS, 8);
				}
			}
			case RANCHER -> {
				BlockPos pen = nearestBuilt(c, f, "animal_pen");
				if (pen != null && !c.onSearchCooldown("RANCH")) return new RanchTask(c, f.site(pen));
				if (pen != null && ColonistBrain.count(inv, s -> s.isOf(Items.WHEAT) || s.isOf(Items.CARROT)) < 4 && !c.onSearchCooldown("STOCK_FEED")) {
					c.searchCooldown("STOCK_FEED", 2400);
					return new StockpileTask(c, StockpileTask.Mode.WITHDRAW, s -> s.isOf(Items.WHEAT) || s.isOf(Items.CARROT) || s.isOf(Items.WHEAT_SEEDS),
							16, "Fetching animal feed");
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
				boolean mine = MineTask.hasWork(c, f);
				boolean quarry = (f.quarry == null && !c.onSearchCooldown("QUARRY")) || QuarryTask.hasWork(c, f);
				if (mine && (!quarry || c.getRandom().nextInt(2) == 0)) return new MineTask(c);
				if (quarry) return new QuarryTask(c);
				if (pick >= ColonistBrain.STONE && !c.onSearchCooldown(HarvestTask.Kind.ORE.name())) return new HarvestTask(c, HarvestTask.Kind.ORE, 6);
				if (ColonistBrain.count(inv, ColonistBrain.STONE_MATS) < 64 && !c.onSearchCooldown(HarvestTask.Kind.STONE.name())) {
					return new HarvestTask(c, HarvestTask.Kind.STONE, 12);
				}
			}
			case BLACKSMITH -> {
				BlockPos smithy = nearestBuilt(c, f, "smithy");
				if (smithy != null && WorkshopTask.worthDoing(c, f, WorkshopTask.Trade.SMITH)) {
					return new WorkshopTask(c, WorkshopTask.Trade.SMITH, f.site(smithy), Blueprints.SMITHY);
				}
			}
			case COOK -> {
				BlockPos bakery = nearestBuilt(c, f, "bakery");
				if (bakery != null && WorkshopTask.worthDoing(c, f, WorkshopTask.Trade.COOK)) {
					return new WorkshopTask(c, WorkshopTask.Trade.COOK, f.site(bakery), Blueprints.BAKERY);
				}
			}
			case BUILDER -> {
				if (!canBuild) return null;
				Task build = project(c, f, canBreak);
				if (build != null) return build;
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
		// Small villages without a builder: everyone pitches in on public buildings.
		if (canBuild && c.home != null && f.countJob("BUILDER") == 0) {
			Task build = project(c, f, canBreak);
			if (build != null) return build;
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
