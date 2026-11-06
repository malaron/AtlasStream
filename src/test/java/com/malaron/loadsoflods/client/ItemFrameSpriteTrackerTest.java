package com.malaron.loadsoflods.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemFrameSpriteTrackerTest {
	@Test
	void frameSubmissionScopeSupportsNesting() {
		assertFalse(ItemFrameSpriteTracker.isSubmittingFrame());
		ItemFrameSpriteTracker.beginFrame();
		try {
			assertTrue(ItemFrameSpriteTracker.isSubmittingFrame());
			ItemFrameSpriteTracker.beginFrame();
			try {
				assertTrue(ItemFrameSpriteTracker.isSubmittingFrame());
			} finally {
				ItemFrameSpriteTracker.endFrame();
			}
			assertTrue(ItemFrameSpriteTracker.isSubmittingFrame());
		} finally {
			ItemFrameSpriteTracker.endFrame();
		}
		assertFalse(ItemFrameSpriteTracker.isSubmittingFrame());
	}

	@Test
	void frameSubmissionScopeIsThreadLocal() throws InterruptedException {
		ItemFrameSpriteTracker.beginFrame();
		try {
			boolean[] observed = new boolean[1];
			Thread thread = new Thread(() -> observed[0] = ItemFrameSpriteTracker.isSubmittingFrame());
			thread.start();
			thread.join();
			assertFalse(observed[0]);
			assertTrue(ItemFrameSpriteTracker.isSubmittingFrame());
		} finally {
			ItemFrameSpriteTracker.endFrame();
		}
	}
}
