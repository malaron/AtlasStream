package com.malaron.loadsoflods.client.mixin;

import com.malaron.loadsoflods.client.DownscalableSprite;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SpriteContents.class)
public abstract class SpriteContentsDownscaleMixin implements DownscalableSprite {
	@Unique private static final int MIN_DOWNSCALED_SIZE = 16;

	@Shadow @Final @Mutable private int width;
	@Shadow @Final @Mutable private int height;
	@Shadow @Final @Mutable private NativeImage originalImage;
	@Shadow private NativeImage[] byMipLevel;

	@Override
	public int loadsOfLods$effectiveDivisor(int requested) {
		int divisor = requested;
		while (divisor > 1 && !canDivide(divisor)) {
			divisor >>= 1;
		}
		return divisor;
	}

	@Unique private boolean canDivide(int divisor) {
		return Math.min(width, height) / divisor >= MIN_DOWNSCALED_SIZE
			&& width % divisor == 0
			&& height % divisor == 0
			&& originalImage.getWidth() % divisor == 0
			&& originalImage.getHeight() % divisor == 0;
	}

	@Override
	public void loadsOfLods$downscale(int divisor) {
		if (divisor <= 1 || byMipLevel.length != 1 || byMipLevel[0] != originalImage || !canDivide(divisor)) {
			return;
		}
		NativeImage source = originalImage;
		NativeImage scaled = new NativeImage(source.getWidth() / divisor, source.getHeight() / divisor, true);
		int samples = divisor * divisor;
		for (int y = 0; y < scaled.getHeight(); y++) {
			for (int x = 0; x < scaled.getWidth(); x++) {
				long alpha = 0;
				long red = 0;
				long green = 0;
				long blue = 0;
				for (int sy = 0; sy < divisor; sy++) {
					for (int sx = 0; sx < divisor; sx++) {
						int pixel = source.getPixel(x * divisor + sx, y * divisor + sy);
						int a = pixel >>> 24;
						alpha += a;
						red += (long) (pixel & 0xFF) * a;
						green += (long) ((pixel >> 8) & 0xFF) * a;
						blue += (long) ((pixel >> 16) & 0xFF) * a;
					}
				}
				int outAlpha = (int) ((alpha + samples / 2) / samples);
				int outRed = 0;
				int outGreen = 0;
				int outBlue = 0;
				if (alpha > 0) {
					outRed = (int) ((red + alpha / 2) / alpha);
					outGreen = (int) ((green + alpha / 2) / alpha);
					outBlue = (int) ((blue + alpha / 2) / alpha);
				}
				scaled.setPixel(x, y, (outAlpha << 24) | (outBlue << 16) | (outGreen << 8) | outRed);
			}
		}
		width /= divisor;
		height /= divisor;
		originalImage = scaled;
		byMipLevel = new NativeImage[]{scaled};
		source.close();
	}
}
