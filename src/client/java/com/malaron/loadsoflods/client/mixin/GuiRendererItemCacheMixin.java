package com.malaron.loadsoflods.client.mixin;

import com.malaron.loadsoflods.client.AtlasPreviewState;
import java.util.Map;
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
	@Unique private long loadsOfLods$itemAtlasRevision;

	@Inject(method = "render", at = @At("HEAD"))
	private void loadsOfLods$refreshItemTextures(CallbackInfo callback) {
		long revision = AtlasPreviewState.guiItemAtlasRevision();
		if (revision != loadsOfLods$itemAtlasRevision) {
			invalidateItemAtlas();
			for (OversizedItemRenderer renderer : oversizedItemRenderers.values()) {
				renderer.invalidateTexture();
			}
			loadsOfLods$itemAtlasRevision = revision;
		}
	}
}
