package com.malaron.atlasstream.shader;

public final class VirtualTextureShaderPatcher {
	private static volatile boolean patchApplied;
	private static final String BLOCK_TEXTURE_UNIFORM = "uniform sampler2D u_BlockTex;";
	private static final String NEAREST_SAMPLE =
		"return textureGrad(source, uv, du, dv);";
	private static final String RGSS_SAMPLE =
		"rgssColor += textureLod(source, sampleUV, mipLevelExact);";
	private static final String MARKER = "u_VirtualPageTable";
	private static final String VIRTUAL_TEXTURE_FUNCTIONS = """

uniform sampler2D u_VirtualTileAtlas;
uniform sampler2D u_VirtualPageTable;
uniform sampler2D u_VirtualAtlasMetadata;
uniform sampler2D u_AtlasStreamLowRemap;

vec2 atlasStreamMapLowAtlas(vec2 uv, out vec2 scale) {
    ivec2 remapSize = textureSize(u_AtlasStreamLowRemap, 0);
    scale = vec2(1.0);
    if (remapSize.x <= 3) {
        return uv;
    }
    int headerY = remapSize.y - 1;
    vec4 destinationHeader = texelFetch(u_AtlasStreamLowRemap, ivec2(0, headerY), 0);
    vec4 sourceHeader = texelFetch(u_AtlasStreamLowRemap, ivec2(1, headerY), 0);
    vec4 gridHeader = texelFetch(u_AtlasStreamLowRemap, ivec2(2, headerY), 0);
    vec2 destinationSize = vec2(
        floor(destinationHeader.r * 255.0 + 0.5) + floor(destinationHeader.g * 255.0 + 0.5) * 256.0,
        floor(destinationHeader.b * 255.0 + 0.5) + floor(destinationHeader.a * 255.0 + 0.5) * 256.0
    ) + 1.0;
    if (textureSize(u_BlockTex, 0) != ivec2(destinationSize)) {
        return uv;
    }
    vec2 sourceSize = vec2(
        floor(sourceHeader.r * 255.0 + 0.5) + floor(sourceHeader.g * 255.0 + 0.5) * 256.0,
        floor(sourceHeader.b * 255.0 + 0.5) + floor(sourceHeader.a * 255.0 + 0.5) * 256.0
    ) + 1.0;
    float cell = floor(gridHeader.r * 255.0 + 0.5) + floor(gridHeader.g * 255.0 + 0.5) * 256.0;
    vec2 sourceTexel = uv * sourceSize;
    ivec2 cellCoordinate = clamp(
        ivec2(floor(sourceTexel / cell)),
        ivec2(0),
        ivec2(remapSize.x / 3 - 1, remapSize.y - 2)
    );
    vec4 sourceOrigin = texelFetch(u_AtlasStreamLowRemap, ivec2(cellCoordinate.x * 3, cellCoordinate.y), 0);
    vec4 destinationEntry = texelFetch(u_AtlasStreamLowRemap, ivec2(cellCoordinate.x * 3 + 1, cellCoordinate.y), 0);
    vec4 factorEntry = texelFetch(u_AtlasStreamLowRemap, ivec2(cellCoordinate.x * 3 + 2, cellCoordinate.y), 0);
    int encodedFactor = int(floor(factorEntry.r * 255.0 + 0.5));
    if (encodedFactor == 0) {
        return uv;
    }
    vec2 sourceOriginPixels = vec2(
        floor(sourceOrigin.r * 255.0 + 0.5) + floor(sourceOrigin.g * 255.0 + 0.5) * 256.0,
        floor(sourceOrigin.b * 255.0 + 0.5) + floor(sourceOrigin.a * 255.0 + 0.5) * 256.0
    );
    vec2 destinationOriginPixels = vec2(
        floor(destinationEntry.r * 255.0 + 0.5) + floor(destinationEntry.g * 255.0 + 0.5) * 256.0,
        floor(destinationEntry.b * 255.0 + 0.5) + floor(destinationEntry.a * 255.0 + 0.5) * 256.0
    );
    float factor = exp2(float(encodedFactor - 1));
    scale = sourceSize / (destinationSize * factor);
    return (destinationOriginPixels + (sourceTexel - sourceOriginPixels) / factor) / destinationSize;
}
vec2 atlasStreamMapLowAtlas(vec2 uv) {
    vec2 scale;
    return atlasStreamMapLowAtlas(uv, scale);
}

bool atlasStreamLookupVirtualTile(
    vec2 uv,
    vec2 du,
    vec2 dv,
    out int slot,
    out vec2 localUv,
    out vec2 atlasMipSize,
    out vec2 cacheSize
) {
    vec4 metadata = texelFetch(u_VirtualAtlasMetadata, ivec2(0), 0);
    vec4 mipMetadata = texelFetch(u_VirtualAtlasMetadata, ivec2(1, 0), 0);
    ivec2 atlasSize = ivec2(
        round(metadata.r * 255.0) + round(metadata.g * 255.0) * 256.0 + 1.0,
        round(metadata.b * 255.0) + round(metadata.a * 255.0) * 256.0 + 1.0
    );
    // textureQueryLod clamps to the reduced atlas's mip range and would hide the finest virtual mips.
    vec2 dx = du * vec2(atlasSize);
    vec2 dy = dv * vec2(atlasSize);
    float requestedLod = max(0.5 * log2(max(max(dot(dx, dx), dot(dy, dy)), 1e-8)), 0.0);
    int mipLevel = min(int(floor(requestedLod)), int(round(mipMetadata.r * 255.0)) - 1);
    ivec2 mipSize = max(ivec2(1), atlasSize >> mipLevel);
    ivec2 texel = clamp(ivec2(floor(uv * vec2(mipSize))), ivec2(0), mipSize - 1);
    ivec2 tile = texel / 128;
    int pageRow = 0;
    for (int level = 0; level < mipLevel; level++) {
        int mipHeight = max(1, atlasSize.y >> level);
        pageRow += (mipHeight + 127) / 128;
    }

    ivec2 pageTableSize = textureSize(u_VirtualPageTable, 0);
    ivec2 pageCoordinate = ivec2(tile.x, pageRow + tile.y);
    if (any(lessThan(pageCoordinate, ivec2(0))) || any(greaterThanEqual(pageCoordinate, pageTableSize))) {
        return false;
    }

    vec4 entry = texelFetch(u_VirtualPageTable, pageCoordinate, 0);
    if (entry.a < 0.5) {
        return false;
    }
    ivec3 encodedLayer = ivec3(round(entry.rgb * 255.0));
    slot = encodedLayer.r + encodedLayer.g * 256 + encodedLayer.b * 65536 - 1;
    localUv = (vec2(texel - tile * 128) + fract(uv * vec2(mipSize))) / 128.0;
    atlasMipSize = vec2(mipSize);
    cacheSize = vec2(textureSize(u_VirtualTileAtlas, 0));
    return slot >= 0;
}

vec4 atlasStreamSampleVirtualOrAtlas(vec2 uv, vec2 du, vec2 dv) {
    int slot;
    vec2 localUv;
    vec2 atlasMipSize;
    vec2 cacheSize;
    if (atlasStreamLookupVirtualTile(uv, du, dv, slot, localUv, atlasMipSize, cacheSize)) {
        vec2 tileScale = atlasMipSize / 128.0;
        float filterRadius = max(length(du * tileScale), length(dv * tileScale)) * 0.5 + 1.0 / 128.0;
        // Clamp inside the tile rather than falling back to the low atlas, which showed as a grid across big sprites.
        localUv = clamp(localUv, vec2(filterRadius), vec2(1.0 - filterRadius));
        int cacheGridWidth = int(cacheSize.x) / 128;
        ivec2 slotCoordinate = ivec2(slot % cacheGridWidth, slot / cacheGridWidth);
        vec2 cacheUv = (vec2(slotCoordinate * 128) + localUv * 128.0) / cacheSize;
        return textureGrad(
            u_VirtualTileAtlas,
            cacheUv,
            du * atlasMipSize / cacheSize,
            dv * atlasMipSize / cacheSize
        );
    }
    vec2 lowScale;
    vec2 lowUv = atlasStreamMapLowAtlas(uv, lowScale);
    vec2 lowDu = du * lowScale;
    vec2 lowDv = dv * lowScale;
    return textureGrad(u_BlockTex, lowUv, lowDu, lowDv);
}
""";

