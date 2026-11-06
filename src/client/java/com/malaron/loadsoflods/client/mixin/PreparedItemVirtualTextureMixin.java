package com.malaron.loadsoflods.client.mixin;

import com.malaron.loadsoflods.LoadsOfLods;
import com.malaron.loadsoflods.client.AtlasPreviewState;
import com.malaron.loadsoflods.client.SodiumLodBridge;
import com.malaron.loadsoflods.client.VirtualItemPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
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
	@Unique private boolean loadsOfLods$virtualItem;
	@Unique private static long loadsOfLods$failedGeneration = Long.MIN_VALUE;
	@Unique private static final Set<RenderPipeline> loadsOfLods$failedPipelines =
		Collections.newSetFromMap(new IdentityHashMap<>());

	@Redirect(
		method = "draw",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;")
	)
	private CompiledRenderPipeline loadsOfLods$selectItemPipeline(RenderPipeline pipeline) {
		loadsOfLods$virtualItem = false;
		if (!AtlasPreviewState.useVirtualTextureForItemDraw()
				|| SodiumLodBridge.isIrisShaderPackInUse()
				|| !name().startsWith("item_")
				|| !Identifier.withDefaultNamespace("core/item").equals(pipeline.getShaders().get(ShaderType.FRAGMENT))
				|| textures().stream().noneMatch(texture -> texture.name().equals("Sampler0")
					&& AtlasPreviewState.isReducedBlockAtlasView(texture.textureView()))) {
			return RenderSystem.getCompiledPipeline(pipeline);
		}
		long generation = AtlasPreviewState.virtualTextureGeneration();
		if (loadsOfLods$failedGeneration != generation) {
			loadsOfLods$failedGeneration = generation;
			loadsOfLods$failedPipelines.clear();
		}
		if (loadsOfLods$failedPipelines.contains(pipeline)) {
			return RenderSystem.getCompiledPipeline(pipeline);
		}
		CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(VirtualItemPipeline.forPipeline(pipeline));
		if (compiled == null) {
			loadsOfLods$failedPipelines.add(pipeline);
			LoadsOfLods.LOGGER.error("Could not compile virtual block-item pipeline {}; using reduced atlas", pipeline);
			return RenderSystem.getCompiledPipeline(pipeline);
		}
		loadsOfLods$virtualItem = true;
		return compiled;
	}

	@Redirect(
		method = "draw",
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V")
	)
	private void loadsOfLods$bindItemSamplers(RenderPass pass, String name, GpuTextureView view, GpuSampler sampler) {
		pass.setUniform(name, view, sampler);
		if (loadsOfLods$virtualItem && name.equals("Sampler0")) {
			var views = AtlasPreviewState.virtualTextureSamplingViews(true);
			pass.setUniform("u_VirtualTileAtlas", views.tileAtlas(), sampler);
			pass.setUniform("u_VirtualPageTable", views.pageTable(), sampler);
			pass.setUniform("u_VirtualAtlasMetadata", views.atlasMetadata(), sampler);
		}
	}
}
