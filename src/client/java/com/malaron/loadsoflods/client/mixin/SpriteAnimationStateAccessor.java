package com.malaron.loadsoflods.client.mixin;

import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SpriteContents.AnimationState.class)
public interface SpriteAnimationStateAccessor {
	@Accessor("frame")
	int loadsOfLods$getFrame();

	@Accessor("subFrame")
	int loadsOfLods$getSubFrame();
}
