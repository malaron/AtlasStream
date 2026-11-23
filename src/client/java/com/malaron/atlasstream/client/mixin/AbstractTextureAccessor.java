package com.malaron.atlasstream.client.mixin;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import net.minecraft.client.renderer.texture.AbstractTexture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractTexture.class)
public interface AbstractTextureAccessor {
	@Accessor("texture")
	GpuTexture atlasStream$getTexture();

	@Accessor("texture")
	@Mutable
	void atlasStream$setTexture(GpuTexture texture);

	@Accessor("textureView")
	GpuTextureView atlasStream$getTextureView();

	@Accessor("textureView")
	@Mutable
	void atlasStream$setTextureView(GpuTextureView textureView);
}
