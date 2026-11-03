package com.malaron.loadsoflods.client.mixin;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.renderpearl.api.pipeline.UniformType;
import java.util.HashSet;
import java.util.Set;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(
	targets = "net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer",
	remap = false
)
public abstract class SodiumVirtualTextureLayoutMixin {
	@Shadow
	@Final
	@Mutable
	private static BindGroupLayout BIND_GROUP;

	@Inject(method = "<clinit>", at = @At("TAIL"), remap = false)
	private static void loadsOfLods$addVirtualTextureBindings(CallbackInfo callback) {
		BindGroupLayout.Builder builder = BindGroupLayout.builder();
		Set<String> names = new HashSet<>();
		for (UniformDescription uniform : BIND_GROUP.uniforms()) {
			names.add(uniform.name());
			if (uniform.gpuFormat() == null) {
				builder.withUniform(uniform.name(), uniform.type());
			} else {
				builder.withUniform(uniform.name(), uniform.type(), uniform.gpuFormat());
			}
		}
		addSampler(builder, names, "u_VirtualTileAtlas");
		addSampler(builder, names, "u_VirtualPageTable");
		addSampler(builder, names, "u_VirtualAtlasMetadata");
		BIND_GROUP = builder.build();
	}

	private static void addSampler(BindGroupLayout.Builder builder, Set<String> names, String name) {
		if (names.add(name)) {
			builder.withUniform(name, UniformType.COMBINED_IMAGE_SAMPLER);
		}
	}
}
