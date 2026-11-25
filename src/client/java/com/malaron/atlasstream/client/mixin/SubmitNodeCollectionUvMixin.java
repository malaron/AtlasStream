package com.malaron.atlasstream.client.mixin;

import com.malaron.atlasstream.client.atlas.AtlasRuntimeState;

import net.minecraft.client.renderer.SubmitNodeCollection;
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
			target = "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$Submit;<init>(Lnet/minecraft/client/renderer/rendertype/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/model/Model;Ljava/lang/Object;IIILnet/minecraft/client/renderer/texture/UvMapping;Lcom/mojang/blaze3d/vertex/PoseStack$Pose;)V"
		),
		index = 7,
		require = 0
	)
	private UvMapping atlasStream$remapModelUv(UvMapping uv) {
		return AtlasRuntimeState.remapEntityBlockUv(uv);
	}
}
