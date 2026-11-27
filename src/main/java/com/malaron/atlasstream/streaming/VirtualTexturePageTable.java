package com.malaron.atlasstream.streaming;

import java.util.Arrays;

public final class VirtualTexturePageTable {
	private final int tileSize;
	private final int[] mipWidths;
	private final int[] mipHeights;
	private final int[] mipRowOffsets;
	private final int[] layers;
	private final int textureWidth;
	private final int textureHeight;
	private long atlasGeneration;

	public VirtualTexturePageTable(
		int atlasWidth,
		int atlasHeight,
		int tileSize,
		int mipLevelCount,
		long atlasGeneration
	) {
		if (atlasWidth <= 0 || atlasHeight <= 0 || mipLevelCount <= 0) {
			throw new IllegalArgumentException("Atlas dimensions and mip level count must be positive");
		}
		if (Integer.bitCount(tileSize) != 1 || tileSize <= 0) {
			throw new IllegalArgumentException("Virtual tile size must be a positive power of two");
		}
		if (atlasGeneration < 0) {
			throw new IllegalArgumentException("Atlas generation must be non-negative");
		}
		this.tileSize = tileSize;
		this.atlasGeneration = atlasGeneration;
		this.mipWidths = new int[mipLevelCount];
		this.mipHeights = new int[mipLevelCount];
		this.mipRowOffsets = new int[mipLevelCount];
		int maximumMipWidth = 0;
		int totalMipRows = 0;
		for (int mip = 0; mip < mipLevelCount; mip++) {
			int width = tileCount(Math.max(1, atlasWidth >> mip));
			int height = tileCount(Math.max(1, atlasHeight >> mip));
			mipWidths[mip] = width;
			mipHeights[mip] = height;
			mipRowOffsets[mip] = totalMipRows;
			maximumMipWidth = Math.max(maximumMipWidth, width);
			totalMipRows = Math.addExact(totalMipRows, height);
		}
		this.textureWidth = maximumMipWidth;
		this.textureHeight = totalMipRows;
		this.layers = new int[Math.multiplyExact(textureWidth, textureHeight)];
	}

	public synchronized void setResident(VirtualTextureTileKey key, int slotIndex) {
		int index = index(key);
		if (slotIndex < 0 || slotIndex >= 0xFFFFFF) {
			throw new IllegalArgumentException("Tile slot must fit in the 24-bit page-table encoding");
		}
		layers[index] = slotIndex + 1;
	}

	public synchronized boolean clear(VirtualTextureTileKey key) {
		int index = index(key);
		if (layers[index] == 0) {
			return false;
		}
		layers[index] = 0;
		return true;
	}

	public synchronized void resetForGeneration(long newGeneration) {
		if (newGeneration < 0) {
			throw new IllegalArgumentException("Atlas generation must be non-negative");
		}
		Arrays.fill(layers, 0);
		atlasGeneration = newGeneration;
	}

	public synchronized int encodedLayer(VirtualTextureTileKey key) {
		return layers[index(key)];
	}

	public synchronized int pixelX(VirtualTextureTileKey key) {
		return key.tileX();
	}

	public synchronized int pixelY(VirtualTextureTileKey key) {
		return mipRowOffsets[validatedMip(key)] + key.tileY();
	}

	public synchronized int pixelColorAt(int x, int y) {
		if (x < 0 || x >= textureWidth || y < 0 || y >= textureHeight) {
			throw new IndexOutOfBoundsException("Page-table coordinate is outside its texture");
		}
		int encodedLayer = layers[y * textureWidth + x];
		int red = encodedLayer & 255;
		int green = encodedLayer >>> 8 & 255;
		int blue = encodedLayer >>> 16 & 255;
		int alpha = encodedLayer == 0 ? 0 : 255;
		// Byte values uploaded by NativeImage must place red in the integer's high RGB byte.
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}

	public int tileCountX(int mipLevel) {
		validateMipLevel(mipLevel);
		return mipWidths[mipLevel];
	}

	public int tileCountY(int mipLevel) {
		validateMipLevel(mipLevel);
		return mipHeights[mipLevel];
	}

	public int mipRowOffset(int mipLevel) {
		validateMipLevel(mipLevel);
		return mipRowOffsets[mipLevel];
	}

	public int textureWidth() {
		return textureWidth;
	}

	public int textureHeight() {
		return textureHeight;
	}

	public int mipLevelCount() {
		return mipWidths.length;
	}

	public synchronized long atlasGeneration() {
		return atlasGeneration;
	}

	private int index(VirtualTextureTileKey key) {
		int mipLevel = validatedMip(key);
		return (mipRowOffsets[mipLevel] + key.tileY()) * textureWidth + key.tileX();
	}

	private int validatedMip(VirtualTextureTileKey key) {
		if (key.atlasGeneration() != atlasGeneration) {
			throw new IllegalArgumentException("Tile key belongs to a stale atlas generation");
		}
		validateMipLevel(key.mipLevel());
		if (key.tileX() >= mipWidths[key.mipLevel()] || key.tileY() >= mipHeights[key.mipLevel()]) {
			throw new IllegalArgumentException("Tile coordinates are outside the mip-level page table");
		}
		return key.mipLevel();
	}

	private void validateMipLevel(int mipLevel) {
		if (mipLevel < 0 || mipLevel >= mipWidths.length) {
			throw new IllegalArgumentException("Mip level is outside the page table");
		}
	}

	private int tileCount(int dimension) {
		return (dimension + tileSize - 1) / tileSize;
	}
}
