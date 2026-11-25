package com.malaron.atlasstream.client.atlas;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasRuntimeStateTest {
	@Test
	void excludesEntitySpritesFromTerrainSectionResidency() {
		assertTrue(AtlasRuntimeState.isTerrainSectionSprite(
			net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS,
			Identifier.withDefaultNamespace("block/stone")
		));
		assertFalse(AtlasRuntimeState.isTerrainSectionSprite(
			net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS,
			Identifier.withDefaultNamespace("entity/chest/normal")
		));
		assertFalse(AtlasRuntimeState.isTerrainSectionSprite(
			Identifier.withDefaultNamespace("textures/atlas/chest"),
			Identifier.withDefaultNamespace("entity/chest/normal")
		));
	}
}
