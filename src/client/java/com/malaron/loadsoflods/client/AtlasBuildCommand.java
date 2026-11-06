package com.malaron.loadsoflods.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

import com.malaron.loadsoflods.LoadsOfLods;
import com.malaron.loadsoflods.atlas.AtlasBuildResult;
import com.malaron.loadsoflods.atlas.AtlasBuilder;
import com.malaron.loadsoflods.cache.VirtualTextureShaderPatcher;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class AtlasBuildCommand {
	private static final AtomicBoolean BUILD_RUNNING = new AtomicBoolean();

	private AtlasBuildCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
			dispatcher.register(
				literal("lods")
					.then(
						literal("preview-low")
							.requires(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource::attended)
							.executes(context -> enableLowPreview(context.getSource()))
					)
					.then(
						literal("preview-high")
							.requires(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource::attended)
							.executes(context -> disableLowPreview(context.getSource()))
					)
					.then(
						literal("preview-distance")
							.requires(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource::attended)
							.executes(context -> enableDistancePreview(context.getSource()))
					)
					.then(
						literal("perf")
							.requires(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource::attended)
							.then(literal("start").executes(context -> startPerformanceSample(context.getSource())))
					)
					.then(
						literal("cache")
							.requires(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource::attended)
							.executes(context -> reportVirtualTextureCache(context.getSource()))
					)
					.then(
						literal("item-debug")
							.requires(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource::attended)
							.executes(context -> reportItemStreaming(context.getSource()))
					)
					.then(
						literal("vram")
							.requires(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource::attended)
							.executes(context -> reportVram(context.getSource()))
					)
					.then(
						literal("glinfo")
							.requires(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource::attended)
							.executes(context -> reportGlInfo(context.getSource()))
					)
					.then(
						literal("build-atlases")
							.requires(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource::attended)
							.executes(context -> buildAtlases(context.getSource()))
					)
			)
		);
	}

	private static int reportGlInfo(
		net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source
	) {
		org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities();
		StringBuilder line = new StringBuilder("sparse_texture=" + caps.GL_ARB_sparse_texture
			+ " sparse_texture2=" + caps.GL_ARB_sparse_texture2
			+ " bindless=" + caps.GL_ARB_bindless_texture
			+ " tex_storage=" + caps.GL_ARB_texture_storage);
		if (caps.GL_ARB_sparse_texture) {
			line.append(" maxSparse2D=").append(org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.ARBSparseTexture.GL_MAX_SPARSE_TEXTURE_SIZE_ARB));
			for (int format : new int[] {org.lwjgl.opengl.GL11.GL_RGBA8}) {
				int[] pageX = new int[1];
				int[] pageY = new int[1];
				org.lwjgl.opengl.GL42.glGetInternalformativ(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, format, org.lwjgl.opengl.ARBSparseTexture.GL_VIRTUAL_PAGE_SIZE_X_ARB, pageX);
				org.lwjgl.opengl.GL42.glGetInternalformativ(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, format, org.lwjgl.opengl.ARBSparseTexture.GL_VIRTUAL_PAGE_SIZE_Y_ARB, pageY);
				line.append(" rgba8Page=").append(pageX[0]).append('x').append(pageY[0]);
			}
		}
		source.sendFeedback(Component.literal(line.toString()));
		LoadsOfLods.LOGGER.info("GL info: {}", line);
		return 1;
	}

	private static int reportVram(
		net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source
	) {
		for (String line : VramReport.build(12)) {
			source.sendFeedback(Component.literal(line));
			LoadsOfLods.LOGGER.info("VRAM report: {}", line);
		}
		return 1;
	}

	private static int reportVirtualTextureCache(
		net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source
	) {
		AtlasPreviewState.VirtualTextureCacheStatus status = AtlasPreviewState.virtualTextureCacheStatus();
		if (!status.available()) {
			source.sendError(Component.literal("The virtual texture cache is not initialized. Run /lods preview-distance first."));
			return 0;
		}

		source.sendFeedback(
			Component.literal(
				"Virtual texture cache: " + status.residentTiles() + " resident tiles, "
					+ status.usedBytes() / 1024 + " KiB used of " + status.capacityBytes() / 1024
					+ " KiB; " + status.pendingTiles() + " tile requests and "
					+ status.pendingQuads() + " UV quads pending; "
					+ status.capturedQuads() + " mesh quads and "
					+ status.capturedSpriteUses() + " Sodium sprite-use events captured."
			)
		);
		return 1;
	}

	private static int reportItemStreaming(
		net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source
	) {
		for (String line : ItemStreamDiagnostics.report()) {
			source.sendFeedback(Component.literal(line));
			LoadsOfLods.LOGGER.info("Item streaming: {}", line);
		}
		return 1;
	}

	private static int enableLowPreview(
		net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source
	) {
		return enablePreview(source, false);
	}

	private static int enableDistancePreview(
		net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source
	) {
		return enableDistancePreview(source.getClient(), source::sendFeedback, source::sendError);
	}

	public static int enableDistancePreview(
		Minecraft client,
		Consumer<Component> feedback,
		Consumer<Component> errors
	) {
		if (client.level == null || client.player == null) {
			errors.accept(Component.literal("Join a world before enabling distance-based LOD."));
			return 0;
		}
		if (!FabricLoader.getInstance().isModLoaded("sodium")) {
			errors.accept(Component.literal("Distance-based preview currently requires Sodium."));
			return 0;
		}
		if (!SodiumLodBridge.isIrisShaderPackInUse() && !VirtualTextureShaderPatcher.isPatchApplied()) {
			errors.accept(
				Component.literal("Sodium's supported virtual-texture shader has not compiled yet. Wait for chunk rendering, then retry enabling distance-based LOD.")
			);
			return 0;
		}
		if (AtlasPreviewState.isDistancePreviewEnabled()) {
			feedback.accept(Component.literal("Distance-based LOD is already enabled. Reload resource packs to rebuild the atlas with changed texture reduction or debug tint."));
			return 1;
		}
		if (SodiumLodBridge.isIrisShaderPackInUse() && AtlasPreviewState.hasLowAtlas()
				&& !AtlasPreviewState.isStreamedAtlasActive()) {
			errors.accept(Component.literal(
				"Reload resource packs before enabling distance-based LOD with Iris; the existing low preview does not include the required PBR atlases."
			));
			return 0;
		}
		try {
			return enablePreview(client, feedback, errors, true);
		} catch (RuntimeException exception) {
			LoadsOfLods.LOGGER.error("Could not enable distance-based LOD", exception);
			errors.accept(Component.literal("Could not enable distance-based LOD: " + exception.getMessage()));
			return 0;
		}
	}

	private static int enablePreview(
		net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source,
		boolean distancePreview
	) {
		return enablePreview(source.getClient(), source::sendFeedback, source::sendError, distancePreview);
	}

	private static int enablePreview(
		Minecraft client,
		Consumer<Component> feedback,
		Consumer<Component> errors,
		boolean distancePreview
	) {
		if (AtlasPreviewState.isBuilding()) {
			errors.accept(Component.literal("The low-resolution atlas preview is still being built."));
			return 0;
		}
		if (AtlasPreviewState.isStreamedAtlasActive() && !distancePreview) {
			errors.accept(
				Component.literal("The full-resolution atlas was released. Reload resource packs to restore it.")
			);
			return 0;
		}
		if (AtlasPreviewState.hasLowAtlas()) {
			if (distancePreview) {
				AtlasPreviewState.enableDistancePreview();
				SodiumLodBridge.scheduleVisibleTerrainRebuild();
				boolean irisShaderpack = SodiumLodBridge.isIrisShaderPackInUse();
				feedback.accept(
					Component.literal(
						(irisShaderpack
							? "Distance-based LOD enabled with Iris."
							: "Streamed albedo LOD enabled. Nearby terrain uses the bounded high-resolution tile cache, and the low atlas is the fallback.")
							+ (AtlasPreviewState.isOriginalAtlasReleaseConfirmed()
								? " The full block atlas was released; reload resource packs to restore it."
								: " The full atlas release could not be confirmed; check latest.log.")
					)
				);
			} else {
				AtlasPreviewState.enableLowPreview();
				feedback.accept(Component.literal("Low-resolution atlas preview enabled for chunk terrain."));
			}
			return 1;
		}

		if (!(client.getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS)
				instanceof net.minecraft.client.renderer.texture.TextureAtlas atlas)) {
			errors.accept(Component.literal("The Minecraft block atlas is not available yet."));
			return 0;
		}
		feedback.accept(
			Component.literal(
				"Building a separate low-resolution block atlas in Minecraft's existing sprite layout..."
			)
		);
		try {
			AtlasPreviewState.buildAndEnable(atlas, client.getResourceManager(), feedback, errors, distancePreview);
		} catch (RuntimeException exception) {
			LoadsOfLods.LOGGER.error("Could not start low-resolution atlas preview build", exception);
			errors.accept(Component.literal("Could not start atlas preview: " + exception.getMessage()));
			return 0;
		}
		return 1;
	}

	private static int disableLowPreview(
		net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source
	) {
		if (AtlasPreviewState.isStreamedAtlasActive()) {
			source.sendError(
				Component.literal("The full-resolution atlas was released. Reload resource packs to restore it.")
			);
			return 0;
		}
		AtlasPreviewState.disableLowPreview();
		source.sendFeedback(
			Component.literal("High-resolution block atlas enabled. Run /lods preview-low to switch back.")
		);
		return 1;
	}

	private static int startPerformanceSample(
		net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source
	) {
		if (!AtlasPreviewState.isDistancePreviewEnabled()) {
			source.sendError(Component.literal("Enable /lods preview-distance before starting a performance sample."));
			return 0;
		}
		if (LodPerformanceStats.isRecording()) {
			source.sendError(Component.literal("An LOD CPU sample is already running."));
			return 0;
		}
		LodPerformanceStats.start();
		var client = source.getClient();
		long sampleDurationSeconds = LodPerformanceStats.sampleDurationSeconds();
		source.sendFeedback(
			Component.literal(
				"LOD CPU sample started for " + sampleDurationSeconds
					+ " seconds. Hold a steady view; results will appear automatically."
			)
		);
		CompletableFuture.delayedExecutor(sampleDurationSeconds, TimeUnit.SECONDS)
			.execute(() -> client.execute(() -> reportPerformanceSample(source)));
		return 1;
	}

	private static void reportPerformanceSample(
		net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source
	) {
		var lines = LodPerformanceStats.stopAndFormat();
		if (lines.isEmpty()) {
			return;
		}
		for (String line : lines) {
			source.sendFeedback(Component.literal(line));
		}
	}

	private static int buildAtlases(
		net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source
	) {
		if (!BUILD_RUNNING.compareAndSet(false, true)) {
			source.sendError(Component.literal("A Loads of Lods atlas build is already running."));
			return 0;
		}

		Path outputDirectory = FabricLoader.getInstance()
			.getConfigDir()
			.resolve("loads-of-lods")
			.resolve("generated");
		var resourceManager = source.getClient().getResourceManager();
		source.sendFeedback(
			Component.literal("Building atlases from the currently active resource packs...")
		);
		CompletableFuture.runAsync(() -> {
			try {
				AtlasBuildResult result = AtlasBuilder.build(
					resourceManager,
					outputDirectory,
					LoadsOfLodsClient.getConfig().downscaleFactor()
				);
				source.getClient().execute(() -> source.sendFeedback(
					Component.literal(
						"Built " + result.spriteCount() + " block textures across " + result.pageCount()
							+ " atlas page(s). Output: "
							+ result.layoutManifest().toAbsolutePath()
							+ (result.cacheHit() ? " (cached)" : "")
					)
				));
				LoadsOfLods.LOGGER.info(
					"Built active-pack atlas pair with {} sprites at {}",
					result.spriteCount(),
					result.layoutManifest()
				);
			} catch (IOException | RuntimeException exception) {
				LoadsOfLods.LOGGER.error("Could not build atlases from active resource packs", exception);
				source.getClient().execute(() -> source.sendError(
					Component.literal("Atlas build failed: " + exception.getMessage())
				));
			} finally {
				BUILD_RUNNING.set(false);
			}
		});
		return 1;
	}
}
