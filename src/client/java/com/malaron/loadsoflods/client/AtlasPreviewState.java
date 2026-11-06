package com.malaron.loadsoflods.client;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import com.malaron.loadsoflods.LoadsOfLods;
import com.malaron.loadsoflods.cache.VirtualTextureTileBuilder;
import com.malaron.loadsoflods.cache.VirtualTextureTileBuilder.MipImage;
import com.malaron.loadsoflods.cache.VirtualTextureTileBuilder.SpriteSource;
import com.malaron.loadsoflods.cache.VirtualTextureTileBuilder.TileMip;
import com.malaron.loadsoflods.cache.VirtualTextureLayout;
import com.malaron.loadsoflods.cache.VirtualTextureRequestTracker;
import com.malaron.loadsoflods.cache.VirtualTextureTileKey;
import com.malaron.loadsoflods.cache.VirtualTextureTileRequestQueue;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.malaron.loadsoflods.client.mixin.AbstractTextureAccessor;
import com.malaron.loadsoflods.client.mixin.SpriteAnimationStateAccessor;
import com.malaron.loadsoflods.client.mixin.SpriteContentsAccessor;
import com.malaron.loadsoflods.client.mixin.TextureAtlasAccessor;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.AnimationFrame;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

public final class AtlasPreviewState {
	private static final int VIRTUAL_TILE_SIZE = 128;
	private static final long MAX_PREVIEW_BYTES = 2048L * 1024 * 1024;
	private static final int MAX_PENDING_TILE_REQUESTS = 8192;
	private static final int MAX_UV_QUADS_PER_TICK = 128;
	private static int animatedTileFailuresLogged;
	private static final int MAX_TILE_KEYS_PER_TICK = 512;
	private static final int MAX_TILE_UPLOADS_PER_TICK = 4;
	private static final long MAX_TILE_UPLOAD_TIME_NANOS_PER_TICK = 4_000_000L;
	private static final AtomicBoolean BUILDING = new AtomicBoolean();
	private static final VirtualTextureRequestTracker VIRTUAL_TEXTURE_REQUEST_TRACKER =
		new VirtualTextureRequestTracker();
	private static final ArrayDeque<VirtualTextureRequestTracker.UvQuad> pendingVirtualTextureQuads =
		new ArrayDeque<>();
	private static final Set<VirtualTextureRequestTracker.UvQuad> pendingItemQuads = new HashSet<>();
	private static final VirtualTextureTileRequestQueue pendingVirtualTextureTiles =
		new VirtualTextureTileRequestQueue(MAX_PENDING_TILE_REQUESTS);
	private static final Set<VirtualTextureTileKey> animatedVirtualTextureTiles = new HashSet<>();
	private static volatile boolean lowPreviewEnabled;
	private static volatile boolean distancePreviewEnabled;
	private static volatile long guiItemAtlasRevision;
	private static volatile TextureAtlas previewedAtlas;
	private static volatile List<Object> reducedPbrAtlases = List.of();
	private static volatile GpuTexture lowResolutionTexture;
	private static volatile GpuTextureView lowResolutionTextureView;
	private static volatile VirtualTextureGpuPageTable virtualTextureGpuPageTable;
	private static volatile VirtualTextureTileBuilder virtualTextureTileBuilder;
	private static volatile VirtualTextureGpuCache virtualTextureGpuCache;
	private static volatile VirtualTextureLayout virtualTextureLayout;
	private static VirtualTextureMetadataTexture virtualTextureMetadataTexture;
	private static VirtualTextureGpuPageTable fallbackPageTable;
	private static VirtualTextureGpuCache fallbackTileCache;
	private static VirtualTextureMetadataTexture fallbackMetadataTexture;
	private static boolean atlasOwnsLowResolutionTexture;
	private static boolean originalAtlasReleaseConfirmed;
	private static List<AnimatedSpriteData> animatedSprites = List.of();
	private static boolean animatedStateMismatchLogged;
	private static int lowAtlasDownscaleFactor = 1;
	private static volatile long generation;
	private static VirtualTextureRequestTracker.TileCursor activeVirtualTextureCursor;
	private static VirtualTextureRequestTracker.UvQuad activeVirtualTextureQuad;
	private static double activeVirtualTextureDistanceSquared = Double.POSITIVE_INFINITY;

	private AtlasPreviewState() {
	}

	public static void buildAndEnable(
		TextureAtlas atlas,
		ResourceManager resourceManager,
		FabricClientCommandSource source,
		boolean enableDistancePreview
	) {
		buildAndEnable(atlas, resourceManager, source::sendFeedback, source::sendError, enableDistancePreview);
	}

