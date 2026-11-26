package com.malaron.atlasstream.client.mixin;

import java.util.LinkedHashMap;
import java.util.Map;

import com.malaron.atlasstream.AtlasStream;
import com.malaron.atlasstream.shader.OriginalUvShaderContract;
import com.malaron.atlasstream.shader.IrisVirtualTileShaderContract;
import com.malaron.atlasstream.shader.VanillaNearShaderPatcher;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Iris terrain shaders read their diffuse UV through _get_texcoord() in the transformed Sodium vertex shader. This
 * routes it through the near-atlas remap texture, which sends a UV in the (possibly huge) vanilla layout to the
 * sprite's slot in the compact near atlas. The remap is a no-op when the 1x1 placeholder is bound.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.transform.TransformPatcher", remap = false)
public abstract class IrisNearRemapMixin {
	private static final String TEXCOORD_RETURN =
		"return vec2(a_TexCoord & TEXTURE_MAX_VALUE) / float(TEXTURE_MAX_COORD);";
	private static final String REMAP_FUNCTION = """
uniform sampler2D u_AtlasStreamNearRemap;
vec2 atlasStreamNearRemap(vec2 uv, vec2 anchor) {
    ivec2 remapSize = textureSize(u_AtlasStreamNearRemap, 0);
    if (remapSize.x <= 1) {
        return uv;
    }
    int rows = remapSize.y - 1;
    vec4 header0 = texelFetch(u_AtlasStreamNearRemap, ivec2(0, rows), 0);
    vec4 header1 = texelFetch(u_AtlasStreamNearRemap, ivec2(1, rows), 0);
    vec2 compactSize = vec2(
        floor(header0.r * 255.0 + 0.5) + floor(header0.g * 255.0 + 0.5) * 256.0,
        floor(header0.b * 255.0 + 0.5) + floor(header0.a * 255.0 + 0.5) * 256.0
    ) + 1.0;
    float cell = floor(header1.r * 255.0 + 0.5);
    vec2 texel = uv * (vec2(remapSize.x, rows) * cell);
    vec2 anchorTexel = anchor * (vec2(remapSize.x, rows) * cell);
    ivec2 cellCoord = clamp(ivec2(floor(anchorTexel / cell)), ivec2(0), ivec2(remapSize.x - 1, rows - 1));
    vec4 entry = texelFetch(u_AtlasStreamNearRemap, cellCoord, 0);
    if (entry.a < 0.5) {
        float fallbackFactor = floor(header1.g * 255.0 + 0.5);
        if (fallbackFactor > 0.0) {
            return texel / (fallbackFactor * compactSize);
        }
        return vec2(cell * 0.5) / compactSize;
    }
    float dx = floor(entry.r * 255.0 + 0.5) + floor(entry.g * 255.0 + 0.5) * 256.0 - 32768.0;
    float dy = floor(entry.b * 255.0 + 0.5) + mod(floor(entry.a * 255.0 + 0.5), 128.0) * 256.0 - 4096.0;
    return (texel + vec2(dx, dy) * cell) / compactSize;
}
vec2 atlasStreamNearRemap(vec2 uv) {
    return atlasStreamNearRemap(uv, uv);
}
""";
	private static final String LOW_REMAP_FUNCTION = """
uniform sampler2D u_AtlasStreamLowRemap;
vec2 atlasStreamLowRemap(vec2 uv) {
    ivec2 remapSize = textureSize(u_AtlasStreamLowRemap, 0);
    if (remapSize.x <= 3) {
        return uv;
    }
    int headerY = remapSize.y - 1;
    vec4 destinationHeader = texelFetch(u_AtlasStreamLowRemap, ivec2(0, headerY), 0);
    if (textureSize(gtexture, 0) != ivec2(
        floor(destinationHeader.r * 255.0 + 0.5) + floor(destinationHeader.g * 255.0 + 0.5) * 256.0 + 1.0,
        floor(destinationHeader.b * 255.0 + 0.5) + floor(destinationHeader.a * 255.0 + 0.5) * 256.0 + 1.0)) {
        return uv;
    }
    vec4 sourceHeader = texelFetch(u_AtlasStreamLowRemap, ivec2(1, headerY), 0);
    vec4 gridHeader = texelFetch(u_AtlasStreamLowRemap, ivec2(2, headerY), 0);
    vec2 sourceSize = vec2(
        floor(sourceHeader.r * 255.0 + 0.5) + floor(sourceHeader.g * 255.0 + 0.5) * 256.0,
        floor(sourceHeader.b * 255.0 + 0.5) + floor(sourceHeader.a * 255.0 + 0.5) * 256.0
    ) + 1.0;
    float cell = floor(gridHeader.r * 255.0 + 0.5) + floor(gridHeader.g * 255.0 + 0.5) * 256.0;
    vec2 sourceTexel = uv * sourceSize;
    ivec2 coordinate = clamp(
        ivec2(floor(sourceTexel / cell)),
        ivec2(0),
        ivec2(remapSize.x / 3 - 1, remapSize.y - 2)
    );
    vec4 sourceOrigin = texelFetch(u_AtlasStreamLowRemap, ivec2(coordinate.x * 3, coordinate.y), 0);
    vec4 destination = texelFetch(u_AtlasStreamLowRemap, ivec2(coordinate.x * 3 + 1, coordinate.y), 0);
    vec4 factorEntry = texelFetch(u_AtlasStreamLowRemap, ivec2(coordinate.x * 3 + 2, coordinate.y), 0);
    int encodedFactor = int(floor(factorEntry.r * 255.0 + 0.5));
    if (encodedFactor == 0) {
        return uv;
    }
    vec2 sourceOriginPixels = vec2(
        floor(sourceOrigin.r * 255.0 + 0.5) + floor(sourceOrigin.g * 255.0 + 0.5) * 256.0,
        floor(sourceOrigin.b * 255.0 + 0.5) + floor(sourceOrigin.a * 255.0 + 0.5) * 256.0
    );
    vec2 destinationOriginPixels = vec2(
        floor(destination.r * 255.0 + 0.5) + floor(destination.g * 255.0 + 0.5) * 256.0,
        floor(destination.b * 255.0 + 0.5) + floor(destination.a * 255.0 + 0.5) * 256.0
    );
    vec2 destinationSize = vec2(
        floor(destinationHeader.r * 255.0 + 0.5) + floor(destinationHeader.g * 255.0 + 0.5) * 256.0,
        floor(destinationHeader.b * 255.0 + 0.5) + floor(destinationHeader.a * 255.0 + 0.5) * 256.0
    ) + 1.0;
    float factor = exp2(float(encodedFactor - 1));
    return (destinationOriginPixels + (sourceTexel - sourceOriginPixels) / factor) / destinationSize;
}
""";
	private static final java.util.regex.Pattern MID_TEXCOORD = java.util.regex.Pattern.compile(
		"vec2\\(mc_midTexCoord\\.xy\\) \\* ([0-9.]+(?:[eE][-+]?[0-9]+)?f?)"
	);
	private static boolean logged;
	private static boolean midLogged;
	private static boolean vanillaLogged;
	private static boolean virtualContractLogged;
	private static final java.util.regex.Pattern UV0_DECLARATION = java.util.regex.Pattern.compile(
		"in\\s+vec2\\s+iris_UV0\\s*;"
	);
	private static final java.util.regex.Pattern GTEXTURE_DECLARATION = java.util.regex.Pattern.compile(
		"uniform\\s+(?:\\w+\\s+)*sampler2D\\s+gtexture\\s*;"
	);
	// Vanilla-style programs (entities, hand, particles) draw many textures, so the remap only applies when the
	// bound diffuse texture has the compact near atlas's size.
	private static final String ATLAS_ONLY_FUNCTION = """
vec2 atlasStreamAtlasRemap(vec2 uv, vec2 anchor) {
    ivec2 remapSize = textureSize(u_AtlasStreamNearRemap, 0);
    if (remapSize.x <= 1) {
        return uv;
    }
    vec4 header0 = texelFetch(u_AtlasStreamNearRemap, ivec2(0, remapSize.y - 1), 0);
    ivec2 compactSize = ivec2(
        floor(header0.r * 255.0 + 0.5) + floor(header0.g * 255.0 + 0.5) * 256.0,
        floor(header0.b * 255.0 + 0.5) + floor(header0.a * 255.0 + 0.5) * 256.0
    ) + 1;
    if (textureSize(gtexture, 0) != compactSize) {
        return uv;
    }
    return atlasStreamNearRemap(uv, anchor);
}
vec2 atlasStreamAtlasRemap(vec2 uv) {
    return atlasStreamAtlasRemap(uv, uv);
}
vec2 atlasStreamTerrainRemap(vec2 uv, vec2 anchor) {
    ivec2 remapSize = textureSize(u_AtlasStreamNearRemap, 0);
    vec4 header0 = texelFetch(u_AtlasStreamNearRemap, ivec2(0, remapSize.y - 1), 0);
    ivec2 compactSize = ivec2(
        floor(header0.r * 255.0 + 0.5) + floor(header0.g * 255.0 + 0.5) * 256.0,
        floor(header0.b * 255.0 + 0.5) + floor(header0.a * 255.0 + 0.5) * 256.0
    ) + 1;
    if (remapSize.x > 1 && textureSize(gtexture, 0) == compactSize) {
        return atlasStreamNearRemap(uv, anchor);
    }
    return atlasStreamLowRemap(uv);
}
vec2 atlasStreamTerrainRemap(vec2 uv) {
    return atlasStreamTerrainRemap(uv, uv);
}
""";

