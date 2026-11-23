package com.malaron.atlasstream.streaming;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

public final class VirtualTextureResidencyPriority {
	private static final Comparator<Entry> ORDER = Comparator.comparingDouble(Entry::distanceSquared)
		.thenComparingInt(entry -> entry.key().mipLevel())
		.thenComparingLong(entry -> entry.key().atlasGeneration())
		.thenComparingInt(entry -> entry.key().tileX())
		.thenComparingInt(entry -> entry.key().tileY());
	private final Map<VirtualTextureTileKey, Entry> entries = new HashMap<>();
	private final TreeSet<Entry> ordered = new TreeSet<>(ORDER);
	private long epoch;

	public void record(VirtualTextureTileKey key, double distanceSquared) {
		if (Double.isNaN(distanceSquared) || distanceSquared < 0) {
			throw new IllegalArgumentException("Distance must be non-negative");
		}
		Entry old = entries.get(key);
		if (old != null) {
			if (old.epoch() == epoch && distanceSquared >= old.distanceSquared()) {
				return;
			}
			ordered.remove(old);
			if (old.epoch() == epoch) {
				distanceSquared = Math.min(distanceSquared, old.distanceSquared());
			}
		}
		Entry entry = new Entry(key, distanceSquared, epoch);
		entries.put(key, entry);
		ordered.add(entry);
	}

	public void remove(VirtualTextureTileKey key) {
		Entry old = entries.remove(key);
		if (old != null) {
			ordered.remove(old);
		}
	}

	public void nextEpoch() {
		epoch++;
		for (Entry entry : entries.values().toArray(new Entry[0])) {
			if (entry.epoch() < epoch - 1 && Double.isFinite(entry.distanceSquared())) {
				ordered.remove(entry);
				Entry stale = new Entry(entry.key(), Double.POSITIVE_INFINITY, entry.epoch());
				entries.put(entry.key(), stale);
				ordered.add(stale);
			}
		}
	}

	public VirtualTextureTileKey victimFor(VirtualTextureTileKey key, double distanceSquared) {
		if (ordered.isEmpty()) {
			return null;
		}
		Entry worst = ordered.last();
		int distanceOrder = Double.compare(distanceSquared, worst.distanceSquared());
		if (distanceOrder < 0 || (distanceOrder == 0 && key.mipLevel() < worst.key().mipLevel())) {
			return worst.key();
		}
		return null;
	}

	private record Entry(VirtualTextureTileKey key, double distanceSquared, long epoch) {
	}
}
