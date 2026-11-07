package net.colonists.ai.task;

import net.colonists.faction.Settlement;
import net.colonists.build.Blueprint;
import net.colonists.build.Blueprints;
import net.colonists.build.Materials;
import net.colonists.build.Site;
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
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
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

/**
 * Levels a lot and puts up any blueprint on it, one block at a time: clears the site, fills hollows
 * (with a stone footing around the edge where the ground falls away), then builds walls, roof and
 * furnishings bottom-up. Used both for a colonist's own home and for the city's public buildings.
 */
public class BuildStructureTask extends Task {
	enum Step { CLEAR, FILL, ENTRY, BED }

	record Job(BlockPos pos, Step step, @Nullable Blueprint.Entry entry, boolean edge) {
		boolean required() {
			return this.step != Step.BED && (this.entry == null || !this.entry.optional());
		}
	}

	private final Site site;
	private final BlockPos origin;
	private final Blueprint bp;
	private final boolean home;
	private List<Job> plan;
	private final Set<BlockPos> skipped = new HashSet<>();
	private Job current;
	private int cooldown;
	private boolean evacuating;

	public BuildStructureTask(ColonistEntity colonist, Site site, Blueprint bp, boolean home) {
		super(colonist);
		this.site = site;
		this.origin = site.origin();
		this.bp = bp;
		this.home = home;
	}

	@Override
	public String describe() {
		return (this.home && this.colonist.home != null ? "Fixing up the " : "Building the ") + this.bp.name.toLowerCase();
	}

	@Override
	public ColonistEntity.ToolKind tool() {
		return ColonistEntity.ToolKind.BUILD;
	}

	@Override
	public int maxTicks() {
		return 6000;
	}

	/** How much uneven ground a settlement is willing to level for one lot. */
	public static int tolerance(Faction.Rank rank) {
		return switch (rank) {
			case VILLAGE -> 2;
			case TOWN -> 3;
			case CITY -> 4;
			case CITY_STATE -> 5;
		};
	}

	static List<Job> plan(Site o, Blueprint bp, int tolerance) {
		List<Job> jobs = new ArrayList<>();
		Set<BlockPos> structure = new HashSet<>();
		for (Blueprint.Entry e : bp.entries) structure.add(new BlockPos(e.x(), e.y(), e.z()));
		for (BlockPos b : bp.beds) {
			structure.add(b);
			structure.add(b.south());
		}
		// 1. Clear the site, top-down (roof overhang included), leaving spots the structure itself will deal with.
		for (int y = bp.height + 1; y >= 0; y--) {
			for (int x = -1; x <= bp.width; x++) {
				for (int z = -1; z <= bp.depth; z++) {
					if (!structure.contains(new BlockPos(x, y, z))) jobs.add(new Job(o.at(x, y, z), Step.CLEAR, null, false));
				}
			}
		}
		// 2. Fill hollows under the footprint and doorstep, bottom-up.
		for (int y = -tolerance - 1; y <= -1; y++) {
			for (int x = 0; x < bp.width; x++) {
				for (int z = -1; z < bp.depth; z++) {
					if (structure.contains(new BlockPos(x, y, z))) continue;
					boolean edge = x == 0 || x == bp.width - 1 || z == -1 || z == bp.depth - 1;
					jobs.add(new Job(o.at(x, y, z), Step.FILL, null, edge));
				}
			}
		}
		// 3. The building itself, bottom-up, in blueprint order within a layer.
		List<Blueprint.Entry> entries = new ArrayList<>(bp.entries);
		entries.sort((a, b) -> Integer.compare(a.y(), b.y()));
		for (Blueprint.Entry e : entries) jobs.add(new Job(e.at(o), Step.ENTRY, e, false));
		for (BlockPos b : bp.beds) jobs.add(new Job(o.at(b), Step.BED, null, false));
		return jobs;
	}

	private boolean hasGlass() {
		return ColonistBrain.count(this.colonist.getInventory(), Materials.GLASS_ITEMS) > 0;
	}

