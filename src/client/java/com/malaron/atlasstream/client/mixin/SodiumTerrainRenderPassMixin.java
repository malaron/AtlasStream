package com.malaron.atlasstream.client.mixin;

import com.malaron.atlasstream.client.sodium.SodiumTerrainIntegration;

import com.mojang.renderpearl.api.textures.GpuTextureView;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(
	targets = "net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass",
	remap = false
)
public abstract class SodiumTerrainRenderPassMixin {
	@Inject(method = "getAtlas", at = @At("HEAD"), cancellable = true, remap = false)
	private void atlasStream$useLowAtlasAsStreamedFallback(
		CallbackInfoReturnable<GpuTextureView> callback
	) {
		GpuTextureView lowAtlas = SodiumTerrainIntegration.lowAtlasForCurrentPass();
		if (lowAtlas != null) {
			callback.setReturnValue(lowAtlas);
		}
	}
}
