package com.malaron.loadsoflods.client.mixin;

import com.malaron.loadsoflods.client.AtlasPreviewState;
import net.minecraft.client.gui.render.GuiItemAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// GUI item icons are rendered once and cached, so they must not sample the near atlas, whose sprites stream in lazily.
@Mixin(GuiItemAtlas.class)
public abstract class GuiItemAtlasMixin {
	@Inject(method = "drawToSlot", at = @At("HEAD"))
	private void loadsOfLods$beginGuiItem(CallbackInfo callback) {
		AtlasPreviewState.setGuiItemDraw(true);
	}

	@Inject(method = "drawToSlot", at = @At("RETURN"))
	private void loadsOfLods$endGuiItem(CallbackInfo callback) {
		AtlasPreviewState.setGuiItemDraw(false);
	}
}
