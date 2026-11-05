package com.malaron.loadsoflods.client.mixin;

import com.malaron.loadsoflods.LoadsOfLods;
import com.malaron.loadsoflods.client.DownscalableSprite;
import com.malaron.loadsoflods.client.SpriteFootprint;
import java.util.List;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.client.renderer.texture.StitcherException;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Packs that do not fit in the GPU's maximum texture size crash vanilla stitching before this mod can
 * reduce anything. When that would happen, shrink the oversized sprites by the smallest power of two that fits.
 */
@Mixin(SpriteLoader.class)
public abstract class SpriteLoaderOverflowMixin {
	@Unique
	private static final int MAX_DIVISOR = 32;

	@Shadow @Final private Identifier location;
	@Shadow @Final private int maxSupportedTextureSize;

	@Inject(method = "stitch", at = @At("HEAD"))
	private void loadsOfLods$fitOversizedAtlas(
		List<SpriteContents> sprites,
		int maxMipmapLevels,
		Executor executor,
		CallbackInfoReturnable<SpriteLoader.Preparations> callback
	) {
		if (sprites.isEmpty()) {
			return;
		}
		int divisor = 1;
		while (divisor < MAX_DIVISOR && !fits(sprites, maxMipmapLevels, divisor)) {
			divisor <<= 1;
		}
		if (divisor == 1) {
			return;
		}
		for (SpriteContents sprite : sprites) {
			DownscalableSprite scalable = (DownscalableSprite) sprite;
			scalable.loadsOfLods$downscale(scalable.loadsOfLods$effectiveDivisor(divisor));
		}
		LoadsOfLods.LOGGER.warn(
			"Atlas {} does not fit in {}x{}; downscaled its large sprites by {}x so it can be stitched",
			location,
			maxSupportedTextureSize,
			maxSupportedTextureSize,
			divisor
		);
	}

	@Unique
	private boolean fits(List<SpriteContents> sprites, int maxMipmapLevels, int divisor) {
		int lowestOneBit = 1 << maxMipmapLevels;
		int minTexel = Integer.MAX_VALUE;
		SpriteFootprint[] footprints = new SpriteFootprint[sprites.size()];
		for (int i = 0; i < footprints.length; i++) {
			SpriteContents sprite = sprites.get(i);
			int d = ((DownscalableSprite) sprite).loadsOfLods$effectiveDivisor(divisor);
			int width = sprite.width() / d;
			int height = sprite.height() / d;
			footprints[i] = new SpriteFootprint(sprite.name(), width, height);
			minTexel = Math.min(minTexel, Math.min(width, height));
			lowestOneBit = Math.min(lowestOneBit, Math.min(Integer.lowestOneBit(width), Integer.lowestOneBit(height)));
		}
		int mipLevel = Math.min(Mth.log2(Math.min(minTexel, lowestOneBit)), maxMipmapLevels);
		Options options = Minecraft.getInstance().options;
		int anisotropyBit = options.textureFiltering().get() != TextureFilteringMethod.ANISOTROPIC ? 0 : options.maxAnisotropyBit().get();
		Stitcher<SpriteFootprint> stitcher = new Stitcher<>(maxSupportedTextureSize, maxSupportedTextureSize, mipLevel, anisotropyBit);
		for (SpriteFootprint footprint : footprints) {
			stitcher.registerSprite(footprint);
		}
		try {
			stitcher.stitch();
			return true;
		} catch (StitcherException exception) {
			return false;
		}
	}
}
