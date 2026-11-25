package com.malaron.atlasstream.client.atlas;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualAtlasPolicyTest {
	@Test
	void limitsOverflowDownscalingToTheBlockAtlas() {
		assertTrue(VirtualAtlasPolicy.isBlockAtlas(TextureAtlas.LOCATION_BLOCKS));
		assertFalse(VirtualAtlasPolicy.isBlockAtlas(
			Identifier.withDefaultNamespace("textures/atlas/chest")
		));
		assertFalse(VirtualAtlasPolicy.isBlockAtlas(
			Identifier.withDefaultNamespace("textures/atlas/shulker_boxes")
		));
	}
}
