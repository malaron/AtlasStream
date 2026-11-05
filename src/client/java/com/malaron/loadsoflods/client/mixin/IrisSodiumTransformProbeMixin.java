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
 * Feasibility probe for remapping Iris terrain UVs. It only checks that Iris's transformed Sodium vertex shader
 * can be patched at _get_texcoord() and that an extra sampler survives linking; the sampled value has no effect.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.transform.TransformPatcher", remap = false)
public abstract class IrisSodiumTransformProbeMixin {
	private static final String TEXCOORD_RETURN =
		"return vec2(a_TexCoord & TEXTURE_MAX_VALUE) / float(TEXTURE_MAX_COORD);";
	private static final String PROBE_UNIFORM = "uniform sampler2D u_LodsProbe;\n";

	@Inject(method = "patchSodium", at = @At("RETURN"), cancellable = true, remap = false)
	private static void loadsOfLods$probe(CallbackInfoReturnable<Map<Object, String>> callback) {
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
				LoadsOfLods.LOGGER.warn("Iris probe: _get_texcoord() not found in the transformed Sodium vertex shader");
				continue;
			}
			String replaced = source
				.substring(0, function) + PROBE_UNIFORM + source.substring(function);
			replaced = replaced.replace(
				TEXCOORD_RETURN,
				"return vec2(a_TexCoord & TEXTURE_MAX_VALUE) / float(TEXTURE_MAX_COORD)"
					+ " + texelFetch(u_LodsProbe, ivec2(0), 0).rg * 0.0;"
			);
			entry.setValue(replaced);
			changed = true;
		}
		if (changed) {
			LoadsOfLods.LOGGER.info("Iris probe: patched _get_texcoord() in a transformed Sodium vertex shader");
			callback.setReturnValue(patched);
		}
	}
}
