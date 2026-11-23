package com.malaron.atlasstream.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayQuadMatcherTest {
	@Test
	void acceptsBothKnownGrassOverlayHeights() {
		assertTrue(OverlayQuadMatcher.isOverlayQuad(true, true, 16.5f / 16, 16.5f / 16, 16.5f / 16, 16.5f / 16, 0, 1, 0, 1));
		assertTrue(OverlayQuadMatcher.isOverlayQuad(true, true, 16.8f / 16, 16.8f / 16, 16.8f / 16, 16.8f / 16, 0, 1, 0, 1));
	}

	@Test
	void rejectsUnrelatedGeometry() {
		assertFalse(OverlayQuadMatcher.isOverlayQuad(false, true, 1.05f, 1.05f, 1.05f, 1.05f, 0, 1, 0, 1));
		assertFalse(OverlayQuadMatcher.isOverlayQuad(true, false, 1.05f, 1.05f, 1.05f, 1.05f, 0, 1, 0, 1));
		assertFalse(OverlayQuadMatcher.isOverlayQuad(true, true, 1, 1, 1, 1, 0, 1, 0, 1));
		assertFalse(OverlayQuadMatcher.isOverlayQuad(true, true, 1.2f, 1.2f, 1.2f, 1.2f, 0, 1, 0, 1));
		assertFalse(OverlayQuadMatcher.isOverlayQuad(true, true, 1.05f, 1.05f, 1.06f, 1.05f, 0, 1, 0, 1));
		assertFalse(OverlayQuadMatcher.isOverlayQuad(true, true, 1.05f, 1.05f, 1.05f, 1.05f, -0.5f, 1, 0, 1));
		assertFalse(OverlayQuadMatcher.isOverlayQuad(true, true, 1.05f, 1.05f, 1.05f, 1.05f, 0, 0.1f, 0, 1));
		assertFalse(OverlayQuadMatcher.isOverlayQuad(true, true, Float.NaN, 1.05f, 1.05f, 1.05f, 0, 1, 0, 1));
	}
}
