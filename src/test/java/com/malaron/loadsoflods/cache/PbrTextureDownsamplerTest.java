package com.malaron.loadsoflods.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PbrTextureDownsamplerTest {
	@Test
	void preservesAndRenormalizesFlatTangentSpaceNormals() {
		int[] pixels = {
			0xff7f7fff,
			0xff807fff,
			0xff7f80ff,
			0xff8080ff
		};

		int result = PbrTextureDownsampler.averageLabPbrNormalPixels(pixels, pixels.length);

		assertEquals(255, result >>> 24);
		assertEquals(128, result >>> 16 & 255);
		assertEquals(128, result >>> 8 & 255);
		assertEquals(255, result & 255);
	}

	@Test
	void averagesRedGreenNormalAndPreservesBlueOcclusion() {
		int[] pixels = {
			0xffe080b0,
			0xff208040
		};

		int result = PbrTextureDownsampler.averageLabPbrNormalPixels(pixels, pixels.length);

		assertEquals(129, result >>> 16 & 255);
		assertEquals(129, result >>> 8 & 255);
		assertEquals(120, result & 255);
	}

	@Test
	void preservesPomHeightAlphaIndependentlyFromNormalAndOcclusion() {
		int[] pixels = {
			0xffe08040,
			0x00e080c0
		};

		int result = PbrTextureDownsampler.averageLabPbrNormalPixels(pixels, pixels.length);

		assertEquals(128, result >>> 24);
		assertEquals(225, result >>> 16 & 255);
		assertEquals(129, result >>> 8 & 255);
		assertEquals(128, result & 255);
	}

	@Test
	void averagesLabPbrSpecularChannelsIndependentlyOfAlpha() {
		int[] pixels = {
			0x00ff2040,
			0xff0080c0
		};

		int result = PbrTextureDownsampler.averageLabPbrSpecularPixels(pixels, pixels.length);

		assertEquals(128, result >>> 24);
		assertEquals(128, result >>> 16 & 255);
		assertEquals(80, result >>> 8 & 255);
		assertEquals(128, result & 255);
	}

	@Test
	void rejectsInvalidSampleCounts() {
		assertThrows(
			IllegalArgumentException.class,
			() -> PbrTextureDownsampler.averageLabPbrNormalPixels(new int[2], 0)
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> PbrTextureDownsampler.averageLabPbrNormalPixels(new int[2], 3)
		);
	}
}
