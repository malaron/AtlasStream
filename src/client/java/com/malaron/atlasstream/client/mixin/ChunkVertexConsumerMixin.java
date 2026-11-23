package com.malaron.atlasstream.client.mixin;

import com.malaron.atlasstream.client.atlas.AtlasRuntimeState;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.BakedChunkModelBuilder", remap = false)
public abstract class ChunkVertexConsumerMixin {
	@Inject(method = "addSprite", at = @At("TAIL"), remap = false)
	private void atlasStream$trackUsedSprite(TextureAtlasSprite sprite, CallbackInfo callback) {
		AtlasRuntimeState.recordChunkSpriteUsage(sprite);
	}
}
