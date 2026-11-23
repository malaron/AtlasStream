package com.malaron.atlasstream.render;

public final class OverlayQuadMatcher {
	private static final float EPSILON = 0.0001f;

	private OverlayQuadMatcher() {
	}

	public static boolean isOverlayQuad(boolean solid, boolean facesUp, float y0, float y1, float y2, float y3,
			float minX, float maxX, float minZ, float maxZ) {
		return solid && facesUp
			&& y0 > 1.0f + EPSILON && y0 <= 1.125f
			&& Math.abs(y1 - y0) <= EPSILON && Math.abs(y2 - y0) <= EPSILON && Math.abs(y3 - y0) <= EPSILON
			&& minX >= -EPSILON && maxX <= 1.0f + EPSILON && maxX - minX >= 0.25f
			&& minZ >= -EPSILON && maxZ <= 1.0f + EPSILON && maxZ - minZ >= 0.25f;
	}
}
