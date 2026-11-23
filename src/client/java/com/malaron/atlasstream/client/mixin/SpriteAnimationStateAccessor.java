package com.malaron.atlasstream.client.mixin;

import net.minecraft.client.renderer.texture.SpriteContents;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SpriteContents.AnimationState.class)
public interface SpriteAnimationStateAccessor {
	@Accessor("frame")
	int atlasStream$getFrame();

	@Accessor("subFrame")
	int atlasStream$getSubFrame();
}
