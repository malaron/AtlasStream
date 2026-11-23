package com.malaron.atlasstream.streaming.compact;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactAtlasPriorityTest {
	@Test
	void prioritizesSectionsWithinFourBlocksIncludingAcrossNegativeBoundaries() {
		assertTrue(CompactAtlasPriority.isImmediateSection(0, 0, 0, 16.1, 8, 8));
		assertTrue(CompactAtlasPriority.isImmediateSection(-1, 0, 0, 4, 8, 8));
		assertFalse(CompactAtlasPriority.isImmediateSection(-1, 0, 0, 4.01, 8, 8));
		assertTrue(CompactAtlasPriority.isImmediateSection(0, 0, 0, 18, 8, 18));
		assertFalse(CompactAtlasPriority.isImmediateSection(0, 0, 0, 19, 8, 19));
		assertTrue(CompactAtlasPriority.isImmediateSection(0, -1, 0, 8, 1, 8));
	}

	@Test
	void cameraAndTouchedSpritesTakePriorityEvenWhenDistancesTie() {
		assertTrue(CompactAtlasPriority.compare(true, 0, false, 0) < 0);
		assertTrue(CompactAtlasPriority.canEvict(true, 0, false, 0, 8));
		assertFalse(CompactAtlasPriority.canEvict(false, 0, true, 100, 8));
	}

	@Test
	void equalPriorityKeepsDistanceMarginAndAvoidsTiedSwaps() {
		assertFalse(CompactAtlasPriority.canEvict(true, 0, true, 0, 8));
		assertFalse(CompactAtlasPriority.canEvict(false, 0, false, 8, 8));
		assertTrue(CompactAtlasPriority.canEvict(false, 0, false, 8.1, 8));
		assertTrue(CompactAtlasPriority.compare(false, 2, false, 3) < 0);
	}

	@Test
	void unneededResidentsAreEvictableButUnneededRequestsCannotDisplaceNeededSprites() {
		assertTrue(CompactAtlasPriority.canEvict(false, 0, false, Double.MAX_VALUE, 8));
		assertFalse(CompactAtlasPriority.canEvict(false, Double.MAX_VALUE, false, 0, 8));
	}

	@Test
	void cameraSectionCanReplaceEightTiedResidentsWithoutEvictingItsOwnSprites() {
		List<Boolean> residents = new ArrayList<>();
		for (int i = 0; i < 195; i++) {
			residents.add(false);
		}
		for (int i = 0; i < 8; i++) {
			residents.sort((a, b) -> CompactAtlasPriority.compare(b, 0, a, 0));
			boolean victim = residents.get(0);
			assertTrue(CompactAtlasPriority.canEvict(true, 0, victim, 0, 8));
			residents.set(0, true);
		}
		assertEquals(8, residents.stream().filter(Boolean::booleanValue).count());
		assertFalse(CompactAtlasPriority.canEvict(false, 0, true, 0, 8));
	}

}
