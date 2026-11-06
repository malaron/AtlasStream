package com.malaron.loadsoflods.lod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SectionLodPolicyTest {
	private static final LodConfig CONFIG = new LodConfig(128.0, 16.0, 1024);

	@Test
	void zeroDistanceKeepsEverySectionLowResolution() {
		LodConfig off = new LodConfig(0.0, 16.0, 1024);
		assertEquals(SectionLod.LOW_RESOLUTION, SectionLodPolicy.initialLevel(0, 0, 0, 8.0, 8.0, 8.0, off));
		assertEquals(
			SectionLod.LOW_RESOLUTION,
			SectionLodPolicy.updateLevel(SectionLod.HIGH_RESOLUTION, 0, 0, 0, 8.0, 8.0, 8.0, off)
		);
	}

	@Test
	void measuresDistanceToSectionBounds() {
		assertEquals(
			SectionLod.HIGH_RESOLUTION,
			SectionLodPolicy.initialLevel(0, 0, 0, 16.0, 8.0, 8.0, CONFIG)
		);
		assertEquals(
			SectionLod.HIGH_RESOLUTION,
			SectionLodPolicy.initialLevel(0, 0, 0, 144.0, 8.0, 8.0, CONFIG)
		);
		assertEquals(
			SectionLod.LOW_RESOLUTION,
			SectionLodPolicy.initialLevel(0, 0, 0, 144.01, 8.0, 8.0, CONFIG)
		);
		assertEquals(
			SectionLod.HIGH_RESOLUTION,
			SectionLodPolicy.initialLevel(-1, 0, 0, -8.0, 8.0, 8.0, CONFIG)
		);
		assertEquals(
			SectionLod.LOW_RESOLUTION,
			SectionLodPolicy.initialLevel(0, 0, 0, 136.0, 128.0, 8.0, CONFIG)
		);
	}

	@Test
	void appliesHysteresisInBothDirections() {
		assertEquals(
			SectionLod.HIGH_RESOLUTION,
			SectionLodPolicy.updateLevel(SectionLod.HIGH_RESOLUTION, 0, 0, 0, 160.0, 8.0, 8.0, CONFIG)
		);
		assertEquals(
			SectionLod.LOW_RESOLUTION,
			SectionLodPolicy.updateLevel(SectionLod.HIGH_RESOLUTION, 0, 0, 0, 160.01, 8.0, 8.0, CONFIG)
		);
		assertEquals(
			SectionLod.LOW_RESOLUTION,
			SectionLodPolicy.updateLevel(SectionLod.LOW_RESOLUTION, 0, 0, 0, 128.0, 8.0, 8.0, CONFIG)
		);
		assertEquals(
			SectionLod.HIGH_RESOLUTION,
			SectionLodPolicy.updateLevel(SectionLod.LOW_RESOLUTION, 0, 0, 0, 127.99, 8.0, 8.0, CONFIG)
		);
	}

	@Test
	void equalDistanceAndHysteresisStillPromotesNearbySections() {
		LodConfig config = new LodConfig(16.0, 16.0, 1024);
		assertEquals(SectionLod.LOW_RESOLUTION,
			SectionLodPolicy.updateLevel(SectionLod.LOW_RESOLUTION, 0, 0, 0, 24.0, 8.0, 8.0, config));
		assertEquals(SectionLod.HIGH_RESOLUTION,
			SectionLodPolicy.updateLevel(SectionLod.LOW_RESOLUTION, 0, 0, 0, 23.99, 8.0, 8.0, config));
		assertEquals(SectionLod.HIGH_RESOLUTION,
			SectionLodPolicy.updateLevel(SectionLod.LOW_RESOLUTION, 0, 0, 0, 8.0, 8.0, 8.0, config));
		assertEquals(SectionLod.HIGH_RESOLUTION,
			SectionLodPolicy.updateLevel(SectionLod.HIGH_RESOLUTION, 0, 0, 0, 48.0, 8.0, 8.0, config));
		assertEquals(SectionLod.LOW_RESOLUTION,
			SectionLodPolicy.updateLevel(SectionLod.HIGH_RESOLUTION, 0, 0, 0, 48.01, 8.0, 8.0, config));
	}

	@Test
	void largeHysteresisDoesNotEliminateInnerHighResolutionRange() {
		LodConfig config = new LodConfig(16.0, 15.9, 1024);
		assertEquals(SectionLod.HIGH_RESOLUTION,
			SectionLodPolicy.updateLevel(SectionLod.LOW_RESOLUTION, 0, 0, 0, 23.9, 8.0, 8.0, config));
		assertEquals(SectionLod.LOW_RESOLUTION,
			SectionLodPolicy.updateLevel(SectionLod.LOW_RESOLUTION, 0, 0, 0, 24.0, 8.0, 8.0, config));
	}

	@Test
	void noHysteresisRetainsConfiguredDistanceBoundary() {
		LodConfig config = new LodConfig(16.0, 0.0, 1024);
		assertEquals(SectionLod.HIGH_RESOLUTION,
			SectionLodPolicy.updateLevel(SectionLod.LOW_RESOLUTION, 0, 0, 0, 31.99, 8.0, 8.0, config));
		assertEquals(SectionLod.LOW_RESOLUTION,
			SectionLodPolicy.updateLevel(SectionLod.LOW_RESOLUTION, 0, 0, 0, 32.0, 8.0, 8.0, config));
	}

	@Test
	void equalHysteresisKeepsHighResolutionUntilOuterBoundaryIsCrossed() {
		LodConfig config = new LodConfig(16.0, 16.0, 1024);
		SectionLod state = SectionLod.LOW_RESOLUTION;
		state = SectionLodPolicy.updateLevel(state, 0, 0, 0, 23.0, 8.0, 8.0, config);
		assertEquals(SectionLod.HIGH_RESOLUTION, state);
		state = SectionLodPolicy.updateLevel(state, 0, 0, 0, 30.0, 8.0, 8.0, config);
		assertEquals(SectionLod.HIGH_RESOLUTION, state);
		state = SectionLodPolicy.updateLevel(state, 0, 0, 0, 48.01, 8.0, 8.0, config);
		assertEquals(SectionLod.LOW_RESOLUTION, state);
		state = SectionLodPolicy.updateLevel(state, 0, 0, 0, 24.0, 8.0, 8.0, config);
		assertEquals(SectionLod.LOW_RESOLUTION, state);
	}
}