	public static void buildAndEnable(
		TextureAtlas atlas,
		ResourceManager resourceManager,
		java.util.function.Consumer<net.minecraft.network.chat.Component> feedback,
		java.util.function.Consumer<net.minecraft.network.chat.Component> errors,
		boolean enableDistancePreview
	) {
		if (!BUILDING.compareAndSet(false, true)) {
			throw new IllegalStateException("A low-resolution atlas preview build is already running");
		}
		long buildGeneration;
		List<SpriteRegion> regions;
		int atlasWidth;
		int atlasHeight;
		int mipLevelCount;
		int downscaleFactor;
		boolean reducePbr;
		GpuTexture highResolutionTexture;
		synchronized (AtlasPreviewState.class) {
			buildGeneration = generation;
		}
		boolean defer = deferActivationUntilIris;
		deferActivationUntilIris = false;
		try {
			TextureAtlasAccessor geometry = (TextureAtlasAccessor) atlas;
			highResolutionTexture = atlas.getTexture();
			atlasWidth = geometry.loadsOfLods$getWidth();
			atlasHeight = geometry.loadsOfLods$getHeight();
			mipLevelCount = geometry.loadsOfLods$getMipLevelCount();
			regions = snapshotRegions(atlas, atlasWidth, atlasHeight);
			downscaleFactor = selectDownscaleFactor(regions, atlasWidth, atlasHeight);
			// The PBR images only need the resource pack, so they can be built before Iris has its pipeline.
			reducePbr = enableDistancePreview && (SodiumLodBridge.isIrisShaderPackInUse()
				|| (defer && SodiumLodBridge.isIrisConfiguredForShaders()));
		} catch (RuntimeException exception) {
			BUILDING.set(false);
			throw exception;
		}

		CompletableFuture.supplyAsync(() -> {
			try {
				return createLowAtlas(
					resourceManager,
					regions,
					atlasWidth,
					atlasHeight,
					mipLevelCount,
					downscaleFactor,
					reducePbr
				);
			} catch (IOException exception) {
				throw new java.util.concurrent.CompletionException(exception);
			}
		}).whenComplete((image, failure) -> Minecraft.getInstance().execute(() -> activateOrPark(defer, () -> {
			try {
				if (failure != null) {
					Throwable cause = failure.getCause() == null ? failure : failure.getCause();
					throw new IllegalStateException("Could not build the low-resolution block atlas", cause);
				}
				boolean releaseHighResolutionAtlas = enableDistancePreview;
				try (image) {
					boolean currentAtlas;
					synchronized (AtlasPreviewState.class) {
						currentAtlas = generation == buildGeneration
							&& Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS) == atlas;
					}
					if (!currentAtlas) {
						LoadsOfLods.LOGGER.info("Discarding low-resolution atlas preview because the source atlas changed");
						return;
					}
					createLowResolutionTexture(
						atlas,
						highResolutionTexture,
						image,
						regions,
						atlasWidth,
						atlasHeight,
						mipLevelCount,
						releaseHighResolutionAtlas
					);
				}
				updateAnimatedFrames(atlas);
				previewedAtlas = atlas;
				lowPreviewEnabled = !enableDistancePreview;
				distancePreviewEnabled = enableDistancePreview;
				guiItemAtlasRevision++;
				if (enableDistancePreview) {
					SodiumLodBridge.scheduleVisibleTerrainRebuild();
				}
				if (reloadBuildStartNanos != 0L) {
					LoadsOfLods.LOGGER.info("Reduced atlas build and activation took {} ms",
						(System.nanoTime() - reloadBuildStartNanos) / 1_000_000L);
					reloadBuildStartNanos = 0L;
				}
				feedback.accept(
					net.minecraft.network.chat.Component.literal(
						(enableDistancePreview
							? SodiumLodBridge.isIrisShaderPackInUse()
								? "Reduced albedo preview enabled. Iris shaderpack detected; all terrain uses low-resolution albedo and Iris PBR maps are reduced to match. "
								: "Streamed albedo LOD enabled. Nearby terrain uses the bounded high-resolution tile cache, and the low atlas is the fallback. "
							: "Separate low-resolution block atlas enabled. Run /lods preview-high to switch back. ")
							+ "Low atlas is " + lowResolutionTexture.getWidth(0) + "x"
							+ lowResolutionTexture.getHeight(0)
							+ (releaseHighResolutionAtlas
								? originalAtlasReleaseConfirmed
									? ". The full block atlas was released. Reload resource packs to restore it."
									: ". The original atlas allocation could not be confirmed as released; check latest.log."
								: "")
							+ " (1:" + image.downscaleFactor() + ")."
					)
				);
			} catch (RuntimeException exception) {
				LoadsOfLods.LOGGER.error("Could not activate low-resolution atlas preview", exception);
				errors.accept(
					net.minecraft.network.chat.Component.literal("Low-atlas preview failed: " + exception.getMessage())
				);
			} finally {
				reloadBuildRunning = false;
				BUILDING.set(false);
			}
		})));
	}

	private static boolean deferActivationUntilIris;
	private static Runnable parkedActivation;
	private static int parkedActivationTicks;

	private static boolean irisActivationReady() {
		return Minecraft.getInstance().level != null && SodiumLodBridge.isIrisShaderPackInUse();
	}

	// A build finished before Iris created its pipeline is held until it exists, since the GPU side needs Iris's
	// PBR holders. Only the cheap GPU step waits; the images are already built.
	private static void activateOrPark(boolean defer, Runnable activate) {
		if (defer && !irisActivationReady()) {
			parkedActivation = activate;
			parkedActivationTicks = 0;
			return;
		}
		activate.run();
	}

	private static void serviceParkedActivation() {
		Runnable parked = parkedActivation;
		if (parked == null) {
			return;
		}
		boolean ready = irisActivationReady();
		if (Minecraft.getInstance().level != null) {
			parkedActivationTicks++;
		}
		// If Iris never activates (shaders failed to load), activate anyway after a while.
		if (ready || parkedActivationTicks > 1200) {
			parkedActivation = null;
			parked.run();
		}
	}

	public static boolean isBuilding() {
		return BUILDING.get();
	}

	public static boolean hasLowAtlas() {
		return previewedAtlas != null && lowResolutionTexture != null && lowResolutionTextureView != null;
	}

	public static VirtualTextureGpuPageTable virtualTexturePageTable() {
		VirtualTextureGpuPageTable pageTable = virtualTextureGpuPageTable;
		if (pageTable == null) {
			throw new IllegalStateException("Build the low atlas before requesting its virtual page table");
		}
		return pageTable;
	}

	public static synchronized int uploadHighResolutionTile(VirtualTextureTileKey key) {
		VirtualTextureTileBuilder tileBuilder = virtualTextureTileBuilder;
		if (tileBuilder == null || virtualTextureGpuPageTable == null) {
			throw new IllegalStateException("Build the low atlas before uploading virtual texture tiles");
		}
		VirtualTextureGpuPageTable pageTable = virtualTextureGpuPageTable;
		VirtualTextureGpuCache cache = virtualTextureGpuCache;
		if (cache == null) {
			throw new IllegalStateException("Virtual texture GPU cache was not initialized with the atlas");
		}
		Integer residentLayer = cache.get(key);
		if (residentLayer != null) {
			return residentLayer;
		}
		int slot = uploadTileImages(cache, tileBuilder, key, false);
		AnimatedTile animated = snapshotAnimatedTile(tileBuilder, key);
		if (!animated.names().isEmpty()) {
			animatedTiles.put(key, animated);
		}
		return slot;
	}

	private static volatile NearAtlas nearAtlas;

	private static boolean guiItemDraw;
	private static final long ITEM_REQUEST_INTERVAL_NANOS = 1_000_000_000L;
	private static final int MAX_TRACKED_ITEM_SPRITES = 4096;
	private static final Map<TextureAtlasSprite, Long> itemSpriteRequests = new java.util.LinkedHashMap<>();
	private static final Map<String, Long> animatedItemSprites = new HashMap<>();
	private static long itemSpriteGeneration = Long.MIN_VALUE;

	public static void setGuiItemDraw(boolean value) {
		guiItemDraw = value;
	}

	public static boolean useVirtualTextureForItemDraw() {
		return !guiItemDraw && isDistancePreviewEnabled() && virtualTextureGpuCache != null
			&& virtualTextureGpuPageTable != null && virtualTextureMetadataTexture != null;
	}

	public static boolean isReducedBlockAtlasView(GpuTextureView view) {
		return view != null && view == lowResolutionTextureView;
	}

	public static long virtualTextureGeneration() {
		VirtualTextureGpuPageTable pageTable = virtualTextureGpuPageTable;
		return pageTable == null ? Long.MIN_VALUE : pageTable.layout().atlasGeneration();
	}

	public static long guiItemAtlasRevision() {
		return guiItemAtlasRevision;
	}

	private static volatile boolean vanillaRemapAvailable;

	public static void markVanillaRemapAvailable() {
		vanillaRemapAvailable = true;
	}

	// Non-terrain draws (held blocks, falling blocks) sample the blocks atlas through its texture slot. In compact
	// mode they get the compact atlas too, which only works because the vanilla vertex shaders remap their UVs.
	public static GpuTextureView nearAtlasViewForSlot(Object atlas) {
		NearAtlas near = nearAtlas;
		if (near == null || !slotUsesNearAtlas(near, atlas)) {
			return null;
		}
		return near.albedoView();
	}

	public static GpuTexture nearAtlasTextureForSlot(Object atlas) {
		NearAtlas near = nearAtlas;
		if (near == null || !slotUsesNearAtlas(near, atlas)) {
			return null;
		}
		return near.albedoTexture();
	}

	private static boolean slotUsesNearAtlas(NearAtlas near, Object atlas) {
		return (!near.isCompact() || vanillaRemapAvailable)
			&& !guiItemDraw
			&& atlas == previewedAtlas
			&& near.isUsable()
			&& SodiumLodBridge.isIrisShaderPackInUse();
	}

	public static GpuTextureView nearRemapView() {
		NearAtlas near = nearAtlas;
		return near != null && near.isUsable() && near.isCompact() ? near.remapView() : null;
	}

	public static int nearPlaceholderGlId() {
		nearRemapPlaceholder();
		try {
			return (Integer) nearRemapPlaceholderTexture.getClass().getMethod("iris$getGlId").invoke(nearRemapPlaceholderTexture);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			return 0;
		}
	}

	public static int nearRemapGlId() {
		NearAtlas near = nearAtlas;
		if (near != null && near.isUsable() && near.isCompact() && near.remapGlId() != 0) {
			return near.remapGlId();
		}
		return nearPlaceholderGlId();
	}

	private static GpuTexture nearRemapPlaceholderTexture;
	private static GpuTextureView nearRemapPlaceholderView;

	public static synchronized GpuTextureView nearRemapPlaceholder() {
		if (nearRemapPlaceholderView == null) {
			var device = RenderSystem.getDevice();
			nearRemapPlaceholderTexture = device.createTexture(
				"Loads of Lods near remap placeholder",
				GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
				com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM,
				1,
				1,
				1,
				1
			);
			nearRemapPlaceholderView = device.createTextureView(nearRemapPlaceholderTexture);
		}
		return nearRemapPlaceholderView;
	}

	public static synchronized void recordSpriteUsage(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite) {
		NearAtlas near = nearAtlas;
		if (near != null && !guiItemDraw && TextureAtlas.LOCATION_BLOCKS.equals(sprite.atlasLocation())) {
			near.touch(sprite);
		}
		if (!TextureAtlas.LOCATION_BLOCKS.equals(sprite.atlasLocation()) || !useVirtualTextureForItemDraw()
				|| SodiumLodBridge.isIrisShaderPackInUse()) {
			return;
		}
		if (itemSpriteGeneration != generation) {
			itemSpriteRequests.clear();
			animatedItemSprites.clear();
			itemSpriteGeneration = generation;
		}
		long now = System.nanoTime();
		if (sprite.contents().isAnimated()) {
			animatedItemSprites.put(sprite.contents().name().toString(), now);
		}
		Long lastRequest = itemSpriteRequests.get(sprite);
		if (lastRequest != null && now - lastRequest < ITEM_REQUEST_INTERVAL_NANOS) {
			return;
		}
		if (lastRequest == null && itemSpriteRequests.size() >= MAX_TRACKED_ITEM_SPRITES) {
			itemSpriteRequests.remove(itemSpriteRequests.keySet().iterator().next());
		}
		itemSpriteRequests.put(sprite, now);
		VIRTUAL_TEXTURE_REQUEST_TRACKER.recordSpriteUsage(
			sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(), generation, 0.0
		);
	}

	public static boolean isNearAtlasWarm() {
		NearAtlas near = nearAtlas;
		return near == null || !near.isUsable() || near.isWarm();
	}

	public static boolean isNearSectionResolved(long sectionKey) {
		NearAtlas near = nearAtlas;
		return near == null || !near.isUsable() || near.sectionResolved(sectionKey);
	}

	public static boolean isNearAtlasActive() {
		NearAtlas near = nearAtlas;
		return near != null && near.isUsable();
	}

	public static GpuTextureView nearAtlasView() {
		NearAtlas near = nearAtlas;
		return near != null && near.isUsable() ? near.albedoView() : null;
	}

	public static void recordChunkSpriteUsage(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite) {
		NearAtlas near = nearAtlas;
		long sectionKey = SodiumChunkMeshPriority.currentSectionKey();
		// Kept in every mode so the streamed preview knows each section's sprites after shaders are switched off,
		// when Sodium does not remesh the sections.
		if (sectionKey != Long.MIN_VALUE) {
			streamSections.computeIfAbsent(sectionKey, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet())
				.add(sprite);
		}
		if (near != null) {
			near.noteTerrainSprite(sprite, sectionKey);
		} else {
			if (sectionKey != Long.MIN_VALUE) {
				// Streaming every sprite of every meshed section floods the tile queue; only sections near the
				// camera are requested, and refreshStreamedSections() requests the rest as they come into range.
				double reach = streamReach;
				if (reach < 0.0 || sectionDistance(sectionKey) - SECTION_RADIUS > reach) {
					return;
				}
				requestedStreamSections.add(sectionKey);
			}
		}
		VIRTUAL_TEXTURE_REQUEST_TRACKER.recordSpriteUsage(
			sprite.getU0(),
			sprite.getV0(),
			sprite.getU1(),
			sprite.getV1(),
			generation,
			SodiumChunkMeshPriority.distanceSquared()
		);
	}

	private static final double SECTION_RADIUS = 14.0;
	private static final double STREAM_PREFETCH_BLOCKS = 32.0;
	private static final int STREAM_MIP0_BLOCKS = 48;
	private static final int STREAM_REFRESH_TICKS = 5;
	private static final int STREAM_REREQUEST_REFRESHES = 20;
	private static int streamRerequestCounter;
	private static final java.util.Map<Long, java.util.Set<net.minecraft.client.renderer.texture.TextureAtlasSprite>> streamSections =
		new java.util.concurrent.ConcurrentHashMap<>();
	private static final java.util.Set<Long> requestedStreamSections = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static volatile double streamReach = -1.0;
	private static volatile double streamCameraX;
	private static volatile double streamCameraY;
	private static volatile double streamCameraZ;
	private static int streamRefreshTicks;
	private static long streamGeneration = Long.MIN_VALUE;
	private static volatile java.util.Set<String> activeAnimatedSprites = java.util.Set.of();
	// Displayed frame (strip index) of every animated sprite, updated each tick by updateAnimatedFrames.
	private static final java.util.Map<String, FrameState> spriteFrames = new java.util.concurrent.ConcurrentHashMap<>();
	private static final int BLEND_STEPS = 4;

	// frame is the strip index shown, next the one it blends toward by blend/BLEND_STEPS (0 when not blending).
	private record FrameState(int frame, int next, int blend) {
		private static final FrameState FIRST = new FrameState(0, 0, 0);
	}
	private static final java.util.LinkedHashMap<VirtualTextureTileKey, AnimatedTile> animatedTiles =
		new java.util.LinkedHashMap<>();
	private static final int MAX_ANIMATED_TILES_IN_FLIGHT = 48;
	private static final int MAX_ANIMATED_TILE_UPLOADS_PER_TICK = 48;
	private static final long MAX_ANIMATED_TILE_UPDATE_NANOS = 3_000_000L;
	private static final java.util.concurrent.ExecutorService animatedTileExecutor =
		java.util.concurrent.Executors.newFixedThreadPool(4, runnable -> {
			Thread thread = new Thread(runnable, "Loads of Lods animated tiles");
			thread.setDaemon(true);
			return thread;
		});
	private static final java.util.LinkedHashMap<VirtualTextureTileKey, PreparedTile> preparingTiles =
		new java.util.LinkedHashMap<>();

	private static final java.util.LinkedHashMap<VirtualTextureTileKey, PreparedTile> loadingTiles =
		new java.util.LinkedHashMap<>();
	private static final int MAX_TILES_LOADING = 64;
	private static final int MAX_TILE_UPLOADS_PER_FRAME_TICK = 32;

	// New tiles are built on worker threads in distance order; the render thread only writes finished ones.
	private static void submitPendingTiles(VirtualTextureGpuCache cache, VirtualTextureTileBuilder builder) {
		int cacheMipLevels = Math.min(
			Integer.numberOfTrailingZeros(VIRTUAL_TILE_SIZE) + 1,
			virtualTextureGpuPageTable.layout().mipLevelCount()
		);
		while (loadingTiles.size() < MAX_TILES_LOADING) {
			VirtualTextureTileKey key = pendingVirtualTextureTiles.poll();
			if (key == null) {
				return;
			}
			if (cache.get(key) != null || loadingTiles.containsKey(key)) {
				continue;
			}
			AnimatedTile snapshot = snapshotAnimatedTile(builder, key);
			loadingTiles.put(key, new PreparedTile(snapshot, animatedTileExecutor.submit(
				() -> buildTileImages(builder, key, cacheMipLevels)
			)));
		}
	}

	private static List<NativeImage> buildTileImages(
		VirtualTextureTileBuilder builder,
		VirtualTextureTileKey key,
		int cacheMipLevels
	) {
		List<TileMip> builtMips = builder.build(key, cacheMipLevels);
		List<NativeImage> images = new ArrayList<>(builtMips.size());
		try {
			for (TileMip mip : builtMips) {
				NativeImage image = new NativeImage(mip.width(), mip.height(), true);
				images.add(image);
				for (int y = 0; y < mip.height(); y++) {
					for (int x = 0; x < mip.width(); x++) {
						image.setPixel(x, y, mip.pixel(x, y));
					}
				}
			}
			return images;
		} catch (RuntimeException exception) {
			closeImages(images);
			throw exception;
		}
	}

	private static void uploadLoadedTiles(VirtualTextureGpuCache cache) {
		long start = System.nanoTime();
		int uploads = 0;
		java.util.Iterator<java.util.Map.Entry<VirtualTextureTileKey, PreparedTile>> iterator =
			loadingTiles.entrySet().iterator();
		while (iterator.hasNext() && uploads < MAX_TILE_UPLOADS_PER_FRAME_TICK) {
			if (uploads > 0 && System.nanoTime() - start >= MAX_TILE_UPLOAD_TIME_NANOS_PER_TICK) {
				break;
			}
			java.util.Map.Entry<VirtualTextureTileKey, PreparedTile> entry = iterator.next();
			if (!entry.getValue().images().isDone()) {
				continue;
			}
			iterator.remove();
			List<NativeImage> images = null;
			try {
				images = entry.getValue().images().get();
				if (cache.get(entry.getKey()) == null) {
					cache.upload(entry.getKey(), images);
					if (!entry.getValue().snapshot().names().isEmpty()) {
						animatedTiles.put(entry.getKey(), entry.getValue().snapshot());
					}
					uploads++;
				}
			} catch (Exception exception) {
				LoadsOfLods.LOGGER.error("Could not stream virtual texture tile {}", entry.getKey(), exception);
			} finally {
				if (images != null) {
					closeImages(images);
				}
			}
		}
	}

	private record AnimatedTile(List<String> names, FrameState[] frames) {
	}

	private record PreparedTile(AnimatedTile snapshot, java.util.concurrent.Future<List<NativeImage>> images) {
	}

	private static void discardPreparedTiles() {
		for (java.util.LinkedHashMap<VirtualTextureTileKey, PreparedTile> tiles : List.of(preparingTiles, loadingTiles)) {
			for (PreparedTile prepared : tiles.values()) {
				animatedTileExecutor.submit(() -> {
					try {
						closeImages(prepared.images().get());
					} catch (Exception ignored) {
						// The build failed or was interrupted, so there is nothing to close.
					}
				});
			}
			tiles.clear();
		}
	}

	// Tile pixels are built on worker threads; the render thread only writes finished tiles to the GPU.
	private static void uploadPreparedTiles(VirtualTextureGpuCache cache) {
		long start = System.nanoTime();
		int uploads = 0;
		java.util.Iterator<java.util.Map.Entry<VirtualTextureTileKey, PreparedTile>> iterator =
			preparingTiles.entrySet().iterator();
		while (iterator.hasNext() && uploads < MAX_ANIMATED_TILE_UPLOADS_PER_TICK) {
			if (uploads > 0 && System.nanoTime() - start >= MAX_ANIMATED_TILE_UPDATE_NANOS) {
				break;
			}
			java.util.Map.Entry<VirtualTextureTileKey, PreparedTile> entry = iterator.next();
			if (!entry.getValue().images().isDone()) {
				continue;
			}
			iterator.remove();
			List<NativeImage> images = null;
			try {
				images = entry.getValue().images().get();
				if (cache.update(entry.getKey(), images)) {
					animatedTiles.put(entry.getKey(), entry.getValue().snapshot());
					uploads++;
				}
			} catch (Exception exception) {
				LoadsOfLods.LOGGER.error("Could not update animated virtual texture tile {}", entry.getKey(), exception);
			} finally {
				if (images != null) {
					closeImages(images);
				}
			}
		}
	}

	// Rebuilds resident tiles containing animated sprites whose frame changed, only for sprites near the camera.
	// Updated tiles move to the back of the map so a backlog is worked through fairly.
	private static void serviceAnimatedTiles(VirtualTextureGpuCache cache, VirtualTextureTileBuilder builder) {
		uploadPreparedTiles(cache);
		long now = System.nanoTime();
		animatedItemSprites.values().removeIf(lastSeen -> now - lastSeen > ITEM_REQUEST_INTERVAL_NANOS * 2);
		if (animatedTiles.isEmpty()) {
			return;
		}
		java.util.Set<String> active = activeAnimatedSprites;
		List<VirtualTextureTileKey> due = new ArrayList<>();
		for (java.util.Iterator<java.util.Map.Entry<VirtualTextureTileKey, AnimatedTile>> iterator =
				animatedTiles.entrySet().iterator(); iterator.hasNext();) {
			java.util.Map.Entry<VirtualTextureTileKey, AnimatedTile> entry = iterator.next();
			if (!cache.isResident(entry.getKey())) {
				iterator.remove();
				continue;
			}
			AnimatedTile tile = entry.getValue();
			if (preparingTiles.containsKey(entry.getKey())) {
				continue;
			}
			for (int index = 0; index < tile.names().size(); index++) {
				String name = tile.names().get(index);
				if ((active.contains(name) || animatedItemSprites.containsKey(name))
						&& !java.util.Objects.equals(spriteFrames.get(name), tile.frames()[index])) {
					due.add(entry.getKey());
					break;
				}
			}
		}
		int cacheMipLevels = Math.min(
			Integer.numberOfTrailingZeros(VIRTUAL_TILE_SIZE) + 1,
			virtualTextureGpuPageTable.layout().mipLevelCount()
		);
		for (VirtualTextureTileKey key : due) {
			if (preparingTiles.size() >= MAX_ANIMATED_TILES_IN_FLIGHT) {
				break;
			}
			AnimatedTile snapshot = snapshotAnimatedTile(builder, key);
			preparingTiles.put(key, new PreparedTile(snapshot, animatedTileExecutor.submit(() -> {
				List<TileMip> builtMips = builder.build(key, cacheMipLevels);
				List<NativeImage> images = new ArrayList<>(builtMips.size());
				try {
					for (TileMip mip : builtMips) {
						NativeImage image = new NativeImage(mip.width(), mip.height(), true);
						images.add(image);
						for (int y = 0; y < mip.height(); y++) {
							for (int x = 0; x < mip.width(); x++) {
								image.setPixel(x, y, mip.pixel(x, y));
							}
						}
					}
					return images;
				} catch (RuntimeException exception) {
					closeImages(images);
					throw exception;
				}
			})));
			// Moves the tile to the back so a backlog is worked through fairly.
			AnimatedTile existing = animatedTiles.remove(key);
			if (existing != null) {
				animatedTiles.put(key, existing);
			}
		}
	}

	private static AnimatedTile snapshotAnimatedTile(VirtualTextureTileBuilder builder, VirtualTextureTileKey key) {
		List<String> names = builder.animatedSpriteNames(key);
		FrameState[] frames = new FrameState[names.size()];
		for (int index = 0; index < frames.length; index++) {
			frames[index] = spriteFrames.getOrDefault(names.get(index), FrameState.FIRST);
		}
		return new AnimatedTile(names, frames);
	}

	private static int uploadTileImages(
		VirtualTextureGpuCache cache,
		VirtualTextureTileBuilder builder,
		VirtualTextureTileKey key,
		boolean replace
	) {
		int cacheMipLevels = Math.min(
			Integer.numberOfTrailingZeros(VIRTUAL_TILE_SIZE) + 1,
			virtualTextureGpuPageTable.layout().mipLevelCount()
		);
		List<TileMip> builtMips = builder.build(key, cacheMipLevels);
		List<NativeImage> images = new ArrayList<>(builtMips.size());
		try {
			for (TileMip mip : builtMips) {
				NativeImage image = new NativeImage(mip.width(), mip.height(), true);
				images.add(image);
				for (int y = 0; y < mip.height(); y++) {
					for (int x = 0; x < mip.width(); x++) {
						image.setPixel(x, y, mip.pixel(x, y));
					}
				}
			}
			if (replace) {
				cache.update(key, images);
				return -1;
			}
			return cache.upload(key, images);
		} finally {
			closeImages(images);
		}
	}

	private static double sectionDistance(long sectionKey) {
		double dx = SodiumChunkMeshPriority.sectionX(sectionKey) * 16.0 + 8.0 - streamCameraX;
		double dy = SodiumChunkMeshPriority.sectionY(sectionKey) * 16.0 + 8.0 - streamCameraY;
		double dz = SodiumChunkMeshPriority.sectionZ(sectionKey) * 16.0 + 8.0 - streamCameraZ;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	// Requests the tiles of sections that have come within streaming range since they were meshed.
	private static void refreshStreamedSections() {
		if (streamRefreshTicks-- > 0) {
			return;
		}
		streamRefreshTicks = STREAM_REFRESH_TICKS;
		// Tiles can be evicted while their section stays marked as requested, so ask again now and then. Tiles that
		// are still resident are skipped when the request is planned.
		if (++streamRerequestCounter >= STREAM_REREQUEST_REFRESHES) {
			streamRerequestCounter = 0;
			requestedStreamSections.clear();
		}
		if (streamGeneration != generation) {
			// Requests recorded for an older atlas generation are discarded, so every section must be requested again.
			streamGeneration = generation;
			requestedStreamSections.clear();
		}
		com.malaron.loadsoflods.lod.LodConfig config = LoadsOfLodsClient.getConfig();
		double radius = config.highResolutionDistance();
		if (radius <= 0.0) {
			streamReach = -1.0;
			requestedStreamSections.clear();
			activeAnimatedSprites = java.util.Set.of();
			return;
		}
		var camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
		streamCameraX = camera.x;
		streamCameraY = camera.y;
		streamCameraZ = camera.z;
		double reach = radius + config.hysteresis() + STREAM_PREFETCH_BLOCKS;
		streamReach = reach;
		java.util.Set<String> animatedInReach = new java.util.HashSet<>();
		for (java.util.Map.Entry<Long, java.util.Set<net.minecraft.client.renderer.texture.TextureAtlasSprite>> entry
				: streamSections.entrySet()) {
			long key = entry.getKey();
			double distance = sectionDistance(key);
			if (distance - SECTION_RADIUS > reach) {
				requestedStreamSections.remove(key);
				continue;
			}
			for (net.minecraft.client.renderer.texture.TextureAtlasSprite sprite : entry.getValue()) {
				if (sprite.contents().isAnimated()) {
					animatedInReach.add(sprite.contents().name().toString());
				}
			}
			if (!requestedStreamSections.add(key)) {
				continue;
			}
			double distanceSquared = distance * distance;
			for (net.minecraft.client.renderer.texture.TextureAtlasSprite sprite : entry.getValue()) {
				VIRTUAL_TEXTURE_REQUEST_TRACKER.recordSpriteUsage(
					sprite.getU0(),
					sprite.getV0(),
					sprite.getU1(),
					sprite.getV1(),
					generation,
					distanceSquared
				);
			}
		}
		activeAnimatedSprites = animatedInReach;
	}

	public static void clearSectionSpriteUsage(long sectionKey) {
		NearAtlas near = nearAtlas;
		if (near != null) {
			near.clearSection(sectionKey);
		}
		streamSections.remove(sectionKey);
		requestedStreamSections.remove(sectionKey);
	}

	public static synchronized void serviceVirtualTextureRequests() {
		serviceIrisSwitch();
		serviceReloadRebuild();
		serviceParkedActivation();
		NearAtlas near = nearAtlas;
		if (near != null) {
			near.service();
		}
		VirtualTextureGpuPageTable pageTable = virtualTextureGpuPageTable;
		VirtualTextureLayout layout = virtualTextureLayout;
		if (!isDistancePreviewEnabled()
				|| pageTable == null
				|| layout == null
				|| virtualTextureGpuCache == null
				|| virtualTextureTileBuilder == null) {
			return;
		}
		if (near == null) {
			refreshStreamedSections();
		}
		long currentGeneration = pageTable.layout().atlasGeneration();
		for (VirtualTextureRequestTracker.UvQuad quad
				: VIRTUAL_TEXTURE_REQUEST_TRACKER.drain(MAX_UV_QUADS_PER_TICK, currentGeneration)) {
			if (quad.distanceSquared() == 0.0) {
				if (pendingItemQuads.add(quad)) {
					pendingVirtualTextureQuads.addFirst(quad);
				}
			} else {
				pendingVirtualTextureQuads.addLast(quad);
			}
		}

		int plannedKeys = 0;
		while (plannedKeys < MAX_TILE_KEYS_PER_TICK) {
			if (activeVirtualTextureCursor == null) {
				VirtualTextureRequestTracker.UvQuad quad = pendingVirtualTextureQuads.pollFirst();
				if (quad == null) {
					break;
				}
				activeVirtualTextureCursor = VirtualTextureRequestTracker.cursor(
					quad,
					layout,
					pageTable.layout().mipLevelCount()
				);
				activeVirtualTextureQuad = quad;
				activeVirtualTextureDistanceSquared = quad.distanceSquared();
			}
			VirtualTextureTileKey key = activeVirtualTextureCursor.next();
			// A mip level is only sampled within a limited distance, and mips as coarse as the low atlas add nothing.
			// Requesting every level for every section would overflow the cache and evict the nearby tiles.
			double effectiveDistance = Math.sqrt(activeVirtualTextureDistanceSquared) - SECTION_RADIUS;
			int lowAtlasMip = Integer.numberOfTrailingZeros(Math.max(1, lowAtlasDownscaleFactor));
			boolean wanted = key.mipLevel() < lowAtlasMip
				&& effectiveDistance <= (STREAM_MIP0_BLOCKS << key.mipLevel());
			if (wanted && !animatedVirtualTextureTiles.contains(key)) {
				VirtualTextureGpuCache cache = virtualTextureGpuCache;
				if (cache != null) {
					if (cache.get(key) == null) {
						pendingVirtualTextureTiles.offer(key, activeVirtualTextureDistanceSquared);
					}
				}
			}
			plannedKeys++;
			if (activeVirtualTextureCursor.isComplete()) {
				pendingItemQuads.remove(activeVirtualTextureQuad);
				activeVirtualTextureCursor = null;
				activeVirtualTextureQuad = null;
				activeVirtualTextureDistanceSquared = Double.POSITIVE_INFINITY;
			}
		}

		VirtualTextureGpuCache streamCache = virtualTextureGpuCache;
		VirtualTextureTileBuilder streamBuilder = virtualTextureTileBuilder;
		if (streamCache != null && streamBuilder != null) {
			uploadLoadedTiles(streamCache);
			submitPendingTiles(streamCache, streamBuilder);
		}
		VirtualTextureGpuCache animatedCache = virtualTextureGpuCache;
		if (animatedCache != null) {
			serviceAnimatedTiles(animatedCache, virtualTextureTileBuilder);
		}
	}

	public static synchronized VirtualTextureCacheStatus virtualTextureCacheStatus() {
		VirtualTextureGpuCache cache = virtualTextureGpuCache;
		if (cache == null) {
			return new VirtualTextureCacheStatus(false, 0, 0, 0, 0, 0, 0, 0);
		}
		int pendingQuads = VIRTUAL_TEXTURE_REQUEST_TRACKER.pendingCount()
			+ pendingVirtualTextureQuads.size()
			+ (activeVirtualTextureCursor == null ? 0 : 1);
		return new VirtualTextureCacheStatus(
			true,
			cache.size(),
			cache.usedBytes(),
			cache.capacityBytes(),
			pendingVirtualTextureTiles.size(),
			pendingQuads,
			VIRTUAL_TEXTURE_REQUEST_TRACKER.completedQuadCount(),
			VIRTUAL_TEXTURE_REQUEST_TRACKER.capturedSpriteUseCount()
		);
	}

	public static synchronized VirtualTextureSamplingViews virtualTextureSamplingViews(boolean useVirtualTexture) {
		VirtualTextureGpuPageTable pageTable = virtualTextureGpuPageTable;
		VirtualTextureGpuCache cache = virtualTextureGpuCache;
		VirtualTextureMetadataTexture metadata = virtualTextureMetadataTexture;
		if (useVirtualTexture && pageTable != null && cache != null && metadata != null) {
			return new VirtualTextureSamplingViews(cache.textureView(), pageTable.view(), metadata.view());
		}
		if (fallbackPageTable == null || fallbackTileCache == null || fallbackMetadataTexture == null) {
			throw new IllegalStateException("Virtual texture fallback resources were not initialized before rendering");
		}
		return new VirtualTextureSamplingViews(
			fallbackTileCache.textureView(),
			fallbackPageTable.view(),
			fallbackMetadataTexture.view()
		);
	}

	public static void enableLowPreview() {
		if (atlasOwnsLowResolutionTexture) {
			throw new IllegalStateException("Reload resource packs to restore the released high-resolution atlas");
		}
		if (!hasLowAtlas()) {
			throw new IllegalStateException("Run /lods preview-low to build and enable the low-resolution atlas");
		}
		lowPreviewEnabled = true;
		distancePreviewEnabled = false;
	}

	public static void enableDistancePreview() {
		if (!hasLowAtlas()) {
			throw new IllegalStateException("Run /lods preview-distance to build the low-resolution atlas first");
		}
		if (!atlasOwnsLowResolutionTexture && lowPreviewEnabled) {
			TextureAtlas atlas = previewedAtlas;
			AbstractTextureAccessor textureAccessor = (AbstractTextureAccessor) atlas;
			GpuTexture highResolutionTexture = textureAccessor.loadsOfLods$getTexture();
			if (virtualTextureGpuPageTable == null
					|| virtualTextureGpuCache == null
					|| virtualTextureMetadataTexture == null
					|| virtualTextureTileBuilder == null) {
				throw new IllegalStateException("The virtual texture cache is not initialized");
			}
			replaceBlockAtlasWithLowResolution(
				atlas,
				highResolutionTexture,
				lowResolutionTexture,
				lowResolutionTextureView
			);
		}
		lowPreviewEnabled = false;
		distancePreviewEnabled = true;
	}

	public static synchronized void disableLowPreview() {
		lowPreviewEnabled = false;
		distancePreviewEnabled = false;
		if (BUILDING.get()) {
			generation++;
		}
	}

	public static boolean isDistancePreviewEnabled() {
		return distancePreviewEnabled && hasLowAtlas();
	}

	// The untouched atlas texture that PBR maps were generated for, or null when it is not resident.
	public static GpuTexture originalAtlasTextureForPbr(GpuTexture boundTexture) {
		TextureAtlas atlas = previewedAtlas;
		if (atlas == null || atlasOwnsLowResolutionTexture || boundTexture == null
				|| boundTexture != lowResolutionTexture) {
			return null;
		}
		return ((AbstractTextureAccessor) atlas).loadsOfLods$getTexture();
	}

	public static boolean isReducedPbrAtlas(Object pbrAtlas) {
		return pbrAtlas != null && reducedPbrAtlases.contains(pbrAtlas);
	}

	public static synchronized List<String> debugLines() {
		List<String> lines = new ArrayList<>();
		GpuTexture low = lowResolutionTexture;
		if (previewedAtlas == null || low == null) {
			lines.add("[Loads of Lods] inactive");
			return lines;
		}
		String mode = distancePreviewEnabled
			? (SodiumLodBridge.isIrisShaderPackInUse() ? "distance (Iris, reduced)" : "distance (streamed)")
			: "low preview";
		lines.add(
			"[Loads of Lods] " + mode + " 1:" + lowAtlasDownscaleFactor + " atlas "
				+ low.getWidth(0) + "x" + low.getHeight(0)
				+ (atlasOwnsLowResolutionTexture ? ", full atlas released" : ", full atlas resident")
		);
		if (!reducedPbrAtlases.isEmpty()) {
			lines.add("[Loads of Lods] Iris PBR atlases reduced: " + reducedPbrAtlases.size() + "/2");
		}
		if (nearAtlas != null) {
			for (String line : nearAtlas.debugLines()) {
				lines.add("[Loads of Lods] " + line);
			}
		}
		VirtualTextureCacheStatus status = virtualTextureCacheStatus();
		if (status.available() && !SodiumLodBridge.isIrisShaderPackInUse()) {
			lines.add(
				"[Loads of Lods] tile cache " + status.residentTiles() + " tiles, "
					+ status.usedBytes() / (1024 * 1024) + "/" + status.capacityBytes() / (1024 * 1024)
					+ " MiB, " + status.pendingTiles() + " pending"
			);
		}
		return lines;
	}

	public static boolean isStreamedAtlasActive() {
		return atlasOwnsLowResolutionTexture;
	}

	public static boolean isOriginalAtlasReleaseConfirmed() {
		return originalAtlasReleaseConfirmed;
	}

	public static boolean shouldSkipVanillaAnimationUpload(TextureAtlas atlas) {
		return atlasOwnsLowResolutionTexture && previewedAtlas == atlas;
	}

	public static GpuTextureView lowResolutionTextureViewForDistancePreview() {
		if (!isDistancePreviewEnabled()) {
			throw new IllegalStateException("Distance-based atlas preview is not active");
		}
		return lowResolutionTextureView;
	}

	public static boolean usesLowResolutionAtlas(TextureAtlas atlas) {
		return lowPreviewEnabled && previewedAtlas == atlas && hasLowAtlas();
	}

	public static GpuTexture lowResolutionTexture(TextureAtlas atlas) {
		if (!usesLowResolutionAtlas(atlas)) {
			throw new IllegalStateException("The low-resolution atlas is not active");
		}
		return lowResolutionTexture;
	}

	public static GpuTextureView lowResolutionTextureView(TextureAtlas atlas) {
		if (!usesLowResolutionAtlas(atlas)) {
			throw new IllegalStateException("The low-resolution atlas is not active");
		}
		return lowResolutionTextureView;
	}

	// A resource reload restores the full atlas and drops the reduced one. If the distance preview was on, rebuild
	// it automatically once the reload has settled.
	// True while a reduced rebuild is guaranteed to follow a resource reload, so Iris's full-size blocks PBR atlases
	// would be built only to be discarded. The rebuild registers reduced maps itself.
	public static synchronized boolean shouldSkipIrisBlockPbrLoad(TextureAtlas atlas) {
		return (reloadRebuildPending || (reloadBuildRunning && BUILDING.get()) || irisSwitchNeedsRebuild())
			&& TextureAtlas.LOCATION_BLOCKS.equals(atlas.location())
			&& SodiumLodBridge.isIrisShaderPackInUse();
	}

	// Shaders were switched on while the non-Iris streamed preview was active: there is no near atlas or reduced PBR,
	// and the full atlas is gone, so the preview must be rebuilt through a resource reload.
	private static boolean irisSwitchNeedsRebuild() {
		return distancePreviewEnabled && atlasOwnsLowResolutionTexture && nearAtlas == null
			&& !nearAtlasUnavailable && SodiumLodBridge.isIrisShaderPackInUse();
	}

	// The Iris build could not create a near atlas (for example the atlas exceeds the sparse texture limit), so
	// rebuilding again would not help.
	private static boolean nearAtlasUnavailable;

	// Shaders were switched off while the Iris preview was active: terrain needs the streamed (non-Iris) preview.
	private static boolean shadersSwitchedOffNeedsRebuild() {
		return distancePreviewEnabled && atlasOwnsLowResolutionTexture && nearAtlas != null
			&& !SodiumLodBridge.isIrisShaderPackInUse();
	}

	private static boolean irisSwitchReloadRequested;
	private static int irisSwitchStableTicks;

	private static void serviceIrisSwitch() {
		if (irisSwitchReloadRequested) {
			return;
		}
		boolean enabled = irisSwitchNeedsRebuild();
		// Iris recreates its pipeline for a moment during some changes, so require the state to hold.
		if (!enabled && !shadersSwitchedOffNeedsRebuild()) {
			irisSwitchStableTicks = 0;
			return;
		}
		if (++irisSwitchStableTicks < 20) {
			return;
		}
		irisSwitchReloadRequested = true;
		reloadRebuildPending = true;
		LoadsOfLods.LOGGER.info(
			"Shaders were switched {} with the preview active; reloading resources to rebuild it",
			enabled ? "on" : "off"
		);
		Minecraft.getInstance().reloadResourcePacks();
	}

	private static boolean reloadRebuildPending;
	private static long reloadBuildStartNanos;
	// Iris loads PBR atlases lazily, possibly after our rebuild has started, so keep skipping until it finishes.
	private static boolean reloadBuildRunning;
	private static int reloadRebuildTicks = -1;
	private static boolean launchRebuild;
	private static int launchWaitTicks;
	private static final int LAUNCH_WAIT_LIMIT_TICKS = 1200;

	// Treat the first atlas load like a reload of an enabled preview: the rebuild runs once a world has been joined
	// and the shaderpack pipeline exists.
	public static synchronized void armLaunchRebuild() {
		reloadRebuildPending = true;
		launchRebuild = true;
	}

	// A virtual block atlas has no usable texture until the preview is built, so every upload must rebuild it.
	public static synchronized void armVirtualRebuild() {
		armLaunchRebuild();
	}

	private static boolean previewPrerequisitesMet() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium")
			&& (SodiumLodBridge.isIrisShaderPackInUse()
				|| com.malaron.loadsoflods.cache.VirtualTextureShaderPatcher.isPatchApplied());
	}

	private static void serviceReloadRebuild() {
		if (reloadRebuildTicks < 0 || reloadRebuildTicks-- > 0) {
			return;
		}
		reloadRebuildTicks = -1;
		Minecraft client = Minecraft.getInstance();
		boolean early = false;
		if (BUILDING.get()) {
			reloadRebuildTicks = 20;
			return;
		}
		if (launchRebuild) {
			// With Iris shaders configured on, start now: the images are built in the background and activation
			// waits for Iris. Otherwise wait for a world so the prerequisites can be detected.
			early = SodiumLodBridge.isIrisConfiguredForShaders();
			boolean ready = early || (client.level != null && previewPrerequisitesMet());
			if (!ready && (client.level == null || launchWaitTicks++ < LAUNCH_WAIT_LIMIT_TICKS)) {
				reloadRebuildTicks = 20;
				return;
			}
			launchRebuild = false;
		}
		reloadRebuildPending = false;
		if (!(client.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS) instanceof TextureAtlas atlas)
				|| !net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium")
				|| (!early && !SodiumLodBridge.isIrisShaderPackInUse()
					&& !com.malaron.loadsoflods.cache.VirtualTextureShaderPatcher.isPatchApplied())
				|| distancePreviewEnabled) {
			LoadsOfLods.LOGGER.info("Not rebuilding the distance preview after the resource reload; run /lods preview-distance");
			return;
		}
		LoadsOfLods.LOGGER.info("Rebuilding the distance preview after a resource reload");
		reloadBuildStartNanos = System.nanoTime();
		reloadBuildRunning = true;
		deferActivationUntilIris = early && !SodiumLodBridge.isIrisShaderPackInUse();
		try {
			buildAndEnable(
				atlas,
				client.getResourceManager(),
				message -> LoadsOfLods.LOGGER.info("{}", message.getString()),
				message -> {
					LoadsOfLods.LOGGER.error("{}", message.getString());
					IrisPbrAtlasReducer.resetIrisHolders();
				},
				true
			);
		} catch (RuntimeException exception) {
			LoadsOfLods.LOGGER.error("Could not rebuild the distance preview after a resource reload", exception);
			reloadBuildRunning = false;
			IrisPbrAtlasReducer.resetIrisHolders();
		}
	}

	public static synchronized void beforeAtlasReload(TextureAtlas atlas) {
		if (TextureAtlas.LOCATION_BLOCKS.equals(atlas.location()) && previewedAtlas == atlas) {
			reloadRebuildPending |= distancePreviewEnabled;
			irisSwitchReloadRequested = false;
			lowPreviewEnabled = false;
			distancePreviewEnabled = false;
			detachAtlasOwnedLowResolutionTexture();
		}
	}

	public static synchronized void invalidateForAtlasReload(TextureAtlas atlas) {
		if (!TextureAtlas.LOCATION_BLOCKS.equals(atlas.location())) {
			return;
		}
		ensureFallbackVirtualTextureResources();
		generation++;
		if (previewedAtlas == atlas) {
			closeLowResolutionTexture();
		}
		if (reloadRebuildPending) {
			reloadRebuildTicks = 20;
			// The early path already builds without waiting for a world or Iris's pipeline. Ticks can be delayed by
			// other mods' reload work on this thread, so start it as soon as the atlas exists.
			if (launchRebuild && SodiumLodBridge.isIrisConfiguredForShaders()) {
				reloadRebuildTicks = 0;
				serviceReloadRebuild();
			}
		}
	}

	public static synchronized void invalidateForAtlasClose(TextureAtlas atlas) {
		if (previewedAtlas == atlas) {
			generation++;
			detachAtlasOwnedLowResolutionTexture();
			closeLowResolutionTexture();
			reducedPbrAtlases = List.of();
		}
	}

	private static void detachAtlasOwnedLowResolutionTexture() {
		if (!atlasOwnsLowResolutionTexture) {
			return;
		}
		atlasOwnsLowResolutionTexture = false;
		originalAtlasReleaseConfirmed = false;
		lowResolutionTextureView = null;
		lowResolutionTexture = null;
	}

	private static List<SpriteRegion> snapshotRegions(TextureAtlas atlas, int atlasWidth, int atlasHeight) {
		List<SpriteRegion> regions = new ArrayList<>();
		for (TextureAtlasSprite sprite : ((TextureAtlasAccessor) atlas).loadsOfLods$getSprites()) {
			int width = sprite.contents().width();
			int height = sprite.contents().height();
			int paddingX = Math.round(sprite.getU0() * atlasWidth) - sprite.getX();
			int paddingY = Math.round(sprite.getV0() * atlasHeight) - sprite.getY();
			if (width <= 0 || height <= 0 || paddingX < 0 || paddingY < 0
					|| sprite.getX() < 0 || sprite.getY() < 0
					|| sprite.getX() + width + paddingX * 2 > atlasWidth
					|| sprite.getY() + height + paddingY * 2 > atlasHeight) {
				LoadsOfLods.LOGGER.warn("Skipping atlas preview sprite with invalid bounds: {}", sprite.contents().name());
				continue;
			}
			regions.add(
				new SpriteRegion(
					sprite.contents().name(),
					sprite.getX() + paddingX,
					sprite.getY() + paddingY,
					width,
					height,
					paddingX,
					paddingY,
					sprite.isAnimated()
				)
			);
		}
		return List.copyOf(regions);
	}

	private static int selectDownscaleFactor(List<SpriteRegion> regions, int atlasWidth, int atlasHeight) {
		for (int factor = LoadsOfLodsClient.getConfig().downscaleFactor(); factor > 1; factor >>= 1) {
			if (atlasWidth % factor != 0 || atlasHeight % factor != 0) {
				continue;
			}
			boolean aligned = true;
			for (SpriteRegion region : regions) {
				if (region.x() % factor != 0 || region.y() % factor != 0
						|| region.width() % factor != 0 || region.height() % factor != 0
						|| region.paddingX() % factor != 0 || region.paddingY() % factor != 0) {
					aligned = false;
					break;
				}
			}
			if (aligned) {
				return factor;
			}
		}
		return 1;
	}

	private static LowAtlasImage createLowAtlas(
		ResourceManager resourceManager,
		List<SpriteRegion> regions,
		int atlasWidth,
		int atlasHeight,
		int mipLevelCount,
		int downscaleFactor,
		boolean reducePbr
	) throws IOException {
		if (mipLevelCount <= 0) {
			throw new IOException("The source block atlas has no mip levels");
		}
		int lowAtlasWidth = atlasWidth / downscaleFactor;
		int lowAtlasHeight = atlasHeight / downscaleFactor;
		int lowMipLevelCount = Math.max(1, mipLevelCount - Integer.numberOfTrailingZeros(downscaleFactor));
		long imageBytes = 0;
		for (int level = 0; level < lowMipLevelCount; level++) {
			imageBytes += (long) Math.max(1, lowAtlasWidth >> level) * Math.max(1, lowAtlasHeight >> level) * 4;
		}
		if (imageBytes > MAX_PREVIEW_BYTES) {
			throw new IOException(
				"Low-resolution block atlas is " + lowAtlasWidth + "x" + lowAtlasHeight
					+ " with " + lowMipLevelCount + " mip levels, exceeding the 2 GiB safety limit"
			);
		}
		List<NativeImage> mipLevels = new ArrayList<>(lowMipLevelCount);
		List<AnimatedSpriteData> animatedSpriteData = new ArrayList<>();
		long[] totalImageBytes = {imageBytes};
		boolean debugTint = LoadsOfLodsClient.getConfig().debugTintLowResolution();
		String cacheKey = ReducedAtlasCache.fingerprint(
			resourceManager, regions, atlasWidth, atlasHeight, mipLevelCount, downscaleFactor, reducePbr, debugTint
		);
		ReducedAtlasCache.Entry cached = cacheKey == null
			? null
			: ReducedAtlasCache.load(cacheKey, lowAtlasWidth, lowAtlasHeight, lowMipLevelCount, reducePbr);
		if (cached != null) {
			try {
				mipLevels.addAll(cached.albedo());
				AtomicInteger animatedSprites = new AtomicInteger();
				AtomicInteger animatedSpritesCached = new AtomicInteger();
				// Only the animation frame data is rebuilt; the atlas pixels already include frame 0.
				regions.parallelStream().filter(SpriteRegion::animated).forEach(region -> {
					animatedSprites.incrementAndGet();
					try {
						Identifier textureId = Identifier.fromNamespaceAndPath(
							region.identifier().getNamespace(),
							"textures/" + region.identifier().getPath() + ".png"
						);
						Resource resource = resourceManager.getResource(textureId)
							.orElseThrow(() -> new IOException("Missing texture resource " + textureId));
						BufferedImage source;
						try (InputStream input = resource.open()) {
							source = ImageIO.read(input);
						}
						if (source == null) {
							throw new IOException("Texture resource is not a readable image");
						}
						try {
							synchronized (animatedSpriteData) {
								AnimatedSpriteData animation = createAnimatedSpriteData(
									resource, source, region, downscaleFactor, lowMipLevelCount, totalImageBytes
								);
								if (animation != null) {
									animatedSpriteData.add(animation);
									animatedSpritesCached.incrementAndGet();
								}
							}
						} finally {
							source.flush();
						}
					} catch (IOException | RuntimeException exception) {
						LoadsOfLods.LOGGER.warn(
							"Leaving animated sprite {} static: {}", region.identifier(), exception.getMessage()
						);
					}
				});
				IrisPbrAtlasReducer.Images cachedPbr = reducePbr
					? new IrisPbrAtlasReducer.Images(cached.normal(), cached.specular())
					: null;
				LoadsOfLods.LOGGER.info(
					"Loaded the reduced atlas from the disk cache: {}x{}, scale 1:{}, {}/{} animated sprites synchronized, {} mip levels; PBR maps {}",
					lowAtlasWidth, lowAtlasHeight, downscaleFactor, animatedSpritesCached.get(), animatedSprites.get(),
					lowMipLevelCount, cachedPbr != null ? "reduced" : "unchanged"
				);
				return new LowAtlasImage(mipLevels, downscaleFactor, animatedSpriteData, cachedPbr);
			} catch (RuntimeException | Error exception) {
				cached.close();
				closeAnimatedSprites(animatedSpriteData);
				throw exception;
			}
		}
		CompletableFuture<IrisPbrAtlasReducer.Images> pbrFuture = null;
		PackTileCache tiles = new PackTileCache(downscaleFactor);
		try {
			NativeImage atlas = new NativeImage(lowAtlasWidth, lowAtlasHeight, true);
			long buildStartNanos = System.nanoTime();
			mipLevels.add(atlas);
			if (reducePbr) {
				// PBR reduction reads only the resource packs, so it runs alongside the albedo pass.
				pbrFuture = CompletableFuture.supplyAsync(() -> {
					try {
						return IrisPbrAtlasReducer.build(
							resourceManager, regions, lowAtlasWidth, lowAtlasHeight, lowMipLevelCount, downscaleFactor, tiles
						);
					} catch (IOException exception) {
						throw new java.io.UncheckedIOException(exception);
					}
				});
			}
			List<SpriteRegion> lowRegions = regions.stream()
				.map(region -> scaleRegion(region, downscaleFactor))
				.toList();
			AtomicInteger loadedSprites = new AtomicInteger();
			AtomicInteger skippedSprites = new AtomicInteger();
			AtomicInteger animatedSprites = new AtomicInteger();
			AtomicInteger animatedSpritesCached = new AtomicInteger();
			// Sprites occupy disjoint atlas regions, so decoding and writing them in parallel is safe.
			regions.parallelStream().forEach(region -> {
				if (region.animated()) {
					animatedSprites.incrementAndGet();
				}
				try {
					Identifier textureId = Identifier.fromNamespaceAndPath(
						region.identifier().getNamespace(),
						"textures/" + region.identifier().getPath() + ".png"
					);
					Resource resource = resourceManager.getResource(textureId)
						.orElseThrow(() -> new IOException("Missing texture resource " + textureId));
					// Animated sprites are decoded anyway for their frame data, so only static ones use cached tiles.
					PackTileCache.Pack tilePack = region.animated() ? null : tiles.forPack(resource.sourcePackId());
					String tileKey = PackTileCache.key('A', region);
					int[] cachedTile = tilePack == null ? null : tilePack.get(tileKey, tilePixels(region, downscaleFactor));
					if (cachedTile != null) {
						blitTile(atlas, cachedTile, region, downscaleFactor);
						tiles.hits.incrementAndGet();
						loadedSprites.incrementAndGet();
						return;
					}
					tiles.decoded.incrementAndGet();
					BufferedImage source;
					try (InputStream input = resource.open()) {
						source = ImageIO.read(input);
					}
					if (source == null) {
						throw new IOException("Texture resource is not a readable image");
					}
					try {
						if (source.getWidth() < region.width() || source.getHeight() < region.height()) {
							throw new IOException("Texture dimensions do not match stitched sprite bounds");
						}
						int[] tile = reduceSprite(source, region, downscaleFactor);
						blitTile(atlas, tile, region, downscaleFactor);
						if (tilePack != null) {
							tilePack.put(tileKey, tile);
						}
						if (isTiledPortalTexture(region)) {
							SpriteRegion lowRegion = scaleRegion(region, downscaleFactor);
							makeSpriteEdgesContinuous(
								atlas,
								lowRegion.x(),
								lowRegion.y(),
								lowRegion.width(),
								lowRegion.height(),
								lowRegion.paddingX(),
								lowRegion.paddingY()
							);
						}
						if (region.animated()) {
							// Shares the byte budget and result list, so only these few sprites are serialised.
							synchronized (animatedSpriteData) {
								AnimatedSpriteData animation = createAnimatedSpriteData(
									resource,
									source,
									region,
									downscaleFactor,
									lowMipLevelCount,
									totalImageBytes
								);
								if (animation != null) {
									animatedSpriteData.add(animation);
									animatedSpritesCached.incrementAndGet();
								}
							}
						}
					} finally {
						source.flush();
					}
					loadedSprites.incrementAndGet();
				} catch (IOException | RuntimeException exception) {
					skippedSprites.incrementAndGet();
					LoadsOfLods.LOGGER.warn(
						"Leaving atlas preview sprite {} unchanged because its source could not be read: {}",
						region.identifier(),
						exception.getMessage()
					);
				}
			});
			for (int level = 1; level < lowMipLevelCount; level++) {
				mipLevels.add(createMipLevel(mipLevels.get(level - 1), lowRegions, level));
			}
			if (LoadsOfLodsClient.getConfig().debugTintLowResolution()) {
				for (NativeImage mipLevel : mipLevels) {
					tintMagenta(mipLevel);
				}
			}
			IrisPbrAtlasReducer.Images pbrImages = null;
			long pbrWaitStartNanos = System.nanoTime();
			if (pbrFuture != null) {
				try {
					pbrImages = pbrFuture.join();
				} catch (RuntimeException exception) {
					LoadsOfLods.LOGGER.error("Could not reduce Iris PBR maps; they stay full resolution", exception);
				}
				pbrFuture = null;
			}
			LoadsOfLods.LOGGER.info(
				"Reduced atlas phases: albedo {} ms, PBR finished {} ms after the albedo pass",
				(System.nanoTime() - buildStartNanos) / 1_000_000L,
				(System.nanoTime() - pbrWaitStartNanos) / 1_000_000L
			);
			tiles.save("albedo and PBR");
			if (cacheKey != null && (!reducePbr || pbrImages != null)) {
				ReducedAtlasCache.store(cacheKey, mipLevels, pbrImages);
			}
			LoadsOfLods.LOGGER.info(
				"Built low-resolution albedo atlas preview: {}x{}, scale 1:{}, {} sprites, {} skipped, {}/{} animated sprites synchronized, {} mip levels; PBR maps {}",
				lowAtlasWidth,
				lowAtlasHeight,
				downscaleFactor,
				loadedSprites.get(),
				skippedSprites.get(),
				animatedSpritesCached.get(),
				animatedSprites.get(),
				lowMipLevelCount,
				pbrImages != null ? "reduced" : "unchanged"
			);
			return new LowAtlasImage(
				mipLevels,
				downscaleFactor,
				animatedSpriteData,
				pbrImages
			);
		} catch (RuntimeException | Error exception) {
			if (pbrFuture != null) {
				pbrFuture.whenComplete((images, failure) -> {
					if (images != null) {
						images.normal().forEach(NativeImage::close);
						images.specular().forEach(NativeImage::close);
					}
				});
			}
			for (NativeImage mipLevel : mipLevels) {
				mipLevel.close();
			}
			closeAnimatedSprites(animatedSpriteData);
			throw exception;
		}
	}

	private static AnimatedSpriteData createAnimatedSpriteData(
		Resource resource,
		BufferedImage source,
		SpriteRegion region,
		int downscaleFactor,
		int mipLevelCount,
		long[] totalImageBytes
	) throws IOException {
		long initialImageBytes = totalImageBytes[0];
		AnimationMetadataSection metadata = resource.metadata()
			.getSection(AnimationMetadataSection.TYPE)
			.orElse(null);
		if (metadata == null) {
			LoadsOfLods.LOGGER.warn("Animated sprite {} has no animation metadata; leaving its low atlas frame static", region.identifier());
			return null;
		}
		FrameSize frameSize = metadata.calculateFrameSize(source.getWidth(), source.getHeight());
		int frameWidth = frameSize.width();
		int frameHeight = frameSize.height();
		if (frameWidth <= 0 || frameHeight <= 0
				|| frameWidth != region.width() || frameHeight != region.height()
				|| source.getWidth() % frameWidth != 0 || source.getHeight() % frameHeight != 0) {
			throw new IOException("Animation frame dimensions do not match the stitched sprite");
		}
		int columns = source.getWidth() / frameWidth;
		int frameCount = columns * (source.getHeight() / frameHeight);
		List<AnimationFrame> sourceFrames = metadata.frames().orElseGet(() -> {
			List<AnimationFrame> frames = new ArrayList<>(frameCount);
			for (int index = 0; index < frameCount; index++) {
				frames.add(new AnimationFrame(index));
			}
			return frames;
		});
		if (sourceFrames.isEmpty()) {
			throw new IOException("Animation contains no frames");
		}
		List<AnimationStep> steps = new ArrayList<>(sourceFrames.size());
		Map<Integer, List<NativeImage>> imagesByFrame = new HashMap<>();
		try {
			for (AnimationFrame frame : sourceFrames) {
				int frameIndex = frame.index();
				int duration = frame.timeOr(metadata.defaultFrameTime());
				if (frameIndex < 0 || frameIndex >= frameCount || duration <= 0) {
					throw new IOException("Animation contains an invalid frame index or duration");
				}
				steps.add(new AnimationStep(frameIndex, duration));
				if (!imagesByFrame.containsKey(frameIndex)) {
					int frameX = frameIndex % columns * frameWidth;
					int frameY = frameIndex / columns * frameHeight;
					imagesByFrame.put(
						frameIndex,
						createAnimationFrameMipLevels(
							source,
							frameX,
							frameY,
							frameWidth,
							frameHeight,
							region,
							downscaleFactor,
							mipLevelCount,
							totalImageBytes
						)
					);
				}
			}
			return new AnimatedSpriteData(region, steps, imagesByFrame, metadata.interpolatedFrames());
		} catch (IOException | RuntimeException | Error exception) {
			closeFrameImages(imagesByFrame);
			totalImageBytes[0] = initialImageBytes;
			throw exception;
		}
	}

	private static List<NativeImage> createAnimationFrameMipLevels(
		BufferedImage source,
		int frameX,
		int frameY,
		int frameWidth,
		int frameHeight,
		SpriteRegion region,
		int downscaleFactor,
		int mipLevelCount,
		long[] totalImageBytes
	) throws IOException {
		List<NativeImage> mipLevels = new ArrayList<>(mipLevelCount);
		int sourceLeft = (region.x() - region.paddingX()) / downscaleFactor;
		int sourceTop = (region.y() - region.paddingY()) / downscaleFactor;
		int sourceRight = (region.x() + region.width() + region.paddingX()) / downscaleFactor;
		int sourceBottom = (region.y() + region.height() + region.paddingY()) / downscaleFactor;
		try {
			for (int level = 0; level < mipLevelCount; level++) {
				int divisor = 1 << level;
				int left = sourceLeft >> level;
				int top = sourceTop >> level;
				int right = ceilDiv(sourceRight, divisor);
				int bottom = ceilDiv(sourceBottom, divisor);
				int width = right - left;
				int height = bottom - top;
				long levelBytes = (long) width * height * 4;
				totalImageBytes[0] += levelBytes;
				if (totalImageBytes[0] > MAX_PREVIEW_BYTES) {
					throw new IOException("Animated low-atlas frames would exceed the 2 GiB CPU image safety limit");
				}
				NativeImage mipLevel = new NativeImage(width, height, true);
				mipLevels.add(mipLevel);
				int sampleScale = downscaleFactor * divisor;
				for (int y = top; y < bottom; y++) {
					int relativeY = (y * divisor - sourceTop) * downscaleFactor;
					int sampleY = frameY + relativeY;
					for (int x = left; x < right; x++) {
						int relativeX = (x * divisor - sourceLeft) * downscaleFactor;
						int sampleX = frameX + relativeX;
						mipLevel.setPixel(
							x - left,
							y - top,
							averageFramePixels(source, sampleX, sampleY, frameX, frameY, frameWidth, frameHeight, sampleScale)
						);
					}
				}
				if (isTiledPortalTexture(region)) {
					SpriteRegion lowRegion = scaleRegion(region, downscaleFactor);
					int lowPaddingX = lowRegion.paddingX() >> level;
					int lowPaddingY = lowRegion.paddingY() >> level;
					makeSpriteEdgesContinuous(
						mipLevel,
						lowPaddingX,
						lowPaddingY,
						Math.max(1, ceilDiv(lowRegion.width(), divisor)),
						Math.max(1, ceilDiv(lowRegion.height(), divisor)),
						lowPaddingX,
						lowPaddingY
					);
				}
			}
			return mipLevels;
		} catch (IOException | RuntimeException | Error exception) {
			for (NativeImage mipLevel : mipLevels) {
				mipLevel.close();
			}
			throw exception;
		}
	}

	private static int averageFramePixels(
		BufferedImage source,
		int x,
		int y,
		int frameX,
		int frameY,
		int frameWidth,
		int frameHeight,
		int scale
	) {
		long alphaSum = 0;
		long redSum = 0;
		long greenSum = 0;
		long blueSum = 0;
		int sampleCount = scale * scale;
		for (int offsetY = 0; offsetY < scale; offsetY++) {
			int sourceY = Math.clamp(y + offsetY, frameY, frameY + frameHeight - 1);
			for (int offsetX = 0; offsetX < scale; offsetX++) {
				int sourceX = Math.clamp(x + offsetX, frameX, frameX + frameWidth - 1);
				int color = source.getRGB(sourceX, sourceY);
				int alpha = color >>> 24;
				alphaSum += alpha;
				redSum += (long) ((color >>> 16) & 255) * alpha;
				greenSum += (long) ((color >>> 8) & 255) * alpha;
				blueSum += (long) (color & 255) * alpha;
			}
		}
		if (alphaSum == 0) {
			return 0;
		}
		int alpha = (int) ((alphaSum + sampleCount / 2) / sampleCount);
		int red = (int) ((redSum + alphaSum / 2) / alphaSum);
		int green = (int) ((greenSum + alphaSum / 2) / alphaSum);
		int blue = (int) ((blueSum + alphaSum / 2) / alphaSum);
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}

	static SpriteRegion scaleRegion(SpriteRegion region, int factor) {
		return new SpriteRegion(
			region.identifier(),
			region.x() / factor,
			region.y() / factor,
			region.width() / factor,
			region.height() / factor,
			region.paddingX() / factor,
			region.paddingY() / factor,
			region.animated()
		);
	}

	private static void createLowResolutionTexture(
		TextureAtlas atlas,
		GpuTexture highResolutionTexture,
		LowAtlasImage image,
		List<SpriteRegion> regions,
		int atlasWidth,
		int atlasHeight,
		int atlasMipLevels,
		boolean releaseHighResolutionAtlas
	) {
		var device = RenderSystem.getDevice();
		closeVirtualTextureGpuPageTable();
		GpuTexture texture = device.createTexture(
			"Loads of Lods low-resolution block atlas",
			GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING
				| GpuTexture.USAGE_RENDER_ATTACHMENT,
			highResolutionTexture.getFormat(),
			atlasWidth / image.downscaleFactor(),
			atlasHeight / image.downscaleFactor(),
			highResolutionTexture.getDepthOrLayers(),
			image.mipLevels().size()
		);
		GpuTextureView textureView = null;
		VirtualTextureGpuPageTable pageTable = null;
		VirtualTextureGpuCache tileCache = null;
		VirtualTextureMetadataTexture metadataTexture = null;
		boolean initialized = false;
		try {
			textureView = device.createTextureView(texture);
			var encoder = device.createCommandEncoder();
			for (int level = 0; level < image.mipLevels().size(); level++) {
				encoder.writeToTexture(texture, image.mipLevels().get(level), level, 0, 0, 0);
			}
			encoder.submit();
			pageTable = new VirtualTextureGpuPageTable(
				atlasWidth,
				atlasHeight,
				VIRTUAL_TILE_SIZE,
				atlasMipLevels,
				generation
			);
			int cacheMipLevels = Math.min(
				Integer.numberOfTrailingZeros(VIRTUAL_TILE_SIZE) + 1,
				pageTable.layout().mipLevelCount()
			);
			long configuredBudgetBytes = Math.multiplyExact(
				LoadsOfLodsClient.getConfig().highResolutionCacheMiB(),
				1024L * 1024
			);
			long atlasBudgetBytes = estimateTextureBytes(atlasWidth, atlasHeight, atlasMipLevels) / 4;
			long minimumCacheBytes = 0;
			for (int mip = 0; mip < cacheMipLevels; mip++) {
				long mipSize = Math.max(1, VIRTUAL_TILE_SIZE >> mip);
				minimumCacheBytes = Math.addExact(minimumCacheBytes, mipSize * mipSize * 4);
			}
			// Iris terrain never samples virtual tiles, so keep the cache at its minimum size.
			long cacheBudgetBytes = SodiumLodBridge.isIrisShaderPackInUse()
				? minimumCacheBytes
				: Math.max(minimumCacheBytes, Math.min(configuredBudgetBytes, atlasBudgetBytes));
			tileCache = new VirtualTextureGpuCache(
				pageTable,
				VIRTUAL_TILE_SIZE,
				cacheMipLevels,
				cacheBudgetBytes
			);
			metadataTexture = createVirtualTextureMetadata(atlasWidth, atlasHeight, atlasMipLevels);
			VirtualTextureTileBuilder tileBuilder = createVirtualTextureTileBuilder(
				atlas,
				regions,
				atlasWidth,
				atlasHeight,
				atlasMipLevels,
				generation
			);
			lowResolutionTexture = texture;
			lowResolutionTextureView = textureView;
			virtualTextureGpuPageTable = pageTable;
			virtualTextureTileBuilder = tileBuilder;
			virtualTextureGpuCache = tileCache;
			virtualTextureMetadataTexture = metadataTexture;
			virtualTextureLayout = new VirtualTextureLayout(
				atlasWidth,
				atlasHeight,
				VIRTUAL_TILE_SIZE,
				0
			);
			previewedAtlas = atlas;
			lowAtlasDownscaleFactor = image.downscaleFactor();
			animatedSprites = image.transferAnimatedSprites();
			animatedStateMismatchLogged = false;
			if (releaseHighResolutionAtlas) {
				Object pbrHolder = image.pbrImages() != null
					? IrisPbrAtlasReducer.detachHolder(highResolutionTexture)
					: null;
				if (image.pbrImages() != null) {
					NearAtlas created = NearAtlas.create(
						atlasWidth,
						atlasHeight,
						atlasMipLevels,
						net.minecraft.client.Minecraft.getInstance().getResourceManager()
					);
					if (nearAtlas != null) {
						nearAtlas.close();
					}
					nearAtlas = created;
					nearAtlasUnavailable = created == null;
				} else {
					nearAtlasUnavailable = false;
				}
				replaceBlockAtlasWithLowResolution(atlas, highResolutionTexture, texture, textureView);
				if (image.pbrImages() != null) {
					reducedPbrAtlases = IrisPbrAtlasReducer.apply(pbrHolder, texture, image.pbrImages(), image.downscaleFactor());
				}
			}
			initialized = true;
		} finally {
			if (!initialized) {
				if (textureView != null) {
					textureView.close();
				}
				if (tileCache != null) {
					tileCache.close();
				}
				closeVirtualTextureMetadata(metadataTexture);
				if (pageTable != null) {
					pageTable.close();
				}
				texture.close();
			}
		}
	}

	public static void updateAnimatedFrames(TextureAtlas atlas) {
		if (previewedAtlas != atlas || lowResolutionTexture == null || animatedSprites.isEmpty()) {
			return;
		}
		TextureAtlasAccessor atlasAccessor = (TextureAtlasAccessor) atlas;
		List<SpriteContents.AnimationState> states = atlasAccessor.loadsOfLods$getAnimatedTextureStates();
		Map<Identifier, AnimatedSpriteData> animationsById = new HashMap<>();
		for (AnimatedSpriteData animation : animatedSprites) {
			animationsById.put(animation.region().identifier(), animation);
		}
		List<PendingAnimationUpdate> updates = new ArrayList<>();
		List<NativeImage> temporaryImages = new ArrayList<>();
		int stateIndex = 0;
		try {
			for (TextureAtlasSprite sprite : atlasAccessor.loadsOfLods$getSprites()) {
				if (!sprite.isAnimated()) {
					continue;
				}
				if (stateIndex >= states.size()) {
					logAnimationStateMismatch(atlas, "fewer animation states than animated sprites");
					return;
				}
				SpriteContents.AnimationState state = states.get(stateIndex++);
				AnimatedSpriteData animation = animationsById.get(sprite.contents().name());
				if (animation == null) {
					continue;
				}
				SpriteAnimationStateAccessor stateAccessor = (SpriteAnimationStateAccessor) state;
				int framePosition = stateAccessor.loadsOfLods$getFrame();
				int subFrame = stateAccessor.loadsOfLods$getSubFrame();
				if (framePosition < 0 || framePosition >= animation.steps().size()) {
					logAnimationStateMismatch(atlas, "animation frame index outside cached frame sequence for " + sprite.contents().name());
					return;
				}
				NearAtlas near = nearAtlas;
				AnimationStep currentStep = animation.steps().get(framePosition);
				boolean blendFrames = animation.interpolated() && subFrame > 0;
				spriteFrames.put(
					sprite.contents().name().toString(),
					new FrameState(
						currentStep.frameIndex(),
						blendFrames
							? animation.steps().get((framePosition + 1) % animation.steps().size()).frameIndex()
							: currentStep.frameIndex(),
						blendFrames ? Math.round(Math.min(1.0F, subFrame / (float) currentStep.duration()) * BLEND_STEPS) : 0
					)
				);
				if (near != null) {
					AnimationStep nearStep = animation.steps().get(framePosition);
					int nearNext = animation.steps().get((framePosition + 1) % animation.steps().size()).frameIndex();
					boolean blend = animation.interpolated() && subFrame > 0;
					near.animate(
						sprite,
						nearStep.frameIndex(),
						blend ? nearNext : nearStep.frameIndex(),
						blend ? subFrame / (float) nearStep.duration() : 0.0F
					);
				}
				if (animation.lastFramePosition == framePosition
						&& (!animation.interpolated() || animation.lastSubFrame == subFrame)) {
					continue;
				}
				AnimationStep step = animation.steps().get(framePosition);
				List<NativeImage> frameImages = animation.imagesByFrame().get(step.frameIndex());
				if (frameImages == null) {
					logAnimationStateMismatch(atlas, "missing cached frame " + step.frameIndex() + " for " + sprite.contents().name());
					continue;
				}
				if (animation.interpolated() && subFrame > 0) {
					int nextPosition = (framePosition + 1) % animation.steps().size();
					int nextFrameIndex = animation.steps().get(nextPosition).frameIndex();
					List<NativeImage> nextImages = animation.imagesByFrame().get(nextFrameIndex);
					if (nextImages == null) {
						logAnimationStateMismatch(atlas, "missing interpolation frame " + nextFrameIndex + " for " + sprite.contents().name());
						continue;
					}
					float blendWeight = Math.min(1.0F, subFrame / (float) step.duration());
					frameImages = blendMipLevels(frameImages, nextImages, blendWeight);
					temporaryImages.addAll(frameImages);
				}
				updates.add(new PendingAnimationUpdate(animation, frameImages, framePosition, subFrame));
			}
			if (stateIndex != states.size()) {
				logAnimationStateMismatch(atlas, "more animation states than animated sprites");
				return;
			}
			if (updates.isEmpty()) {
				return;
			}
			var encoder = RenderSystem.getDevice().createCommandEncoder();
			for (PendingAnimationUpdate update : updates) {
				int sourceLeft = (update.animation().region().x() - update.animation().region().paddingX())
					/ lowAtlasDownscaleFactor;
				int sourceTop = (update.animation().region().y() - update.animation().region().paddingY())
					/ lowAtlasDownscaleFactor;
				for (int level = 0; level < update.mipLevels().size(); level++) {
					encoder.writeToTexture(
						lowResolutionTexture,
						update.mipLevels().get(level),
						level,
						0,
						sourceLeft >> level,
						sourceTop >> level
					);
				}
			}
			encoder.submit();
			for (PendingAnimationUpdate update : updates) {
				update.animation().lastFramePosition = update.framePosition();
				update.animation().lastSubFrame = update.subFrame();
			}
		} finally {
			closeImages(temporaryImages);
		}
	}

	private static List<NativeImage> blendMipLevels(
		List<NativeImage> current,
		List<NativeImage> next,
		float nextWeight
	) {
		if (current.size() != next.size()) {
			throw new IllegalStateException("Animated frame mip chains have different lengths");
		}
		List<NativeImage> blended = new ArrayList<>(current.size());
		try {
			for (int level = 0; level < current.size(); level++) {
				NativeImage currentLevel = current.get(level);
				NativeImage nextLevel = next.get(level);
				if (currentLevel.getWidth() != nextLevel.getWidth()
						|| currentLevel.getHeight() != nextLevel.getHeight()) {
					throw new IllegalStateException("Animated frame mip images have different dimensions");
				}
				NativeImage result = new NativeImage(currentLevel.getWidth(), currentLevel.getHeight(), true);
				blended.add(result);
				for (int y = 0; y < result.getHeight(); y++) {
					for (int x = 0; x < result.getWidth(); x++) {
						result.setPixel(x, y, blendColors(
							currentLevel.getPixel(x, y),
							nextLevel.getPixel(x, y),
							nextWeight
						));
					}
				}
			}
			return blended;
		} catch (RuntimeException | Error exception) {
			closeImages(blended);
			throw exception;
		}
	}

	private static int blendColors(int current, int next, float nextWeight) {
		float currentWeight = 1.0F - nextWeight;
		int alpha = Math.round((current >>> 24) * currentWeight + (next >>> 24) * nextWeight);
		int red = Math.round(((current >>> 16) & 255) * currentWeight + ((next >>> 16) & 255) * nextWeight);
		int green = Math.round(((current >>> 8) & 255) * currentWeight + ((next >>> 8) & 255) * nextWeight);
		int blue = Math.round((current & 255) * currentWeight + (next & 255) * nextWeight);
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}

	private static void logAnimationStateMismatch(TextureAtlas atlas, String reason) {
		if (!animatedStateMismatchLogged) {
			LoadsOfLods.LOGGER.error("Could not synchronize low-atlas animations for {}: {}", atlas.location(), reason);
			animatedStateMismatchLogged = true;
		}
	}

	private static void closeAnimatedSprites(List<AnimatedSpriteData> animations) {
		for (AnimatedSpriteData animation : animations) {
			closeFrameImages(animation.imagesByFrame());
		}
	}

	private static void closeFrameImages(Map<Integer, List<NativeImage>> imagesByFrame) {
		for (List<NativeImage> mipLevels : imagesByFrame.values()) {
			closeImages(mipLevels);
		}
	}

	private static void closeImages(List<NativeImage> images) {
		for (NativeImage image : images) {
			image.close();
		}
	}

	private static void closeLowResolutionTexture() {
		lowPreviewEnabled = false;
		distancePreviewEnabled = false;
		previewedAtlas = null;
		NearAtlas near = nearAtlas;
		nearAtlas = null;
		if (near != null) {
			near.close();
		}
		virtualTextureLayout = null;
		pendingVirtualTextureQuads.clear();
		pendingItemQuads.clear();
		pendingVirtualTextureTiles.clear();
		animatedVirtualTextureTiles.clear();
		animatedTiles.clear();
		discardPreparedTiles();
		spriteFrames.clear();
		activeVirtualTextureCursor = null;
		activeVirtualTextureQuad = null;
		activeVirtualTextureDistanceSquared = Double.POSITIVE_INFINITY;
		closeVirtualTextureGpuPageTable();
		closeAnimatedSprites(animatedSprites);
		animatedSprites = List.of();
		lowAtlasDownscaleFactor = 1;
		animatedStateMismatchLogged = false;
		GpuTextureView textureView = lowResolutionTextureView;
		GpuTexture texture = lowResolutionTexture;
		lowResolutionTextureView = null;
		lowResolutionTexture = null;
		boolean atlasOwnsTexture = atlasOwnsLowResolutionTexture;
		atlasOwnsLowResolutionTexture = false;
		if (!atlasOwnsTexture && textureView != null) {
			textureView.close();
		}
		if (!atlasOwnsTexture && texture != null) {
			texture.close();
		}
		VirtualTextureMetadataTexture metadata = virtualTextureMetadataTexture;
		virtualTextureMetadataTexture = null;
		closeVirtualTextureMetadata(metadata);
	}

	private static void closeVirtualTextureGpuPageTable() {
		VirtualTextureGpuCache cache = virtualTextureGpuCache;
		virtualTextureGpuCache = null;
		if (cache != null) {
			cache.close();
		}
		virtualTextureTileBuilder = null;
		VirtualTextureGpuPageTable pageTable = virtualTextureGpuPageTable;
		virtualTextureGpuPageTable = null;
		if (pageTable != null) {
			pageTable.close();
		}
		VirtualTextureMetadataTexture metadata = virtualTextureMetadataTexture;
		virtualTextureMetadataTexture = null;
		closeVirtualTextureMetadata(metadata);
	}

	private static void ensureFallbackVirtualTextureResources() {
		if (fallbackPageTable != null && fallbackTileCache != null) {
			return;
		}
		VirtualTextureGpuPageTable pageTable = new VirtualTextureGpuPageTable(1, 1, VIRTUAL_TILE_SIZE, 1, 0);
		VirtualTextureGpuCache cache = null;
		VirtualTextureMetadataTexture metadata = null;
		try {
			cache = new VirtualTextureGpuCache(
				pageTable,
				VIRTUAL_TILE_SIZE,
				1,
				(long) VIRTUAL_TILE_SIZE * VIRTUAL_TILE_SIZE * 4
			);
			metadata = createVirtualTextureMetadata(1, 1, 1);
			fallbackPageTable = pageTable;
			fallbackTileCache = cache;
			fallbackMetadataTexture = metadata;
		} catch (RuntimeException | Error exception) {
			if (metadata != null) {
				closeVirtualTextureMetadata(metadata);
			}
			if (cache != null) {
				cache.close();
			}
			pageTable.close();
			throw exception;
		}
	}

	private static VirtualTextureMetadataTexture createVirtualTextureMetadata(
		int atlasWidth,
		int atlasHeight,
		int mipLevelCount
	) {
		if (atlasWidth <= 0 || atlasWidth > 65536 || atlasHeight <= 0 || atlasHeight > 65536
				|| mipLevelCount <= 0 || mipLevelCount > 255) {
			throw new IllegalArgumentException("Virtual texture metadata is outside its supported range");
		}
		var device = RenderSystem.getDevice();
		GpuTexture texture = device.createTexture(
			"Loads of Lods virtual atlas dimensions",
			GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
			GpuFormat.RGBA8_UNORM,
			2,
			1,
			1,
			1
		);
		GpuTextureView view = null;
		boolean initialized = false;
		try {
			view = device.createTextureView(texture);
			NativeImage image = new NativeImage(2, 1, true);
			try (image) {
				// The sizes are stored minus one so a 65536 texel atlas still fits in two bytes.
				int storedWidth = atlasWidth - 1;
				int storedHeight = atlasHeight - 1;
				int red = storedWidth & 0xFF;
				int green = storedWidth >>> 8;
				int blue = storedHeight & 0xFF;
				int alpha = storedHeight >>> 8;
				image.setPixel(0, 0, (alpha << 24) | (blue << 16) | (green << 8) | red);
				image.setPixel(1, 0, mipLevelCount << 16);
				var encoder = device.createCommandEncoder();
				encoder.writeToTexture(texture, image);
				encoder.submit();
			}
			initialized = true;
			return new VirtualTextureMetadataTexture(texture, view);
		} finally {
			if (!initialized) {
				if (view != null) {
					view.close();
				}
				texture.close();
			}
		}
	}

	private static void closeVirtualTextureMetadata(VirtualTextureMetadataTexture metadata) {
		if (metadata == null) {
			return;
		}
		metadata.view().close();
		metadata.texture().close();
	}

	private static long estimateTextureBytes(int width, int height, int mipLevelCount) {
		long bytes = 0;
		for (int mip = 0; mip < mipLevelCount; mip++) {
			bytes = Math.addExact(
				bytes,
				Math.multiplyExact(
					Math.multiplyExact((long) Math.max(1, width >> mip), Math.max(1, height >> mip)),
					4L
				)
			);
		}
		return bytes;
	}

	private static void replaceBlockAtlasWithLowResolution(
		TextureAtlas atlas,
		GpuTexture highResolutionTexture,
		GpuTexture lowResolutionTexture,
		GpuTextureView lowResolutionTextureView
	) {
		TextureAtlasAccessor atlasAccessor = (TextureAtlasAccessor) atlas;
		AbstractTextureAccessor textureAccessor = (AbstractTextureAccessor) atlas;
		int originalWidth = ((TextureAtlasAccessor) atlas).loadsOfLods$getWidth();
		int originalHeight = ((TextureAtlasAccessor) atlas).loadsOfLods$getHeight();
		originalAtlasReleaseConfirmed = false;
		GpuTextureView[] oldMipViews = atlasAccessor.loadsOfLods$getMipViews();
		GpuTextureView oldTextureView = textureAccessor.loadsOfLods$getTextureView();
		atlasAccessor.loadsOfLods$setMipViews(new GpuTextureView[0]);
		textureAccessor.loadsOfLods$setTexture(lowResolutionTexture);
		textureAccessor.loadsOfLods$setTextureView(lowResolutionTextureView);
		atlasOwnsLowResolutionTexture = true;
		SodiumLodBridge.trackAtlasForIris(lowResolutionTexture, atlas, highResolutionTexture);

		for (GpuTextureView mipView : oldMipViews) {
			if (mipView != null && mipView != oldTextureView) {
				closeReleasedAtlasView(mipView);
			}
		}
		if (oldTextureView != null && oldTextureView != lowResolutionTextureView) {
			closeReleasedAtlasView(oldTextureView);
		}
		boolean originalTextureReleased = false;
		if (highResolutionTexture != null && highResolutionTexture != lowResolutionTexture) {
			try {
				highResolutionTexture.close();
				originalTextureReleased = true;
				originalAtlasReleaseConfirmed = true;
			} catch (RuntimeException exception) {
				LoadsOfLods.LOGGER.error("Could not release the original high-resolution block atlas allocation", exception);
			}
		}
		if (originalTextureReleased) {
			LoadsOfLods.LOGGER.info(
				"Released the original {}x{} block atlas GPU allocation; streamed terrain uses the {}x{} fallback atlas",
				originalWidth,
				originalHeight,
				lowResolutionTexture.getWidth(0),
				lowResolutionTexture.getHeight(0)
			);
		} else {
			originalAtlasReleaseConfirmed = false;
			LoadsOfLods.LOGGER.error(
				"Streamed mode is active, but the original {}x{} block atlas allocation could not be confirmed as released",
				originalWidth,
				originalHeight
			);
		}
	}

	private static void closeReleasedAtlasView(GpuTextureView view) {
		try {
			view.close();
		} catch (RuntimeException exception) {
			LoadsOfLods.LOGGER.error("Could not release an original block-atlas mip view", exception);
		}
	}

	private static VirtualTextureTileBuilder createVirtualTextureTileBuilder(
		TextureAtlas atlas,
		List<SpriteRegion> regions,
		int atlasWidth,
		int atlasHeight,
		int mipLevelCount,
		long atlasGeneration
	) {
		Map<Identifier, SpriteRegion> regionsById = new HashMap<>();
		for (SpriteRegion region : regions) {
			regionsById.put(region.identifier(), region);
		}
		Map<String, AnimatedStrip> strips = new HashMap<>();
		List<SpriteSource> sources = new ArrayList<>();
		for (TextureAtlasSprite sprite : ((TextureAtlasAccessor) atlas).loadsOfLods$getSprites()) {
			SpriteRegion region = regionsById.get(sprite.contents().name());
			if (region == null) {
				continue;
			}
			List<MipImage> mipImages = new ArrayList<>();
			if (region.animated()) {
				strips.put(
					region.identifier().toString(),
					new AnimatedStrip(
						((SpriteContentsAccessor) sprite.contents()).loadsOfLods$getMipImages(),
						region.width(),
						region.height()
					)
				);
			} else {
				NativeImage[] images = ((SpriteContentsAccessor) sprite.contents()).loadsOfLods$getMipImages();
				for (NativeImage image : images) {
					mipImages.add(new MipImage() {
						@Override
						public int width() {
							return image.getWidth();
						}

						@Override
						public int height() {
							return image.getHeight();
						}

						@Override
						public int pixel(int x, int y) {
							return image.getPixel(x, y);
						}
					});
				}
			}
			sources.add(new SpriteSource(
				region.identifier().toString(),
				region.x(),
				region.y(),
				region.width(),
				region.height(),
				region.paddingX(),
				region.paddingY(),
				region.animated(),
				mipImages
			));
		}
		VirtualTextureTileBuilder builder = new VirtualTextureTileBuilder(
			atlasWidth,
			atlasHeight,
			VIRTUAL_TILE_SIZE,
			mipLevelCount,
			atlasGeneration,
			sources
		);
		builder.setAnimationFrames((name, mipLevel) -> {
			AnimatedStrip strip = strips.get(name);
			FrameState state = spriteFrames.getOrDefault(name, FrameState.FIRST);
			return strip == null ? null : strip.frame(state, mipLevel);
		});
		return builder;
	}

	// The full-resolution frame strip of an animated sprite. A frame is a view into the strip, not a copy.
	private record AnimatedStrip(NativeImage[] mips, int frameWidth, int frameHeight) {
		MipImage frame(FrameState state, int mipLevel) {
			MipImage current = frame(state.frame(), mipLevel);
			if (current == null || state.blend() == 0 || state.next() == state.frame()) {
				return current;
			}
			MipImage next = frame(state.next(), mipLevel);
			if (next == null) {
				return current;
			}
			int weight = state.blend();
			return new MipImage() {
				@Override
				public int width() {
					return current.width();
				}

				@Override
				public int height() {
					return current.height();
				}

				@Override
				public int pixel(int x, int y) {
					int a = current.pixel(x, y);
					int b = next.pixel(x, y);
					int result = 0;
					for (int shift = 0; shift < 32; shift += 8) {
						int from = a >>> shift & 255;
						int to = b >>> shift & 255;
						result |= (from + (to - from) * weight / BLEND_STEPS & 255) << shift;
					}
					return result;
				}
			};
		}

		MipImage frame(int frameIndex, int mipLevel) {
			if (mipLevel >= mips.length) {
				return null;
			}
			NativeImage image = mips[mipLevel];
			int columns = Math.max(1, mips[0].getWidth() / Math.max(1, frameWidth));
			int rows = Math.max(1, mips[0].getHeight() / Math.max(1, frameHeight));
			int width = Math.max(1, image.getWidth() / columns);
			int height = Math.max(1, image.getHeight() / rows);
			int originX = frameIndex % columns * width;
			int originY = Math.min(rows - 1, frameIndex / columns) * height;
			return new MipImage() {
				@Override
				public int width() {
					return width;
				}

				@Override
				public int height() {
					return height;
				}

				@Override
				public int pixel(int x, int y) {
					return image.getPixel(originX + x, originY + y);
				}
			};
		}
	}

	private static int averageSourcePixels(
		BufferedImage source,
		int x,
		int y,
		int spriteWidth,
		int spriteHeight,
		int factor
	) {
		long alphaSum = 0;
		long redSum = 0;
		long greenSum = 0;
		long blueSum = 0;
		int sampleCount = 0;
		for (int sampleY = 0; sampleY < factor; sampleY++) {
			int sourceY = Math.clamp(y + sampleY, 0, Math.min(source.getHeight(), spriteHeight) - 1);
			for (int sampleX = 0; sampleX < factor; sampleX++) {
				int sourceX = Math.clamp(x + sampleX, 0, Math.min(source.getWidth(), spriteWidth) - 1);
				int color = source.getRGB(sourceX, sourceY);
				int alpha = color >>> 24;
				alphaSum += alpha;
				redSum += (long) ((color >>> 16) & 255) * alpha;
				greenSum += (long) ((color >>> 8) & 255) * alpha;
				blueSum += (long) (color & 255) * alpha;
				sampleCount++;
			}
		}
		if (alphaSum == 0) {
			return 0;
		}
		int alpha = (int) ((alphaSum + sampleCount / 2) / sampleCount);
		int red = (int) ((redSum + alphaSum / 2) / alphaSum);
		int green = (int) ((greenSum + alphaSum / 2) / alphaSum);
		int blue = (int) ((blueSum + alphaSum / 2) / alphaSum);
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}

	@FunctionalInterface
	interface PixelAverager {
		int average(int first, int second, int third, int fourth);
	}

	private static void tintMagenta(NativeImage image) {
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int pixel = image.getPixel(x, y);
				int red = Math.min(255, ((pixel >>> 16) & 0xFF) / 2 + 128);
				int green = ((pixel >>> 8) & 0xFF) / 3;
				int blue = Math.min(255, (pixel & 0xFF) / 2 + 128);
				image.setPixel(x, y, (pixel & 0xFF000000) | (red << 16) | (green << 8) | blue);
			}
		}
	}

	private static NativeImage createMipLevel(NativeImage previous, List<SpriteRegion> regions, int level) {
		return createMipLevel(previous, regions, level, AtlasPreviewState::averagePixels);
	}

	static NativeImage createMipLevel(
		NativeImage previous,
		List<SpriteRegion> regions,
		int level,
		PixelAverager averager
	) {
		int width = Math.max(1, previous.getWidth() >> 1);
		int height = Math.max(1, previous.getHeight() >> 1);
		NativeImage mipLevel = new NativeImage(width, height, true);
		try {
			int previousLevel = level - 1;
			for (SpriteRegion region : regions) {
				int sourceLeft = Math.max(0, (region.x() - region.paddingX()) >> previousLevel);
				int sourceTop = Math.max(0, (region.y() - region.paddingY()) >> previousLevel);
				int sourceRight = Math.min(
					previous.getWidth(),
					ceilDiv(region.x() + region.width() + region.paddingX(), 1 << previousLevel)
				);
				int sourceBottom = Math.min(
					previous.getHeight(),
					ceilDiv(region.y() + region.height() + region.paddingY(), 1 << previousLevel)
				);
				int targetLeft = sourceLeft >> 1;
				int targetTop = sourceTop >> 1;
				int targetRight = Math.min(width, ceilDiv(sourceRight, 2));
				int targetBottom = Math.min(height, ceilDiv(sourceBottom, 2));
				for (int y = targetTop; y < targetBottom; y++) {
					for (int x = targetLeft; x < targetRight; x++) {
						int x0 = Math.clamp(x * 2, sourceLeft, sourceRight - 1);
						int x1 = Math.clamp(x * 2 + 1, sourceLeft, sourceRight - 1);
						int y0 = Math.clamp(y * 2, sourceTop, sourceBottom - 1);
						int y1 = Math.clamp(y * 2 + 1, sourceTop, sourceBottom - 1);
						mipLevel.setPixel(x, y, averager.average(
							previous.getPixel(x0, y0),
							previous.getPixel(x1, y0),
							previous.getPixel(x0, y1),
							previous.getPixel(x1, y1)
						));
					}
				}
				if (isTiledPortalTexture(region)) {
					int divisor = 1 << level;
					makeSpriteEdgesContinuous(
						mipLevel,
						region.x() >> level,
						region.y() >> level,
						Math.max(1, ceilDiv(region.width(), divisor)),
						Math.max(1, ceilDiv(region.height(), divisor)),
						region.paddingX() >> level,
						region.paddingY() >> level
					);
				}
			}
			return mipLevel;
		} catch (RuntimeException | Error exception) {
			mipLevel.close();
			throw exception;
		}
	}

	private static int averagePixels(int first, int second, int third, int fourth) {
		int alphaSum = (first >>> 24) + (second >>> 24) + (third >>> 24) + (fourth >>> 24);
		if (alphaSum == 0) {
			return 0;
		}
		int red = (((first >>> 16) & 255) * (first >>> 24)
			+ ((second >>> 16) & 255) * (second >>> 24)
			+ ((third >>> 16) & 255) * (third >>> 24)
			+ ((fourth >>> 16) & 255) * (fourth >>> 24) + alphaSum / 2) / alphaSum;
		int green = (((first >>> 8) & 255) * (first >>> 24)
			+ ((second >>> 8) & 255) * (second >>> 24)
			+ ((third >>> 8) & 255) * (third >>> 24)
			+ ((fourth >>> 8) & 255) * (fourth >>> 24) + alphaSum / 2) / alphaSum;
		int blue = ((first & 255) * (first >>> 24)
			+ (second & 255) * (second >>> 24)
			+ (third & 255) * (third >>> 24)
			+ (fourth & 255) * (fourth >>> 24) + alphaSum / 2) / alphaSum;
		int alpha = (alphaSum + 2) / 4;
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}

	private static void makeSpriteEdgesContinuous(
		NativeImage image,
		int contentX,
		int contentY,
		int contentWidth,
		int contentHeight,
		int paddingX,
		int paddingY
	) {
		if (contentWidth < 2 || contentHeight < 2) {
			return;
		}
		int left = Math.max(0, contentX - paddingX);
		int top = Math.max(0, contentY - paddingY);
		int right = Math.min(image.getWidth(), contentX + contentWidth + paddingX);
		int bottom = Math.min(image.getHeight(), contentY + contentHeight + paddingY);
		int contentRight = contentX + contentWidth - 1;
		int contentBottom = contentY + contentHeight - 1;
		for (int y = contentY; y <= contentBottom; y++) {
			int edge = averageTwoPixels(
				image.getPixel(contentX, y),
				image.getPixel(contentRight, y)
			);
			image.setPixel(contentX, y, edge);
			image.setPixel(contentRight, y, edge);
			for (int x = left; x < contentX; x++) {
				image.setPixel(x, y, edge);
			}
			for (int x = contentRight + 1; x < right; x++) {
				image.setPixel(x, y, edge);
			}
		}
		for (int x = left; x < right; x++) {
			int edge = averageTwoPixels(
				image.getPixel(x, contentY),
				image.getPixel(x, contentBottom)
			);
			image.setPixel(x, contentY, edge);
			image.setPixel(x, contentBottom, edge);
			for (int y = top; y < contentY; y++) {
				image.setPixel(x, y, edge);
			}
			for (int y = contentBottom + 1; y < bottom; y++) {
				image.setPixel(x, y, edge);
			}
		}
	}

	private static int averageTwoPixels(int first, int second) {
		int firstAlpha = first >>> 24;
		int secondAlpha = second >>> 24;
		int alphaSum = firstAlpha + secondAlpha;
		if (alphaSum == 0) {
			return 0;
		}
		int red = ((((first >>> 16) & 255) * firstAlpha) + (((second >>> 16) & 255) * secondAlpha) + alphaSum / 2)
			/ alphaSum;
		int green = ((((first >>> 8) & 255) * firstAlpha) + (((second >>> 8) & 255) * secondAlpha) + alphaSum / 2)
			/ alphaSum;
		int blue = (((first & 255) * firstAlpha) + ((second & 255) * secondAlpha) + alphaSum / 2) / alphaSum;
		int alpha = (alphaSum + 1) / 2;
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}

	private static int ceilDiv(int value, int divisor) {
		return (value + divisor - 1) / divisor;
	}

	private static boolean isTiledPortalTexture(SpriteRegion region) {
		return region.identifier().getPath().equals("block/nether_portal");
	}

	static int tilePixels(SpriteRegion region, int factor) {
		int width = (region.width() + region.paddingX()) / factor + region.paddingX() / factor;
		int height = (region.height() + region.paddingY()) / factor + region.paddingY() / factor;
		return width * height;
	}

	// The reduced pixels of a sprite including its padding, row by row; independent of where it sits in the atlas.
	static int[] reduceSprite(BufferedImage source, SpriteRegion region, int downscaleFactor) {
		int firstLowX = -region.paddingX() / downscaleFactor;
		int lastLowX = (region.width() + region.paddingX()) / downscaleFactor - 1;
		int firstLowY = -region.paddingY() / downscaleFactor;
		int lastLowY = (region.height() + region.paddingY()) / downscaleFactor - 1;
		int tileWidth = lastLowX - firstLowX + 1;
		int[] tile = new int[tileWidth * (lastLowY - firstLowY + 1)];
		for (int lowY = firstLowY; lowY <= lastLowY; lowY++) {
			int relativeY = lowY * downscaleFactor;
			int sourceY = Math.clamp(relativeY, 0, Math.min(source.getHeight(), region.height()) - 1);
			for (int lowX = firstLowX; lowX <= lastLowX; lowX++) {
				int relativeX = lowX * downscaleFactor;
				int sourceX = Math.clamp(relativeX, 0, Math.min(source.getWidth(), region.width()) - 1);
				tile[(lowY - firstLowY) * tileWidth + (lowX - firstLowX)] =
					averageSourcePixels(source, sourceX, sourceY, region.width(), region.height(), downscaleFactor);
			}
		}
		return tile;
	}

	static void blitTile(NativeImage atlas, int[] tile, SpriteRegion region, int downscaleFactor) {
		int firstLowX = -region.paddingX() / downscaleFactor;
		int lastLowX = (region.width() + region.paddingX()) / downscaleFactor - 1;
		int firstLowY = -region.paddingY() / downscaleFactor;
		int lastLowY = (region.height() + region.paddingY()) / downscaleFactor - 1;
		int tileWidth = lastLowX - firstLowX + 1;
		for (int lowY = firstLowY; lowY <= lastLowY; lowY++) {
			int targetY = region.y() / downscaleFactor + lowY;
			for (int lowX = firstLowX; lowX <= lastLowX; lowX++) {
				int targetX = region.x() / downscaleFactor + lowX;
				if (targetX >= 0 && targetY >= 0 && targetX < atlas.getWidth() && targetY < atlas.getHeight()) {
					atlas.setPixel(targetX, targetY, tile[(lowY - firstLowY) * tileWidth + (lowX - firstLowX)]);
				}
			}
		}
	}

	record SpriteRegion(
		Identifier identifier,
		int x,
		int y,
		int width,
		int height,
		int paddingX,
		int paddingY,
		boolean animated
	) {
	}

	public record VirtualTextureSamplingViews(
		GpuTextureView tileAtlas,
		GpuTextureView pageTable,
		GpuTextureView atlasMetadata
	) {
	}

	private record VirtualTextureMetadataTexture(GpuTexture texture, GpuTextureView view) {
	}

	public record VirtualTextureCacheStatus(
		boolean available,
		int residentTiles,
		long usedBytes,
		long capacityBytes,
		int pendingTiles,
		int pendingQuads,
		long capturedQuads,
		long capturedSpriteUses
	) {
	}

	private record AnimationStep(int frameIndex, int duration) {
	}

	private static final class AnimatedSpriteData {
		private final SpriteRegion region;
		private final List<AnimationStep> steps;
		private final Map<Integer, List<NativeImage>> imagesByFrame;
		private final boolean interpolated;
		private int lastFramePosition = -1;
		private int lastSubFrame = -1;

		private AnimatedSpriteData(
			SpriteRegion region,
			List<AnimationStep> steps,
			Map<Integer, List<NativeImage>> imagesByFrame,
			boolean interpolated
		) {
			this.region = region;
			this.steps = List.copyOf(steps);
			this.imagesByFrame = Map.copyOf(imagesByFrame);
			this.interpolated = interpolated;
		}

		private SpriteRegion region() {
			return region;
		}

		private List<AnimationStep> steps() {
			return steps;
		}

		private Map<Integer, List<NativeImage>> imagesByFrame() {
			return imagesByFrame;
		}

		private boolean interpolated() {
			return interpolated;
		}
	}

	private record PendingAnimationUpdate(
		AnimatedSpriteData animation,
		List<NativeImage> mipLevels,
		int framePosition,
		int subFrame
	) {
	}

	private static final class LowAtlasImage implements AutoCloseable {
		private final List<NativeImage> mipLevels;
		private final int downscaleFactor;
		private final List<AnimatedSpriteData> animatedSprites;
		private final IrisPbrAtlasReducer.Images pbrImages;
		private boolean animationsTransferred;

		private LowAtlasImage(
			List<NativeImage> mipLevels,
			int downscaleFactor,
			List<AnimatedSpriteData> animatedSprites,
			IrisPbrAtlasReducer.Images pbrImages
		) {
			this.mipLevels = List.copyOf(mipLevels);
			this.downscaleFactor = downscaleFactor;
			this.animatedSprites = List.copyOf(animatedSprites);
			this.pbrImages = pbrImages;
		}

		private IrisPbrAtlasReducer.Images pbrImages() {
			return pbrImages;
		}

		private List<NativeImage> mipLevels() {
			return mipLevels;
		}

		private int downscaleFactor() {
			return downscaleFactor;
		}

		private List<AnimatedSpriteData> transferAnimatedSprites() {
			animationsTransferred = true;
			return animatedSprites;
		}

		@Override
		public void close() {
			closeImages(mipLevels);
			if (pbrImages != null) {
				pbrImages.close();
			}
			if (!animationsTransferred) {
				closeAnimatedSprites(animatedSprites);
			}
		}
	}
}
