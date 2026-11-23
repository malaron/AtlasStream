package com.malaron.atlasstream.render;

public final class FlowerLayerPolicy {
	private FlowerLayerPolicy() {
	}

	public static boolean shouldUseCutout(
		boolean enabled, boolean smallFlower, boolean translucent, boolean forceOpaque
	) {
		return enabled && smallFlower && translucent && !forceOpaque;
	}
}
