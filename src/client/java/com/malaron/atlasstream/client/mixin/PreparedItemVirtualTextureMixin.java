package com.malaron.atlasstream.client.mixin;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import com.malaron.atlasstream.AtlasStream;
import com.malaron.atlasstream.client.atlas.AtlasRuntimeState;
import com.malaron.atlasstream.client.diagnostics.ItemStreamDiagnostics;
import com.malaron.atlasstream.client.sodium.SodiumTerrainIntegration;
import com.malaron.atlasstream.client.streaming.VirtualItemPipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PreparedRenderType.class)
public abstract class PreparedItemVirtualTextureMixin {
	@Shadow public abstract String name();
	@Shadow public abstract List<PreparedRenderType.Texture> textures();
	@Unique private boolean atlasStream$virtualItem;
	@Unique private boolean atlasStream$streamTiles;
	@Unique private static long atlasStream$failedGeneration = Long.MIN_VALUE;
	@Unique private static final Set<RenderPipeline> atlasStream$failedPipelines =
		Collections.newSetFromMap(new IdentityHashMap<>());

	@Redirect(
		method = "draw",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;")
	)
	private CompiledRenderPipeline atlasStream$selectItemPipeline(RenderPipeline pipeline) {
		atlasStream$virtualItem = false;
		atlasStream$streamTiles = false;
		boolean itemShader = name().startsWith("item_")
			&& Identifier.withDefaultNamespace("core/item").equals(pipeline.getShaders().get(ShaderType.FRAGMENT));
		boolean blockAtlas = textures().stream().anyMatch(texture -> texture.name().equals("Sampler0")
			&& AtlasRuntimeState.isReducedBlockAtlasView(texture.textureView()));
		if (!AtlasRuntimeState.isDistancePreviewEnabled()
				|| SodiumTerrainIntegration.isIrisShaderPackInUse()
				|| !itemShader || !blockAtlas) {
			if (itemShader) {
				ItemStreamDiagnostics.recordDraw(name() + (blockAtlas ? " reduced" : " other-atlas"));
			}
			return RenderSystem.getCompiledPipeline(pipeline);
		}
		long generation = AtlasRuntimeState.virtualTextureGeneration();
		if (atlasStream$failedGeneration != generation) {
			atlasStream$failedGeneration = generation;
			atlasStream$failedPipelines.clear();
		}
		if (atlasStream$failedPipelines.contains(pipeline)) {
			ItemStreamDiagnostics.recordDraw(name() + " compile-failed");
			return RenderSystem.getCompiledPipeline(pipeline);
		}
		CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(VirtualItemPipeline.forPipeline(pipeline));
		if (compiled == null) {
			atlasStream$failedPipelines.add(pipeline);
			AtlasStream.LOGGER.error("Could not compile virtual block-item pipeline {}; using reduced atlas", pipeline);
			ItemStreamDiagnostics.recordDraw(name() + " compile-failed");
			return RenderSystem.getCompiledPipeline(pipeline);
		}
		atlasStream$virtualItem = true;
		atlasStream$streamTiles = AtlasRuntimeState.useVirtualTextureForItemDraw();
		ItemStreamDiagnostics.recordDraw(name() + " virtual");
		return compiled;
	}

	@Redirect(
		method = "draw",
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V")
	)
	private void atlasStream$bindItemSamplers(RenderPass pass, String name, GpuTextureView view, GpuSampler sampler) {
		pass.setUniform(name, view, sampler);
		if (atlasStream$virtualItem && name.equals("Sampler0")) {
			var views = AtlasRuntimeState.virtualTextureSamplingViews(atlasStream$streamTiles);
			pass.setUniform("u_VirtualTileAtlas", views.tileAtlas(), sampler);
			pass.setUniform("u_VirtualPageTable", views.pageTable(), sampler);
			pass.setUniform("u_VirtualAtlasMetadata", views.atlasMetadata(), sampler);
			pass.setUniform("u_AtlasStreamLowRemap", AtlasRuntimeState.lowAtlasRemapView(), sampler);
		}
	}
}
