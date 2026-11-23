package com.malaron.atlasstream.streaming.compact;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactSectionResidencyTest {
	@Test
	void aNewMissingBlockDoesNotDemoteHybridSection() {
		Set<String> sprites = new HashSet<>(Set.of("grass", "stone"));
		Set<String> resident = new HashSet<>(sprites);
		assertTrue(CompactSectionResidency.resolved(true, false, sprites, resident::contains));
		sprites.add("daylight_sensor");
		assertFalse(CompactSectionResidency.resolved(true, false, sprites, resident::contains));
		assertTrue(CompactSectionResidency.resolved(true, true, sprites, resident::contains));
		assertTrue(resident.contains("grass"));
		assertFalse(resident.contains("daylight_sensor"));
		resident.add("daylight_sensor");
		assertTrue(CompactSectionResidency.resolved(true, false, sprites, resident::contains));
	}

	@Test
	void legacyNonCompactAndUnknownSectionsRemainResolved() {
		assertTrue(CompactSectionResidency.resolved(false, false, Set.of("missing"), ignored -> false));
		assertTrue(CompactSectionResidency.resolved(true, false, null, ignored -> false));
	}
}
