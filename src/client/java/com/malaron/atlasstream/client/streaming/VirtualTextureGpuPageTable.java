package com.malaron.atlasstream.client.streaming;

import com.malaron.atlasstream.streaming.VirtualTexturePageTable;
import com.malaron.atlasstream.streaming.VirtualTextureTileKey;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

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

	public synchronized int debugReadPixel(VirtualTextureTileKey key) {
		ensureOpen();
		int previousFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		int framebuffer = GL30.glGenFramebuffers();
		java.nio.ByteBuffer pixel = MemoryUtil.memAlloc(4);
		try {
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
			GL30.glFramebufferTexture2D(
				GL30.GL_READ_FRAMEBUFFER,
				GL30.GL_COLOR_ATTACHMENT0,
				GL11.GL_TEXTURE_2D,
				glId(),
				0
			);
			if (GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER)
					!= GL30.GL_FRAMEBUFFER_COMPLETE) {
				throw new IllegalStateException("Virtual page table readback framebuffer is incomplete");
			}
			GL11.glReadPixels(
				pageTable.pixelX(key), pageTable.pixelY(key), 1, 1,
				GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel
			);
			return (pixel.get(3) & 0xFF) << 24
				| (pixel.get(0) & 0xFF) << 16
				| (pixel.get(1) & 0xFF) << 8
				| (pixel.get(2) & 0xFF);
		} finally {
			MemoryUtil.memFree(pixel);
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousFramebuffer);
			GL30.glDeleteFramebuffers(framebuffer);
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
