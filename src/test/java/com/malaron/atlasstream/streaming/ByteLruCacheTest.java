package com.malaron.atlasstream.streaming;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ByteLruCacheTest {
	@Test
	void evictsLeastRecentlyUsedEntriesToStayWithinByteBudget() {
		List<String> disposed = new ArrayList<>();
		ByteLruCache<String, String> cache = new ByteLruCache<>(10, disposed::add);
		cache.put("first", "first-value", 4);
		cache.put("second", "second-value", 4);

		assertEquals("first-value", cache.get("first"));
		cache.put("third", "third-value", 4);

		assertEquals("first-value", cache.get("first"));
		assertNull(cache.get("second"));
		assertEquals(List.of("second-value"), disposed);
		assertEquals(8, cache.usedBytes());
		assertTrue(cache.usedBytes() <= cache.maximumBytes());
	}

	@Test
	void replacementUpdatesWeightAndDisposesPreviousValue() {
		List<String> disposed = new ArrayList<>();
		ByteLruCache<String, String> cache = new ByteLruCache<>(10, disposed::add);
		cache.put("tile", "old", 4);
		cache.put("tile", "new", 7);

		assertEquals("new", cache.get("tile"));
		assertEquals(7, cache.usedBytes());
		assertEquals(List.of("old"), disposed);
	}

	@Test
	void rejectsOversizedEntryWithoutEvictingCachedValues() {
		List<String> disposed = new ArrayList<>();
		ByteLruCache<String, String> cache = new ByteLruCache<>(10, disposed::add);
		cache.put("existing", "value", 8);

		assertThrows(IllegalArgumentException.class, () -> cache.put("too-large", "value", 11));

		assertEquals("value", cache.get("existing"));
		assertEquals(8, cache.usedBytes());
		assertTrue(disposed.isEmpty());
	}

	@Test
	void invalidateAndClearDisposeEntriesAndResetUsage() {
		List<String> disposed = new ArrayList<>();
		ByteLruCache<String, String> cache = new ByteLruCache<>(10, disposed::add);
		cache.put("first", "first-value", 3);
		cache.put("second", "second-value", 5);

		assertTrue(cache.invalidate("first"));
		assertFalse(cache.invalidate("missing"));
		cache.clear();

		assertEquals(List.of("first-value", "second-value"), disposed);
		assertEquals(0, cache.size());
		assertEquals(0, cache.usedBytes());
	}

	@Test
	void canEvictLeastRecentlyUsedEntryToAcquireAnExternalResourceSlot() {
		List<String> disposed = new ArrayList<>();
		ByteLruCache<String, String> cache = new ByteLruCache<>(10, disposed::add);
		cache.put("first", "first-value", 3);
		cache.put("second", "second-value", 3);
		cache.get("first");

		assertTrue(cache.evictLeastRecentlyUsed());

		assertNull(cache.get("second"));
		assertEquals("first-value", cache.get("first"));
		assertEquals(List.of("second-value"), disposed);
		assertTrue(cache.evictLeastRecentlyUsed());
		assertFalse(cache.evictLeastRecentlyUsed());
	}

	@Test
	void checkingPresenceDoesNotChangeLeastRecentlyUsedEntry() {
		List<String> disposed = new ArrayList<>();
		ByteLruCache<String, String> cache = new ByteLruCache<>(10, disposed::add);
		cache.put("first", "first-value", 3);
		cache.put("second", "second-value", 3);

		assertTrue(cache.containsKey("first"));
		assertFalse(cache.containsKey("missing"));
		assertTrue(cache.evictLeastRecentlyUsed());

		assertNull(cache.get("first"));
		assertEquals("second-value", cache.get("second"));
		assertEquals(List.of("first-value"), disposed);
	}
}
