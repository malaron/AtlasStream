package com.malaron.atlasstream.client.mixin;

import com.malaron.atlasstream.client.sodium.BlockModelMeshTracker;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.entity.TntMinecartRenderer;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(TntMinecartRenderer.class)
public abstract class TntBlockSpriteUsageMixin {
	@WrapMethod(method = "submitWhiteSolidBlock")
	private static void atlasStream$trackTntMesh(
		BlockModelRenderState model, PoseStack pose, SubmitNodeCollector collector, int light,
		boolean white, int outline, Operation<Void> original
	) {
		BlockModelMeshTracker.begin();
		try {
			original.call(model, pose, collector, light, white, outline);
		} finally {
			BlockModelMeshTracker.end();
		}
	}
}
