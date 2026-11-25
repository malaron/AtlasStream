package com.malaron.atlasstream.client.mixin;

import com.malaron.atlasstream.client.atlas.AtlasRuntimeState;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.ConduitRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.UvMapping;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ConduitRenderer.class)
public abstract class ConduitRendererUvMixin {
	@WrapOperation(
		method = "submit",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModelPart(Lnet/minecraft/client/model/geom/ModelPart;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IILnet/minecraft/client/renderer/texture/UvMapping;)V"
		)
	)
	private void atlasStream$remapSimplePart(
		SubmitNodeCollector collector,
		ModelPart part,
		PoseStack pose,
		RenderType renderType,
		int light,
		int overlay,
		UvMapping uv,
		Operation<Void> original
	) {
		original.call(collector, part, pose, renderType, light, overlay,
			AtlasRuntimeState.remapEntityBlockUv(uv));
	}

	@WrapOperation(
		method = "submit",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModelPart(Lnet/minecraft/client/model/geom/ModelPart;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IILnet/minecraft/client/renderer/texture/UvMapping;I)V"
		)
	)
	private void atlasStream$remapTintedPart(
		SubmitNodeCollector collector,
		ModelPart part,
		PoseStack pose,
		RenderType renderType,
		int light,
		int overlay,
		UvMapping uv,
		int tint,
		Operation<Void> original
	) {
		original.call(collector, part, pose, renderType, light, overlay,
			AtlasRuntimeState.remapEntityBlockUv(uv), tint);
	}
}
