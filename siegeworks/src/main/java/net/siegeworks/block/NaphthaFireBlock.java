package net.siegeworks.block;

import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidFillable;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;
import net.siegeworks.Siegeworks;
import org.jetbrains.annotations.Nullable;

/**
 * Naphtha fire. It burns on the surface of water, water cannot put it out (water will not even flow into it), and it
 * sets everything flammable around it alight. Smother it with sand, red sand or dirt, or let it burn itself out.
 */
public class NaphthaFireBlock extends Block implements FluidFillable {
	public static final IntProperty AGE = Properties.AGE_7;
	private static final VoxelShape SHAPE = Block.createCuboidShape(0, 0, 0, 16, 1, 16);

	public NaphthaFireBlock(Settings settings) {
		super(settings);
		setDefaultState(getStateManager().getDefaultState().with(AGE, 0));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(AGE);
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return SHAPE;
	}

	@Override
	protected boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
		return canBurnAt(world, pos);
	}

	public static boolean canBurnAt(WorldView world, BlockPos pos) {
		BlockState below = world.getBlockState(pos.down());
		if (world.getFluidState(pos.down()).isIn(FluidTags.WATER)) return true;
		return below.isSideSolidFullSquare(world, pos.down(), Direction.UP) || below.isBurnable() || !below.isAir() && below.getFluidState().isEmpty();
	}

	@Override
	protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState, WorldAccess world, BlockPos pos, BlockPos neighborPos) {
		return canPlaceAt(state, world, pos) ? state : net.minecraft.block.Blocks.AIR.getDefaultState();
	}

	@Override
	protected void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
		world.scheduleBlockTick(pos, this, 30 + world.random.nextInt(20));
	}

	@Override
	protected void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
		if (!canPlaceAt(state, world, pos)) {
			world.removeBlock(pos, false);
			return;
		}
		int age = state.get(AGE);
		// Set what can burn around it alight.
		for (int i = 0; i < 3; i++) {
			BlockPos p = pos.add(random.nextInt(3) - 1, random.nextInt(3) - 1, random.nextInt(3) - 1);
			if (world.getBlockState(p).isBurnable()) {
				for (Direction d : Direction.values()) {
					BlockPos a = p.offset(d);
					if (world.getBlockState(a).isAir()) {
						world.setBlockState(a, AbstractFireBlock.getState(world, a));
						break;
					}
				}
			}
		}
		// It creeps across water.
		if (age < 4 && random.nextInt(3) == 0) {
			BlockPos n = pos.offset(Direction.Type.HORIZONTAL.random(random));
			if (world.getBlockState(n).isAir() && world.getFluidState(n.down()).isIn(FluidTags.WATER)) world.setBlockState(n, state.with(AGE, Math.min(7, age + 2)));
		}
		if (random.nextInt(2) == 0) age++;
		if (age > 7) {
			world.removeBlock(pos, false);
			world.playSound(null, pos, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.4f, 1.4f);
			return;
		}
		world.setBlockState(pos, state.with(AGE, age), Block.NOTIFY_LISTENERS);
		world.scheduleBlockTick(pos, this, 30 + random.nextInt(20));
	}

	@Override
	protected void onEntityCollision(BlockState state, World world, BlockPos pos, Entity entity) {
		if (world.isClient) return;
		entity.setOnFireFor(10f);
		entity.damage(world.getDamageSources().inFire(), 2.5f);
	}

	@Override
	protected ItemActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
		if (!(stack.isOf(Items.SAND) || stack.isOf(Items.RED_SAND) || stack.isOf(Items.DIRT) || stack.isOf(Items.COARSE_DIRT))) return ItemActionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (!world.isClient) {
			for (BlockPos p : BlockPos.iterate(pos.add(-1, -1, -1), pos.add(1, 1, 1))) if (world.getBlockState(p).isOf(this)) world.removeBlock(p, false);
			world.playSound(null, pos, SoundEvents.BLOCK_SAND_PLACE, SoundCategory.BLOCKS, 1f, 0.8f);
			world.playSound(null, pos, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.7f, 1f);
			if (!player.getAbilities().creativeMode) stack.decrement(1);
		}
		return ItemActionResult.success(world.isClient);
	}

	@Override
	public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
		if (random.nextInt(16) == 0) world.playSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.BLOCK_FIRE_AMBIENT, SoundCategory.BLOCKS, 1f, 0.6f, false);
		for (int i = 0; i < 2; i++) {
			world.addParticle(ParticleTypes.FLAME, pos.getX() + random.nextDouble(), pos.getY() + 0.2 + random.nextDouble() * 0.5, pos.getZ() + random.nextDouble(), 0, 0.03, 0);
			world.addParticle(ParticleTypes.LARGE_SMOKE, pos.getX() + random.nextDouble(), pos.getY() + 0.8, pos.getZ() + random.nextDouble(), 0, 0.05, 0);
		}
	}

	// Water cannot flow into Naphtha fire, nor be poured onto it.
	@Override
	public boolean canFillWithFluid(@Nullable PlayerEntity player, BlockView world, BlockPos pos, BlockState state, Fluid fluid) {
		return false;
	}

	@Override
	public boolean tryFillWithFluid(WorldAccess world, BlockPos pos, BlockState state, FluidState fluidState) {
		return false;
	}

	/** Splash Naphtha over everything within a radius: on the ground, on roofs, on the water. */
	public static int spread(ServerWorld world, Vec3d at, double radius) {
		int placed = 0;
		BlockPos c = BlockPos.ofFloored(at);
		int r = (int) Math.ceil(radius);
		BlockState fire = Siegeworks.NAPHTHA_FIRE.getDefaultState();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (dx * dx + dz * dz > radius * radius || world.random.nextInt(5) == 0) continue;
				for (int dy = 2; dy >= -3; dy--) {
					BlockPos p = c.add(dx, dy, dz);
					BlockState s = world.getBlockState(p);
					if ((s.isAir() || s.isReplaceable() && s.getFluidState().isEmpty()) && canBurnAt(world, p)) {
						world.setBlockState(p, fire.with(AGE, world.random.nextInt(2)));
						placed++;
						break;
					}
				}
			}
		}
		for (Entity e : world.getOtherEntities(null, new net.minecraft.util.math.Box(at, at).expand(radius))) {
			e.setOnFireFor(12f);
			e.damage(world.getDamageSources().onFire(), 3f);
		}
		world.spawnParticles(ParticleTypes.FLAME, at.x, at.y + 0.5, at.z, 60, radius / 2, 0.5, radius / 2, 0.05);
		return placed;
	}
}
