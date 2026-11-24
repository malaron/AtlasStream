package com.malaron.atlasstream.client.mixin;

import com.malaron.atlasstream.client.atlas.AtlasRuntimeState;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractTexture.class)
public abstract class AbstractTexturePreviewMixin {
	@Inject(method = "getTexture", at = @At("HEAD"), cancellable = true)
	private void atlasStream$useLowResolutionAtlas(CallbackInfoReturnable<GpuTexture> callback) {
		GpuTexture near = AtlasRuntimeState.nearAtlasTextureForSlot(this);
		if (near != null) {
			callback.setReturnValue(near);
			return;
		}
		if ((Object) this instanceof TextureAtlas atlas
				&& AtlasRuntimeState.usesLowResolutionAtlas(atlas)) {
			callback.setReturnValue(AtlasRuntimeState.lowResolutionTexture(atlas));
		} else if ((Object) this instanceof TextureAtlas atlas) {
			GpuTexture fallback = AtlasRuntimeState.reloadFallbackTexture(atlas);
			if (fallback != null) {
				callback.setReturnValue(fallback);
			}
		}
	}

	@Inject(method = "getTextureView", at = @At("HEAD"), cancellable = true)
	private void atlasStream$useLowResolutionAtlasView(CallbackInfoReturnable<GpuTextureView> callback) {
		GpuTextureView near = AtlasRuntimeState.nearAtlasViewForSlot(this);
		if (near != null) {
			callback.setReturnValue(near);
			return;
		}
		if ((Object) this instanceof TextureAtlas atlas
				&& AtlasRuntimeState.usesLowResolutionAtlas(atlas)) {
			callback.setReturnValue(AtlasRuntimeState.lowResolutionTextureView(atlas));
		} else if ((Object) this instanceof TextureAtlas atlas) {
			GpuTextureView fallback = AtlasRuntimeState.reloadFallbackView(atlas);
			if (fallback != null) {
				callback.setReturnValue(fallback);
			}
		}
	}
}
