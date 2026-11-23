package com.malaron.atlasstream.atlas;

public final class PbrTextureDownsampler {
	private PbrTextureDownsampler() {
	}

	public static int averageLabPbrNormalPixels(int[] pixels, int count) {
		if (pixels == null || count <= 0 || count > pixels.length) {
			throw new IllegalArgumentException("Normal-map sample count must fit the supplied pixel buffer");
		}
		double normalX = 0.0;
		double normalY = 0.0;
		double normalZ = 0.0;
		double occlusionSum = 0.0;
		long alphaSum = 0;
		for (int index = 0; index < count; index++) {
			int pixel = pixels[index];
			double sampleX = decodeNormalComponent(pixel >>> 16 & 255);
			double sampleY = decodeNormalComponent(pixel >>> 8 & 255);
			double sampleLengthSquared = sampleX * sampleX + sampleY * sampleY;
			double sampleZ = Math.sqrt(Math.max(0.0, 1.0 - sampleLengthSquared));
			if (sampleLengthSquared > 1.0) {
				double inverseLength = 1.0 / Math.sqrt(sampleLengthSquared);
				sampleX *= inverseLength;
				sampleY *= inverseLength;
			}
			normalX += sampleX;
			normalY += sampleY;
			normalZ += sampleZ;
			occlusionSum += pixel & 255;
			alphaSum += pixel >>> 24;
		}
		double length = Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
		if (length < 1.0e-8) {
			normalX = 0.0;
			normalY = 0.0;
			normalZ = 1.0;
			length = 1.0;
		}
		int red = encodeNormalComponent(normalX / length);
		int green = encodeNormalComponent(normalY / length);
		int blue = (int) Math.round(occlusionSum / count);
		int alpha = (int) ((alphaSum + count / 2) / count);
		return alpha << 24 | red << 16 | green << 8 | blue;
	}

	public static int averageLabPbrSpecularPixels(int[] pixels, int count) {
		if (pixels == null || count <= 0 || count > pixels.length) {
			throw new IllegalArgumentException("Specular-map sample count must fit the supplied pixel buffer");
		}
		long redSum = 0;
		long greenSum = 0;
		long blueSum = 0;
		long alphaSum = 0;
		for (int index = 0; index < count; index++) {
			int pixel = pixels[index];
			redSum += pixel >>> 16 & 255;
			greenSum += pixel >>> 8 & 255;
			blueSum += pixel & 255;
			alphaSum += pixel >>> 24;
		}
		int red = (int) ((redSum + count / 2) / count);
		int green = (int) ((greenSum + count / 2) / count);
		int blue = (int) ((blueSum + count / 2) / count);
		int alpha = (int) ((alphaSum + count / 2) / count);
		return alpha << 24 | red << 16 | green << 8 | blue;
	}

	private static double decodeNormalComponent(int value) {
		return (value * 2.0 - 254.0) / 255.0;
	}

	private static int encodeNormalComponent(double component) {
		return (int) Math.round(Math.clamp(component * 0.5 + 0.5, 0.0, 1.0) * 255.0);
	}
}
