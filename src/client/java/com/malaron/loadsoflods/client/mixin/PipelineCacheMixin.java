package com.malaron.loadsoflods.client.mixin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.malaron.loadsoflods.LoadsOfLods;
import com.malaron.loadsoflods.cache.VirtualTextureShaderPatcher;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.mojang.blaze3d.pipeline.PipelineCache")
public abstract class PipelineCacheMixin {
	@Unique
	private static boolean warnedAboutUnsupportedSodiumShader;

	@Redirect(
		method = "get",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/renderpearl/api/device/GpuDevice;compilePipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lcom/mojang/renderpearl/api/pipeline/ShaderSource;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
		)
	)
	private CompletableFuture<CompiledRenderPipeline.Pending> loadsOfLods$patchSodiumFragmentShader(
		GpuDevice device,
		RenderPipeline pipeline,
		ShaderSource source,
		Executor executor
	) {
		return device.compilePipeline(
			pipeline,
			new VirtualTextureShaderSource(source),
			executor
		);
	}

	private static final class VirtualTextureShaderSource implements ShaderSource {
		private final ShaderSource delegate;

		private VirtualTextureShaderSource(ShaderSource delegate) {
			this.delegate = delegate;
		}

		@Override
		public String getShader(Identifier shader, ShaderType type) {
			String source = delegate.getShader(shader, type);
			if (type != ShaderType.FRAGMENT
					|| !shader.getNamespace().equals("sodium")
					|| !shader.getPath().equals("blocks/block_layer_opaque")) {
				return source;
			}
			String patched = VirtualTextureShaderPatcher.patch(source);
			if (patched.equals(source) && !source.contains("u_VirtualPageTable")) {
				synchronized (PipelineCacheMixin.class) {
					if (!warnedAboutUnsupportedSodiumShader) {
						LoadsOfLods.LOGGER.warn(
							"Could not add virtual-texture sampling to Sodium's chunk shader; the active shader will use the normal atlas"
						);
						warnedAboutUnsupportedSodiumShader = true;
					}
				}
			}
			return patched;
		}

		@Override
		public CachedIncludeSource getInclude(Identifier include) {
			return delegate.getInclude(include);
		}

		@Override
		public void close() {
		}
	}
}
