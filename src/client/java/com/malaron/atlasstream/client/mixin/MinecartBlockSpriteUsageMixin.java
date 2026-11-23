package com.malaron.atlasstream.client.mixin;

import com.malaron.atlasstream.client.sodium.BlockModelMeshTracker;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.entity.AbstractMinecartRenderer;
import net.minecraft.client.renderer.entity.state.MinecartRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractMinecartRenderer.class)
public abstract class MinecartBlockSpriteUsageMixin {
	@WrapOperation(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/MinecartRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/AbstractMinecartRenderer;submitMinecartContents(Lnet/minecraft/client/renderer/entity/state/MinecartRenderState;Lnet/minecraft/client/renderer/block/BlockModelRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V")
	)
	private void atlasStream$trackMinecartBlockMesh(
		AbstractMinecartRenderer<?, ?> renderer, MinecartRenderState state, BlockModelRenderState blockModel,
		PoseStack pose, SubmitNodeCollector collector, int light, Operation<Void> original
	) {
		BlockModelMeshTracker.begin();
		try {
			original.call(renderer, state, blockModel, pose, collector, light);
		} finally {
			BlockModelMeshTracker.end();
		}
	}
}
