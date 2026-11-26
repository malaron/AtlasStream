package com.malaron.atlasstream.shader;

import java.util.regex.Pattern;

public final class IrisVirtualTileShaderContract {
	private static final Pattern ORIGINAL_UV = Pattern.compile(
		"\\bin\\s+vec2\\s+atlasStream_OriginalUv\\s*;"
	);
	private static final Pattern DIRECT_PBR_SAMPLE = Pattern.compile(
		"\\btexture\\s*\\(\\s*(gtexture|normals|specular)\\s*,\\s*([^,\\n]+)\\s*,\\s*([^\\)\\n]+)\\s*\\)"
	);
	private static final String DECLARATIONS = """

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
""";

	private IrisVirtualTileShaderContract() {
	}

	public static String patchFragment(String source) {
		java.util.regex.Matcher originalUv = ORIGINAL_UV.matcher(source);
		if (!originalUv.find() || source.contains("u_AtlasStreamVirtualPageTable")) {
			return source;
		}
		String patchedSamples = DIRECT_PBR_SAMPLE.matcher(source).replaceAll(match -> {
			String sampler = match.group(1);
			String function = switch (sampler) {
				case "gtexture" -> "atlasStreamSampleVirtualGtexture";
				case "normals" -> "atlasStreamSampleVirtualNormals";
				case "specular" -> "atlasStreamSampleVirtualSpecular";
				default -> throw new IllegalStateException("Unexpected sampler " + sampler);
			};
			return function + "(" + match.group(2).trim() + ", atlasStream_OriginalUv, "
				+ match.group(3).trim() + ")";
		});
		java.util.regex.Matcher patchedOriginalUv = ORIGINAL_UV.matcher(patchedSamples);
		patchedOriginalUv.find();
		return patchedSamples.substring(0, patchedOriginalUv.end())
			+ DECLARATIONS
			+ patchedSamples.substring(patchedOriginalUv.end());
	}
}
