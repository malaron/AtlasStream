package com.malaron.loadsoflods.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationBlendCacheTest {
	@Test
	void reusesBlendsAcrossBatchesAndClearsOnReload() {
		List<Object> disposed = new ArrayList<>();
		AnimationBlendCache<String, Object> cache = new AnimationBlendCache<>(10, disposed::add);
		AtomicInteger builds = new AtomicInteger();
		Object frame;
		try (var batch = cache.beginBatch()) {
			frame = batch.getOrCreate("frame", 4, () -> {
				builds.incrementAndGet();
				return new Object();
			});
			assertSame(frame, batch.getOrCreate("frame", 4, () -> { throw new AssertionError(); }));
			batch.commit();
		}
		try (var batch = cache.beginBatch()) {
			assertSame(frame, batch.getOrCreate("frame", 4, () -> { throw new AssertionError(); }));
			batch.commit();
		}
		assertEquals(1, builds.get());
		assertTrue(disposed.isEmpty());
		cache.clear();
		assertEquals(List.of(frame), disposed);
	}

	@Test
	void delaysEvictionUntilAfterUploadsAreSubmitted() {
		List<String> disposed = new ArrayList<>();
		AnimationBlendCache<String, String> cache = new AnimationBlendCache<>(4, disposed::add);
		try (var batch = cache.beginBatch()) {
			batch.getOrCreate("old", 4, () -> "old");
			batch.commit();
		}
		try (var batch = cache.beginBatch()) {
			assertEquals("old", batch.getOrCreate("old", 4, () -> { throw new AssertionError(); }));
			batch.getOrCreate("new", 4, () -> "new");
			assertTrue(disposed.isEmpty());
			assertThrows(IllegalStateException.class, cache::clear);
			assertThrows(IllegalStateException.class, cache::beginBatch);
			batch.commit();
			assertEquals(List.of("old"), disposed);
		}
		cache.close();
		assertEquals(List.of("old", "new"), disposed);
	}

	@Test
	void disposesAbortedAndOversizedBlendsWithoutCachingThem() {
		List<String> disposed = new ArrayList<>();
		AnimationBlendCache<String, String> cache = new AnimationBlendCache<>(4, disposed::add);
		try (var batch = cache.beginBatch()) {
			batch.getOrCreate("aborted", 2, () -> "aborted");
		}
		try (var batch = cache.beginBatch()) {
			batch.getOrCreate("large", 5, () -> "large");
			batch.commit();
			assertEquals(List.of("aborted"), disposed);
		}
		assertEquals(List.of("aborted", "large"), disposed);
		try (var batch = cache.beginBatch()) {
			assertEquals("rebuilt", batch.getOrCreate("large", 5, () -> "rebuilt"));
		}
		assertEquals(List.of("aborted", "large", "rebuilt"), disposed);
		cache.close();
	}
}
