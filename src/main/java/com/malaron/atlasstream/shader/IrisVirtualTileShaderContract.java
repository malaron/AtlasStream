package com.malaron.atlasstream.shader;

import java.util.List;
import java.util.regex.Pattern;

public final class IrisVirtualTileShaderContract {
	private static final Pattern ORIGINAL_UV = Pattern.compile(
		"\\bin\\s+vec2\\s+atlasStream_OriginalUv\\s*;"
	);
	private static final Pattern DIRECT_PBR_SAMPLE = Pattern.compile(
		"\\btexture\\s*\\(\\s*(gtexture|normals|specular)\\s*,\\s*([^,\\n]+)\\s*,\\s*([^\\)\\n]+)\\s*\\)"
	);
	private static final Pattern PARALLAX_PBR_SAMPLE = Pattern.compile(
		"\\btextureParallax\\s*\\(\\s*(gtexture|normals|specular)\\s*,\\s*([^,\\n]+)\\s*,\\s*([^\\)\\n]+)\\s*\\)"
	);
	private static final String HELPERS = """

uniform sampler2D u_AtlasStreamVirtualAlbedo;
uniform sampler2D u_AtlasStreamVirtualNormal;
uniform sampler2D u_AtlasStreamVirtualSpecular;
uniform sampler2D u_AtlasStreamVirtualPageTable;
uniform sampler2D u_AtlasStreamVirtualMetadata;

bool atlasStreamVirtualLookup(
    vec2 originalUv,
    out int slot,
    out vec2 localUv
) {
    vec4 metadata = texelFetch(u_AtlasStreamVirtualMetadata, ivec2(0), 0);
    ivec2 atlasSize = ivec2(
        floor(metadata.r * 255.0 + 0.5) + floor(metadata.g * 255.0 + 0.5) * 256.0 + 1.0,
        floor(metadata.b * 255.0 + 0.5) + floor(metadata.a * 255.0 + 0.5) * 256.0 + 1.0
    );
    ivec2 tile = ivec2(originalUv * vec2(atlasSize)) / 128;
    tile = clamp(tile, ivec2(0), ivec2(atlasSize + 127) / 128 - 1);
    vec4 entry = texelFetch(u_AtlasStreamVirtualPageTable, tile, 0);
    if (entry.a < 0.5) {
        return false;
    }
    ivec3 encoded = ivec3(floor(entry.rgb * 255.0 + 0.5));
    slot = encoded.r + encoded.g * 256 + encoded.b * 65536 - 1;
    localUv = (originalUv * vec2(atlasSize) - vec2(tile * 128)) / 128.0;
    return slot >= 0;
}

vec2 atlasStreamVirtualCacheUv(
    vec2 originalUv,
    sampler2D cacheTexture
) {
    int slot;
    vec2 localUv;
    if (!atlasStreamVirtualLookup(originalUv, slot, localUv)) {
        return vec2(-1.0);
    }
    ivec2 cacheSize = textureSize(cacheTexture, 0);
    int gridWidth = max(1, cacheSize.x / 128);
    ivec2 slotCoordinate = ivec2(slot % gridWidth, slot / gridWidth);
    localUv = clamp(localUv, vec2(0.5 / 128.0), vec2(1.0 - 0.5 / 128.0));
    return (vec2(slotCoordinate * 128) + localUv * 128.0) / vec2(cacheSize);
}

vec4 atlasStreamSampleVirtualGtexture(vec2 displayUv, vec2 originalUv, float bias) {
    vec2 cacheUv = atlasStreamVirtualCacheUv(originalUv, u_AtlasStreamVirtualAlbedo);
    return cacheUv.x < 0.0
        ? texture(gtexture, displayUv, bias)
        : texture(u_AtlasStreamVirtualAlbedo, cacheUv, bias);
}

vec4 atlasStreamSampleVirtualNormals(vec2 displayUv, vec2 originalUv, float bias) {
    vec2 cacheUv = atlasStreamVirtualCacheUv(originalUv, u_AtlasStreamVirtualNormal);
    return cacheUv.x < 0.0
        ? texture(normals, displayUv, bias)
        : texture(u_AtlasStreamVirtualNormal, cacheUv, bias);
}

vec4 atlasStreamSampleVirtualSpecular(vec2 displayUv, vec2 originalUv, float bias) {
    vec2 cacheUv = atlasStreamVirtualCacheUv(originalUv, u_AtlasStreamVirtualSpecular);
    return cacheUv.x < 0.0
        ? texture(specular, displayUv, bias)
        : texture(u_AtlasStreamVirtualSpecular, cacheUv, bias);
}

vec2 atlasStreamOriginalUvFromDisplay(
    vec2 baseOriginalUv,
    vec2 baseDisplayUv,
    vec2 displayUv
) {
    mat2 displayJacobian = mat2(dFdx(baseDisplayUv), dFdy(baseDisplayUv));
    mat2 originalJacobian = mat2(dFdx(baseOriginalUv), dFdy(baseOriginalUv));
    if (abs(determinant(displayJacobian)) < 1e-12) {
        return baseOriginalUv;
    }
    return baseOriginalUv
        + originalJacobian * inverse(displayJacobian) * (displayUv - baseDisplayUv);
}

vec4 atlasStreamSampleParallax(
    sampler2D fallbackTexture,
    sampler2D cacheTexture,
    vec2 displayUv,
    vec2 baseDisplayUv,
    vec2 originalUv,
    mat2 derivatives
) {
    vec2 parallaxOriginalUv = atlasStreamOriginalUvFromDisplay(
        originalUv, baseDisplayUv, displayUv
    );
    vec2 cacheUv = atlasStreamVirtualCacheUv(parallaxOriginalUv, cacheTexture);
    if (cacheUv.x < 0.0) {
        return textureGrad(fallbackTexture, displayUv, derivatives[0], derivatives[1]);
    }
    return textureGrad(
        cacheTexture,
        cacheUv,
        dFdx(cacheUv),
        dFdy(cacheUv)
    );
}

vec4 atlasStreamSampleParallaxGtexture(vec2 uv, vec2 baseUv, vec2 originalUv, mat2 derivatives) {
    return atlasStreamSampleParallax(
        gtexture, u_AtlasStreamVirtualAlbedo, uv, baseUv, originalUv, derivatives
    );
}

vec4 atlasStreamSampleParallaxNormals(vec2 uv, vec2 baseUv, vec2 originalUv, mat2 derivatives) {
    return atlasStreamSampleParallax(
        normals, u_AtlasStreamVirtualNormal, uv, baseUv, originalUv, derivatives
    );
}

vec4 atlasStreamSampleParallaxSpecular(vec2 uv, vec2 baseUv, vec2 originalUv, mat2 derivatives) {
    return atlasStreamSampleParallax(
        specular, u_AtlasStreamVirtualSpecular, uv, baseUv, originalUv, derivatives
    );
}
""";

