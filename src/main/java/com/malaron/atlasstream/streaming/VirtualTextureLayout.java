package com.malaron.atlasstream.streaming;

public final class VirtualTextureLayout {
	private final int atlasWidth;
	private final int atlasHeight;
	private final int tileSize;
	private final int gutterSize;

	public VirtualTextureLayout(int atlasWidth, int atlasHeight, int tileSize, int gutterSize) {
		if (atlasWidth <= 0 || atlasHeight <= 0) {
			throw new IllegalArgumentException("Virtual atlas dimensions must be positive");
		}
		if (tileSize <= 0 || Integer.bitCount(tileSize) != 1) {
			throw new IllegalArgumentException("Virtual tile size must be a positive power of two");
		}
		if (gutterSize < 0 || gutterSize * 2 >= tileSize) {
			throw new IllegalArgumentException("Tile gutter must be non-negative and smaller than half the tile size");
		}
		this.atlasWidth = atlasWidth;
		this.atlasHeight = atlasHeight;
		this.tileSize = tileSize;
		this.gutterSize = gutterSize;
	}

	public TileAddress address(long atlasGeneration, int mipLevel, double u, double v) {
		if (!Double.isFinite(u) || !Double.isFinite(v)) {
			throw new IllegalArgumentException("Virtual texture UV coordinates must be finite");
		}
		if (atlasGeneration < 0 || mipLevel < 0) {
			throw new IllegalArgumentException("Virtual atlas generation and mip level must be non-negative");
		}
		validateMipLevel(mipLevel);
		int mipWidth = Math.max(1, atlasWidth >> mipLevel);
		int mipHeight = Math.max(1, atlasHeight >> mipLevel);
		double atlasX = clamp(u, 0.0, 1.0) * mipWidth;
		double atlasY = clamp(v, 0.0, 1.0) * mipHeight;
		int tileX = Math.min((int) (atlasX / tileSize), tileCount(mipWidth) - 1);
		int tileY = Math.min((int) (atlasY / tileSize), tileCount(mipHeight) - 1);
		double tileLocalX = atlasX - (double) tileX * tileSize;
		double tileLocalY = atlasY - (double) tileY * tileSize;
		int storedTileSize = tileSize + gutterSize * 2;
		double paddedU = (gutterSize + tileLocalX) / storedTileSize;
		double paddedV = (gutterSize + tileLocalY) / storedTileSize;

		return new TileAddress(
			new VirtualTextureTileKey(atlasGeneration, mipLevel, tileX, tileY),
			paddedU,
			paddedV,
			mipWidth,
			mipHeight
		);
	}

	public int tileCountX(int mipLevel) {
		validateMipLevel(mipLevel);
		return tileCount(Math.max(1, atlasWidth >> mipLevel));
	}

	public int tileCountY(int mipLevel) {
		validateMipLevel(mipLevel);
		return tileCount(Math.max(1, atlasHeight >> mipLevel));
	}

	public int storedTileSize() {
		return tileSize + gutterSize * 2;
	}

	public int tileSize() {
		return tileSize;
	}

	public int gutterSize() {
		return gutterSize;
	}

	private void validateMipLevel(int mipLevel) {
		if (mipLevel < 0 || mipLevel >= Integer.SIZE - 1) {
			throw new IllegalArgumentException("Mip level is outside the supported integer range");
		}
	}

	private int tileCount(int dimension) {
		return (dimension + tileSize - 1) / tileSize;
	}

	private static double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	public record TileAddress(
		VirtualTextureTileKey key,
		double paddedU,
		double paddedV,
		int mipWidth,
		int mipHeight
	) {
	}
}
