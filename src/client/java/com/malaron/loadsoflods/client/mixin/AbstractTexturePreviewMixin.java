package com.malaron.loadsoflods.client.mixin;

import com.malaron.loadsoflods.client.AtlasPreviewState;
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
	private void loadsOfLods$useLowResolutionAtlas(CallbackInfoReturnable<GpuTexture> callback) {
		GpuTexture near = AtlasPreviewState.nearAtlasTextureForSlot(this);
		if (near != null) {
			callback.setReturnValue(near);
			return;
		}
		if ((Object) this instanceof TextureAtlas atlas
				&& AtlasPreviewState.usesLowResolutionAtlas(atlas)) {
			callback.setReturnValue(AtlasPreviewState.lowResolutionTexture(atlas));
		}
	}

	@Inject(method = "getTextureView", at = @At("HEAD"), cancellable = true)
	private void loadsOfLods$useLowResolutionAtlasView(CallbackInfoReturnable<GpuTextureView> callback) {
		GpuTextureView near = AtlasPreviewState.nearAtlasViewForSlot(this);
		if (near != null) {
			callback.setReturnValue(near);
			return;
		}
		if ((Object) this instanceof TextureAtlas atlas
				&& AtlasPreviewState.usesLowResolutionAtlas(atlas)) {
			callback.setReturnValue(AtlasPreviewState.lowResolutionTextureView(atlas));
		}
	}
}
