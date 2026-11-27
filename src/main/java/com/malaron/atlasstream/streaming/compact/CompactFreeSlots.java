package com.malaron.atlasstream.streaming.compact;

import java.util.ArrayList;
import java.util.List;

public final class CompactFreeSlots {
	public static final int LARGE_MIN_SIDE = 512;

	private final List<Rect> free;
	// Released oversized slots stay whole so small sprites cannot carve them up; only large requests reuse them.
	private final List<Rect> large;

	public CompactFreeSlots() {
		free = new ArrayList<>();
		large = new ArrayList<>();
	}

	private CompactFreeSlots(List<Rect> free, List<Rect> large) {
		this.free = new ArrayList<>(free);
		this.large = new ArrayList<>(large);
	}

	public static boolean isLarge(int width, int height) {
		return Math.min(width, height) >= LARGE_MIN_SIDE;
	}

	public CompactFreeSlots copy() {
		return new CompactFreeSlots(free, large);
	}

	public boolean isEmpty() {
		return free.isEmpty() && large.isEmpty();
	}

	public int countFitting(int width, int height) {
		int count = 0;
		for (Rect rect : free) {
			if (rect.width >= width && rect.height >= height) {
				count++;
			}
		}
		if (isLarge(width, height)) {
			for (Rect rect : large) {
				if (rect.width == width && rect.height == height) {
					count++;
				}
			}
		}
		return count;
	}

	public int[] allocate(int width, int height) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("Slot dimensions must be positive");
		}
		if (isLarge(width, height)) {
			for (int i = 0; i < large.size(); i++) {
				Rect rect = large.get(i);
				if (rect.width == width && rect.height == height) {
					large.remove(i);
					return new int[] {rect.x, rect.y};
				}
			}
		}
		int best = -1;
		long bestArea = Long.MAX_VALUE;
		for (int i = 0; i < free.size(); i++) {
			Rect rect = free.get(i);
			long area = (long) rect.width * rect.height;
			if (rect.width >= width && rect.height >= height && area < bestArea) {
				best = i;
				bestArea = area;
			}
		}
		if (best < 0) {
			return null;
		}
		Rect rect = free.remove(best);
		if (rect.width > width) {
			free.add(new Rect(rect.x + width, rect.y, rect.width - width, height));
		}
		if (rect.height > height) {
			free.add(new Rect(rect.x, rect.y + height, rect.width, rect.height - height));
		}
		return new int[] {rect.x, rect.y};
	}

	// Returns a sprite's slot. Oversized slots are kept whole for oversized sprites.
	public void releaseSlot(int x, int y, int width, int height) {
		if (isLarge(width, height)) {
			large.add(new Rect(x, y, width, height));
		} else {
			release(x, y, width, height);
		}
	}

	public void release(int x, int y, int width, int height) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("Slot dimensions must be positive");
		}
		Rect merged = new Rect(x, y, width, height);
		for (int i = 0; i < free.size();) {
			Rect other = free.get(i);
			Rect combined = merge(merged, other);
			if (combined == null) {
				i++;
			} else {
				merged = combined;
				free.remove(i);
				i = 0;
			}
		}
		free.add(merged);
	}

	private static Rect merge(Rect a, Rect b) {
		if (a.y == b.y && a.height == b.height) {
			if (a.x + a.width == b.x || b.x + b.width == a.x) {
				return new Rect(Math.min(a.x, b.x), a.y, a.width + b.width, a.height);
			}
		}
		if (a.x == b.x && a.width == b.width) {
			if (a.y + a.height == b.y || b.y + b.height == a.y) {
				return new Rect(a.x, Math.min(a.y, b.y), a.width, a.height + b.height);
			}
		}
		return null;
	}

	private record Rect(int x, int y, int width, int height) {
	}
}
