package com.malaron.loadsoflods.client.mixin;

import com.malaron.loadsoflods.client.SodiumChunkMeshPriority;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(
	targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask",
	remap = false
)
public abstract class SodiumChunkBuildPriorityMixin {
	private static final String MESH_EXECUTE_DESCRIPTOR =
		"(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;"
			+ "Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)"
			+ "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;";

	@Inject(method = MESH_EXECUTE_DESCRIPTOR, at = @At("HEAD"), remap = false)
	private void loadsOfLods$beginPriorityCapture(CallbackInfoReturnable<?> callback) {
		SodiumChunkMeshPriority.begin(this);
	}

	@Inject(method = MESH_EXECUTE_DESCRIPTOR, at = @At("RETURN"), remap = false)
	private void loadsOfLods$endPriorityCapture(CallbackInfoReturnable<?> callback) {
		SodiumChunkMeshPriority.end();
	}
}
