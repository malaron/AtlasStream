package com.malaron.atlasstream.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IrisVirtualTileShaderContractTest {
	@Test
	void addsPbrVirtualSamplersToOriginalUvShaders() {
		String fragment = """
			#version 330
			layout(location = 0) out vec4 sceneAlbedo;
			in vec2 iris_UV0;
			in vec2 atlasStream_OriginalUv;
			void main() {
			    vec4 color = texture(gtexture, iris_UV0, MipBias);
			    vec4 normal = texture(normals, iris_UV0, MipBias);
			    vec4 properties = texture(specular, iris_UV0, MipBias);
			    vec4 parallaxColor = textureParallax(gtexture, parallaxCoord.xy, dCoord);
			    vec4 parallaxNormal = textureParallax(normals, parallaxCoord.xy, dCoord);
			    vec4 parallaxSpecular = textureParallax(specular, parallaxCoord.xy, dCoord);
			}
			""";

		String patched = IrisVirtualTileShaderContract.patchFragment(fragment);

		assertTrue(patched.contains("uniform sampler2D u_AtlasStreamVirtualAlbedo;"));
		assertTrue(patched.contains("uniform sampler2D u_AtlasStreamVirtualNormal;"));
		assertTrue(patched.contains("uniform sampler2D u_AtlasStreamVirtualSpecular;"));
		assertTrue(patched.contains("uniform sampler2D u_AtlasStreamVirtualPageTable;"));
		assertTrue(patched.contains("bool atlasStreamVirtualLookup("));
		assertTrue(patched.contains(
			"atlasStreamSampleVirtualGtexture(iris_UV0, atlasStream_OriginalUv, MipBias)"
		));
		assertTrue(patched.contains(
			"atlasStreamSampleVirtualNormals(iris_UV0, atlasStream_OriginalUv, MipBias)"
		));
		assertTrue(patched.contains(
			"atlasStreamSampleVirtualSpecular(iris_UV0, atlasStream_OriginalUv, MipBias)"
		));
		assertTrue(patched.contains(
			"atlasStreamSampleParallaxGtexture(parallaxCoord.xy, uv[0], atlasStream_OriginalUv, dCoord)"
		));
		assertTrue(patched.contains(
			"atlasStreamSampleParallaxNormals(parallaxCoord.xy, uv[0], atlasStream_OriginalUv, dCoord)"
		));
		assertTrue(patched.contains(
			"atlasStreamSampleParallaxSpecular(parallaxCoord.xy, uv[0], atlasStream_OriginalUv, dCoord)"
		));
		assertEquals(patched, IrisVirtualTileShaderContract.patchFragment(patched));
	}

	@Test
	void leavesNativeShadersUnchanged() {
		String fragment = "in vec2 iris_UV0;\nvoid main() {}\n";

		assertEquals(fragment, IrisVirtualTileShaderContract.patchFragment(fragment));
	}

	@Test
	void leavesShadowProgramsUnchanged() {
		String shadow = """
			in vec2 iris_UV0;
			in vec2 atlasStream_OriginalUv;
			vec4 color = textureParallax(gtexture, parallaxCoord.xy, dCoord);
			""";

		assertEquals(shadow, IrisVirtualTileShaderContract.patchFragment(shadow));
	}
}
