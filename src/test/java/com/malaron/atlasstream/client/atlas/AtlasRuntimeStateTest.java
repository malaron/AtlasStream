package com.malaron.atlasstream.client.atlas;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasRuntimeStateTest {
	@Test
	void keepsEntityAtlasesOutOfBlockNearDemand() {
		assertTrue(AtlasRuntimeState.isTerrainNearSprite(
			TextureAtlas.LOCATION_BLOCKS,
			Identifier.withDefaultNamespace("block/stone")
		));
		assertFalse(AtlasRuntimeState.isTerrainNearSprite(
			TextureAtlas.LOCATION_BLOCKS,
			Identifier.withDefaultNamespace("entity/shulker/shulker_white")
		));
	}
}
