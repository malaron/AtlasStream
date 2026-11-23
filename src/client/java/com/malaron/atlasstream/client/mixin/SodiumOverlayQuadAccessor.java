package com.malaron.atlasstream.client.mixin;

import net.minecraft.core.Direction;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.model.QuadViewImpl", remap = false)
public interface SodiumOverlayQuadAccessor {
	@Invoker(value = "getX", remap = false)
	float atlasStream$x(int vertex);

	@Invoker(value = "getY", remap = false)
	float atlasStream$y(int vertex);

	@Invoker(value = "getZ", remap = false)
	float atlasStream$z(int vertex);

	@Invoker(value = "getLightFace", remap = false)
	Direction atlasStream$lightFace();
}
