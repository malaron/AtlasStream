package com.malaron.atlasstream.client.mixin;

import com.malaron.atlasstream.client.sodium.SodiumTerrainIntegration;

import com.mojang.renderpearl.api.textures.GpuTexture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisPbrAtlasLookupMixin {
	// Ordinal 1 is the id passed to PBRTextureManager.getOrLoadHolder; ordinal 0 is the albedo binding.
	@Redirect(
		method = "onSetAlbedoTex",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/renderpearl/api/textures/GpuTexture;iris$getGlId()I",
			ordinal = 1
		),
		remap = false
	)
	private int atlasStream$lookUpPbrOnOriginalAtlas(GpuTexture texture) {
		return SodiumTerrainIntegration.irisPbrLookupTextureId(texture);
	}
}
