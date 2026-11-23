package com.malaron.atlasstream.client.mixin;

import com.malaron.atlasstream.client.atlas.AtlasRuntimeState;
import com.malaron.atlasstream.client.sodium.SodiumTerrainIntegration;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockModelFeatureRenderer.class)
public abstract class BlockModelSpriteUsageMixin {
	@Inject(method = "putQuad", at = @At("HEAD"))
	private static void atlasStream$trackBlockModelSprite(
		PoseStack.Pose pose,
		BakedQuad quad,
		QuadInstance instance,
		int baseTintColor,
		int[] tintLayers,
		VertexConsumer buffer,
		CallbackInfo callback
	) {
		if (SodiumTerrainIntegration.isIrisShaderPackInUse()) {
			AtlasRuntimeState.recordSpriteUsage(quad.materialInfo().sprite());
		}
	}
}
