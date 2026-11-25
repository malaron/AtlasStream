package com.malaron.atlasstream.streaming.compact;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactHybridLayoutTest {
	@Test
	void packsCurrentLargeAtlasAndMapsOriginalUvsToReducedPixels() {
		CompactHybridLayout layout = CompactHybridLayout.create(16384, 16384, 65536, 32768, 8, 5, 128);
		assertEquals(8192, layout.fallbackWidth());
		assertEquals(4096, layout.fallbackHeight());
		assertEquals(8192, layout.reservedWidth());
		assertEquals(4096, layout.reservedHeight());
		assertEquals(0.25, layout.fallbackU(0.5));
		assertEquals(0.125, layout.fallbackV(0.5));
		assertEquals(44_695_552L, layout.fallbackTexels());
	}

	@Test
	void reservesFullSparsePagesAtEveryMip() {
		CompactHybridLayout layout = CompactHybridLayout.create(16384, 16384, 40000, 20000, 8, 5, 128);
		assertEquals(6144, layout.reservedWidth());
		assertEquals(4096, layout.reservedHeight());
		for (int level = 0; level < layout.mipLevels(); level++) {
			assertEquals(0, (layout.reservedWidth() >> level) % 128);
			assertEquals(0, (layout.reservedHeight() >> level) % 128);
			assertTrue((layout.fallbackWidth() >> level) <= (layout.reservedWidth() >> level));
			assertTrue((layout.fallbackHeight() >> level) <= (layout.reservedHeight() >> level));
		}
	}

	@Test
	void acceptsAlreadyRepackedFallbackDimensions() {
		CompactHybridLayout layout = CompactHybridLayout.createFallback(
			16384, 16384, 8192, 4096, 5, 128
		);

		assertEquals(8192, layout.fallbackWidth());
		assertEquals(4096, layout.fallbackHeight());
		assertEquals(8192, layout.reservedWidth());
		assertEquals(4096, layout.reservedHeight());
	}

	@Test
	void rejectsFallbackThatCannotFitOrLeaveStreamedCapacity() {
		assertThrows(IllegalArgumentException.class,
			() -> CompactHybridLayout.create(16384, 16384, 65536, 32768, 2, 5, 128));
		assertThrows(IllegalArgumentException.class,
			() -> CompactHybridLayout.create(8192, 4096, 65536, 32768, 8, 5, 128));
		assertThrows(IllegalArgumentException.class,
			() -> CompactHybridLayout.create(16384, 16384, 65535, 32768, 8, 5, 128));
		assertThrows(IllegalArgumentException.class,
			() -> CompactHybridLayout.create(16000, 16384, 65536, 32768, 8, 5, 128));
		assertThrows(IllegalArgumentException.class,
			() -> CompactHybridLayout.create(16384, 16384, 65536, 32768, 8, 5, 96));
		assertThrows(IllegalArgumentException.class,
			() -> CompactHybridLayout.create(16384, 16384, 65536, 32768, 256, 5, 128));
	}

	@Test
	void streamedSlotsNeverOverlapFallbackPagesAtAnyMip() {
		CompactHybridLayout layout = CompactHybridLayout.create(16384, 16384, 65536, 32768, 8, 5, 128);
		CompactFreeSlots slots = layout.createStreamedSlots();
		List<int[]> allocations = new ArrayList<>();
		int count = 0;
		int[] slot;
		while ((slot = slots.allocate(1088, 1088)) != null) {
			allocations.add(slot);
			for (int level = 0; level < layout.mipLevels(); level++) {
				int lastFallbackPageX = ((layout.fallbackWidth() - 1) >> level) / 128;
				int lastFallbackPageY = ((layout.fallbackHeight() - 1) >> level) / 128;
				int firstSlotPageX = (slot[0] >> level) / 128;
				int firstSlotPageY = (slot[1] >> level) / 128;
				assertTrue(firstSlotPageX > lastFallbackPageX || firstSlotPageY > lastFallbackPageY);
			}
			count++;
		}
		assertEquals(186, count);
		assertEquals(225, (16384 / 1088) * (16384 / 1088));
		for (int[] allocation : allocations) {
			slots.release(allocation[0], allocation[1], 1088, 1088);
		}
		assertNotNull(slots.allocate(1088, 1088));
	}

	@Test
	void mixedSlotsAndReuseNeverTouchFallbackPages() {
		CompactHybridLayout layout = CompactHybridLayout.create(16384, 16384, 65536, 32768, 8, 5, 128);
		CompactFreeSlots slots = layout.createStreamedSlots();
		Random random = new Random(91);
		List<int[]> allocations = new ArrayList<>();
		for (int i = 0; i < 5000; i++) {
			if (!allocations.isEmpty() && random.nextBoolean()) {
				int[] old = allocations.remove(random.nextInt(allocations.size()));
				slots.release(old[0], old[1], old[2], old[3]);
			} else {
				int width = 16 * (1 + random.nextInt(80));
				int height = 16 * (1 + random.nextInt(80));
				int[] position = slots.allocate(width, height);
				if (position == null) {
					continue;
				}
				int[] allocated = {position[0], position[1], width, height};
				for (int level = 0; level < layout.mipLevels(); level++) {
					assertTrue((allocated[0] >> level) / 128 > ((layout.fallbackWidth() - 1) >> level) / 128
						|| (allocated[1] >> level) / 128 > ((layout.fallbackHeight() - 1) >> level) / 128);
				}
				for (int[] other : allocations) {
					assertTrue(allocated[0] + width <= other[0] || other[0] + other[2] <= allocated[0]
						|| allocated[1] + height <= other[1] || other[1] + other[3] <= allocated[1]);
				}
				allocations.add(allocated);
			}
		}
	}
}
