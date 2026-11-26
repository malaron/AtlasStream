package com.malaron.atlasstream.shader;

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
    vec2 displayUv,
    out vec2 derivativeScale
) {
    vec4 metadata = texelFetch(u_AtlasStreamVirtualMetadata, ivec2(0), 0);
    vec2 originalSize = vec2(
        floor(metadata.r * 255.0 + 0.5) + floor(metadata.g * 255.0 + 0.5) * 256.0 + 1.0,
        floor(metadata.b * 255.0 + 0.5) + floor(metadata.a * 255.0 + 0.5) * 256.0 + 1.0
    );
    derivativeScale = vec2(textureSize(gtexture, 0)) / originalSize;

    ivec2 nearRemapSize = textureSize(u_AtlasStreamNearRemap, 0);
    if (nearRemapSize.x > 1) {
        int rows = nearRemapSize.y - 1;
        vec4 header = texelFetch(u_AtlasStreamNearRemap, ivec2(0, rows), 0);
        vec2 compactSize = vec2(
            floor(header.r * 255.0 + 0.5) + floor(header.g * 255.0 + 0.5) * 256.0,
            floor(header.b * 255.0 + 0.5) + floor(header.a * 255.0 + 0.5) * 256.0
        ) + 1.0;
        if (textureSize(gtexture, 0) == ivec2(compactSize)) {
            vec4 gridHeader = texelFetch(u_AtlasStreamNearRemap, ivec2(1, rows), 0);
            float cell = floor(gridHeader.r * 255.0 + 0.5);
            ivec2 coordinate = clamp(
                ivec2(floor(baseOriginalUv * vec2(nearRemapSize.x, rows) )),
                ivec2(0),
                ivec2(nearRemapSize.x - 1, rows - 1)
            );
            vec4 entry = texelFetch(u_AtlasStreamNearRemap, coordinate, 0);
            if (entry.a >= 0.5) {
                vec2 delta = vec2(
                    floor(entry.r * 255.0 + 0.5)
                        + floor(entry.g * 255.0 + 0.5) * 256.0 - 32768.0,
                    floor(entry.b * 255.0 + 0.5)
                        + mod(floor(entry.a * 255.0 + 0.5), 128.0) * 256.0 - 4096.0
                );
                vec2 original = (
                    displayUv * compactSize - delta * cell
                ) / (vec2(nearRemapSize.x, rows) * cell);
                derivativeScale = compactSize / (vec2(nearRemapSize.x, rows) * cell);
                return original;
            }
        }
    }

    ivec2 remapSize = textureSize(u_AtlasStreamLowRemap, 0);
    if (remapSize.x > 3) {
        int headerY = remapSize.y - 1;
        vec4 destinationHeader = texelFetch(u_AtlasStreamLowRemap, ivec2(0, headerY), 0);
        vec2 destinationSize = vec2(
            floor(destinationHeader.r * 255.0 + 0.5) + floor(destinationHeader.g * 255.0 + 0.5) * 256.0 + 1.0,
            floor(destinationHeader.b * 255.0 + 0.5) + floor(destinationHeader.a * 255.0 + 0.5) * 256.0 + 1.0
        );
        if (textureSize(gtexture, 0) == destinationSize) {
            vec4 sourceHeader = texelFetch(u_AtlasStreamLowRemap, ivec2(1, headerY), 0);
            vec4 gridHeader = texelFetch(u_AtlasStreamLowRemap, ivec2(2, headerY), 0);
            vec2 sourceSize = vec2(
                floor(sourceHeader.r * 255.0 + 0.5) + floor(sourceHeader.g * 255.0 + 0.5) * 256.0,
                floor(sourceHeader.b * 255.0 + 0.5) + floor(sourceHeader.a * 255.0 + 0.5) * 256.0
            ) + 1.0;
            float cell = floor(gridHeader.r * 255.0 + 0.5)
                + floor(gridHeader.g * 255.0 + 0.5) * 256.0;
            ivec2 coordinate = clamp(
                ivec2(floor(baseOriginalUv * sourceSize / cell)),
                ivec2(0),
                ivec2(remapSize.x / 3 - 1, remapSize.y - 2)
            );
            vec4 factorEntry = texelFetch(
                u_AtlasStreamLowRemap, ivec2(coordinate.x * 3 + 2, coordinate.y), 0
            );
            int encodedFactor = int(floor(factorEntry.r * 255.0 + 0.5));
            if (encodedFactor > 0) {
                vec4 sourceOriginEntry = texelFetch(
                    u_AtlasStreamLowRemap, ivec2(coordinate.x * 3, coordinate.y), 0
                );
                vec4 destinationEntry = texelFetch(
                    u_AtlasStreamLowRemap, ivec2(coordinate.x * 3 + 1, coordinate.y), 0
                );
                vec2 sourceOrigin = vec2(
                    floor(sourceOriginEntry.r * 255.0 + 0.5)
                        + floor(sourceOriginEntry.g * 255.0 + 0.5) * 256.0,
                    floor(sourceOriginEntry.b * 255.0 + 0.5)
                        + floor(sourceOriginEntry.a * 255.0 + 0.5) * 256.0
                );
                vec2 destinationOrigin = vec2(
                    floor(destinationEntry.r * 255.0 + 0.5)
                        + floor(destinationEntry.g * 255.0 + 0.5) * 256.0,
                    floor(destinationEntry.b * 255.0 + 0.5)
                        + floor(destinationEntry.a * 255.0 + 0.5) * 256.0
                );
                float factor = exp2(float(encodedFactor - 1));
                derivativeScale = destinationSize * factor / sourceSize;
                return (
                    sourceOrigin + (displayUv * destinationSize - destinationOrigin) * factor
                ) / sourceSize;
            }
        }
    }
    return baseOriginalUv;
}

vec4 atlasStreamSampleParallax(
    sampler2D fallbackTexture,
    sampler2D cacheTexture,
    vec2 displayUv,
    vec2 baseDisplayUv,
    vec2 originalUv,
    mat2 derivatives
) {
    vec2 derivativeScale;
    vec2 parallaxOriginalUv = atlasStreamOriginalUvFromDisplay(
        originalUv, displayUv, derivativeScale
    );
    vec2 cacheUv = atlasStreamVirtualCacheUv(parallaxOriginalUv, cacheTexture);
    if (cacheUv.x < 0.0) {
        return textureGrad(fallbackTexture, displayUv, derivatives[0], derivatives[1]);
    }
    vec4 metadata = texelFetch(u_AtlasStreamVirtualMetadata, ivec2(0), 0);
    vec2 originalSize = vec2(
        floor(metadata.r * 255.0 + 0.5) + floor(metadata.g * 255.0 + 0.5) * 256.0 + 1.0,
        floor(metadata.b * 255.0 + 0.5) + floor(metadata.a * 255.0 + 0.5) * 256.0 + 1.0
    );
    derivativeScale *= originalSize / vec2(textureSize(cacheTexture, 0));
    return textureGrad(
        cacheTexture,
        cacheUv,
        derivatives[0] * derivativeScale,
        derivatives[1] * derivativeScale
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
		return patchedSamples.substring(0, main)
			+ samplerDeclarations(patchedSamples)
			+ HELPERS
			+ patchedSamples.substring(main);
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
