package com.malaron.atlasstream.client.sodium;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.malaron.atlasstream.AtlasStream;
import com.malaron.atlasstream.client.AtlasStreamClient;
import com.malaron.atlasstream.client.atlas.AtlasRuntimeState;
import com.malaron.atlasstream.client.atlas.NearAtlas;
import com.malaron.atlasstream.client.diagnostics.LodPerformanceStats;
import com.malaron.atlasstream.client.diagnostics.TerrainAlphaDiagnostics;
import com.malaron.atlasstream.lod.LodConfig;
import com.malaron.atlasstream.lod.RevisionTracker;
import com.malaron.atlasstream.lod.SectionLod;
import com.malaron.atlasstream.lod.SectionLodPolicy;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

public final class SodiumTerrainIntegration {
	private static final double CAMERA_CACHE_CELL_SIZE = 0.25;
	private static final double CAMERA_CACHE_CELL_CENTER_OFFSET = CAMERA_CACHE_CELL_SIZE / 2.0;
	private static final String RENDER_LIST_INTERFACE =
		"net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable";
	private static final String RENDER_LIST_CLASS =
		"net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList";
	private static final ThreadLocal<SectionLod> ACTIVE_LOD = new ThreadLocal<>();
	private static final ThreadLocal<Boolean> TRANSLUCENT_PASS = ThreadLocal.withInitial(() -> false);
	private static final Map<MethodKey, Method> METHODS = new ConcurrentHashMap<>();
	private static final AtomicBoolean IRIS_STATE_FAILURE_LOGGED = new AtomicBoolean();
	private static final AtomicBoolean IRIS_SAMPLER_REFRESH_FAILURE_LOGGED = new AtomicBoolean();
	private static final AtomicBoolean IRIS_SAMPLER_REFRESH_LOGGED = new AtomicBoolean();
	private static final AtomicBoolean IRIS_PBR_LOOKUP_FAILURE_LOGGED = new AtomicBoolean();
	private static final AtomicBoolean LOW_ATLAS_BINDING_LOGGED = new AtomicBoolean();
	private static final AtomicBoolean SECTION_SPLIT_LOGGED = new AtomicBoolean();
	private static volatile Method irisPackInUseMethod;
	private static volatile Field irisActiveProgramSamplersField;
	private static volatile Method irisProgramSamplersUpdateMethod;
	private static final Set<Object> SPLIT_PASSES =
		Collections.newSetFromMap(new IdentityHashMap<>());
	private static final ClassValue<Method> RENDER_METHODS = new ClassValue<>() {
		@Override
		protected Method computeValue(Class<?> type) {
			return findRenderMethod(type);
		}
	};
	private static final ClassValue<ChunkListMethods> CHUNK_LIST_METHODS = new ClassValue<>() {
		@Override
		protected ChunkListMethods computeValue(Class<?> type) {
			return new ChunkListMethods(type);
		}
	};
	private static final ClassValue<SectionIteratorMethods> SECTION_ITERATOR_METHODS = new ClassValue<>() {
		@Override
		protected SectionIteratorMethods computeValue(Class<?> type) {
			return new SectionIteratorMethods(type);
		}
	};
	private static final Map<RegionCoordinates, RegionLodCache> REGION_LEVELS =
		new LinkedHashMap<>(4096, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<RegionCoordinates, RegionLodCache> eldest) {
				return size() > 2048;
			}
		};
	private static Object cachedLevel;

	private SodiumTerrainIntegration() {
	}

	public static void sampleTerrainAlpha(Object terrainPass) {
		if (!TerrainAlphaDiagnostics.isActive()) {
			return;
		}
		TerrainAlphaDiagnostics.afterDraw(
			isTranslucent(terrainPass),
			(boolean) invoke(terrainPass, "supportsFragmentDiscard", new Class<?>[0]),
			String.valueOf(ACTIVE_LOD.get())
		);
	}

	public static void render(
		Object renderer,
		Object matrices,
		Object renderLists,
		Object terrainRenderPass,
		Object cameraTransform,
		Object fogParameters,
		boolean translucentSorting,
		Object renderPass,
		Object sampler,
		Object globals,
		Object sectionTimeInfo,
		Object oitStage
	) {
		Object[] arguments = {
			matrices,
			null,
			terrainRenderPass,
			cameraTransform,
			fogParameters,
			translucentSorting,
			renderPass,
			sampler,
			globals,
			sectionTimeInfo,
			oitStage
		};
		Method renderMethod = RENDER_METHODS.get(renderer.getClass());
		boolean distancePreview = AtlasRuntimeState.isDistancePreviewEnabled();
		boolean irisShaderpack = distancePreview && isIrisShaderPackInUse() && !AtlasRuntimeState.isNearAtlasActive();
		boolean translucent = isTranslucent(terrainRenderPass);
		if (irisShaderpack) {
			ACTIVE_LOD.set(SectionLod.LOW_RESOLUTION);
			try {
				refreshIrisPbrSamplers();
				arguments[1] = renderLists;
				invoke(renderMethod, renderer, arguments);
			} finally {
				ACTIVE_LOD.remove();
				refreshIrisPbrSamplers();
			}
			return;
		}
		// With Iris and the near atlas, translucent terrain is split by distance too so glass and water near the
		// player use full-resolution textures. Far sections are drawn first to keep back-to-front order.
		boolean splitTranslucent = translucent && isIrisShaderPackInUse() && AtlasRuntimeState.isNearAtlasActive();
		if (!distancePreview || (translucent && !splitTranslucent)) {
			boolean restoreFullBatches = distancePreview
				? translucent
				: SPLIT_PASSES.remove(terrainRenderPass);
			if (restoreFullBatches) {
				clearCachedBatches(renderLists, terrainRenderPass);
				prepare(renderer, renderLists, cameraTransform, translucentSorting);
			}
			arguments[1] = renderLists;
			if (distancePreview && isIrisShaderPackInUse()) {
				refreshIrisPbrSamplers();
			}
			TRANSLUCENT_PASS.set(translucent);
			try {
				invoke(renderMethod, renderer, arguments);
			} finally {
				TRANSLUCENT_PASS.remove();
			}
			return;
		}

		SPLIT_PASSES.add(terrainRenderPass);
		Vec3 cameraPosition = Minecraft.getInstance().gameRenderer.mainCamera().position();
		Map<Boolean, FilteredListsByLod> filteredListsByTranslucency = filteredListCache(renderLists, cameraPosition);
		try {
			SectionLod[] order = translucent
				? new SectionLod[] { SectionLod.LOW_RESOLUTION, SectionLod.HIGH_RESOLUTION }
				: SectionLod.values();
			for (SectionLod lod : order) {
				ACTIVE_LOD.set(lod);
				try {
					Object filteredLists = filteredRenderLists(
						renderLists,
						lod,
						cameraPosition,
						filteredListsByTranslucency
					);
					clearCachedBatches(renderLists, terrainRenderPass);
					boolean recording = LodPerformanceStats.isRecording();
					long prepareStart = recording ? System.nanoTime() : 0;
					if (recording) {
						LodPerformanceStats.beginPrepare(lod);
					}
					try {
						prepare(renderer, filteredLists, cameraTransform, translucentSorting);
					} finally {
						if (recording) {
							LodPerformanceStats.recordPrepare(lod, System.nanoTime() - prepareStart);
						}
					}
					arguments[1] = filteredLists;
					long drawStart = recording ? System.nanoTime() : 0;
					refreshIrisPbrSamplers();
					invoke(renderMethod, renderer, arguments);
					if (recording) {
						LodPerformanceStats.recordDraw(lod, System.nanoTime() - drawStart);
					}
				} finally {
					ACTIVE_LOD.remove();
				}
			}
		} finally {
			ACTIVE_LOD.remove();
			refreshIrisPbrSamplers();
		}
	}

	// Iris only binds samplers it registered itself, so the near remap texture is added to its terrain programs here.
	public static void registerNearRemapSampler(Object samplerHolder) {
		try {
			Class<?> typeClass = Class.forName(
				"net.irisshaders.iris.gl.texture.TextureType",
				false,
				SodiumTerrainIntegration.class.getClassLoader()
			);
			Object type = typeClass.getField("TEXTURE_2D").get(null);
			Class<?> holderClass = Class.forName(
				"net.irisshaders.iris.gl.sampler.SamplerHolder",
				false,
				SodiumTerrainIntegration.class.getClassLoader()
			);
			Method method = holderClass.getMethod(
				"addDynamicSampler",
				typeClass,
				java.util.function.IntSupplier.class,
				java.util.function.Supplier.class,
				String[].class
			);
			java.util.function.IntSupplier id = () -> {
				SectionLod lod = ACTIVE_LOD.get();
				// Vanilla-style programs (no active terrain LOD) guard the remap themselves by the bound texture size.
				boolean remap = (lod == SectionLod.HIGH_RESOLUTION || lod == null) && AtlasRuntimeState.isDistancePreviewEnabled();
				return remap ? AtlasRuntimeState.nearRemapGlId() : AtlasRuntimeState.nearPlaceholderGlId();
			};
			java.util.function.Supplier<Object> sampler = () -> null;
			method.invoke(samplerHolder, type, id, sampler, new String[] {"u_AtlasStreamNearRemap"});
			java.util.function.IntSupplier lowRemapId = () -> {
				return AtlasRuntimeState.isDistancePreviewEnabled()
					? AtlasRuntimeState.lowAtlasRemapGlId()
					: AtlasRuntimeState.nearPlaceholderGlId();
			};
			method.invoke(samplerHolder, type, lowRemapId, sampler, new String[] {"u_AtlasStreamLowRemap"});
		} catch (ReflectiveOperationException | RuntimeException exception) {
			if (IRIS_PBR_LOOKUP_FAILURE_LOGGED.compareAndSet(false, true)) {
				AtlasStream.LOGGER.error("Could not register the near remap sampler with Iris", exception);
			}
		}
	}

	// The remap texture is only bound while the near atlas is the albedo atlas of the pass; otherwise a 1x1
	// placeholder makes the vertex shader leave UVs untouched.
	public static com.mojang.renderpearl.api.textures.GpuTextureView nearRemapForCurrentPass() {
		return AtlasRuntimeState.nearRemapPlaceholder();
	}

	public static com.mojang.renderpearl.api.textures.GpuTextureView lowAtlasForCurrentPass() {
		if (AtlasRuntimeState.isDistancePreviewEnabled()
				&& AtlasRuntimeState.isNearAtlasActive()
				&& isIrisShaderPackInUse()) {
			// The atlas texture slot returns the near atlas, so every terrain pass must pick its atlas explicitly.
			com.mojang.renderpearl.api.textures.GpuTextureView near = ACTIVE_LOD.get() == SectionLod.HIGH_RESOLUTION
				? AtlasRuntimeState.nearAtlasView()
				: AtlasRuntimeState.lowResolutionTextureViewForDistancePreview();
			if (near != null) {
				return near;
			}
		}
		if (!useLowResolutionForCurrentPass()) {
			return null;
		}
		if (LOW_ATLAS_BINDING_LOGGED.compareAndSet(false, true)) {
			AtlasStream.LOGGER.info(
				"Sodium TerrainRenderPass.getAtlas is binding the reduced albedo atlas for the active terrain pass"
			);
		}
		return AtlasRuntimeState.lowResolutionTextureViewForDistancePreview();
	}

	public static boolean useLowResolutionForCurrentPass() {
		SectionLod activeLod = ACTIVE_LOD.get();
		return activeLod != null
			&& AtlasRuntimeState.isDistancePreviewEnabled()
			&& (activeLod == SectionLod.LOW_RESOLUTION
				|| (AtlasRuntimeState.isStreamedAtlasActive() && !isIrisShaderPackInUse()));
	}

	// Iris finds normal/specular maps by the bound albedo texture's GL id. Keep that lookup on the
	// original atlas so PBR stays untouched while the albedo atlas is reduced.
	// Iris resolves PBR maps through TextureTracker's GL-id map, which only learns the atlas at upload time.
	public static void trackAtlasForIris(
		com.mojang.renderpearl.api.textures.GpuTexture texture,
		Object atlas,
		com.mojang.renderpearl.api.textures.GpuTexture releasedTexture
	) {
		if (!FabricLoader.getInstance().isModLoaded("iris")) {
			return;
		}
		try {
			int glId = (Integer) texture.getClass().getMethod("iris$getGlId").invoke(texture);
			Class<?> trackerClass = Class.forName(
				"net.irisshaders.iris.pbr.TextureTracker",
				false,
				SodiumTerrainIntegration.class.getClassLoader()
			);
			Object tracker = trackerClass.getField("INSTANCE").get(null);
			trackerClass.getMethod(
				"trackTexture",
				int.class,
				net.minecraft.client.renderer.texture.AbstractTexture.class
			).invoke(tracker, glId, atlas);
			if (releasedTexture != null) {
				// Drops Iris's PBR holder keyed by the old id so its maps are rebuilt once under the new id, not duplicated.
				int releasedId = (Integer) releasedTexture.getClass().getMethod("iris$getGlId").invoke(releasedTexture);
				Class<?> managerClass = Class.forName(
					"net.irisshaders.iris.pbr.texture.PBRTextureManager",
					false,
					SodiumTerrainIntegration.class.getClassLoader()
				);
				Object manager = managerClass.getField("INSTANCE").get(null);
				managerClass.getMethod("onDeleteTexture", int.class).invoke(manager, releasedId);
			}
		} catch (ReflectiveOperationException | RuntimeException exception) {
			if (IRIS_PBR_LOOKUP_FAILURE_LOGGED.compareAndSet(false, true)) {
				AtlasStream.LOGGER.error("Could not register the reduced atlas with Iris", exception);
			}
		}
	}

	public static int irisPbrLookupTextureId(com.mojang.renderpearl.api.textures.GpuTexture bound) {
		try {
			Object source = AtlasRuntimeState.originalAtlasTextureForPbr(bound);
			if (source == null) {
				source = bound;
			}
			return (Integer) source.getClass().getMethod("iris$getGlId").invoke(source);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			if (IRIS_PBR_LOOKUP_FAILURE_LOGGED.compareAndSet(false, true)) {
				AtlasStream.LOGGER.error("Could not resolve the original atlas for Iris PBR lookup", exception);
			}
			return 0;
		}
	}

	// Whether the user has shaders switched on in Iris's config. Valid before Iris has created its pipeline (title screen).
	public static boolean isIrisConfiguredForShaders() {
		if (!FabricLoader.getInstance().isModLoaded("iris")) {
			return false;
		}
		try {
			Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris", false, SodiumTerrainIntegration.class.getClassLoader());
			Object config = irisClass.getMethod("getIrisConfig").invoke(null);
			return config != null && (boolean) config.getClass().getMethod("areShadersEnabled").invoke(config);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			return false;
		}
	}

	public static boolean isIrisShaderPackInUse() {
		if (!FabricLoader.getInstance().isModLoaded("iris")) {
			return false;
		}
		try {
			Method method = irisPackInUseMethod;
			if (method == null) {
				synchronized (SodiumTerrainIntegration.class) {
					method = irisPackInUseMethod;
					if (method == null) {
						Class<?> irisClass = Class.forName(
							"net.irisshaders.iris.Iris",
							false,
							SodiumTerrainIntegration.class.getClassLoader()
						);
						method = irisClass.getMethod("isPackInUseQuick");
						irisPackInUseMethod = method;
					}
				}

			}
			return (boolean) method.invoke(null);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			if (IRIS_STATE_FAILURE_LOGGED.compareAndSet(false, true)) {
				AtlasStream.LOGGER.error(
					"Could not determine whether an Iris shaderpack is active; distance LOD will remain disabled for safety",
					exception
				);
			}
			return true;
		}
	}

	private static void refreshIrisPbrSamplers() {
		if (!isIrisShaderPackInUse()) {
			return;
		}
		try {
			Field activeSamplersField = irisActiveProgramSamplersField;
			Method updateSamplers = irisProgramSamplersUpdateMethod;
			if (activeSamplersField == null || updateSamplers == null) {
				synchronized (SodiumTerrainIntegration.class) {
					activeSamplersField = irisActiveProgramSamplersField;
					updateSamplers = irisProgramSamplersUpdateMethod;
					if (activeSamplersField == null || updateSamplers == null) {
						Class<?> samplersClass = Class.forName(
							"net.irisshaders.iris.gl.program.ProgramSamplers",
							false,
							SodiumTerrainIntegration.class.getClassLoader()
						);
						activeSamplersField = samplersClass.getDeclaredField("active");
						activeSamplersField.setAccessible(true);
						updateSamplers = samplersClass.getMethod("update");
						irisActiveProgramSamplersField = activeSamplersField;
						irisProgramSamplersUpdateMethod = updateSamplers;
					}
				}
			}
			Object activeSamplers = activeSamplersField.get(null);
			if (activeSamplers != null) {
				updateSamplers.invoke(activeSamplers);
				if (ACTIVE_LOD.get() != null
						&& IRIS_SAMPLER_REFRESH_LOGGED.compareAndSet(false, true)) {
					AtlasStream.LOGGER.info(
						"Refreshing Iris terrain PBR samplers for section LOD selection"
					);
				}
			}
		} catch (ReflectiveOperationException | RuntimeException exception) {
			if (IRIS_SAMPLER_REFRESH_FAILURE_LOGGED.compareAndSet(false, true)) {
				AtlasStream.LOGGER.error(
					"Could not refresh Iris terrain PBR samplers for section LOD selection",
					exception
				);
			}
		}
	}

	public static boolean useVirtualTextureForCurrentPass() {
		SectionLod lod = ACTIVE_LOD.get();
		return (lod == SectionLod.HIGH_RESOLUTION || (lod == null && TRANSLUCENT_PASS.get()))
			&& AtlasRuntimeState.isDistancePreviewEnabled()
			&& !isIrisShaderPackInUse();
	}

	public static void scheduleVisibleTerrainRebuild() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.level == null) {
			return;
		}
		try {
			Class<?> rendererClass = Class.forName(
				"net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer",
				false,
				client.getClass().getClassLoader()
			);
			Object renderer = rendererClass.getMethod("instanceNullable").invoke(null);
			if (renderer == null) {
				return;
			}
			int radius = Math.multiplyExact(client.options.getEffectiveRenderDistance(), 16);
			var position = client.player.blockPosition();
			rendererClass.getMethod(
				"scheduleRebuildForBlockArea",
				int.class,
				int.class,
				int.class,
				int.class,
				int.class,
				int.class,
				boolean.class
			).invoke(
				renderer,
				position.getX() - radius,
				client.level.getMinY(),
				position.getZ() - radius,
				position.getX() + radius,
				client.level.getMaxY() - 1,
				position.getZ() + radius,
				true
			);
		} catch (ClassNotFoundException exception) {
			throw new IllegalStateException("Sodium is required to collect terrain texture requests", exception);
		} catch (NoSuchMethodException | IllegalAccessException exception) {
			throw new IllegalStateException("Could not schedule Sodium terrain for virtual-texture collection", exception);
		} catch (InvocationTargetException exception) {
			throw new IllegalStateException("Sodium rejected the terrain rebuild request", exception.getCause());
		}
	}

	public static void reloadTerrainForCutoutChange() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.level == null) {
			throw new IllegalStateException("No active world");
		}
		try {
			Class<?> rendererClass = Class.forName(
				"net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer",
				false, client.getClass().getClassLoader()
			);
			Object renderer = rendererClass.getMethod("instanceNullable").invoke(null);
			if (renderer == null) {
				throw new IllegalStateException("Sodium terrain renderer is not available");
			}
			rendererClass.getMethod("reload").invoke(renderer);
		} catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException exception) {
			throw new IllegalStateException("Could not refresh the Sodium terrain renderer", exception);
		} catch (InvocationTargetException exception) {
			throw new IllegalStateException("Could not refresh the Sodium terrain renderer", exception.getCause());
		}
	}

	// Sodium reuses one render-list object for every terrain pass of a frame, and across frames until the visible set
	// changes. Share the split between terrain passes until the view, config, or near-atlas residency changes.
	private static final Map<Boolean, FilteredListsByLod> FILTER_CACHE = new HashMap<>();
	private static Object filterCacheSource;
	private static Object filterCacheLevel;
	private static Object filterCacheConfig;
	private static boolean filterCacheWarm;
	private static boolean filterCacheNearActive;
	private static final RevisionTracker FILTER_RESIDENCY_REVISION = new RevisionTracker();
	private static int filterCacheCellX;
	private static int filterCacheCellY;
	private static int filterCacheCellZ;

	private static Map<Boolean, FilteredListsByLod> filteredListCache(Object source, Vec3 cameraPosition) {
		Object level = Minecraft.getInstance().level;
		Object config = AtlasStreamClient.getConfig();
		boolean warm = AtlasRuntimeState.isNearAtlasWarm();
		boolean nearActive = AtlasRuntimeState.isNearAtlasActive();
		boolean residencyChanged = FILTER_RESIDENCY_REVISION.update(NearAtlas.residencyRevision());
		int cellX = RegionLodCache.cameraCell(cameraPosition.x);
		int cellY = RegionLodCache.cameraCell(cameraPosition.y);
		int cellZ = RegionLodCache.cameraCell(cameraPosition.z);
		if (source != filterCacheSource
				|| level != filterCacheLevel
				|| config != filterCacheConfig
				|| warm != filterCacheWarm
				|| nearActive != filterCacheNearActive
				|| residencyChanged
				|| cellX != filterCacheCellX
				|| cellY != filterCacheCellY
				|| cellZ != filterCacheCellZ) {
			FILTER_CACHE.clear();
			filterCacheSource = source;
			filterCacheLevel = level;
			filterCacheConfig = config;
			filterCacheWarm = warm;
			filterCacheNearActive = nearActive;
			filterCacheCellX = cellX;
			filterCacheCellY = cellY;
			filterCacheCellZ = cellZ;
		}
		return FILTER_CACHE;
	}

	private static Object filteredRenderLists(
		Object source,
		SectionLod selectedLod,
		Vec3 cameraPosition,
		Map<Boolean, FilteredListsByLod> filteredListsByTranslucency
	) {
		try {
			ClassLoader loader = source.getClass().getClassLoader();
			Class<?> iterableClass = Class.forName(RENDER_LIST_INTERFACE, false, loader);
			InvocationHandler handler = (proxy, method, arguments) -> {
				if (method.getDeclaringClass() == Object.class) {
					return switch (method.getName()) {
						case "toString" -> "AtlasStreamFilteredRenderLists[" + selectedLod + "]";
						case "hashCode" -> System.identityHashCode(proxy);
						case "equals" -> proxy == arguments[0];
						default -> throw new UnsupportedOperationException(method.getName());
					};
				}
				if (!method.getName().equals("iterator")) {
					throw new UnsupportedOperationException(method.getName());
				}
				boolean translucent = arguments != null && arguments.length > 0 && (boolean) arguments[0];
				FilteredListsByLod filteredLists = filteredListsByTranslucency.get(translucent);
				if (filteredLists == null) {
					boolean recording = LodPerformanceStats.isRecording();
					long filterStart = recording ? System.nanoTime() : 0;
					filteredLists = createFilteredListsByLod(
						source,
						translucent,
						cameraPosition
					);
					filteredListsByTranslucency.put(translucent, filteredLists);
					if (recording) {
						LodPerformanceStats.recordFilter(
							System.nanoTime() - filterStart,
							filteredLists.highSectionCount(),
							filteredLists.lowSectionCount()
						);
					}
				}
				return filteredLists.renderLists(selectedLod).iterator();
			};
			return Proxy.newProxyInstance(loader, new Class<?>[] { iterableClass }, handler);
		} catch (ClassNotFoundException exception) {
			throw new IllegalStateException("Sodium render-list API is unavailable", exception);
		}
	}

	private static FilteredListsByLod createFilteredListsByLod(
		Object source,
		boolean translucent,
		Vec3 cameraPosition
	) {
		List<Object> highRenderLists = new ArrayList<>();
		List<Object> lowRenderLists = new ArrayList<>();
		int highSectionCount = 0;
		int lowSectionCount = 0;
		Object iterator = invoke(source, "iterator", new Class<?>[] { boolean.class }, translucent);
		Object level = Minecraft.getInstance().level;
		while ((boolean) invoke(iterator, "hasNext", new Class<?>[0])) {
			Object sourceList = invoke(iterator, "next", new Class<?>[0]);
			ChunkListMethods listMethods = CHUNK_LIST_METHODS.get(sourceList.getClass());
			Object region = listMethods.getRegion(sourceList);
			RegionCoordinates regionOrigin = new RegionCoordinates(
				level,
				(int) invoke(region, "getChunkX", new Class<?>[0]),
				(int) invoke(region, "getChunkY", new Class<?>[0]),
				(int) invoke(region, "getChunkZ", new Class<?>[0])
			);
			RegionLodCache regionLodCache = regionLodCache(regionOrigin, cameraPosition);
			Object highRenderList = newRenderList(region);
			Object lowRenderList = newRenderList(region);
			Object sectionIterator = listMethods.sectionsWithGeometryIterator(sourceList, translucent);
			if (sectionIterator == null) {
				continue;
			}
			SectionIteratorMethods sectionIteratorMethods =
				SECTION_ITERATOR_METHODS.get(sectionIterator.getClass());
			while (sectionIteratorMethods.hasNext(sectionIterator)) {
				int sectionIndex = sectionIteratorMethods.nextByteAsInt(sectionIterator) & 0xFF;
				if (regionLodCache.levelFor(sectionIndex, regionOrigin, cameraPosition)
						== SectionLod.HIGH_RESOLUTION) {
					listMethods.add(highRenderList, sectionIndex);
					highSectionCount++;
				} else {
					listMethods.add(lowRenderList, sectionIndex);
					lowSectionCount++;
				}
			}
			if (listMethods.getSectionsWithGeometryCount(highRenderList) > 0) {
				highRenderLists.add(highRenderList);
			}
			if (listMethods.getSectionsWithGeometryCount(lowRenderList) > 0) {
				lowRenderLists.add(lowRenderList);
			}
		}
		if (lowSectionCount > 0 && SECTION_SPLIT_LOGGED.compareAndSet(false, true)) {
			AtlasStream.LOGGER.info(
				"Split Sodium terrain sections for {} pass: {} high-resolution, {} low-resolution",
				translucent ? "translucent" : "opaque/cutout",
				highSectionCount,
				lowSectionCount
			);
		}
		return new FilteredListsByLod(
			highRenderLists,
			highSectionCount,
			lowRenderLists,
			lowSectionCount
		);
	}

	private static void clearCachedBatches(Object renderLists, Object terrainRenderPass) {
		Set<Object> regions = Collections.newSetFromMap(new IdentityHashMap<>());
		for (boolean translucent : new boolean[] { false, true }) {
			Object iterator = invoke(
				renderLists,
				"iterator",
				new Class<?>[] { boolean.class },
				translucent
			);
			while ((boolean) invoke(iterator, "hasNext", new Class<?>[0])) {
				Object renderList = invoke(iterator, "next", new Class<?>[0]);
				Object region = invoke(renderList, "getRegion", new Class<?>[0]);
				if (regions.add(region)) {
					invoke(
						region,
						"clearCachedBatchFor",
						new Class<?>[] { terrainRenderPass.getClass() },
						terrainRenderPass
					);
				}
			}
		}
	}

	private static void prepare(
		Object renderer,
		Object renderLists,
		Object cameraTransform,
		boolean translucentSorting
	) {
		try {
			Class<?> iterableClass = Class.forName(
				RENDER_LIST_INTERFACE,
				false,
				renderLists.getClass().getClassLoader()
			);
			Method prepareMethod = renderer.getClass().getMethod(
				"prepare",
				iterableClass,
				cameraTransform.getClass(),
				boolean.class
			);
			prepareMethod.invoke(renderer, renderLists, cameraTransform, translucentSorting);
		} catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException exception) {
			throw new IllegalStateException("Could not prepare Sodium's filtered chunk batches", exception);
		} catch (InvocationTargetException exception) {
			throw reflectionFailure("Could not prepare Sodium's filtered chunk batches", exception);
		}
	}

	private static Object newRenderList(Object region) {
		try {
			Class<?> renderListClass = Class.forName(
				RENDER_LIST_CLASS,
				false,
				region.getClass().getClassLoader()
			);
			Constructor<?> constructor = renderListClass.getConstructor(region.getClass());
			return constructor.newInstance(region);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Could not create a filtered Sodium render list", exception);
		}
	}

	private static RegionLodCache regionLodCache(
		RegionCoordinates coordinates,
		Vec3 cameraPosition
	) {
		synchronized (REGION_LEVELS) {
			if (cachedLevel != coordinates.level()) {
				REGION_LEVELS.clear();
				cachedLevel = coordinates.level();
			}
			RegionLodCache cache = REGION_LEVELS.computeIfAbsent(
				coordinates,
				ignored -> new RegionLodCache()
			);
			cache.updateCamera(cameraPosition);
			return cache;
		}
	}

	private static boolean isTranslucent(Object terrainRenderPass) {
		return (boolean) invoke(terrainRenderPass, "isTranslucent", new Class<?>[0]);
	}

	private static Method findRenderMethod(Class<?> rendererClass) {
		for (Method method : rendererClass.getMethods()) {
			if (method.getName().equals("render") && method.getParameterCount() == 11) {
				return method;
			}
		}
		throw new IllegalStateException("Could not find Sodium's chunk renderer method on " + rendererClass.getName());
	}

	private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... arguments) {
		try {
			MethodKey key = new MethodKey(target.getClass(), methodName, List.of(parameterTypes));
			Method method = METHODS.computeIfAbsent(key, methodKey -> {
				try {
					return methodKey.targetClass().getMethod(
						methodKey.name(),
						methodKey.parameterTypes().toArray(Class<?>[]::new)
					);
				} catch (NoSuchMethodException exception) {
					throw new IllegalStateException(
						"Could not find Sodium method " + methodKey.name() + " on "
							+ methodKey.targetClass().getName(),
						exception
					);
				}
			});
			return method.invoke(target, arguments);
		} catch (ReflectiveOperationException exception) {
			throw reflectionFailure("Could not invoke " + methodName, exception);
		}
	}

	private static Object invoke(Method method, Object target, Object[] arguments) {
		try {
			return method.invoke(target, arguments);
		} catch (ReflectiveOperationException exception) {
			throw reflectionFailure("Could not invoke Sodium chunk renderer", exception);
		}
	}

	private static IllegalStateException reflectionFailure(String message, ReflectiveOperationException exception) {
		Throwable cause = exception instanceof InvocationTargetException invocation && invocation.getCause() != null
			? invocation.getCause()
			: exception;
		if (cause instanceof RuntimeException runtimeException) {
			throw runtimeException;
		}
		if (cause instanceof Error error) {
			throw error;
		}
		return new IllegalStateException(message, cause);
	}

	private static MethodHandle virtualHandle(
		Class<?> owner,
		String name,
		Class<?> returnType,
		Class<?>... parameterTypes
	) {
		try {
			Method method = publicMethod(owner, name, parameterTypes);
			MethodHandle handle = MethodHandles.publicLookup().unreflect(method);
			Class<?>[] genericParameters = new Class<?>[parameterTypes.length + 1];
			genericParameters[0] = Object.class;
			System.arraycopy(parameterTypes, 0, genericParameters, 1, parameterTypes.length);
			return handle.asType(MethodType.methodType(returnType, genericParameters));
		} catch (NoSuchMethodException | IllegalAccessException exception) {
			throw new IllegalStateException(
				"Could not access Sodium hot-path method " + name + " on " + owner.getName(),
				exception
			);
		}
	}

	private static Method publicMethod(Class<?> owner, String name, Class<?>[] parameterTypes)
			throws NoSuchMethodException {
		for (Class<?> interfaceType : owner.getInterfaces()) {
			try {
				Method method = publicMethod(interfaceType, name, parameterTypes);
				if (Modifier.isPublic(interfaceType.getModifiers())) {
					return method;
				}
			} catch (NoSuchMethodException ignored) {
			}
		}
		if (Modifier.isPublic(owner.getModifiers())) {
			return owner.getMethod(name, parameterTypes);
		}
		Class<?> superclass = owner.getSuperclass();
		if (superclass != null) {
			return publicMethod(superclass, name, parameterTypes);
		}
		throw new NoSuchMethodException(name);
	}

	private static IllegalStateException methodHandleFailure(String methodName, Throwable cause) {
		if (cause instanceof RuntimeException runtimeException) {
			throw runtimeException;
		}
		if (cause instanceof Error error) {
			throw error;
		}
		return new IllegalStateException("Could not invoke Sodium hot-path method " + methodName, cause);
	}

	private record RegionCoordinates(Object level, int x, int y, int z) {
	}

	private record FilteredListsByLod(
		List<Object> highRenderLists,
		int highSectionCount,
		List<Object> lowRenderLists,
		int lowSectionCount
	) {
		private List<Object> renderLists(SectionLod lod) {
			return lod == SectionLod.HIGH_RESOLUTION ? highRenderLists : lowRenderLists;
		}
	}

	private static final class RegionLodCache {
		private final SectionLod[] levels = new SectionLod[256];
		private final boolean[] evaluated = new boolean[256];
		private int cameraCellX;
		private int cameraCellY;
		private int cameraCellZ;
		private Vec3 evaluationPosition;
		private boolean hasCamera;
		private boolean lastWarm = true;
		private boolean lastNearActive;
		private final RevisionTracker residencyRevision = new RevisionTracker();
		private Object lastConfig;

		private void updateCamera(Vec3 cameraPosition) {
			int nextCellX = cameraCell(cameraPosition.x);
			int nextCellY = cameraCell(cameraPosition.y);
			int nextCellZ = cameraCell(cameraPosition.z);
			boolean warm = AtlasRuntimeState.isNearAtlasWarm();
			boolean warmChanged = warm != lastWarm;
			lastWarm = warm;
			boolean nearActive = AtlasRuntimeState.isNearAtlasActive();
			boolean nearActiveChanged = nearActive != lastNearActive;
			lastNearActive = nearActive;
			boolean residencyChanged = residencyRevision.update(NearAtlas.residencyRevision());
			Object config = AtlasStreamClient.getConfig();
			boolean configChanged = config != lastConfig;
			lastConfig = config;
			if (warmChanged || nearActiveChanged || residencyChanged || configChanged) {
				Arrays.fill(evaluated, false);
			}
			if (hasCamera
					&& cameraCellX == nextCellX
					&& cameraCellY == nextCellY
					&& cameraCellZ == nextCellZ) {
				return;
			}
			cameraCellX = nextCellX;
			cameraCellY = nextCellY;
			cameraCellZ = nextCellZ;
			evaluationPosition = new Vec3(
				cameraCellCenter(cameraCellX),
				cameraCellCenter(cameraCellY),
				cameraCellCenter(cameraCellZ)
			);
			hasCamera = true;
			Arrays.fill(evaluated, false);
		}

		private SectionLod levelFor(
			int localSectionIndex,
			RegionCoordinates regionOrigin,
			Vec3 cameraPosition
		) {
			if (evaluated[localSectionIndex]) {
				return levels[localSectionIndex];
			}
			int sectionX = regionOrigin.x() + ((localSectionIndex >>> 5) & 0x7);
			int sectionY = regionOrigin.y() + (localSectionIndex & 0x3);
			int sectionZ = regionOrigin.z() + ((localSectionIndex >>> 2) & 0x7);
			SectionLod current = levels[localSectionIndex];
			SectionLod next = current == null
				? SectionLodPolicy.initialLevel(
					sectionX,
					sectionY,
					sectionZ,
					evaluationPosition.x,
					evaluationPosition.y,
					evaluationPosition.z,
					AtlasStreamClient.getConfig()
				)
				: SectionLodPolicy.updateLevel(
					current,
					sectionX,
					sectionY,
					sectionZ,
					evaluationPosition.x,
					evaluationPosition.y,
					evaluationPosition.z,
					AtlasStreamClient.getConfig()
				);
			if (next == SectionLod.HIGH_RESOLUTION && !AtlasRuntimeState.isNearAtlasWarm()) {
				return SectionLod.LOW_RESOLUTION;
			}
			if (next == SectionLod.HIGH_RESOLUTION
					&& !AtlasRuntimeState.isNearSectionResolved(SodiumChunkMeshPriority.packSection(sectionX, sectionY, sectionZ))) {
				return SectionLod.LOW_RESOLUTION;
			}
			levels[localSectionIndex] = next;
			evaluated[localSectionIndex] = true;
			return next;
		}

		private static int cameraCell(double coordinate) {
			return (int) Math.floor(coordinate / CAMERA_CACHE_CELL_SIZE);
		}

		private static double cameraCellCenter(int cell) {
			return cell * CAMERA_CACHE_CELL_SIZE + CAMERA_CACHE_CELL_CENTER_OFFSET;
		}
	}

	private static final class ChunkListMethods {
		private final MethodHandle getRegion;
		private final MethodHandle sectionsWithGeometryIterator;
		private final MethodHandle add;
		private final MethodHandle getSectionsWithGeometryCount;

		private ChunkListMethods(Class<?> type) {
			getRegion = virtualHandle(type, "getRegion", Object.class);
			sectionsWithGeometryIterator = virtualHandle(
				type,
				"sectionsWithGeometryIterator",
				Object.class,
				boolean.class
			);
			add = virtualHandle(type, "add", void.class, int.class);
			getSectionsWithGeometryCount = virtualHandle(
				type,
				"getSectionsWithGeometryCount",
				int.class
			);
		}

		private Object getRegion(Object list) {
			try {
				return (Object) getRegion.invokeExact(list);
			} catch (Throwable cause) {
				throw methodHandleFailure("getRegion", cause);
			}
		}

		private Object sectionsWithGeometryIterator(Object list, boolean translucent) {
			try {
				return (Object) sectionsWithGeometryIterator.invokeExact(list, translucent);
			} catch (Throwable cause) {
				throw methodHandleFailure("sectionsWithGeometryIterator", cause);
			}
		}

		private void add(Object list, int sectionIndex) {
			try {
				add.invokeExact(list, sectionIndex);
			} catch (Throwable cause) {
				throw methodHandleFailure("add", cause);
			}
		}

		private int getSectionsWithGeometryCount(Object list) {
			try {
				return (int) getSectionsWithGeometryCount.invokeExact(list);
			} catch (Throwable cause) {
				throw methodHandleFailure("getSectionsWithGeometryCount", cause);
			}
		}
	}

	private static final class SectionIteratorMethods {
		private final MethodHandle hasNext;
		private final MethodHandle nextByteAsInt;

		private SectionIteratorMethods(Class<?> type) {
			hasNext = virtualHandle(type, "hasNext", boolean.class);
			nextByteAsInt = virtualHandle(type, "nextByteAsInt", int.class);
		}

		private boolean hasNext(Object iterator) {
			try {
				return (boolean) hasNext.invokeExact(iterator);
			} catch (Throwable cause) {
				throw methodHandleFailure("section iterator hasNext", cause);
			}
		}

		private int nextByteAsInt(Object iterator) {
			try {
				return (int) nextByteAsInt.invokeExact(iterator);
			} catch (Throwable cause) {
				throw methodHandleFailure("section iterator nextByteAsInt", cause);
			}
		}
	}

	private record MethodKey(Class<?> targetClass, String name, List<Class<?>> parameterTypes) {
	}
}
