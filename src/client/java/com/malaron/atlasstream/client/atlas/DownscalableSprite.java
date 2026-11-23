package com.malaron.atlasstream.client.atlas;

public interface DownscalableSprite {
	/** Largest power-of-two divisor up to {@code requested} that keeps this sprite at 16 texels or more and evenly divisible. */
	int atlasStream$effectiveDivisor(int requested);

	void atlasStream$downscale(int divisor);
}
