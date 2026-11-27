package com.malaron.atlasstream.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.malaron.atlasstream.atlas.LowAtlasPacker;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.Test;

class LowAtlasUvContractTest {
	@Test
	void mappedUvCanRoundTripToOriginalSpritePixels() {
		LowAtlasPacker.Sprite source = new LowAtlasPacker.Sprite(
			Identifier.withDefaultNamespace("block/test"),
			100, 200, 512, 256, 16, 8
		);
		LowAtlasPacker.Placement placement = new LowAtlasPacker.Placement(
			source, 8, 700, 300, 64, 32, 2, 1
		);
		float[] mapped = LowAtlasUvContract.map(
			placement, 65536, 32768, 4096, 2048, 428.0F / 65536.0F, 344.0F / 32768.0F
		);

		assertEquals(428, LowAtlasUvContract.inverseSpriteX(
			placement, 65536, 4096, mapped[0]
		));
		assertEquals(344, LowAtlasUvContract.inverseSpriteY(
			placement, 32768, 2048, mapped[1]
		));
	}
}
