package com.malaron.atlasstream.client.atlas;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import com.malaron.atlasstream.AtlasStream;
import com.malaron.atlasstream.atlas.TranslucentMipAlpha;
import com.malaron.atlasstream.client.AtlasStreamClient;
import com.malaron.atlasstream.client.atlas.AtlasRuntimeState.PixelAverager;
import com.malaron.atlasstream.client.diagnostics.LodPerformanceStats;
import com.malaron.atlasstream.client.mixin.AbstractTextureAccessor;
import com.malaron.atlasstream.client.mixin.SpriteContentsAccessor;
import com.malaron.atlasstream.client.sodium.SodiumChunkMeshPriority;
import com.malaron.atlasstream.lod.LodConfig;
import com.malaron.atlasstream.lod.SectionLodPolicy;
import com.malaron.atlasstream.streaming.NearMovementLookahead;
import com.malaron.atlasstream.streaming.NearestSpriteDemand;
import com.malaron.atlasstream.streaming.SparsePageRuns;
import com.malaron.atlasstream.streaming.StreamingWorkBudget;
import com.malaron.atlasstream.streaming.compact.CompactAtlasPriority;
import com.malaron.atlasstream.streaming.compact.CompactFreeSlots;
import com.malaron.atlasstream.streaming.compact.CompactHybridLayout;
import com.malaron.atlasstream.streaming.compact.CompactPendingQueue;
import com.malaron.atlasstream.streaming.compact.CompactSectionResidency;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import org.lwjgl.opengl.ARBSparseTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

// Full-resolution albedo/normal/specular atlases backed by OpenGL sparse textures. They keep the original atlas
// layout, so terrain UVs are unchanged, but physical memory is committed only for sprites terrain actually uses.
public final class NearAtlas implements AutoCloseable {
	private static final int PAGE = 128;
	private static final AtomicLong RESIDENCY_REVISION = new AtomicLong();
	// -Datlasstream.compactSize=<texels> shrinks the compact atlas to force overflow when testing.
	private static final int MAX_COMPACT_SIZE = Integer.getInteger("atlasstream.compactSize", 32768);
	private static final boolean HYBRID_REQUESTED = Boolean.parseBoolean(
		System.getProperty("atlasstream.hybridCompact", "true")
	);
	private static final boolean PRESERVE_TRANSPARENT_MIPS =
		Boolean.parseBoolean(System.getProperty("atlasstream.preserveTransparentMips", "true"));
	private static final int DEFAULT_NORMAL = 0xFF8080FF;
	private static final int DEFAULT_SPECULAR = 0;
	private static final int MAX_NEW_JOBS_PER_TICK = 8;
	private static final int MAX_UPLOADS_PER_TICK = 8;
	private static final long COMPACT_PREPARE_BUDGET_NANOS = 4_000_000L;
	private static final long COMPACT_UPLOAD_BUDGET_NANOS = 4_000_000L;
	private static final int MAX_ANIMATIONS_IN_FLIGHT = 48;
	private static final int MAX_ANIMATION_UPLOADS_PER_TICK = 24;
	private static final FrameKey FIRST_FRAME = new FrameKey(0, 0, 0.0F);

	// Dimensions of the physical textures. In compact mode they are smaller than the vanilla layout (bigWidth x
	// bigHeight) and sprites live in slots found through the remap texture.
	private final int width;
	private final int height;
	private final int bigWidth;
	private final int bigHeight;
	private final boolean compact;
	private final int cell;
	private final int cellsX;
	private final int cellsY;
	private int remapId;
	private GpuTexture remapTexture;
	private GpuTextureView remapView;
	private final List<int[]> shelves = new ArrayList<>();
	private CompactFreeSlots freeSlots = new CompactFreeSlots();
	private CompactHybridLayout hybridLayout;
	private int shelfCursorY;
	private boolean misalignedLogged;
	private boolean overflowLogged;
	// Set when the compact atlas had no free slot; makes the next refresh evict sprites nobody needs.
	private boolean slotsFull;
	// Sprites that found no free slot. They stay in `requested` so nothing re-queues them until a slot is freed.
	private final java.util.LinkedHashSet<TextureAtlasSprite> waitingForSlot = new java.util.LinkedHashSet<>();
	private int freedSlots;
	private long lastPressureLog;
	// Nearest distance in blocks from the camera to a section using each sprite; render thread only.
	private java.util.Map<TextureAtlasSprite, Double> spriteDistance = new java.util.HashMap<>();
	private Set<TextureAtlasSprite> preferredSprites = Set.of();
	private final NearMovementLookahead movementLookahead = new NearMovementLookahead();
	private static final long PRESSURE_EVICT_BUDGET_NANOS = 6_000_000L;
	private static final int MAX_PRESSURE_EVICTIONS_PER_REFRESH = 128;
	private static final double PRESSURE_SWAP_MARGIN_BLOCKS = 8.0;
	private final int mipLevels;
	private final ResourceManager resourceManager;
	private final Layer albedo;
	private final Layer normal;
	private final Layer specular;
	private GpuTextureView albedoBaseMipView;
	private boolean albedoBaseMipOnly;
	private final AbstractTexture normalWrapper = new Wrapper();
	private final AbstractTexture specularWrapper = new Wrapper();
	private final ExecutorService executor = Executors.newFixedThreadPool(
		Math.max(3, Math.min(8, Runtime.getRuntime().availableProcessors() / 2)),
		runnable -> {
			Thread thread = new Thread(runnable, "AtlasStream near-atlas loader");
			thread.setDaemon(true);
			return thread;
		}
	);
	private final Set<TextureAtlasSprite> requested = ConcurrentHashMap.newKeySet();
	private final java.util.Map<Long, Set<TextureAtlasSprite>> sectionSprites = new ConcurrentHashMap<>();
	private final java.util.Map<TextureAtlasSprite, Long> lastTouched = new ConcurrentHashMap<>();
	private final java.util.Map<TextureAtlasSprite, Long> lastNeeded = new java.util.HashMap<>();
	private final java.util.Map<TextureAtlasSprite, long[]> pagesBySprite = new java.util.HashMap<>();
	private final java.util.Map<Long, Integer> pageRefs = new java.util.HashMap<>();
	private volatile Set<TextureAtlasSprite> activeSprites = Set.of();
	private volatile double nearReach = -1.0;
	private volatile double cameraX;
	private volatile double cameraY;
	private volatile double cameraZ;
	private int activeRefreshTicks;
	private int evictedSprites;
	private static final double SECTION_RADIUS = 14.0;
	// Sprites of sections this far beyond the draw distance are kept resident, so they are ready when a section turns near.
	private static final double PREFETCH_BLOCKS = 32.0;
	private static final long TOUCH_RECENT_NANOS = 2_000_000_000L;
	private static final long TOUCH_FORGET_NANOS = 60_000_000_000L;
	private static final long EVICT_GRACE_NANOS = 10_000_000_000L;
	private static final int MAX_EVICTIONS_PER_REFRESH = 4;
	// Decommitting sparse pages is slow in the driver, so release memory gradually rather than in one frame.
	private static final long EVICT_BUDGET_NANOS = 400_000L;
	private final ConcurrentLinkedQueue<TextureAtlasSprite> incoming = new ConcurrentLinkedQueue<>();
	private final com.malaron.atlasstream.streaming.compact.CompactPendingQueue<TextureAtlasSprite> compactPending =
		new com.malaron.atlasstream.streaming.compact.CompactPendingQueue<>();
	private final java.util.ArrayDeque<TextureAtlasSprite> retryIncoming = new java.util.ArrayDeque<>();
	private final List<Job> jobs = new ArrayList<>();
	private final java.util.Map<TextureAtlasSprite, Geometry> resident = new java.util.HashMap<>();
	private final java.util.Map<TextureAtlasSprite, FrameKey> lastFrame = new java.util.HashMap<>();
	private final java.util.LinkedHashMap<TextureAtlasSprite, FrameKey> pendingFrames = new java.util.LinkedHashMap<>();
	private final java.util.LinkedHashMap<TextureAtlasSprite, PreparedFrame> preparing = new java.util.LinkedHashMap<>();
	private int residentSprites;
	private double averageBacklog;
	private int peakBacklog;
	private int displayedPeak;
	private int uploadedThisWindow;
	private int uploadsPerSecond;
	private int windowTicks;
	private int failedSprites;
	private static final long WARM_MIN_NANOS = 1_500_000_000L;
	private static final long WARM_MAX_NANOS = 15_000_000_000L;
	private final long createdNanos = System.nanoTime();
	private volatile boolean warm;
	private volatile boolean closed;
	private volatile boolean failed;

