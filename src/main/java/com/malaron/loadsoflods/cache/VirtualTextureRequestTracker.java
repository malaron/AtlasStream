package com.malaron.loadsoflods.cache;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.LongAdder;

public final class VirtualTextureRequestTracker {
	private static final int MAX_PENDING_QUADS = 4096;
	private static final Comparator<UvQuad> UV_QUAD_PRIORITY = Comparator
		.comparingDouble(UvQuad::distanceSquared)
		.thenComparingLong(UvQuad::atlasGeneration)
		.thenComparingDouble(UvQuad::minimumU)
		.thenComparingDouble(UvQuad::minimumV)
		.thenComparingDouble(UvQuad::maximumU)
		.thenComparingDouble(UvQuad::maximumV);
	private final NavigableMap<UvQuad, UvQuad> pendingQuads = new TreeMap<>(UV_QUAD_PRIORITY);
	private final Map<UvQuadIdentity, UvQuad> uniquePendingQuads = new HashMap<>();
	private final LongAdder completedQuadCount = new LongAdder();
	private final LongAdder capturedSpriteUseCount = new LongAdder();

	public void recordSpriteUsage(
		double minimumU,
		double minimumV,
		double maximumU,
		double maximumV,
		long atlasGeneration
	) {
		recordSpriteUsage(minimumU, minimumV, maximumU, maximumV, atlasGeneration, Double.POSITIVE_INFINITY);
	}

	public void recordSpriteUsage(
		double minimumU,
		double minimumV,
		double maximumU,
		double maximumV,
		long atlasGeneration,
		double distanceSquared
	) {
		capturedSpriteUseCount.increment();
		if (!Double.isFinite(minimumU) || !Double.isFinite(minimumV)
				|| !Double.isFinite(maximumU) || !Double.isFinite(maximumV)
				|| minimumU > maximumU || minimumV > maximumV || atlasGeneration < 0
				|| Double.isNaN(distanceSquared) || distanceSquared < 0.0) {
			return;
		}
		enqueue(new UvQuad(minimumU, minimumV, maximumU, maximumV, atlasGeneration, distanceSquared));
		completedQuadCount.increment();
	}

	public synchronized List<UvQuad> drain(int maximumQuads, long atlasGeneration) {
		if (maximumQuads <= 0) {
			throw new IllegalArgumentException("Maximum quad count must be positive");
		}
		List<UvQuad> result = new ArrayList<>(Math.min(maximumQuads, pendingQuads.size()));
		while (result.size() < maximumQuads && !pendingQuads.isEmpty()) {
			UvQuad quad = pendingQuads.pollFirstEntry().getValue();
			uniquePendingQuads.remove(quad.identity());
			if (quad.atlasGeneration() == atlasGeneration) {
				result.add(quad);
			}
		}
		return result;
	}

	public synchronized UvQuad poll(long atlasGeneration) {
		while (!pendingQuads.isEmpty()) {
			UvQuad quad = pendingQuads.pollFirstEntry().getValue();
			uniquePendingQuads.remove(quad.identity());
			if (quad.atlasGeneration() == atlasGeneration) {
				return quad;
			}
		}
		return null;
	}

	public void offer(UvQuad quad) {
		enqueue(Objects.requireNonNull(quad, "quad"));
	}

	public synchronized void clear() {
		pendingQuads.clear();
		uniquePendingQuads.clear();
	}

	public synchronized int pendingCount() {
		return pendingQuads.size();
	}

	public long completedQuadCount() {
		return completedQuadCount.sum();
	}

	public long capturedSpriteUseCount() {
		return capturedSpriteUseCount.sum();
	}

	public static TileCursor cursor(UvQuad quad, VirtualTextureLayout layout, int mipLevelCount) {
		return new TileCursor(quad, layout, mipLevelCount);
	}

