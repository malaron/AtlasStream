package com.malaron.atlasstream.atlas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TranslucentMipAlphaTest {
	@Test
	void averagesSourceAlphaAndPreservesMipColors() {
		int[] base = {
			0x00000000, 0x40000000, 0x00000000, 0x00000000,
			0x80000000, 0xFF000000, 0x00000000, 0x00000000,
			0xFF000000, 0xFF000000, 0x20000000, 0x20000000,
			0xFF000000, 0xFF000000, 0x20000000, 0x20000000
		};
		TranslucentMipAlpha alpha = new TranslucentMipAlpha(base, 4, 4);
		int[] mip1 = {0x11223344, 0x11223344, 0x11223344, 0x11223344};
		alpha.applyNext(mip1, 2, 2);
		assertArrayEquals(new int[] {0x6F223344, 0x00223344, 0xFF223344, 0x20223344}, mip1);
		int[] mip2 = {0x22334455};
		alpha.applyNext(mip2, 1, 1);
		assertArrayEquals(new int[] {0x63334455}, mip2);
	}

	@Test
	void handlesSinglePixelDimensionAndZeroAlpha() {
		TranslucentMipAlpha alpha = new TranslucentMipAlpha(new int[4], 1, 4);
		int[] mip1 = {0x06123456, 0x06765432};
		alpha.applyNext(mip1, 1, 2);
		assertArrayEquals(new int[] {0x00123456, 0x00765432}, mip1);
		int[] mip2 = {0x0C123456};
		alpha.applyNext(mip2, 1, 1);
		assertArrayEquals(new int[] {0x00123456}, mip2);
	}

	@Test
	void rejectsInvalidDimensions() {
		assertThrows(IllegalArgumentException.class, () -> new TranslucentMipAlpha(new int[3], 2, 2));
		TranslucentMipAlpha alpha = new TranslucentMipAlpha(new int[16], 4, 4);
		assertThrows(IllegalArgumentException.class, () -> alpha.applyNext(new int[9], 3, 3));
	}
}
