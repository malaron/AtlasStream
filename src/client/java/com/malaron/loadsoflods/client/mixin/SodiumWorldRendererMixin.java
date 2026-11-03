package com.malaron.loadsoflods.client.mixin;

import com.malaron.loadsoflods.client.SodiumLodBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(
	targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer",
	remap = false
)
public abstract class SodiumWorldRendererMixin {
	@Redirect(
		method = "renderLayer",
		at = @At(
			value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderer;render(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;Lnet/caffeinemc/mods/sodium/client/util/FogParameters;ZLcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;Lnet/minecraft/client/renderer/oit/OitStage;)V"
		),
		remap = false
	)
	private void loadsOfLods$renderBySectionLod(
		@Coerce Object renderer,
		@Coerce Object matrices,
		@Coerce Object renderLists,
		@Coerce Object terrainRenderPass,
		@Coerce Object cameraTransform,
		@Coerce Object fogParameters,
		boolean translucentSorting,
		@Coerce Object renderPass,
		@Coerce Object sampler,
		@Coerce Object globals,
		@Coerce Object sectionTimeInfo,
		@Coerce Object oitStage
	) {
		SodiumLodBridge.render(
			renderer,
			matrices,
			renderLists,
			terrainRenderPass,
			cameraTransform,
			fogParameters,
			translucentSorting,
			renderPass,
			sampler,
			globals,
			sectionTimeInfo,
			oitStage
		);
	}
}