	private boolean needed(World world, Job j) {
		BlockState s = world.getBlockState(j.pos);
		return switch (j.step) {
			case CLEAR -> !s.isAir() && City.isNatural(s) && !(s.isReplaceable() && j.pos.getY() < this.origin.getY());
			case FILL -> {
				// Only fill directly under the floor or under something we already filled (never mid-air shelves).
				if (s.isSolidBlock(world, j.pos) || City.isPavement(s) || s.isOf(Blocks.FARMLAND)) yield false;
				if (!s.isReplaceable() && s.getFluidState().isEmpty()) yield false;
				// Only fill hollows open to the floor above; never caves under solid ground.
				for (BlockPos p = j.pos.up(); p.getY() < this.origin.getY(); p = p.up()) {
					if (world.getBlockState(p).isSolidBlock(world, p)) yield false;
				}
				yield true;
			}
			case ENTRY -> Materials.needed(j.entry, world, j.pos, this.hasGlass());
			case BED -> !(s.getBlock() instanceof BedBlock) && s.isReplaceable() && world.getBlockState(j.pos.offset(this.site.dir(Direction.SOUTH))).isReplaceable();
		};
	}

	private boolean canPay(Job j) {
		SimpleInventory inv = this.colonist.getInventory();
		return switch (j.step) {
			case CLEAR -> true;
			case FILL -> ColonistBrain.count(inv, Materials.FILL_ITEMS) + ColonistBrain.count(inv, ColonistBrain.PLANKS) > 0;
			case BED -> ColonistBrain.count(inv, s -> s.isIn(ItemTags.BEDS)) > 0;
			case ENTRY -> {
				var cost = Materials.costOf(j.entry);
				yield cost == null || ColonistBrain.count(inv, cost) >= Materials.countOf(j.entry);
			}
		};
	}

	/** True when this building has anything left to do that the colonist could do with what they carry. */
	public static boolean hasWork(ColonistEntity c, Site origin, Blueprint bp) {
		World world = c.getWorld();
		boolean glass = ColonistBrain.count(c.getInventory(), Materials.GLASS_ITEMS) > 0;
		for (Blueprint.Entry e : bp.entries) {
			if (!Materials.needed(e, world, e.at(origin), glass)) continue;
			var cost = Materials.costOf(e);
			if (cost == null || ColonistBrain.count(c.getInventory(), cost) >= Materials.countOf(e)) return true;
		}
		for (BlockPos b : bp.beds) {
			BlockPos p = origin.at(b);
			if (!(world.getBlockState(p).getBlock() instanceof BedBlock) && ColonistBrain.count(c.getInventory(), s -> s.isIn(ItemTags.BEDS)) > 0) return true;
		}
		return false;
	}

	/** True once every required block of the building is in place. */
	public static boolean complete(World world, Site origin, Blueprint bp) {
		for (Blueprint.Entry e : bp.entries) {
			if (e.optional()) continue;
			if (Materials.needed(e, world, e.at(origin), true) && !(e.mat() == Blueprint.Mat.GLASS)) return false;
		}
		return true;
	}

	@Override
	public void start() {
		FactionManager fm = this.colonist.factions();
		Settlement f = this.colonist.settlement();
		this.plan = plan(this.site, this.bp, tolerance(f == null ? Faction.Rank.VILLAGE : f.rank));
	}

	@Override
	public void tick() {
		if (this.done || this.plan == null || this.cooldown-- > 0) return;
		ServerWorld world = (ServerWorld) this.colonist.getWorld();

		if (this.evacuating) {
			if (this.approach(this.bp.doorstep(this.site).offset(this.site.front(), 2), 1.5, 1.0) || this.stuck()) {
				this.evacuating = false;
				this.resetMovement();
			}
			return;
		}
		if (this.current == null) {
			this.current = this.next(world);
			if (this.current == null) {
				this.finish(world);
				return;
			}
			this.resetMovement();
		}
		Job j = this.current;
		if (!this.needed(world, j)) {
			this.current = null;
			return;
		}
		if (!this.canPay(j)) {
			ColonistBrain.craft(this.colonist);
			if (!this.canPay(j)) {
				if (j.required()) {
					this.colonist.needs = j.step == Step.FILL || (j.entry != null && (j.entry.mat() == Blueprint.Mat.STONE
							|| j.entry.mat() == Blueprint.Mat.STONE_STAIRS)) ? "stone" : "wood";
					this.done = true;
				} else {
					this.skipped.add(j.pos);
					this.current = null;
				}
				return;
			}
		}
		if (!this.approach(j.pos, 4.5, 1.0)) {
			if (this.stuck()) {
				this.skipped.add(j.pos);
				this.current = null;
			}
			return;
		}
		if (j.step == Step.ENTRY || j.step == Step.BED) {
			Box space = new Box(j.pos);
			if (j.step == Step.BED) space = space.union(new Box(j.pos.offset(this.site.dir(Direction.SOUTH))));
			List<LivingEntity> inTheWay = world.getEntitiesByClass(LivingEntity.class, space, e -> true);
			boolean solid = j.entry == null || j.entry.fixed() == null || !j.entry.fixed().isAir();
			if (solid && !inTheWay.isEmpty()) {
				if (inTheWay.contains(this.colonist)) this.evacuating = true;
				else this.skipped.add(j.pos);
				this.current = null;
				return;
			}
		}
		this.perform(world, j);
		this.colonist.swingHand(Hand.MAIN_HAND);
		this.current = null;
		this.cooldown = j.step == Step.CLEAR ? 5 : 4;
	}

