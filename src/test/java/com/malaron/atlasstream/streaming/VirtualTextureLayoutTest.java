package com.malaron.atlasstream.streaming;

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

	@Test
	void hugeOriginalLayoutAddressesAndRoundTripsAtTileBoundaries() {
		VirtualTextureLayout layout = new VirtualTextureLayout(65536, 32768, 128, 0);
		double[][] pixels = {
			{0, 0},
			{127, 127},
			{128, 127},
			{65535, 32767}
		};
		int[][] expectedTiles = {
			{0, 0},
			{0, 0},
			{1, 0},
			{511, 255}
		};

		for (int index = 0; index < pixels.length; index++) {
			VirtualTextureLayout.TileAddress address = layout.address(
				17,
				0,
				pixels[index][0] / 65536.0,
				pixels[index][1] / 32768.0
			);
			assertEquals(expectedTiles[index][0], address.key().tileX());
			assertEquals(expectedTiles[index][1], address.key().tileY());
			assertEquals(
				(pixels[index][0] - expectedTiles[index][0] * 128.0) / 128.0,
				address.paddedU(),
				1.0E-12
			);
			assertEquals(
				(pixels[index][1] - expectedTiles[index][1] * 128.0) / 128.0,
				address.paddedV(),
				1.0E-12
			);
		}
	}

	@Test
	void pageTableSlotEncodingMatchesShader24BitDecode() {
		VirtualTexturePageTable pageTable = new VirtualTexturePageTable(
			65536, 32768, 128, 5, 23
		);
		VirtualTextureTileKey key = new VirtualTextureTileKey(23, 0, 511, 255);
		int slot = 0xFEDCBA;
		pageTable.setResident(key, slot);
		int encoded = pageTable.pixelColorAt(pageTable.pixelX(key), pageTable.pixelY(key));
		int decoded = (encoded >>> 16 & 0xFF)
			| (encoded >>> 8 & 0xFF) << 8
			| (encoded & 0xFF) << 16;
		assertEquals(slot + 1, decoded);
	}
}
