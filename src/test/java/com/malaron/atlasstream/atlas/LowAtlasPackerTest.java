package com.malaron.atlasstream.atlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

class LowAtlasPackerTest {
	@Test
	void packsMixedResolutionsWithoutUpscaling() {
		LowAtlasPacker.Layout layout = LowAtlasPacker.pack(List.of(
			sprite("minecraft:large", 1024),
			sprite("minecraft:medium", 256),
			sprite("minecraft:vanilla", 16)
		), 64, 1024);

		assertEquals(16, layout.placements().get(id("minecraft:large")).factor());
		assertEquals(4, layout.placements().get(id("minecraft:medium")).factor());
		assertEquals(1, layout.placements().get(id("minecraft:vanilla")).factor());
		assertEquals(64, layout.placements().get(id("minecraft:large")).width());
		assertEquals(64, layout.placements().get(id("minecraft:medium")).width());
		assertEquals(16, layout.placements().get(id("minecraft:vanilla")).width());
		assertTrue(layout.width() >= 64);
		assertTrue(layout.height() >= 64);
	}

	@Test
	void destinationRectanglesDoNotOverlap() {
		LowAtlasPacker.Layout layout = LowAtlasPacker.pack(List.of(
			sprite("minecraft:a", 1024),
			sprite("minecraft:b", 1024),
			sprite("minecraft:c", 16)
		), 64, 1024);
		List<LowAtlasPacker.Placement> placements = layout.placements().values().stream().toList();
		for (int first = 0; first < placements.size(); first++) {
			for (int second = first + 1; second < placements.size(); second++) {
				LowAtlasPacker.Placement a = placements.get(first);
				LowAtlasPacker.Placement b = placements.get(second);
				boolean overlaps = a.x() < b.x() + b.paddedWidth()
					&& b.x() < a.x() + a.paddedWidth()
					&& a.y() < b.y() + b.paddedHeight()
					&& b.y() < a.y() + a.paddedHeight();
				assertTrue(!overlaps);
			}
		}
	}

	@Test
	void preservesOriginalLayoutWhenEverySpriteFitsTheTarget() {
		LowAtlasPacker.Sprite first = new LowAtlasPacker.Sprite(
			id("minecraft:first"), 19, 37, 16, 16, 1, 1
		);
		LowAtlasPacker.Sprite second = new LowAtlasPacker.Sprite(
			id("minecraft:second"), 53, 91, 8, 8, 2, 2
		);

		LowAtlasPacker.Layout layout = LowAtlasPacker.preserveLayout(
			List.of(first, second), 16, 256, 256
		);

		assertEquals(256, layout.width());
		assertEquals(256, layout.height());
		assertEquals(18, layout.placements().get(id("minecraft:first")).x());
		assertEquals(36, layout.placements().get(id("minecraft:first")).y());
		assertEquals(1, layout.placements().get(id("minecraft:first")).factor());
		assertEquals(51, layout.placements().get(id("minecraft:second")).x());
		assertEquals(89, layout.placements().get(id("minecraft:second")).y());
	}

	@Test
	void reducesOversizedSpriteInOriginalLayout() {
		LowAtlasPacker.Layout layout = LowAtlasPacker.preserveLayout(
			List.of(sprite("minecraft:large", 32)), 16, 64, 64
		);

		assertEquals(2, layout.placements().get(id("minecraft:large")).factor());
		assertEquals(0, layout.placements().get(id("minecraft:large")).x());
	}

	private static LowAtlasPacker.Sprite sprite(String identifier, int size) {
		return new LowAtlasPacker.Sprite(id(identifier), 0, 0, size, size, 0, 0);
	}

	private static Identifier id(String value) {
		return Identifier.tryParse(value);
	}
}
