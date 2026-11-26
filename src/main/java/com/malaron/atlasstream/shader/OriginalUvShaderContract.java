package com.malaron.atlasstream.shader;

import java.util.regex.Pattern;

public final class OriginalUvShaderContract {
	private static final String ORIGINAL_UV_OUT = "atlasStream_OriginalUv";
	private static final Pattern OUT_DECLARATION = Pattern.compile(
		"\\bout\\s+vec2\\s+" + ORIGINAL_UV_OUT + "\\s*;"
	);
	private static final Pattern IN_DECLARATION = Pattern.compile(
		"\\bin\\s+vec2\\s+" + ORIGINAL_UV_OUT + "\\s*;"
	);
	private static final Pattern UV_DECLARATION = Pattern.compile(
		"\\bin\\s+vec2\\s+iris_UV0\\s*;"
	);

	private OriginalUvShaderContract() {
	}

	public static String patchVertex(String source) {
		if (!source.contains("iris_UV0") || OUT_DECLARATION.matcher(source).find()) {
			return source;
		}
		java.util.regex.Matcher uv = UV_DECLARATION.matcher(source);
		if (!uv.find()) {
			return source;
		}
		String declaration = "\nout vec2 " + ORIGINAL_UV_OUT + ";";
		int main = source.indexOf("void main(");
		if (main < 0) {
			return source;
		}
		String body = source.substring(main);
		int brace = body.indexOf('{');
		if (brace < 0) {
			return source;
		}
		int insertAt = main + brace + 1;
		return source.substring(0, uv.end())
			+ declaration
			+ source.substring(uv.end(), insertAt)
			+ "\n    " + ORIGINAL_UV_OUT + " = iris_UV0;"
			+ source.substring(insertAt);
	}

	public static String patchFragment(String source) {
		if (!source.contains("iris_UV0") || IN_DECLARATION.matcher(source).find()) {
			return source;
		}
		java.util.regex.Matcher uv = UV_DECLARATION.matcher(source);
		if (!uv.find()) {
			return source;
		}
		return source.substring(0, uv.end())
			+ "\nin vec2 " + ORIGINAL_UV_OUT + ";"
			+ source.substring(uv.end());
	}

	public static String patchSodiumFragment(String source) {
		if (IN_DECLARATION.matcher(source).find()) {
			return source;
		}
		int version = source.indexOf("#version");
		int insertion = 0;
		if (version >= 0) {
			int newline = source.indexOf('\n', version);
			insertion = newline < 0 ? source.length() : newline + 1;
		}
		return source.substring(0, insertion)
			+ "in vec2 atlasStream_OriginalUv;\n"
			+ source.substring(insertion);
	}

	public static String patchSodiumVertex(String source, String originalTexcoordReturn) {
		String assignment =
			"atlasStream_OriginalUv = vec2(a_TexCoord & TEXTURE_MAX_VALUE) / float(TEXTURE_MAX_COORD);"
				+ " return atlasStreamTerrainRemap(atlasStream_OriginalUv);";
		if (!source.contains(originalTexcoordReturn) || source.contains("atlasStream_OriginalUv = vec2(")) {
			return source;
		}
		return source.replace(originalTexcoordReturn, assignment);
	}
}
