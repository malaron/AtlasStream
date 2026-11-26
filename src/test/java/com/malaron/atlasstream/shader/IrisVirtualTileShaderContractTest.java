package com.malaron.atlasstream.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IrisVirtualTileShaderContractTest {
	@Test
	void addsPbrVirtualSamplersToOriginalUvShaders() {
		String fragment = """
			#version 330
			in vec2 iris_UV0;
			in vec2 atlasStream_OriginalUv;
			void main() {
			}
			""";

		String patched = IrisVirtualTileShaderContract.patchFragment(fragment);

		assertTrue(patched.contains("uniform sampler2D u_AtlasStreamVirtualAlbedo;"));
		assertTrue(patched.contains("uniform sampler2D u_AtlasStreamVirtualNormal;"));
		assertTrue(patched.contains("uniform sampler2D u_AtlasStreamVirtualSpecular;"));
		assertTrue(patched.contains("uniform sampler2D u_AtlasStreamVirtualPageTable;"));
		assertTrue(patched.contains("bool atlasStreamVirtualLookup("));
		assertEquals(patched, IrisVirtualTileShaderContract.patchFragment(patched));
	}

	@Test
	void leavesNativeShadersUnchanged() {
		String fragment = "in vec2 iris_UV0;\nvoid main() {}\n";

		assertEquals(fragment, IrisVirtualTileShaderContract.patchFragment(fragment));
	}
}
