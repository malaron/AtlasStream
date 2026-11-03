package com.malaron.loadsoflods.client.mixin;

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
	List<TextureAtlasSprite> loadsOfLods$getSprites();

	@Accessor("animatedTexturesStates")
	List<SpriteContents.AnimationState> loadsOfLods$getAnimatedTextureStates();

	@Accessor("mipViews")
	GpuTextureView[] loadsOfLods$getMipViews();

	@Accessor("mipViews")
	@Mutable
	void loadsOfLods$setMipViews(GpuTextureView[] mipViews);
}
