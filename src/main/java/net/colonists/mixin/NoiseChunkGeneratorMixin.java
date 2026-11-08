package net.colonists.mixin;

import net.colonists.world.WorldEdge;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ends the world: terrain past the edge is cleared as soon as it is shaped, and the band before it crumbles. */
@Mixin(NoiseChunkGenerator.class)
public abstract class NoiseChunkGeneratorMixin {
	@Shadow
	public abstract RegistryEntry<ChunkGeneratorSettings> getSettings();

	@Inject(method = "populateNoise(Lnet/minecraft/world/gen/chunk/Blender;Lnet/minecraft/world/gen/StructureAccessor;Lnet/minecraft/world/gen/noise/NoiseConfig;Lnet/minecraft/world/chunk/Chunk;II)Lnet/minecraft/world/chunk/Chunk;",
			at = @At("RETURN"))
	private void colonists$worldEdge(Blender blender, StructureAccessor structures, NoiseConfig noise, Chunk chunk, int minY, int cellHeight,
									 CallbackInfoReturnable<Chunk> cir) {
		String id = this.getSettings().getKey().map(k -> k.getValue().toString()).orElse("minecraft:overworld");
		WorldEdge.carve(cir.getReturnValue(), WorldEdge.radiusFor(id));
	}
}
