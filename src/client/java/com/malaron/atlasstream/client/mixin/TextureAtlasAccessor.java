package com.malaron.atlasstream.client.mixin;

import java.util.List;

import com.mojang.renderpearl.api.textures.GpuTextureView;

import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TextureAtlas.class)
public interface TextureAtlasAccessor {
	@Accessor("sprites")
	List<TextureAtlasSprite> atlasStream$getSprites();

	@Accessor("animatedTexturesStates")
	List<SpriteContents.AnimationState> atlasStream$getAnimatedTextureStates();

	@Accessor("mipViews")
	GpuTextureView[] atlasStream$getMipViews();

	@Accessor("mipViews")
	@Mutable
	void atlasStream$setMipViews(GpuTextureView[] mipViews);

	@Accessor("width")
	int atlasStream$getWidth();

	@Accessor("height")
	int atlasStream$getHeight();

	@Accessor("mipLevelCount")
	int atlasStream$getMipLevelCount();
}
