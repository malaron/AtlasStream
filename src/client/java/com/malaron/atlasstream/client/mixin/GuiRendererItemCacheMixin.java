package com.malaron.atlasstream.client.mixin;

import java.util.Map;

import com.malaron.atlasstream.client.atlas.AtlasRuntimeState;

import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.pip.OversizedItemRenderer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderer.class)
public abstract class GuiRendererItemCacheMixin {
	@Shadow @Final private Map<Object, OversizedItemRenderer> oversizedItemRenderers;
	@Shadow private void invalidateItemAtlas() {
		throw new AssertionError();
	}
	@Unique private long atlasStream$itemAtlasRevision;

	@Inject(method = "render", at = @At("HEAD"))
	private void atlasStream$refreshItemTextures(CallbackInfo callback) {
		long revision = AtlasRuntimeState.guiItemAtlasRevision();
		if (revision != atlasStream$itemAtlasRevision) {
			invalidateItemAtlas();
			for (OversizedItemRenderer renderer : oversizedItemRenderers.values()) {
				renderer.invalidateTexture();
			}
			atlasStream$itemAtlasRevision = revision;
		}
	}
}
