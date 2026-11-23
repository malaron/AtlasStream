package com.malaron.atlasstream.client.mixin;

import java.util.List;

import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlockModelRenderState.class)
public interface BlockModelRenderStateAccessor {
	@Accessor("modelParts")
	List<BlockStateModelPart> atlasStream$getModelParts();
}
