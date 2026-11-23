package com.malaron.atlasstream.client.sodium;

public final class BlockModelMeshTracker {
	private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<>();

	private BlockModelMeshTracker() {
	}

	public static void begin() {
		Integer depth = DEPTH.get();
		DEPTH.set(depth == null ? 1 : depth + 1);
	}

	public static void end() {
		int depth = DEPTH.get() - 1;
		if (depth == 0) {
			DEPTH.remove();
		} else {
			DEPTH.set(depth);
		}
	}

	public static boolean isSubmitting() {
		return DEPTH.get() != null;
	}
}
