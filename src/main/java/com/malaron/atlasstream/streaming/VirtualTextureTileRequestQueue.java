package com.malaron.atlasstream.streaming;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public final class VirtualTextureTileRequestQueue {
	private final int maximumSize;
	private final TreeMap<RequestPriority, ArrayDeque<VirtualTextureTileKey>> requestsByPriority =
		new TreeMap<>(Comparator.comparingDouble(RequestPriority::distanceSquared)
			.thenComparingInt(RequestPriority::mipLevel));
	private final Map<VirtualTextureTileKey, RequestPriority> uniqueRequests = new HashMap<>();

	public VirtualTextureTileRequestQueue(int maximumSize) {
		if (maximumSize <= 0) {
			throw new IllegalArgumentException("Maximum tile request count must be positive");
		}
		this.maximumSize = maximumSize;
	}

	public boolean offer(VirtualTextureTileKey key) {
		return offer(key, Double.POSITIVE_INFINITY);
	}

	public boolean offer(VirtualTextureTileKey key, double distanceSquared) {
		if (Double.isNaN(distanceSquared) || distanceSquared < 0.0) {
			throw new IllegalArgumentException("Tile request distance must be non-negative");
		}
		RequestPriority priority = new RequestPriority(key.mipLevel(), distanceSquared);
		RequestPriority existingPriority = uniqueRequests.get(key);
		if (existingPriority != null) {
			if (compare(priority, existingPriority) >= 0) {
				return false;
			}
			remove(key, existingPriority);
		}
		if (uniqueRequests.size() >= maximumSize) {
			Map.Entry<RequestPriority, ArrayDeque<VirtualTextureTileKey>> lowestPriority =
				requestsByPriority.lastEntry();
			if (lowestPriority == null || compare(priority, lowestPriority.getKey()) >= 0) {
				return false;
			}
			VirtualTextureTileKey evicted = lowestPriority.getValue().removeLast();
			uniqueRequests.remove(evicted);
			if (lowestPriority.getValue().isEmpty()) {
				requestsByPriority.remove(lowestPriority.getKey());
			}
		}
		requestsByPriority.computeIfAbsent(priority, ignored -> new ArrayDeque<>()).addLast(key);
		uniqueRequests.put(key, priority);
		return true;
	}

	public VirtualTextureTileKey poll() {
		Request request = pollRequest();
		return request == null ? null : request.key();
	}

	public Request pollRequest() {
		Map.Entry<RequestPriority, ArrayDeque<VirtualTextureTileKey>> highestPriority =
			requestsByPriority.firstEntry();
		if (highestPriority == null) {
			return null;
		}
		VirtualTextureTileKey key = highestPriority.getValue().removeFirst();
		uniqueRequests.remove(key);
		if (highestPriority.getValue().isEmpty()) {
			requestsByPriority.remove(highestPriority.getKey());
		}
		return new Request(key, highestPriority.getKey().distanceSquared());
	}

	public record Request(VirtualTextureTileKey key, double distanceSquared) {
	}

	public boolean contains(VirtualTextureTileKey key) {
		return uniqueRequests.containsKey(key);
	}

	public int size() {
		return uniqueRequests.size();
	}

	public void clear() {
		requestsByPriority.clear();
		uniqueRequests.clear();
	}

	private void remove(VirtualTextureTileKey key, RequestPriority priority) {
		ArrayDeque<VirtualTextureTileKey> requests = requestsByPriority.get(priority);
		if (requests == null || !requests.remove(key)) {
			throw new IllegalStateException("Tile request priority index is inconsistent");
		}
		if (requests.isEmpty()) {
			requestsByPriority.remove(priority);
		}
		uniqueRequests.remove(key);
	}

	private static int compare(RequestPriority left, RequestPriority right) {
		int distanceOrder = Double.compare(left.distanceSquared(), right.distanceSquared());
		return distanceOrder != 0 ? distanceOrder : Integer.compare(left.mipLevel(), right.mipLevel());
	}

	private record RequestPriority(int mipLevel, double distanceSquared) {
	}
}
