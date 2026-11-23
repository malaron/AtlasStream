package com.malaron.atlasstream.client.atlas;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.mojang.blaze3d.platform.NativeImage;

class AtlasRuntimeStateRemapTest {
	@Test
	void writesPackedGpuBytesWithoutArgBConversion() {
		int packed = 0x04030201;
		try (NativeImage image = new NativeImage(1, 1, true)) {
			AtlasRuntimeState.writeRemapPixel(image, 0, 0, packed);
			assertEquals(0x04010203, image.getPixel(0, 0));
		}
	}
}
