package net.colonists.mixin;

import net.minecraft.entity.mob.PatrolEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the clans march their war bands: a patrol only walks to its target while it is "patrolling". */
@Mixin(PatrolEntity.class)
public interface PatrolEntityAccess {
	@Invoker("setPatrolling")
	void colonists$setPatrolling(boolean patrolling);
}