	private IrisVirtualTileShaderContract() {
	}

	public static int directSampleCount(String source) {
		return (int) DIRECT_PBR_SAMPLE.matcher(source).results().count();
	}

	public static int parallaxSampleCount(String source) {
		return (int) PARALLAX_PBR_SAMPLE.matcher(source).results().count();
	}

	public static String patchFragment(String source) {
		return patchFragment(source, true, true, true);
	}

	public static String patchSodiumAlbedoFragment(String source) {
		return patchSodiumAlbedoFragment(source, true);
	}

	public static String patchSodiumPbrFragment(String source) {
		return patchFragment(
			OriginalUvShaderContract.patchSodiumFragment(source),
			true,
			true,
			true,
			true
		);
	}

	public static String patchSodiumAlbedoFragment(String source, boolean parallax) {
		return patchFragment(
			OriginalUvShaderContract.patchSodiumFragment(source),
			true,
			false,
			false,
			parallax
		);
	}

	private static String patchFragment(String source, boolean albedo, boolean normal, boolean specular) {
		return patchFragment(source, albedo, normal, specular, true);
	}

	private static String patchFragment(
		String source,
		boolean albedo,
		boolean normal,
		boolean specular,
		boolean parallax
	) {
		java.util.regex.Matcher originalUv = ORIGINAL_UV.matcher(source);
		if (!originalUv.find() || source.contains("u_AtlasStreamVirtualPageTable")
				|| !source.contains("sceneAlbedo")) {
			return source;
		}
		String patchedSamples = PARALLAX_PBR_SAMPLE.matcher(source).replaceAll(match -> {
			String sampler = match.group(1);
			if (!parallax || !enabledSampler(sampler, albedo, normal, specular)) {
				return match.group();
			}
			String function = switch (sampler) {
				case "gtexture" -> "atlasStreamSampleParallaxGtexture";
				case "normals" -> "atlasStreamSampleParallaxNormals";
				case "specular" -> "atlasStreamSampleParallaxSpecular";
				default -> throw new IllegalStateException("Unexpected sampler " + sampler);
			};
			return function + "(" + match.group(2).trim() + ", uv[0], atlasStream_OriginalUv, "
				+ match.group(3).trim() + ")";
		});
		patchedSamples = DIRECT_PBR_SAMPLE.matcher(patchedSamples).replaceAll(match -> {
			String sampler = match.group(1);
			if (!enabledSampler(sampler, albedo, normal, specular)) {
				return match.group();
			}
			String function = switch (sampler) {
				case "gtexture" -> "atlasStreamSampleVirtualGtexture";
				case "normals" -> "atlasStreamSampleVirtualNormals";
				case "specular" -> "atlasStreamSampleVirtualSpecular";
				default -> throw new IllegalStateException("Unexpected sampler " + sampler);
			};
			return function + "(" + match.group(2).trim() + ", atlasStream_OriginalUv, "
				+ match.group(3).trim() + ")";
		});
		int main = patchedSamples.indexOf("void main(");
		if (main < 0) {
			return source;
		}
		int firstVirtualCall = firstVirtualCall(patchedSamples);
		int insertion = firstVirtualCall >= 0 ? enclosingFunctionStart(patchedSamples, firstVirtualCall) : main;
		if (insertion < 0) {
			return source;
		}
		return patchedSamples.substring(0, insertion)
			+ samplerDeclarations(patchedSamples)
			+ HELPERS
			+ patchedSamples.substring(insertion);
	}

