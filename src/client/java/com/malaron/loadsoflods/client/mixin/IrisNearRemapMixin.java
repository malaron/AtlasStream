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
vec2 loadsOfLodsNearRemapKeyed(vec2 uv, vec2 keyOffset) {
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
    // Edge vertices sit exactly on a cell boundary, so look the slot up slightly inside the sprite.
    ivec2 cellCoord = clamp(ivec2(floor((texel + keyOffset * cell) / cell)), ivec2(0), ivec2(remapSize.x - 1, rows - 1));
    vec4 entry = texelFetch(u_LodsNearRemap, cellCoord, 0);
    if (entry.a < 0.5) {
        return vec2(cell * 0.5) / compactSize;
    }
    float dx = floor(entry.r * 255.0 + 0.5) + floor(entry.g * 255.0 + 0.5) * 256.0 - 32768.0;
    float dy = floor(entry.b * 255.0 + 0.5) + mod(floor(entry.a * 255.0 + 0.5), 128.0) * 256.0 - 4096.0;
    return (texel + vec2(dx, dy) * cell) / compactSize;
}
vec2 loadsOfLodsNearRemap(vec2 uv) {
    return loadsOfLodsNearRemapKeyed(uv, vec2(0.0));
}
vec2 loadsOfLodsNearRemapEdge(vec2 uv) {
    return loadsOfLodsNearRemapKeyed(uv, mix(vec2(0.25), vec2(-0.25), bvec2(a_TexCoord >> TEXTURE_BITS)));
}
""";
	private static final java.util.regex.Pattern MID_TEXCOORD = java.util.regex.Pattern.compile(
		"vec2\\(mc_midTexCoord\\.xy\\) \\* (.*?)(?=\\)\\.xy;|, 0\\.0\\);|, 0\\.0, 1\\.0\\);)"
	);
	private static boolean logged;
	private static boolean midLogged;

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
				"return loadsOfLodsNearRemapEdge(vec2(a_TexCoord & TEXTURE_MAX_VALUE) / float(TEXTURE_MAX_COORD));"
			);
			// The sprite's mid coordinate is a separate attribute in layout space; packs compare it with the diffuse
			// UV (for example to clamp to the sprite), so it has to move to the compact atlas as well.
			int[] midCount = {0};
			replaced = MID_TEXCOORD.matcher(replaced).replaceAll(
				match -> {
					midCount[0]++;
					return java.util.regex.Matcher.quoteReplacement(
						"loadsOfLodsNearRemap(vec2(mc_midTexCoord.xy) * " + match.group(1) + ")"
					);
				}
			);
			if (midCount[0] == 0 && !midLogged) {
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
