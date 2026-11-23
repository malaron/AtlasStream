package com.malaron.atlasstream.streaming.compact;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactFreeSlotsTest {
	@Test
	void splitsAndCoalescesLargeSlots() {
		CompactFreeSlots slots = new CompactFreeSlots();
		slots.release(0, 0, 1088, 1088);
		assertArrayEquals(new int[] {0, 0}, slots.allocate(192, 192));
		assertArrayEquals(new int[] {192, 0}, slots.allocate(192, 192));
		assertNull(slots.allocate(1088, 1088));
		slots.release(0, 0, 192, 192);
		slots.release(192, 0, 192, 192);
		assertArrayEquals(new int[] {0, 0}, slots.allocate(1088, 1088));
	}

	@Test
	void reservationCopyDoesNotChangeRealSlots() {
		CompactFreeSlots slots = new CompactFreeSlots();
		slots.release(16, 16, 384, 192);
		CompactFreeSlots simulated = slots.copy();
		assertArrayEquals(new int[] {16, 16}, simulated.allocate(192, 192));
		assertArrayEquals(new int[] {208, 16}, simulated.allocate(192, 192));
		assertNull(simulated.allocate(192, 192));
		assertEquals(1, slots.countFitting(384, 192));
		assertArrayEquals(new int[] {16, 16}, slots.allocate(384, 192));
	}

	@Test
	void mismatchedRectanglesDoNotMergeOrFit() {
		CompactFreeSlots slots = new CompactFreeSlots();
		slots.release(0, 0, 192, 192);
		slots.release(192, 0, 192, 384);
		assertNull(slots.allocate(384, 192));
		assertEquals(1, slots.countFitting(192, 384));
	}

	@Test
	void repeatedSplitsAndReleasesNeverOverlapLiveSlots() {
		CompactFreeSlots slots = new CompactFreeSlots();
		slots.release(0, 0, 1088, 1088);
		List<int[]> live = new ArrayList<>();
		Random random = new Random(42);
		for (int i = 0; i < 10_000; i++) {
			if (!live.isEmpty() && random.nextBoolean()) {
				int[] rect = live.remove(random.nextInt(live.size()));
				slots.release(rect[0], rect[1], rect[2], rect[3]);
			} else {
				int width = 16 * (1 + random.nextInt(20));
				int height = 16 * (1 + random.nextInt(20));
				int[] position = slots.allocate(width, height);
				if (position == null) {
					continue;
				}
				assertTrue(position[0] >= 0 && position[1] >= 0);
				assertTrue(position[0] + width <= 1088 && position[1] + height <= 1088);
				for (int[] rect : live) {
					assertTrue(position[0] + width <= rect[0] || rect[0] + rect[2] <= position[0]
						|| position[1] + height <= rect[1] || rect[1] + rect[3] <= position[1]);
				}
				live.add(new int[] {position[0], position[1], width, height});
			}
		}
	}
}
