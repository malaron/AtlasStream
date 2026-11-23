package com.malaron.atlasstream.streaming;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NearestSpriteDemandTest {
	@Test
	void retainsNearestDistanceAcrossSectionsAndTouches() {
		NearestSpriteDemand<String> demand = new NearestSpriteDemand<>();
		Set<String> needed = demand.sprites();
		demand.record("stone", 20.0);
		demand.record("stone", 30.0);
		demand.record("glass", 12.0);
		demand.record("stone", 5.0);
		demand.record("glass", 0.0);

		assertEquals(Set.of("stone", "glass"), needed);
		assertEquals(Map.of("stone", 5.0, "glass", 0.0), demand.distances());
	}

	@Test
	void matchesMinimumDistanceForRepeatedSectionDemand() {
		NearestSpriteDemand<Integer> demand = new NearestSpriteDemand<>();
		Map<Integer, Double> expected = new HashMap<>();
		Random random = new Random(42);
		for (int i = 0; i < 10_000; i++) {
			int sprite = random.nextInt(300);
			double distance = random.nextInt(200);
			demand.record(sprite, distance);
			expected.merge(sprite, distance, Math::min);
		}
		assertEquals(expected, demand.distances());
		assertEquals(expected.keySet(), demand.sprites());
	}
}
