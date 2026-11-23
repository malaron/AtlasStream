package com.malaron.atlasstream.streaming;

public final class StreamingWorkBudget {
	private StreamingWorkBudget() {
	}

	public static boolean canContinue(int completed, int limit, long elapsedNanos, long budgetNanos) {
		return completed < limit && (completed == 0 || elapsedNanos < budgetNanos);
	}
}
