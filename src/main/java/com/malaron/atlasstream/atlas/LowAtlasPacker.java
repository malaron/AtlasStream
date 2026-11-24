package com.malaron.atlasstream.atlas;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.minecraft.resources.Identifier;

public final class LowAtlasPacker {
	private LowAtlasPacker() {
	}

	public static Layout pack(List<Sprite> sprites, int targetResolution, int maximumDimension) {
		Objects.requireNonNull(sprites, "sprites");
		if (targetResolution <= 0 || Integer.bitCount(targetResolution) != 1) {
			throw new IllegalArgumentException("Target resolution must be a positive power of two");
		}
		List<Placement> placements = new ArrayList<>(sprites.size());
		for (Sprite sprite : sprites) {
			if (sprite.width() <= 0 || sprite.height() <= 0
					|| sprite.paddingX() < 0 || sprite.paddingY() < 0) {
				throw new IllegalArgumentException("Sprite dimensions must be positive and padding non-negative");
			}
			int factor = reductionFactor(sprite.width(), sprite.height(), targetResolution);
			int paddingX = Math.ceilDiv(sprite.paddingX(), factor);
			int paddingY = Math.ceilDiv(sprite.paddingY(), factor);
			int width = Math.max(1, Math.ceilDiv(sprite.width(), factor));
			int height = Math.max(1, Math.ceilDiv(sprite.height(), factor));
			placements.add(new Placement(sprite, factor, 0, 0, width, height, paddingX, paddingY));
		}
		placements.sort(
			Comparator.comparingInt((Placement value) -> value.height() + value.paddingY() * 2)
				.reversed()
				.thenComparingInt(value -> value.width() + value.paddingX() * 2)
				.thenComparing(placement -> placement.sprite().identifier().toString())
		);

		long totalArea = placements.stream()
			.mapToLong(Placement::paddedArea)
			.sum();
		int maximumSpriteWidth = placements.stream()
			.mapToInt(Placement::paddedWidth)
			.max()
			.orElse(1);
		int width = Integer.highestOneBit(Math.max(maximumSpriteWidth, (int) Math.ceil(Math.sqrt(totalArea))));
		if (width < Math.max(maximumSpriteWidth, Math.ceil(Math.sqrt(totalArea)))) {
			width <<= 1;
		}
		width = Math.min(width, maximumDimension);
		for (int candidate = width; candidate <= maximumDimension; candidate <<= 1) {
			List<Placement> packed = placeInShelves(placements, candidate);
			int usedHeight = packed.stream()
				.mapToInt(placement -> placement.y() + placement.paddedHeight())
				.max()
				.orElse(1);
			int height = nextPowerOfTwo(usedHeight);
			if (height <= maximumDimension) {
				return new Layout(candidate, height, positionByIdentifier(packed));
			}
		}
		throw new IllegalArgumentException("Repacked low atlas exceeds the supported maximum dimension");
	}

	public static Layout preserveLayout(
		List<Sprite> sprites,
		int targetResolution,
		int sourceWidth,
		int sourceHeight
	) {
		Map<Identifier, Placement> placements = new LinkedHashMap<>();
		for (Sprite sprite : sprites) {
			if (sprite.sourceX() < sprite.paddingX()
					|| sprite.sourceY() < sprite.paddingY()
					|| sprite.sourceX() + sprite.width() + sprite.paddingX() > sourceWidth
					|| sprite.sourceY() + sprite.height() + sprite.paddingY() > sourceHeight) {
				return null;
			}
			int factor = reductionFactor(sprite.width(), sprite.height(), targetResolution);
			placements.put(
				sprite.identifier(),
				new Placement(
					sprite,
					factor,
					sprite.sourceX() - Math.ceilDiv(sprite.paddingX(), factor),
					sprite.sourceY() - Math.ceilDiv(sprite.paddingY(), factor),
					Math.max(1, Math.ceilDiv(sprite.width(), factor)),
					Math.max(1, Math.ceilDiv(sprite.height(), factor)),
					Math.ceilDiv(sprite.paddingX(), factor),
					Math.ceilDiv(sprite.paddingY(), factor)
				)
			);
		}
		return new Layout(sourceWidth, sourceHeight, Map.copyOf(placements));
	}

	public static int reductionFactor(int width, int height, int targetResolution) {
		int factor = 1;
		while (Math.max(Math.ceilDiv(width, factor), Math.ceilDiv(height, factor)) > targetResolution) {
			if (factor > Integer.MAX_VALUE / 2) {
				throw new IllegalArgumentException("Sprite dimensions are too large to reduce");
			}
			factor <<= 1;
		}
		return factor;
	}

	private static List<Placement> placeInShelves(List<Placement> placements, int atlasWidth) {
		List<Shelf> shelves = new ArrayList<>();
		List<Placement> packed = new ArrayList<>(placements.size());
		for (Placement placement : placements) {
			int itemWidth = placement.paddedWidth();
			int itemHeight = placement.paddedHeight();
			Shelf selected = null;
			for (Shelf shelf : shelves) {
				if (shelf.height >= itemHeight && shelf.cursor + itemWidth <= atlasWidth) {
					selected = shelf;
					break;
				}
			}
			if (selected == null) {
				if (itemWidth > atlasWidth) {
					throw new IllegalArgumentException("A reduced sprite is wider than the candidate atlas");
				}
				int y = shelves.isEmpty() ? 0 : shelves.getLast().y + shelves.getLast().height;
				selected = new Shelf(y, itemHeight);
				shelves.add(selected);
			}
			packed.add(placement.at(selected.cursor, selected.y));
			selected.cursor += itemWidth;
		}
		return packed;
	}

	private static Map<Identifier, Placement> positionByIdentifier(List<Placement> placements) {
		Map<Identifier, Placement> result = new LinkedHashMap<>();
		for (Placement placement : placements) {
			result.put(placement.sprite().identifier(), placement);
		}
		return Map.copyOf(result);
	}

	private static int nextPowerOfTwo(int value) {
		int result = 1;
		while (result < value) {
			result = Math.multiplyExact(result, 2);
		}
		return result;
	}

	public record Sprite(
		Identifier identifier,
		int sourceX,
		int sourceY,
		int width,
		int height,
		int paddingX,
		int paddingY
	) {
	}

	public record Placement(
		Sprite sprite,
		int factor,
		int x,
		int y,
		int width,
		int height,
		int paddingX,
		int paddingY
	) {
		public int paddedWidth() {
			return width + paddingX * 2;
		}

		public int paddedHeight() {
			return height + paddingY * 2;
		}

		public long paddedArea() {
			return (long) paddedWidth() * paddedHeight();
		}

		public Placement at(int destinationX, int destinationY) {
			return new Placement(
				sprite, factor, destinationX, destinationY,
				width, height, paddingX, paddingY
			);
		}
	}

	public record Layout(int width, int height, Map<Identifier, Placement> placements) {
	}

	private static final class Shelf {
		private final int y;
		private final int height;
		private int cursor;

		private Shelf(int y, int height) {
			this.y = y;
			this.height = height;
		}
	}
}
