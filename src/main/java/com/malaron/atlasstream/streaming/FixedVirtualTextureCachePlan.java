package com.malaron.atlasstream.streaming;

public record FixedVirtualTextureCachePlan(
	int gridWidth,
	int gridHeight,
	int slotCount,
	int textureWidth,
	int textureHeight,
	long capacityBytes
) {
	public static FixedVirtualTextureCachePlan create(
		int tileSize,
		int mipLevels,
		long budgetBytes,
		int maximumTextureSize,
		boolean pbrBundle
	) {
		if (tileSize <= 0 || Integer.bitCount(tileSize) != 1
				|| mipLevels <= 0 || mipLevels > Integer.numberOfTrailingZeros(tileSize) + 1
				|| budgetBytes <= 0 || maximumTextureSize <= 0) {
			throw new IllegalArgumentException("Invalid fixed virtual texture cache plan");
		}
		long bytesPerTile = 0;
		for (int mip = 0; mip < mipLevels; mip++) {
			long size = Math.max(1, tileSize >> mip);
			bytesPerTile += size * size * 4L;
		}
		if (pbrBundle) {
			bytesPerTile *= 3;
		}
		int maximumTilesPerSide = maximumTextureSize / tileSize;
		int tileCount = (int) Math.min(
			Math.multiplyExact(maximumTilesPerSide, maximumTilesPerSide),
			budgetBytes / bytesPerTile
		);
		if (tileCount <= 0) {
			throw new IllegalArgumentException("GPU cache budget cannot hold one virtual texture tile");
		}
		int gridWidth = Math.max(1, (int) Math.sqrt(tileCount));
		int gridHeight = tileCount / gridWidth;
		int slotCount = Math.multiplyExact(gridWidth, gridHeight);
		return new FixedVirtualTextureCachePlan(
			gridWidth,
			gridHeight,
			slotCount,
			Math.multiplyExact(gridWidth, tileSize),
			Math.multiplyExact(gridHeight, tileSize),
			Math.multiplyExact(bytesPerTile, slotCount)
		);
	}
}
