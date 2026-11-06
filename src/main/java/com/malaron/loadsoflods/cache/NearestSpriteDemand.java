package com.malaron.loadsoflods.cache;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class NearestSpriteDemand<T> {
	private final Map<T, Double> distances = new HashMap<>();

	public void record(T sprite, double distance) {
		Double previous = distances.get(sprite);
		if (previous == null || distance < previous) {
			distances.put(sprite, distance);
		}
	}

	public Set<T> sprites() {
		return distances.keySet();
	}

	public Map<T, Double> distances() {
		return distances;
	}
}
