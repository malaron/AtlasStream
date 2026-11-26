package com.malaron.atlasstream.client.atlas;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;

import com.malaron.atlasstream.AtlasStream;
import com.malaron.atlasstream.client.AtlasStreamClient;
import com.malaron.atlasstream.client.diagnostics.BuildPhaseToast;
import com.malaron.atlasstream.client.diagnostics.ItemStreamDiagnostics;
import com.malaron.atlasstream.client.diagnostics.LodPerformanceStats;
import com.malaron.atlasstream.client.mixin.AbstractTextureAccessor;
import com.malaron.atlasstream.client.mixin.SpriteAnimationStateAccessor;
import com.malaron.atlasstream.client.mixin.SpriteContentsAccessor;
import com.malaron.atlasstream.client.mixin.TextureAtlasAccessor;
import com.malaron.atlasstream.client.sodium.SodiumChunkMeshPriority;
import com.malaron.atlasstream.client.sodium.SodiumTerrainIntegration;
import com.malaron.atlasstream.client.streaming.VirtualTextureGpuCache;
import com.malaron.atlasstream.client.streaming.VirtualTextureGpuPageTable;
import com.malaron.atlasstream.streaming.AnimationBlendCache;
import com.malaron.atlasstream.streaming.VirtualTextureLayout;
import com.malaron.atlasstream.streaming.VirtualTextureRequestTracker;
import com.malaron.atlasstream.streaming.VirtualTextureTileBuilder.MipImage;
import com.malaron.atlasstream.streaming.VirtualTextureTileBuilder.SpriteSource;
import com.malaron.atlasstream.streaming.VirtualTextureTileBuilder.TileMip;
import com.malaron.atlasstream.streaming.VirtualTextureTileBuilder;
import com.malaron.atlasstream.streaming.VirtualTextureTileKey;
import com.malaron.atlasstream.streaming.VirtualTextureTileRequestQueue;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.client.resources.metadata.animation.AnimationFrame;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

public final class AtlasRuntimeState {
	private static final int VIRTUAL_TILE_SIZE = 128;
	private static final long IRIS_VIRTUAL_CACHE_BUDGET_BYTES = 256L * 1024 * 1024;
	private static final long MAX_PREVIEW_BYTES = 2048L * 1024 * 1024;
	private static final long MAX_ANIMATION_BLEND_CACHE_BYTES = 32L * 1024 * 1024;
	private static final AnimationBlendCache<AnimationBlendKey, List<NativeImage>> animationBlendCache =
		new AnimationBlendCache<>(MAX_ANIMATION_BLEND_CACHE_BYTES, AtlasRuntimeState::closeImages);
	private static final int MAX_PENDING_TILE_REQUESTS = 8192;
	private static final int MAX_UV_QUADS_PER_TICK = 128;
	private static int animatedTileFailuresLogged;
	private static final int MAX_TILE_KEYS_PER_TICK = 512;
	private static final int MAX_TILE_UPLOADS_PER_TICK = 4;
	private static final long MAX_TILE_UPLOAD_TIME_NANOS_PER_TICK = 4_000_000L;
	private static final AtomicBoolean BUILDING = new AtomicBoolean();
	private static final AtomicLong BUILD_SERIAL = new AtomicLong();
	private static volatile long activeBuildSerial;
	private static volatile CompletableFuture<?> activeBuildFuture;
	private static final VirtualTextureRequestTracker VIRTUAL_TEXTURE_REQUEST_TRACKER =
		new VirtualTextureRequestTracker();
	private static final VirtualTextureRequestTracker pendingVirtualTextureQuads = new VirtualTextureRequestTracker();
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
	private static volatile GpuTexture lowAtlasRemapTexture;
	private static volatile GpuTextureView lowAtlasRemapTextureView;
	private static volatile GpuTexture reloadFallbackTexture;
	private static volatile GpuTextureView reloadFallbackView;
	private static volatile GpuTexture reloadFallbackRemapTexture;
	private static volatile GpuTextureView reloadFallbackRemapView;
	private static volatile LowAtlasRemap reloadFallbackMapping;
	private static volatile LowAtlasRemap lowAtlasRemap;
	private static volatile boolean lowAtlasOriginalLayout;
	private static volatile boolean screenEffectUsesNearAtlas;
	private static final AtomicBoolean SCREEN_OVERLAY_REMAP_LOGGED = new AtomicBoolean();
	private static final Set<String> CONDUIT_REMAP_LOGGED = new HashSet<>();
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
	private static int lowAtlasStreamedMipLevels;
	private static volatile long generation;
	private static VirtualTextureRequestTracker.TileCursor activeVirtualTextureCursor;
	private static double activeVirtualTextureDistanceSquared = Double.POSITIVE_INFINITY;

	private AtlasRuntimeState() {
	}

	private static void cancelActiveBuild() {
		CompletableFuture<?> future = activeBuildFuture;
		boolean hadActiveBuild = future != null || BUILDING.get();
		if (!hadActiveBuild) {
			return;
		}
		generation++;
		if (future != null) {
			future.cancel(true);
		}
		activeBuildSerial = BUILD_SERIAL.incrementAndGet();
		BUILDING.set(false);
		reloadBuildRunning = false;
		parkedActivation = null;
		AtlasStream.LOGGER.info("Cancelled stale low-resolution atlas build");
	}

	public static GpuTextureView reloadFallbackView(TextureAtlas atlas) {
		GpuTextureView fallback = reloadFallbackView;
		LowAtlasRemap mapping = reloadFallbackMapping;
		TextureAtlasAccessor geometry = (TextureAtlasAccessor) atlas;
		return fallback != null && mapping != null
				&& TextureAtlas.LOCATION_BLOCKS.equals(atlas.location())
				&& mapping.matches(geometry.atlasStream$getWidth(), geometry.atlasStream$getHeight())
			? fallback
			: null;
	}

	public static GpuTexture reloadFallbackTexture(TextureAtlas atlas) {
		GpuTexture fallback = reloadFallbackTexture;
		LowAtlasRemap mapping = reloadFallbackMapping;
		TextureAtlasAccessor geometry = (TextureAtlasAccessor) atlas;
		return fallback != null && mapping != null
				&& TextureAtlas.LOCATION_BLOCKS.equals(atlas.location())
				&& mapping.matches(geometry.atlasStream$getWidth(), geometry.atlasStream$getHeight())
			? fallback
			: null;
	}

