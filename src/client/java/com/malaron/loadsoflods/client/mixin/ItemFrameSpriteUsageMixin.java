package com.malaron.loadsoflods.client.mixin;

import com.malaron.loadsoflods.client.AtlasPreviewState;
import com.malaron.loadsoflods.client.ItemFrameSpriteTracker;
import com.malaron.loadsoflods.client.SodiumLodBridge;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
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
	private void loadsOfLods$trackFrameSprites(
		ItemFrameRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, Operation<Void> original
	) {
		List<BlockStateModelPart> parts = ((BlockModelRenderStateAccessor) state.frameModel).loadsOfLods$getModelParts();
		if (SodiumLodBridge.isIrisShaderPackInUse() && parts != null) {
			for (BlockStateModelPart part : parts) {
				for (Direction direction : Direction.values()) {
					loadsOfLods$trackQuads(part.getQuads(direction));
				}
				loadsOfLods$trackQuads(part.getQuads(null));
			}
		}
		ItemFrameSpriteTracker.beginFrame();
		try {
			original.call(state, poseStack, collector, camera);
		} finally {
			ItemFrameSpriteTracker.endFrame();
		}
	}

	private static void loadsOfLods$trackQuads(List<BakedQuad> quads) {
		for (BakedQuad quad : quads) {
			AtlasPreviewState.recordSpriteUsage(quad.materialInfo().sprite());
		}
	}
}
