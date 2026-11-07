package net.colonists.ai.task;

import net.colonists.entity.ColonistBrain;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.City;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.LivingEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Levels a city lot and builds (or repairs) a 5x5 cabin on it: log corner pillars, plank walls and roof,
 * a door facing the street, and a torch and bed inside when the materials exist.
 *
 * Seen from above (origin = north-west corner; the street runs along the north side):
 * <pre>
 *   P W D W P      D = door, P = log pillar, W = plank wall
 *   W . . c W      c = chest spot, t = torch, b = bed (foot, head)
 *   W b . . W
 *   W b . t W
 *   P W W W P
 * </pre>
 */
public class BuildHouseTask extends Task {
	/** Planks + 4×logs a colonist wants before breaking ground. */
	public static final int WOOD_NEEDED = 100;

	enum Part { CLEAR, FILL, PILLAR, WALL, ROOF, DOOR, TORCH, BED }

	record Placement(BlockPos pos, Part part) {
		boolean required() {
			return this.part != Part.TORCH && this.part != Part.BED;
		}
	}

	private static final Predicate<ItemStack> FILLER = s -> s.isOf(Items.DIRT) || s.isOf(Items.COBBLESTONE)
			|| s.isOf(Items.COBBLED_DEEPSLATE) || s.isOf(Items.GRAVEL) || s.isOf(Items.SAND) || s.isOf(Items.GRANITE)
			|| s.isOf(Items.DIORITE) || s.isOf(Items.ANDESITE) || s.isOf(Items.TUFF);

	private List<Placement> plan;
	private final Set<BlockPos> skipped = new HashSet<>();
	private Placement current;
	private int cooldown;
	private boolean evacuating;

	public BuildHouseTask(ColonistEntity colonist) {
		super(colonist);
	}

	@Override
	public String describe() {
		return this.colonist.home == null ? "Building a house" : "Fixing up the house";
	}

	@Override
	public ColonistEntity.ToolKind tool() {
		return ColonistEntity.ToolKind.BUILD;
	}

	@Override
	public int maxTicks() {
		return 6000;
	}

	public static BlockPos chestPos(BlockPos origin) {
		return origin.add(3, 0, 1);
	}

	public static BlockPos bedFoot(BlockPos origin) {
		return origin.add(1, 0, 2);
	}

	public static BlockPos doorstep(BlockPos origin) {
		return origin.add(2, 0, -1);
	}

	public static BlockPos center(BlockPos origin) {
		return origin.add(2, 0, 2);
	}

	static List<Placement> blueprint(BlockPos o) {
		List<Placement> plan = new ArrayList<>();
		// Groundwork: clear the footprint and doorstep, fill holes under it.
		for (int x = 0; x < 5; x++) {
			for (int z = -1; z < 5; z++) {
				boolean pillar = (x == 0 || x == 4) && (z == 0 || z == 4);
				for (int y = 4; y >= 0; y--) {
					// Never "clear" a pillar spot: a log already standing there simply serves as the pillar.
					if (pillar && y <= 2) continue;
					plan.add(new Placement(o.add(x, y, z), Part.CLEAR));
				}
				plan.add(new Placement(o.add(x, -1, z), Part.FILL));
			}
		}
		for (int y = 0; y < 3; y++) {
			Placement door = null;
			for (int x = 0; x < 5; x++) {
				for (int z = 0; z < 5; z++) {
					boolean edgeX = x == 0 || x == 4, edgeZ = z == 0 || z == 4;
					if (!edgeX && !edgeZ) continue;
					BlockPos p = o.add(x, y, z);
					if (x == 2 && z == 0 && y < 2) {
						if (y == 0) door = new Placement(p, Part.DOOR);
						continue;
					}
					plan.add(new Placement(p, edgeX && edgeZ ? Part.PILLAR : Part.WALL));
				}
			}
			// The door goes in right after the first layer so the builder can always get in and out.
			if (door != null) plan.add(door);
		}
		for (int x = 0; x < 5; x++) {
			for (int z = 0; z < 5; z++) plan.add(new Placement(o.add(x, 3, z), Part.ROOF));
		}
		plan.add(new Placement(o.add(3, 0, 3), Part.TORCH));
		plan.add(new Placement(bedFoot(o), Part.BED));
		return plan;
	}

