package com.malaron.atlasstream.client.mixin;

import com.malaron.atlasstream.client.atlas.AtlasRuntimeState;

import net.minecraft.client.gui.render.pip.OversizedItemRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PictureInPictureRenderer.class)
public abstract class GuiPictureItemMixin {
	@Inject(method = "prepare", at = @At("HEAD"))
	private void atlasStream$beginGuiItem(CallbackInfo callback) {
		if ((Object) this instanceof OversizedItemRenderer) {
			AtlasRuntimeState.setGuiItemDraw(true);
		}
	}

	@Inject(method = "prepare", at = @At("RETURN"))
	private void atlasStream$endGuiItem(CallbackInfo callback) {
		if ((Object) this instanceof OversizedItemRenderer) {
			AtlasRuntimeState.setGuiItemDraw(false);
		}
	}
}
