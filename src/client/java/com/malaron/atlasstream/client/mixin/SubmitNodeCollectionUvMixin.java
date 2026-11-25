package com.malaron.atlasstream.client.mixin;

import com.malaron.atlasstream.client.atlas.AtlasRuntimeState;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.UvMapping;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(SubmitNodeCollection.class)
public abstract class SubmitNodeCollectionUvMixin {
	@ModifyArg(
		method = "submitModel",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V"
		),
		index = 7
	)
	private UvMapping atlasStream$remapModelUv(UvMapping uv) {
		return AtlasRuntimeState.remapEntityBlockUv(uv);
	}
}
