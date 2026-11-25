package com.malaron.atlasstream.client.mixin;

import java.util.List;
import java.util.concurrent.Executor;

import com.malaron.atlasstream.AtlasStream;
import com.malaron.atlasstream.client.atlas.DownscalableSprite;
import com.malaron.atlasstream.client.atlas.SpriteFootprint;
import com.malaron.atlasstream.client.atlas.VirtualAtlasPolicy;

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
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla refuses to stitch an atlas larger than the GPU's texture limit, which stops big packs before this mod
 * can shrink anything. The block atlas is stitched against a larger virtual limit instead (it is never allocated
 * as a GPU texture, see TextureAtlasVirtualMixin). Only when even that is too small, or for other atlases, are
 * the large sprites reduced by the smallest power of two that fits.
 */
@Mixin(SpriteLoader.class)
public abstract class SpriteLoaderOverflowMixin {
	@Unique private static final int MAX_DIVISOR = 32;

	@Shadow @Final private Identifier location;
	@Shadow @Final @Mutable private int maxSupportedTextureSize;

	@Inject(method = "stitch", at = @At("HEAD"))
	private void atlasStream$fitOversizedAtlas(
		List<SpriteContents> sprites,
		int maxMipmapLevels,
		Executor executor,
		CallbackInfoReturnable<SpriteLoader.Preparations> callback
	) {
		// AtlasStream only remaps and preserves coordinates for the block atlas. Silently shrinking chest, shulker,
		// banner, bed, or other standalone entity atlases changes their native entity/model appearance.
		if (!VirtualAtlasPolicy.isBlockAtlas(location)) {
			return;
		}
		if (sprites.isEmpty() || atlasStream$fits(sprites, maxMipmapLevels, 1, maxSupportedTextureSize)) {
			return;
		}
		int limit = VirtualAtlasPolicy.available(location)
			? Math.max(maxSupportedTextureSize, VirtualAtlasPolicy.MAX_VIRTUAL_SIZE)
			: maxSupportedTextureSize;
		int divisor = 1;
		while (divisor < MAX_DIVISOR && !atlasStream$fits(sprites, maxMipmapLevels, divisor, limit)) {
			divisor <<= 1;
		}
		if (limit != maxSupportedTextureSize) {
			AtlasStream.LOGGER.info(
				"Atlas {} does not fit in {}x{}; stitching it as a virtual {}x{} layout",
				location,
				maxSupportedTextureSize,
				maxSupportedTextureSize,
				limit,
				limit
			);
			maxSupportedTextureSize = limit;
		}
		if (divisor == 1) {
			return;
		}
		for (SpriteContents sprite : sprites) {
			DownscalableSprite scalable = (DownscalableSprite) sprite;
			scalable.atlasStream$downscale(scalable.atlasStream$effectiveDivisor(divisor));
		}
		AtlasStream.LOGGER.warn(
			"Atlas {} still does not fit in {}x{}; downscaled its large sprites by {}x so it can be stitched",
			location,
			limit,
			limit,
			divisor
		);
	}

	@Unique
	private static boolean atlasStream$fits(List<SpriteContents> sprites, int maxMipmapLevels, int divisor, int limit) {
		int lowestOneBit = 1 << maxMipmapLevels;
		int minTexel = Integer.MAX_VALUE;
		SpriteFootprint[] footprints = new SpriteFootprint[sprites.size()];
		for (int i = 0; i < footprints.length; i++) {
			SpriteContents sprite = sprites.get(i);
			int d = ((DownscalableSprite) sprite).atlasStream$effectiveDivisor(divisor);
			int width = sprite.width() / d;
			int height = sprite.height() / d;
			footprints[i] = new SpriteFootprint(sprite.name(), width, height);
			minTexel = Math.min(minTexel, Math.min(width, height));
			lowestOneBit = Math.min(lowestOneBit, Math.min(Integer.lowestOneBit(width), Integer.lowestOneBit(height)));
		}
		int mipLevel = Math.min(Mth.log2(Math.min(minTexel, lowestOneBit)), maxMipmapLevels);
		Options options = Minecraft.getInstance().options;
		int anisotropyBit = options.textureFiltering().get() != TextureFilteringMethod.ANISOTROPIC ? 0 : options.maxAnisotropyBit().get();
		Stitcher<SpriteFootprint> stitcher = new Stitcher<>(limit, limit, mipLevel, anisotropyBit);
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
