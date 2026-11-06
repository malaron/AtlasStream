package com.malaron.loadsoflods.cache;

import java.util.List;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VirtualTextureTileBuilderTest {
	private static final int RED = 0xFFFF0000;
	private static final int GREEN = 0xFF00FF00;
	private static final int BLUE = 0xFF0000FF;
	private static final int WHITE = 0xFFFFFFFF;

	@Test
	void buildsTileMipChainFromAtlasMipImages() {
		VirtualTextureTileBuilder builder = new VirtualTextureTileBuilder(
			4,
			4,
			4,
			2,
			3,
			List.of(sprite("tile", 0, 0, 4, 4, 0, 0, false,
				image(4, 4, RED),
				image(2, 2, GREEN)))
		);

		List<VirtualTextureTileBuilder.TileMip> mips = builder.build(
			new VirtualTextureTileKey(3, 0, 0, 0),
			3
		);

		assertEquals(3, mips.size());
		assertEquals(4, mips.get(0).width());
		assertEquals(RED, mips.get(0).pixel(3, 3));
		assertEquals(2, mips.get(1).width());
		assertEquals(GREEN, mips.get(1).pixel(1, 1));
		assertEquals(1, mips.get(2).width());
		assertEquals(GREEN, mips.get(2).pixel(0, 0));
	}

	@Test
	void copiesSpriteEdgePixelsIntoAtlasPadding() {
		VirtualTextureTileBuilder builder = new VirtualTextureTileBuilder(
			4,
			4,
			4,
			1,
			0,
			List.of(sprite("padded", 1, 1, 2, 2, 1, 1, false,
				image(2, 2, RED, GREEN, BLUE, WHITE)))
		);

		VirtualTextureTileBuilder.TileMip mip = builder.build(
			new VirtualTextureTileKey(0, 0, 0, 0),
			1
		).get(0);

		assertEquals(RED, mip.pixel(0, 0));
		assertEquals(GREEN, mip.pixel(3, 0));
		assertEquals(BLUE, mip.pixel(0, 3));
		assertEquals(WHITE, mip.pixel(3, 3));
	}

	@Test
	void rejectsTilesIntersectingAnimatedSprites() {
		VirtualTextureTileBuilder builder = new VirtualTextureTileBuilder(
			8,
			8,
			4,
			1,
			0,
			List.of(sprite("animated", 3, 0, 1, 1, 0, 0, true))
		);

		VirtualTextureTileBuilder.AnimatedTileException exception = assertThrows(
			VirtualTextureTileBuilder.AnimatedTileException.class,
			() -> builder.build(new VirtualTextureTileKey(0, 0, 0, 0), 1)
		);

		assertEquals("animated", exception.spriteName());
	}

	@Test
	void rejectsStaleAndOutOfRangeTileKeys() {
		VirtualTextureTileBuilder builder = new VirtualTextureTileBuilder(
			8,
			8,
			4,
			2,
			2,
			List.of()
		);

		assertThrows(
			IllegalArgumentException.class,
			() -> builder.build(new VirtualTextureTileKey(1, 0, 0, 0), 1)
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> builder.build(new VirtualTextureTileKey(2, 1, 2, 0), 1)
		);
	}

	@Test
	void retainsPaddedSpritesAcrossTileAndMipBoundaries() {
		var builder = new VirtualTextureTileBuilder(16, 8, 4, 3, 1, List.of(
			sprite("crossing", 3, 1, 2, 4, 1, 1, false,
				image(2, 4, RED), image(1, 2, GREEN), image(1, 1, BLUE)),
			sprite("other", 12, 0, 4, 4, 0, 0, true)
		));
		var left = builder.build(new VirtualTextureTileKey(1, 0, 0, 0), 3);
		var right = builder.build(new VirtualTextureTileKey(1, 0, 1, 0), 3);
		assertEquals(RED, left.get(0).pixel(3, 1));
		assertEquals(RED, right.get(0).pixel(1, 1));
		assertEquals(GREEN, left.get(1).pixel(1, 0));
		assertEquals(GREEN, right.get(1).pixel(0, 0));
		assertEquals(BLUE, left.get(2).pixel(0, 0));
		assertEquals(BLUE, right.get(2).pixel(0, 0));
	}

	private static VirtualTextureTileBuilder.SpriteSource sprite(
		String name,
		int x,
		int y,
		int width,
		int height,
		int paddingX,
		int paddingY,
		boolean animated,
		VirtualTextureTileBuilder.MipImage... mipImages
	) {
		return new VirtualTextureTileBuilder.SpriteSource(
			name,
			x,
			y,
			width,
			height,
			paddingX,
			paddingY,
			animated,
			List.of(mipImages)
		);
	}

	private static VirtualTextureTileBuilder.MipImage image(int width, int height, int... pixels) {
		int[] imagePixels = pixels.length == 1
			? new int[Math.multiplyExact(width, height)]
			: pixels;
		if (pixels.length == 1) {
			Arrays.fill(imagePixels, pixels[0]);
		}
		if (imagePixels.length != width * height) {
			throw new IllegalArgumentException("Test image pixel count does not match its dimensions");
		}
		return new VirtualTextureTileBuilder.MipImage() {
			@Override
			public int width() {
				return width;
			}

			@Override
			public int height() {
				return height;
			}

			@Override
			public int pixel(int x, int y) {
				return imagePixels[y * width + x];
			}
		};
	}
}