	private VirtualTextureShaderPatcher() {
	}

	public static boolean isPatchApplied() {
		return patchApplied;
	}

	public static String patch(String fragmentShader) {
		if (fragmentShader.contains(MARKER)) {
			patchApplied = true;
			return fragmentShader;
		}
		if (!fragmentShader.contains(BLOCK_TEXTURE_UNIFORM)
				|| !fragmentShader.contains(NEAREST_SAMPLE)
				|| !fragmentShader.contains(RGSS_SAMPLE)) {
			return fragmentShader;
		}

		String patched = fragmentShader
			.replace(BLOCK_TEXTURE_UNIFORM, BLOCK_TEXTURE_UNIFORM + VIRTUAL_TEXTURE_FUNCTIONS)
			.replace(NEAREST_SAMPLE, "return atlasStreamSampleVirtualOrAtlas(uv, du, dv);")
			.replace(
				RGSS_SAMPLE,
				"rgssColor += atlasStreamSampleVirtualOrAtlas(sampleUV, dFdx(uv), dFdy(uv));"
			);
		patchApplied = true;
		return patched;
	}

	public static String patchItem(String fragmentShader) {
		return patchItem(fragmentShader, true);
	}

	public static String patchItem(String fragmentShader, boolean streamTiles) {
		String uniform = "uniform sampler2D Sampler0;";
		String sample = "vec4 color = texture(Sampler0, texCoord0);";
		if (fragmentShader.contains(MARKER)) {
			return fragmentShader;
		}
		if (!fragmentShader.contains(uniform) || !fragmentShader.contains(sample)) {
			return fragmentShader;
		}
		String patched = fragmentShader
			.replace(uniform, uniform + VIRTUAL_TEXTURE_FUNCTIONS.replace("u_BlockTex", "Sampler0"))
			.replace(sample, streamTiles
				? "vec4 color = atlasStreamSampleVirtualOrAtlas(texCoord0, dFdx(texCoord0), dFdy(texCoord0));"
				: "vec4 color = texture(Sampler0, atlasStreamMapLowAtlas(texCoord0));");
		return patched;
	}
}
