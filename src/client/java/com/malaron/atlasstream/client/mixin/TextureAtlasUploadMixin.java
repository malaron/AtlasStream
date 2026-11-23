package com.malaron.atlasstream.client.mixin;

import com.malaron.atlasstream.client.atlas.AtlasRuntimeState;

import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
public abstract class TextureAtlasUploadMixin {
	@Inject(method = "upload", at = @At("HEAD"))
	private void atlasStream$disablePreviewBeforeReload(
		SpriteLoader.Preparations preparations,
		CallbackInfo callback
	) {
		AtlasRuntimeState.beforeAtlasReload((TextureAtlas) (Object) this);
	}

	@Inject(method = "upload", at = @At("TAIL"))
	private void atlasStream$invalidatePreviewOnReload(
		SpriteLoader.Preparations preparations,
		CallbackInfo callback
	) {
		AtlasRuntimeState.invalidateForAtlasReload((TextureAtlas) (Object) this);
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void atlasStream$releasePreviewOnClose(CallbackInfo callback) {
		AtlasRuntimeState.invalidateForAtlasClose((TextureAtlas) (Object) this);
	}

	@Inject(method = "cycleAnimationFrames", at = @At("TAIL"))
	private void atlasStream$updateLowAtlasAnimationFrames(CallbackInfo callback) {
		AtlasRuntimeState.updateAnimatedFrames((TextureAtlas) (Object) this);
	}

	@Inject(method = "uploadAnimationFrames", at = @At("HEAD"), cancellable = true)
	private void atlasStream$skipFullAtlasAnimationUpload(CallbackInfo callback) {
		if (AtlasRuntimeState.shouldSkipVanillaAnimationUpload((TextureAtlas) (Object) this)) {
			callback.cancel();
		}
	}
}
