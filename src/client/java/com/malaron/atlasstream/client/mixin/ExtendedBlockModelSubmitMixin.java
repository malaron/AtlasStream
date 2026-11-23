package com.malaron.atlasstream.client.mixin;

import java.util.List;
import java.util.function.Function;

import com.malaron.atlasstream.client.atlas.AtlasRuntimeState;
import com.malaron.atlasstream.client.sodium.BlockModelMeshTracker;

import com.mojang.blaze3d.vertex.PoseStack;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.render.submit.ExtendedBlockModelSubmit;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ExtendedBlockModelSubmit.class, remap = false)
public abstract class ExtendedBlockModelSubmitMixin {
	@Inject(method = "<init>", at = @At("RETURN"))
	private void atlasStream$trackBlockMesh(
		PoseStack.Pose pose, Function<ChunkSectionLayer, RenderType> renderTypeFunction,
		List<BlockStateModelPart> parts, Mesh mesh, int[] tintLayers,
		int lightCoords, int overlayCoords, int tintColor, PoseStack.Pose sheetedDecalPose,
		CallbackInfo callback
	) {
		if (mesh != null && BlockModelMeshTracker.isSubmitting()) {
			AtlasRuntimeState.recordBlockModelMesh(mesh);
		}
	}
}
