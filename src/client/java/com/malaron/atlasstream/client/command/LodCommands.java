package com.malaron.atlasstream.client.command;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.malaron.atlasstream.AtlasStream;
import com.malaron.atlasstream.atlas.AtlasBuildResult;
import com.malaron.atlasstream.atlas.AtlasBuilder;
import com.malaron.atlasstream.client.AtlasStreamClient;
import com.malaron.atlasstream.client.atlas.AtlasRuntimeState;
import com.malaron.atlasstream.client.atlas.NearAtlas;
import com.malaron.atlasstream.client.diagnostics.ItemStreamDiagnostics;
import com.malaron.atlasstream.client.diagnostics.LodPerformanceStats;
import com.malaron.atlasstream.client.diagnostics.TerrainAlphaDiagnostics;
import com.malaron.atlasstream.client.diagnostics.VramReport;
import com.malaron.atlasstream.client.mixin.TextureAtlasAccessor;
import com.malaron.atlasstream.client.sodium.SodiumTerrainIntegration;
import com.malaron.atlasstream.client.sodium.TerrainLayerCorrections;
import com.malaron.atlasstream.shader.VirtualTextureShaderPatcher;

import com.mojang.brigadier.arguments.StringArgumentType;

import javax.imageio.ImageIO;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class LodCommands {
	private static final AtomicBoolean BUILD_RUNNING = new AtomicBoolean();

	private LodCommands() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
			dispatcher.register(
				literal("atlasstream")
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
					.then(LodConfigCommands.build())
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
						literal("near-debug")
							.requires(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource::attended)
							.executes(context -> reportNearAtlas(context.getSource()))
					)
					.then(
						literal("sprite-alpha")
							.requires(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource::attended)
							.then(argument("sprite", StringArgumentType.greedyString())
								.executes(context -> reportSpriteAlpha(context.getSource(), StringArgumentType.getString(context, "sprite"))))
					)
					.then(
						literal("terrain-alpha")
							.requires(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource::attended)
							.executes(context -> startTerrainAlpha(context.getSource()))
					)
					.then(
						literal("plant-pass")
							.requires(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource::attended)
							.then(literal("cutout").executes(context -> setPlantPass(context.getSource(), true)))
							.then(literal("normal").executes(context -> setPlantPass(context.getSource(), false)))
					)
					.then(
						literal("overlay-pass")
							.requires(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource::attended)
							.then(literal("cutout").executes(context -> setOverlayPass(context.getSource(), true)))
							.then(literal("normal").executes(context -> setOverlayPass(context.getSource(), false)))
					)
					.then(
						literal("flower-pass")
							.requires(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource::attended)
							.then(literal("cutout").executes(context -> setFlowerPass(context.getSource(), true)))
							.then(literal("normal").executes(context -> setFlowerPass(context.getSource(), false)))
					)
					.then(
						literal("near-mip-test")
							.requires(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource::attended)
							.then(literal("base").executes(context -> setNearMipTest(context.getSource(), true)))
							.then(literal("normal").executes(context -> setNearMipTest(context.getSource(), false)))
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

	private static int setNearMipTest(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, boolean baseOnly) {
		NearAtlas near = AtlasRuntimeState.activeNearAtlas();
		if (near == null || !SodiumTerrainIntegration.isIrisShaderPackInUse()) {
			source.sendError(Component.literal("The near mip test requires an active Iris shader pack and near atlas."));
			return 0;
		}
		try {
			near.setAlbedoBaseMipOnly(baseOnly);
		} catch (IllegalStateException exception) {
			AtlasStream.LOGGER.error("Could not change the near albedo mip limit", exception);
			source.sendError(Component.literal("Could not change the near albedo mip limit: " + exception.getMessage()));
			return 0;
		}
		String message = baseOnly
			? "Near terrain albedo mip test: base mip only. Other nearby textures may shimmer; use /atlasstream near-mip-test normal to restore."
			: "Near albedo mip test: normal mip levels restored.";
		AtlasStream.LOGGER.info(message);
		source.sendFeedback(Component.literal(message));
		return 1;
	}

	private static int setPlantPass(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, boolean cutout) {
		Minecraft client = source.getClient();
		if (!FabricLoader.getInstance().isModLoaded("sodium") || client.level == null || client.player == null) {
			source.sendError(Component.literal("The plant pass experiment requires Sodium and an active world."));
			return 0;
		}
		Block block;
		if (cutout) {
			if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
				source.sendError(Component.literal("Look at the affected block before running this command."));
				return 0;
			}
			block = client.level.getBlockState(hit.getBlockPos()).getBlock();
		} else {
			block = TerrainLayerCorrections.targetBlock();
			if (block == null) {
				source.sendError(Component.literal("No plant pass experiment is active."));
				return 0;
			}
		}
		TerrainLayerCorrections.setTargetBlockCutout(block, cutout);
		try {
			SodiumTerrainIntegration.reloadTerrainForCutoutChange();
		} catch (IllegalStateException exception) {
			AtlasStream.LOGGER.error("Could not rebuild terrain after changing the plant pass experiment", exception);
			source.sendError(Component.literal("Experiment changed, but terrain could not be rebuilt: " + exception.getMessage() + ". Reload the world to apply it."));
			return 0;
		}
		source.sendFeedback(Component.literal(
			"Plant pass experiment for " + BuiltInRegistries.BLOCK.getKey(block) + ": "
				+ (cutout ? "cutout" : "normal")
				+ (cutout ? ". Terrain is rebuilding; distinct matching quad layers will be logged."
					: ". Terrain is rebuilding; overlay-pass and flower-pass settings still apply.")
		));
		return 1;
	}

	private static int setOverlayPass(
		net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, boolean cutout
	) {
		Minecraft client = source.getClient();
		if (!FabricLoader.getInstance().isModLoaded("sodium") || client.level == null || client.player == null) {
			source.sendError(Component.literal("Changing the overlay pass requires Sodium and an active world."));
			return 0;
		}

		TerrainLayerCorrections.setOverlaysUseCutout(cutout);
		try {
			SodiumTerrainIntegration.reloadTerrainForCutoutChange();
		} catch (IllegalStateException exception) {
			AtlasStream.LOGGER.error("Could not rebuild terrain after changing the overlay pass", exception);
			source.sendError(Component.literal("Overlay pass changed, but terrain could not be rebuilt: "
				+ exception.getMessage() + ". Reload the world to apply it."));
			return 0;
		}
		source.sendFeedback(Component.literal(cutout
			? "Overlay cutout enabled. Matching overlays may lose soft edges; terrain is rebuilding."
			: "Overlay cutout disabled for this session. Terrain is rebuilding; any plant-pass experiment still applies."));
		return 1;
	}

	private static int setFlowerPass(
		net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, boolean cutout
	) {
		Minecraft client = source.getClient();
		if (!FabricLoader.getInstance().isModLoaded("sodium") || client.level == null || client.player == null) {
			source.sendError(Component.literal("Changing the flower pass requires Sodium and an active world."));
			return 0;
		}
		TerrainLayerCorrections.setFlowersUseCutout(cutout);
		try {
			SodiumTerrainIntegration.reloadTerrainForCutoutChange();
		} catch (IllegalStateException exception) {
			AtlasStream.LOGGER.error("Could not rebuild terrain after changing the flower pass", exception);
			source.sendError(Component.literal("Flower pass changed, but terrain could not be rebuilt: "
				+ exception.getMessage() + ". Reload the world to apply it."));
			return 0;
		}
		source.sendFeedback(Component.literal(cutout
			? "Flower cutout enabled for small-flower-tagged blocks. Soft transparency may become hard-edged; terrain is rebuilding."
			: "Flower cutout disabled for this session. Terrain is rebuilding; any plant-pass experiment still applies."));
		return 1;
	}

	private static int startTerrainAlpha(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source) {
		if (!SodiumTerrainIntegration.isIrisShaderPackInUse()) {
			source.sendError(Component.literal("Terrain alpha diagnostics require an active Iris shader pack."));
			return 0;
		}
		TerrainAlphaDiagnostics.start();
		source.sendFeedback(Component.literal("Sampling terrain draw alpha state for five seconds; results are written to the log."));
		return 1;
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
		AtlasStream.LOGGER.info("GL info: {}", line);
		return 1;
	}

	private static int reportVram(
		net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source
	) {
		for (String line : VramReport.build(12)) {
			source.sendFeedback(Component.literal(line));
			AtlasStream.LOGGER.info("VRAM report: {}", line);
		}
		return 1;
	}

	private static int reportVirtualTextureCache(
		net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source
	) {
		AtlasRuntimeState.VirtualTextureCacheStatus status = AtlasRuntimeState.virtualTextureCacheStatus();
		if (!status.available()) {
			source.sendError(Component.literal("The virtual texture cache is not initialized. Run /atlasstream preview-distance first."));
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
			AtlasStream.LOGGER.info("Item streaming: {}", line);
		}
		return 1;
	}

	private static int reportNearAtlas(
		net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source
	) {
		Minecraft client = source.getClient();
		NearAtlas near = AtlasRuntimeState.activeNearAtlas();
		if (near == null) {
			source.sendError(Component.literal("The near atlas is not active."));
			return 0;
		}

		var camera = client.gameRenderer.mainCamera().position();
		int x = Math.floorDiv((int) Math.floor(camera.x), 16);
		int y = Math.floorDiv((int) Math.floor(camera.y), 16);
		int z = Math.floorDiv((int) Math.floor(camera.z), 16);
		reportNearSection(source, near, "camera", x, y, z);
		if (client.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			var block = hit.getBlockPos();
			int targetX = Math.floorDiv(block.getX(), 16);
			int targetY = Math.floorDiv(block.getY(), 16);
			int targetZ = Math.floorDiv(block.getZ(), 16);
			if (targetX != x || targetY != y || targetZ != z) {
				reportNearSection(source, near, "target block", targetX, targetY, targetZ);
			} else {
				source.sendFeedback(Component.literal("Target block is in the camera section."));
			}
		} else {
			source.sendFeedback(Component.literal("No target block in reach."));
		}
		return 1;
	}

	private static int reportSpriteAlpha(
		net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, String spriteName
	) {
		var id = net.minecraft.resources.Identifier.tryParse(spriteName);
		if (id == null) {
			source.sendError(Component.literal("Invalid sprite identifier: " + spriteName));
			return 0;
		}
		NearAtlas near = AtlasRuntimeState.activeNearAtlas();
		if (near == null) {
			source.sendError(Component.literal("The near atlas is not active."));
			return 0;
		}
		var texture = source.getClient().getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
		if (!(texture instanceof net.minecraft.client.renderer.texture.TextureAtlas atlas)) {
			source.sendError(Component.literal("The block atlas is unavailable."));
			return 0;
		}
		var sprite = ((com.malaron.atlasstream.client.mixin.TextureAtlasAccessor) atlas).atlasStream$getSprites().stream()
			.filter(candidate -> candidate.contents().name().equals(id))
			.findFirst();
		if (sprite.isEmpty()) {
			source.sendError(Component.literal("Sprite not found in the block atlas: " + id));
			return 0;
		}
		try {
			for (String line : near.spriteAlphaDebugLines(sprite.get())) {
				source.sendFeedback(Component.literal(line));
				AtlasStream.LOGGER.info("Sprite alpha: {}", line);
			}
			return 1;
		} catch (IOException | IllegalStateException exception) {
			AtlasStream.LOGGER.error("Could not inspect alpha for sprite {}", id, exception);
			source.sendError(Component.literal("Could not inspect sprite alpha: " + exception.getMessage()));
			return 0;
		}
	}

	private static void reportNearSection(
		net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source,
		NearAtlas near, String label, int x, int y, int z
	) {
		for (String line : near.sectionDebugLines(label, x, y, z)) {
			source.sendFeedback(Component.literal(line));
			AtlasStream.LOGGER.info("Near debug: {}", line);
		}
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
		if (!SodiumTerrainIntegration.isIrisShaderPackInUse() && !VirtualTextureShaderPatcher.isPatchApplied()) {
			errors.accept(
				Component.literal("Sodium's supported virtual-texture shader has not compiled yet. Wait for chunk rendering, then retry enabling distance-based LOD.")
			);
			return 0;
		}
		if (AtlasRuntimeState.isDistancePreviewEnabled()) {
			feedback.accept(Component.literal("Distance-based LOD is already enabled. Reload resource packs to rebuild the atlas with changed texture reduction or debug tint."));
			return 1;
		}
		if (SodiumTerrainIntegration.isIrisShaderPackInUse() && AtlasRuntimeState.hasLowAtlas()
				&& !AtlasRuntimeState.isStreamedAtlasActive()) {
			errors.accept(Component.literal(
				"Reload resource packs before enabling distance-based LOD with Iris; the existing low preview does not include the required PBR atlases."
			));
			return 0;
		}
		try {
			return enablePreview(client, feedback, errors, true);
		} catch (RuntimeException exception) {
			AtlasStream.LOGGER.error("Could not enable distance-based LOD", exception);
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
		if (AtlasRuntimeState.isBuilding()) {
			errors.accept(Component.literal("The low-resolution atlas preview is still being built."));
			return 0;
		}
		if (AtlasRuntimeState.isStreamedAtlasActive() && !distancePreview) {
			errors.accept(
				Component.literal("The full-resolution atlas was released. Reload resource packs to restore it.")
			);
			return 0;
		}
		if (AtlasRuntimeState.hasLowAtlas()) {
			if (distancePreview) {
				AtlasRuntimeState.enableDistancePreview();
				SodiumTerrainIntegration.scheduleVisibleTerrainRebuild();
				boolean irisShaderpack = SodiumTerrainIntegration.isIrisShaderPackInUse();
				feedback.accept(
					Component.literal(
						(irisShaderpack
							? "Distance-based LOD enabled with Iris."
							: "Streamed albedo LOD enabled. Nearby terrain uses the bounded high-resolution tile cache, and the low atlas is the fallback.")
							+ (AtlasRuntimeState.isOriginalAtlasReleaseConfirmed()
								? " The full block atlas was released; reload resource packs to restore it."
								: " The full atlas release could not be confirmed; check latest.log.")
					)
				);
			} else {
				AtlasRuntimeState.enableLowPreview();
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
			AtlasRuntimeState.buildAndEnable(atlas, client.getResourceManager(), feedback, errors, distancePreview);
		} catch (RuntimeException exception) {
			AtlasStream.LOGGER.error("Could not start low-resolution atlas preview build", exception);
			errors.accept(Component.literal("Could not start atlas preview: " + exception.getMessage()));
			return 0;
		}
		return 1;
	}

	private static int disableLowPreview(
		net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source
	) {
		if (AtlasRuntimeState.isStreamedAtlasActive()) {
			source.sendError(
				Component.literal("The full-resolution atlas was released. Reload resource packs to restore it.")
			);
			return 0;
		}
		AtlasRuntimeState.disableLowPreview();
		source.sendFeedback(
			Component.literal("High-resolution block atlas enabled. Run /atlasstream preview-low to switch back.")
		);
		return 1;
	}

	private static int startPerformanceSample(
		net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source
	) {
		if (!AtlasRuntimeState.isDistancePreviewEnabled()) {
			source.sendError(Component.literal("Enable /atlasstream preview-distance before starting a performance sample."));
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
			source.sendError(Component.literal("A AtlasStream atlas build is already running."));
			return 0;
		}

		Path outputDirectory = FabricLoader.getInstance()
			.getConfigDir()
			.resolve("atlasstream")
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
					standaloneReductionFactor(
						resourceManager,
						AtlasStreamClient.getConfig().targetResolution()
					)
				);
				source.getClient().execute(() -> source.sendFeedback(
					Component.literal(
						"Built " + result.spriteCount() + " block textures across " + result.pageCount()
							+ " atlas page(s). Output: "
							+ result.layoutManifest().toAbsolutePath()
							+ (result.cacheHit() ? " (cached)" : "")
					)
				));
				AtlasStream.LOGGER.info(
					"Built active-pack atlas pair with {} sprites at {}",
					result.spriteCount(),
					result.layoutManifest()
				);
			} catch (IOException | RuntimeException exception) {
				AtlasStream.LOGGER.error("Could not build atlases from active resource packs", exception);
				source.getClient().execute(() -> source.sendError(
					Component.literal("Atlas build failed: " + exception.getMessage())
				));
			} finally {
				BUILD_RUNNING.set(false);
			}

		});
		return 1;
	}

	private static int standaloneReductionFactor(
		net.minecraft.server.packs.resources.ResourceManager resourceManager,
		int targetResolution
	) {
		int largestDimension = 16;
		for (var resource : resourceManager.listResources(
			"textures/block", identifier -> identifier.getPath().endsWith(".png")
		).values()) {
			try (InputStream input = resource.open()) {
				var image = ImageIO.read(input);
				if (image != null) {
					largestDimension = Math.max(largestDimension, Math.max(image.getWidth(), image.getHeight()));
					image.flush();
				}
			} catch (IOException exception) {
				AtlasStream.LOGGER.debug("Could not inspect block texture for standalone atlas scale", exception);
			}
		}
		int factor = 2;
		while (Math.ceilDiv(largestDimension, factor) > targetResolution && factor < 16) {
			factor <<= 1;
		}
		return factor;
	}
}
