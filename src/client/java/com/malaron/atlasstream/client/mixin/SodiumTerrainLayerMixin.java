package com.malaron.atlasstream.client.mixin;

import com.malaron.atlasstream.client.sodium.TerrainLayerCorrections;
import com.malaron.atlasstream.render.OverlayQuadMatcher;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer", remap = false)
public abstract class SodiumTerrainLayerMixin {
	@ModifyExpressionValue(
		method = "processQuad",
		at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/model/MutableQuadViewImpl;getRenderType()Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;"),
		remap = false
	)
	private ChunkSectionLayer atlasStream$correctTerrainLayer(ChunkSectionLayer layer, @Coerce Object quadObject) {
		if (!TerrainLayerCorrections.hasActiveCorrections()) {
			return layer;
		}
		SodiumBlockRenderContextAccessor context = (SodiumBlockRenderContextAccessor) this;
		BlockState state = context.atlasStream$getBlockState();
		boolean forceOpaque = context.atlasStream$getForceOpaque();
		ChunkSectionLayer correctedLayer = TerrainLayerCorrections.correctTerrainLayer(state, layer, forceOpaque);
		if (!TerrainLayerCorrections.overlaysUseCutout() || correctedLayer != ChunkSectionLayer.TRANSLUCENT
				|| forceOpaque || !state.isSolidRender()) {
			return correctedLayer;
		}
		SodiumOverlayQuadAccessor quad = (SodiumOverlayQuadAccessor) quadObject;
		float minX = Math.min(Math.min(quad.atlasStream$x(0), quad.atlasStream$x(1)), Math.min(quad.atlasStream$x(2), quad.atlasStream$x(3)));
		float maxX = Math.max(Math.max(quad.atlasStream$x(0), quad.atlasStream$x(1)), Math.max(quad.atlasStream$x(2), quad.atlasStream$x(3)));
		float minZ = Math.min(Math.min(quad.atlasStream$z(0), quad.atlasStream$z(1)), Math.min(quad.atlasStream$z(2), quad.atlasStream$z(3)));
		float maxZ = Math.max(Math.max(quad.atlasStream$z(0), quad.atlasStream$z(1)), Math.max(quad.atlasStream$z(2), quad.atlasStream$z(3)));
		if (OverlayQuadMatcher.isOverlayQuad(true, quad.atlasStream$lightFace() == Direction.UP,
				quad.atlasStream$y(0), quad.atlasStream$y(1), quad.atlasStream$y(2), quad.atlasStream$y(3),
				minX, maxX, minZ, maxZ)) {
			TerrainLayerCorrections.reportMatchedOverlay(state, quad.atlasStream$y(0));
			return ChunkSectionLayer.CUTOUT;
		}
		return correctedLayer;
	}
}