	private NearAtlas(
		int bigWidth,
		int bigHeight,
		int width,
		int height,
		int mipLevels,
		ResourceManager resourceManager,
		boolean compact
	) {
		this.width = width;
		this.height = height;
		this.bigWidth = bigWidth;
		this.bigHeight = bigHeight;
		this.compact = compact;
		this.cell = 1 << (mipLevels - 1);
		this.cellsX = bigWidth / cell;
		this.cellsY = bigHeight / cell;
		// The first cell row is reserved as the target for sprites that are not resident.
		this.shelfCursorY = cell;
		this.mipLevels = mipLevels;
		this.resourceManager = resourceManager;
		Layer createdAlbedo = null;
		Layer createdNormal = null;
		Layer createdSpecular = null;
		try {
			createdAlbedo = Layer.create("AtlasStream near albedo atlas", width, height, mipLevels);
			createdNormal = Layer.create("AtlasStream near normal atlas", width, height, mipLevels);
			createdSpecular = Layer.create("AtlasStream near specular atlas", width, height, mipLevels);
			if (compact) {
				createRemapTexture();
			}
		} catch (RuntimeException | Error exception) {
			closeQuietly(createdAlbedo);
			closeQuietly(createdNormal);
			closeQuietly(createdSpecular);
			closeRemap();
			executor.shutdownNow();
			throw exception;
		}
		this.albedo = createdAlbedo;
		this.normal = createdNormal;
		this.specular = createdSpecular;
		((AbstractTextureAccessor) normalWrapper).atlasStream$setTexture(normal.texture);
		((AbstractTextureAccessor) normalWrapper).atlasStream$setTextureView(normal.view);
		((AbstractTextureAccessor) specularWrapper).atlasStream$setTexture(specular.texture);
		((AbstractTextureAccessor) specularWrapper).atlasStream$setTextureView(specular.view);
		IrisPbrAtlasReducer.registerHolder(albedo.id, normalWrapper, specularWrapper);
	}

	static NearAtlas create(int width, int height, int mipLevels, ResourceManager resourceManager) {
		try {
			boolean sparse = GL.getCapabilities().GL_ARB_sparse_texture;
			if (!sparse) {
				AtlasStream.LOGGER.info(
					"Sparse textures are unavailable (device {}, GL_ARB_sparse_texture={}); Iris terrain stays on the reduced atlas",
					RenderSystem.getDevice().getClass().getName(), sparse
				);
				return null;
			}
			int maximum = GL11.glGetInteger(ARBSparseTexture.GL_MAX_SPARSE_TEXTURE_SIZE_ARB);
			if (width > maximum || height > maximum
					|| width % PAGE != 0 || height % PAGE != 0
					|| (width >> (mipLevels - 1)) < PAGE || (height >> (mipLevels - 1)) < PAGE) {
				return createCompact(width, height, mipLevels, maximum, resourceManager);
			}
			NearAtlas atlas = new NearAtlas(width, height, width, height, mipLevels, resourceManager, false);
			RESIDENCY_REVISION.incrementAndGet();
			AtlasStream.LOGGER.info(
				"Created sparse near atlases {}x{} with {} mips (page {}x{}); memory is committed per used sprite",
				width, height, mipLevels, PAGE, PAGE
			);
			return atlas;
		} catch (RuntimeException | Error exception) {
			AtlasStream.LOGGER.error("Could not create sparse near atlases; Iris terrain stays on the reduced atlas", exception);
			return null;
		}
	}

	// For layouts too large for a sparse texture: a smaller sparse atlas holds the sprites near the camera, and a
	// remap texture sends each terrain vertex's layout UV to the sprite's slot there.
	private static NearAtlas createCompact(
		int bigWidth,
		int bigHeight,
		int mipLevels,
		int sparseMaximum,
		ResourceManager resourceManager
	) {
		int cell = 1 << (mipLevels - 1);
		int size = Math.min(sparseMaximum, MAX_COMPACT_SIZE);
		size -= size % PAGE;
		int maxTexture = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
		if (bigWidth % cell != 0 || bigHeight % cell != 0 || (size >> (mipLevels - 1)) < PAGE
				|| bigWidth / cell > maxTexture || bigHeight / cell + 1 > maxTexture || bigWidth / cell < 2) {
			AtlasStream.LOGGER.info(
				"Atlas {}x{} with {} mips does not suit sparse textures; Iris terrain stays on the reduced atlas",
				bigWidth, bigHeight, mipLevels
			);
			return null;
		}
		NearAtlas atlas = new NearAtlas(bigWidth, bigHeight, size, size, mipLevels, resourceManager, true);
		RESIDENCY_REVISION.incrementAndGet();
		AtlasStream.LOGGER.info(
			"Created compact sparse near atlases {}x{} with {} mips for a {}x{} layout; terrain UVs are remapped in the vertex shader",
			size, size, mipLevels, bigWidth, bigHeight
		);
		return atlas;
	}

	boolean isUsable() {
		return !closed && !failed;
	}

	boolean isResident(TextureAtlasSprite sprite) {
		return isUsable() && resident.containsKey(sprite);
	}

	float[] remapUv(TextureAtlasSprite sprite, float u, float v) {
		Geometry geometry = resident.get(sprite);
		if (geometry == null) {
			return new float[] {u, v};
		}
		float sourceX = u * bigWidth;
		float sourceY = v * bigHeight;
		float mappedX = sourceX + geometry.x - geometry.bigX;
		float mappedY = sourceY + geometry.y - geometry.bigY;
		return new float[] {mappedX / width, mappedY / height};
	}

	public static long residencyRevision() {
		return RESIDENCY_REVISION.get();
	}

	boolean isCompact() {
		return compact;
	}

	public static boolean hybridRequested() {
		return HYBRID_REQUESTED;
	}

	boolean isHybrid() {
		return hybridLayout != null;
	}

