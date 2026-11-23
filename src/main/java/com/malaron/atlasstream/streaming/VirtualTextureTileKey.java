package com.malaron.atlasstream.streaming;

public record VirtualTextureTileKey(long atlasGeneration, int mipLevel, int tileX, int tileY) {
	public VirtualTextureTileKey {
		if (atlasGeneration < 0 || mipLevel < 0 || tileX < 0 || tileY < 0) {
			throw new IllegalArgumentException("Virtual texture tile coordinates and generation must be non-negative");
		}
	}
}
