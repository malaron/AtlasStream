package com.malaron.atlasstream.streaming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingWorkBudgetTest {
	@Test
	void permitsFirstOperationEvenWhenBudgetAlreadyElapsed() {
		assertTrue(StreamingWorkBudget.canContinue(0, 32, 8_000_000, 4_000_000));
		assertFalse(StreamingWorkBudget.canContinue(0, 0, 0, 4_000_000));
	}

	@Test
	void stopsAtTimeOrCountLimitAfterMakingProgress() {
		assertTrue(StreamingWorkBudget.canContinue(1, 32, 3_999_999, 4_000_000));
		assertFalse(StreamingWorkBudget.canContinue(1, 32, 4_000_000, 4_000_000));
		assertFalse(StreamingWorkBudget.canContinue(32, 32, 1, 4_000_000));
	}
}
