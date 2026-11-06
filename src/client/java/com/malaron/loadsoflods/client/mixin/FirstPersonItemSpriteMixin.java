package com.malaron.loadsoflods.client.mixin;

import com.malaron.loadsoflods.client.AtlasPreviewState;
import com.malaron.loadsoflods.client.ItemStreamDiagnostics;
import com.malaron.loadsoflods.client.SodiumLodBridge;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ItemFeatureRenderer.class)
public abstract class FirstPersonItemSpriteMixin {
	@Redirect(
		method = "prepareMainSubmit",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/model/geometry/BakedQuad;materialInfo()Lnet/minecraft/client/resources/model/geometry/BakedQuad$MaterialInfo;")
	)
	private BakedQuad.MaterialInfo loadsOfLods$trackFirstPersonSprite(
		BakedQuad quad,
		ItemFeatureRenderer.Submit submit
	) {
		BakedQuad.MaterialInfo material = quad.materialInfo();
		if (submit.displayContext().firstPerson()) {
			ItemStreamDiagnostics.recordSprite(material.sprite());
			if (AtlasPreviewState.useVirtualTextureForItemDraw() && !SodiumLodBridge.isIrisShaderPackInUse()) {
				AtlasPreviewState.recordSpriteUsage(material.sprite());
			}
		}
		return material;
	}
}
