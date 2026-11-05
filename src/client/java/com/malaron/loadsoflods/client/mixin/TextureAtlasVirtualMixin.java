package com.malaron.loadsoflods.client.mixin;

import com.malaron.loadsoflods.LoadsOfLods;
import com.malaron.loadsoflods.client.AtlasPreviewState;
import com.malaron.loadsoflods.client.VirtualAtlasFlag;
import com.malaron.loadsoflods.client.VirtualAtlasPolicy;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
public abstract class TextureAtlasVirtualMixin implements VirtualAtlasFlag {
	@Shadow @Final private Identifier location;
	@Shadow @Final private int maxSupportedTextureSize;
	@Shadow private int width;
	@Shadow private int height;
	@Shadow private int maxMipLevel;
	@Shadow private int mipLevelCount;
	@Shadow private GpuTextureView[] mipViews;
	@Shadow protected abstract void releaseTextures();

	@Unique private boolean loadsOfLods$virtual;

	@Override
	public boolean loadsOfLods$isVirtual() {
		return loadsOfLods$virtual;
	}

	@Inject(method = "upload", at = @At("HEAD"))
	private void loadsOfLods$chooseVirtual(SpriteLoader.Preparations preparations, CallbackInfo callback) {
		loadsOfLods$virtual = VirtualAtlasPolicy.shouldVirtualize(
			location,
			preparations.width(),
			preparations.height(),
			maxSupportedTextureSize
		);
		if (loadsOfLods$virtual) {
			AtlasPreviewState.armVirtualRebuild();
		}
	}

	// A virtual atlas keeps the stitched size for sprite coordinates but only holds a placeholder texture.
	@Inject(method = "createTexture", at = @At("HEAD"), cancellable = true)
	private void loadsOfLods$createPlaceholder(int newWidth, int newHeight, int newMipLevel, CallbackInfo callback) {
		if (!loadsOfLods$virtual) {
			return;
		}
		LoadsOfLods.LOGGER.info(
			"Created virtual {}x{}x{} {}-atlas; no full-size GPU texture is allocated",
			newWidth,
			newHeight,
			newMipLevel,
			location
		);
		releaseTextures();
		var device = RenderSystem.getDevice();
		var placeholder = device.createTexture(location::toString, 15, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
		var accessor = (AbstractTextureAccessor) (Object) this;
		accessor.loadsOfLods$setTexture(placeholder);
		accessor.loadsOfLods$setTextureView(device.createTextureView(placeholder));
		width = newWidth;
		height = newHeight;
		maxMipLevel = newMipLevel;
		mipLevelCount = newMipLevel + 1;
		mipViews = new GpuTextureView[0];
		callback.cancel();
	}

	@Inject(method = "uploadInitialContents", at = @At("HEAD"), cancellable = true)
	private void loadsOfLods$skipInitialContents(CallbackInfo callback) {
		if (loadsOfLods$virtual) {
			callback.cancel();
		}
	}

	@Inject(method = "uploadAnimationFrames", at = @At("HEAD"), cancellable = true)
	private void loadsOfLods$skipVirtualAnimationUpload(CallbackInfo callback) {
		if (loadsOfLods$virtual) {
			callback.cancel();
		}
	}
}
