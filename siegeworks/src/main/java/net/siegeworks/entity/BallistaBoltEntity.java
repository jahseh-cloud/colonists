package net.siegeworks.entity;

import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;
import net.siegeworks.Siegeworks;

/** A heavy bolt: it pierces several bodies, and smashes through glass and leaves. */
public class BallistaBoltEntity extends PersistentProjectileEntity {
	private int smashed;

	public BallistaBoltEntity(EntityType<? extends BallistaBoltEntity> type, World world) {
		super(type, world);
	}

	public BallistaBoltEntity(World world, double x, double y, double z) {
		super(Siegeworks.BALLISTA_BOLT, x, y, z, world, new ItemStack(Siegeworks.BOLT), null);
	}

	/** Piercing (vanilla only grants it through enchantments, so it goes in through the saved data). */
	public void pierce(int level) {
		net.minecraft.nbt.NbtCompound n = new net.minecraft.nbt.NbtCompound();
		writeCustomDataToNbt(n);
		n.putByte("PierceLevel", (byte) level);
		readCustomDataFromNbt(n);
	}

	@Override
	protected ItemStack getDefaultItemStack() {
		return new ItemStack(Siegeworks.BOLT);
	}

	@Override
	protected void onBlockHit(BlockHitResult hit) {
		World w = getWorld();
		BlockState state = w.getBlockState(hit.getBlockPos());
		if (!w.isClient && smashed < 3 && SiegeEngineEntity.blockDamage(w) && fragile(state)) {
			w.breakBlock(hit.getBlockPos(), false, getOwner());
			smashed++;
			setVelocity(getVelocity().multiply(0.75));
			return;
		}
		super.onBlockHit(hit);
	}

	static boolean fragile(BlockState s) {
		String id = net.minecraft.registry.Registries.BLOCK.getId(s.getBlock()).getPath();
		return s.isIn(BlockTags.LEAVES) || id.contains("glass") || id.endsWith("_pane") || s.isIn(BlockTags.FLOWER_POTS);
	}
}
