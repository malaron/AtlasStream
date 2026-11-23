package com.malaron.atlasstream.client.mixin;

import com.malaron.atlasstream.client.atlas.AtlasRuntimeState;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.resources.model.geometry.BakedQuad;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Non-terrain block quads (held and falling blocks) need their sprites resident in the near atlas.
@Mixin(VertexConsumer.class)
public interface VertexConsumerSpriteUsageMixin {
	@Inject(method = "putBakedQuad", at = @At("HEAD"), require = 0)
	private void atlasStream$trackQuadSprite(
		com.mojang.blaze3d.vertex.PoseStack.Pose pose,
		BakedQuad quad,
		com.mojang.blaze3d.vertex.QuadInstance instance,
		CallbackInfo callback
	) {
		AtlasRuntimeState.recordSpriteUsage(quad.materialInfo().sprite());
	}

	@Inject(method = "putBakedQuadWithGlint", at = @At("HEAD"), require = 0)
	private void atlasStream$trackGlintQuadSprite(
		com.mojang.blaze3d.vertex.PoseStack.Pose pose,
		BakedQuad quad,
		com.mojang.blaze3d.vertex.QuadInstance instance,
		com.mojang.blaze3d.vertex.PoseStack.Pose sheetedDecalPose,
		CallbackInfo callback
	) {
		AtlasRuntimeState.recordSpriteUsage(quad.materialInfo().sprite());
	}
}
