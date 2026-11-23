package com.malaron.atlasstream.client.mixin;

import com.malaron.atlasstream.client.diagnostics.TerrainAlphaDiagnostics;
import com.malaron.atlasstream.client.sodium.SodiumTerrainIntegration;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer", remap = false)
public abstract class SodiumTerrainAlphaDiagnosticMixin {
	@Inject(
		method = "render",
		at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/gpu/device/batch/MultiDrawBatch;draw(Lnet/caffeinemc/mods/sodium/client/gpu/device/context/DrawContext;)V", shift = At.Shift.AFTER),
		remap = false
	)
	private void atlasStream$sampleTerrainAlpha(
		@Coerce Object matrices, @Coerce Object lists, @Coerce Object terrainPass,
		@Coerce Object camera, @Coerce Object fog, boolean translucentSorting,
		@Coerce Object renderPass, @Coerce Object sampler, @Coerce Object globals,
		@Coerce Object sectionTimeInfo, @Coerce Object oitStage, CallbackInfo callback
	) {
		SodiumTerrainIntegration.sampleTerrainAlpha(terrainPass);
	}
}
