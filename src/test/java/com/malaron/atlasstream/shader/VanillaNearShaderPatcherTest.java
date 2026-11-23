package com.malaron.atlasstream.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VanillaNearShaderPatcherTest {
	@Test
	void remapsUvAndVec4MidpointWithoutChangingDeclarations() {
		String source = """
			in vec2 iris_UV0;
			in vec4 mc_midTexCoord;
			void main() {
			    vec2 uv = iris_UV0;
			    vec2 mid = (matrix * mc_midTexCoord).xy;
			}
			""";
		String expected = """
			in vec2 iris_UV0;
			in vec4 mc_midTexCoord;
			void main() {
			    vec2 uv = atlasStreamAtlasRemap(iris_UV0, mc_midTexCoord.xy);
			    vec2 mid = (matrix * vec4(atlasStreamAtlasRemap(mc_midTexCoord.xy, mc_midTexCoord.xy), mc_midTexCoord.zw)).xy;
			}
			""";
		assertEquals(expected, VanillaNearShaderPatcher.patchReferences(source));
	}

	@Test
	void supportsMidpointDeclaredBeforeUvAndVec2Attribute() {
		String source = "in vec2 mc_midTexCoord;\nin vec2 iris_UV0;\nvec2 p = mc_midTexCoord + iris_UV0;";
		String expected = "in vec2 mc_midTexCoord;\nin vec2 iris_UV0;\n"
			+ "vec2 p = atlasStreamAtlasRemap(mc_midTexCoord.xy, mc_midTexCoord.xy)"
			+ " + atlasStreamAtlasRemap(iris_UV0, mc_midTexCoord.xy);";
		assertEquals(expected, VanillaNearShaderPatcher.patchReferences(source));
	}

	@Test
	void preservesExistingUvOnlyPathWhenMidpointAbsent() {
		String source = "in vec2 iris_UV0;\nvec2 p = iris_UV0;";
		assertEquals("in vec2 iris_UV0;\nvec2 p = atlasStreamAtlasRemap(iris_UV0);",
			VanillaNearShaderPatcher.patchReferences(source));
	}
}