	/** True if the house is damaged, or there is a torch/bed waiting to go in. */
	public static boolean hasWork(ColonistEntity colonist) {
		BlockPos origin = colonist.home;
		if (origin == null) return false;
		World world = colonist.getWorld();
		SimpleInventory inv = colonist.getInventory();
		boolean hasTorch = ColonistBrain.count(inv, Items.TORCH) > 0;
		boolean hasBed = ColonistBrain.count(inv, s -> s.isIn(ItemTags.BEDS)) > 0;
		int woodValue = ColonistBrain.woodValue(inv);
		for (Placement p : blueprint(origin)) {
			if (p.part == Part.CLEAR || p.part == Part.FILL) continue;
			if (!needed(world, p)) continue;
			if (p.required() && woodValue >= 2) return true;
			if (p.part == Part.TORCH && hasTorch) return true;
			if (p.part == Part.BED && hasBed) return true;
		}
		return false;
	}

	static boolean needed(World world, Placement p) {
		BlockState state = world.getBlockState(p.pos);
		return switch (p.part) {
			case CLEAR -> !state.isAir() && City.isNatural(state);
			case FILL -> !state.isSolidBlock(world, p.pos) && !City.isPavement(state) && !state.isOf(Blocks.FARMLAND);
			case PILLAR, WALL, ROOF, DOOR -> state.isReplaceable() && state.getFluidState().isEmpty();
			case TORCH -> state.isAir();
			case BED -> !(state.getBlock() instanceof BedBlock) && state.isReplaceable()
					&& world.getBlockState(p.pos.south()).isReplaceable();
		};
	}

	@Override
	public void start() {
		if (this.colonist.home == null && this.colonist.buildSite == null) {
			BlockPos site = findSite(this.colonist);
			if (site == null) {
				this.colonist.searchCooldown("BUILD_SITE", 2400);
				this.done = true;
				return;
			}
			this.colonist.buildSite = site;
			FactionManager fm = this.colonist.factions();
			if (fm != null) fm.claimHome(this.colonist.getFactionId(), site, this.colonist.getUuid());
			this.colonist.say("I've picked out a lot for my house.");
		}
		this.plan = blueprint(this.origin());
	}

	private BlockPos origin() {
		return this.colonist.home != null ? this.colonist.home : this.colonist.buildSite;
	}

	@Override
	public void tick() {
		if (this.done || this.plan == null || this.cooldown-- > 0) return;
		ServerWorld world = (ServerWorld) this.colonist.getWorld();
		BlockPos origin = this.origin();
		if (origin == null) {
			this.done = true;
			return;
		}

		if (this.evacuating) {
			if (this.approach(doorstep(origin).north(2), 1.5, 1.0) || this.stuck()) {
				this.evacuating = false;
				this.resetMovement();
			}
			return;
		}

		if (this.current == null) {
			this.current = this.next(world);
			if (this.current == null) {
				this.finish(world, origin);
				return;
			}
			this.resetMovement();
		}
		Placement p = this.current;
		if (!needed(world, p)) {
			this.current = null;
			return;
		}
		if (!this.hasMaterial(p.part)) {
			ColonistBrain.craft(this.colonist);
			if (!this.hasMaterial(p.part)) {
				if (p.part == Part.FILL) {
					// No dirt or stone to fill with: use planks rather than leave a hole.
					if (ColonistBrain.count(this.colonist.getInventory(), ColonistBrain.PLANKS) == 0) {
						this.done = true;
						return;
					}
				} else if (p.required()) {
					this.done = true; // out of wood: go gather more, the plan resumes later
					return;
				} else {
					this.skipped.add(p.pos);
					this.current = null;
					return;
				}
			}
		}
		if (!this.approach(p.pos, 4.0, 1.0)) {
			if (this.stuck()) {
				this.skipped.add(p.pos);
				this.current = null;
			}
			return;
		}
		if (p.part != Part.CLEAR && p.part != Part.FILL) {
			List<LivingEntity> inTheWay = world.getEntitiesByClass(LivingEntity.class,
					new Box(p.pos).union(new Box(p.part == Part.BED ? p.pos.south() : p.pos)), e -> true);
			if (!inTheWay.isEmpty()) {
				if (inTheWay.contains(this.colonist)) this.evacuating = true;
				else this.skipped.add(p.pos);
				this.current = null;
				return;
			}
		}
		this.place(world, p);
		this.current = null;
		this.cooldown = p.part == Part.CLEAR ? 6 : 4;
	}

