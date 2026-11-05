package com.malaron.loadsoflods.client;

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

import com.malaron.loadsoflods.LoadsOfLods;
import com.malaron.loadsoflods.client.AtlasPreviewState.PixelAverager;
import com.malaron.loadsoflods.client.mixin.AbstractTextureAccessor;
import com.malaron.loadsoflods.client.mixin.SpriteContentsAccessor;
import com.malaron.loadsoflods.lod.LodConfig;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.ARBSparseTexture;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL42;
import org.lwjgl.system.MemoryUtil;

// Full-resolution albedo/normal/specular atlases backed by OpenGL sparse textures. They keep the original atlas
// layout, so terrain UVs are unchanged, but physical memory is committed only for sprites terrain actually uses.
final class NearAtlas implements AutoCloseable {
	private static final int PAGE = 128;
	private static final int MAX_COMPACT_SIZE = 16384;
	private static final int DEFAULT_NORMAL = 0xFF8080FF;
	private static final int DEFAULT_SPECULAR = 0;
	private static final int MAX_NEW_JOBS_PER_TICK = 8;
	private static final int MAX_UPLOADS_PER_TICK = 8;
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
	private final java.util.Map<Long, java.util.ArrayDeque<int[]>> freeSlots = new java.util.HashMap<>();
	private int shelfCursorY;
	private boolean misalignedLogged;
	private final int mipLevels;
	private final ResourceManager resourceManager;
	private final Layer albedo;
	private final Layer normal;
	private final Layer specular;
	private final AbstractTexture normalWrapper = new Wrapper();
	private final AbstractTexture specularWrapper = new Wrapper();
	private final ExecutorService executor = Executors.newFixedThreadPool(
		Math.max(3, Math.min(8, Runtime.getRuntime().availableProcessors() / 2)),
		runnable -> {
			Thread thread = new Thread(runnable, "Loads of Lods near-atlas loader");
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
			createdAlbedo = Layer.create("Loads of Lods near albedo atlas", width, height, mipLevels);
			createdNormal = Layer.create("Loads of Lods near normal atlas", width, height, mipLevels);
			createdSpecular = Layer.create("Loads of Lods near specular atlas", width, height, mipLevels);
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
		((AbstractTextureAccessor) normalWrapper).loadsOfLods$setTexture(normal.texture);
		((AbstractTextureAccessor) normalWrapper).loadsOfLods$setTextureView(normal.view);
		((AbstractTextureAccessor) specularWrapper).loadsOfLods$setTexture(specular.texture);
		((AbstractTextureAccessor) specularWrapper).loadsOfLods$setTextureView(specular.view);
		IrisPbrAtlasReducer.registerHolder(albedo.id, normalWrapper, specularWrapper);
	}

	static NearAtlas create(int width, int height, int mipLevels, ResourceManager resourceManager) {
		try {
			boolean sparse = GL.getCapabilities().GL_ARB_sparse_texture;
			if (!sparse) {
				LoadsOfLods.LOGGER.info(
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
			LoadsOfLods.LOGGER.info(
				"Created sparse near atlases {}x{} with {} mips (page {}x{}); memory is committed per used sprite",
				width, height, mipLevels, PAGE, PAGE
			);
			return atlas;
		} catch (RuntimeException | Error exception) {
			LoadsOfLods.LOGGER.error("Could not create sparse near atlases; Iris terrain stays on the reduced atlas", exception);
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
			LoadsOfLods.LOGGER.info(
				"Atlas {}x{} with {} mips does not suit sparse textures; Iris terrain stays on the reduced atlas",
				bigWidth, bigHeight, mipLevels
			);
			return null;
		}
		NearAtlas atlas = new NearAtlas(bigWidth, bigHeight, size, size, mipLevels, resourceManager, true);
		LoadsOfLods.LOGGER.info(
			"Created compact sparse near atlases {}x{} with {} mips for a {}x{} layout; terrain UVs are remapped in the vertex shader",
			size, size, mipLevels, bigWidth, bigHeight
		);
		return atlas;
	}

	boolean isUsable() {
		return !closed && !failed;
	}

	boolean isCompact() {
		return compact;
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
		GpuTexture texture = new SparseGlTexture("Loads of Lods near remap", cellsX, rows, 1, id);
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
		java.util.ArrayDeque<int[]> free = freeSlots.get(((long) w << 32) | h);
		if (free != null && !free.isEmpty()) {
			return free.poll();
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
			freeSlots.computeIfAbsent(((long) g.slotW << 32) | g.slotH, ignored -> new java.util.ArrayDeque<>())
				.add(new int[] {g.x, g.y});
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

	GpuTextureView albedoView() {
		return albedo.view;
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
		sectionSprites.computeIfAbsent(sectionKey, ignored -> ConcurrentHashMap.newKeySet()).add(sprite);
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

	// Called when a section is about to be re-meshed; its sprite list is rebuilt by the mesh task.
	void clearSection(long sectionKey) {
		sectionSprites.put(sectionKey, ConcurrentHashMap.newKeySet());
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
		LodConfig config = LoadsOfLodsClient.getConfig();
		double radius = config.highResolutionDistance();
		long now = System.nanoTime();
		var minecraft = net.minecraft.client.Minecraft.getInstance();
		var cameraEntity = minecraft.gameRenderer.mainCamera();
		var camera = cameraEntity.position();
		cameraX = camera.x;
		cameraY = camera.y;
		cameraZ = camera.z;

		Set<TextureAtlasSprite> active = new java.util.HashSet<>();
		Set<TextureAtlasSprite> needed = new java.util.HashSet<>();
		if (radius > 0.0) {
			var forward = cameraEntity.forwardVector();
			double drawReach = radius + config.hysteresis();
			double reach = drawReach + PREFETCH_BLOCKS;
			nearReach = reach;
			double aspect = (double) minecraft.getWindow().getWidth() / Math.max(1, minecraft.getWindow().getHeight());
			// Cone around the view direction that covers the frustum corners, plus a small margin.
			double halfAngle = Math.atan(Math.tan(Math.toRadians(cameraEntity.getFov()) / 2.0) * Math.sqrt(1.0 + aspect * aspect))
				+ Math.toRadians(8.0);
			for (java.util.Map.Entry<Long, Set<TextureAtlasSprite>> entry : sectionSprites.entrySet()) {
				long key = entry.getKey();
				double dx = SodiumChunkMeshPriority.sectionX(key) * 16.0 + 8.0 - camera.x;
				double dy = SodiumChunkMeshPriority.sectionY(key) * 16.0 + 8.0 - camera.y;
				double dz = SodiumChunkMeshPriority.sectionZ(key) * 16.0 + 8.0 - camera.z;
				double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (distance - SECTION_RADIUS > reach) {
					continue;
				}
				needed.addAll(entry.getValue());
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
				needed.add(entry.getKey());
				if (entry.getKey().contents().isAnimated()) {
					active.add(entry.getKey());
				}
			}
		}
		for (TextureAtlasSprite sprite : needed) {
			request(sprite);
			if (resident.containsKey(sprite)) {
				lastNeeded.put(sprite, now);
			}
		}
		activeSprites = active;
		evictUnneeded(needed, config, radius <= 0.0, now);
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
			LoadsOfLods.LOGGER.error("Could not release near sprites", exception);
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
		for (long key : pages) {
			Integer remaining = pageRefs.merge(key, -1, Integer::sum);
			if (remaining != null && remaining <= 0) {
				pageRefs.remove(key);
				uncommit(albedo, key);
				uncommit(normal, key);
				uncommit(specular, key);
			}
		}
	}

	private void uncommit(Layer layer, long key) {
		if (!layer.committedPages.remove(key)) {
			return;
		}
		int level = (int) (key >>> 48);
		int pageX = (int) ((key >> 24) & 0xFFFFFF);
		int pageY = (int) (key & 0xFFFFFF);
		int commitWidth = Math.min(PAGE, Math.max(1, width >> level) - pageX * PAGE);
		int commitHeight = Math.min(PAGE, Math.max(1, height >> level) - pageY * PAGE);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, layer.id);
		ARBSparseTexture.glTexPageCommitmentARB(
			GL11.GL_TEXTURE_2D, level, pageX * PAGE, pageY * PAGE, 0, commitWidth, commitHeight, 1, false
		);
		layer.committedBytes -= (long) commitWidth * commitHeight * 4;
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
			activeRefreshTicks = 3;
			refreshSprites();
		}
		// Until the first sprites around the camera are resident the near terrain is drawn from the reduced atlas,
		// so catch up faster than the steady-state limits allow.
		int newJobLimit = warm ? MAX_NEW_JOBS_PER_TICK : MAX_NEW_JOBS_PER_TICK * 4;
		int uploadLimit = warm ? MAX_UPLOADS_PER_TICK : MAX_UPLOADS_PER_TICK * 4;
		for (int started = 0; started < newJobLimit; started++) {
			TextureAtlasSprite sprite = incoming.poll();
			if (sprite == null) {
				break;
			}
			startJob(sprite);
		}
		int uploads = 0;
		Iterator<Job> iterator = jobs.iterator();
		while (iterator.hasNext() && uploads < uploadLimit) {
			Job job = iterator.next();
			if (!job.pbr.isDone()) {
				continue;
			}
			iterator.remove();
			uploads++;
			finishJob(job);
			if (failed) {
				return;
			}
		}
		flushAnimations();
		if (!warm) {
			long age = System.nanoTime() - createdNanos;
			boolean drained = incoming.isEmpty() && jobs.isEmpty() && !requested.isEmpty();
			if ((drained && age >= WARM_MIN_NANOS) || age >= WARM_MAX_NANOS) {
				warm = true;
			}
		}
	}

	// False until the sprites needed around the camera have streamed in after creation, so near sections are drawn
	// from the reduced atlas instead of showing unpopulated (black) pages.
	boolean isWarm() {
		return warm;
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
				LoadsOfLods.LOGGER.error("Could not animate near sprites", exception);
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
			"Near sparse atlas: " + residentSprites + " sprites resident, " + (jobs.size() + incoming.size())
				+ " pending, " + evictedSprites + " evicted, " + failedSprites + " failed",
			"Near animation: " + activeSprites.size() + " sprites active, " + uploadsPerSecond + " uploads/s, backlog avg "
				+ String.format(java.util.Locale.ROOT, "%.0f", averageBacklog) + " peak " + displayedPeak,
			"Near committed VRAM: " + String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
				+ " / budget " + LoadsOfLodsClient.getConfig().highResolutionCacheMiB() + " MiB"
				+ (failed ? " (disabled after an error)" : "")
		);
	}

	private void startJob(TextureAtlasSprite sprite) {
		Geometry geometry = geometry(sprite);
		if (geometry == null) {
			failedSprites++;
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
			LoadsOfLods.LOGGER.warn("Could not prepare near sprite {}: {}", sprite.contents().name(), exception.getMessage());
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
		} catch (java.util.concurrent.ExecutionException | InterruptedException | RuntimeException exception) {
			freeSlot(job.geometry);
			failedSprites++;
			LoadsOfLods.LOGGER.error("Could not upload near sprite {}", job.sprite.contents().name(), exception);
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
				LoadsOfLods.LOGGER.warn("Sprite {} is not aligned to {} texels; the compact near atlas cannot hold it",
					sprite.contents().name(), cell);
			}
			return null;
		}
		int slotW = Math.ceilDiv(w + 2 * padX, cell) * cell;
		int slotH = Math.ceilDiv(h + 2 * padY, cell) * cell;
		int[] slot = allocateSlot(slotW, slotH);
		if (slot == null) {
			return null;
		}
		return new Geometry(slot[0], slot[1], w, h, padX, padY, sprite.getX(), sprite.getY(), slotW, slotH);
	}

	private List<LevelData> albedoLevels(TextureAtlasSprite sprite, Geometry geometry, int frameIndex) {
		NativeImage[] mips = ((SpriteContentsAccessor) sprite.contents()).loadsOfLods$getMipImages();
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
		executor.shutdownNow();
		jobs.clear();
		incoming.clear();
		IrisPbrAtlasReducer.unregisterHolder(albedo.id);
		for (AbstractTexture wrapper : List.of(normalWrapper, specularWrapper)) {
			((AbstractTextureAccessor) wrapper).loadsOfLods$setTexture(null);
			((AbstractTextureAccessor) wrapper).loadsOfLods$setTextureView(null);
		}
		closeQuietly(albedo);
		closeQuietly(normal);
		closeQuietly(specular);
		try {
			closeRemap();
		} catch (RuntimeException exception) {
			LoadsOfLods.LOGGER.warn("Could not release the near remap texture", exception);
		}
	}

	private static void closeQuietly(Layer layer) {
		if (layer != null) {
			try {
				layer.view.close();
				layer.texture.close();
			} catch (RuntimeException exception) {
				LoadsOfLods.LOGGER.warn("Could not release a sparse near atlas", exception);
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
