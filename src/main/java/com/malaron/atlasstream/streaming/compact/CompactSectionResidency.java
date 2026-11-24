package com.malaron.atlasstream.streaming.compact;

import java.util.function.Predicate;

public final class CompactSectionResidency {
	private CompactSectionResidency() {
	}

	public static <T> boolean resolved(
		boolean compact, boolean hybrid, Iterable<T> sprites, Predicate<T> isResident
	) {
		if (hybrid || sprites == null) {
			return hybrid;
		}
		boolean hasSprite = false;
		for (T sprite : sprites) {
			hasSprite = true;
			if (!isResident.test(sprite)) {
				return false;
			}
		}
		return hasSprite;
	}
}
