package com.malaron.loadsoflods.client;

public final class ItemFrameSpriteTracker {
	private static final ThreadLocal<Integer> FRAME_DEPTH = new ThreadLocal<>();

	private ItemFrameSpriteTracker() {
	}

	public static void beginFrame() {
		Integer depth = FRAME_DEPTH.get();
		FRAME_DEPTH.set(depth == null ? 1 : depth + 1);
	}

	public static void endFrame() {
		int depth = FRAME_DEPTH.get() - 1;
		if (depth == 0) {
			FRAME_DEPTH.remove();
		} else {
			FRAME_DEPTH.set(depth);
		}
	}

	public static boolean isSubmittingFrame() {
		return FRAME_DEPTH.get() != null;
	}
}
