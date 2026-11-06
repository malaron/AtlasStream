package com.malaron.loadsoflods.client;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import com.malaron.loadsoflods.cache.ByteLruCache;
import com.malaron.loadsoflods.cache.VirtualTextureTileKey;
import com.malaron.loadsoflods.cache.VirtualTextureResidencyPriority;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

public final class VirtualTextureGpuCache implements AutoCloseable {
	private final VirtualTextureGpuPageTable pageTable;
	private final int tileTextureSize;
	private final int mipLevelCount;
	private final int gridWidth;
	private final int gridHeight;
	private GpuTexture texture;
	private GpuTextureView textureView;
	private final Queue<Integer> freeSlots = new ArrayDeque<>();
	private final ByteLruCache<VirtualTextureTileKey, ResidentTile> residentTiles;
	private final VirtualTextureResidencyPriority priority = new VirtualTextureResidencyPriority();
	private final long bytesPerTile;
	private final long capacityBytes;
	private boolean closed;
	private boolean closing;
	private long evictionCount;

	public VirtualTextureGpuCache(
		VirtualTextureGpuPageTable pageTable,
		int tileTextureSize,
		int mipLevelCount,
		long budgetBytes
	) {
		if (tileTextureSize <= 0 || mipLevelCount <= 0 || budgetBytes <= 0) {
			throw new IllegalArgumentException("Tile texture size, mip level count, and GPU budget must be positive");
		}
		if (pageTable == null) {
			throw new NullPointerException("pageTable");
		}
		this.pageTable = pageTable;
		this.tileTextureSize = tileTextureSize;
		this.mipLevelCount = mipLevelCount;
		this.bytesPerTile = calculateTileBytes(tileTextureSize, mipLevelCount);
		int maximumTextureSize = RenderSystem.getDevice()
			.getDeviceInfo()
			.limits()
			.maxTextureSizeForFormat(GpuFormat.RGBA8_UNORM);
		int maximumTilesPerSide = maximumTextureSize / tileTextureSize;
		int maximumTextureTiles = Math.multiplyExact(maximumTilesPerSide, maximumTilesPerSide);
		int tileCount = (int) Math.min(
			maximumTextureTiles,
			budgetBytes / bytesPerTile
		);
		if (tileCount <= 0) {
			throw new IllegalArgumentException("GPU cache budget cannot hold one virtual texture tile");
		}
		this.gridWidth = Math.max(1, (int) Math.sqrt(tileCount));
		this.gridHeight = tileCount / gridWidth;
		int slotCount = Math.multiplyExact(gridWidth, gridHeight);
		int textureWidth = Math.multiplyExact(gridWidth, tileTextureSize);
		int textureHeight = Math.multiplyExact(gridHeight, tileTextureSize);
		long allocationBytes = Math.multiplyExact(bytesPerTile, slotCount);
		long maximumAllocation = RenderSystem.getDevice().getDeviceInfo().limits().maxMemoryAllocationSize();
		if (allocationBytes > maximumAllocation) {
			throw new IllegalArgumentException(
				"Virtual texture cache requires " + allocationBytes + " bytes, exceeding the GPU allocation limit "
					+ maximumAllocation
			);
		}

		GpuTexture createdTexture = RenderSystem.getDevice().createTexture(
			"Loads of Lods streamed high-resolution tile cache",
			GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING,
			GpuFormat.RGBA8_UNORM,
			textureWidth,
			textureHeight,
			1,
			mipLevelCount
		);
		GpuTextureView createdView = null;
		try {
			createdView = RenderSystem.getDevice().createTextureView(createdTexture);
		} catch (RuntimeException | Error exception) {
			if (createdView != null) {
				createdView.close();
			}
			createdTexture.close();
			throw exception;
		}
		this.texture = createdTexture;
		this.textureView = createdView;
		this.capacityBytes = allocationBytes;
		for (int slot = 0; slot < slotCount; slot++) {
			freeSlots.add(slot);
		}
		this.residentTiles = new ByteLruCache<>(allocationBytes, this::disposeTile);
	}

	public synchronized int upload(VirtualTextureTileKey key, List<NativeImage> mipLevels) {
		return upload(key, mipLevels, Double.POSITIVE_INFINITY);
	}

