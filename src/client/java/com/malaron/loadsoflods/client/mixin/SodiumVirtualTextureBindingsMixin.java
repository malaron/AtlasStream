package com.malaron.loadsoflods.client.mixin;

import com.malaron.loadsoflods.client.AtlasPreviewState;
import com.malaron.loadsoflods.client.AtlasPreviewState.VirtualTextureSamplingViews;
import com.malaron.loadsoflods.client.SodiumLodBridge;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(
	targets = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer",
	remap = false
)
public abstract class SodiumVirtualTextureBindingsMixin {
	@Redirect(
		method = "render",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V",
			ordinal = 1
		),
		remap = false
	)
	private void loadsOfLods$bindVirtualTextureSamplers(
		RenderPass renderPass,
		String name,
		GpuTextureView atlas,
		GpuSampler sampler
	) {
		renderPass.setUniform(name, atlas, sampler);
		VirtualTextureSamplingViews views = AtlasPreviewState.virtualTextureSamplingViews(
			SodiumLodBridge.useVirtualTextureForCurrentPass()
		);
		renderPass.setUniform("u_VirtualTileAtlas", views.tileAtlas(), sampler);
		renderPass.setUniform("u_VirtualPageTable", views.pageTable(), sampler);
		renderPass.setUniform("u_VirtualAtlasMetadata", views.atlasMetadata(), sampler);
		renderPass.setUniform("u_LodsNearRemap", SodiumLodBridge.nearRemapForCurrentPass(), sampler);
	}
}
