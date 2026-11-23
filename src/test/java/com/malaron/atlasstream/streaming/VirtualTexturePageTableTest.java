package com.malaron.atlasstream.streaming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualTexturePageTableTest {
	@Test
	void packsMipTileGridsIntoNonOverlappingRows() {
		VirtualTexturePageTable table = new VirtualTexturePageTable(512, 256, 128, 3, 5);

		assertEquals(4, table.textureWidth());
		assertEquals(0, table.mipRowOffset(0));
		assertEquals(2, table.mipRowOffset(1));
		assertEquals(3, table.mipRowOffset(2));
		assertEquals(4, table.textureHeight());
	}

	@Test
	void encodesResidentArrayLayersAsRgbaAndClearsEvictedTiles() {
		VirtualTexturePageTable table = new VirtualTexturePageTable(256, 256, 128, 2, 0);
		VirtualTextureTileKey key = new VirtualTextureTileKey(0, 0, 1, 0);

		table.setResident(key, 0x123456);

		assertEquals(0x123457, table.encodedLayer(key));
		assertEquals(0xFF573412, table.pixelColorAt(1, 0));
		assertTrue(table.clear(key));
		assertFalse(table.clear(key));
		assertEquals(0, table.encodedLayer(key));
		assertEquals(0, table.pixelColorAt(1, 0));
	}

	@Test
	void clearsAllEntriesWhenAtlasGenerationChanges() {
		VirtualTexturePageTable table = new VirtualTexturePageTable(256, 256, 128, 1, 0);
		table.setResident(new VirtualTextureTileKey(0, 0, 1, 1), 17);

		table.resetForGeneration(1);

		assertEquals(1, table.atlasGeneration());
		assertEquals(0, table.encodedLayer(new VirtualTextureTileKey(1, 0, 1, 1)));
		assertThrows(
			IllegalArgumentException.class,
			() -> table.encodedLayer(new VirtualTextureTileKey(0, 0, 1, 1))
		);
	}

	@Test
	void rejectsInvalidTileCoordinatesAndLayerIds() {
		VirtualTexturePageTable table = new VirtualTexturePageTable(256, 256, 128, 1, 3);

		assertThrows(
			IllegalArgumentException.class,
			() -> table.setResident(new VirtualTextureTileKey(3, 0, 2, 0), 0)
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> table.setResident(new VirtualTextureTileKey(3, 0, 0, 0), 0xFFFFFF)
		);
	}
}
