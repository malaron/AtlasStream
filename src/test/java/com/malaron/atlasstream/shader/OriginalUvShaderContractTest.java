package com.malaron.atlasstream.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OriginalUvShaderContractTest {
	@Test
	void vertexExportsUnmodifiedUvBeforeDisplayRemap() {
		String source = """
			in vec2 iris_UV0;
			void main() {
			    vec2 texCoord = atlasStreamTerrainRemap(iris_UV0);
			}
			""";
		String patched = OriginalUvShaderContract.patchVertex(source);

		assertTrue(patched.contains("out vec2 atlasStream_OriginalUv;"));
		assertTrue(patched.contains("atlasStream_OriginalUv = iris_UV0;"));
		assertTrue(patched.indexOf("atlasStream_OriginalUv = iris_UV0;")
			< patched.indexOf("atlasStreamTerrainRemap(iris_UV0)"));
	}

	@Test
	void fragmentDeclaresMatchingOriginalUvVarying() {
		String source = "in vec2 iris_UV0;\nvoid main() { vec2 uv = iris_UV0; }";
		assertTrue(OriginalUvShaderContract.patchFragment(source)
			.contains("in vec2 atlasStream_OriginalUv;"));
	}

	@Test
	void nativeProgramsWithoutIrisUvRemainUnchanged() {
		String source = "uniform sampler2D Sampler0;\nvoid main() {}";
		assertEquals(source, OriginalUvShaderContract.patchVertex(source));
		assertEquals(source, OriginalUvShaderContract.patchFragment(source));
	}
}
