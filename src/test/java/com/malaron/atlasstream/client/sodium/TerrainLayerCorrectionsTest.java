package com.malaron.atlasstream.client.sodium;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainLayerCorrectionsTest {
	@Test
	void correctionsDefaultOnAndCanBeToggledIndependently() {
		assertTrue(TerrainLayerCorrections.overlaysUseCutout());
		assertTrue(TerrainLayerCorrections.flowersUseCutout());
		assertTrue(TerrainLayerCorrections.hasActiveCorrections());
		try {
			TerrainLayerCorrections.setOverlaysUseCutout(false);
			assertFalse(TerrainLayerCorrections.overlaysUseCutout());
			assertTrue(TerrainLayerCorrections.hasActiveCorrections());
			TerrainLayerCorrections.setFlowersUseCutout(false);
			assertFalse(TerrainLayerCorrections.flowersUseCutout());
			assertFalse(TerrainLayerCorrections.hasActiveCorrections());
			TerrainLayerCorrections.setOverlaysUseCutout(true);
			assertTrue(TerrainLayerCorrections.overlaysUseCutout());
			assertTrue(TerrainLayerCorrections.hasActiveCorrections());
			TerrainLayerCorrections.setFlowersUseCutout(true);
			assertTrue(TerrainLayerCorrections.flowersUseCutout());
		} finally {
			TerrainLayerCorrections.setOverlaysUseCutout(true);
			TerrainLayerCorrections.setFlowersUseCutout(true);
		}
	}
}
