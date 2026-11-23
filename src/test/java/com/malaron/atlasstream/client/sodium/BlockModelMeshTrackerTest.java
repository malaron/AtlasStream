package com.malaron.atlasstream.client.sodium;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockModelMeshTrackerTest {
	@Test
	void submissionScopeSupportsNesting() {
		assertFalse(BlockModelMeshTracker.isSubmitting());
		BlockModelMeshTracker.begin();
		try {
			assertTrue(BlockModelMeshTracker.isSubmitting());
			BlockModelMeshTracker.begin();
			try {
				assertTrue(BlockModelMeshTracker.isSubmitting());
			} finally {
				BlockModelMeshTracker.end();
			}
			assertTrue(BlockModelMeshTracker.isSubmitting());
		} finally {
			BlockModelMeshTracker.end();
		}
		assertFalse(BlockModelMeshTracker.isSubmitting());
	}

	@Test
	void submissionScopeIsThreadLocal() throws InterruptedException {
		BlockModelMeshTracker.begin();
		try {
			boolean[] observed = new boolean[1];
			Thread thread = new Thread(() -> observed[0] = BlockModelMeshTracker.isSubmitting());
			thread.start();
			thread.join();
			assertFalse(observed[0]);
			assertTrue(BlockModelMeshTracker.isSubmitting());
		} finally {
			BlockModelMeshTracker.end();
		}
	}
}
