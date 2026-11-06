package com.malaron.loadsoflods.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualTextureShaderPatcherTest {
	private static final String SODIUM_FRAGMENT = """
uniform sampler2D u_BlockTex;
vec4 sampleNearest(sampler2D source, vec2 uv, vec2 du, vec2 dv) {
    return textureGrad(source, uv, du, dv);
}
vec4 sampleRGSS(sampler2D source, vec2 uv) {
    rgssColor += textureLod(source, sampleUV, mipLevelExact);
}
void main() {
    vec4 color = u_UseRGSS ? sampleRGSS(u_BlockTex, v_TexCoord, u_TexelSize) : sampleNearest(u_BlockTex, v_TexCoord, u_TexelSize);
}
""";

	@Test
	void patchesKnownSodiumSamplingSitesWithVirtualTextureFallback() {
		String patched = VirtualTextureShaderPatcher.patch(SODIUM_FRAGMENT);

		assertNotEquals(SODIUM_FRAGMENT, patched);
		assertTrue(patched.contains("uniform sampler2D u_VirtualTileAtlas;"));
		assertFalse(patched.contains("sampler2DArray"));
		assertTrue(patched.contains("uniform sampler2D u_VirtualPageTable;"));
		assertTrue(patched.contains("uniform sampler2D u_VirtualAtlasMetadata;"));
		assertTrue(patched.contains("textureGrad(u_BlockTex, uv, du, dv)"));
		assertTrue(patched.contains("loadsOfLodsSampleVirtualOrAtlas(uv, du, dv)"));
		assertTrue(patched.contains("loadsOfLodsSampleVirtualOrAtlas(sampleUV, dFdx(uv), dFdy(uv))"));
		assertTrue(patched.contains("atlasSize.y >> level"));
		assertTrue(patched.contains("clamp(localUv, vec2(filterRadius)"));
		assertTrue(patched.contains("slotCoordinate * 128"));
	}

	@Test
	void doesNotPatchUnknownShaderVersionsAndIsIdempotent() {
		String unknownShader = "void main() { color = texture(u_BlockTex, uv); }";
		assertEquals(unknownShader, VirtualTextureShaderPatcher.patch(unknownShader));

		String patched = VirtualTextureShaderPatcher.patch(SODIUM_FRAGMENT);
		assertEquals(patched, VirtualTextureShaderPatcher.patch(patched));
	}

	@Test
	void patchesItemSamplingWithReducedAtlasFallback() {
		String source = "uniform sampler2D Sampler0;\nvoid main() { vec4 color = texture(Sampler0, texCoord0); }";
		String patched = VirtualTextureShaderPatcher.patchItem(source);
		assertNotEquals(source, patched);
		assertTrue(patched.contains("textureGrad(Sampler0, uv, du, dv)"));
		assertTrue(patched.contains("loadsOfLodsSampleVirtualOrAtlas(texCoord0, dFdx(texCoord0), dFdy(texCoord0))"));
		assertEquals(patched, VirtualTextureShaderPatcher.patchItem(patched));
		assertEquals("void main() {}", VirtualTextureShaderPatcher.patchItem("void main() {}"));
	}

	@Test
	void patchesMinecraftItemShader() throws IOException {
		try (var stream = getClass().getResourceAsStream("/assets/minecraft/shaders/core/item.fsh")) {
			assertNotNull(stream);
			String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			String patched = VirtualTextureShaderPatcher.patchItem(source);
			assertNotEquals(source, patched);
			assertTrue(patched.contains("loadsOfLodsSampleVirtualOrAtlas(texCoord0, dFdx(texCoord0), dFdy(texCoord0))"));
			assertTrue(patched.contains("texture(GlintSampler, texCoordGlint)"));
			assertTrue(patched.contains("executeAlphaOnlyPhase(gl_FragCoord.z, color.a)"));
		}
	}
}
