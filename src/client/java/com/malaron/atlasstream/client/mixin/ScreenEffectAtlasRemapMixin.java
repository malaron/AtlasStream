package com.malaron.atlasstream.client.mixin;

import com.malaron.atlasstream.client.atlas.AtlasRuntimeState;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectAtlasRemapMixin {
	@WrapMethod(
		method = "submitBlockSprite"
	)
	private static void atlasStream$remapBlockOverlay(
		Identifier atlasLocation,
		float u0,
		float v0,
		float u1,
		float v1,
		PoseStack poseStack,
		SubmitNodeCollector submitNodeCollector,
		int color,
		Operation<Void> original
	) {
		boolean useNear = AtlasRuntimeState.useNearAtlasForScreenEffect(atlasLocation, u0, v0, u1, v1);
		AtlasRuntimeState.setScreenEffectUsesNearAtlas(useNear);
		AtlasRuntimeState.logScreenOverlayRemap(atlasLocation, u0, v0, u1, v1);
		float[] first = AtlasRuntimeState.screenEffectUv(atlasLocation, u0, v0, false, useNear);
		float[] second = AtlasRuntimeState.screenEffectUv(atlasLocation, u1, v1, true, useNear);
		original.call(
			atlasLocation, first[0], first[1], second[0], second[1],
			poseStack, submitNodeCollector, color
		);
	}

	@WrapOperation(
		method = "buildFireQuad",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;buildSpriteQuad(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;FFFFFI)V"
		)
	)
	private static void atlasStream$remapFireOverlay(
		VertexConsumer builder,
		Matrix4f pose,
		TextureAtlasSprite sprite,
		float x0,
		float y0,
		float x1,
		float y1,
		float z,
		int color,
		Operation<Void> original
	) {
		boolean useNear = AtlasRuntimeState.useNearAtlasForScreenEffect(sprite);
		AtlasRuntimeState.setScreenEffectUsesNearAtlas(useNear);
		AtlasRuntimeState.logFireOverlayRemap(sprite);
		float[] first = AtlasRuntimeState.screenEffectUv(
			sprite, sprite.getU1(), sprite.getV1(), true
		);
		float[] second = AtlasRuntimeState.screenEffectUv(
			sprite, sprite.getU0(), sprite.getV0(), false
		);
		atlasStream$buildQuad(builder, pose, x0, y0, x1, y1, z,
			first[0], first[1], second[0], second[1], color);
	}

	@Invoker("buildQuad")
	private static void atlasStream$buildQuad(
		VertexConsumer builder, Matrix4f pose,
		float x0, float y0, float x1, float y1, float z,
		float u0, float v0, float u1, float v1, int color
	) {
		throw new AssertionError();
	}
}