	private static int firstVirtualCall(String source) {
		int first = Integer.MAX_VALUE;
		for (String prefix : List.of(
				"atlasStreamSampleVirtualGtexture(",
				"atlasStreamSampleVirtualNormals(",
				"atlasStreamSampleVirtualSpecular(",
				"atlasStreamSampleParallaxGtexture(",
				"atlasStreamSampleParallaxNormals(",
				"atlasStreamSampleParallaxSpecular(")) {
			int index = source.indexOf(prefix);
			if (index >= 0) {
				first = Math.min(first, index);
			}
		}
		return first == Integer.MAX_VALUE ? -1 : first;
	}

	private static int enclosingFunctionStart(String source, int position) {
		java.util.regex.Matcher function = Pattern.compile(
			"(?m)^\\s*(?:[A-Za-z_]\\w*\\s+)+[A-Za-z_]\\w*\\s*\\([^;{}]*\\)\\s*\\{"
		).matcher(source);
		int start = -1;
		while (function.find() && function.start() < position) {
			start = function.start();
		}
		return start;
	}

	private static boolean enabledSampler(
		String sampler,
		boolean albedo,
		boolean normal,
		boolean specular
	) {
		return switch (sampler) {
			case "gtexture" -> albedo;
			case "normals" -> normal;
			case "specular" -> specular;
			default -> false;
		};
	}

	public static Analysis analyzeSodiumFragment(String source) {
		String withOriginalUv = OriginalUvShaderContract.patchSodiumFragment(source);
		String candidate = patchFragment(withOriginalUv);
		int helper = candidate.indexOf("bool atlasStreamVirtualLookup(");
		int main = candidate.indexOf("void main(");
		return new Analysis(
			withOriginalUv.contains("in vec2 atlasStream_OriginalUv;"),
			directSampleCount(source),
			directSampleCount(mainBody(candidate)),
			parallaxSampleCount(source),
			parallaxSampleCount(mainBody(candidate)),
			candidate.contains("uniform sampler2D u_AtlasStreamVirtualPageTable;"),
			helper >= 0 && helper < main,
			requiredSamplersDeclaredBeforeHelpers(candidate, helper)
		);
	}

	private static String mainBody(String source) {
		int main = source.indexOf("void main(");
		return main < 0 ? source : source.substring(main);
	}

	private static String samplerDeclarations(String source) {
		StringBuilder declarations = new StringBuilder();
		declareIfMissing(declarations, source, "gtexture");
		declareIfMissing(declarations, source, "normals");
		declareIfMissing(declarations, source, "specular");
		declareIfMissing(declarations, source, "u_AtlasStreamNearRemap");
		declareIfMissing(declarations, source, "u_AtlasStreamLowRemap");
		return declarations.toString();
	}

	private static void declareIfMissing(StringBuilder declarations, String source, String sampler) {
		if (!Pattern.compile(
			"\\buniform\\s+(?:\\w+\\s+)*sampler2D\\s+" + Pattern.quote(sampler) + "\\s*;"
		).matcher(source).find()) {
			declarations.append("uniform sampler2D ").append(sampler).append(";\n");
		}
	}

	private static boolean requiredSamplersDeclaredBeforeHelpers(String source, int helper) {
		if (helper < 0) {
			return false;
		}
		String head = source.substring(0, helper);
		return samplerDeclared(head, "gtexture")
			&& samplerDeclared(head, "normals")
			&& samplerDeclared(head, "specular")
			&& samplerDeclared(head, "u_AtlasStreamNearRemap")
			&& samplerDeclared(head, "u_AtlasStreamLowRemap");
	}

	private static boolean samplerDeclared(String source, String sampler) {
		return Pattern.compile(
			"\\buniform\\s+(?:\\w+\\s+)*sampler2D\\s+" + Pattern.quote(sampler) + "\\s*;"
		).matcher(source).find();
	}

	public record Analysis(
		boolean originalUvInput,
		int originalDirectSamples,
		int candidateDirectSamples,
		int originalParallaxSamples,
		int candidateParallaxSamples,
		boolean virtualSamplersDeclared,
		boolean helpersBeforeMain,
		boolean requiredSamplersBeforeHelpers
	) {
	}
}
