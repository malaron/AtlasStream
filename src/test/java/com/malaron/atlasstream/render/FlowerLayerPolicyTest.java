package com.malaron.atlasstream.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowerLayerPolicyTest {
	@Test
	void onlyEnabledTranslucentSmallFlowersAreChanged() {
		assertTrue(FlowerLayerPolicy.shouldUseCutout(true, true, true, false));
		assertFalse(FlowerLayerPolicy.shouldUseCutout(false, true, true, false));
		assertFalse(FlowerLayerPolicy.shouldUseCutout(true, false, true, false));
		assertFalse(FlowerLayerPolicy.shouldUseCutout(true, true, false, false));
		assertFalse(FlowerLayerPolicy.shouldUseCutout(true, true, true, true));
	}
}