	public static void setBuildPhase(String message) {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> SystemToast.addOrUpdate(
			client.gui.toastManager(),
			BuildPhaseToast.ID,
			Component.literal("AtlasStream"),
			Component.literal(message)
		));
	}

	public static void clearBuildPhase() {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> SystemToast.forceHide(client.gui.toastManager(), BuildPhaseToast.ID));
	}

	public static float[] remapLowAtlasUv(Identifier atlasLocation, float u, float v) {
		LowAtlasRemap remap = lowAtlasRemap != null ? lowAtlasRemap : reloadFallbackMapping;
		if (!TextureAtlas.LOCATION_BLOCKS.equals(atlasLocation) || remap == null) {
			return new float[] {u, v};
		}
		return remap.map(u, v);
	}

	public static float[] remapLowAtlasUv(
		Identifier atlasLocation, float u, float v, boolean upperBound
	) {
		LowAtlasRemap remap = lowAtlasRemap != null ? lowAtlasRemap : reloadFallbackMapping;
		if (!TextureAtlas.LOCATION_BLOCKS.equals(atlasLocation) || remap == null) {
			return new float[] {u, v};
		}
		return remap.map(u, v, upperBound);
	}

	public static void logScreenOverlayRemap(
		Identifier atlasLocation, float u0, float v0, float u1, float v1
	) {
		LowAtlasRemap remap = lowAtlasRemap != null ? lowAtlasRemap : reloadFallbackMapping;
		if (SCREEN_OVERLAY_REMAP_LOGGED.compareAndSet(false, true)) {
			if (remap == null) {
				AtlasStream.LOGGER.info(
					"Screen overlay remap called without a remap: atlas={}, source=({},{})-({},{})",
					atlasLocation, u0, v0, u1, v1
				);
				return;
			}

			float[] first = remap.map(u0, v0);
			float[] second = remap.map(u1, v1, true);
			AtlasStream.LOGGER.info(
				"Screen overlay remap: atlas={}, source=({},{})-({},{ }), destination=({},{})-({},{ }), "
					+ "sourceSprite={}, destinationSprite={}",
				atlasLocation, u0, v0, u1, v1, first[0], first[1], second[0], second[1],
				remap.placementAt(u0, v0), remap.placementAt(u1, v1, true)
			);
		}
	}

	public static void logFireOverlayRemap(TextureAtlasSprite sprite) {
		LowAtlasRemap remap = lowAtlasRemap != null ? lowAtlasRemap : reloadFallbackMapping;
		if (remap != null && SCREEN_OVERLAY_REMAP_LOGGED.compareAndSet(false, true)) {
			float[] first = remap.map(sprite.getU0(), sprite.getV0());
			float[] second = remap.map(sprite.getU1(), sprite.getV1(), true);
			Identifier placement = remap.placementAt(sprite.getU0(), sprite.getV0());
			AtlasStream.LOGGER.info(
				"Fire screen overlay remap: sprite={} atlas={} source0={} sourceV0={} "
					+ "sourceU1={} sourceV1={} destinationU0={} destinationV0={} "
					+ "destinationU1={} destinationV1={} placement={}",
				sprite.contents().name(), sprite.atlasLocation(),
				sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(),
				first[0], first[1], second[0], second[1],
				placement
			);
		}
	}

	public static void buildAndEnable(
		TextureAtlas atlas,
		ResourceManager resourceManager,
		FabricClientCommandSource source,
		boolean enableDistancePreview
	) {
		buildAndEnable(atlas, resourceManager, source::sendFeedback, source::sendError, enableDistancePreview);
	}

	private static final class LowAtlasRemap {
		private final float sourceWidth;
		private final float sourceHeight;
		private final float destinationWidth;
		private final float destinationHeight;
		private final List<com.malaron.atlasstream.atlas.LowAtlasPacker.Placement> placements;

		private LowAtlasRemap(
			int sourceWidth,
			int sourceHeight,
			int destinationWidth,
			int destinationHeight,
			com.malaron.atlasstream.atlas.LowAtlasPacker.Layout layout
		) {
			this.sourceWidth = sourceWidth;
			this.sourceHeight = sourceHeight;
			this.destinationWidth = destinationWidth;
			this.destinationHeight = destinationHeight;
			this.placements = List.copyOf(layout.placements().values());
		}

		private boolean matches(int width, int height) {
			return Math.round(sourceWidth) == width && Math.round(sourceHeight) == height;
		}

		private float[] map(float u, float v) {
			return map(u, v, false);
		}

		private float[] map(float u, float v, boolean upperBound) {
			double x = u * sourceWidth;
			double y = v * sourceHeight;
			if (upperBound) {
				x = Math.nextDown(x);
				y = Math.nextDown(y);
			}
			for (com.malaron.atlasstream.atlas.LowAtlasPacker.Placement placement : placements) {
				var source = placement.sprite();
				double localX = x - source.sourceX();
				double localY = y - source.sourceY();
				if (localX < -source.paddingX() || localY < -source.paddingY()
						|| localX > source.width() + source.paddingX()
						|| localY > source.height() + source.paddingY()) {
					continue;
				}
				float[] mapped = new float[] {
					(float) ((placement.x() + placement.paddingX() + localX / placement.factor()) / destinationWidth),
					(float) ((placement.y() + placement.paddingY() + localY / placement.factor()) / destinationHeight)
				};
				return mapped;
			}
			return new float[] {u, v};
		}

		private Identifier placementAt(float u, float v) {
			return placementAt(u, v, false);
		}

		private Identifier placementAt(float u, float v, boolean upperBound) {
			double x = u * sourceWidth;
			double y = v * sourceHeight;
			if (upperBound) {
				x = Math.nextDown(x);
				y = Math.nextDown(y);
			}
			for (var placement : placements) {
				var source = placement.sprite();
				double localX = x - source.sourceX();
				double localY = y - source.sourceY();
				if (localX >= -source.paddingX() && localY >= -source.paddingY()
						&& localX <= source.width() + source.paddingX()
						&& localY <= source.height() + source.paddingY()) {
					return source.identifier();
				}
			}
			return null;
		}
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
		long thisBuildSerial = BUILD_SERIAL.incrementAndGet();
		activeBuildSerial = thisBuildSerial;
		setBuildPhase("Preparing texture atlas...");
		long buildGeneration;
		List<SpriteRegion> regions;
		Map<Identifier, NativeImage[]> spriteMipImages;
		int atlasWidth;
		int atlasHeight;
		int mipLevelCount;
		int targetResolution;
		boolean reducePbr;
		GpuTexture highResolutionTexture;
		synchronized (AtlasRuntimeState.class) {
			buildGeneration = generation;
		}
		boolean defer = deferActivationUntilIris;
		deferActivationUntilIris = false;
		try {
			TextureAtlasAccessor geometry = (TextureAtlasAccessor) atlas;
			highResolutionTexture = atlas.getTexture();
			atlasWidth = geometry.atlasStream$getWidth();
			atlasHeight = geometry.atlasStream$getHeight();
			mipLevelCount = geometry.atlasStream$getMipLevelCount();
			regions = snapshotRegions(atlas, atlasWidth, atlasHeight);
			spriteMipImages = snapshotSpriteMipImages(atlas);
			targetResolution = AtlasStreamClient.getConfig().targetResolution();
			// The PBR images only need the resource pack, so they can be built before Iris has its pipeline.
			reducePbr = enableDistancePreview && (SodiumTerrainIntegration.isIrisShaderPackInUse()
				|| (defer && SodiumTerrainIntegration.isIrisConfiguredForShaders()));
		} catch (RuntimeException exception) {
			BUILDING.set(false);
			throw exception;
		}

		SCREEN_OVERLAY_REMAP_LOGGED.set(false);
		CompletableFuture<?> buildFuture = CompletableFuture.supplyAsync(() -> {
			setBuildPhase("Building low-resolution atlas...");
			try {
				return createLowAtlas(
					resourceManager,
					spriteMipImages,
					regions,
					atlasWidth,
					atlasHeight,
					mipLevelCount,
					targetResolution,
					reducePbr,
					buildGeneration
				);
			} catch (IOException exception) {
				throw new java.util.concurrent.CompletionException(exception);
			}
		});
		activeBuildFuture = buildFuture;
		buildFuture.whenComplete((imageValue, failure) -> Minecraft.getInstance().execute(() -> activateOrPark(defer, () -> {
			try {
				LowAtlasImage image = (LowAtlasImage) imageValue;
				setBuildPhase("Activating texture streaming...");
				if (!ownsBuild(thisBuildSerial)) {
					return;
				}
				if (failure != null) {
					Throwable cause = failure.getCause() == null ? failure : failure.getCause();
					if (cause instanceof CancellationException || cause instanceof BuildCancelledException) {
						AtlasStream.LOGGER.info("Stopped stale low-resolution atlas build");
						return;
					}
					throw new IllegalStateException("Could not build the low-resolution block atlas", cause);
				}
				boolean releaseHighResolutionAtlas = enableDistancePreview;
				try (image) {
					boolean currentAtlas;
					synchronized (AtlasRuntimeState.class) {
						currentAtlas = generation == buildGeneration
							&& Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS) == atlas;
					}
					if (!currentAtlas) {
						AtlasStream.LOGGER.info("Discarding low-resolution atlas preview because the source atlas changed");
						errors.accept(net.minecraft.network.chat.Component.literal(
							"Low-atlas build was discarded because the source atlas changed. Retry after the resource reload."
						));
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
					SodiumTerrainIntegration.scheduleVisibleTerrainRebuild();
				}
				if (reloadBuildStartNanos != 0L) {
					AtlasStream.LOGGER.info("Reduced atlas build and activation took {} ms",
						(System.nanoTime() - reloadBuildStartNanos) / 1_000_000L);
					reloadBuildStartNanos = 0L;
				}
				feedback.accept(
					net.minecraft.network.chat.Component.literal(
						(enableDistancePreview
							? SodiumTerrainIntegration.isIrisShaderPackInUse()
								? (isNearAtlasActive()
									? "Distance-based LOD enabled with Iris; nearby terrain uses the near atlas where available. "
									: "Distance-based LOD enabled with Iris; the near atlas is unavailable, so terrain uses the reduced atlas. ")
								: "Streamed albedo LOD enabled. Nearby terrain uses the bounded high-resolution tile cache, and the low atlas is the fallback. "
							: "Separate low-resolution block atlas enabled. Run /atlasstream preview-high to switch back. ")
							+ "Low atlas is " + lowResolutionTexture.getWidth(0) + "x"
							+ lowResolutionTexture.getHeight(0)
							+ (releaseHighResolutionAtlas
								? originalAtlasReleaseConfirmed
									? ". The full block atlas was released. Reload resource packs to restore it."
									: ". The original atlas allocation could not be confirmed as released; check latest.log."
								: "")
							+ " (target " + AtlasStreamClient.getConfig().targetResolution() + "px)."
					)
				);
				if (enableDistancePreview && isNearAtlasActive() && !isNearAtlasWarm()) {
					setBuildPhase("Low res atlas ready; nearby high-resolution detail stream starting...");
				} else if (!enableDistancePreview) {
					setBuildPhase("Low-resolution atlas ready");
					clearBuildPhase();
				} else {
					setBuildPhase("Atlas ready");
					clearBuildPhase();
				}
			} catch (RuntimeException exception) {
				AtlasStream.LOGGER.error("Could not activate low-resolution atlas preview", exception);
				errors.accept(
					net.minecraft.network.chat.Component.literal("Low-atlas preview failed: " + exception.getMessage())
				);
				clearBuildPhase();
			} finally {
				if (ownsBuild(thisBuildSerial)) {
					reloadBuildRunning = false;
					BUILDING.set(false);
					activeBuildFuture = null;
				}
			}
		})));
	}

	private static boolean ownsBuild(long serial) {
		return serial == activeBuildSerial;
	}

	private static boolean isBuildCurrent(long buildGeneration) {
		return generation == buildGeneration && !Thread.currentThread().isInterrupted();
	}

	static void checkBuildCurrent(long buildGeneration) throws BuildCancelledException {
		if (!isBuildCurrent(buildGeneration)) {
			throw new BuildCancelledException();
		}
	}

	static final class BuildCancelledException extends RuntimeException {
	}

	private static boolean deferActivationUntilIris;
	private static Runnable parkedActivation;
	private static int parkedActivationTicks;

	private static boolean irisActivationReady() {
		return Minecraft.getInstance().level != null && SodiumTerrainIntegration.isIrisShaderPackInUse();
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
		if (slot < 0) {
			throw new IllegalStateException("Virtual texture cache has no lower-priority tile to replace");
		}
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
		return !lowAtlasOriginalLayout && view != null && view == lowResolutionTextureView;
	}

	public static GpuTextureView lowAtlasViewForFullSparseEntity(GpuTextureView view) {
		NearAtlas near = nearAtlas;
		if (near != null && near.isUsable() && view == near.albedoView()) {
			return lowResolutionTextureView;
		}
		return view;
	}

	public static net.minecraft.client.renderer.texture.UvMapping remapEntityBlockUv(
		net.minecraft.client.renderer.texture.UvMapping mapping
	) {
		if (mapping == null) {
			return null;
		}
		LowAtlasRemap remap = lowAtlasRemap;
		if (remap == null
				|| !(mapping instanceof TextureAtlasSprite sprite)
				|| !TextureAtlas.LOCATION_BLOCKS.equals(sprite.atlasLocation())
				|| lowAtlasOriginalLayout) {
			return mapping;
		}
		float[] start = remap.map(sprite.getU(0), sprite.getV(0));
		float[] end = remap.map(sprite.getU(1), sprite.getV(1), true);
		Identifier name = sprite.contents().name();
		if (name.getPath().startsWith("entity/conduit/")
				&& CONDUIT_REMAP_LOGGED.size() < 12
				&& CONDUIT_REMAP_LOGGED.add(name.toString())) {
			AtlasStream.LOGGER.info(
				"Conduit low remap: sprite={}, source=({},{})-({},{ }), mapped=({},{})-({},{ }), placement={}",
				name,
				sprite.getU(0), sprite.getV(0), sprite.getU(1), sprite.getV(1),
				start[0], start[1], end[0], end[1],
				remap.placementAt(sprite.getU(0), sprite.getV(0))
			);
		}
		return new RemappedUvMapping(start[0], start[1], end[0], end[1]);
	}

	private record RemappedUvMapping(float u0, float v0, float u1, float v1)
		implements net.minecraft.client.renderer.texture.UvMapping {
		@Override
		public float getU(float value) {
			return u0 + (u1 - u0) * value;
		}

		@Override
		public float getV(float value) {
			return v0 + (v1 - v0) * value;
		}
	}

	public static boolean isLowAtlasOriginalLayout() {
		return lowAtlasOriginalLayout;
	}

	public static GpuTextureView lowAtlasTextureForView(GpuTextureView view) {
		LowAtlasRemap remap = lowAtlasRemap;
		GpuTextureView low = lowResolutionTextureView;
		return remap != null && low != null && view != null ? low : view;
	}

	public static long virtualTextureGeneration() {
		VirtualTextureGpuPageTable pageTable = virtualTextureGpuPageTable;
		return pageTable == null ? Long.MIN_VALUE : pageTable.layout().atlasGeneration();
	}

	public static synchronized String describeItemSpriteResidency(TextureAtlasSprite sprite) {
		String label = sprite.contents().name() + " (" + sprite.atlasLocation() + ")";
		VirtualTextureGpuPageTable pageTable = virtualTextureGpuPageTable;
		VirtualTextureGpuCache cache = virtualTextureGpuCache;
		VirtualTextureLayout layout = virtualTextureLayout;
		if (!TextureAtlas.LOCATION_BLOCKS.equals(sprite.atlasLocation())
				|| pageTable == null || cache == null || layout == null) {
			return label + ": not using the block tile cache";
		}

		int levels = Math.min(pageTable.layout().mipLevelCount(), lowAtlasStreamedMipLevels);
		if (levels == 0) {
			return label + ": no streamed mip levels";
		}
		int[] resident = new int[levels];
		int[] total = new int[levels];
		var quad = new VirtualTextureRequestTracker.UvQuad(
			sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(),
			pageTable.layout().atlasGeneration(), 0.0
		);
		var cursor = VirtualTextureRequestTracker.cursor(quad, layout, levels);
		int examined = 0;
		while (!cursor.isComplete() && examined < 16384) {
			var key = cursor.next();
			total[key.mipLevel()]++;
			if (cache.isResident(key)) {
				resident[key.mipLevel()]++;
			}
			examined++;
		}
		StringBuilder result = new StringBuilder(label).append(": resident tiles");
		for (int level = 0; level < levels; level++) {
			result.append(" mip").append(level).append('=').append(resident[level]).append('/').append(total[level]);
		}
		if (!cursor.isComplete()) {
			result.append(" (truncated)");
		}
		return result.toString();
	}

	public static synchronized String describeVirtualTextureActivity() {
		VirtualTextureGpuCache cache = virtualTextureGpuCache;
		if (cache == null) {
			return "Virtual texture cache unavailable";
		}
		return "cache=" + cache.size() + " tiles, " + cache.usedBytes() / 1024 + "/"
			+ cache.capacityBytes() / 1024 + " KiB, evictions=" + cache.evictionCount()
			+ ", pending tiles=" + pendingVirtualTextureTiles.size()
			+ ", loading=" + loadingTiles.size()
			+ ", pending quads=" + (VIRTUAL_TEXTURE_REQUEST_TRACKER.pendingCount() + pendingVirtualTextureQuads.pendingCount())
			+ ", refresh counter=" + streamRerequestCounter;
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
			&& SodiumTerrainIntegration.isIrisShaderPackInUse();
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
				"AtlasStream near remap placeholder",
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
				|| SodiumTerrainIntegration.isIrisShaderPackInUse()) {
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

	public static void recordBlockModelMesh(net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh mesh) {
		TextureAtlas atlas = previewedAtlas;
		if (atlas == null || nearAtlas == null || !SodiumTerrainIntegration.isIrisShaderPackInUse()) {
			return;
		}

		var finder = ((net.fabricmc.fabric.api.client.renderer.v1.sprite.FabricTextureAtlas) atlas).spriteFinder();
		Set<net.minecraft.client.renderer.texture.TextureAtlasSprite> sprites = new java.util.HashSet<>();
		mesh.forEach(quad -> {
			if (quad.atlas() == net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas.BLOCK) {
				var sprite = finder.find(quad);
				if (sprite != null) {
					sprites.add(sprite);
				}
			}
		});
		for (var sprite : sprites) {
			recordSpriteUsage(sprite);
		}
	}

	public static synchronized boolean useNearAtlasForScreenEffect(TextureAtlasSprite sprite) {
		NearAtlas near = nearAtlas;
		if (near == null || !near.isUsable()
				|| !TextureAtlas.LOCATION_BLOCKS.equals(sprite.atlasLocation())) {
			return false;
		}
		near.touch(sprite);
		return near.isResident(sprite) && (!near.isCompact() || vanillaRemapAvailable);
	}

	public static synchronized boolean useNearAtlasForScreenEffect(
		Identifier atlasLocation, float u0, float v0, float u1, float v1
	) {
		TextureAtlas atlas = previewedAtlas;
		NearAtlas near = nearAtlas;
		if (atlas == null || near == null || !near.isUsable()
				|| !TextureAtlas.LOCATION_BLOCKS.equals(atlasLocation)) {
			return false;
		}
		float centerU = (u0 + u1) * 0.5F;
		float centerV = (v0 + v1) * 0.5F;
		for (TextureAtlasSprite sprite : ((TextureAtlasAccessor) atlas).atlasStream$getSprites()) {
			if (centerU >= sprite.getU0() && centerU < sprite.getU1()
					&& centerV >= sprite.getV0() && centerV < sprite.getV1()) {
				return useNearAtlasForScreenEffect(sprite);
			}
		}
		return false;
	}

	public static GpuTextureView screenEffectNearAtlasView() {
		NearAtlas near = nearAtlas;
		return screenEffectUsesNearAtlas && near != null && near.isUsable() ? near.albedoView() : null;
	}

	public static float[] screenEffectUv(
		Identifier atlasLocation, float u, float v, boolean upperBound, boolean useNear
	) {
		if (!useNear) {
			return remapLowAtlasUv(atlasLocation, u, v, upperBound);
		}
		TextureAtlas atlas = previewedAtlas;
		NearAtlas near = nearAtlas;
		if (atlas == null || near == null) {
			return new float[] {u, v};
		}
		float adjustedU = u;
		float adjustedV = v;
		if (upperBound) {
			adjustedU = Math.nextDown(u);
			adjustedV = Math.nextDown(v);
		}
		for (TextureAtlasSprite sprite : ((TextureAtlasAccessor) atlas).atlasStream$getSprites()) {
			if (adjustedU >= sprite.getU0() && adjustedU <= sprite.getU1()
					&& adjustedV >= sprite.getV0() && adjustedV <= sprite.getV1()) {
				return near.remapUv(sprite, adjustedU, adjustedV);
			}
		}
		return new float[] {adjustedU, adjustedV};
	}

	public static float[] screenEffectUv(TextureAtlasSprite sprite, float u, float v, boolean upperBound) {
		NearAtlas near = nearAtlas;
		if (near == null || !near.isResident(sprite)) {
			return remapLowAtlasUv(sprite.atlasLocation(), u, v, upperBound);
		}
		float adjustedU = upperBound ? Math.nextDown(u) : u;
		float adjustedV = upperBound ? Math.nextDown(v) : v;
		return near.remapUv(sprite, adjustedU, adjustedV);
	}

	public static void setScreenEffectUsesNearAtlas(boolean useNearAtlas) {
		screenEffectUsesNearAtlas = useNearAtlas;
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

	public static boolean isNearAtlasCompact() {
		NearAtlas near = nearAtlas;
		return near != null && near.isUsable() && near.isCompact();
	}

	public static NearAtlas activeNearAtlas() {
		NearAtlas near = nearAtlas;
		return near != null && near.isUsable() ? near : null;
	}

	public static GpuTextureView nearAtlasView() {
		NearAtlas near = nearAtlas;
		return near != null && near.isUsable() ? near.terrainAlbedoView() : null;
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
	private static final double STREAM_MOVEMENT_REREQUEST_BLOCKS = 16.0;
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
	private static double streamRequestCameraX = Double.NaN;
	private static double streamRequestCameraY;
	private static double streamRequestCameraZ;
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
			Thread thread = new Thread(runnable, "AtlasStream animated tiles");
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
			var request = pendingVirtualTextureTiles.pollRequest();
			if (request == null) {
				return;
			}
			VirtualTextureTileKey key = request.key();
			if (cache.noteDemand(key, request.distanceSquared()) || loadingTiles.containsKey(key)
					|| !cache.canAdmit(key, request.distanceSquared())) {
				continue;
			}
			AnimatedTile snapshot = snapshotAnimatedTile(builder, key);
			loadingTiles.put(key, new PreparedTile(snapshot, animatedTileExecutor.submit(
				() -> {
					List<NativeImage> images = buildTileImages(builder, key, cacheMipLevels);
					LodPerformanceStats.recordTileBuilt();
					return images;
				}
			), request.distanceSquared()));
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
				if (!cache.noteDemand(entry.getKey(), entry.getValue().distanceSquared())
						&& cache.upload(entry.getKey(), images, entry.getValue().distanceSquared()) >= 0) {
					if (!entry.getValue().snapshot().names().isEmpty()) {
						animatedTiles.put(entry.getKey(), entry.getValue().snapshot());
					}
					uploads++;
					LodPerformanceStats.recordTileUploaded();
				}
			} catch (Exception exception) {
				AtlasStream.LOGGER.error("Could not stream virtual texture tile {}", entry.getKey(), exception);
			} finally {
				if (images != null) {
					closeImages(images);
				}
			}
		}
	}

	private record AnimatedTile(List<String> names, FrameState[] frames) {
	}

	private record PreparedTile(
		AnimatedTile snapshot, java.util.concurrent.Future<List<NativeImage>> images, double distanceSquared
	) {
		private PreparedTile(AnimatedTile snapshot, java.util.concurrent.Future<List<NativeImage>> images) {
			this(snapshot, images, Double.POSITIVE_INFINITY);
		}
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
				AtlasStream.LOGGER.error("Could not update animated virtual texture tile {}", entry.getKey(), exception);
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
			virtualTextureGpuCache.nextDemandEpoch();
			requestedStreamSections.clear();
		}
		if (streamGeneration != generation) {
			// Requests recorded for an older atlas generation are discarded, so every section must be requested again.
			streamGeneration = generation;
			requestedStreamSections.clear();
		}
		com.malaron.atlasstream.lod.LodConfig config = AtlasStreamClient.getConfig();
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
		double movedX = camera.x - streamRequestCameraX;
		double movedY = camera.y - streamRequestCameraY;
		double movedZ = camera.z - streamRequestCameraZ;
		if (!Double.isFinite(streamRequestCameraX)
				|| movedX * movedX + movedY * movedY + movedZ * movedZ
					>= STREAM_MOVEMENT_REREQUEST_BLOCKS * STREAM_MOVEMENT_REREQUEST_BLOCKS) {
			requestedStreamSections.clear();
			streamRequestCameraX = camera.x;
			streamRequestCameraY = camera.y;
			streamRequestCameraZ = camera.z;
		}
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
			serviceBuildPhaseCompletion();
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
			pendingVirtualTextureQuads.offer(quad);
		}

		int plannedKeys = 0;
		while (plannedKeys < MAX_TILE_KEYS_PER_TICK) {
			if (activeVirtualTextureCursor == null) {
				VirtualTextureRequestTracker.UvQuad quad = pendingVirtualTextureQuads.poll(currentGeneration);
				if (quad == null) {
					break;
				}
				int streamedMips = Math.min(pageTable.layout().mipLevelCount(), lowAtlasStreamedMipLevels);
				if (streamedMips == 0) {
					continue;
				}
				activeVirtualTextureCursor = VirtualTextureRequestTracker.cursor(
					quad,
					layout,
					streamedMips
				);
				activeVirtualTextureDistanceSquared = quad.distanceSquared();
			}
			VirtualTextureTileKey key = activeVirtualTextureCursor.next();
			// A mip level is only sampled within a limited distance, and mips as coarse as the low atlas add nothing.
			// Requesting every level for every section would overflow the cache and evict the nearby tiles.
			double effectiveDistance = Math.sqrt(activeVirtualTextureDistanceSquared) - SECTION_RADIUS;
			int lowAtlasMip = lowAtlasStreamedMipLevels;
			boolean wanted = key.mipLevel() < lowAtlasMip
				&& effectiveDistance <= (STREAM_MIP0_BLOCKS << key.mipLevel());
			if (wanted && !animatedVirtualTextureTiles.contains(key)) {
				LodPerformanceStats.recordTileRequested();
				VirtualTextureGpuCache cache = virtualTextureGpuCache;
				if (cache != null) {
					if (cache.noteDemand(key, activeVirtualTextureDistanceSquared)) {
						LodPerformanceStats.recordTileResident();
					} else {
						PreparedTile loading = loadingTiles.get(key);
						if (loading != null) {
							LodPerformanceStats.recordTileLoading();
							if (activeVirtualTextureDistanceSquared < loading.distanceSquared()) {
								loadingTiles.put(key, new PreparedTile(
									loading.snapshot(), loading.images(), activeVirtualTextureDistanceSquared
								));
							}
						} else if (!cache.canAdmit(key, activeVirtualTextureDistanceSquared)) {
							LodPerformanceStats.recordTileAdmissionBlocked(
								Math.sqrt(activeVirtualTextureDistanceSquared),
								AtlasStreamClient.getConfig().highResolutionDistance()
							);
						} else if (pendingVirtualTextureTiles.offer(key, activeVirtualTextureDistanceSquared)) {
							LodPerformanceStats.recordTileQueued();
						} else {
							LodPerformanceStats.recordTileQueueRejected();
						}
					}
				}
			}
			plannedKeys++;
			if (activeVirtualTextureCursor.isComplete()) {
				activeVirtualTextureCursor = null;
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
		ItemStreamDiagnostics.sampleCacheActivity();
	}

	private static void serviceBuildPhaseCompletion() {
		NearAtlas near = nearAtlas;
		if (near != null && near.isUsable() && near.isWarm()) {
			clearBuildPhase();
		}
	}

	public static synchronized VirtualTextureCacheStatus virtualTextureCacheStatus() {
		VirtualTextureGpuCache cache = virtualTextureGpuCache;
		if (cache == null) {
			return new VirtualTextureCacheStatus(false, 0, 0, 0, 0, 0, 0, 0);
		}
		int pendingQuads = VIRTUAL_TEXTURE_REQUEST_TRACKER.pendingCount()
			+ pendingVirtualTextureQuads.pendingCount()
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
			throw new IllegalStateException("Run /atlasstream preview-low to build and enable the low-resolution atlas");
		}
		lowPreviewEnabled = true;
		distancePreviewEnabled = false;
	}

	public static void enableDistancePreview() {
		if (!hasLowAtlas()) {
			throw new IllegalStateException("Run /atlasstream preview-distance to build the low-resolution atlas first");
		}
		if (!atlasOwnsLowResolutionTexture && lowPreviewEnabled) {
			TextureAtlas atlas = previewedAtlas;
			AbstractTextureAccessor textureAccessor = (AbstractTextureAccessor) atlas;
			GpuTexture highResolutionTexture = textureAccessor.atlasStream$getTexture();
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
			cancelActiveBuild();
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
		return ((AbstractTextureAccessor) atlas).atlasStream$getTexture();
	}

	public static boolean isReducedPbrAtlas(Object pbrAtlas) {
		return pbrAtlas != null && reducedPbrAtlases.contains(pbrAtlas);
	}

	public static synchronized List<String> debugLines() {
		List<String> lines = new ArrayList<>();
		GpuTexture low = lowResolutionTexture;
		if (previewedAtlas == null || low == null) {
			lines.add("[AtlasStream] inactive");
			return lines;
		}
		String mode = distancePreviewEnabled
			? (SodiumTerrainIntegration.isIrisShaderPackInUse() ? "distance (Iris, reduced)" : "distance (streamed)")
			: "low preview";
		lines.add(
			"[AtlasStream] " + mode + " target "
				+ AtlasStreamClient.getConfig().targetResolution() + "px atlas "
				+ low.getWidth(0) + "x" + low.getHeight(0)
				+ (atlasOwnsLowResolutionTexture ? ", full atlas released" : ", full atlas resident")
		);
		if (!reducedPbrAtlases.isEmpty()) {
			lines.add("[AtlasStream] Iris PBR atlases reduced: " + reducedPbrAtlases.size() + "/2");
		}
		if (nearAtlas != null) {
			for (String line : nearAtlas.debugLines()) {
				lines.add("[AtlasStream] " + line);
			}
		}
		VirtualTextureCacheStatus status = virtualTextureCacheStatus();
		if (status.available() && !SodiumTerrainIntegration.isIrisShaderPackInUse()) {
			lines.add(
				"[AtlasStream] tile cache " + status.residentTiles() + " tiles, "
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

	public static GpuTextureView lowAtlasRemapView() {
		GpuTextureView fallback = reloadFallbackRemapView;
		GpuTextureView view = lowAtlasRemapTextureView != null ? lowAtlasRemapTextureView : fallback;
		return view != null ? view : nearRemapPlaceholder();
	}

	public static int lowAtlasRemapGlId() {
		GpuTexture texture = lowAtlasRemapTexture != null
			? lowAtlasRemapTexture
			: reloadFallbackRemapTexture;
		if (texture == null) {
			return nearPlaceholderGlId();
		}
		try {
			return (Integer) texture.getClass().getMethod("iris$getGlId").invoke(texture);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			return nearPlaceholderGlId();
		}
	}

	private static void replaceLowAtlasRemap(GpuTexture texture, GpuTextureView view) {
		GpuTexture oldTexture = lowAtlasRemapTexture;
		GpuTextureView oldView = lowAtlasRemapTextureView;
		lowAtlasRemapTexture = texture;
		lowAtlasRemapTextureView = view;
		if (oldView != null) {
			oldView.close();
		}
		if (oldTexture != null) {
			oldTexture.close();
		}
	}

	private static synchronized void retainLowAtlasFallback() {
		GpuTexture source = lowResolutionTexture;
		GpuTexture remapSource = lowAtlasRemapTexture;
		closeReloadFallback();
		reloadFallbackMapping = lowAtlasRemap;
		if (source == null) {
			return;
		}
		var device = RenderSystem.getDevice();
		try {
			reloadFallbackTexture = copyTexture("AtlasStream reload fallback", source);
			reloadFallbackView = device.createTextureView(reloadFallbackTexture);
			if (remapSource != null) {
				reloadFallbackRemapTexture = copyTexture("AtlasStream reload remap fallback", remapSource);
				reloadFallbackRemapView = device.createTextureView(reloadFallbackRemapTexture);
			}
		} catch (RuntimeException | Error exception) {
			AtlasStream.LOGGER.warn("Could not retain the previous low atlas during reload", exception);
			closeReloadFallback();
		}
	}

	private static GpuTexture copyTexture(String label, GpuTexture source) {
		var device = RenderSystem.getDevice();
		GpuTexture destination = device.createTexture(
			label,
			GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
			source.getFormat(),
			source.getWidth(0),
			source.getHeight(0),
			source.getDepthOrLayers(),
			source.getMipLevels()
		);
		try {
			var encoder = device.createCommandEncoder();
			for (int level = 0; level < source.getMipLevels(); level++) {
				encoder.copyTextureToTexture(
					source, destination, level,
					0, 0, 0, 0,
					source.getWidth(level), source.getHeight(level)
				);
			}
			encoder.submit();
			return destination;
		} catch (RuntimeException | Error exception) {
			destination.close();
			throw exception;
		}
	}

	private static synchronized void closeReloadFallback() {
		if (reloadFallbackRemapView != null) {
			reloadFallbackRemapView.close();
		}
		if (reloadFallbackRemapTexture != null) {
			reloadFallbackRemapTexture.close();
		}
		if (reloadFallbackView != null) {
			reloadFallbackView.close();
		}
		if (reloadFallbackTexture != null) {
			reloadFallbackTexture.close();
		}
		reloadFallbackRemapView = null;
		reloadFallbackRemapTexture = null;
		reloadFallbackView = null;
		reloadFallbackTexture = null;
		reloadFallbackMapping = null;
	}

	private static void closeLowAtlasRemap() {
		replaceLowAtlasRemap(null, null);
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
		int targetResolution = AtlasStreamClient.getConfig().targetResolution();
		TextureAtlasAccessor geometry = (TextureAtlasAccessor) atlas;
		boolean preservedLayoutFits = geometry.atlasStream$getWidth() <= 16384
			&& geometry.atlasStream$getHeight() <= 16384;
		if (preservedLayoutFits) {
			AtlasStream.LOGGER.info(
				"Keeping Iris block PBR atlases because the {}x{} atlas supports selective {}px reduction",
				geometry.atlasStream$getWidth(),
				geometry.atlasStream$getHeight(),
				targetResolution
			);
			return false;
		}
		return (reloadRebuildPending || (reloadBuildRunning && BUILDING.get()) || irisSwitchNeedsRebuild())
			&& TextureAtlas.LOCATION_BLOCKS.equals(atlas.location())
			&& SodiumTerrainIntegration.isIrisShaderPackInUse();
	}

	// Shaders were switched on while the non-Iris streamed preview was active: there is no near atlas or reduced PBR,
	// and the full atlas is gone, so the preview must be rebuilt through a resource reload.
	private static boolean irisSwitchNeedsRebuild() {
		return distancePreviewEnabled && atlasOwnsLowResolutionTexture && nearAtlas == null
			&& !nearAtlasUnavailable && SodiumTerrainIntegration.isIrisShaderPackInUse();
	}

	// The Iris build could not create a near atlas (for example the atlas exceeds the sparse texture limit), so
	// rebuilding again would not help.
	private static boolean nearAtlasUnavailable;

	// Shaders were switched off while the Iris preview was active: terrain needs the streamed (non-Iris) preview.
	private static boolean shadersSwitchedOffNeedsRebuild() {
		return distancePreviewEnabled && atlasOwnsLowResolutionTexture && nearAtlas != null
			&& !SodiumTerrainIntegration.isIrisShaderPackInUse();
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
		AtlasStream.LOGGER.info(
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
			&& (SodiumTerrainIntegration.isIrisShaderPackInUse()
				|| com.malaron.atlasstream.shader.VirtualTextureShaderPatcher.isPatchApplied());
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
			early = SodiumTerrainIntegration.isIrisConfiguredForShaders();
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
				|| (!early && !SodiumTerrainIntegration.isIrisShaderPackInUse()
					&& !com.malaron.atlasstream.shader.VirtualTextureShaderPatcher.isPatchApplied())
				|| distancePreviewEnabled) {
			AtlasStream.LOGGER.info("Not rebuilding the distance preview after the resource reload; run /atlasstream preview-distance");
			return;
		}
		AtlasStream.LOGGER.info("Rebuilding the distance preview after a resource reload");
		reloadBuildStartNanos = System.nanoTime();
		reloadBuildRunning = true;
		deferActivationUntilIris = early && !SodiumTerrainIntegration.isIrisShaderPackInUse();
		try {
			buildAndEnable(
				atlas,
				client.getResourceManager(),
				message -> AtlasStream.LOGGER.info("{}", message.getString()),
				message -> {
					AtlasStream.LOGGER.error("{}", message.getString());
					IrisPbrAtlasReducer.resetIrisHolders();
				},
				true
			);
		} catch (RuntimeException exception) {
			AtlasStream.LOGGER.error("Could not rebuild the distance preview after a resource reload", exception);
			reloadBuildRunning = false;
			IrisPbrAtlasReducer.resetIrisHolders();
		}
	}

	public static synchronized void beforeAtlasReload(TextureAtlas atlas) {
		if (TextureAtlas.LOCATION_BLOCKS.equals(atlas.location())) {
			cancelActiveBuild();
			if (previewedAtlas == atlas) {
				retainLowAtlasFallback();
				setBuildPhase("Resource pack changed; preparing AtlasStream...");
				reloadRebuildPending |= distancePreviewEnabled;
				irisSwitchReloadRequested = false;
				lowPreviewEnabled = false;
				distancePreviewEnabled = false;
				detachAtlasOwnedLowResolutionTexture();
			}
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
			if (launchRebuild && SodiumTerrainIntegration.isIrisConfiguredForShaders()) {
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
		for (TextureAtlasSprite sprite : ((TextureAtlasAccessor) atlas).atlasStream$getSprites()) {
			int width = sprite.contents().width();
			int height = sprite.contents().height();
			int paddingX = Math.round(sprite.getU0() * atlasWidth) - sprite.getX();
			int paddingY = Math.round(sprite.getV0() * atlasHeight) - sprite.getY();
			if (width <= 0 || height <= 0 || paddingX < 0 || paddingY < 0
					|| sprite.getX() < 0 || sprite.getY() < 0
					|| sprite.getX() + width + paddingX * 2 > atlasWidth
					|| sprite.getY() + height + paddingY * 2 > atlasHeight) {
				AtlasStream.LOGGER.warn("Skipping atlas preview sprite with invalid bounds: {}", sprite.contents().name());
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

	private static Map<Identifier, NativeImage[]> snapshotSpriteMipImages(TextureAtlas atlas) {
		Map<Identifier, NativeImage[]> result = new HashMap<>();
		for (TextureAtlasSprite sprite : ((TextureAtlasAccessor) atlas).atlasStream$getSprites()) {
			result.put(
				sprite.contents().name(),
				((SpriteContentsAccessor) sprite.contents()).atlasStream$getMipImages()
			);
		}
		return Map.copyOf(result);
	}

	private static LowAtlasImage createLowAtlas(
		ResourceManager resourceManager,
		Map<Identifier, NativeImage[]> spriteMipImages,
		List<SpriteRegion> regions,
		int sourceAtlasWidth,
		int sourceAtlasHeight,
		int sourceMipLevelCount,
		int targetResolution,
		boolean reducePbr,
		long buildGeneration
	) throws IOException {
		checkBuildCurrent(buildGeneration);
		if (sourceMipLevelCount <= 0) {
			throw new IOException("The source block atlas has no mip levels");
		}
		List<com.malaron.atlasstream.atlas.LowAtlasPacker.Sprite> packedSprites = regions.stream()
			.map(region -> new com.malaron.atlasstream.atlas.LowAtlasPacker.Sprite(
				region.identifier(), region.x(), region.y(), region.width(), region.height(),
				region.paddingX(), region.paddingY()))
			.toList();
		com.malaron.atlasstream.atlas.LowAtlasPacker.Layout originalLayout =
			com.malaron.atlasstream.atlas.LowAtlasPacker.preserveLayout(
				packedSprites, targetResolution, sourceAtlasWidth, sourceAtlasHeight
			);
		int lowMipLevelCount = Math.min(
			sourceMipLevelCount,
			Integer.numberOfTrailingZeros(targetResolution) + 1
		);
		boolean preserveOriginalLayout = originalLayout != null;
		boolean preserveOriginalMips = preserveOriginalLayout && regions.stream().allMatch(region -> {
			NativeImage[] images = spriteMipImages.get(region.identifier());
			return images != null && images.length >= lowMipLevelCount;
		});
		if (preserveOriginalLayout) {
			List<String> reductions = regions.stream()
				.filter(region -> Math.max(region.width(), region.height()) > targetResolution)
				.map(region -> region.identifier() + "=" + region.width() + "x" + region.height())
				.sorted()
				.toList();
			List<String> missingMips = regions.stream()
				.filter(region -> {
					NativeImage[] images = spriteMipImages.get(region.identifier());
					return images == null || images.length < lowMipLevelCount;
				})
				.map(region -> region.identifier().toString())
				.sorted()
				.toList();
			AtlasStream.LOGGER.info(
				"Using original-layout low atlas: selectivelyReducedSprites={} [{}], missingMipChains={} [{}]",
				reductions.size(),
				reductions.stream().limit(24).collect(java.util.stream.Collectors.joining(", ")),
				missingMips.size(),
				missingMips.stream().limit(24).collect(java.util.stream.Collectors.joining(", "))
			);
		}
		com.malaron.atlasstream.atlas.LowAtlasPacker.Layout layout = preserveOriginalLayout
			? originalLayout
			: com.malaron.atlasstream.atlas.LowAtlasPacker.pack(
				packedSprites, targetResolution, 16384
			);
		long imageBytes = 0;
		for (int level = 0; level < lowMipLevelCount; level++) {
			imageBytes += (long) Math.max(1, layout.width() >> level) * Math.max(1, layout.height() >> level) * 4;
		}
		if (imageBytes > MAX_PREVIEW_BYTES) {
			throw new IOException("Repacked low-resolution atlas exceeds the 2 GiB CPU safety limit");
		}

		List<NativeImage> mipLevels = new ArrayList<>(lowMipLevelCount);
		List<AnimatedSpriteData> animatedSpriteData = new ArrayList<>();
		long[] totalImageBytes = {imageBytes};
		boolean cachePbr = reducePbr && !preserveOriginalLayout;
		String cacheKey = ReducedAtlasCache.fingerprint(
			resourceManager, regions, sourceAtlasWidth, sourceAtlasHeight,
			sourceMipLevelCount, targetResolution, cachePbr,
			AtlasStreamClient.getConfig().debugTintLowResolution()
		);
		ReducedAtlasCache.Entry cached = cacheKey == null
			? null
			: ReducedAtlasCache.load(
				cacheKey, layout.width(), layout.height(), lowMipLevelCount, cachePbr
			);
		if (cached != null) {
			List<AnimatedSpriteData> cachedAnimations = new ArrayList<>();
			try {
				rebuildCachedAnimations(
					resourceManager,
					regions,
					layout,
					lowMipLevelCount,
					totalImageBytes,
					cachedAnimations,
					buildGeneration
				);
				NativeImage remap = createLowAtlasRemap(
					layout, sourceAtlasWidth, sourceAtlasHeight, sourceMipLevelCount
				);
				AtlasStream.LOGGER.info(
					"Loaded repacked low atlas from disk cache: {}x{} with {} mips",
					layout.width(), layout.height(), lowMipLevelCount
				);
				return new LowAtlasImage(
					cached.albedo(), layout, remap, cachedAnimations,
					cachePbr ? new IrisPbrAtlasReducer.Images(
						cached.normal(), cached.specular()
					) : null,
					preserveOriginalLayout,
					layout.placements().values().stream()
						.allMatch(placement -> placement.factor() == 1)
				);
			} catch (IOException | RuntimeException exception) {
				cached.close();
				closeAnimatedSprites(cachedAnimations);
				throw exception;
			}
		}
		CompletableFuture<IrisPbrAtlasReducer.Images> pbrFuture = null;
		NativeImage atlas = new NativeImage(layout.width(), layout.height(), true);
		mipLevels.add(atlas);
		try {
			if (preserveOriginalLayout) {
				for (int level = 1; level < lowMipLevelCount; level++) {
					mipLevels.add(new NativeImage(
						Math.max(1, layout.width() >> level),
						Math.max(1, layout.height() >> level),
						true
					));
				}
				for (SpriteRegion region : regions) {
					checkBuildCurrent(buildGeneration);
					com.malaron.atlasstream.atlas.LowAtlasPacker.Placement placement =
						layout.placements().get(region.identifier());
					if (placement != null && placement.factor() == 1
							&& spriteMipImages.get(region.identifier()) != null) {
						writeOriginalSpriteMips(
							mipLevels, spriteMipImages.get(region.identifier()), region
						);
					}
				}
			}
			if (reducePbr && !preserveOriginalLayout) {
				pbrFuture = CompletableFuture.supplyAsync(() -> {
					try {
						return IrisPbrAtlasReducer.buildRepacked(
							resourceManager, regions, layout, lowMipLevelCount, buildGeneration
						);
					} catch (IOException exception) {
						throw new java.io.UncheckedIOException(exception);
					}
				});
			}
			Map<Identifier, SpriteRegion> regionsById = regions.stream()
				.collect(java.util.stream.Collectors.toMap(SpriteRegion::identifier, java.util.function.Function.identity()));
			AtomicInteger loadedSprites = new AtomicInteger();
			AtomicInteger skippedSprites = new AtomicInteger();
			regions.parallelStream().forEach(region -> {
				checkBuildCurrent(buildGeneration);
				com.malaron.atlasstream.atlas.LowAtlasPacker.Placement placement = layout.placements().get(region.identifier());
				if (placement == null) {
					return;
				}
				try {
					NativeImage[] decodedMips = spriteMipImages.get(region.identifier());
					if (!region.animated() && decodedMips != null && decodedMips.length > 0) {
						if (preserveOriginalLayout && placement.factor() != 1) {
							writeRepackedSpriteMips(mipLevels, decodedMips, region, placement);
						} else if (!preserveOriginalLayout) {
							writeRepackedSprite(atlas, decodedMips[0], region, placement);
						}
						loadedSprites.incrementAndGet();
						return;
					}
					Identifier textureId = Identifier.fromNamespaceAndPath(
						region.identifier().getNamespace(), "textures/" + region.identifier().getPath() + ".png"
					);
					Resource resource = resourceManager.getResource(textureId)
						.orElseThrow(() -> new IOException("Missing texture resource " + textureId));
					BufferedImage source;
					try (InputStream input = resource.open()) {
						source = ImageIO.read(input);
					}
					if (source == null || source.getWidth() < region.width() || source.getHeight() < region.height()) {
						throw new IOException("Texture dimensions do not match stitched sprite bounds");
					}
					try {
						if (!preserveOriginalLayout) {
							writeRepackedSprite(atlas, source, region, placement);
						} else if (placement.factor() != 1) {
							writeRepackedSpriteMips(
								mipLevels, source, region, placement
							);
						}
						if (region.animated()) {
							SpriteRegion destination = destinationRegion(region, placement);
							AnimatedSpriteData animation = createAnimatedSpriteData(
								resource, source, region, destination, placement.factor(),
								lowMipLevelCount, totalImageBytes
							);
							if (animation != null) {
								synchronized (animatedSpriteData) {
									animatedSpriteData.add(animation);
								}
							}
						}
					} finally {
						source.flush();
					}
					loadedSprites.incrementAndGet();
				} catch (IOException | RuntimeException exception) {
					skippedSprites.incrementAndGet();
					AtlasStream.LOGGER.warn("Could not build low-atlas sprite {}: {}", region.identifier(), exception.getMessage());
				}
			});
			List<SpriteRegion> lowRegions = layout.placements().values().stream()
				.map(placement -> destinationRegion(regionsById.get(placement.sprite().identifier()), placement))
				.toList();
			if (!preserveOriginalLayout) {
				for (int level = 1; level < lowMipLevelCount; level++) {
					mipLevels.add(createMipLevel(mipLevels.get(level - 1), lowRegions, level));
				}
			}
			if (AtlasStreamClient.getConfig().debugTintLowResolution()) {
				mipLevels.forEach(AtlasRuntimeState::tintMagenta);
			}
			IrisPbrAtlasReducer.Images pbrImages = null;
			if (pbrFuture != null) {
				try {
					pbrImages = pbrFuture.join();
				} catch (java.util.concurrent.CompletionException exception) {
					if (exception.getCause() instanceof BuildCancelledException cancelled) {
						throw cancelled;
					}
					AtlasStream.LOGGER.error(
						"Could not reduce Iris PBR maps; they stay full resolution",
						exception.getCause()
					);
				} catch (RuntimeException exception) {
					if (exception instanceof BuildCancelledException cancelled) {
						throw cancelled;
					}
					AtlasStream.LOGGER.error("Could not reduce Iris PBR maps; they stay full resolution", exception);
				}
			}
			NativeImage remap = createLowAtlasRemap(layout, sourceAtlasWidth, sourceAtlasHeight, sourceMipLevelCount);
			AtlasStream.LOGGER.info(
				"{} low atlas at target {}px: {} sprites into {}x{} with {} mips ({} skipped); no pixels were upscaled",
				preserveOriginalLayout ? "Preserved original-layout" : "Repacked",
				targetResolution, loadedSprites.get(), layout.width(), layout.height(), lowMipLevelCount,
				skippedSprites.get()
			);
			if (cacheKey != null) {
				checkBuildCurrent(buildGeneration);
				ReducedAtlasCache.store(cacheKey, mipLevels, pbrImages);
			}
			return new LowAtlasImage(
				mipLevels,
				layout,
				remap,
				animatedSpriteData,
				pbrImages,
				preserveOriginalLayout,
				layout.placements().values().stream().allMatch(placement -> placement.factor() == 1)
			);
		} catch (RuntimeException | Error exception) {
			mipLevels.forEach(NativeImage::close);
			closeAnimatedSprites(animatedSpriteData);
			if (pbrFuture != null) {
				pbrFuture.cancel(true);
			}

			throw exception;
		}
	}

	private static void rebuildCachedAnimations(
		ResourceManager resourceManager,
		List<SpriteRegion> regions,
		com.malaron.atlasstream.atlas.LowAtlasPacker.Layout layout,
		int mipLevelCount,
		long[] totalImageBytes,
		List<AnimatedSpriteData> animations,
		long buildGeneration
	) throws IOException {
		for (SpriteRegion region : regions) {
			checkBuildCurrent(buildGeneration);
			if (!region.animated()) {
				continue;
			}
			var placement = layout.placements().get(region.identifier());
			if (placement == null) {
				continue;
			}
			Identifier textureId = Identifier.fromNamespaceAndPath(
				region.identifier().getNamespace(),
				"textures/" + region.identifier().getPath() + ".png"
			);
			Resource resource = resourceManager.getResource(textureId).orElse(null);
			if (resource == null) {
				continue;
			}
			try (InputStream input = resource.open()) {
				BufferedImage source = ImageIO.read(input);
				if (source == null) {
					throw new IOException("Texture resource is not a readable image");
				}
				try {
					AnimatedSpriteData animation = createAnimatedSpriteData(
						resource,
						source,
						region,
						destinationRegion(region, placement),
						placement.factor(),
						mipLevelCount,
						totalImageBytes
					);
					if (animation != null) {
						animations.add(animation);
					}
				} finally {
					source.flush();
				}
			}
		}
	}

	private static AnimatedSpriteData createAnimatedSpriteData(
		Resource resource,
		BufferedImage source,
		SpriteRegion sourceRegion,
		SpriteRegion destinationRegion,
		int downscaleFactor,
		int mipLevelCount,
		long[] totalImageBytes
	) throws IOException {
		long initialImageBytes = totalImageBytes[0];
		AnimationMetadataSection metadata = resource.metadata()
			.getSection(AnimationMetadataSection.TYPE)
			.orElse(null);
		if (metadata == null) {
			AtlasStream.LOGGER.warn("Animated sprite {} has no animation metadata; leaving its low atlas frame static", sourceRegion.identifier());
			return null;
		}
		FrameSize frameSize = metadata.calculateFrameSize(source.getWidth(), source.getHeight());
		int frameWidth = frameSize.width();
		int frameHeight = frameSize.height();
		if (frameWidth <= 0 || frameHeight <= 0
				|| frameWidth != sourceRegion.width() || frameHeight != sourceRegion.height()
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
		long validMetadataFrameCount = sourceFrames.stream()
			.filter(frame -> frame.index() >= 0 && frame.index() < frameCount
				&& frame.timeOr(metadata.defaultFrameTime()) > 0)
			.count();
		List<AnimationFrame> validFrames = validAnimationFrames(
			sourceFrames, frameCount, metadata.defaultFrameTime()
		);
		if (validMetadataFrameCount != sourceFrames.size()) {
			AtlasStream.LOGGER.warn(
				"Atlas animation {} contains {} invalid frame entries; using the {} valid entries",
				sourceRegion.identifier(), sourceFrames.size() - validMetadataFrameCount, validMetadataFrameCount
			);
		}
		if (validMetadataFrameCount == 0) {
			AtlasStream.LOGGER.warn(
				"Atlas animation {} has no valid metadata frames; using sequential frames",
				sourceRegion.identifier()
			);
		}
		List<AnimationStep> steps = new ArrayList<>(validFrames.size());
		Map<Integer, List<NativeImage>> imagesByFrame = new HashMap<>();
		try {
			for (AnimationFrame frame : validFrames) {
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
							sourceRegion,
							downscaleFactor,
							mipLevelCount,
							totalImageBytes
						)
					);
				}
			}
			return new AnimatedSpriteData(destinationRegion, steps, imagesByFrame, metadata.interpolatedFrames());
		} catch (IOException | RuntimeException | Error exception) {
			closeFrameImages(imagesByFrame);
			totalImageBytes[0] = initialImageBytes;
			throw exception;
		}
	}

	static List<AnimationFrame> validAnimationFrames(
		List<AnimationFrame> sourceFrames, int frameCount, int defaultFrameTime
	) {
		List<AnimationFrame> valid = sourceFrames.stream()
			.filter(frame -> frame.index() >= 0 && frame.index() < frameCount
				&& frame.timeOr(defaultFrameTime) > 0)
			.toList();
		if (!valid.isEmpty()) {
			return valid;
		}
		return java.util.stream.IntStream.range(0, frameCount)
			.mapToObj(AnimationFrame::new)
			.toList();
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
		int paddedWidth = Math.ceilDiv(region.width(), downscaleFactor)
			+ Math.ceilDiv(region.paddingX(), downscaleFactor) * 2;
		int paddedHeight = Math.ceilDiv(region.height(), downscaleFactor)
			+ Math.ceilDiv(region.paddingY(), downscaleFactor) * 2;
		int lowPaddingX = Math.ceilDiv(region.paddingX(), downscaleFactor);
		int lowPaddingY = Math.ceilDiv(region.paddingY(), downscaleFactor);
		try {
			for (int level = 0; level < mipLevelCount; level++) {
				int divisor = 1 << level;
				int width = Math.max(1, Math.ceilDiv(paddedWidth, divisor));
				int height = Math.max(1, Math.ceilDiv(paddedHeight, divisor));
				long levelBytes = (long) width * height * 4;
				totalImageBytes[0] += levelBytes;
				if (totalImageBytes[0] > MAX_PREVIEW_BYTES) {
					throw new IOException("Animated low-atlas frames would exceed the 2 GiB CPU image safety limit");
				}
				NativeImage mipLevel = new NativeImage(width, height, true);
				mipLevels.add(mipLevel);
				int sampleScale = downscaleFactor * divisor;
				for (int y = 0; y < height; y++) {
					int sampleY = frameY + (y - lowPaddingY) * sampleScale;
					for (int x = 0; x < width; x++) {
						int sampleX = frameX + (x - lowPaddingX) * sampleScale;
						mipLevel.setPixel(
							x,
							y,
							averageFramePixels(source, sampleX, sampleY, frameX, frameY, frameWidth, frameHeight, sampleScale)
						);
					}
				}
				if (isTiledPortalTexture(region)) {
					SpriteRegion lowRegion = new SpriteRegion(
						region.identifier(), region.paddingX() / downscaleFactor,
						region.paddingY() / downscaleFactor,
						Math.ceilDiv(region.width(), downscaleFactor),
						Math.ceilDiv(region.height(), downscaleFactor),
						Math.ceilDiv(region.paddingX(), downscaleFactor),
						Math.ceilDiv(region.paddingY(), downscaleFactor),
						region.animated()
					);
					int mipPaddingX = lowRegion.paddingX() >> level;
					int mipPaddingY = lowRegion.paddingY() >> level;
					makeSpriteEdgesContinuous(
						mipLevel,
						mipPaddingX,
						mipPaddingY,
						Math.max(1, ceilDiv(lowRegion.width(), divisor)),
						Math.max(1, ceilDiv(lowRegion.height(), divisor)),
						mipPaddingX,
						mipPaddingY
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

	private static SpriteRegion destinationRegion(
		SpriteRegion source, com.malaron.atlasstream.atlas.LowAtlasPacker.Placement placement
	) {
		return new SpriteRegion(
			source.identifier(),
			placement.x() + placement.paddingX(),
			placement.y() + placement.paddingY(),
			placement.width(),
			placement.height(),
			placement.paddingX(),
			placement.paddingY(),
			source.animated()
		);
	}

	private static void writeRepackedSprite(
		NativeImage atlas,
		BufferedImage source,
		SpriteRegion region,
		com.malaron.atlasstream.atlas.LowAtlasPacker.Placement placement
	) {
		int factor = placement.factor();
		for (int destinationY = 0; destinationY < placement.paddedHeight(); destinationY++) {
			int sourceY = (destinationY - placement.paddingY()) * factor;
			for (int destinationX = 0; destinationX < placement.paddedWidth(); destinationX++) {
				int sourceX = (destinationX - placement.paddingX()) * factor;
				atlas.setPixel(
					placement.x() + destinationX,
					placement.y() + destinationY,
					averageImagePixels(source, sourceX, sourceY, region.width(), region.height(), factor)
				);
			}
		}
	}

	private static void writeRepackedSprite(
		NativeImage atlas,
		NativeImage source,
		SpriteRegion region,
		com.malaron.atlasstream.atlas.LowAtlasPacker.Placement placement
	) {
		int factor = placement.factor();
		for (int destinationY = 0; destinationY < placement.paddedHeight(); destinationY++) {
			int sourceY = (destinationY - placement.paddingY()) * factor;
			for (int destinationX = 0; destinationX < placement.paddedWidth(); destinationX++) {
				int sourceX = (destinationX - placement.paddingX()) * factor;
				atlas.setPixel(
					placement.x() + destinationX,
					placement.y() + destinationY,
					averageImagePixels(source, sourceX, sourceY, region.width(), region.height(), factor)
				);
			}
		}
	}

	private static void writeOriginalSpriteMips(
		List<NativeImage> destinationMips,
		NativeImage[] sourceMips,
		SpriteRegion region
	) {
		int columns = Math.max(1, sourceMips[0].getWidth() / Math.max(1, region.width()));
		int rows = Math.max(1, sourceMips[0].getHeight() / Math.max(1, region.height()));
		for (int level = 0; level < destinationMips.size(); level++) {
			NativeImage source = sourceMips[level];
			int sourceWidth = Math.max(1, source.getWidth() / columns);
			int sourceHeight = Math.max(1, source.getHeight() / rows);
			int paddedWidth = Math.max(
				sourceWidth,
				Math.max(1, (region.width() + region.paddingX() * 2) >> level)
			);
			int paddedHeight = Math.max(
				sourceHeight,
				Math.max(1, (region.height() + region.paddingY() * 2) >> level)
			);
			int sourceOffsetX = (paddedWidth - sourceWidth) / 2;
			int sourceOffsetY = (paddedHeight - sourceHeight) / 2;
			int destinationX = (region.x() - region.paddingX()) >> level;
			int destinationY = (region.y() - region.paddingY()) >> level;
			NativeImage destination = destinationMips.get(level);
			for (int y = 0; y < paddedHeight && destinationY + y < destination.getHeight(); y++) {
				int sourceY = Math.clamp(y - sourceOffsetY, 0, sourceHeight - 1);
				for (int x = 0; x < paddedWidth && destinationX + x < destination.getWidth(); x++) {
					int sourceX = Math.clamp(x - sourceOffsetX, 0, sourceWidth - 1);
					destination.setPixel(
						destinationX + x,
						destinationY + y,
						source.getPixel(sourceX, sourceY)
					);
				}
			}
		}
	}

	private static void writeRepackedSpriteMips(
		List<NativeImage> destinationMips,
		BufferedImage source,
		SpriteRegion region,
		com.malaron.atlasstream.atlas.LowAtlasPacker.Placement placement
	) {
		for (int level = 0; level < destinationMips.size(); level++) {
			NativeImage destination = destinationMips.get(level);
			int mipDivisor = 1 << level;
			int sampleFactor = placement.factor() * mipDivisor;
			int width = Math.max(1, Math.ceilDiv(placement.width(), mipDivisor));
			int height = Math.max(1, Math.ceilDiv(placement.height(), mipDivisor));
			int paddedWidth = Math.max(
				width,
				Math.ceilDiv(placement.paddedWidth(), mipDivisor)
			);
			int paddedHeight = Math.max(
				height,
				Math.ceilDiv(placement.paddedHeight(), mipDivisor)
			);
			int originX = (placement.x() >> level);
			int originY = (placement.y() >> level);
			for (int y = 0; y < paddedHeight; y++) {
				int sourceY = (y - (placement.paddingY() >> level)) * sampleFactor;
				for (int x = 0; x < paddedWidth; x++) {
					int sourceX = (x - (placement.paddingX() >> level)) * sampleFactor;
					if (originX + x < destination.getWidth()
							&& originY + y < destination.getHeight()) {
						destination.setPixel(
							originX + x,
							originY + y,
							averageImagePixels(
								source,
								sourceX,
								sourceY,
								region.width(),
								region.height(),
								sampleFactor
							)
						);
					}
				}
			}
		}
	}

	private static void writeRepackedSpriteMips(
		List<NativeImage> destinationMips,
		NativeImage[] sourceMips,
		SpriteRegion region,
		com.malaron.atlasstream.atlas.LowAtlasPacker.Placement placement
	) {
		for (int level = 0; level < destinationMips.size(); level++) {
			int requestedFactor = placement.factor() << level;
			int sourceLevel = Math.min(
				Integer.numberOfTrailingZeros(requestedFactor),
				sourceMips.length - 1
			);
			NativeImage source = sourceMips[sourceLevel];
			int residualFactor = Math.max(1, requestedFactor >> sourceLevel);
			int mipDivisor = 1 << level;
			int paddedWidth = Math.max(
				Math.max(1, Math.ceilDiv(placement.width(), mipDivisor)),
				Math.ceilDiv(placement.paddedWidth(), mipDivisor)
			);
			int paddedHeight = Math.max(
				Math.max(1, Math.ceilDiv(placement.height(), mipDivisor)),
				Math.ceilDiv(placement.paddedHeight(), mipDivisor)
			);
			int paddingX = placement.paddingX() >> level;
			int paddingY = placement.paddingY() >> level;
			int originX = placement.x() >> level;
			int originY = placement.y() >> level;
			NativeImage destination = destinationMips.get(level);
			for (int y = 0; y < paddedHeight; y++) {
				int sourceY = (y - paddingY) * residualFactor;
				for (int x = 0; x < paddedWidth; x++) {
					int sourceX = (x - paddingX) * residualFactor;
					destination.setPixel(
						originX + x,
						originY + y,
						averageImagePixels(
							source,
							sourceX,
							sourceY,
							Math.max(1, source.getWidth()),
							Math.max(1, source.getHeight()),
							residualFactor
						)
					);
				}
			}
		}
	}

	private static int averageImagePixels(
		BufferedImage source, int x, int y, int width, int height, int factor
	) {
		long alphaSum = 0;
		long redSum = 0;
		long greenSum = 0;
		long blueSum = 0;
		int count = 0;
		for (int sampleY = y; sampleY < y + factor; sampleY++) {
			int clampedY = Math.clamp(sampleY, 0, height - 1);
			for (int sampleX = x; sampleX < x + factor; sampleX++) {
				int argb = source.getRGB(Math.clamp(sampleX, 0, width - 1), clampedY);
				int alpha = argb >>> 24;
				alphaSum += alpha;
				redSum += (argb >>> 16 & 0xFF) * (long) alpha;
				greenSum += (argb >>> 8 & 0xFF) * (long) alpha;
				blueSum += (argb & 0xFF) * (long) alpha;
				count++;
			}
		}
		if (alphaSum == 0) {
			return 0;
		}
		return ((int) ((alphaSum + count / 2) / count) << 24)
			| ((int) ((redSum + alphaSum / 2) / alphaSum) << 16)
			| ((int) ((greenSum + alphaSum / 2) / alphaSum) << 8)
			| (int) ((blueSum + alphaSum / 2) / alphaSum);
	}

	private static int averageImagePixels(
		NativeImage source, int x, int y, int width, int height, int factor
	) {
		long alphaSum = 0;
		long redSum = 0;
		long greenSum = 0;
		long blueSum = 0;
		int count = 0;
		for (int sampleY = y; sampleY < y + factor; sampleY++) {
			int clampedY = Math.clamp(sampleY, 0, height - 1);
			for (int sampleX = x; sampleX < x + factor; sampleX++) {
				int argb = source.getPixel(Math.clamp(sampleX, 0, width - 1), clampedY);
				int alpha = argb >>> 24;
				alphaSum += alpha;
				redSum += (argb >>> 16 & 0xFF) * (long) alpha;
				greenSum += (argb >>> 8 & 0xFF) * (long) alpha;
				blueSum += (argb & 0xFF) * (long) alpha;
				count++;
			}
		}
		if (alphaSum == 0) {
			return 0;
		}
		return ((int) ((alphaSum + count / 2) / count) << 24)
			| ((int) ((redSum + alphaSum / 2) / alphaSum) << 16)
			| ((int) ((greenSum + alphaSum / 2) / alphaSum) << 8)
			| (int) ((blueSum + alphaSum / 2) / alphaSum);
	}

	private static NativeImage createLowAtlasRemap(
		com.malaron.atlasstream.atlas.LowAtlasPacker.Layout layout,
		int sourceWidth,
		int sourceHeight,
		int mipLevelCount
	) {
		int cell = 1 << Math.max(0, mipLevelCount - 1);
		int cellsX = Math.ceilDiv(sourceWidth, cell);
		int cellsY = Math.ceilDiv(sourceHeight, cell);
		NativeImage remap = new NativeImage(cellsX * 3, cellsY + 1, true);
		for (com.malaron.atlasstream.atlas.LowAtlasPacker.Placement placement : layout.placements().values()) {
			int sourceX = placement.sprite().sourceX();
			int sourceY = placement.sprite().sourceY();
			int sourceEndX = placement.sprite().sourceX()
				+ placement.sprite().width() + placement.sprite().paddingX();
			int sourceEndY = placement.sprite().sourceY()
				+ placement.sprite().height() + placement.sprite().paddingY();
			for (int cellY = Math.floorDiv(sourceY, cell); cellY <= Math.floorDiv(sourceEndY - 1, cell); cellY++) {
				for (int cellX = Math.floorDiv(sourceX, cell); cellX <= Math.floorDiv(sourceEndX - 1, cell); cellX++) {
					writeRemapPixel(
						remap,
						cellX * 3,
						cellY,
						packBytes(sourceX & 0xFF, sourceX >>> 8, sourceY & 0xFF, sourceY >>> 8)
					);
					writeRemapPixel(
						remap,
						cellX * 3 + 1,
						cellY,
						packBytes(
							(placement.x() + placement.paddingX()) & 0xFF,
							(placement.x() + placement.paddingX()) >>> 8,
							(placement.y() + placement.paddingY()) & 0xFF,
							(placement.y() + placement.paddingY()) >>> 8
						)
					);
					writeRemapPixel(
						remap,
						cellX * 3 + 2,
						cellY,
						packBytes(Integer.numberOfTrailingZeros(placement.factor()) + 1, 0, 0, 1)
					);
				}
			}
		}
		int headerY = cellsY;
		writeRemapPixel(remap, 0, headerY, packBytes(
			(layout.width() - 1) & 0xFF, (layout.width() - 1) >>> 8,
			(layout.height() - 1) & 0xFF, (layout.height() - 1) >>> 8
		));
		writeRemapPixel(remap, 1, headerY, packBytes(
			(sourceWidth - 1) & 0xFF, (sourceWidth - 1) >>> 8,
			(sourceHeight - 1) & 0xFF, (sourceHeight - 1) >>> 8
		));
		writeRemapPixel(remap, 2, headerY, packBytes(cell & 0xFF, cell >>> 8, 0, 1));
		return remap;
	}

	static void writeRemapPixel(NativeImage image, int x, int y, int packedRgba) {
		image.setPixelABGR(x, y, packedRgba);
	}

	private static int packBytes(int r, int g, int b, int a) {
		return (a & 0xFF) << 24 | (b & 0xFF) << 16 | (g & 0xFF) << 8 | (r & 0xFF);
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
		if (image.isOriginalLayout() && SodiumTerrainIntegration.isIrisShaderPackInUse()) {
			activateOriginalIrisAtlas(atlas, highResolutionTexture);
			return;
		}
		var device = RenderSystem.getDevice();
		closeVirtualTextureGpuPageTable();
		GpuTexture texture = device.createTexture(
			"AtlasStream low-resolution block atlas",
			GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING
				| GpuTexture.USAGE_RENDER_ATTACHMENT,
			highResolutionTexture.getFormat(),
			image.layout().width(),
			image.layout().height(),
			highResolutionTexture.getDepthOrLayers(),
			image.mipLevels().size()
		);
		GpuTextureView textureView = null;
		GpuTexture remapTexture = device.createTexture(
			"AtlasStream low-atlas UV remap",
			GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
			com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM,
			image.remap().getWidth(),
			image.remap().getHeight(),
			1,
			1
		);
		GpuTextureView remapView = null;
		VirtualTextureGpuPageTable pageTable = null;
		VirtualTextureGpuCache tileCache = null;
		VirtualTextureMetadataTexture metadataTexture = null;
		boolean initialized = false;
		try {
			textureView = device.createTextureView(texture);
			remapView = device.createTextureView(remapTexture);
			var encoder = device.createCommandEncoder();
			for (int level = 0; level < image.mipLevels().size(); level++) {
				encoder.writeToTexture(texture, image.mipLevels().get(level), level, 0, 0, 0);
			}
			encoder.writeToTexture(remapTexture, image.remap(), 0, 0, 0, 0);
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
				AtlasStreamClient.getConfig().highResolutionCacheMiB(),
				1024L * 1024
			);
			long atlasBudgetBytes = estimateTextureBytes(atlasWidth, atlasHeight, atlasMipLevels) / 4;
			long minimumCacheBytes = 0;
			for (int mip = 0; mip < cacheMipLevels; mip++) {
				long mipSize = Math.max(1, VIRTUAL_TILE_SIZE >> mip);
				minimumCacheBytes = Math.addExact(minimumCacheBytes, mipSize * mipSize * 4);
			}
			// Iris sampling is not enabled yet, but exercise its request/build/upload path with a bounded fixed cache.
			// This tier remains intentionally small until all three PBR surfaces use one atomic residency lifecycle.
			long cacheBudgetBytes = SodiumTerrainIntegration.isIrisShaderPackInUse()
				? Math.max(minimumCacheBytes, IRIS_VIRTUAL_CACHE_BUDGET_BYTES)
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
			lowAtlasRemap = new LowAtlasRemap(
				atlasWidth, atlasHeight, image.layout().width(), image.layout().height(), image.layout()
			);
			lowAtlasStreamedMipLevels = image.layout().placements().values().stream()
				.mapToInt(placement -> Integer.numberOfTrailingZeros(placement.factor()))
				.max()
				.orElse(0);
			replaceLowAtlasRemap(remapTexture, remapView);
			closeReloadFallback();
			animatedSprites = image.transferAnimatedSprites();
			animatedStateMismatchLogged = false;
			if (releaseHighResolutionAtlas) {
				Object pbrHolder = image.isOriginalLayout()
					? IrisPbrAtlasReducer.holder(highResolutionTexture)
					: image.pbrImages() != null
						? IrisPbrAtlasReducer.detachHolder(highResolutionTexture)
						: null;
				if (image.pbrImages() != null) {
					NearAtlas created = NearAtlas.create(
						atlasWidth,
						atlasHeight,
						atlasMipLevels,
						net.minecraft.client.Minecraft.getInstance().getResourceManager()
					);
					if (created != null && created.isCompact() && NearAtlas.hybridRequested()) {
						AtlasStream.LOGGER.info(
							"Repacked low atlas uses compact near-atlas streaming instead of the layout-based hybrid fallback"
						);
					}
					if (nearAtlas != null) {
						nearAtlas.close();
					}
					nearAtlas = created;
					nearAtlasUnavailable = created == null;
				} else {
					// An original-layout low atlas is already the correct all-distance Iris texture. The lack of a
					// near atlas is intentional here, not a shader-switch transition that needs another reload.
					nearAtlasUnavailable = image.isOriginalLayout();
				}
				if (image.isOriginalLayout()) {
					AtlasStream.LOGGER.info(
						"Low atlas preserves the original {}x{} layout and vanilla PBR maps; "
							+ "Iris terrain uses both for all distances",
						atlasWidth,
						atlasHeight
					);
				}
				if (image.isOriginalLayout()) {
					// Item geometry and GUI previews retain direct references to the original atlas view. Keep it
					// resident for this no-relocation layout; Sodium terrain still selects the reduced texture
					// explicitly for terrain draws.
					IrisPbrAtlasReducer.rebindHolder(pbrHolder, highResolutionTexture, texture);
					AtlasStream.LOGGER.info(
						"Retaining the original block atlas for inventory and held-item rendering"
					);
				} else if (image.pbrImages() != null) {
					replaceBlockAtlasWithLowResolution(atlas, highResolutionTexture, texture, textureView);
					reducedPbrAtlases = IrisPbrAtlasReducer.apply(pbrHolder, texture, image.pbrImages());
				} else {
					replaceBlockAtlasWithLowResolution(atlas, highResolutionTexture, texture, textureView);
				}
			}
			initialized = true;
		} finally {
			if (!initialized) {
				if (remapView != null) {
					remapView.close();
				}
				remapTexture.close();
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

	private static void activateOriginalIrisAtlas(TextureAtlas atlas, GpuTexture texture) {
		GpuTextureView view = ((AbstractTextureAccessor) atlas).atlasStream$getTextureView();
		if (texture == null || view == null) {
			throw new IllegalStateException("The original Iris block atlas is unavailable");
		}
		closeVirtualTextureGpuPageTable();
		if (nearAtlas != null) {
			nearAtlas.close();
			nearAtlas = null;
		}
		nearAtlasUnavailable = false;
		lowAtlasRemap = null;
		lowAtlasOriginalLayout = true;
		lowAtlasStreamedMipLevels = 0;
		closeLowAtlasRemap();
		lowResolutionTexture = texture;
		lowResolutionTextureView = view;
		previewedAtlas = atlas;
		atlasOwnsLowResolutionTexture = false;
		originalAtlasReleaseConfirmed = false;
		reducedPbrAtlases = List.of();
		animatedSprites = List.of();
		AtlasStream.LOGGER.info(
			"Atlas requires no reduction; keeping the original Iris albedo and PBR atlases unchanged"
		);
	}

	public static void updateAnimatedFrames(TextureAtlas atlas) {
		if (previewedAtlas != atlas || lowResolutionTexture == null || animatedSprites.isEmpty()) {
			return;
		}
		TextureAtlasAccessor atlasAccessor = (TextureAtlasAccessor) atlas;
		List<SpriteContents.AnimationState> states = atlasAccessor.atlasStream$getAnimatedTextureStates();
		Map<Identifier, AnimatedSpriteData> animationsById = new HashMap<>();
		for (AnimatedSpriteData animation : animatedSprites) {
			animationsById.put(animation.region().identifier(), animation);
		}
		List<PendingAnimationUpdate> updates = new ArrayList<>();
		int stateIndex = 0;
		try (var blends = animationBlendCache.beginBatch()) {
			for (TextureAtlasSprite sprite : atlasAccessor.atlasStream$getSprites()) {
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
				int framePosition = stateAccessor.atlasStream$getFrame();
				int subFrame = stateAccessor.atlasStream$getSubFrame();
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
					List<NativeImage> currentImages = frameImages;
					long blendBytes = 0;
					for (NativeImage image : currentImages) {
						blendBytes += (long) image.getWidth() * image.getHeight() * 4;
					}
					AnimationBlendKey key = new AnimationBlendKey(
						animation, step.frameIndex(), nextFrameIndex, Float.floatToIntBits(blendWeight)
					);
					boolean recording = LodPerformanceStats.isRecording();
					long blendStart = recording ? System.nanoTime() : 0;
					frameImages = blends.getOrCreate(
						key, blendBytes, () -> {
							if (recording) {
								LodPerformanceStats.recordAnimationBlendBuild();
							}
							return blendMipLevels(currentImages, nextImages, blendWeight);
						}
					);
					if (recording) {
						LodPerformanceStats.recordAnimationBlend(System.nanoTime() - blendStart);
					}
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
				int sourceLeft = update.animation().region().x() - update.animation().region().paddingX();
				int sourceTop = update.animation().region().y() - update.animation().region().paddingY();
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
			NearAtlas near = nearAtlas;
			if (near != null && near.isHybrid()) {
				for (PendingAnimationUpdate update : updates) {
					int sourceLeft = update.animation().region().x() - update.animation().region().paddingX();
					int sourceTop = update.animation().region().y() - update.animation().region().paddingY();
					near.updateHybridAnimation(sourceLeft, sourceTop, update.mipLevels());
				}
			}
			blends.commit();
			for (PendingAnimationUpdate update : updates) {
				update.animation().lastFramePosition = update.framePosition();
				update.animation().lastSubFrame = update.subFrame();
			}
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
			AtlasStream.LOGGER.error("Could not synchronize low-atlas animations for {}: {}", atlas.location(), reason);
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
		pendingVirtualTextureTiles.clear();
		animatedVirtualTextureTiles.clear();
		animatedTiles.clear();
		discardPreparedTiles();
		spriteFrames.clear();
		activeVirtualTextureCursor = null;
		activeVirtualTextureDistanceSquared = Double.POSITIVE_INFINITY;
		closeVirtualTextureGpuPageTable();
		animationBlendCache.clear();
		closeAnimatedSprites(animatedSprites);
		animatedSprites = List.of();
		lowAtlasStreamedMipLevels = 0;
		lowAtlasRemap = null;
		boolean originalAtlasResident = lowAtlasOriginalLayout;
		lowAtlasOriginalLayout = false;
		closeLowAtlasRemap();
		animatedStateMismatchLogged = false;
		GpuTextureView textureView = lowResolutionTextureView;
		GpuTexture texture = lowResolutionTexture;
		lowResolutionTextureView = null;
		lowResolutionTexture = null;
		boolean atlasOwnsTexture = atlasOwnsLowResolutionTexture;
		atlasOwnsLowResolutionTexture = false;
		if (!originalAtlasResident && !atlasOwnsTexture && textureView != null) {
			textureView.close();
		}
		if (!originalAtlasResident && !atlasOwnsTexture && texture != null) {
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
			"AtlasStream virtual atlas dimensions",
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
		int originalWidth = ((TextureAtlasAccessor) atlas).atlasStream$getWidth();
		int originalHeight = ((TextureAtlasAccessor) atlas).atlasStream$getHeight();
		originalAtlasReleaseConfirmed = false;
		GpuTextureView[] oldMipViews = atlasAccessor.atlasStream$getMipViews();
		GpuTextureView oldTextureView = textureAccessor.atlasStream$getTextureView();
		atlasAccessor.atlasStream$setMipViews(new GpuTextureView[0]);
		textureAccessor.atlasStream$setTexture(lowResolutionTexture);
		textureAccessor.atlasStream$setTextureView(lowResolutionTextureView);
		atlasOwnsLowResolutionTexture = true;
		SodiumTerrainIntegration.trackAtlasForIris(lowResolutionTexture, atlas, highResolutionTexture);

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
				AtlasStream.LOGGER.error("Could not release the original high-resolution block atlas allocation", exception);
			}
		}
		if (originalTextureReleased) {
			AtlasStream.LOGGER.info(
				"Released the original {}x{} block atlas GPU allocation; streamed terrain uses the {}x{} fallback atlas",
				originalWidth,
				originalHeight,
				lowResolutionTexture.getWidth(0),
				lowResolutionTexture.getHeight(0)
			);
		} else {
			originalAtlasReleaseConfirmed = false;
			AtlasStream.LOGGER.error(
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
			AtlasStream.LOGGER.error("Could not release an original block-atlas mip view", exception);
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
		for (TextureAtlasSprite sprite : ((TextureAtlasAccessor) atlas).atlasStream$getSprites()) {
			SpriteRegion region = regionsById.get(sprite.contents().name());
			if (region == null) {
				continue;
			}
			List<MipImage> mipImages = new ArrayList<>();
			if (region.animated()) {
				strips.put(
					region.identifier().toString(),
					new AnimatedStrip(
						((SpriteContentsAccessor) sprite.contents()).atlasStream$getMipImages(),
						region.width(),
						region.height()
					)
				);
			} else {
				NativeImage[] images = ((SpriteContentsAccessor) sprite.contents()).atlasStream$getMipImages();
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
		return createMipLevel(previous, regions, level, AtlasRuntimeState::averagePixels);
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
			regions.parallelStream().forEach(region -> {
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
			});
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

	private record AnimationBlendKey(AnimatedSpriteData animation, int currentFrame, int nextFrame, int weightBits) {
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
		private final com.malaron.atlasstream.atlas.LowAtlasPacker.Layout layout;
		private final NativeImage remap;
		private final List<AnimatedSpriteData> animatedSprites;
		private final IrisPbrAtlasReducer.Images pbrImages;
		private final boolean originalLayout;
		private final boolean requiresNoReduction;
		private boolean animationsTransferred;

		private LowAtlasImage(
			List<NativeImage> mipLevels,
			com.malaron.atlasstream.atlas.LowAtlasPacker.Layout layout,
			NativeImage remap,
			List<AnimatedSpriteData> animatedSprites,
			IrisPbrAtlasReducer.Images pbrImages,
			boolean originalLayout,
			boolean requiresNoReduction
		) {
			this.mipLevels = List.copyOf(mipLevels);
			this.layout = layout;
			this.remap = remap;
			this.animatedSprites = List.copyOf(animatedSprites);
			this.pbrImages = pbrImages;
			this.originalLayout = originalLayout;
			this.requiresNoReduction = requiresNoReduction;
		}

		private IrisPbrAtlasReducer.Images pbrImages() {
			return pbrImages;
		}

		private List<NativeImage> mipLevels() {
			return mipLevels;
		}

		private com.malaron.atlasstream.atlas.LowAtlasPacker.Layout layout() {
			return layout;
		}

		private boolean isOriginalLayout() {
			return originalLayout;
		}

		private boolean requiresNoReduction() {
			return requiresNoReduction;
		}

		private NativeImage remap() {
			return remap;
		}

		private List<AnimatedSpriteData> transferAnimatedSprites() {
			animationsTransferred = true;
			return animatedSprites;
		}

		@Override
		public void close() {
			closeImages(mipLevels);
			remap.close();
			if (pbrImages != null) {
				pbrImages.close();
			}
			if (!animationsTransferred) {
				closeAnimatedSprites(animatedSprites);
			}
		}
	}
}
