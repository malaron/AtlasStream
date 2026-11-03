package com.malaron.loadsoflods.cache;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualTextureRequestTrackerTest {
	@Test
	void collectsAndDeduplicatesCompletedUvQuads() {
		VirtualTextureRequestTracker tracker = new VirtualTextureRequestTracker();

		tracker.recordSpriteUsage(0.1, 0.2, 0.4, 0.5, 7);
		tracker.recordSpriteUsage(0.1, 0.2, 0.4, 0.5, 7);
		List<VirtualTextureRequestTracker.UvQuad> quads = tracker.drain(10, 7);

		assertEquals(
			List.of(new VirtualTextureRequestTracker.UvQuad(0.1, 0.2, 0.4, 0.5, 7)),
			quads
		);
		assertEquals(2, tracker.completedQuadCount());
	}

	@Test
	void drainsOnlyQuadsForTheCurrentAtlasGeneration() {
		VirtualTextureRequestTracker tracker = new VirtualTextureRequestTracker();

		tracker.recordSpriteUsage(0.1, 0.2, 0.4, 0.5, 3);
		tracker.recordSpriteUsage(0.2, 0.3, 0.5, 0.6, 4);

		assertEquals(
			List.of(new VirtualTextureRequestTracker.UvQuad(0.2, 0.3, 0.5, 0.6, 4)),
			tracker.drain(10, 4)
		);
	}

	@Test
	void deduplicatesSpriteUsageAtItsNearestObservedSectionDistance() {
		VirtualTextureRequestTracker tracker = new VirtualTextureRequestTracker();

		tracker.recordSpriteUsage(0.1, 0.2, 0.4, 0.5, 7, 400.0);
		tracker.recordSpriteUsage(0.2, 0.3, 0.5, 0.6, 7, 100.0);
		tracker.recordSpriteUsage(0.1, 0.2, 0.4, 0.5, 7, 25.0);

		assertEquals(
			List.of(
				new VirtualTextureRequestTracker.UvQuad(0.1, 0.2, 0.4, 0.5, 7, 25.0),
				new VirtualTextureRequestTracker.UvQuad(0.2, 0.3, 0.5, 0.6, 7, 100.0)
			),
			tracker.drain(10, 7)
		);
	}

	@Test
	void cursorEnumeratesAllTilesTouchedAtEachMipLevel() {
		VirtualTextureLayout layout = new VirtualTextureLayout(512, 512, 128, 0);
		VirtualTextureRequestTracker.UvQuad quad =
			new VirtualTextureRequestTracker.UvQuad(0.1, 0.1, 0.4, 0.4, 2);
		VirtualTextureRequestTracker.TileCursor cursor =
			VirtualTextureRequestTracker.cursor(quad, layout, 4);
		List<VirtualTextureTileKey> keys = new ArrayList<>();

		VirtualTextureTileKey key;
		while ((key = cursor.next()) != null) {
			keys.add(key);
		}

		assertEquals(
			List.of(
				new VirtualTextureTileKey(2, 0, 0, 0),
				new VirtualTextureTileKey(2, 0, 1, 0),
				new VirtualTextureTileKey(2, 0, 0, 1),
				new VirtualTextureTileKey(2, 0, 1, 1),
				new VirtualTextureTileKey(2, 1, 0, 0),
				new VirtualTextureTileKey(2, 2, 0, 0),
				new VirtualTextureTileKey(2, 3, 0, 0)
			),
			keys
		);
		assertTrue(cursor.isComplete());
	}
}
