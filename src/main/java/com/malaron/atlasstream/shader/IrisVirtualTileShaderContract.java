package com.malaron.atlasstream.shader;

import java.util.regex.Pattern;

public final class IrisVirtualTileShaderContract {
	private static final Pattern ORIGINAL_UV = Pattern.compile(
		"\\bin\\s+vec2\\s+atlasStream_OriginalUv\\s*;"
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
""";

	private IrisVirtualTileShaderContract() {
	}

	public static String patchFragment(String source) {
		java.util.regex.Matcher originalUv = ORIGINAL_UV.matcher(source);
		if (!originalUv.find() || source.contains("u_AtlasStreamVirtualPageTable")) {
			return source;
		}
		return source.substring(0, originalUv.end())
			+ DECLARATIONS
			+ source.substring(originalUv.end());
	}
}
