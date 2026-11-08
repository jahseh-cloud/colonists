package net.siegeworks.item;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Places a siege engine, facing the way you look. */
public class EngineItem extends Item {
	private final EntityType<?> type;

	public EngineItem(EntityType<?> type, Settings settings) {
		super(settings);
		this.type = type;
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext ctx) {
		World world = ctx.getWorld();
		if (!(world instanceof ServerWorld sw)) return ActionResult.SUCCESS;
		BlockPos at = ctx.getBlockPos().offset(ctx.getSide());
		Entity e = type.create(sw);
		if (e == null) return ActionResult.FAIL;
		float yaw = ctx.getPlayer() != null ? ctx.getPlayer().getYaw() : 0;
		e.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, 0);
		if (!sw.isSpaceEmpty(e, e.getBoundingBox().contract(0.05))) return ActionResult.FAIL;
		sw.spawnEntity(e);
		if (ctx.getPlayer() == null || !ctx.getPlayer().getAbilities().creativeMode) ctx.getStack().decrement(1);
		return ActionResult.CONSUME;
	}
}
