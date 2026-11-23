package com.malaron.atlasstream.client.mixin;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.renderer.texture.SpriteContents;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
	@Accessor("byMipLevel")
	NativeImage[] atlasStream$getMipImages();

	@Accessor("mipmapStrategy")
	MipmapStrategy atlasStream$getMipmapStrategy();
}
