package com.malaron.atlasstream.client.mixin;

import java.util.List;

import com.malaron.atlasstream.client.atlas.AtlasRuntimeState;
import com.malaron.atlasstream.client.sodium.BlockModelMeshTracker;
import com.malaron.atlasstream.client.sodium.SodiumTerrainIntegration;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemFrameRenderer.class)
public abstract class ItemFrameSpriteUsageMixin {
	@WrapMethod(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V")
	private void atlasStream$trackFrameSprites(
		ItemFrameRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, Operation<Void> original
	) {
		List<BlockStateModelPart> parts = ((BlockModelRenderStateAccessor) state.frameModel).atlasStream$getModelParts();
		if (SodiumTerrainIntegration.isIrisShaderPackInUse() && parts != null) {
			for (BlockStateModelPart part : parts) {
				for (Direction direction : Direction.values()) {
					atlasStream$trackQuads(part.getQuads(direction));
				}
				atlasStream$trackQuads(part.getQuads(null));
			}
		}
		BlockModelMeshTracker.begin();
		try {
			original.call(state, poseStack, collector, camera);
		} finally {
			BlockModelMeshTracker.end();
		}
	}

	private static void atlasStream$trackQuads(List<BakedQuad> quads) {
		for (BakedQuad quad : quads) {
			AtlasRuntimeState.recordSpriteUsage(quad.materialInfo().sprite());
		}
	}
}
