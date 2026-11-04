package com.malaron.loadsoflods.cache;

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

bool loadsOfLodsLookupVirtualTile(
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
        round(metadata.r * 255.0) + round(metadata.g * 255.0) * 256.0,
        round(metadata.b * 255.0) + round(metadata.a * 255.0) * 256.0
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

vec4 loadsOfLodsSampleVirtualOrAtlas(vec2 uv, vec2 du, vec2 dv) {
    int slot;
    vec2 localUv;
    vec2 atlasMipSize;
    vec2 cacheSize;
    if (loadsOfLodsLookupVirtualTile(uv, du, dv, slot, localUv, atlasMipSize, cacheSize)) {
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
    return textureGrad(u_BlockTex, uv, du, dv);
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
			.replace(NEAREST_SAMPLE, "return loadsOfLodsSampleVirtualOrAtlas(uv, du, dv);")
			.replace(
				RGSS_SAMPLE,
				"rgssColor += loadsOfLodsSampleVirtualOrAtlas(sampleUV, dFdx(uv), dFdy(uv));"
			);
		patchApplied = true;
		return patched;
	}
}
