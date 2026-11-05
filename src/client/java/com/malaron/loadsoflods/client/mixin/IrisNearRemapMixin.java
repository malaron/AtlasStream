package com.malaron.loadsoflods.client.mixin;

import com.malaron.loadsoflods.LoadsOfLods;
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
vec2 loadsOfLodsNearRemap(vec2 uv) {
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
    ivec2 cellCoord = clamp(ivec2(floor(texel / cell)), ivec2(0), ivec2(remapSize.x - 1, rows - 1));
    vec4 entry = texelFetch(u_LodsNearRemap, cellCoord, 0);
    if (entry.a < 0.5) {
        return vec2(cell * 0.5) / compactSize;
    }
    float dx = floor(entry.r * 255.0 + 0.5) + floor(entry.g * 255.0 + 0.5) * 256.0 - 32768.0;
    float dy = floor(entry.b * 255.0 + 0.5) + mod(floor(entry.a * 255.0 + 0.5), 128.0) * 256.0 - 4096.0;
    return (texel + vec2(dx, dy) * cell) / compactSize;
}
""";
	private static boolean logged;

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
			String replaced = source.substring(0, function) + REMAP_FUNCTION + source.substring(function);
			replaced = replaced.replace(
				TEXCOORD_RETURN,
				"return loadsOfLodsNearRemap(vec2(a_TexCoord & TEXTURE_MAX_VALUE) / float(TEXTURE_MAX_COORD));"
			);
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