	@Nullable
	private Placement next(World world) {
		for (Placement p : this.plan) {
			if (this.skipped.contains(p.pos)) continue;
			if (needed(world, p)) return p;
		}
		return null;
	}

	private boolean hasMaterial(Part part) {
		SimpleInventory inv = this.colonist.getInventory();
		return switch (part) {
			case CLEAR -> true;
			case FILL -> ColonistBrain.count(inv, FILLER) > 0;
			case PILLAR, WALL, ROOF -> ColonistBrain.count(inv, ColonistBrain.PLANKS) + ColonistBrain.count(inv, ColonistBrain.LOGS) > 0;
			case DOOR -> ColonistBrain.count(inv, ColonistBrain.PLANKS) >= 2;
			case TORCH -> ColonistBrain.count(inv, Items.TORCH) > 0;
			case BED -> ColonistBrain.count(inv, s -> s.isIn(ItemTags.BEDS)) > 0;
		};
	}

	private void place(ServerWorld world, Placement p) {
		SimpleInventory inv = this.colonist.getInventory();
		BlockState state;
		switch (p.part) {
			case CLEAR -> {
				state = world.getBlockState(p.pos);
				BlockEntity be = world.getBlockEntity(p.pos);
				for (ItemStack drop : Block.getDroppedStacks(state, world, p.pos, be, this.colonist, ItemStack.EMPTY)) {
					ColonistBrain.give(this.colonist, drop);
				}
				world.breakBlock(p.pos, false, this.colonist);
				this.colonist.swingHand(Hand.MAIN_HAND);
				return;
			}
			case FILL -> {
				ItemStack fill = ColonistBrain.takeOne(inv, FILLER);
				if (fill.isEmpty()) fill = ColonistBrain.takeOne(inv, ColonistBrain.PLANKS);
				state = blockOf(fill, Blocks.DIRT).getDefaultState();
			}
			case PILLAR -> {
				ItemStack log = ColonistBrain.takeOne(inv, ColonistBrain.LOGS);
				if (log.isEmpty()) log = ColonistBrain.takeOne(inv, ColonistBrain.PLANKS);
				state = blockOf(log, Blocks.OAK_LOG).getDefaultState();
			}
			case WALL, ROOF -> {
				ItemStack planks = ColonistBrain.takeOne(inv, ColonistBrain.PLANKS);
				if (planks.isEmpty()) planks = ColonistBrain.takeOne(inv, ColonistBrain.LOGS);
				state = blockOf(planks, Blocks.OAK_PLANKS).getDefaultState();
			}
			case DOOR -> {
				ColonistBrain.take(inv, ColonistBrain.PLANKS, 2);
				state = Blocks.OAK_DOOR.getDefaultState().with(DoorBlock.FACING, Direction.SOUTH).with(DoorBlock.HALF, DoubleBlockHalf.LOWER);
				world.setBlockState(p.pos, state, Block.NOTIFY_ALL);
				world.setBlockState(p.pos.up(), state.with(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.NOTIFY_ALL);
			}
			case TORCH -> {
				ColonistBrain.take(inv, Items.TORCH, 1);
				state = Blocks.TORCH.getDefaultState();
			}
			case BED -> {
				ItemStack bedItem = ColonistBrain.takeOne(inv, s -> s.isIn(ItemTags.BEDS));
				Block bedBlock = blockOf(bedItem, Blocks.WHITE_BED);
				state = bedBlock.getDefaultState().with(BedBlock.FACING, Direction.SOUTH).with(BedBlock.PART, BedPart.FOOT);
				world.setBlockState(p.pos, state, Block.NOTIFY_ALL);
				world.setBlockState(p.pos.south(), state.with(BedBlock.PART, BedPart.HEAD), Block.NOTIFY_ALL);
				this.colonist.bed = p.pos;
			}
			default -> throw new IllegalStateException();
		}
		if (p.part != Part.DOOR && p.part != Part.BED) world.setBlockState(p.pos, state, Block.NOTIFY_ALL);
		world.playSound(null, p.pos, state.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 1.0f, 0.9f);
		this.colonist.swingHand(Hand.MAIN_HAND);
	}

	static Block blockOf(ItemStack stack, Block fallback) {
		Block b = Block.getBlockFromItem(stack.getItem());
		return b == Blocks.AIR ? fallback : b;
	}

	private void finish(ServerWorld world, BlockPos origin) {
		this.done = true;
		for (Placement p : this.plan) {
			if (p.required() && p.part != Part.CLEAR && p.part != Part.FILL && needed(world, p)) return; // something was in the way
		}
		if (this.colonist.home == null) {
			this.colonist.home = origin;
			this.colonist.buildSite = null;
			FactionManager fm = this.colonist.factions();
			if (fm != null) {
				fm.claimHome(this.colonist.getFactionId(), origin, this.colonist.getUuid());
				Faction f = fm.get(this.colonist.getFactionId());
				if (f != null && f.homes.size() == 1) this.colonist.say("The first house of the " + f.name + " is finished!");
				else this.colonist.say("My house is finished. Come visit!");
			}
		}
		if (this.colonist.bed == null && world.getBlockState(bedFoot(origin)).getBlock() instanceof BedBlock) {
			this.colonist.bed = bedFoot(origin);
		}
	}

	// ---------------------------------------------------------------- choosing a lot

	/** The nearest free, buildable house lot in the colonist's city (with y set to the floor height). */
	@Nullable
	public static BlockPos findSite(ColonistEntity colonist) {
		ServerWorld world = (ServerWorld) colonist.getWorld();
		FactionManager fm = colonist.factions();
		Faction faction = fm == null ? null : fm.get(colonist.getFactionId());
		if (faction == null || faction.cityCenter == null) return null;
		int checked = 0;
		for (City.Lot lot : City.lots(faction.cityCenter)) {
			if (lot.farm() || faction.blockedLots.contains(lot.origin())) continue;
			if (isTaken(faction, lot.origin()) || fm.tooCloseToForeignHome(faction, lot.origin())) continue;
			if (++checked > 8) break; // spread the cost of terrain checks over several attempts
			BlockPos o = lot.origin();
			Integer floor = City.floorFor(world, o.getX(), o.getZ() - 1, o.getX() + 8, o.getZ() + 4, 2);
			if (floor == null) {
				if (world.isChunkLoaded(o.getX() >> 4, o.getZ() >> 4)) {
					faction.blockedLots.add(o);
					fm.markDirty();
				}
				continue;
			}
			return o.withY(floor);
		}
		return null;
	}

	static boolean isTaken(Faction faction, BlockPos lotOrigin) {
		for (BlockPos h : faction.homes.keySet()) if (City.sameColumn(h, lotOrigin)) return true;
		for (BlockPos h : faction.farms) if (City.sameColumn(h, lotOrigin)) return true;
		return false;
	}
}
