package net.siegeworks.item;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.siegeworks.Siegeworks;
import net.siegeworks.entity.Spray;

/**
 * A hand siphon: a short bronze tube with a squeeze-bag. Hold use to spray a short jet of burning Naphtha, fed from the
 * Naphtha pots in your inventory (one pot lasts a second and a half). Careless hands get splashed.
 */
public class HandSiphonItem extends Item {
	public static final int TICKS_PER_POT = 30;

	public HandSiphonItem(Settings settings) {
		super(settings);
	}

	@Override
	public UseAction getUseAction(ItemStack stack) {
		return UseAction.BOW;
	}

	@Override
	public int getMaxUseTime(ItemStack stack, LivingEntity user) {
		return 72000;
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (!user.getAbilities().creativeMode && findPot(user) == null) {
			if (!world.isClient) user.sendMessage(Text.literal("The siphon is dry: you need Naphtha pots.").formatted(Formatting.RED), true);
			return TypedActionResult.fail(stack);
		}
		user.setCurrentHand(hand);
		return TypedActionResult.consume(stack);
	}

	@Override
	public void usageTick(World world, LivingEntity user, ItemStack stack, int remainingUseTicks) {
		if (!(world instanceof ServerWorld w)) return;
		int used = getMaxUseTime(stack, user) - remainingUseTicks;
		spray(w, user, stack, used);
	}

	/** One tick of spraying (public for the self-test). Returns false when the user ran dry. */
	public boolean spray(ServerWorld w, LivingEntity user, ItemStack stack, int used) {
		boolean creative = user instanceof PlayerEntity p && p.getAbilities().creativeMode;
		if (used % TICKS_PER_POT == 0 && !creative) {
			ItemStack pot = user instanceof PlayerEntity p ? findPot(p) : null;
			if (pot == null) {
				user.stopUsingItem();
				w.playSound(null, user.getBlockPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.6f, 1.6f);
				return false;
			}
			pot.decrement(1);
		}
		Vec3d dir = user.getRotationVec(1f);
		Vec3d from = user.getEyePos().add(dir.multiply(0.8)).add(0, -0.25, 0);
		Spray.tick(w, user, user, from, dir, 4.5, used, 11);
		if (used % 10 == 0) stack.damage(1, user, LivingEntity.getSlotForHand(user.getActiveHand()));
		// Careless hands get splashed: spraying straight down, or one time in a few hundred.
		if (user.getPitch() > 65 || w.random.nextInt(400) == 0) {
			user.setOnFireFor(4f);
			if (user instanceof PlayerEntity p) p.sendMessage(Text.literal("Naphtha splashes back on you!").formatted(Formatting.RED), true);
		}
		return true;
	}

	@Override
	public void onStoppedUsing(ItemStack stack, World world, LivingEntity user, int remainingUseTicks) {
		if (user instanceof PlayerEntity p) p.getItemCooldownManager().set(this, 30);
	}

	static ItemStack findPot(PlayerEntity p) {
		for (Hand h : Hand.values()) if (p.getStackInHand(h).isOf(Siegeworks.NAPHTHA_POT)) return p.getStackInHand(h);
		for (int i = 0; i < p.getInventory().size(); i++) {
			ItemStack s = p.getInventory().getStack(i);
			if (s.isOf(Siegeworks.NAPHTHA_POT)) return s;
		}
		return null;
	}
}
