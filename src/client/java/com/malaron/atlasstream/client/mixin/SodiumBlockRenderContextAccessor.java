package com.malaron.atlasstream.client.mixin;

import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext", remap = false)
public interface SodiumBlockRenderContextAccessor {
	@Accessor(value = "state", remap = false)
	BlockState atlasStream$getBlockState();

	@Accessor(value = "forceOpaque", remap = false)
	boolean atlasStream$getForceOpaque();
}