	@Inject(method = "patchVanilla", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private static void atlasStream$remapVanilla(CallbackInfoReturnable<Map<Object, String>> callback) {
		Map<Object, String> result = callback.getReturnValue();
		if (result == null) {
			return;
		}
		Map<Object, String> patched = new LinkedHashMap<>(result);
		boolean changed = false;
		for (Map.Entry<Object, String> entry : patched.entrySet()) {
			if (!"VERTEX".equals(String.valueOf(entry.getKey()))) {
				continue;
			}
			String source = entry.getValue();
			java.util.regex.Matcher declaration = UV0_DECLARATION.matcher(source);
			if (!declaration.find()) {
				continue;
			}
			String functions =
				(GTEXTURE_DECLARATION.matcher(source).find() ? "" : "uniform sampler2D gtexture;\n")
				+ REMAP_FUNCTION + LOW_REMAP_FUNCTION + ATLAS_ONLY_FUNCTION;
			// Include declarations before iris_UV0 when identifying the midpoint attribute.
			String patchedSource = VanillaNearShaderPatcher.patchReferences(source);
			if (patchedSource.equals(source)) {
				continue;
			}
			patchedSource = OriginalUvShaderContract.patchVertex(patchedSource);
			declaration = UV0_DECLARATION.matcher(patchedSource);
			declaration.find();
			String head = patchedSource.substring(0, declaration.end());
			String tail = patchedSource.substring(declaration.end());
			entry.setValue(head + "\n" + functions + tail);
			changed = true;
		}
		if (changed) {
			com.malaron.atlasstream.client.atlas.AtlasRuntimeState.markVanillaRemapAvailable();
			if (!vanillaLogged) {
				vanillaLogged = true;
				AtlasStream.LOGGER.info("Patched Iris-transformed vanilla vertex shaders for the near UV remap");
			}
			callback.setReturnValue(patched);
		}
	}

	@Inject(method = "patchSodium", at = @At("RETURN"), cancellable = true, remap = false)
	private static void atlasStream$remap(CallbackInfoReturnable<Map<Object, String>> callback) {
		Map<Object, String> result = callback.getReturnValue();
		if (result == null) {
			return;
		}
		Map<Object, String> patched = new LinkedHashMap<>(result);
		boolean changed = false;
		for (Map.Entry<Object, String> entry : patched.entrySet()) {
			if (!"VERTEX".equals(String.valueOf(entry.getKey()))) {
				continue;
			}
			String source = entry.getValue();
			int function = source.indexOf("vec2 _get_texcoord()");
			if (function < 0 || !source.contains(TEXCOORD_RETURN)) {
				AtlasStream.LOGGER.warn("_get_texcoord() not found in an Iris-transformed Sodium vertex shader; near UV remap is unavailable");
				continue;
			}
			// Translucent terrain stays on the low atlas but shares these programs, so the remap only applies when the
			// bound diffuse texture is the compact near atlas.
			String atlasFunctions = (GTEXTURE_DECLARATION.matcher(source).find() ? "" : "uniform sampler2D gtexture;\n")
				+ "out vec2 atlasStream_OriginalUv;\n"
				+ REMAP_FUNCTION + LOW_REMAP_FUNCTION + ATLAS_ONLY_FUNCTION;
			String replaced = source.substring(0, function) + atlasFunctions + source.substring(function);
			replaced = OriginalUvShaderContract.patchSodiumVertex(replaced, TEXCOORD_RETURN);
			if (!replaced.contains("atlasStream_OriginalUv = vec2(")) {
				AtlasStream.LOGGER.error(
					"Could not export Sodium original UV; texcoord source remains: {}",
					shaderSnippet(replaced, TEXCOORD_RETURN)
				);
				continue;
			}
			// The sprite's mid coordinate is a separate attribute in layout space; packs compare it with the diffuse
			// UV (for example to clamp to the sprite), so it has to move to the compact atlas as well.
			int[] midCount = {0};
			replaced = MID_TEXCOORD.matcher(replaced).replaceAll(
				match -> {
					midCount[0]++;
					return java.util.regex.Matcher.quoteReplacement(
						"atlasStreamTerrainRemap(vec2(mc_midTexCoord.xy) * " + match.group(1) + ")"
					);
				}
			);
			if (midCount[0] == 0 && !midLogged && replaced.contains("mc_midTexCoord")) {
				midLogged = true;
				int at = replaced.indexOf("mc_midTexCoord");
				AtlasStream.LOGGER.warn("Near UV remap did not patch mc_midTexCoord; shader text: {}",
					at < 0 ? "<absent>" : replaced.substring(Math.max(0, at - 120), Math.min(replaced.length(), at + 200)).replace('\n', ' '));
			}
			entry.setValue(replaced);
			changed = true;
		}
		for (Map.Entry<Object, String> entry : patched.entrySet()) {
			if (!"FRAGMENT".equals(String.valueOf(entry.getKey()))) {
				continue;
			}
			String original = entry.getValue();
			String fragment = IrisVirtualTileShaderContract.patchSodiumPbrFragment(original);
			if (!virtualContractLogged && original.contains("sceneAlbedo")) {
				virtualContractLogged = true;
				var analysis = IrisVirtualTileShaderContract.analyzeSodiumFragment(original);
				AtlasStream.LOGGER.info(
					"Iris virtual shader contract candidate: originalUvIn={}, directSamples={}/{}, "
						+ "parallaxSamples={}/{}, samplerDeclared={}, helpersBeforeMain={}, "
						+ "requiredSamplersBeforeHelpers={}",
					analysis.originalUvInput(),
					analysis.originalDirectSamples(),
					analysis.candidateDirectSamples(),
					analysis.originalParallaxSamples(),
					analysis.candidateParallaxSamples(),
					analysis.virtualSamplersDeclared(),
					analysis.helpersBeforeMain(),
					analysis.requiredSamplersBeforeHelpers()
				);
			}
			if (!fragment.equals(original)) {
				entry.setValue(fragment);
				changed = true;
			}

		}
		if (changed) {
			if (!logged) {
				logged = true;
				AtlasStream.LOGGER.info("Patched Iris-transformed Sodium vertex shaders for the near UV remap");
			}
			callback.setReturnValue(patched);
		}
	}

	private static String shaderSnippet(String source, String marker) {
		int at = source.indexOf(marker);
		if (at < 0) {
			at = source.indexOf("_get_texcoord()");
		}
		if (at < 0) {
			return "<marker absent>";
		}
		return source.substring(Math.max(0, at - 160), Math.min(source.length(), at + 320))
			.replace('\n', ' ');
	}
}
