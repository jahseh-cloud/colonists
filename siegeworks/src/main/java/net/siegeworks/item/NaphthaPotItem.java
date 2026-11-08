package net.siegeworks.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import net.siegeworks.entity.NaphthaPotEntity;

/** A sealed clay pot of Naphtha. Throw it, or load it into a catapult. */
public class NaphthaPotItem extends Item {
	public NaphthaPotItem(Settings settings) {
		super(settings);
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		world.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.ENTITY_SPLASH_POTION_THROW, SoundCategory.PLAYERS, 0.6f, 0.5f);
		if (!world.isClient) {
			NaphthaPotEntity pot = new NaphthaPotEntity(world, user);
			pot.setItem(stack.copyWithCount(1));
			pot.setVelocity(user, user.getPitch(), user.getYaw(), -15f, 0.9f, 1f);
			world.spawnEntity(pot);
		}
		user.getItemCooldownManager().set(this, 20);
		if (!user.getAbilities().creativeMode) stack.decrement(1);
		return TypedActionResult.success(stack, world.isClient());
	}
}
