package com.malaron.atlasstream.client.mixin;

import java.util.function.Supplier;

import com.malaron.atlasstream.client.sodium.SodiumTerrainIntegration;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisNearRemapSamplerMixin {
	@Inject(method = "addGbufferOrShadowSamplers", at = @At("TAIL"), remap = false, require = 0)
	private void atlasStream$addNearRemapSampler(
		@Coerce Object samplers,
		@Coerce Object images,
		Supplier<?> flipped,
		boolean isShadowPass,
		boolean hasTexture,
		boolean hasLightmap,
		boolean hasOverlay,
		CallbackInfo callback
	) {
		SodiumTerrainIntegration.registerNearRemapSampler(samplers);
	}
}
