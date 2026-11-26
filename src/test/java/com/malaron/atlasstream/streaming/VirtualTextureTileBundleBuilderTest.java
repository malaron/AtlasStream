package com.malaron.atlasstream.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class VirtualTextureTileBundleBuilderTest {
	@Test
	void allChannelsUseIdenticalTileAndMipGeometry() {
		VirtualTextureTileBuilder.MipImage albedo = image(0xFFFF0000);
		VirtualTextureTileBuilder.MipImage normal = image(0xFF00FF00);
		VirtualTextureTileBuilder.MipImage specular = image(0xFF0000FF);
		var sourceAlbedo = source("albedo", albedo);
		var sourceNormal = source("normal", normal);
		var sourceSpecular = source("specular", specular);
		var bundleBuilder = new VirtualTextureTileBundleBuilder(
			builder(List.of(sourceAlbedo)),
			builder(List.of(sourceNormal)),
			builder(List.of(sourceSpecular))
		);

		VirtualTextureTileBundleBuilder.Bundle bundle = bundleBuilder.build(
			new VirtualTextureTileKey(1, 0, 0, 0), 2
		);

		assertEquals(2, bundle.albedo().size());
		assertEquals(bundle.albedo().get(0).width(), bundle.normal().get(0).width());
		assertEquals(bundle.albedo().get(0).width(), bundle.specular().get(0).width());
		assertEquals(0xFFFF0000, bundle.albedo().get(0).pixel(20, 20));
		assertEquals(0xFF00FF00, bundle.normal().get(0).pixel(20, 20));
		assertEquals(0xFF0000FF, bundle.specular().get(0).pixel(20, 20));
	}

	private static VirtualTextureTileBuilder builder(List<VirtualTextureTileBuilder.SpriteSource> sources) {
		return new VirtualTextureTileBuilder(256, 256, 128, 2, 1, sources);
	}

	private static VirtualTextureTileBuilder.SpriteSource source(
		String name,
		VirtualTextureTileBuilder.MipImage mip0
	) {
		VirtualTextureTileBuilder.MipImage mip1 = new ConstantImage(128, 128, mip0.pixel(0, 0));
		return new VirtualTextureTileBuilder.SpriteSource(
			name, 0, 0, 256, 256, 0, 0, false, List.of(mip0, mip1)
		);
	}

	private static VirtualTextureTileBuilder.MipImage image(int color) {
		return new ConstantImage(256, 256, color);
	}

	private record ConstantImage(int width, int height, int color)
		implements VirtualTextureTileBuilder.MipImage {
		@Override
		public int pixel(int x, int y) {
			return color;
		}
	}
}
