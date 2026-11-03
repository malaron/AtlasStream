package com.malaron.loadsoflods.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VirtualTextureLayoutTest {
	private static final int TILE_SIZE = 128;
	private static final int GUTTER_SIZE = 4;

	@Test
	void mapsNormalizedAtlasUvToTileAndPaddedTileCoordinates() {
		VirtualTextureLayout layout = new VirtualTextureLayout(1024, 512, TILE_SIZE, GUTTER_SIZE);

		VirtualTextureLayout.TileAddress address = layout.address(3, 0, 0.25, 0.5);

		assertEquals(new VirtualTextureTileKey(3, 0, 2, 2), address.key());
		assertEquals((GUTTER_SIZE + 0.0) / layout.storedTileSize(), address.paddedU(), 1.0E-12);
		assertEquals((GUTTER_SIZE + 0.0) / layout.storedTileSize(), address.paddedV(), 1.0E-12);
	}

	@Test
	void resolvesTileEdgesToAdjacentTilesAndClampsAtlasEndpoints() {
		VirtualTextureLayout layout = new VirtualTextureLayout(256, 256, TILE_SIZE, GUTTER_SIZE);

		VirtualTextureLayout.TileAddress leftOfBoundary = layout.address(0, 0, Math.nextDown(0.5), 0.5);
		VirtualTextureLayout.TileAddress rightOfBoundary = layout.address(0, 0, 0.5, 0.5);
		VirtualTextureLayout.TileAddress outsideAtlas = layout.address(0, 0, 4.0, -2.0);

		assertEquals(0, leftOfBoundary.key().tileX());
		assertEquals(1, rightOfBoundary.key().tileX());
		assertEquals(1, outsideAtlas.key().tileX());
		assertEquals(0, outsideAtlas.key().tileY());
		assertEquals((GUTTER_SIZE + TILE_SIZE) / (double) layout.storedTileSize(), outsideAtlas.paddedU(), 1.0E-12);
		assertEquals(GUTTER_SIZE / (double) layout.storedTileSize(), outsideAtlas.paddedV(), 1.0E-12);
	}

	@Test
	void computesNonSquareMipTileGrid() {
		VirtualTextureLayout layout = new VirtualTextureLayout(1024, 512, TILE_SIZE, GUTTER_SIZE);

		assertEquals(8, layout.tileCountX(0));
		assertEquals(4, layout.tileCountY(0));
		assertEquals(4, layout.tileCountX(1));
		assertEquals(2, layout.tileCountY(1));
		assertEquals(1, layout.tileCountX(4));
		assertEquals(1, layout.tileCountY(4));
	}

	@Test
	void rejectsInvalidLayoutAndCoordinates() {
		assertThrows(IllegalArgumentException.class, () -> new VirtualTextureLayout(0, 64, TILE_SIZE, 4));
		assertThrows(IllegalArgumentException.class, () -> new VirtualTextureLayout(64, 64, 96, 4));
		assertThrows(IllegalArgumentException.class, () -> new VirtualTextureLayout(64, 64, TILE_SIZE, TILE_SIZE / 2));
		VirtualTextureLayout layout = new VirtualTextureLayout(256, 256, TILE_SIZE, GUTTER_SIZE);
		assertThrows(IllegalArgumentException.class, () -> layout.address(0, 0, Double.NaN, 0.5));
		assertThrows(IllegalArgumentException.class, () -> layout.address(0, 31, 0.5, 0.5));
	}
}
