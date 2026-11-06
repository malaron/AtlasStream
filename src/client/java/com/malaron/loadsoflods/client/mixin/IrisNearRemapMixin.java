package com.malaron.loadsoflods.client.mixin;

import com.malaron.loadsoflods.LoadsOfLods;
import com.malaron.loadsoflods.cache.VanillaNearShaderPatcher;
import java.util.LinkedHashMap;
import java.util.Map;
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
uniform sampler2D u_LodsNearRemap;
vec2 loadsOfLodsNearRemap(vec2 uv, vec2 anchor) {
    ivec2 remapSize = textureSize(u_LodsNearRemap, 0);
    if (remapSize.x <= 1) {
        return uv;
    }
    int rows = remapSize.y - 1;
    vec4 header0 = texelFetch(u_LodsNearRemap, ivec2(0, rows), 0);
    vec4 header1 = texelFetch(u_LodsNearRemap, ivec2(1, rows), 0);
    vec2 compactSize = vec2(
        floor(header0.r * 255.0 + 0.5) + floor(header0.g * 255.0 + 0.5) * 256.0,
        floor(header0.b * 255.0 + 0.5) + floor(header0.a * 255.0 + 0.5) * 256.0
    ) + 1.0;
    float cell = floor(header1.r * 255.0 + 0.5);
    vec2 texel = uv * (vec2(remapSize.x, rows) * cell);
    vec2 anchorTexel = anchor * (vec2(remapSize.x, rows) * cell);
    ivec2 cellCoord = clamp(ivec2(floor(anchorTexel / cell)), ivec2(0), ivec2(remapSize.x - 1, rows - 1));
    vec4 entry = texelFetch(u_LodsNearRemap, cellCoord, 0);
    if (entry.a < 0.5) {
        return vec2(cell * 0.5) / compactSize;
    }
    float dx = floor(entry.r * 255.0 + 0.5) + floor(entry.g * 255.0 + 0.5) * 256.0 - 32768.0;
    float dy = floor(entry.b * 255.0 + 0.5) + mod(floor(entry.a * 255.0 + 0.5), 128.0) * 256.0 - 4096.0;
    return (texel + vec2(dx, dy) * cell) / compactSize;
}
vec2 loadsOfLodsNearRemap(vec2 uv) {
    return loadsOfLodsNearRemap(uv, uv);
}
""";
	private static final java.util.regex.Pattern MID_TEXCOORD = java.util.regex.Pattern.compile(
		"vec2\\(mc_midTexCoord\\.xy\\) \\* ([0-9.]+(?:[eE][-+]?[0-9]+)?f?)"
	);
	private static boolean logged;
	private static boolean midLogged;
	private static boolean vanillaLogged;
	private static final java.util.regex.Pattern UV0_DECLARATION = java.util.regex.Pattern.compile(
		"in\\s+vec2\\s+iris_UV0\\s*;"
	);
	private static final java.util.regex.Pattern GTEXTURE_DECLARATION = java.util.regex.Pattern.compile(
		"uniform\\s+(?:\\w+\\s+)*sampler2D\\s+gtexture\\s*;"
	);
	// Vanilla-style programs (entities, hand, particles) draw many textures, so the remap only applies when the
	// bound diffuse texture has the compact near atlas's size.
	private static final String ATLAS_ONLY_FUNCTION = """
