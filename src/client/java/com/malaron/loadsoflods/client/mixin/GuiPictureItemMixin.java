package com.malaron.loadsoflods.client.mixin;

import com.malaron.loadsoflods.client.AtlasPreviewState;
import net.minecraft.client.gui.render.pip.OversizedItemRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PictureInPictureRenderer.class)
public abstract class GuiPictureItemMixin {
	@Inject(method = "prepare", at = @At("HEAD"))
	private void loadsOfLods$beginGuiItem(CallbackInfo callback) {
		if ((Object) this instanceof OversizedItemRenderer) {
			AtlasPreviewState.setGuiItemDraw(true);
		}
	}

	@Inject(method = "prepare", at = @At("RETURN"))
	private void loadsOfLods$endGuiItem(CallbackInfo callback) {
		if ((Object) this instanceof OversizedItemRenderer) {
			AtlasPreviewState.setGuiItemDraw(false);
		}
	}
}
