package com.malaron.loadsoflods.client;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlTexture;

// Wraps a GL texture created with sparse storage so Sodium and Iris can bind it like any other texture.
// It is never an FBO attachment, so no frame buffer cache is needed.
final class SparseGlTexture extends GlTexture {
	SparseGlTexture(String label, int width, int height, int mipLevels, int glId) {
		super(
			GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
			label,
			GpuFormat.RGBA8_UNORM,
			width,
			height,
			1,
			mipLevels,
			glId,
			null
		);
	}
}
