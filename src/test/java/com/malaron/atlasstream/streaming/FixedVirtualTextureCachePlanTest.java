package com.malaron.atlasstream.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FixedVirtualTextureCachePlanTest {
	@Test
	void producesOneStableGridInsideBudget() {
		FixedVirtualTextureCachePlan plan = FixedVirtualTextureCachePlan.create(
			128, 8, 1024L * 1024 * 1024, 32768, true
		);

		assertTrue(plan.capacityBytes() <= 1024L * 1024 * 1024);
		assertEquals(plan.gridWidth() * plan.gridHeight(), plan.slotCount());
		assertEquals(plan.gridWidth() * 128, plan.textureWidth());
		assertEquals(plan.gridHeight() * 128, plan.textureHeight());
	}

	@Test
	void budgetAndDeviceLimitOnlyAffectInitialSlotCount() {
		FixedVirtualTextureCachePlan small = FixedVirtualTextureCachePlan.create(
			128, 5, 256L * 1024 * 1024, 32768, true
		);
		FixedVirtualTextureCachePlan large = FixedVirtualTextureCachePlan.create(
			128, 5, 4L * 1024 * 1024 * 1024, 32768, true
		);

		assertTrue(small.slotCount() < large.slotCount());
		assertTrue(small.capacityBytes() <= 256L * 1024 * 1024);
		assertTrue(large.capacityBytes() <= 4L * 1024 * 1024 * 1024);
	}
}
