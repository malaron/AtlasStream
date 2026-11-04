package com.malaron.loadsoflods.lod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SectionLodPolicyTest {
	private static final LodConfig CONFIG = new LodConfig(128.0, 16.0, 1024);

	@Test
	void zeroDistanceKeepsEverySectionLowResolution() {
		LodConfig off = new LodConfig(0.0, 0.0, 1024);
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
}
