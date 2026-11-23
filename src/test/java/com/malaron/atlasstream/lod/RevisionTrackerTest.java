package com.malaron.atlasstream.lod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RevisionTrackerTest {
	@Test
	void reportsEveryChangeOncePerConsumer() {
		RevisionTracker drawLists = new RevisionTracker();
		RevisionTracker region = new RevisionTracker();
		assertTrue(drawLists.update(0));
		assertTrue(region.update(0));
		assertFalse(drawLists.update(0));
		assertTrue(drawLists.update(1));
		assertFalse(drawLists.update(1));
		assertTrue(region.update(1));
		assertTrue(region.update(3));
		assertFalse(region.update(3));
	}
}
