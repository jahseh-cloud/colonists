package net.colonists.atlas;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/** A hand-inked atlas of realms and borders. Right-click to open the political map. */
public class PoliticalAtlasItem extends Item {
	public PoliticalAtlasItem(Settings settings) {
		super(settings);
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		if (user instanceof ServerPlayerEntity sp) ServerPlayNetworking.send(sp, new AtlasPayloads.Open());
		return TypedActionResult.success(user.getStackInHand(hand), world.isClient);
	}
}
