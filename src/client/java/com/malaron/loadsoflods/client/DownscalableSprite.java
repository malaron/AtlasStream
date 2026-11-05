package com.malaron.loadsoflods.client;

public interface DownscalableSprite {
	/** Largest power-of-two divisor up to {@code requested} that keeps this sprite at 16 texels or more and evenly divisible. */
	int loadsOfLods$effectiveDivisor(int requested);

	void loadsOfLods$downscale(int divisor);
}
