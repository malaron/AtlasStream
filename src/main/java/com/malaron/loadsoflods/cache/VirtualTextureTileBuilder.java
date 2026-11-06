package com.malaron.loadsoflods.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class VirtualTextureTileBuilder {
	private final int atlasWidth;
	private final int atlasHeight;
	private final int tileSize;
	private final int mipLevelCount;
	private final long atlasGeneration;
	private final List<SpriteSource> sprites;
	private volatile AnimationFrames animationFrames;

	public VirtualTextureTileBuilder(
		int atlasWidth,
		int atlasHeight,
		int tileSize,
		int mipLevelCount,
		long atlasGeneration,
		List<SpriteSource> sprites
	) {
		if (atlasWidth <= 0 || atlasHeight <= 0 || tileSize <= 0 || mipLevelCount <= 0) {
			throw new IllegalArgumentException("Atlas dimensions, tile size, and mip level count must be positive");
		}
		if (Integer.bitCount(tileSize) != 1) {
			throw new IllegalArgumentException("Tile size must be a power of two");
		}
		if (atlasGeneration < 0) {
			throw new IllegalArgumentException("Atlas generation must be non-negative");
		}
		this.atlasWidth = atlasWidth;
		this.atlasHeight = atlasHeight;
		this.tileSize = tileSize;
		this.mipLevelCount = mipLevelCount;
		this.atlasGeneration = atlasGeneration;
		this.sprites = List.copyOf(sprites);
	}

	public List<TileMip> build(VirtualTextureTileKey key, int tileMipLevelCount) {
		Objects.requireNonNull(key, "key");
		if (key.atlasGeneration() != atlasGeneration) {
			throw new IllegalArgumentException("Tile key belongs to a stale atlas generation");
		}
		if (key.mipLevel() >= mipLevelCount) {
			throw new IllegalArgumentException("Tile key mip level is outside the atlas mip chain");
		}
		if (tileMipLevelCount <= 0 || tileMipLevelCount > Integer.numberOfTrailingZeros(tileSize) + 1) {
			throw new IllegalArgumentException("Tile mip level count is invalid for the configured tile size");
		}
		int mipWidth = Math.max(1, atlasWidth >> key.mipLevel());
		int mipHeight = Math.max(1, atlasHeight >> key.mipLevel());
		int tileCountX = tileCount(mipWidth);
		int tileCountY = tileCount(mipHeight);
		if (key.tileX() >= tileCountX || key.tileY() >= tileCountY) {
			throw new IllegalArgumentException("Tile coordinates are outside the atlas mip level");
		}

		int baseOriginX = key.tileX() * tileSize;
		int baseOriginY = key.tileY() * tileSize;
		List<SpriteSource> intersectingSprites = new ArrayList<>();
		for (SpriteSource sprite : sprites) {
			if (intersects(sprite, key.mipLevel(), baseOriginX, baseOriginY, tileSize, tileSize)) {
				intersectingSprites.add(sprite);
			}
		}
		List<TileMip> result = new ArrayList<>(tileMipLevelCount);
		for (int localMip = 0; localMip < tileMipLevelCount; localMip++) {
			int width = Math.max(1, tileSize >> localMip);
			int height = width;
			if (localMip > 0 && key.mipLevel() + localMip >= mipLevelCount) {
				result.add(downsample(result.get(localMip - 1)));
				continue;
			}
			int sourceMip = key.mipLevel() + localMip;
			int originX = key.tileX() * tileSize >> localMip;
			int originY = key.tileY() * tileSize >> localMip;
			int sourceWidth = Math.max(1, atlasWidth >> sourceMip);
			int sourceHeight = Math.max(1, atlasHeight >> sourceMip);
			int[] pixels = new int[Math.multiplyExact(width, height)];
			for (SpriteSource sprite : intersectingSprites) {
				MipImage image;
				if (sprite.animated()) {
					image = animationFrames == null ? null : animationFrames.currentFrame(sprite.name(), sourceMip);
					if (image == null) {
						throw new AnimatedTileException(sprite.name());
					}
				} else {
					image = sprite.mipImage(sourceMip);
				}
				if (image == null) {
					throw new IllegalStateException(
						"Sprite " + sprite.name() + " has no source image for atlas mip level " + sourceMip
					);
				}
				copySpritePixels(
					pixels,
					width,
					height,
					sourceWidth,
					sourceHeight,
					originX,
					originY,
					sourceMip,
					sprite,
					image
				);
			}
			result.add(new TileMip(width, height, pixels));
		}
		return List.copyOf(result);
	}

	// Names of the animated sprites whose pixels appear in the tile, so it can be rebuilt when their frame changes.
	public List<String> animatedSpriteNames(VirtualTextureTileKey key) {
		List<String> names = new ArrayList<>();
		int originX = key.tileX() * tileSize;
		int originY = key.tileY() * tileSize;
		for (SpriteSource sprite : sprites) {
			if (sprite.animated() && intersects(sprite, key.mipLevel(), originX, originY, tileSize, tileSize)) {
				names.add(sprite.name());
			}
		}
		return names;
	}

	public void setAnimationFrames(AnimationFrames animationFrames) {
		this.animationFrames = animationFrames;
	}

	public interface AnimationFrames {
		// The image of the sprite's currently displayed frame for the atlas mip level, or null if unavailable.
		MipImage currentFrame(String spriteName, int mipLevel);
	}

	private void copySpritePixels(
		int[] pixels,
		int tileWidth,
		int tileHeight,
		int sourceWidth,
		int sourceHeight,
		int originX,
		int originY,
		int mipLevel,
		SpriteSource sprite,
		MipImage image
	) {
		int shift = Math.min(mipLevel, 30);
		int contentX = sprite.x() >> shift;
		int contentY = sprite.y() >> shift;
		int left = Math.max(0, (sprite.x() - sprite.paddingX()) >> shift);
		int top = Math.max(0, (sprite.y() - sprite.paddingY()) >> shift);
		int right = Math.min(sourceWidth, ceilShift(sprite.x() + sprite.width() + sprite.paddingX(), shift));
		int bottom = Math.min(sourceHeight, ceilShift(sprite.y() + sprite.height() + sprite.paddingY(), shift));
		int startX = Math.max(left, originX);
		int startY = Math.max(top, originY);
		int endX = Math.min(right, originX + tileWidth);
		int endY = Math.min(bottom, originY + tileHeight);
		for (int atlasY = startY; atlasY < endY; atlasY++) {
			int sourceY = Math.clamp(atlasY - contentY, 0, image.height() - 1);
			for (int atlasX = startX; atlasX < endX; atlasX++) {
				int sourceX = Math.clamp(atlasX - contentX, 0, image.width() - 1);
				pixels[(atlasY - originY) * tileWidth + atlasX - originX] = image.pixel(sourceX, sourceY);
			}
		}
	}

	private boolean intersects(SpriteSource sprite, int mipLevel, int originX, int originY, int width, int height) {
		int shift = Math.min(mipLevel, 30);
		int left = (sprite.x() - sprite.paddingX()) >> shift;
		int top = (sprite.y() - sprite.paddingY()) >> shift;
		int right = ceilShift(sprite.x() + sprite.width() + sprite.paddingX(), shift);
		int bottom = ceilShift(sprite.y() + sprite.height() + sprite.paddingY(), shift);
		return left < originX + width && right > originX && top < originY + height && bottom > originY;
	}

	private static TileMip downsample(TileMip source) {
		int width = Math.max(1, source.width() >> 1);
		int height = Math.max(1, source.height() >> 1);
		int[] pixels = new int[Math.multiplyExact(width, height)];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				pixels[y * width + x] = average(
					source.pixel(x * 2, y * 2),
					source.pixel(Math.min(x * 2 + 1, source.width() - 1), y * 2),
					source.pixel(x * 2, Math.min(y * 2 + 1, source.height() - 1)),
					source.pixel(
						Math.min(x * 2 + 1, source.width() - 1),
						Math.min(y * 2 + 1, source.height() - 1)
					)
				);
			}
		}
		return new TileMip(width, height, pixels);
	}

	private static int average(int first, int second, int third, int fourth) {
		int alphaFirst = first >>> 24;
		int alphaSecond = second >>> 24;
		int alphaThird = third >>> 24;
		int alphaFourth = fourth >>> 24;
		int alphaSum = alphaFirst + alphaSecond + alphaThird + alphaFourth;
		int alpha = (alphaSum + 2) / 4;
		if (alphaSum == 0) {
			return 0;
		}
		long redSum = (long) ((first >>> 16) & 255) * alphaFirst
			+ (long) ((second >>> 16) & 255) * alphaSecond
			+ (long) ((third >>> 16) & 255) * alphaThird
			+ (long) ((fourth >>> 16) & 255) * alphaFourth;
		long greenSum = (long) ((first >>> 8) & 255) * alphaFirst
			+ (long) ((second >>> 8) & 255) * alphaSecond
			+ (long) ((third >>> 8) & 255) * alphaThird
			+ (long) ((fourth >>> 8) & 255) * alphaFourth;
		long blueSum = (long) (first & 255) * alphaFirst
			+ (long) (second & 255) * alphaSecond
			+ (long) (third & 255) * alphaThird
			+ (long) (fourth & 255) * alphaFourth;
		int red = (int) ((redSum + alphaSum / 2) / alphaSum);
		int green = (int) ((greenSum + alphaSum / 2) / alphaSum);
		int blue = (int) ((blueSum + alphaSum / 2) / alphaSum);
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}

	private static int ceilShift(int value, int shift) {
		if (shift == 0) {
			return value;
		}
		int mask = (1 << shift) - 1;
		return (value + mask) >> shift;
	}

	private int tileCount(int dimension) {
		return (dimension + tileSize - 1) / tileSize;
	}

	public interface MipImage {
		int width();

		int height();

		int pixel(int x, int y);
	}

	public record SpriteSource(
		String name,
		int x,
		int y,
		int width,
		int height,
		int paddingX,
		int paddingY,
		boolean animated,
		List<MipImage> mipImages
	) {
		public SpriteSource {
			Objects.requireNonNull(name, "name");
			if (x < 0 || y < 0 || width <= 0 || height <= 0 || paddingX < 0 || paddingY < 0) {
				throw new IllegalArgumentException("Sprite bounds and padding must be non-negative");
			}
			mipImages = List.copyOf(mipImages);
		}

		private MipImage mipImage(int mipLevel) {
			return mipLevel < mipImages.size() ? mipImages.get(mipLevel) : null;
		}
	}

	public static final class TileMip {
		private final int width;
		private final int height;
		private final int[] pixels;

		private TileMip(int width, int height, int[] pixels) {
			this.width = width;
			this.height = height;
			this.pixels = pixels;
		}

		public int width() {
			return width;
		}

		public int height() {
			return height;
		}

		public int pixel(int x, int y) {
			if (x < 0 || x >= width || y < 0 || y >= height) {
				throw new IndexOutOfBoundsException("Tile pixel is outside the mip image");
			}
			return pixels[y * width + x];
		}
	}

	public static final class AnimatedTileException extends IllegalArgumentException {
		private final String spriteName;

		private AnimatedTileException(String spriteName) {
			super("Virtual texture tile intersects animated sprite " + spriteName);
			this.spriteName = spriteName;
		}

		public String spriteName() {
			return spriteName;
		}
	}
}
