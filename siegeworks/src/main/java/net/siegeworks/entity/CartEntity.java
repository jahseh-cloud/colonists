package net.siegeworks.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.siegeworks.Siegeworks;
import org.jetbrains.annotations.Nullable;

/** A two-wheeled cart: three rows of cargo and two seats. Pull it by hand, or hitch it to a horse. */
public class CartEntity extends SiegeEngineEntity {
	public final SimpleInventory cargo = new SimpleInventory(27);

	public CartEntity(EntityType<? extends CartEntity> type, World world) {
		super(type, world);
	}

	@Override
	public float maxHealth() {
		return 30;
	}

	@Override
	public int seats() {
		return 2;
	}

	@Override
	public Item item() {
		return Siegeworks.CART_ITEM;
	}

	@Override
	protected Vec3d seat(int index) {
		return index == 0 ? new Vec3d(0, 0.65, 0.35) : new Vec3d(0, 0.65, -0.55);
	}

	@Override
	protected float driveSpeed() {
		return 0.07f;
	}

	@Override
	protected float turnSpeed() {
		return 3f;
	}

	@Override
	protected int weight() {
		return -1;
	}

	@Override
	protected double pushFactor() {
		return 1.0;
	}

	@Override
	protected double hitchLength() {
		return 2.4;
	}

	@Override
	protected void sneakUse(PlayerEntity player, ItemStack held) {
		open(player);
	}

	public void open(PlayerEntity player) {
		player.openHandledScreen(new SimpleNamedScreenHandlerFactory((syncId, inv, p) -> GenericContainerScreenHandler.createGeneric9x3(syncId, inv, cargo),
				Text.translatable("entity.siegeworks.cart")));
		getWorld().playSound(null, getBlockPos(), SoundEvents.BLOCK_BARREL_OPEN, SoundCategory.NEUTRAL, 0.8f, 1f);
	}

	@Override
	public void crankKey(PlayerEntity rider) {
		open(rider);
	}

	@Override
	protected void release(@Nullable LivingEntity by) {}

	@Override
	protected void serverTick() {
		if (age % 20 == 0) setFlag(!cargo.isEmpty());
	}

	@Override
	protected void dropContents() {
		ItemScatterer.spawn(getWorld(), this, cargo);
	}

	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		cargo.readNbtList(nbt.getList("Cargo", NbtElement.COMPOUND_TYPE), getRegistryManager());
	}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		nbt.put("Cargo", cargo.toNbtList(getRegistryManager()));
	}

	@Override
	public String[] keyHints() {
		return new String[]{"open cargo", null};
	}

	@Override
	public Text status() {
		int used = 0;
		for (int i = 0; i < cargo.size(); i++) if (!cargo.getStack(i).isEmpty()) used++;
		return Text.literal("Cart  ").formatted(Formatting.GOLD)
				.append(Text.literal(used + "/27 slots").formatted(Formatting.AQUA))
				.append(Text.literal(hitch != null ? "  Hitched" : "").formatted(Formatting.DARK_GRAY));
	}
}