	private synchronized void enqueue(UvQuad quad) {
		UvQuadIdentity identity = quad.identity();
		UvQuad existing = uniquePendingQuads.get(identity);
		if (existing != null) {
			if (quad.distanceSquared() >= existing.distanceSquared()) {
				return;
			}
			pendingQuads.remove(existing);
		} else if (pendingQuads.size() >= MAX_PENDING_QUADS) {
			UvQuad lowestPriority = pendingQuads.lastKey();
			if (UV_QUAD_PRIORITY.compare(quad, lowestPriority) >= 0) {
				return;
			}
			pendingQuads.pollLastEntry();
			uniquePendingQuads.remove(lowestPriority.identity());
		}
		pendingQuads.put(quad, quad);
		uniquePendingQuads.put(identity, quad);
	}

	public record UvQuad(
		double minimumU,
		double minimumV,
		double maximumU,
		double maximumV,
		long atlasGeneration,
		double distanceSquared
	) {
		public UvQuad(
			double minimumU,
			double minimumV,
			double maximumU,
			double maximumV,
			long atlasGeneration
		) {
			this(minimumU, minimumV, maximumU, maximumV, atlasGeneration, Double.POSITIVE_INFINITY);
		}

		public UvQuad {
			if (!Double.isFinite(minimumU) || !Double.isFinite(minimumV)
					|| !Double.isFinite(maximumU) || !Double.isFinite(maximumV)
					|| minimumU > maximumU || minimumV > maximumV || atlasGeneration < 0
					|| Double.isNaN(distanceSquared) || distanceSquared < 0.0) {
					throw new IllegalArgumentException("Virtual texture UV quad data must be valid");
			}
		}

		private UvQuadIdentity identity() {
			return new UvQuadIdentity(minimumU, minimumV, maximumU, maximumV, atlasGeneration);
		}
	}

	private record UvQuadIdentity(
		double minimumU,
		double minimumV,
		double maximumU,
		double maximumV,
		long atlasGeneration
	) {
	}

	public static final class TileCursor {
		private final UvQuad quad;
		private final VirtualTextureLayout layout;
		private final int mipLevelCount;
		private int mipLevel;
		private int tileX;
		private int tileY;
		private int minimumTileX;
		private int maximumTileX;
		private int maximumTileY;
		private boolean complete;

		private TileCursor(UvQuad quad, VirtualTextureLayout layout, int mipLevelCount) {
			this.quad = Objects.requireNonNull(quad, "quad");
			this.layout = Objects.requireNonNull(layout, "layout");
			if (mipLevelCount <= 0 || mipLevelCount > Integer.SIZE - 1) {
				throw new IllegalArgumentException("Virtual texture mip count must be positive and supported");
			}
			this.mipLevelCount = mipLevelCount;
			initializeMipBounds();
		}

		public VirtualTextureTileKey next() {
			if (complete) {
				return null;
			}
			VirtualTextureTileKey result = new VirtualTextureTileKey(
				quad.atlasGeneration(),
				mipLevel,
				tileX,
				tileY
			);
			advance();
			return result;
		}

		public boolean isComplete() {
			return complete;
		}

		private void advance() {
			if (tileX < maximumTileX) {
				tileX++;
				return;
			}
			if (tileY < maximumTileY) {
				tileX = minimumTileX();
				tileY++;
				return;
			}
			mipLevel++;
			if (mipLevel >= mipLevelCount) {
				complete = true;
			} else {
				initializeMipBounds();
			}
		}

		private void initializeMipBounds() {
			VirtualTextureTileKey minimum = layout.address(
				quad.atlasGeneration(),
				mipLevel,
				quad.minimumU(),
				quad.minimumV()
			).key();
			VirtualTextureTileKey maximum = layout.address(
				quad.atlasGeneration(),
				mipLevel,
				quad.maximumU(),
				quad.maximumV()
			).key();
			minimumTileX = minimum.tileX();
			tileX = minimumTileX;
			tileY = minimum.tileY();
			maximumTileX = maximum.tileX();
			maximumTileY = maximum.tileY();
		}

		private int minimumTileX() {
			return minimumTileX;
		}
	}
}