	@Nullable
	private Job next(World world) {
		for (Job j : this.plan) {
			if (this.skipped.contains(j.pos)) continue;
			if (this.needed(world, j)) return j;
		}
		return null;
	}

	private void breakForBuilding(ServerWorld world, BlockPos pos) {
		BlockState s = world.getBlockState(pos);
		if (s.isAir()) return;
		BlockEntity be = world.getBlockEntity(pos);
		for (ItemStack drop : Block.getDroppedStacks(s, world, pos, be, this.colonist, this.colonist.getMainHandStack())) {
			ColonistBrain.give(this.colonist, drop);
		}
		world.breakBlock(pos, false, this.colonist);
	}

	private void perform(ServerWorld world, Job j) {
		SimpleInventory inv = this.colonist.getInventory();
		BlockState state;
		switch (j.step) {
			case CLEAR -> {
				this.breakForBuilding(world, j.pos);
				return;
			}
			case FILL -> {
				// Edges get a stone footing if there is stone to spare; the rest is earth.
				ItemStack fill = j.edge ? ColonistBrain.takeOne(inv, Materials.STONE_ITEMS) : ItemStack.EMPTY;
				if (fill.isEmpty()) fill = ColonistBrain.takeOne(inv, Materials.FILL_ITEMS);
				if (fill.isEmpty()) fill = ColonistBrain.takeOne(inv, ColonistBrain.PLANKS);
				if (fill.isEmpty()) return;
				state = Materials.blockOf(fill, Blocks.DIRT).getDefaultState();
				if (state.isOf(Blocks.SAND) || state.isOf(Blocks.GRAVEL)) state = Blocks.DIRT.getDefaultState(); // no collapsing fill
			}
			case BED -> {
				ItemStack bedItem = ColonistBrain.takeOne(inv, s -> s.isIn(ItemTags.BEDS));
				Block bedBlock = Materials.blockOf(bedItem, Blocks.WHITE_BED);
				Direction head = this.site.dir(Direction.SOUTH);
				state = bedBlock.getDefaultState().with(BedBlock.FACING, head).with(BedBlock.PART, BedPart.FOOT);
				this.breakForBuilding(world, j.pos);
				this.breakForBuilding(world, j.pos.offset(head));
				world.setBlockState(j.pos, state, Block.NOTIFY_ALL);
				world.setBlockState(j.pos.offset(head), state.with(BedBlock.PART, BedPart.HEAD), Block.NOTIFY_ALL);
				if (this.home && this.colonist.bed == null) this.colonist.bed = j.pos;
				world.playSound(null, j.pos, state.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 1.0f, 0.9f);
				return;
			}
			default -> {
				BlockState paid = Materials.pay(j.entry, inv);
				if (paid == null) return;
				state = this.site.state(paid);
			}
		}
		if (j.step == Step.ENTRY && !world.getBlockState(j.pos).isAir()) this.breakForBuilding(world, j.pos);
		if (state.getBlock() instanceof DoorBlock) {
			BlockState lower = state.with(DoorBlock.FACING, this.site.dir(Direction.SOUTH)).with(DoorBlock.HALF, DoubleBlockHalf.LOWER);
			world.setBlockState(j.pos, lower, Block.NOTIFY_ALL);
			world.setBlockState(j.pos.up(), lower.with(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.NOTIFY_ALL);
		} else if (!state.isAir()) {
			world.setBlockState(j.pos, Materials.settle(state, world, j.pos), Block.NOTIFY_ALL);
		}
		if (!state.isAir()) world.playSound(null, j.pos, state.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 1.0f, 0.9f);
		if (this.home && this.bp.chest != null && j.pos.equals(this.site.at(this.bp.chest)) && state.isOf(Blocks.CHEST)) {
			this.colonist.chest = j.pos;
		}
	}

	private void finish(ServerWorld world) {
		this.done = true;
		if (!complete(world, this.site, this.bp)) return; // something was in the way; try again later
		FactionManager fm = this.colonist.factions();
		Settlement f = this.colonist.settlement();
		if (f == null) return;
		boolean wasBuilt = f.built.contains(this.origin);
		f.built.add(this.origin);
		f.buildingTypes.put(this.origin, this.bp.id);
		fm.markDirty();
		if (this.home) {
			if (this.colonist.home == null || this.origin.equals(this.colonist.buildSite)) {
				BlockPos old = this.colonist.home;
				if (old != null && !old.equals(this.origin)) {
					f.homes.put(old, null); // the old house goes to someone who needs one
					this.colonist.bed = null;
					this.colonist.chest = null;
				}
				this.colonist.home = this.origin;
				this.colonist.buildSite = null;
				fm.claimHome(f, this.origin, this.colonist.getUuid());
				this.colonist.say(f.homes.size() == 1 ? "The first house of " + f.name + " is finished!" : "My new " + this.bp.name.toLowerCase() + " is finished!");
			}
			for (BlockPos b : this.bp.beds) {
				if (this.colonist.bed == null && world.getBlockState(this.site.at(b)).getBlock() instanceof BedBlock) this.colonist.bed = this.site.at(b);
			}
			if (this.bp.chest != null && world.getBlockState(this.site.at(this.bp.chest)).isOf(Blocks.CHEST)) this.colonist.chest = this.site.at(this.bp.chest);
		} else if (!wasBuilt) {
			FactionManager.broadcastNear(world, this.origin, 128, Text.literal("🔨 ").formatted(Formatting.YELLOW).append(f.displayName())
					.append(Text.literal(" has finished building a " + this.bp.name.toLowerCase() + ".").formatted(Formatting.YELLOW)));
		}
	}

	// ---------------------------------------------------------------- lots

	/** Claims the best roadside lot for a blueprint (turned to face its street), or null. */
	@Nullable
	public static Site claimLot(ColonistEntity c, Settlement f, Blueprint bp) {
		FactionManager fm = c.factions();
		if (fm == null || f.cityCenter == null) return null;
		return net.colonists.faction.CityGrowth.claim((ServerWorld) c.getWorld(), fm, f, bp, net.colonists.faction.CityGrowth.Purpose.BUILDING, c.getRandom());
	}

	public static boolean isTaken(Settlement f, BlockPos lotOrigin) {
		for (BlockPos h : f.homes.keySet()) if (City.sameColumn(h, lotOrigin)) return true;
		for (BlockPos h : f.farms) if (City.sameColumn(h, lotOrigin)) return true;
		for (BlockPos h : f.buildingTypes.keySet()) if (City.sameColumn(h, lotOrigin)) return true;
		return false;
	}

	public static Blueprint blueprintFor(ColonistEntity c, BlockPos origin) {
		FactionManager fm = c.factions();
		Settlement f = c.settlement();
		return f == null ? Blueprints.CABIN : blueprintAt(f, origin);
	}

	public static Site siteFor(ColonistEntity c, BlockPos origin) {
		FactionManager fm = c.factions();
		Settlement f = c.settlement();
		return f == null ? Site.of(origin) : f.site(origin);
	}

	public static BlockPos doorstep(ColonistEntity c, BlockPos origin) {
		return blueprintFor(c, origin).doorstep(siteFor(c, origin));
	}

	public static BlockPos inside(ColonistEntity c, BlockPos origin) {
		return blueprintFor(c, origin).inside(siteFor(c, origin));
	}

	/** The blueprint used on a lot (cabins for anything unknown). */
	public static Blueprint blueprintAt(Settlement f, BlockPos origin) {
		String id = f.buildingTypes.get(origin);
		return id == null ? Blueprints.CABIN : Blueprints.get(id);
	}
}
