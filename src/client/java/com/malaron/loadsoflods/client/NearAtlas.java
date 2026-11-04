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
	private static final int DEFAULT_NORMAL = 0xFF8080FF;
	private static final int DEFAULT_SPECULAR = 0;
	private static final int MAX_NEW_JOBS_PER_TICK = 8;
	private static final int MAX_UPLOADS_PER_TICK = 8;
	private static final int MAX_ANIMATIONS_IN_FLIGHT = 48;
	private static final int MAX_ANIMATION_UPLOADS_PER_TICK = 24;
	private static final FrameKey FIRST_FRAME = new FrameKey(0, 0, 0.0F);

	private final int width;
	private final int height;
	private final int mipLevels;
	private final ResourceManager resourceManager;
	private final Layer albedo;
	private final Layer normal;
	private final Layer specular;
	private final AbstractTexture normalWrapper = new Wrapper();
	private final AbstractTexture specularWrapper = new Wrapper();
	private final ExecutorService executor = Executors.newFixedThreadPool(3, runnable -> {
		Thread thread = new Thread(runnable, "Loads of Lods near-atlas loader");
		thread.setDaemon(true);
		return thread;
	});
	private final Set<TextureAtlasSprite> requested = ConcurrentHashMap.newKeySet();
	private final java.util.Map<TextureAtlasSprite, Set<Long>> sectionUses = new ConcurrentHashMap<>();
	private final Set<TextureAtlasSprite> alwaysAnimated = ConcurrentHashMap.newKeySet();
	private volatile Set<TextureAtlasSprite> activeSprites = Set.of();
	private int activeRefreshTicks;
	private static final double SECTION_RADIUS = 14.0;
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
	private volatile boolean closed;
	private volatile boolean failed;

	private NearAtlas(int width, int height, int mipLevels, ResourceManager resourceManager) {
		this.width = width;
		this.height = height;
		this.mipLevels = mipLevels;
		this.resourceManager = resourceManager;
		Layer createdAlbedo = null;
		Layer createdNormal = null;
		Layer createdSpecular = null;
		try {
			createdAlbedo = Layer.create("Loads of Lods near albedo atlas", width, height, mipLevels);
			createdNormal = Layer.create("Loads of Lods near normal atlas", width, height, mipLevels);
			createdSpecular = Layer.create("Loads of Lods near specular atlas", width, height, mipLevels);
		} catch (RuntimeException | Error exception) {
			closeQuietly(createdAlbedo);
			closeQuietly(createdNormal);
			closeQuietly(createdSpecular);
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
				LoadsOfLods.LOGGER.info(
					"Atlas {}x{} with {} mips does not suit sparse textures; Iris terrain stays on the reduced atlas",
					width, height, mipLevels
				);
				return null;
			}
			NearAtlas atlas = new NearAtlas(width, height, mipLevels, resourceManager);
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

	boolean isUsable() {
		return !closed && !failed;
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

	// Safe to call from chunk-build threads. Remembers which sections use an animated sprite.
	void noteSection(TextureAtlasSprite sprite, long sectionKey) {
		if (sectionKey != Long.MIN_VALUE && sprite.contents().isAnimated()) {
			sectionUses.computeIfAbsent(sprite, ignored -> ConcurrentHashMap.newKeySet()).add(sectionKey);
		}
	}

	void clearSection(long sectionKey) {
		for (Set<Long> keys : sectionUses.values()) {
			keys.remove(sectionKey);
		}
	}

	// Safe to call from the render thread for sprites with no section (held blocks).
	void noteAlwaysAnimated(TextureAtlasSprite sprite) {
		if (sprite.contents().isAnimated()) {
			alwaysAnimated.add(sprite);
		}
	}

	private void refreshActiveSprites() {
		double radius = LoadsOfLodsClient.getConfig().highResolutionDistance();
		Set<TextureAtlasSprite> active = new java.util.HashSet<>(alwaysAnimated);
		if (radius > 0.0) {
			var minecraft = net.minecraft.client.Minecraft.getInstance();
			var cameraEntity = minecraft.gameRenderer.mainCamera();
			var camera = cameraEntity.position();
			var forward = cameraEntity.forwardVector();
			double reach = radius + LoadsOfLodsClient.getConfig().hysteresis();
			double aspect = (double) minecraft.getWindow().getWidth() / Math.max(1, minecraft.getWindow().getHeight());
			// Cone around the view direction that covers the frustum corners, plus a small margin.
			double halfAngle = Math.atan(Math.tan(Math.toRadians(cameraEntity.getFov()) / 2.0) * Math.sqrt(1.0 + aspect * aspect))
				+ Math.toRadians(8.0);
			for (java.util.Map.Entry<TextureAtlasSprite, Set<Long>> entry : sectionUses.entrySet()) {
				for (long key : entry.getValue()) {
					double dx = SodiumChunkMeshPriority.sectionX(key) * 16.0 + 8.0 - camera.x;
					double dy = SodiumChunkMeshPriority.sectionY(key) * 16.0 + 8.0 - camera.y;
					double dz = SodiumChunkMeshPriority.sectionZ(key) * 16.0 + 8.0 - camera.z;
					double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
					if (distance - SECTION_RADIUS > reach) {
						continue;
					}
					boolean visible = distance <= SECTION_RADIUS;
					if (!visible) {
						double cos = (dx * forward.x() + dy * forward.y() + dz * forward.z()) / distance;
						double angle = Math.acos(Math.max(-1.0, Math.min(1.0, cos)));
						visible = angle <= halfAngle + Math.asin(Math.min(1.0, SECTION_RADIUS / distance));
					}
					if (visible) {
						active.add(entry.getKey());
						break;
					}
				}
			}
		} else {
			active.clear();
		}
		activeSprites = active;
	}

	// Render thread only.
	void service() {
		if (closed || failed) {
			return;
		}
		if (activeRefreshTicks-- <= 0) {
			activeRefreshTicks = 3;
			refreshActiveSprites();
		}
		for (int started = 0; started < MAX_NEW_JOBS_PER_TICK; started++) {
			TextureAtlasSprite sprite = incoming.poll();
			if (sprite == null) {
				break;
			}
			startJob(sprite);
		}
		int uploads = 0;
		Iterator<Job> iterator = jobs.iterator();
		while (iterator.hasNext() && uploads < MAX_UPLOADS_PER_TICK) {
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
		long bytes = albedo.committedBytes + normal.committedBytes + specular.committedBytes;
		return List.of(
			"Near sparse atlas: " + residentSprites + " sprites resident, " + (jobs.size() + incoming.size())
				+ " pending, " + failedSprites + " failed",
			"Near animation: " + activeSprites.size() + " sprites active, " + uploadsPerSecond + " uploads/s, backlog avg "
				+ String.format(java.util.Locale.ROOT, "%.0f", averageBacklog) + " peak " + displayedPeak,
			"Near committed VRAM: " + String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
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
			residentSprites++;
			resident.put(job.sprite, job.geometry);
		} catch (java.util.concurrent.ExecutionException | InterruptedException | RuntimeException exception) {
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
		int padX = Math.round(sprite.getU0() * width) - sprite.getX();
		int padY = Math.round(sprite.getV0() * height) - sprite.getY();
		if (w <= 0 || h <= 0 || padX < 0 || padY < 0
				|| sprite.getX() < 0 || sprite.getY() < 0
				|| sprite.getX() + w + padX * 2 > width || sprite.getY() + h + padY * 2 > height) {
			return null;
		}
		return new Geometry(sprite.getX(), sprite.getY(), w, h, padX, padY);
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

	private record Geometry(int x, int y, int width, int height, int padX, int padY) {
	}

	private record LevelData(int level, int x, int y, int width, int height, int[] argb) {
	}

	private record Job(TextureAtlasSprite sprite, Geometry geometry, List<LevelData> albedo, Future<List<List<LevelData>>> pbr) {
	}
}
