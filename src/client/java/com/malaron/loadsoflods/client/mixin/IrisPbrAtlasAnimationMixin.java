package com.malaron.loadsoflods.client.mixin;

import com.malaron.loadsoflods.client.AtlasPreviewState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pbr.texture.PBRAtlasTexture", remap = false)
public abstract class IrisPbrAtlasAnimationMixin {
	// Iris uploads animation frames at full-size sprite coordinates, which would overrun a reduced atlas.
	@Inject(method = "cycleAnimationFrames", at = @At("HEAD"), cancellable = true, remap = false)
	private void loadsOfLods$skipReducedAtlasAnimation(CallbackInfo callback) {
		if (AtlasPreviewState.isReducedPbrAtlas(this)) {
			callback.cancel();
		}
	}
}
