package com.malaron.atlasstream.streaming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NearMovementLookaheadTest {
	@Test
	void projectsMovementAndTemporarilyKeepsDirectionWhenStopped() {
		NearMovementLookahead lookahead = new NearMovementLookahead();
		lookahead.update(0, 0, 1);
		assertEquals(0, lookahead.targetX(0));
		lookahead.update(1, 0, 100);
		assertEquals(13, lookahead.targetX(1));
		lookahead.update(1, 0, 900_000_000);
		assertEquals(13, lookahead.targetX(1));
		lookahead.update(1, 0, 1_100_000_100);
		assertEquals(1, lookahead.targetX(1));
	}

	@Test
	void reversalsAndTeleportsDoNotKeepOldPrediction() {
		NearMovementLookahead lookahead = new NearMovementLookahead();
		lookahead.update(0, 0, 1);
		lookahead.update(1, 0, 2);
		lookahead.update(0, 0, 3);
		assertEquals(-12, lookahead.targetX(0));
		lookahead.update(100, 100, 4);
		assertEquals(100, lookahead.targetX(100));
		assertEquals(100, lookahead.targetZ(100));
	}

	@Test
	void accumulatesSmallMovements() {
		NearMovementLookahead lookahead = new NearMovementLookahead();
		lookahead.update(0, 0, 1);
		lookahead.update(0.03, 0, 2);
		lookahead.update(0.06, 0, 3);
		assertEquals(12.06, lookahead.targetX(0.06), 0.00001);
	}
}
