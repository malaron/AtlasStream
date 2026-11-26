package com.malaron.atlasstream.client.streaming;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import com.malaron.atlasstream.streaming.ByteLruCache;
import com.malaron.atlasstream.streaming.FixedVirtualTextureCachePlan;
import com.malaron.atlasstream.streaming.VirtualTextureResidencyPriority;
import com.malaron.atlasstream.streaming.VirtualTextureTileKey;

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
	private final boolean pbrBundle;
	private GpuTexture texture;
	private GpuTexture normalTexture;
	private GpuTexture specularTexture;
	private GpuTextureView textureView;
	private GpuTextureView normalTextureView;
	private GpuTextureView specularTextureView;
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
		this(pageTable, tileTextureSize, mipLevelCount, budgetBytes, false);
	}

	public VirtualTextureGpuCache(
		VirtualTextureGpuPageTable pageTable,
		int tileTextureSize,
		int mipLevelCount,
		long budgetBytes,
		boolean pbrBundle
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
		this.pbrBundle = pbrBundle;
		this.bytesPerTile = calculateTileBytes(tileTextureSize, mipLevelCount) * (pbrBundle ? 3 : 1);
		int maximumTextureSize = RenderSystem.getDevice()
			.getDeviceInfo()
			.limits()
			.maxTextureSizeForFormat(GpuFormat.RGBA8_UNORM);
		FixedVirtualTextureCachePlan plan = FixedVirtualTextureCachePlan.create(
			tileTextureSize, mipLevelCount, budgetBytes, maximumTextureSize, pbrBundle
		);
		this.gridWidth = plan.gridWidth();
		this.gridHeight = plan.gridHeight();
		int slotCount = plan.slotCount();
		int textureWidth = plan.textureWidth();
		int textureHeight = plan.textureHeight();
		long allocationBytes = plan.capacityBytes();
		long maximumAllocation = RenderSystem.getDevice().getDeviceInfo().limits().maxMemoryAllocationSize();
		if (allocationBytes > maximumAllocation) {
			throw new IllegalArgumentException(
				"Virtual texture cache requires " + allocationBytes + " bytes, exceeding the GPU allocation limit "
					+ maximumAllocation
			);
		}

		GpuTexture createdTexture = RenderSystem.getDevice().createTexture(
			"AtlasStream streamed high-resolution tile cache",
			GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING,
			GpuFormat.RGBA8_UNORM,
			textureWidth,
			textureHeight,
			1,
			mipLevelCount
		);
		GpuTexture createdNormalTexture = null;
		GpuTexture createdSpecularTexture = null;
		GpuTextureView createdView = null;
		GpuTextureView createdNormalView = null;
		GpuTextureView createdSpecularView = null;
		try {
			createdView = RenderSystem.getDevice().createTextureView(createdTexture);
			if (pbrBundle) {
				createdNormalTexture = createCacheTexture("normal", textureWidth, textureHeight, mipLevelCount);
				createdSpecularTexture = createCacheTexture("specular", textureWidth, textureHeight, mipLevelCount);
				createdNormalView = RenderSystem.getDevice().createTextureView(createdNormalTexture);
				createdSpecularView = RenderSystem.getDevice().createTextureView(createdSpecularTexture);
			}
		} catch (RuntimeException | Error exception) {
			closeQuietly(createdView);
			closeQuietly(createdNormalView);
			closeQuietly(createdSpecularView);
			closeQuietly(createdNormalTexture);
			closeQuietly(createdSpecularTexture);
			createdTexture.close();
			throw exception;
		}
		this.texture = createdTexture;
		this.normalTexture = createdNormalTexture;
		this.specularTexture = createdSpecularTexture;
		this.textureView = createdView;
		this.normalTextureView = createdNormalView;
		this.specularTextureView = createdSpecularView;
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
		if (pbrBundle) {
			throw new IllegalStateException("PBR cache uploads must provide normal and specular mip chains");
		}
		return upload(key, mipLevels, null, null, distanceSquared);
	}

	public synchronized int upload(
		VirtualTextureTileKey key,
		List<NativeImage> albedo,
		List<NativeImage> normal,
		List<NativeImage> specular,
		double distanceSquared
	) {
		ensureOpen();
		ResidentTile existing = residentTiles.get(key);
		if (existing != null) {
			return existing.slot();
		}
		if (key.mipLevel() >= pageTable.layout().mipLevelCount()) {
			throw new IllegalArgumentException("Tile key mip level is outside its page table");
		}
		pageTable.layout().encodedLayer(key);
		if (albedo.size() != mipLevelCount
				|| (pbrBundle && (normal.size() != mipLevelCount || specular.size() != mipLevelCount))
				|| (!pbrBundle && (normal != null || specular != null))) {
			throw new IllegalArgumentException("Tile upload must include the complete cache mip chain");
		}
		long weight = validateMipChain(albedo);
		if (pbrBundle) {
			weight = Math.addExact(weight, validateMipChain(normal));
			weight = Math.addExact(weight, validateMipChain(specular));
		}
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
			for (int mip = 0; mip < albedo.size(); mip++) {
				int mipTileSize = Math.max(1, tileTextureSize >> mip);
				encoder.writeToTexture(
					texture,
					albedo.get(mip),
					mip,
					0,
					slotX * mipTileSize,
					slotY * mipTileSize
				);
				if (pbrBundle) {
					encoder.writeToTexture(
						normalTexture, normal.get(mip), mip, 0, slotX * mipTileSize, slotY * mipTileSize
					);
					encoder.writeToTexture(
						specularTexture, specular.get(mip), mip, 0, slotX * mipTileSize, slotY * mipTileSize
					);
				}
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
		if (pbrBundle) {
			throw new IllegalStateException("PBR cache updates must provide normal and specular mip chains");
		}
		return update(key, mipLevels, null, null);
	}

	public synchronized boolean update(
		VirtualTextureTileKey key,
		List<NativeImage> albedo,
		List<NativeImage> normal,
		List<NativeImage> specular
	) {
		ensureOpen();
		ResidentTile existing = residentTiles.get(key);
		if (existing == null) {
			return false;
		}
		if (albedo.size() != mipLevelCount
				|| (pbrBundle && (normal.size() != mipLevelCount || specular.size() != mipLevelCount))
				|| (!pbrBundle && (normal != null || specular != null))) {
			throw new IllegalArgumentException("Tile upload must include the complete cache mip chain");
		}
		validateMipChain(albedo);
		if (pbrBundle) {
			validateMipChain(normal);
			validateMipChain(specular);
		}
		int slotX = existing.slot() % gridWidth;
		int slotY = existing.slot() / gridWidth;
		var encoder = RenderSystem.getDevice().createCommandEncoder();
		for (int mip = 0; mip < albedo.size(); mip++) {
			int mipTileSize = Math.max(1, tileTextureSize >> mip);
			encoder.writeToTexture(texture, albedo.get(mip), mip, 0, slotX * mipTileSize, slotY * mipTileSize);
			if (pbrBundle) {
				encoder.writeToTexture(
					normalTexture, normal.get(mip), mip, 0, slotX * mipTileSize, slotY * mipTileSize
				);
				encoder.writeToTexture(
					specularTexture, specular.get(mip), mip, 0, slotX * mipTileSize, slotY * mipTileSize
				);
			}
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

	public synchronized GpuTextureView normalTextureView() {
		ensureOpen();
		if (!pbrBundle) {
			throw new IllegalStateException("Virtual texture GPU cache was not allocated with PBR surfaces");
		}
		return normalTextureView;
	}

	public synchronized GpuTextureView specularTextureView() {
		ensureOpen();
		if (!pbrBundle) {
			throw new IllegalStateException("Virtual texture GPU cache was not allocated with PBR surfaces");
		}
		return specularTextureView;
	}

	public synchronized int textureGlId() {
		ensureOpen();
		return glId(texture);
	}

	public synchronized int normalTextureGlId() {
		ensureOpen();
		return glId(normalTexture);
	}

	public synchronized int specularTextureGlId() {
		ensureOpen();
		return glId(specularTexture);
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
				closeQuietly(textureView);
				closeQuietly(normalTextureView);
				closeQuietly(specularTextureView);
			} finally {
				texture.close();
				closeQuietly(normalTexture);
				closeQuietly(specularTexture);
				textureView = null;
				normalTextureView = null;
				specularTextureView = null;
				texture = null;
				normalTexture = null;
				specularTexture = null;
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

	private static GpuTexture createCacheTexture(
		String label,
		int width,
		int height,
		int mipLevelCount
	) {
		return RenderSystem.getDevice().createTexture(
			"AtlasStream streamed high-resolution " + label + " tile cache",
			GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING,
			GpuFormat.RGBA8_UNORM,
			width,
			height,
			1,
			mipLevelCount
		);
	}

	private static void closeQuietly(GpuTextureView resource) {
		if (resource != null) {
			resource.close();
		}
	}

	private static void closeQuietly(GpuTexture resource) {
		if (resource != null) {
			resource.close();
		}
	}

	private static int glId(GpuTexture texture) {
		if (texture == null) {
			return 0;
		}
		try {
			return (Integer) texture.getClass().getMethod("iris$getGlId").invoke(texture);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			throw new IllegalStateException("Could not resolve virtual tile cache texture id", exception);
		}
	}

	private record ResidentTile(VirtualTextureTileKey key, int slot) {
	}
}