	public synchronized int upload(VirtualTextureTileKey key, List<NativeImage> mipLevels, double distanceSquared) {
		ensureOpen();
		ResidentTile existing = residentTiles.get(key);
		if (existing != null) {
			return existing.slot();
		}
		if (key.mipLevel() >= pageTable.layout().mipLevelCount()) {
			throw new IllegalArgumentException("Tile key mip level is outside its page table");
		}
		pageTable.layout().encodedLayer(key);
		if (mipLevels.size() != mipLevelCount) {
			throw new IllegalArgumentException("Tile upload must include the complete cache mip chain");
		}
		long weight = validateMipChain(mipLevels);
		if (weight > residentTiles.maximumBytes()) {
			throw new IllegalArgumentException("Virtual texture tile exceeds the configured cache budget");
		}
		while (freeSlots.isEmpty()) {
			VirtualTextureTileKey victim = priority.victimFor(key, distanceSquared);
			if (victim == null) {
				return -1;
			}
			if (!residentTiles.invalidate(victim)) {
				throw new IllegalStateException("Virtual texture priority index is inconsistent");
			}
		}
		int slot = freeSlots.remove();
		int slotX = slot % gridWidth;
		int slotY = slot / gridWidth;
		boolean uploaded = false;
		try {
			var encoder = RenderSystem.getDevice().createCommandEncoder();
			for (int mip = 0; mip < mipLevels.size(); mip++) {
				int mipTileSize = Math.max(1, tileTextureSize >> mip);
				encoder.writeToTexture(
					texture,
					mipLevels.get(mip),
					mip,
					0,
					slotX * mipTileSize,
					slotY * mipTileSize
				);
			}
			encoder.submit();
			pageTable.setResident(key, slot);
			residentTiles.put(key, new ResidentTile(key, slot), weight);
			priority.record(key, distanceSquared);
			uploaded = true;
			return slot;
		} finally {
			if (!uploaded) {
				freeSlots.add(slot);
			}
		}
	}

	// Rewrites the pixels of an already resident tile in place; the page table is unchanged.
	public synchronized boolean update(VirtualTextureTileKey key, List<NativeImage> mipLevels) {
		ensureOpen();
		ResidentTile existing = residentTiles.get(key);
		if (existing == null) {
			return false;
		}
		if (mipLevels.size() != mipLevelCount) {
			throw new IllegalArgumentException("Tile upload must include the complete cache mip chain");
		}
		validateMipChain(mipLevels);
		int slotX = existing.slot() % gridWidth;
		int slotY = existing.slot() / gridWidth;
		var encoder = RenderSystem.getDevice().createCommandEncoder();
		for (int mip = 0; mip < mipLevels.size(); mip++) {
			int mipTileSize = Math.max(1, tileTextureSize >> mip);
			encoder.writeToTexture(texture, mipLevels.get(mip), mip, 0, slotX * mipTileSize, slotY * mipTileSize);
		}
		encoder.submit();
		return true;
	}

	public synchronized Integer get(VirtualTextureTileKey key) {
		ensureOpen();
		ResidentTile tile = residentTiles.get(key);
		return tile == null ? null : tile.slot();
	}

	public synchronized boolean isResident(VirtualTextureTileKey key) {
		ensureOpen();
		return residentTiles.containsKey(key);
	}

	public synchronized boolean noteDemand(VirtualTextureTileKey key, double distanceSquared) {
		ensureOpen();
		if (!residentTiles.containsKey(key)) {
			return false;
		}
		priority.record(key, distanceSquared);
		return true;
	}

	public synchronized boolean canAdmit(VirtualTextureTileKey key, double distanceSquared) {
		ensureOpen();
		return !freeSlots.isEmpty() || priority.victimFor(key, distanceSquared) != null;
	}

	public synchronized void nextDemandEpoch() {
		ensureOpen();
		priority.nextEpoch();
	}

	public synchronized GpuTextureView textureView() {
		ensureOpen();
		return textureView;
	}

	public int gridWidth() {
		return gridWidth;
	}

	public synchronized long usedBytes() {
		return residentTiles.usedBytes();
	}

	public long capacityBytes() {
		return capacityBytes;
	}

	public synchronized int size() {
		return residentTiles.size();
	}

	public synchronized long evictionCount() {
		return evictionCount;
	}

	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		try {
			closing = true;
			try {
				pageTable.resetForGeneration(pageTable.layout().atlasGeneration());
			} finally {
				residentTiles.clear();
			}
		} finally {
			try {
				textureView.close();
			} finally {
				texture.close();
				textureView = null;
				texture = null;
			}
		}
	}

	private void ensureOpen() {
		if (closed) {
			throw new IllegalStateException("Virtual texture GPU cache is closed");
		}
	}

	private long validateMipChain(List<NativeImage> mipLevels) {
		long totalBytes = 0;
		for (int mip = 0; mip < mipLevels.size(); mip++) {
			NativeImage image = mipLevels.get(mip);
			int expectedSize = Math.max(1, tileTextureSize >> mip);
			if (image.getWidth() != expectedSize || image.getHeight() != expectedSize) {
				throw new IllegalArgumentException("Tile mip level " + mip + " must be " + expectedSize + "x" + expectedSize);
			}
			totalBytes = Math.addExact(totalBytes, (long) expectedSize * expectedSize * 4);
		}
		return totalBytes;
	}

	private void disposeTile(ResidentTile tile) {
		if (closing) {
			return;
		}
		evictionCount++;
		priority.remove(tile.key());
		pageTable.clear(tile.key());
		freeSlots.add(tile.slot());
	}

	private static long calculateTileBytes(int tileTextureSize, int mipLevelCount) {
		long totalBytes = 0;
		for (int mip = 0; mip < mipLevelCount; mip++) {
			long size = Math.max(1, tileTextureSize >> mip);
			totalBytes = Math.addExact(totalBytes, Math.multiplyExact(size * size, 4));
		}
		return totalBytes;
	}

	private record ResidentTile(VirtualTextureTileKey key, int slot) {
	}
}
