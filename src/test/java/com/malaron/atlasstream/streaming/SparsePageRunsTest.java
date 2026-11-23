package com.malaron.atlasstream.streaming;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparsePageRunsTest {
	private static long key(int level, int x, int y) {
		return ((long) level << 48) | ((long) x << 24) | y;
	}

	@Test
	void combinesFullGridIntoOneRectangle() {
		List<Long> pages = new ArrayList<>();
		for (int x = 0; x < 9; x++) {
			for (int y = 0; y < 9; y++) {
				pages.add(key(0, x, y));
			}
		}
		var result = SparsePageRuns.rectangles(pages.stream().mapToLong(Long::longValue).toArray());
		assertEquals(List.of(new SparsePageRuns.Rectangle(0, 0, 0, 9, 9)), result);
	}

	@Test
	void preservesHolesLevelsAndDuplicateKeys() {
		long[] pages = {key(0, 0, 0), key(0, 2, 0), key(1, 0, 0), key(0, 0, 0)};
		var result = SparsePageRuns.rectangles(pages);
		assertEquals(3, result.size());
		assertEquals(Set.of(key(0, 0, 0), key(0, 2, 0), key(1, 0, 0)), expand(result));
	}

	@Test
	void onlyReleasesUnsharedPagesAndEachLayersCommittedSubset() {
		long first = key(0, 0, 0);
		long shared = key(0, 1, 0);
		long last = key(0, 2, 0);
		Map<Long, Integer> refs = new HashMap<>(Map.of(first, 1, shared, 2, last, 1));
		long[] released = SparsePageRuns.releaseReferences(refs, new long[] {first, shared, last});
		assertArrayEquals(new long[] {first, last}, released);
		assertEquals(Map.of(shared, 1), refs);
		Set<Long> layer = new HashSet<>(Set.of(first, shared));
		assertArrayEquals(new long[] {first}, SparsePageRuns.takeCommitted(layer, released));
		assertEquals(Set.of(shared), layer);
		assertThrows(IllegalStateException.class, () -> SparsePageRuns.releaseReferences(refs, new long[] {first}));
	}

	@Test
	void rectanglesExactlyCoverRandomSparsePagesWithoutOverlap() {
		Random random = new Random(42);
		for (int iteration = 0; iteration < 100; iteration++) {
			Set<Long> pages = new HashSet<>();
			for (int i = 0; i < 500; i++) {
				pages.add(key(random.nextInt(5), random.nextInt(20), random.nextInt(20)));
			}
			var rectangles = SparsePageRuns.rectangles(pages.stream().mapToLong(Long::longValue).toArray());
			assertEquals(pages, expand(rectangles));
		}
	}

	private static Set<Long> expand(List<SparsePageRuns.Rectangle> rectangles) {
		Set<Long> result = new HashSet<>();
		for (var rectangle : rectangles) {
			for (int y = rectangle.y(); y < rectangle.y() + rectangle.height(); y++) {
				for (int x = rectangle.x(); x < rectangle.x() + rectangle.width(); x++) {
					assertTrue(result.add(key(rectangle.level(), x, y)), "overlapping rectangles");
				}
			}
		}
		return result;
	}
}
