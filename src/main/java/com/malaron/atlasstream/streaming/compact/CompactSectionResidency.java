package com.malaron.atlasstream.streaming.compact;

import java.util.function.Predicate;

public final class CompactSectionResidency {
	private CompactSectionResidency() {
	}

	public static <T> boolean resolved(
		boolean compact, boolean hybrid, Iterable<T> sprites, Predicate<T> isResident
	) {
		if (!compact || hybrid || sprites == null) {
			return true;
		}
		for (T sprite : sprites) {
			if (!isResident.test(sprite)) {
				return false;
			}
		}
		return true;
	}
}
