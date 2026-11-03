package com.malaron.loadsoflods.client.mixin;

import com.malaron.loadsoflods.client.AtlasPreviewState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.SpriteLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
public abstract class TextureAtlasUploadMixin {
	@Inject(method = "upload", at = @At("HEAD"))
	private void loadsOfLods$disablePreviewBeforeReload(
		SpriteLoader.Preparations preparations,
		CallbackInfo callback
	) {
		AtlasPreviewState.beforeAtlasReload((TextureAtlas) (Object) this);
	}

	@Inject(method = "upload", at = @At("TAIL"))
	private void loadsOfLods$invalidatePreviewOnReload(
		SpriteLoader.Preparations preparations,
		CallbackInfo callback
	) {
		AtlasPreviewState.invalidateForAtlasReload((TextureAtlas) (Object) this);
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void loadsOfLods$releasePreviewOnClose(CallbackInfo callback) {
		AtlasPreviewState.invalidateForAtlasClose((TextureAtlas) (Object) this);
	}

	@Inject(method = "cycleAnimationFrames", at = @At("TAIL"))
	private void loadsOfLods$updateLowAtlasAnimationFrames(CallbackInfo callback) {
		AtlasPreviewState.updateAnimatedFrames((TextureAtlas) (Object) this);
	}

	@Inject(method = "uploadAnimationFrames", at = @At("HEAD"), cancellable = true)
	private void loadsOfLods$skipFullAtlasAnimationUpload(CallbackInfo callback) {
		if (AtlasPreviewState.shouldSkipVanillaAnimationUpload((TextureAtlas) (Object) this)) {
			callback.cancel();
		}
	}
}
