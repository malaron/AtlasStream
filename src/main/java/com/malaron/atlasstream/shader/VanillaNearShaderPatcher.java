package com.malaron.atlasstream.shader;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VanillaNearShaderPatcher {
	private static final Pattern MID_DECLARATION = Pattern.compile(
		"\\b(?:in|attribute)\\s+(?:(?:lowp|mediump|highp)\\s+)?(vec[24])\\s+mc_midTexCoord\\s*;"
	);
	private static final Pattern MID_REFERENCE = Pattern.compile("\\bmc_midTexCoord\\b");
	private static final Pattern UV_REFERENCE = Pattern.compile("\\biris_UV0\\b");
	private static final Pattern UV_DECLARATION = Pattern.compile("\\bin\\s+vec2\\s+iris_UV0\\s*;");

	private VanillaNearShaderPatcher() {
	}

	public static String patchReferences(String source) {
		Matcher declaration = MID_DECLARATION.matcher(source);
		if (!declaration.find()) {
			return source;
		}
		String type = declaration.group(1);
		String before = source.substring(0, declaration.start());
		String after = source.substring(declaration.end());
		String midpoint = "atlasStreamAtlasRemap(mc_midTexCoord.xy, mc_midTexCoord.xy)";
		String replacement = type.equals("vec4")
			? "vec4(" + midpoint + ", mc_midTexCoord.zw)"
			: midpoint;
		before = MID_REFERENCE.matcher(before).replaceAll(Matcher.quoteReplacement(replacement));
		after = MID_REFERENCE.matcher(after).replaceAll(Matcher.quoteReplacement(replacement));
		String patched = before + declaration.group() + after;
		return replaceUvReferences(patched, "atlasStreamAtlasRemap(iris_UV0, mc_midTexCoord.xy)");
	}

	private static String replaceUvReferences(String source, String replacement) {
		Matcher declaration = UV_DECLARATION.matcher(source);
		if (!declaration.find()) {
			return source;
		}
		return UV_REFERENCE.matcher(source.substring(0, declaration.start())).replaceAll(replacement)
			+ declaration.group()
			+ UV_REFERENCE.matcher(source.substring(declaration.end())).replaceAll(replacement);
	}
}
