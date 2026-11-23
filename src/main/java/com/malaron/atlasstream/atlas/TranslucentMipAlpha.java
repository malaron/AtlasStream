package com.malaron.atlasstream.atlas;

public final class TranslucentMipAlpha {
	private byte[] alpha;
	private int width;
	private int height;

	public TranslucentMipAlpha(int[] basePixels, int width, int height) {
		if (width <= 0 || height <= 0 || basePixels.length != Math.multiplyExact(width, height)) {
			throw new IllegalArgumentException("Invalid base mip dimensions");
		}
		this.width = width;
		this.height = height;
		alpha = new byte[basePixels.length];
		for (int i = 0; i < basePixels.length; i++) {
			alpha[i] = (byte) (basePixels[i] >>> 24);
		}
	}

	public void applyNext(int[] pixels, int nextWidth, int nextHeight) {
		if (nextWidth != Math.max(1, width / 2) || nextHeight != Math.max(1, height / 2)
				|| pixels.length != Math.multiplyExact(nextWidth, nextHeight)) {
			throw new IllegalArgumentException("Invalid next mip dimensions");
		}
		byte[] next = new byte[pixels.length];
		for (int y = 0; y < nextHeight; y++) {
			int y0 = y * 2;
			int y1 = Math.min(y0 + 1, height - 1);
			for (int x = 0; x < nextWidth; x++) {
				int x0 = x * 2;
				int x1 = Math.min(x0 + 1, width - 1);
				int index = y * nextWidth + x;
				int mean = ((alpha[y0 * width + x0] & 255) + (alpha[y0 * width + x1] & 255)
					+ (alpha[y1 * width + x0] & 255) + (alpha[y1 * width + x1] & 255)) / 4;
				next[index] = (byte) mean;
				pixels[index] = (pixels[index] & 0x00FFFFFF) | (mean << 24);
			}
		}
		alpha = next;
		width = nextWidth;
		height = nextHeight;
	}
}
