package com.malaron.loadsoflods.client.mixin;

import com.malaron.loadsoflods.client.AtlasPreviewState;
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
	private void loadsOfLods$trackQuadSprite(
		com.mojang.blaze3d.vertex.PoseStack.Pose pose,
		BakedQuad quad,
		com.mojang.blaze3d.vertex.QuadInstance instance,
		CallbackInfo callback
	) {
		AtlasPreviewState.recordSpriteUsage(quad.materialInfo().sprite());
	}

	@Inject(method = "putBakedQuadWithGlint", at = @At("HEAD"), require = 0)
	private void loadsOfLods$trackGlintQuadSprite(
		com.mojang.blaze3d.vertex.PoseStack.Pose pose,
		BakedQuad quad,
		com.mojang.blaze3d.vertex.QuadInstance instance,
		com.mojang.blaze3d.vertex.PoseStack.Pose sheetedDecalPose,
		CallbackInfo callback
	) {
		AtlasPreviewState.recordSpriteUsage(quad.materialInfo().sprite());
	}
}
