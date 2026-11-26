package com.malaron.atlasstream.streaming.compact;

public final class CompactHybridLayout {
	private final int atlasWidth;
	private final int atlasHeight;
	private final int fallbackWidth;
	private final int fallbackHeight;
	private final int reservedWidth;
	private final int reservedHeight;
	private final int mipLevels;

	private CompactHybridLayout(
		int atlasWidth, int atlasHeight, int fallbackWidth, int fallbackHeight, int reservedWidth, int reservedHeight,
		int mipLevels
	) {
		this.atlasWidth = atlasWidth;
		this.atlasHeight = atlasHeight;
		this.fallbackWidth = fallbackWidth;
		this.fallbackHeight = fallbackHeight;
		this.reservedWidth = reservedWidth;
		this.reservedHeight = reservedHeight;
		this.mipLevels = mipLevels;
	}

	public static CompactHybridLayout create(
		int atlasWidth, int atlasHeight, int sourceWidth, int sourceHeight, int factor, int mipLevels, int pageSize
	) {
		if (atlasWidth <= 0 || atlasHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0 || factor <= 0 || factor > 255
				|| (factor & (factor - 1)) != 0 || mipLevels <= 0 || mipLevels > 30 || pageSize <= 0
				|| (pageSize & (pageSize - 1)) != 0
				|| sourceWidth % factor != 0 || sourceHeight % factor != 0) {
			throw new IllegalArgumentException("Invalid source dimensions, scale, mip count or page size");
		}
		return create(
			atlasWidth, atlasHeight, sourceWidth / factor, sourceHeight / factor, mipLevels, pageSize
		);
	}

	public static CompactHybridLayout create(
		int atlasWidth, int atlasHeight, int fallbackWidth, int fallbackHeight, int mipLevels, int pageSize
	) {
		if (atlasWidth <= 0 || atlasHeight <= 0 || fallbackWidth <= 0 || fallbackHeight <= 0
				|| mipLevels <= 0 || mipLevels > 30 || pageSize <= 0
				|| (pageSize & (pageSize - 1)) != 0) {
			throw new IllegalArgumentException("Invalid fallback dimensions, mip count or page size");
		}
		long alignment = (long) pageSize << (mipLevels - 1);
		if (atlasWidth % alignment != 0 || atlasHeight % alignment != 0) {
			throw new IllegalArgumentException("Compact atlas dimensions must align to sparse pages at every mip");
		}
		long reservedWidth = ((fallbackWidth + alignment - 1) / alignment) * alignment;
		long reservedHeight = ((fallbackHeight + alignment - 1) / alignment) * alignment;
		if (reservedWidth > atlasWidth || reservedHeight > atlasHeight
				|| (reservedWidth == atlasWidth && reservedHeight == atlasHeight)) {
			throw new IllegalArgumentException("Reduced fallback leaves no space for streamed sprites");
		}
		return new CompactHybridLayout(
			atlasWidth, atlasHeight, fallbackWidth, fallbackHeight,
			(int) reservedWidth, (int) reservedHeight, mipLevels
		);
	}

	public double fallbackU(double layoutU) {
		return layoutU * fallbackWidth / atlasWidth;
	}

	public int fallbackWidth() {
		return fallbackWidth;
	}

	public int fallbackHeight() {
		return fallbackHeight;
	}

	public int reservedWidth() {
		return reservedWidth;
	}

	public int reservedHeight() {
		return reservedHeight;
	}

	public int mipLevels() {
		return mipLevels;
	}

	public double fallbackV(double layoutV) {
		return layoutV * fallbackHeight / atlasHeight;
	}

	public long fallbackTexels() {
		long total = 0;
		for (int level = 0; level < mipLevels; level++) {
			total = Math.addExact(total, (long) Math.max(1, fallbackWidth >> level) * Math.max(1, fallbackHeight >> level));
		}
		return total;
	}

	public CompactFreeSlots createStreamedSlots() {
		CompactFreeSlots slots = new CompactFreeSlots();
		if (reservedWidth < atlasWidth) {
			slots.release(reservedWidth, 0, atlasWidth - reservedWidth, reservedHeight);
		}
		if (reservedHeight < atlasHeight) {
			slots.release(0, reservedHeight, atlasWidth, atlasHeight - reservedHeight);
		}
		return slots;
	}

	public CompactFreeSlots createTerrainSlots() {
		CompactFreeSlots slots = new CompactFreeSlots();
		if (reservedHeight < atlasHeight) {
			slots.release(0, reservedHeight, atlasWidth, atlasHeight - reservedHeight);
		}
		return slots;
	}

	public CompactFreeSlots createCompatibilitySlots() {
		CompactFreeSlots slots = new CompactFreeSlots();
		if (reservedWidth < atlasWidth) {
			slots.release(reservedWidth, 0, atlasWidth - reservedWidth, reservedHeight);
		}
		return slots;
	}
}
