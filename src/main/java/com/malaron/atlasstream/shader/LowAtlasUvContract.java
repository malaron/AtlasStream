package com.malaron.atlasstream.shader;

import com.malaron.atlasstream.atlas.LowAtlasPacker;

public final class LowAtlasUvContract {
	private LowAtlasUvContract() {
	}

	public static float[] map(
		LowAtlasPacker.Placement placement,
		int sourceWidth,
		int sourceHeight,
		int destinationWidth,
		int destinationHeight,
		float u,
		float v
	) {
		double sourceX = u * sourceWidth;
		double sourceY = v * sourceHeight;
		double localX = sourceX - placement.sprite().sourceX();
		double localY = sourceY - placement.sprite().sourceY();
		return new float[] {
			(float) ((placement.x() + placement.paddingX() + localX / placement.factor()) / destinationWidth),
			(float) ((placement.y() + placement.paddingY() + localY / placement.factor()) / destinationHeight)
		};
	}

	public static int inverseSpriteX(
		LowAtlasPacker.Placement placement,
		int sourceWidth,
		int destinationWidth,
		float mappedU
	) {
		double destinationX = mappedU * destinationWidth;
		return (int) Math.round(
			placement.sprite().sourceX()
				+ (destinationX - placement.x() - placement.paddingX()) * placement.factor()
		);
	}

	public static int inverseSpriteY(
		LowAtlasPacker.Placement placement,
		int sourceHeight,
		int destinationHeight,
		float mappedV
	) {
		double destinationY = mappedV * destinationHeight;
		return (int) Math.round(
			placement.sprite().sourceY()
				+ (destinationY - placement.y() - placement.paddingY()) * placement.factor()
		);
	}
}