vec2 loadsOfLodsAtlasRemap(vec2 uv, vec2 anchor) {
    ivec2 remapSize = textureSize(u_LodsNearRemap, 0);
    if (remapSize.x <= 1) {
        return uv;
    }
    vec4 header0 = texelFetch(u_LodsNearRemap, ivec2(0, remapSize.y - 1), 0);
    ivec2 compactSize = ivec2(
        floor(header0.r * 255.0 + 0.5) + floor(header0.g * 255.0 + 0.5) * 256.0,
        floor(header0.b * 255.0 + 0.5) + floor(header0.a * 255.0 + 0.5) * 256.0
    ) + 1;
    if (textureSize(gtexture, 0) != compactSize) {
        return uv;
    }
    return loadsOfLodsNearRemap(uv, anchor);
}
vec2 loadsOfLodsAtlasRemap(vec2 uv) {
    return loadsOfLodsAtlasRemap(uv, uv);
}
""";

	@Inject(method = "patchVanilla", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private static void loadsOfLods$remapVanilla(CallbackInfoReturnable<Map<Object, String>> callback) {
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
			String functions = REMAP_FUNCTION
				+ (GTEXTURE_DECLARATION.matcher(source).find() ? "" : "uniform sampler2D gtexture;\n")
				+ ATLAS_ONLY_FUNCTION;
			// Include declarations before iris_UV0 when identifying the midpoint attribute.
			String patchedSource = VanillaNearShaderPatcher.patchReferences(source);
			declaration = UV0_DECLARATION.matcher(patchedSource);
			declaration.find();
			String head = patchedSource.substring(0, declaration.end());
			String tail = patchedSource.substring(declaration.end());
			entry.setValue(head + "\n" + functions + tail);
			changed = true;
		}
		if (changed) {
			com.malaron.loadsoflods.client.AtlasPreviewState.markVanillaRemapAvailable();
			if (!vanillaLogged) {
				vanillaLogged = true;
				LoadsOfLods.LOGGER.info("Patched Iris-transformed vanilla vertex shaders for the near UV remap");
			}
			callback.setReturnValue(patched);
		}
	}

	@Inject(method = "patchSodium", at = @At("RETURN"), cancellable = true, remap = false)
	private static void loadsOfLods$remap(CallbackInfoReturnable<Map<Object, String>> callback) {
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
				LoadsOfLods.LOGGER.warn("_get_texcoord() not found in an Iris-transformed Sodium vertex shader; near UV remap is unavailable");
				continue;
			}
			// Translucent terrain stays on the low atlas but shares these programs, so the remap only applies when the
			// bound diffuse texture is the compact near atlas.
			String atlasFunctions = (GTEXTURE_DECLARATION.matcher(source).find() ? "" : "uniform sampler2D gtexture;\n")
				+ ATLAS_ONLY_FUNCTION;
			String replaced = source.substring(0, function) + REMAP_FUNCTION + atlasFunctions + source.substring(function);
			replaced = replaced.replace(
				TEXCOORD_RETURN,
				"return loadsOfLodsAtlasRemap(vec2(a_TexCoord & TEXTURE_MAX_VALUE) / float(TEXTURE_MAX_COORD));"
			);
			// The sprite's mid coordinate is a separate attribute in layout space; packs compare it with the diffuse
			// UV (for example to clamp to the sprite), so it has to move to the compact atlas as well.
			int[] midCount = {0};
			replaced = MID_TEXCOORD.matcher(replaced).replaceAll(
				match -> {
					midCount[0]++;
					return java.util.regex.Matcher.quoteReplacement(
						"loadsOfLodsAtlasRemap(vec2(mc_midTexCoord.xy) * " + match.group(1) + ")"
					);
				}
			);
			if (midCount[0] == 0 && !midLogged && replaced.contains("mc_midTexCoord")) {
				midLogged = true;
				int at = replaced.indexOf("mc_midTexCoord");
				LoadsOfLods.LOGGER.warn("Near UV remap did not patch mc_midTexCoord; shader text: {}",
					at < 0 ? "<absent>" : replaced.substring(Math.max(0, at - 120), Math.min(replaced.length(), at + 200)).replace('\n', ' '));
			}
			entry.setValue(replaced);
			changed = true;
		}
		if (changed) {
			if (!logged) {
				logged = true;
				LoadsOfLods.LOGGER.info("Patched Iris-transformed Sodium vertex shaders for the near UV remap");
			}
			callback.setReturnValue(patched);
		}
	}
}
