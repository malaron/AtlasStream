package com.malaron.atlasstream.streaming;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SparsePageRuns {
	private static final long COORD_MASK = 0xFFFFFFL;

	private SparsePageRuns() {
	}

	public record Rectangle(int level, int x, int y, int width, int height) {
	}

	public static long[] releaseReferences(Map<Long, Integer> references, long[] keys) {
		long[] released = new long[keys.length];
		int count = 0;
		for (long key : keys) {
			Integer referencesBefore = references.get(key);
			if (referencesBefore == null || referencesBefore <= 0) {
				throw new IllegalStateException("Sparse page has no resident reference: " + key);
			}
			if (referencesBefore == 1) {
				references.remove(key);
				released[count++] = key;
			} else {
				references.put(key, referencesBefore - 1);
			}
		}
		return Arrays.copyOf(released, count);
	}

	public static long[] takeCommitted(Set<Long> committed, long[] keys) {
		long[] removed = new long[keys.length];
		int count = 0;
		for (long key : keys) {
			if (committed.remove(key)) {
				removed[count++] = key;
			}
		}
		return Arrays.copyOf(removed, count);
	}

	public static List<Rectangle> rectangles(long[] keys) {
		long[] ordered = new long[keys.length];
		Set<Long> remaining = new HashSet<>();
		for (int i = 0; i < keys.length; i++) {
			long key = keys[i];
			int level = (int) (key >>> 48);
			int x = (int) ((key >>> 24) & COORD_MASK);
			int y = (int) (key & COORD_MASK);
			long rowMajor = ((long) level << 48) | ((long) y << 24) | x;
			ordered[i] = rowMajor;
			remaining.add(rowMajor);
		}
		Arrays.sort(ordered);
		List<Rectangle> rectangles = new ArrayList<>();
		for (long key : ordered) {
			if (!remaining.remove(key)) {
				continue;
			}
			int level = (int) (key >>> 48);
			int y = (int) ((key >>> 24) & COORD_MASK);
			int x = (int) (key & COORD_MASK);
			int width = 1;
			while (x + width <= COORD_MASK && remaining.remove(key + width)) {
				width++;
			}
			int height = 1;
			while (y + height <= COORD_MASK) {
				long row = ((long) level << 48) | ((long) (y + height) << 24) | x;
				boolean complete = true;
				for (int dx = 0; dx < width; dx++) {
					if (!remaining.contains(row + dx)) {
						complete = false;
						break;
					}
				}
				if (!complete) {
					break;
				}
				for (int dx = 0; dx < width; dx++) {
					remaining.remove(row + dx);
				}
				height++;
			}
			rectangles.add(new Rectangle(level, x, y, width, height));
		}
		return rectangles;
	}
}
