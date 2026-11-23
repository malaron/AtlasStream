package com.malaron.atlasstream.client.mixin;

import com.malaron.atlasstream.client.atlas.AtlasRuntimeState;

import net.minecraft.client.renderer.texture.TextureAtlas;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pbr.loader.AtlasPBRLoader", remap = false)
public abstract class IrisAtlasPbrLoaderMixin {
	// Iris would build full-size normal/specular block atlases (tens of seconds and gigabytes of VRAM) that the
	// reduced preview immediately replaces, so skip that load while a reduced rebuild is pending.
	@Inject(
		method = "load(Lnet/minecraft/client/renderer/texture/TextureAtlas;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/irisshaders/iris/pbr/loader/PBRTextureLoader$PBRTextureConsumer;)V",
		at = @At("HEAD"),
		cancellable = true,
		remap = false,
		require = 0
	)
	private void atlasStream$skipFullSizeBlockPbr(
		TextureAtlas atlas,
		net.minecraft.server.packs.resources.ResourceManager resourceManager,
		@Coerce Object consumer,
		CallbackInfo callback
	) {
		if (AtlasRuntimeState.shouldSkipIrisBlockPbrLoad(atlas)) {
			callback.cancel();
		}
	}
}
