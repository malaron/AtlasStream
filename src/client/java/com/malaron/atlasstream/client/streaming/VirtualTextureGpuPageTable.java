package com.malaron.atlasstream.client.streaming;

import com.malaron.atlasstream.streaming.VirtualTexturePageTable;
import com.malaron.atlasstream.streaming.VirtualTextureTileKey;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

public final class VirtualTextureGpuPageTable implements AutoCloseable {
	private final VirtualTexturePageTable pageTable;
	private GpuTexture texture;
	private GpuTextureView textureView;

	public VirtualTextureGpuPageTable(
		int atlasWidth,
		int atlasHeight,
		int tileSize,
		int mipLevelCount,
		long atlasGeneration
	) {
		this.pageTable = new VirtualTexturePageTable(
			atlasWidth,
			atlasHeight,
			tileSize,
			mipLevelCount,
			atlasGeneration
		);
		var device = RenderSystem.getDevice();
		GpuTexture createdTexture = device.createTexture(
			"AtlasStream virtual texture page table",
			GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING,
			GpuFormat.RGBA8_UNORM,
			pageTable.textureWidth(),
			pageTable.textureHeight(),
			1,
			1
		);
		GpuTextureView createdView = null;
		boolean initialized = false;
		try {
			createdView = device.createTextureView(createdTexture);
			NativeImage initialContents = new NativeImage(pageTable.textureWidth(), pageTable.textureHeight(), true);
			try (initialContents) {
				initialContents.fillRect(0, 0, initialContents.getWidth(), initialContents.getHeight(), 0);
				var encoder = device.createCommandEncoder();
				encoder.writeToTexture(createdTexture, initialContents);
				encoder.submit();
			}
			texture = createdTexture;
			textureView = createdView;
			initialized = true;
		} finally {
			if (!initialized) {
				if (createdView != null) {
					createdView.close();
				}
				createdTexture.close();
			}
		}
	}

	public synchronized GpuTextureView view() {
		ensureOpen();
		return textureView;
	}

	public synchronized int glId() {
		ensureOpen();
		try {
			return (Integer) texture.getClass().getMethod("iris$getGlId").invoke(texture);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			throw new IllegalStateException("Could not resolve virtual page table texture id", exception);
		}
	}

	public synchronized void setResident(VirtualTextureTileKey key, int arrayLayer) {
		ensureOpen();
		pageTable.setResident(key, arrayLayer);
		uploadEntry(key);
	}

	public synchronized boolean clear(VirtualTextureTileKey key) {
		ensureOpen();
		if (!pageTable.clear(key)) {
			return false;
		}
		uploadEntry(key);
		return true;
	}

	public synchronized void resetForGeneration(long generation) {
		ensureOpen();
		pageTable.resetForGeneration(generation);
		NativeImage clearedContents = new NativeImage(pageTable.textureWidth(), pageTable.textureHeight(), true);
		try (clearedContents) {
			clearedContents.fillRect(0, 0, clearedContents.getWidth(), clearedContents.getHeight(), 0);
			var encoder = RenderSystem.getDevice().createCommandEncoder();
			encoder.writeToTexture(texture, clearedContents);
			encoder.submit();
		}
	}

	public VirtualTexturePageTable layout() {
		return pageTable;
	}

	@Override
	public synchronized void close() {
		if (textureView != null) {
			textureView.close();
			textureView = null;
		}
		if (texture != null) {
			texture.close();
			texture = null;
		}
	}

	private void uploadEntry(VirtualTextureTileKey key) {
		NativeImage entry = new NativeImage(1, 1, true);
		try (entry) {
			entry.setPixel(0, 0, pageTable.pixelColorAt(pageTable.pixelX(key), pageTable.pixelY(key)));
			var encoder = RenderSystem.getDevice().createCommandEncoder();
			encoder.writeToTexture(texture, entry, 0, 0, pageTable.pixelX(key), pageTable.pixelY(key));
			encoder.submit();
		}
	}

	private void ensureOpen() {
		if (texture == null || textureView == null || texture.isClosed() || textureView.isClosed()) {
			throw new IllegalStateException("Virtual texture page table is closed");
		}
	}
}