	void initializeHybrid(
		int factor, List<NativeImage> lowAlbedo, IrisPbrAtlasReducer.Images lowPbr
	) {
		if (!compact || !HYBRID_REQUESTED) {
			return;
		}

		CompactHybridLayout layout = CompactHybridLayout.create(width, height, bigWidth, bigHeight, factor, mipLevels, PAGE);
		if (lowPbr == null || lowAlbedo.size() != mipLevels
				|| lowPbr.normal().size() != mipLevels || lowPbr.specular().size() != mipLevels) {
			throw new IllegalStateException("Hybrid compact atlas requires complete albedo and PBR mip chains");
		}
		for (List<NativeImage> images : List.of(lowAlbedo, lowPbr.normal(), lowPbr.specular())) {
			for (int level = 0; level < mipLevels; level++) {
				NativeImage image = images.get(level);
				if (image.getWidth() != Math.max(1, layout.fallbackWidth() >> level)
						|| image.getHeight() != Math.max(1, layout.fallbackHeight() >> level)) {
					throw new IllegalStateException("Hybrid compact atlas fallback dimensions do not match");
				}
			}
		}
		initializeHybridLayer(albedo, lowAlbedo);
		initializeHybridLayer(normal, lowPbr.normal());
		initializeHybridLayer(specular, lowPbr.specular());
		int previousBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		try {
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, remapId);
			uploadRemapRect(1, cellsY, 1, 1, new int[] {packBytes(cell, factor, 0, 0)});
			checkGl("enabling hybrid compact atlas remapping");
		} finally {
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousBinding);
		}
		freeSlots = layout.createStreamedSlots();
		hybridLayout = layout;
		AtlasStream.LOGGER.info(
			"Experimental hybrid compact atlas enabled: fallback {}x{}, reserved {}x{}, committed {} MiB",
			layout.fallbackWidth(), layout.fallbackHeight(), layout.reservedWidth(), layout.reservedHeight(),
			committedBytes() / (1024 * 1024)
		);
	}

	void initializeRepackedHybrid(
		List<NativeImage> lowAlbedo, IrisPbrAtlasReducer.Images lowPbr
	) {
		if (!compact || !HYBRID_REQUESTED) {
			return;
		}
		if (lowAlbedo.isEmpty() || lowAlbedo.size() != mipLevels
				|| lowPbr == null || lowPbr.normal().size() != mipLevels
				|| lowPbr.specular().size() != mipLevels) {
			throw new IllegalStateException("Repacked hybrid atlas requires complete albedo and PBR mip chains");
		}
		CompactHybridLayout layout = CompactHybridLayout.createFallback(
			width, height, lowAlbedo.get(0).getWidth(), lowAlbedo.get(0).getHeight(), mipLevels, PAGE
		);
		initializeHybridLayer(albedo, lowAlbedo);
		initializeHybridLayer(normal, lowPbr.normal());
		initializeHybridLayer(specular, lowPbr.specular());
		int previousBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		try {
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, remapId);
			uploadRemapRect(1, cellsY, 1, 1, new int[] {packBytes(cell, 1, 0, 0)});
			checkGl("enabling repacked hybrid compact atlas remapping");
		} finally {
			GL11.glBindTexture(GL11.GL_TEXTURE_BINDING_2D, previousBinding);
		}
		freeSlots = layout.createStreamedSlots();
		hybridLayout = layout;
		AtlasStream.LOGGER.info(
			"Repacked hybrid compact atlas enabled: fallback {}x{}, reserved {}x{}, committed {} MiB",
			layout.fallbackWidth(), layout.fallbackHeight(), layout.reservedWidth(), layout.reservedHeight(),
			committedBytes() / (1024 * 1024)
		);
	}

	private void initializeHybridLayer(Layer layer, List<NativeImage> images) {
		int previousBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		try {
			clearGlErrors();
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, layer.id);
			for (int level = 0; level < images.size(); level++) {
				NativeImage image = images.get(level);
				int pagesX = Math.ceilDiv(image.getWidth(), PAGE);
				int pagesY = Math.ceilDiv(image.getHeight(), PAGE);
				int commitWidth = Math.min(pagesX * PAGE, width >> level);
				int commitHeight = Math.min(pagesY * PAGE, height >> level);
				ARBSparseTexture.glTexPageCommitmentARB(
					GL11.GL_TEXTURE_2D, level, 0, 0, 0, commitWidth, commitHeight, 1, true
				);
				checkGl("committing hybrid compact fallback pages");
				for (int y = 0; y < pagesY; y++) {
					for (int x = 0; x < pagesX; x++) {
						layer.committedPages.add(((long) level << 48) | ((long) x << 24) | y);
					}
				}
				layer.committedBytes += (long) commitWidth * commitHeight * 4;
			}
			var encoder = RenderSystem.getDevice().createCommandEncoder();
			for (int level = 0; level < images.size(); level++) {
				encoder.writeToTexture(layer.texture, images.get(level), level, 0, 0, 0);
			}
			encoder.submit();
			checkGl("uploading hybrid compact fallback");
		} finally {
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousBinding);
		}
	}

	void updateHybridAnimation(int x, int y, List<NativeImage> images) {
		if (!isHybrid() || !isUsable()) {
			return;
		}
		try {
			if (images.size() != mipLevels) {
				throw new IllegalStateException("Hybrid compact animation has an incomplete mip chain");
			}
			var encoder = RenderSystem.getDevice().createCommandEncoder();
			for (int level = 0; level < images.size(); level++) {
				NativeImage image = images.get(level);
				int left = x >> level;
				int top = y >> level;
				if (left < 0 || top < 0
						|| left + image.getWidth() > Math.max(1, hybridLayout.fallbackWidth() >> level)
						|| top + image.getHeight() > Math.max(1, hybridLayout.fallbackHeight() >> level)) {
					throw new IllegalStateException("Hybrid compact animation exceeds fallback bounds");
				}
				encoder.writeToTexture(albedo.texture, image, level, 0, left, top);
			}
			encoder.submit();
		} catch (RuntimeException exception) {
			failed = true;
			RESIDENCY_REVISION.incrementAndGet();
			AtlasStream.LOGGER.error("Could not update hybrid compact animation; near atlas disabled", exception);
		}
	}

	GpuTextureView remapView() {
		return remapView;
	}

	int remapGlId() {
		return remapId;
	}

	private void createRemapTexture() {
		int rows = cellsY + 1;
		int previousBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		clearGlErrors();
		int id = GL11.glGenTextures();
		try {
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
			GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, cellsX, rows);
			int previousAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
			int previousRowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
			GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
			GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
			ByteBuffer zeros = MemoryUtil.memCalloc(cellsX * rows * 4);
			try {
				GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, cellsX, rows, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, zeros);
			} finally {
				MemoryUtil.memFree(zeros);
				GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, previousAlignment);
				GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, previousRowLength);
			}
			// Header texels: physical atlas size minus one (16 bits per axis), then the cell size.
			uploadRemapRect(0, cellsY, 2, 1, new int[] {
				packBytes((width - 1) & 0xFF, (width - 1) >> 8, (height - 1) & 0xFF, (height - 1) >> 8),
				packBytes(cell, 0, 0, 0)
			});
			checkGl("allocating the near remap texture");
		} catch (RuntimeException | Error exception) {
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousBinding);
			GL11.glDeleteTextures(id);
			throw exception;
		}
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousBinding);
		GpuTexture texture = new SparseGlTexture("AtlasStream near remap", cellsX, rows, 1, id);
		try {
			remapView = RenderSystem.getDevice().createTextureView(texture);
		} catch (RuntimeException | Error exception) {
			texture.close();
			throw exception;
		}
		remapId = id;
		remapTexture = texture;
	}

	private static int packBytes(int r, int g, int b, int a) {
		return (a & 0xFF) << 24 | (b & 0xFF) << 16 | (g & 0xFF) << 8 | (r & 0xFF);
	}

	// Uploads a rectangle of remap texels; the caller has the remap texture bound or passes through here.
	private void uploadRemapRect(int x, int y, int w, int h, int[] texels) {
		int previousBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		int alignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
		int rowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
		int skipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
		int skipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
		ByteBuffer buffer = MemoryUtil.memAlloc(texels.length * 4);
		try {
			if (remapId != 0) {
				GL11.glBindTexture(GL11.GL_TEXTURE_2D, remapId);
			}
			GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
			GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
			GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
			GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
			buffer.asIntBuffer().put(texels);
			GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
		} finally {
			MemoryUtil.memFree(buffer);
			GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, alignment);
			GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, rowLength);
			GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, skipPixels);
			GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, skipRows);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousBinding);
		}
	}

	// Points the layout cells of a sprite's slot at its compact slot (mapped) or clears them.
	private void writeRemap(Geometry g, boolean mapped) {
		int cx = g.bigX / cell;
		int cy = g.bigY / cell;
		int cw = Math.min(g.slotW / cell, cellsX - cx);
		int ch = Math.min(g.slotH / cell, cellsY - cy);
		if (cw <= 0 || ch <= 0) {
			return;
		}
		int value = 0;
		if (mapped) {
			int dx = (g.x - g.bigX) / cell + 32768;
			int dy = (g.y - g.bigY) / cell + 4096;
			value = packBytes(dx & 0xFF, dx >> 8, dy & 0xFF, 0x80 | (dy >> 8));
		}
		int[] texels = new int[cw * ch];
		Arrays.fill(texels, value);
		uploadRemapRect(cx, cy, cw, ch, texels);
	}

	// Finds a free slot of the given size, aligned to the cell size, in the compact atlas.
	private int[] allocateSlot(int w, int h) {
		int[] free = freeSlots.allocate(w, h);
		if (free != null) {
			return free;
		}
		if (isHybrid()) {
			return null;
		}
		for (int[] shelf : shelves) {
			if (shelf[1] == h && shelf[2] + w <= width) {
				int[] slot = {shelf[2], shelf[0]};
				shelf[2] += w;
				return slot;
			}
		}
		if (shelfCursorY + h > height || w > width) {
			return null;
		}
		shelves.add(new int[] {shelfCursorY, h, w});
		int[] slot = {0, shelfCursorY};
		shelfCursorY += h;
		return slot;
	}

	private void freeSlot(Geometry g) {
		if (compact) {
			freeSlots.release(g.x, g.y, g.slotW, g.slotH);
		}
	}

	private void closeRemap() {
		if (remapView != null) {
			remapView.close();
			remapView = null;
		}
		if (remapTexture != null) {
			remapTexture.close();
			remapTexture = null;
		}
	}

	GpuTexture albedoTexture() {
		return albedo.texture;
	}

	public int albedoGlId() {
		return albedo.id;
	}

	public void setAlbedoBaseMipOnly(boolean enabled) {
		if (enabled && albedoBaseMipView == null) {
			albedoBaseMipView = RenderSystem.getDevice().createTextureView(albedo.texture, 0, 1);
		}
		albedoBaseMipOnly = enabled;
	}

	GpuTextureView albedoView() {
		return albedo.view;
	}

	GpuTextureView terrainAlbedoView() {
		return albedoBaseMipOnly ? albedoBaseMipView : albedo.view;
	}

	// Safe to call from chunk-build threads.
	void request(TextureAtlasSprite sprite) {
		if (!closed && !failed && requested.add(sprite)) {
			incoming.add(sprite);
		}
	}

	// Safe to call from chunk-build threads. Records the sprites a section uses and, when the section is close
	// enough to be drawn from this atlas soon, requests them so they are resident before the section renders.
	void noteTerrainSprite(TextureAtlasSprite sprite, long sectionKey) {
		if (sectionKey == Long.MIN_VALUE) {
			request(sprite);
			return;
		}
		if (sectionSprites.computeIfAbsent(sectionKey, ignored -> ConcurrentHashMap.newKeySet()).add(sprite)) {
			RESIDENCY_REVISION.incrementAndGet();
		}
		double reach = nearReach;
		if (reach >= 0.0) {
			double dx = SodiumChunkMeshPriority.sectionX(sectionKey) * 16.0 + 8.0 - cameraX;
			double dy = SodiumChunkMeshPriority.sectionY(sectionKey) * 16.0 + 8.0 - cameraY;
			double dz = SodiumChunkMeshPriority.sectionZ(sectionKey) * 16.0 + 8.0 - cameraZ;
			if (Math.sqrt(dx * dx + dy * dy + dz * dz) - SECTION_RADIUS <= reach) {
				request(sprite);
			}
		}
	}

	// Render thread. In compact mode a sprite without a slot draws as a hole, so a section may only use this atlas
	// once every sprite it uses is resident; otherwise it is drawn from the reduced atlas.
	boolean sectionResolved(long sectionKey) {
		return CompactSectionResidency.resolved(
			compact, isHybrid(), sectionSprites.get(sectionKey), resident::containsKey
		);
	}

	// Called when a section is about to be re-meshed; its sprite list is rebuilt by the mesh task.
	void clearSection(long sectionKey) {
		sectionSprites.put(sectionKey, ConcurrentHashMap.newKeySet());
		RESIDENCY_REVISION.incrementAndGet();
	}

	// For sprites drawn without a section (held items, entities, particles): resident while they keep being drawn.
	void touch(TextureAtlasSprite sprite) {
		if (closed || failed) {
			return;
		}
		lastTouched.put(sprite, System.nanoTime());
		request(sprite);
	}

	// Render thread only. Works out which sprites are needed (and so resident), which of those animate, and frees
	// sprites nobody needs when the atlas is over budget.
	private void refreshSprites() {
		LodConfig config = AtlasStreamClient.getConfig();
		double radius = config.highResolutionDistance();
		long now = System.nanoTime();
		var minecraft = net.minecraft.client.Minecraft.getInstance();
		var cameraEntity = minecraft.gameRenderer.mainCamera();
		var camera = cameraEntity.position();
		cameraX = camera.x;
		cameraY = camera.y;
		cameraZ = camera.z;
		movementLookahead.update(camera.x, camera.z, now);

		Set<TextureAtlasSprite> active = new java.util.HashSet<>();
		Set<TextureAtlasSprite> preferred = new java.util.HashSet<>();
		var demand = new com.malaron.atlasstream.streaming.NearestSpriteDemand<TextureAtlasSprite>();
		Set<TextureAtlasSprite> needed = demand.sprites();
		if (radius > 0.0) {
			var forward = cameraEntity.forwardVector();
			double drawReach = radius + config.hysteresis();
			double reach = drawReach + PREFETCH_BLOCKS;
			nearReach = reach;
			double aspect = (double) minecraft.getWindow().getWidth() / Math.max(1, minecraft.getWindow().getHeight());
			// Cone around the view direction that covers the frustum corners, plus a small margin.
			double halfAngle = Math.atan(Math.tan(Math.toRadians(cameraEntity.getFov()) / 2.0) * Math.sqrt(1.0 + aspect * aspect))
				+ Math.toRadians(8.0);
			long aheadSection = SodiumChunkMeshPriority.packSection(
				(int) Math.floor(movementLookahead.targetX(camera.x) / 16.0), (int) Math.floor(camera.y / 16.0),
				(int) Math.floor(movementLookahead.targetZ(camera.z) / 16.0)
			);
			for (java.util.Map.Entry<Long, Set<TextureAtlasSprite>> entry : sectionSprites.entrySet()) {
				long key = entry.getKey();
				int sectionX = SodiumChunkMeshPriority.sectionX(key);
				int sectionY = SodiumChunkMeshPriority.sectionY(key);
				int sectionZ = SodiumChunkMeshPriority.sectionZ(key);
				double dx = sectionX * 16.0 + 8.0 - camera.x;
				double dy = sectionY * 16.0 + 8.0 - camera.y;
				double dz = sectionZ * 16.0 + 8.0 - camera.z;
				double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (distance - SECTION_RADIUS > reach) {
					continue;
				}
				double nearest = compact
					? com.malaron.atlasstream.lod.SectionLodPolicy.distanceToSection(
						sectionX, sectionY, sectionZ, camera.x, camera.y, camera.z)
					: Math.max(0.0, distance - SECTION_RADIUS);
				for (TextureAtlasSprite sprite : entry.getValue()) {
					demand.record(sprite, nearest);
				}
				if (key == aheadSection || CompactAtlasPriority.isImmediateSection(
						sectionX, sectionY, sectionZ, camera.x, camera.y, camera.z)) {
					preferred.addAll(entry.getValue());
				}
				if (distance - SECTION_RADIUS > drawReach) {
					continue;
				}
				boolean visible = distance <= SECTION_RADIUS;
				if (!visible) {
					double cos = (dx * forward.x() + dy * forward.y() + dz * forward.z()) / distance;
					double angle = Math.acos(Math.max(-1.0, Math.min(1.0, cos)));
					visible = angle <= halfAngle + Math.asin(Math.min(1.0, SECTION_RADIUS / distance));
				}
				if (visible) {
					for (TextureAtlasSprite sprite : entry.getValue()) {
						if (sprite.contents().isAnimated()) {
							active.add(sprite);
						}
					}
				}
			}
		} else {
			nearReach = -1.0;
		}

		java.util.Iterator<java.util.Map.Entry<TextureAtlasSprite, Long>> touched = lastTouched.entrySet().iterator();
		while (touched.hasNext()) {
			java.util.Map.Entry<TextureAtlasSprite, Long> entry = touched.next();
			long age = now - entry.getValue();
			if (age > TOUCH_FORGET_NANOS) {
				touched.remove();
			} else if (age <= TOUCH_RECENT_NANOS) {
				demand.record(entry.getKey(), 0.0);
				preferred.add(entry.getKey());
				if (entry.getKey().contents().isAnimated()) {
					active.add(entry.getKey());
				}
			}
		}
		waitingForSlot.removeIf(sprite -> {
			if (needed.contains(sprite)) {
				return false;
			}
			requested.remove(sprite);
			return true;
		});
		for (TextureAtlasSprite sprite : needed) {
			request(sprite);
			if (resident.containsKey(sprite)) {
				lastNeeded.put(sprite, now);
			}
		}
		activeSprites = active;
		spriteDistance = demand.distances();
		preferredSprites = preferred;
		compactPending.prioritiesChanged();
		retryIncoming.removeIf(sprite -> {
			if (needed.contains(sprite)) {
				return false;
			}
			requested.remove(sprite);
			return true;
		});
		List<TextureAtlasSprite> retries = new ArrayList<>(retryIncoming);
		retries.sort(this::comparePriority);
		retryIncoming.clear();
		retryIncoming.addAll(retries);
		if (!waitingForSlot.isEmpty() && radius > 0.0) {
			boolean recording = LodPerformanceStats.isRecording();
			long start = recording ? System.nanoTime() : 0;
			int beforeEvictions = evictedSprites;
			evictForWaitingSprites(needed, now);
			if (recording) {
				LodPerformanceStats.recordNearPressure(System.nanoTime() - start, evictedSprites - beforeEvictions);
			}
		}
		evictUnneeded(needed, config, radius <= 0.0, now);
		// Simulate reservations so each retry has enough free space before it is queued.
		if (!waitingForSlot.isEmpty() && !freeSlots.isEmpty()) {
			List<TextureAtlasSprite> waiting = new ArrayList<>(waitingForSlot);
			waiting.sort(this::comparePriority);
			CompactFreeSlots free = freeSlots.copy();
			reserveRetries(free);
			for (TextureAtlasSprite sprite : waiting) {
				long key = slotKey(sprite);
				if (allocateFrom(free, key)) {
					retryIncoming.add(sprite);
					waitingForSlot.remove(sprite);
				}
			}
			retries = new ArrayList<>(retryIncoming);
			retries.sort(this::comparePriority);
			retryIncoming.clear();
			retryIncoming.addAll(retries);
		}
		freedSlots = 0;
	}

	private double distanceOf(TextureAtlasSprite sprite) {
		return spriteDistance.getOrDefault(sprite, Double.MAX_VALUE);
	}

	private int comparePriority(TextureAtlasSprite first, TextureAtlasSprite second) {
		return CompactAtlasPriority.compare(
			preferredSprites.contains(first), distanceOf(first),
			preferredSprites.contains(second), distanceOf(second)
		);
	}

	private boolean canEvictFor(TextureAtlasSprite waiting, TextureAtlasSprite victim) {
		return CompactAtlasPriority.canEvict(
			preferredSprites.contains(waiting), distanceOf(waiting),
			preferredSprites.contains(victim), distanceOf(victim), PRESSURE_SWAP_MARGIN_BLOCKS
		);
	}

	// The compact slot size a sprite needs (as allocateSlot keys it), or -1 when it cannot be placed.
	private long slotKey(TextureAtlasSprite sprite) {
		int w = sprite.contents().width();
		int h = sprite.contents().height();
		int padX = Math.round(sprite.getU0() * bigWidth) - sprite.getX();
		int padY = Math.round(sprite.getV0() * bigHeight) - sprite.getY();
		if (w <= 0 || h <= 0 || padX < 0 || padY < 0) {
			return -1L;
		}
		long slotW = (long) Math.ceilDiv(w + 2 * padX, cell) * cell;
		long slotH = (long) Math.ceilDiv(h + 2 * padY, cell) * cell;
		return (slotW << 32) | slotH;
	}

	private static boolean allocateFrom(CompactFreeSlots free, long key) {
		return key != -1L && free.allocate((int) (key >>> 32), (int) key) != null;
	}

	private void reserveRetries(CompactFreeSlots free) {
		for (TextureAtlasSprite retry : retryIncoming) {
			allocateFrom(free, slotKey(retry));
		}
	}

	// Under slot pressure, drop lower-priority sprites first. The distance margin prevents similarly near swaps.
	private void evictForWaitingSprites(Set<TextureAtlasSprite> needed, long now) {
		List<TextureAtlasSprite> waiting = new ArrayList<>(waitingForSlot);
		waiting.sort(this::comparePriority);
		List<TextureAtlasSprite> victims = new ArrayList<>(resident.keySet());
		victims.sort((first, second) -> comparePriority(second, first));
		int previousBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		if (now - lastPressureLog > 5_000_000_000L && !waiting.isEmpty() && !victims.isEmpty()) {
			lastPressureLog = now;
			TextureAtlasSprite far = victims.get(0);
			TextureAtlasSprite near = waiting.get(0);
			AtlasStream.LOGGER.debug(
				"Slot pressure: nearest waiting {} at {} blocks ({}x{}), farthest resident {} at {} blocks, {} waiting, {} resident",
				near.contents().name(), (int) distanceOf(near), near.contents().width(), near.contents().height(),
				far.contents().name(), needed.contains(far) ? (int) distanceOf(far) : -1, waiting.size(), victims.size()
			);
		}
		long deadline = System.nanoTime() + PRESSURE_EVICT_BUDGET_NANOS;
		try {
			CompactFreeSlots free = freeSlots.copy();
			reserveRetries(free);
			int evictions = 0;
			for (TextureAtlasSprite sprite : waiting) {
				if (evictions >= MAX_PRESSURE_EVICTIONS_PER_REFRESH || System.nanoTime() >= deadline) {
					break;
				}
				long key = slotKey(sprite);
				if (allocateFrom(free, key)) {
					continue;
				}
				if (key == -1L) {
					continue;
				}
				int wantedWidth = (int) (key >>> 32);
				int wantedHeight = (int) key;
				TextureAtlasSprite victim = null;
				for (TextureAtlasSprite candidate : victims) {
					Geometry g = resident.get(candidate);
					if (g != null && g.slotW >= wantedWidth && g.slotH >= wantedHeight
							&& canEvictFor(sprite, candidate)) {
						victim = candidate;
						break;
					}
				}
				if (victim == null) {
					continue;
				}
				Geometry geometry = resident.get(victim);
				free.release(geometry.x, geometry.y, geometry.slotW, geometry.slotH);
				allocateFrom(free, key);
				evict(victim);
				if (needed.contains(victim)) {
					// Still wanted, but farther than the sprite taking its slot: queue it behind nearer ones
					// instead of letting the next refresh re-request it and win the slot back.
					requested.add(victim);
					waitingForSlot.add(victim);
				}
				evictions++;
			}
			checkGl("releasing sparse pages");
		} catch (IllegalStateException exception) {
			AtlasStream.LOGGER.error("Could not release near sprites", exception);
			failed = true;
		} finally {
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousBinding);
		}
	}

	private long committedBytes() {
		return albedo.committedBytes + normal.committedBytes + specular.committedBytes;
	}

	// Frees sprites nothing has needed for a while. Normally only when over the cache budget; with the near
	// distance off there is no use for the atlas, so everything is released.
	private void evictUnneeded(Set<TextureAtlasSprite> needed, LodConfig config, boolean off, long now) {
		long budget = config.highResolutionCacheMiB() * 1024L * 1024L;
		long committed = committedBytes();
		if (!off && committed <= budget) {
			return;
		}
		long target = off ? 0L : budget / 10 * 9;
		List<TextureAtlasSprite> victims = new ArrayList<>();
		for (TextureAtlasSprite sprite : resident.keySet()) {
			if (!needed.contains(sprite) && now - lastNeeded.getOrDefault(sprite, now) >= EVICT_GRACE_NANOS) {
				victims.add(sprite);
			}
		}
		if (victims.isEmpty()) {
			return;
		}
		victims.sort(java.util.Comparator.comparingLong(sprite -> lastNeeded.getOrDefault(sprite, now)));
		int previousBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		long deadline = System.nanoTime() + EVICT_BUDGET_NANOS;
		try {
			for (int i = 0; i < victims.size() && i < MAX_EVICTIONS_PER_REFRESH && (off || committedBytes() > target); i++) {
				evict(victims.get(i));
				if (System.nanoTime() >= deadline) {
					break;
				}
			}
			checkGl("releasing sparse pages");
		} catch (IllegalStateException exception) {
			AtlasStream.LOGGER.error("Could not release near sprites", exception);
			failed = true;
		} finally {
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousBinding);
		}
	}

	private void evict(TextureAtlasSprite sprite) {
		long[] pages = pagesBySprite.remove(sprite);
		Geometry evicted = resident.remove(sprite);
		if (compact && evicted != null) {
			writeRemap(evicted, false);
			freeSlot(evicted);
			freedSlots++;
		}
		if (evicted != null) {
			RESIDENCY_REVISION.incrementAndGet();
		}
		lastFrame.remove(sprite);
		pendingFrames.remove(sprite);
		PreparedFrame prepared = preparing.remove(sprite);
		if (prepared != null) {
			prepared.levels.cancel(false);
		}
		lastNeeded.remove(sprite);
		requested.remove(sprite);
		residentSprites--;
		evictedSprites++;
		if (pages == null) {
			return;
		}
		long[] unreferenced = SparsePageRuns.releaseReferences(pageRefs, pages);
		if (unreferenced.length > 0) {
			uncommit(albedo, unreferenced);
			uncommit(normal, unreferenced);
			uncommit(specular, unreferenced);
		}
	}

	private void uncommit(Layer layer, long[] keys) {
		long[] committed = SparsePageRuns.takeCommitted(layer.committedPages, keys);
		if (committed.length == 0) {
			return;
		}
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, layer.id);
		List<SparsePageRuns.Rectangle> rectangles = SparsePageRuns.rectangles(committed);
		if (LodPerformanceStats.isRecording()) {
			LodPerformanceStats.recordNearDecommit(committed.length, rectangles.size());
		}
		for (SparsePageRuns.Rectangle rect : rectangles) {
			int x = rect.x() * PAGE;
			int y = rect.y() * PAGE;
			int commitWidth = Math.min(rect.width() * PAGE, Math.max(1, width >> rect.level()) - x);
			int commitHeight = Math.min(rect.height() * PAGE, Math.max(1, height >> rect.level()) - y);
			ARBSparseTexture.glTexPageCommitmentARB(
				GL11.GL_TEXTURE_2D, rect.level(), x, y, 0, commitWidth, commitHeight, 1, false
			);
			layer.committedBytes -= (long) commitWidth * commitHeight * 4;
		}
	}

	private long[] pageKeys(List<LevelData> levels) {
		Set<Long> keys = new java.util.HashSet<>();
		for (LevelData data : levels) {
			int levelWidth = Math.max(1, width >> data.level);
			int levelHeight = Math.max(1, height >> data.level);
			int lastPageX = Math.min(levelWidth - 1, data.x + data.width - 1) / PAGE;
			int lastPageY = Math.min(levelHeight - 1, data.y + data.height - 1) / PAGE;
			for (int pageY = data.y / PAGE; pageY <= lastPageY; pageY++) {
				for (int pageX = data.x / PAGE; pageX <= lastPageX; pageX++) {
					keys.add(((long) data.level << 48) | ((long) pageX << 24) | pageY);
				}
			}
		}
		return keys.stream().mapToLong(Long::longValue).toArray();
	}

	// Render thread only.
	void service() {
		if (closed || failed) {
			return;
		}
		if (activeRefreshTicks-- <= 0) {
			activeRefreshTicks = waitingForSlot.isEmpty() ? 3 : 1;
			boolean recording = LodPerformanceStats.isRecording();
			long start = recording ? System.nanoTime() : 0;
			refreshSprites();
			if (recording) {
				LodPerformanceStats.recordNearRefresh(System.nanoTime() - start);
			}
		}
		// Until the first sprites around the camera are resident the near terrain is drawn from the reduced atlas,
		// so catch up faster than the steady-state limits allow.
		int newJobLimit = warm && waitingForSlot.isEmpty() ? MAX_NEW_JOBS_PER_TICK : MAX_NEW_JOBS_PER_TICK * 4;
		int uploadLimit = warm && waitingForSlot.isEmpty() ? MAX_UPLOADS_PER_TICK : MAX_UPLOADS_PER_TICK * 4;
		if (compact) {
			compactPending.drainFrom(incoming);
		}
		long prepareStart = compact ? System.nanoTime() : 0;
		for (int started = 0; started < newJobLimit; started++) {
			if (compact && !StreamingWorkBudget.canContinue(
					started, newJobLimit, System.nanoTime() - prepareStart, COMPACT_PREPARE_BUDGET_NANOS)) {
				break;
			}
			TextureAtlasSprite sprite;
			if (compact) {
				TextureAtlasSprite retry = retryIncoming.peek();
				TextureAtlasSprite pending = compactPending.peek(this::comparePriority);
				sprite = retry != null && (pending == null || comparePriority(retry, pending) <= 0)
					? retryIncoming.poll() : compactPending.poll(this::comparePriority);
			} else {
				sprite = retryIncoming.poll();
				if (sprite == null) {
					sprite = incoming.poll();
				}
			}
			if (sprite == null) {
				break;
			}
			boolean recording = LodPerformanceStats.isRecording();
			long start = recording ? System.nanoTime() : 0;
			startJob(sprite);
			if (recording) {
				LodPerformanceStats.recordNearPrepare(System.nanoTime() - start);
			}
		}
		int uploads = 0;
		long uploadStart = compact ? System.nanoTime() : 0;
		if (compact) {
			jobs.sort((first, second) -> comparePriority(first.sprite, second.sprite));
		}
		Iterator<Job> iterator = jobs.iterator();
		while (iterator.hasNext() && uploads < uploadLimit) {
			if (compact && !StreamingWorkBudget.canContinue(
					uploads, uploadLimit, System.nanoTime() - uploadStart, COMPACT_UPLOAD_BUDGET_NANOS)) {
				break;
			}
			Job job = iterator.next();
			if (!job.pbr.isDone()) {
				continue;
			}
			iterator.remove();
			uploads++;
			boolean recording = LodPerformanceStats.isRecording();
			long start = recording ? System.nanoTime() : 0;
			finishJob(job);
			if (recording) {
				LodPerformanceStats.recordNearUpload(System.nanoTime() - start);
			}
			if (failed) {
				return;
			}
		}
		flushAnimations();
		if (!warm) {
			long age = System.nanoTime() - createdNanos;
			boolean drained = incoming.isEmpty() && compactPending.isEmpty() && retryIncoming.isEmpty()
				&& jobs.isEmpty() && !requested.isEmpty();
			if ((drained && age >= WARM_MIN_NANOS) || age >= WARM_MAX_NANOS) {
				warm = true;
			}
		}
	}

	// False until the sprites needed around the camera have streamed in after creation, so near sections are drawn
	// from the reduced atlas instead of showing unpopulated (black) pages.
	boolean isWarm() {
		return warm || isHybrid();
	}

	// Render thread only. Queues the albedo re-upload for a resident animated sprite whose displayed frame changed.
	// PBR maps keep frame 0. Queued work is drained under a time budget in service().
	void animate(TextureAtlasSprite sprite, int frameIndex, int nextFrameIndex, float nextWeight) {
		if (closed || failed || !resident.containsKey(sprite)) {
			return;
		}
		if (!activeSprites.contains(sprite)) {
			pendingFrames.remove(sprite);
			return;
		}
		pendingFrames.put(sprite, new FrameKey(frameIndex, nextFrameIndex, nextWeight));
	}

	// The CPU work (frame copy, padding, blending, channel swizzle) runs on worker threads;
	// the render thread only uploads finished frames.
	private void flushAnimations() {
		Iterator<java.util.Map.Entry<TextureAtlasSprite, FrameKey>> wanted = pendingFrames.entrySet().iterator();
		while (wanted.hasNext() && preparing.size() < MAX_ANIMATIONS_IN_FLIGHT) {
			java.util.Map.Entry<TextureAtlasSprite, FrameKey> entry = wanted.next();
			TextureAtlasSprite sprite = entry.getKey();
			FrameKey key = entry.getValue();
			if (preparing.containsKey(sprite)) {
				continue;
			}
			wanted.remove();
			if (key.equals(lastFrame.getOrDefault(sprite, FIRST_FRAME))) {
				continue;
			}
			Geometry geometry = resident.get(sprite);
			preparing.put(sprite, new PreparedFrame(key, executor.submit(() -> {
				List<LevelData> current = albedoLevels(sprite, geometry, key.frame);
				return key.weight > 0.0F
					? blend(current, albedoLevels(sprite, geometry, key.nextFrame), key.weight)
					: current;
			})));
		}

		List<List<LevelData>> batch = new ArrayList<>();
		try {
			Iterator<java.util.Map.Entry<TextureAtlasSprite, PreparedFrame>> ready = preparing.entrySet().iterator();
			while (ready.hasNext() && batch.size() < MAX_ANIMATION_UPLOADS_PER_TICK) {
				java.util.Map.Entry<TextureAtlasSprite, PreparedFrame> entry = ready.next();
				if (!entry.getValue().levels.isDone()) {
					continue;
				}
				ready.remove();
				if (!resident.containsKey(entry.getKey())) {
					continue;
				}
				batch.add(entry.getValue().levels.get());
				lastFrame.put(entry.getKey(), entry.getValue().key);
			}
			if (!batch.isEmpty()) {
				writeAll(albedo, batch);
					uploadedThisWindow += batch.size();
				}
			} catch (java.util.concurrent.ExecutionException | InterruptedException | RuntimeException exception) {
				AtlasStream.LOGGER.error("Could not animate near sprites", exception);
				if (exception instanceof IllegalStateException) {
					failed = true;
				}
			}
			int backlog = preparing.size();
			for (TextureAtlasSprite sprite : pendingFrames.keySet()) {
				if (!preparing.containsKey(sprite)) {
					backlog++;
				}
			}
			averageBacklog += (backlog - averageBacklog) * 0.05;
			peakBacklog = Math.max(peakBacklog, backlog);
			if (++windowTicks >= 20) {
				uploadsPerSecond = uploadedThisWindow;
				displayedPeak = peakBacklog;
				uploadedThisWindow = 0;
				peakBacklog = 0;
				windowTicks = 0;
			}
	}

	private static List<LevelData> blend(List<LevelData> current, List<LevelData> next, float weight) {
		List<LevelData> blended = new ArrayList<>(current.size());
		for (int level = 0; level < current.size(); level++) {
			LevelData a = current.get(level);
			LevelData b = next.get(level);
			int[] out = new int[a.argb.length];
			for (int i = 0; i < out.length; i++) {
				int first = a.argb[i];
				int second = b.argb[i];
				int result = 0;
				for (int shift = 0; shift < 32; shift += 8) {
					int from = (first >>> shift) & 0xFF;
					int to = (second >>> shift) & 0xFF;
					result |= Math.round(from + (to - from) * weight) << shift;
				}
				out[i] = result;
			}
			blended.add(new LevelData(a.level, a.x, a.y, a.width, a.height, out));
		}
		return blended;
	}

	List<String> debugLines() {
		long bytes = committedBytes();
		return List.of(
			"Near sparse atlas" + (isHybrid() ? " (experimental hybrid)" : "") + ": "
				+ residentSprites + " sprites resident, " + (jobs.size() + incoming.size() + compactPending.size() + retryIncoming.size())
				+ " pending, " + evictedSprites + " evicted, " + failedSprites + " failed, " + waitingForSlot.size() + " waiting for a slot",
			"Near animation: " + activeSprites.size() + " sprites active, " + uploadsPerSecond + " uploads/s, backlog avg "
				+ String.format(java.util.Locale.ROOT, "%.0f", averageBacklog) + " peak " + displayedPeak,
			"Near committed VRAM: " + String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
				+ " / budget " + AtlasStreamClient.getConfig().highResolutionCacheMiB() + " MiB"
				+ (failed ? " (disabled after an error)" : "")
		);
	}

	public List<String> sectionDebugLines(String label, int x, int y, int z) {
		List<String> lines = new ArrayList<>();
		String section = label + " section " + x + "," + y + "," + z;
		if (!compact) {
			lines.add(section + ": atlas is not compact; section residency does not gate rendering.");
			return lines;
		}
		Set<TextureAtlasSprite> recorded = sectionSprites.get(SodiumChunkMeshPriority.packSection(x, y, z));
		if (recorded == null) {
			lines.add(section + ": no sprite usage recorded; currently treated as resolved.");
			return lines;
		}
		List<TextureAtlasSprite> missing = new ArrayList<>();
		for (TextureAtlasSprite sprite : recorded) {
			if (!resident.containsKey(sprite)) {
				missing.add(sprite);
			}
		}
		missing.sort(java.util.Comparator.comparing(sprite -> sprite.contents().name().toString()));
		lines.add(section + ": " + recorded.size() + " recorded sprites, " + missing.size()
			+ " not resident; atlas " + (warm ? "warm" : "warming") + ".");
		int limit = Math.min(missing.size(), 20);
		for (int i = 0; i < limit; i++) {
			TextureAtlasSprite sprite = missing.get(i);
			long key = slotKey(sprite);
			int wantedWidth = (int) (key >>> 32);
			int wantedHeight = (int) key;
			int freeCount = key == -1L ? 0 : freeSlots.countFitting(wantedWidth, wantedHeight);
			int compatible = 0;
			int eligible = 0;
			double waitingDistance = distanceOf(sprite);
			for (var entry : resident.entrySet()) {
				Geometry g = entry.getValue();
				if (key != -1L && g.slotW >= wantedWidth && g.slotH >= wantedHeight) {
					compatible++;
					if (canEvictFor(sprite, entry.getKey())) {
						eligible++;
					}
				}
			}
			String state = waitingForSlot.contains(sprite) ? "waiting"
				: jobs.stream().anyMatch(job -> job.sprite == sprite) ? "upload pending"
				: retryIncoming.contains(sprite) || compactPending.contains(sprite) || incoming.contains(sprite) ? "queued"
				: requested.contains(sprite) ? "requested, not queued" : "not requested";
			lines.add("  " + sprite.contents().name() + ": " + state + ", slot "
				+ (key == -1L ? "invalid" : (key >>> 32) + "x" + (key & 0xFFFFFFFFL))
				+ ", distance " + formatDistance(waitingDistance)
				+ (preferredSprites.contains(sprite) ? ", preferred" : "")
				+ ", fitting free rectangles " + freeCount + ", fitting residents " + compatible + ", eligible victims " + eligible);
		}
		if (missing.size() > limit) {
			lines.add("  " + (missing.size() - limit) + " additional missing sprites omitted.");
		}
		return lines;
	}

	private static String formatDistance(double distance) {
		return Double.isFinite(distance) && distance != Double.MAX_VALUE
			? String.format(java.util.Locale.ROOT, "%.1f", distance) : "not in recent demand";
	}

	public List<String> spriteAlphaDebugLines(TextureAtlasSprite sprite) throws IOException {
		List<String> lines = new ArrayList<>();
		Geometry geometry = resident.get(sprite);
		lines.add(sprite.contents().name() + ": " + (compact ? "compact" : "layout-preserving")
			+ (isHybrid() ? " hybrid" : "") + ", " + (geometry == null ? "not resident" : "resident")
			+ ", source layout " + sprite.contents().width() + "x" + sprite.contents().height() + ".");
		if (geometry == null) {
			lines.add("No GPU slot to inspect. Use /atlasstream near-debug while targeting the block to check section residency.");
			return lines;
		}
		if (sprite.contents().isAnimated()) {
			throw new IllegalStateException("Sprite alpha readback currently supports static sprites only");
		}
		if (!GL.getCapabilities().OpenGL30) {
			throw new IllegalStateException("OpenGL 3.0 framebuffer support is required for sprite alpha readback");
		}
		Identifier name = sprite.contents().name();
		Identifier resourceId = Identifier.fromNamespaceAndPath(name.getNamespace(), "textures/" + name.getPath() + ".png");
		Resource resource = resourceManager.getResource(resourceId)
			.orElseThrow(() -> new IOException("Source texture not found: " + resourceId));
		NativeImage[] mips = ((SpriteContentsAccessor) sprite.contents()).atlasStream$getMipImages();
		List<LevelData> expectedLevels = albedoLevels(sprite, geometry, 0);
		int preexistingError = GL11.glGetError();
		if (preexistingError != GL11.GL_NO_ERROR) {
			throw new IllegalStateException("Pre-existing OpenGL error " + preexistingError + " before alpha readback");
		}
		int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
		int alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
		int rowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
		int skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
		int skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
		int imageHeight = GL11.glGetInteger(GL12.GL_PACK_IMAGE_HEIGHT);
		int skipImages = GL11.glGetInteger(GL12.GL_PACK_SKIP_IMAGES);
		int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		int framebuffer = 0;
		ByteBuffer pixel = MemoryUtil.memAlloc(4);
		try (InputStream input = resource.open(); NativeImage original = NativeImage.read(input)) {
			if (original.getWidth() != geometry.width || original.getHeight() != geometry.height
					|| mips[0].getWidth() != geometry.width || mips[0].getHeight() != geometry.height) {
				throw new IllegalStateException("Source, mip and sprite dimensions do not match");
			}
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
			GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
			GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
			GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
			GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
			GL11.glPixelStorei(GL12.GL_PACK_IMAGE_HEIGHT, 0);
			GL11.glPixelStorei(GL12.GL_PACK_SKIP_IMAGES, 0);
			framebuffer = GL30.glGenFramebuffers();
			if (framebuffer == 0) {
				throw new IllegalStateException("Could not create framebuffer for sprite alpha readback");
			}
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
			GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
			int[][] points = {{0, 0}, {3, 0}, {0, 3}, {3, 3}, {1, 1}};
			lines.add("Alpha at four inset corners and an interior point; source / CPU mip / expected upload / GPU (0-255).");
			for (int level = 0; level < Math.min(mipLevels, mips.length); level++) {
				GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
					GL11.GL_TEXTURE_2D, albedo.id, level);
				int status = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);
				if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
					throw new IllegalStateException("Incomplete framebuffer for sprite mip " + level + ": " + status);
				}
				NativeImage image = mips[level];
				int sw = Math.max(1, geometry.width >> level);
				int sh = Math.max(1, geometry.height >> level);
				int qw = Math.max(sw, Math.max(1, (geometry.width + 2 * geometry.padX) >> level));
				int qh = Math.max(sh, Math.max(1, (geometry.height + 2 * geometry.padY) >> level));
				int padX = (qw - sw) / 2;
				int padY = (qh - sh) / 2;
				StringBuilder line = new StringBuilder("  mip " + level + ":");
				for (int[] point : points) {
					int x = (int) ((long) (sw - 1) * (2 * point[0] + 1) / 8);
					int y = (int) ((long) (sh - 1) * (2 * point[1] + 1) / 8);
					int sourceX = Math.min(original.getWidth() - 1, (int) (((long) x * 2 + 1) * geometry.width / (2L * sw)));
					int sourceY = Math.min(original.getHeight() - 1, (int) (((long) y * 2 + 1) * geometry.height / (2L * sh)));
					pixel.clear();
					GL11.glReadPixels((geometry.x >> level) + padX + x,
						(geometry.y >> level) + padY + y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
					int error = GL11.glGetError();
					if (error != GL11.GL_NO_ERROR) {
						throw new IllegalStateException("OpenGL error " + error + " reading sprite mip " + level);
					}
					int gpu = pixel.get(3) & 255;
					int expected = expectedLevels.get(level).argb[(y + padY) * qw + x] >>> 24;
					line.append(' ').append(original.getPixel(sourceX, sourceY) >>> 24)
						.append('/').append(image.getPixel(x, y) >>> 24)
						.append('/').append(expected).append('/').append(gpu);
				}
				lines.add(line.toString());
			}
			int error = GL11.glGetError();
			if (error != GL11.GL_NO_ERROR) {
				throw new IllegalStateException("OpenGL error " + error + " during sprite alpha readback");
			}
		} finally {
			MemoryUtil.memFree(pixel);
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
			if (framebuffer != 0) {
				GL30.glDeleteFramebuffers(framebuffer);
			}
			GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment);
			GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, rowLength);
			GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
			GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, skipRows);
			GL11.glPixelStorei(GL12.GL_PACK_IMAGE_HEIGHT, imageHeight);
			GL11.glPixelStorei(GL12.GL_PACK_SKIP_IMAGES, skipImages);
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
		}
		return lines;
	}

	private void startJob(TextureAtlasSprite sprite) {
		Geometry geometry = geometry(sprite);
		if (geometry == null) {
			if (slotsFull) {
				slotsFull = false;
				waitingForSlot.add(sprite);
			} else {
				failedSprites++;
				if (failedSprites <= 20) {
					AtlasStream.LOGGER.warn(
						"Near atlas cannot place sprite {} ({}x{} at {},{} in the {}x{} layout, u0/v0 {}/{})",
						sprite.contents().name(), sprite.contents().width(), sprite.contents().height(),
						sprite.getX(), sprite.getY(), bigWidth, bigHeight, sprite.getU0(), sprite.getV0()
					);
				}
			}
			return;
		}
		try {
			List<LevelData> albedoLevels = albedoLevels(sprite, geometry, 0);
			Future<List<List<LevelData>>> pbr = executor.submit(() -> List.of(
				pbrLevels(sprite.contents().name(), "_n", DEFAULT_NORMAL, IrisPbrAtlasReducer::averageNormal, geometry),
				pbrLevels(sprite.contents().name(), "_s", DEFAULT_SPECULAR, IrisPbrAtlasReducer::averageSpecular, geometry)
			));
			jobs.add(new Job(sprite, geometry, albedoLevels, pbr));
		} catch (RuntimeException exception) {
			freeSlot(geometry);
			failedSprites++;
			AtlasStream.LOGGER.warn("Could not prepare near sprite {}: {}", sprite.contents().name(), exception.getMessage());
		}
	}

	private void finishJob(Job job) {
		try {
			List<List<LevelData>> pbr = job.pbr.get();
			write(albedo, job.albedo);
			write(normal, pbr.get(0));
			write(specular, pbr.get(1));
			if (compact) {
				writeRemap(job.geometry, true);
			}
			pagesBySprite.put(job.sprite, pageKeys(job.albedo));
			for (long key : pagesBySprite.get(job.sprite)) {
				pageRefs.merge(key, 1, Integer::sum);
			}
			lastNeeded.put(job.sprite, System.nanoTime());
			residentSprites++;
			resident.put(job.sprite, job.geometry);
			RESIDENCY_REVISION.incrementAndGet();
		} catch (java.util.concurrent.ExecutionException | InterruptedException | RuntimeException exception) {
			freeSlot(job.geometry);
			failedSprites++;
			AtlasStream.LOGGER.error("Could not upload near sprite {}", job.sprite.contents().name(), exception);
			if (exception instanceof IllegalStateException) {
				failed = true;
			}
		}
	}

	private Geometry geometry(TextureAtlasSprite sprite) {
		int w = sprite.contents().width();
		int h = sprite.contents().height();
		int padX = Math.round(sprite.getU0() * bigWidth) - sprite.getX();
		int padY = Math.round(sprite.getV0() * bigHeight) - sprite.getY();
		if (w <= 0 || h <= 0 || padX < 0 || padY < 0
				|| sprite.getX() < 0 || sprite.getY() < 0
				|| sprite.getX() + w + padX * 2 > bigWidth || sprite.getY() + h + padY * 2 > bigHeight) {
			return null;
		}
		if (!compact) {
			return new Geometry(sprite.getX(), sprite.getY(), w, h, padX, padY, sprite.getX(), sprite.getY(), 0, 0);
		}
		if (sprite.getX() % cell != 0 || sprite.getY() % cell != 0) {
			if (!misalignedLogged) {
				misalignedLogged = true;
				AtlasStream.LOGGER.warn("Sprite {} is not aligned to {} texels; the compact near atlas cannot hold it",
					sprite.contents().name(), cell);
			}
			return null;
		}
		int slotW = Math.ceilDiv(w + 2 * padX, cell) * cell;
		int slotH = Math.ceilDiv(h + 2 * padY, cell) * cell;
		int[] slot = allocateSlot(slotW, slotH);
		if (slot == null) {
			slotsFull = true;
			if (!overflowLogged) {
				overflowLogged = true;
				AtlasStream.LOGGER.warn(
					"The compact near atlas ({}x{}) is full; sprites that do not fit stay unresolved until space is freed",
					width, height
				);
			}
			return null;
		}
		return new Geometry(slot[0], slot[1], w, h, padX, padY, sprite.getX(), sprite.getY(), slotW, slotH);
	}

	private List<LevelData> albedoLevels(TextureAtlasSprite sprite, Geometry geometry, int frameIndex) {
		SpriteContentsAccessor contents = (SpriteContentsAccessor) sprite.contents();
		NativeImage[] mips = contents.atlasStream$getMipImages();
		var transparency = sprite.contents().transparency();
		boolean preserveTransparent = PRESERVE_TRANSPARENT_MIPS
			&& contents.atlasStream$getMipmapStrategy() == MipmapStrategy.AUTO
			&& transparency.hasTransparent() && transparency.hasTranslucent();
		TranslucentMipAlpha correctedAlpha = null;
		int columns = Math.max(1, mips[0].getWidth() / Math.max(1, geometry.width));
		int rows = Math.max(1, mips[0].getHeight() / Math.max(1, geometry.height));
		int frameColumn = frameIndex % columns;
		int frameRow = Math.min(rows - 1, frameIndex / columns);
		List<LevelData> levels = new ArrayList<>();
		for (int level = 0; level < Math.min(mipLevels, mips.length); level++) {
			NativeImage image = mips[level];
			int sw = Math.max(1, image.getWidth() / columns);
			int sh = Math.max(1, image.getHeight() / rows);
			int originX = frameColumn * sw;
			int originY = frameRow * sh;
			int[] source = new int[sw * sh];
			for (int y = 0; y < sh; y++) {
				for (int x = 0; x < sw; x++) {
					source[y * sw + x] = image.getPixel(originX + x, originY + y);
				}
			}
			if (preserveTransparent) {
				if (level == 0) {
					correctedAlpha = new TranslucentMipAlpha(source, sw, sh);
				} else {
					correctedAlpha.applyNext(source, sw, sh);
				}
			}
			levels.add(padded(level, geometry, source, sw, sh));
		}
		return levels;
	}

	private List<LevelData> pbrLevels(
		Identifier name,
		String suffix,
		int defaultPixel,
		PixelAverager averager,
		Geometry geometry
	) throws IOException {
		int w = geometry.width;
		int h = geometry.height;
		int[] current = new int[w * h];
		Arrays.fill(current, defaultPixel);
		Optional<Resource> resource = resourceManager.getResource(Identifier.fromNamespaceAndPath(
			name.getNamespace(),
			"textures/" + name.getPath() + suffix + ".png"
		));
		if (resource.isPresent()) {
			try (InputStream input = resource.get().open(); NativeImage source = NativeImage.read(input)) {
				int sourceWidth = source.getWidth();
				// Animated sprites store frames in a strip; only the first frame is used.
				int frameHeight = Math.min(source.getHeight(), Math.max(1, (int) ((long) sourceWidth * h / w)));
				for (int y = 0; y < h; y++) {
					for (int x = 0; x < w; x++) {
						current[y * w + x] = source.getPixel(x * sourceWidth / w, y * frameHeight / h);
					}
				}
			}
		}
		List<LevelData> levels = new ArrayList<>();
		int currentWidth = w;
		int currentHeight = h;
		for (int level = 0; level < mipLevels; level++) {
			if (level > 0) {
				int nextWidth = Math.max(1, currentWidth / 2);
				int nextHeight = Math.max(1, currentHeight / 2);
				int[] next = new int[nextWidth * nextHeight];
				for (int y = 0; y < nextHeight; y++) {
					int y0 = Math.min(currentHeight - 1, y * 2);
					int y1 = Math.min(currentHeight - 1, y * 2 + 1);
					for (int x = 0; x < nextWidth; x++) {
						int x0 = Math.min(currentWidth - 1, x * 2);
						int x1 = Math.min(currentWidth - 1, x * 2 + 1);
						next[y * nextWidth + x] = averager.average(
							current[y0 * currentWidth + x0],
							current[y0 * currentWidth + x1],
							current[y1 * currentWidth + x0],
							current[y1 * currentWidth + x1]
						);
					}
				}
				current = next;
				currentWidth = nextWidth;
				currentHeight = nextHeight;
			}
			levels.add(padded(level, geometry, current, currentWidth, currentHeight));
		}
		return levels;
	}

	// Extends the sprite's edge pixels into its atlas padding, as vanilla's clamped sprite blit does.
	private static LevelData padded(int level, Geometry geometry, int[] source, int sw, int sh) {
		int qw = Math.max(sw, Math.max(1, (geometry.width + 2 * geometry.padX) >> level));
		int qh = Math.max(sh, Math.max(1, (geometry.height + 2 * geometry.padY) >> level));
		int padX = (qw - sw) / 2;
		int padY = (qh - sh) / 2;
		int[] out = new int[qw * qh];
		for (int y = 0; y < qh; y++) {
			int sy = Math.clamp(y - padY, 0, sh - 1);
			for (int x = 0; x < qw; x++) {
				int pixel = source[sy * sw + Math.clamp(x - padX, 0, sw - 1)];
				// ARGB to little-endian RGBA bytes, ready for a direct upload.
				out[y * qw + x] = (pixel & 0xFF00FF00) | ((pixel >> 16) & 0xFF) | ((pixel & 0xFF) << 16);
			}
		}
		return new LevelData(level, geometry.x >> level, geometry.y >> level, qw, qh, out);
	}

	private void write(Layer layer, List<LevelData> levels) {
		writeAll(layer, List.of(levels));
	}

	private void writeAll(Layer layer, List<List<LevelData>> batch) {
		int previousBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		int alignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
		int rowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
		int skipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
		int skipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
		clearGlErrors();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, layer.id);
		try {
			GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
			GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
			GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
			GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
			for (List<LevelData> levels : batch) {
				for (LevelData data : levels) {
					commit(layer, data);
					ByteBuffer buffer = MemoryUtil.memAlloc(data.width * data.height * 4);
					try {
						IntBuffer ints = buffer.asIntBuffer();
						ints.put(data.argb);
						GL11.glTexSubImage2D(
							GL11.GL_TEXTURE_2D, data.level, data.x, data.y, data.width, data.height,
							GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer
						);
					} finally {
						MemoryUtil.memFree(buffer);
					}
				}
			}
			checkGl("uploading a near sprite");
		} finally {
			GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, alignment);
			GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, rowLength);
			GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, skipPixels);
			GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, skipRows);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousBinding);
		}
	}

	private void commit(Layer layer, LevelData data) {
		int levelWidth = Math.max(1, width >> data.level);
		int levelHeight = Math.max(1, height >> data.level);
		int lastPageX = Math.min(levelWidth - 1, data.x + data.width - 1) / PAGE;
		int lastPageY = Math.min(levelHeight - 1, data.y + data.height - 1) / PAGE;
		for (int pageY = data.y / PAGE; pageY <= lastPageY; pageY++) {
			for (int pageX = data.x / PAGE; pageX <= lastPageX; pageX++) {
				long key = ((long) data.level << 48) | ((long) pageX << 24) | pageY;
				if (!layer.committedPages.add(key)) {
					continue;
				}
				int commitWidth = Math.min(PAGE, levelWidth - pageX * PAGE);
				int commitHeight = Math.min(PAGE, levelHeight - pageY * PAGE);
				ARBSparseTexture.glTexPageCommitmentARB(
					GL11.GL_TEXTURE_2D, data.level, pageX * PAGE, pageY * PAGE, 0, commitWidth, commitHeight, 1, true
				);
				layer.committedBytes += (long) commitWidth * commitHeight * 4;
			}
		}
		checkGl("committing sparse pages");
	}

	private static void clearGlErrors() {
		for (int i = 0; i < 8 && GL11.glGetError() != GL11.GL_NO_ERROR; i++) {
			// Drain stale errors so they are not blamed on this upload.
		}
	}

	private static void checkGl(String action) {
		int error = GL11.glGetError();
		if (error != GL11.GL_NO_ERROR) {
			throw new IllegalStateException("OpenGL error " + error + " while " + action);
		}
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		if (albedoBaseMipView != null) {
			albedoBaseMipView.close();
			albedoBaseMipView = null;
		}
		RESIDENCY_REVISION.incrementAndGet();
		executor.shutdownNow();
		jobs.clear();
		incoming.clear();
		compactPending.clear();
		retryIncoming.clear();
		IrisPbrAtlasReducer.unregisterHolder(albedo.id);
		for (AbstractTexture wrapper : List.of(normalWrapper, specularWrapper)) {
			((AbstractTextureAccessor) wrapper).atlasStream$setTexture(null);
			((AbstractTextureAccessor) wrapper).atlasStream$setTextureView(null);
		}
		closeQuietly(albedo);
		closeQuietly(normal);
		closeQuietly(specular);
		try {
			closeRemap();
		} catch (RuntimeException exception) {
			AtlasStream.LOGGER.warn("Could not release the near remap texture", exception);
		}
	}

	private static void closeQuietly(Layer layer) {
		if (layer != null) {
			try {
				layer.view.close();
				layer.texture.close();
			} catch (RuntimeException exception) {
				AtlasStream.LOGGER.warn("Could not release a sparse near atlas", exception);
			}
		}
	}

	private static final class Layer {
		final int id;
		final GpuTexture texture;
		final GpuTextureView view;
		final Set<Long> committedPages = new java.util.HashSet<>();
		long committedBytes;

		private Layer(int id, GpuTexture texture, GpuTextureView view) {
			this.id = id;
			this.texture = texture;
			this.view = view;
		}

		static Layer create(String label, int width, int height, int mipLevels) {
			int previousBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
			clearGlErrors();
			int id = GL11.glGenTextures();
			try {
				GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
				GL11.glTexParameteri(GL11.GL_TEXTURE_2D, ARBSparseTexture.GL_TEXTURE_SPARSE_ARB, GL11.GL_TRUE);
				GL11.glTexParameteri(GL11.GL_TEXTURE_2D, ARBSparseTexture.GL_VIRTUAL_PAGE_SIZE_INDEX_ARB, 0);
				GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, mipLevels - 1);
				GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MIN_LOD, 0);
				GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD, mipLevels - 1);
				GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, mipLevels, GL11.GL_RGBA8, width, height);
				checkGl("allocating " + label);
			} catch (RuntimeException | Error exception) {
				GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousBinding);
				GL11.glDeleteTextures(id);
				throw exception;
			}
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousBinding);
			GpuTexture texture = new SparseGlTexture(label, width, height, mipLevels, id);
			GpuTextureView view;
			try {
				view = RenderSystem.getDevice().createTextureView(texture);
			} catch (RuntimeException | Error exception) {
				texture.close();
				throw exception;
			}
			return new Layer(id, texture, view);
		}
	}

	private static final class Wrapper extends AbstractTexture {
	}

	private record PreparedFrame(FrameKey key, Future<List<LevelData>> levels) {
	}

	private record FrameKey(int frame, int nextFrame, float weight) {
	}

	private record Geometry(
		int x, int y, int width, int height, int padX, int padY, int bigX, int bigY, int slotW, int slotH
	) {
	}

	private record LevelData(int level, int x, int y, int width, int height, int[] argb) {
	}

	private record Job(TextureAtlasSprite sprite, Geometry geometry, List<LevelData> albedo, Future<List<List<LevelData>>> pbr) {
	}
}
