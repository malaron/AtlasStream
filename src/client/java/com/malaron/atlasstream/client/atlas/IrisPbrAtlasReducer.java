package com.malaron.atlasstream.client.atlas;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.malaron.atlasstream.AtlasStream;
import com.malaron.atlasstream.atlas.PbrTextureDownsampler;
import com.malaron.atlasstream.client.atlas.AtlasRuntimeState.PixelAverager;
import com.malaron.atlasstream.client.atlas.AtlasRuntimeState.SpriteRegion;
import com.malaron.atlasstream.client.mixin.AbstractTextureAccessor;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

// Shrinks Iris's LabPBR normal/specular atlases with per-channel averaging. The albedo path's
// alpha-weighted averaging would zero PBR data, because alpha is emissive or POM height there.
public final class IrisPbrAtlasReducer {
	private static final int DEFAULT_NORMAL = 0xFF8080FF;
	private static final int DEFAULT_SPECULAR = 0;

	private IrisPbrAtlasReducer() {
	}

	record Images(List<NativeImage> normal, List<NativeImage> specular) implements AutoCloseable {
		@Override
		public void close() {
			normal.forEach(NativeImage::close);
			specular.forEach(NativeImage::close);
		}
	}

	static Images buildRepacked(
		ResourceManager resourceManager,
		List<AtlasRuntimeState.SpriteRegion> regions,
		com.malaron.atlasstream.atlas.LowAtlasPacker.Layout layout,
		int mipLevelCount
	) throws IOException {
		CompletableFuture<List<NativeImage>> specularFuture = CompletableFuture.supplyAsync(() -> {
			try {
				return buildRepackedKind(
					resourceManager, regions, layout, mipLevelCount, "_s",
					DEFAULT_SPECULAR, IrisPbrAtlasReducer::averageSpecular
				);
			} catch (IOException exception) {
				throw new java.io.UncheckedIOException(exception);
			}
		});
		List<NativeImage> normal;
		try {
			normal = buildRepackedKind(
				resourceManager, regions, layout, mipLevelCount, "_n",
				DEFAULT_NORMAL, IrisPbrAtlasReducer::averageNormal
			);
		} catch (IOException | RuntimeException | Error exception) {
			specularFuture.whenComplete((images, failure) -> {
				if (images != null) {
					images.forEach(NativeImage::close);
				}
			});
			throw exception;
		}
		try {
			return new Images(normal, specularFuture.join());
		} catch (java.util.concurrent.CompletionException exception) {
			normal.forEach(NativeImage::close);
			if (exception.getCause() instanceof java.io.UncheckedIOException io) {
				throw io.getCause();
			}
			throw exception;
		}
	}

	private static List<NativeImage> buildRepackedKind(
		ResourceManager resourceManager,
		List<AtlasRuntimeState.SpriteRegion> regions,
		com.malaron.atlasstream.atlas.LowAtlasPacker.Layout layout,
		int mipLevelCount,
		String suffix,
		int defaultPixel,
		PixelAverager averager
	) throws IOException {
		List<NativeImage> images = new ArrayList<>(mipLevelCount);
		NativeImage atlas = new NativeImage(layout.width(), layout.height(), true);
		images.add(atlas);
		atlas.fillRect(0, 0, layout.width(), layout.height(), defaultPixel);
		try {
			regions.parallelStream().forEach(region -> {
				com.malaron.atlasstream.atlas.LowAtlasPacker.Placement placement =
					layout.placements().get(region.identifier());
				Identifier id = Identifier.fromNamespaceAndPath(
					region.identifier().getNamespace(),
					"textures/" + region.identifier().getPath() + suffix + ".png"
				);
				Optional<Resource> resource = resourceManager.getResource(id);
				if (placement == null || resource.isEmpty()) {
					return;
				}
				try (InputStream input = resource.get().open(); NativeImage source = NativeImage.read(input)) {
					for (int destinationY = 0; destinationY < placement.paddedHeight(); destinationY++) {
						int sourceY = (destinationY - placement.paddingY()) * placement.factor();
						for (int destinationX = 0; destinationX < placement.paddedWidth(); destinationX++) {
							int sourceX = (destinationX - placement.paddingX()) * placement.factor();
							atlas.setPixel(
								placement.x() + destinationX,
								placement.y() + destinationY,
								averagePbrPixels(
									source, sourceX, sourceY, region.width(), region.height(),
									placement.factor(), averager
								)
							);
						}
					}
				} catch (IOException | RuntimeException exception) {
					AtlasStream.LOGGER.warn("Leaving PBR sprite {} at its default: {}", id, exception.getMessage());
				}
			});
			List<AtlasRuntimeState.SpriteRegion> destinations = layout.placements().values().stream()
				.map(placement -> {
					AtlasRuntimeState.SpriteRegion source = regions.stream()
						.filter(region -> region.identifier().equals(placement.sprite().identifier()))
						.findFirst()
						.orElseThrow();
					return new AtlasRuntimeState.SpriteRegion(
						source.identifier(),
						placement.x() + placement.paddingX(),
						placement.y() + placement.paddingY(),
						placement.width(), placement.height(),
						placement.paddingX(), placement.paddingY(),
						source.animated()
					);
				})
				.toList();
			for (int level = 1; level < mipLevelCount; level++) {
				images.add(AtlasRuntimeState.createMipLevel(images.get(level - 1), destinations, level, averager));
			}
			return images;
		} catch (RuntimeException | Error exception) {
			images.forEach(NativeImage::close);
			throw exception;
		}
	}

	private static int averagePbrPixels(
		NativeImage source,
		int x,
		int y,
		int width,
		int height,
		int factor,
		PixelAverager averager
	) {
		int[] samples = new int[factor * factor];
		int sample = 0;
		for (int sampleY = y; sampleY < y + factor; sampleY++) {
			int clampedY = Math.clamp(sampleY, 0, height - 1);
			for (int sampleX = x; sampleX < x + factor; sampleX++) {
				samples[sample++] = source.getPixel(
					Math.clamp(sampleX, 0, width - 1),
					clampedY
				);
			}
		}
		return averageSamples(samples, samples.length, averager);
	}

	static Images build(
		ResourceManager resourceManager,
		List<SpriteRegion> regions,
		int lowWidth,
		int lowHeight,
		int mipLevelCount,
		int factor,
		PackTileCache tiles
	) throws IOException {
		// Normal and specular are independent, so overlap them to hide file and decode stalls.
		CompletableFuture<List<NativeImage>> specularFuture = CompletableFuture.supplyAsync(() -> {
			try {
				return buildKind(
					resourceManager, regions, lowWidth, lowHeight, mipLevelCount, factor, tiles,
					"_s", DEFAULT_SPECULAR, IrisPbrAtlasReducer::averageSpecular
				);
			} catch (IOException exception) {
				throw new java.io.UncheckedIOException(exception);
			}
		});
		List<NativeImage> normal;
		try {
			normal = buildKind(
				resourceManager, regions, lowWidth, lowHeight, mipLevelCount, factor, tiles,
				"_n", DEFAULT_NORMAL, IrisPbrAtlasReducer::averageNormal
			);
		} catch (IOException | RuntimeException | Error exception) {
			specularFuture.whenComplete((images, failure) -> {
				if (images != null) {
					images.forEach(NativeImage::close);
				}
			});
			throw exception;
		}
		try {
			return new Images(normal, specularFuture.join());
		} catch (java.util.concurrent.CompletionException exception) {
			normal.forEach(NativeImage::close);
			if (exception.getCause() instanceof java.io.UncheckedIOException io) {
				throw io.getCause();
			}
			throw exception;
		}
	}

	@SuppressWarnings("unchecked")
	public static Map<Integer, Object> loadedHolders() {
		try {
			return (Map<Integer, Object>) irisHolders(irisManager());
		} catch (ReflectiveOperationException | RuntimeException exception) {
			return Map.of();
		}
	}

	private static Object irisManager() throws ReflectiveOperationException {
		Class<?> managerClass = Class.forName(
			"net.irisshaders.iris.pbr.texture.PBRTextureManager",
			false,
			IrisPbrAtlasReducer.class.getClassLoader()
		);
		return managerClass.getField("INSTANCE").get(null);
	}

	@SuppressWarnings("unchecked")
	private static Map<Integer, Object> irisHolders(Object manager) throws ReflectiveOperationException {
		Field field = manager.getClass().getDeclaredField("holders");
		field.setAccessible(true);
		return (Map<Integer, Object>) field.get(manager);
	}

	// Removes Iris's already-loaded PBR holder for the original atlas without closing it, so its textures can be
	// shrunk and re-registered under the reduced atlas's GL id. Returns null if Iris has not loaded one.
	static Object detachHolder(GpuTexture original) {
		try {
			int glId = (Integer) original.getClass().getMethod("iris$getGlId").invoke(original);
			return irisHolders(irisManager()).remove(glId);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			AtlasStream.LOGGER.error("Could not detach Iris PBR holder", exception);
			return null;
		}
	}

	static Object holder(GpuTexture original) {
		try {
			return irisHolders(irisManager()).get(glId(original));
		} catch (ReflectiveOperationException | RuntimeException exception) {
			AtlasStream.LOGGER.error("Could not read Iris PBR holder", exception);
			return null;
		}
	}

	static void rebindHolder(Object holder, GpuTexture... albedos) {
		if (holder == null) {
			AtlasStream.LOGGER.warn("Could not preserve Iris PBR maps because the original holder is unavailable");
			return;
		}
		try {
			Map<Integer, Object> holders = irisHolders(irisManager());
			for (GpuTexture albedo : albedos) {
				holders.put(glId(albedo), holder);
			}
			AtlasStream.LOGGER.info("Rebound untouched Iris PBR maps to the original-layout albedo atlas");
		} catch (ReflectiveOperationException | RuntimeException exception) {
			AtlasStream.LOGGER.error("Could not rebind Iris PBR maps to the original-layout albedo atlas", exception);
		}
	}

	public static String holderSummary(int albedoGlId) {
		try {
			Object holder = irisHolders(irisManager()).get(albedoGlId);
			if (holder == null) {
				return "missing";
			}
			Class<?> holderInterface = Class.forName(
				"net.irisshaders.iris.pbr.texture.PBRTextureHolder",
				false,
				IrisPbrAtlasReducer.class.getClassLoader()
			);
			Object normal = holderInterface.getMethod("normalTexture").invoke(holder);
			Object specular = holderInterface.getMethod("specularTexture").invoke(holder);
			return "present normal=" + textureSummary(normal) + " specular=" + textureSummary(specular);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			return "error:" + exception.getClass().getSimpleName();
		}
	}

	private static String textureSummary(Object value) {
		if (!(value instanceof AbstractTexture texture)) {
			return "missing";
		}
		GpuTexture gpuTexture = ((AbstractTextureAccessor) texture).atlasStream$getTexture();
		if (gpuTexture == null) {
			return "unbound";
		}
		try {
			int glId = (Integer) gpuTexture.getClass().getMethod("iris$getGlId").invoke(gpuTexture);
			return glId + "@" + gpuTexture.getWidth(0) + "x" + gpuTexture.getHeight(0);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			return "error";
		}
	}

	// Returns the PBR atlas objects that now own reduced textures, so Iris's full-size animation uploads can be skipped.
	static List<Object> apply(Object holder, GpuTexture reducedAlbedo, Images images) {
		List<Object> reduced = new ArrayList<>();
		if (holder != null) {
			try {
				Class<?> holderInterface = Class.forName(
					"net.irisshaders.iris.pbr.texture.PBRTextureHolder",
					false,
					IrisPbrAtlasReducer.class.getClassLoader()
				);
				try {
					swap(holderInterface.getMethod("normalTexture").invoke(holder), images.normal(), "normal", reduced);
					swap(holderInterface.getMethod("specularTexture").invoke(holder), images.specular(), "specular", reduced);
				} finally {
					irisHolders(irisManager()).put(glId(reducedAlbedo), holder);
				}
			} catch (ReflectiveOperationException | RuntimeException exception) {
				AtlasStream.LOGGER.error("Could not reduce Iris PBR atlases", exception);
			}
		}
		if (reduced.isEmpty()) {
			// Iris had no full-size maps to shrink (its load was skipped, or not done yet): provide the reduced ones directly.
			try {
				AbstractTexture normal = createReduced("normal", images.normal());
				AbstractTexture specular = createReduced("specular", images.specular());
				registerHolder(glId(reducedAlbedo), normal, specular);
				reduced.add(normal);
				reduced.add(specular);
				AtlasStream.LOGGER.info("Registered reduced Iris PBR maps directly (Iris full-size load was skipped or absent)");
			} catch (ReflectiveOperationException | RuntimeException exception) {
				AtlasStream.LOGGER.error("Could not register reduced Iris PBR maps", exception);
			}
		}
		return reduced;
	}

	private static int glId(GpuTexture texture) throws ReflectiveOperationException {
		return (Integer) texture.getClass().getMethod("iris$getGlId").invoke(texture);
	}

	private static AbstractTexture createReduced(String label, List<NativeImage> mips) {
		var device = RenderSystem.getDevice();
		GpuTexture texture = device.createTexture(
			"AtlasStream reduced PBR " + label + " atlas",
			GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING
				| GpuTexture.USAGE_RENDER_ATTACHMENT,
			com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM,
			mips.get(0).getWidth(),
			mips.get(0).getHeight(),
			1,
			mips.size()
		);
		GpuTextureView view = null;
		try {
			view = device.createTextureView(texture);
			var encoder = device.createCommandEncoder();
			for (int level = 0; level < mips.size(); level++) {
				encoder.writeToTexture(texture, mips.get(level), level, 0, 0, 0);
			}
			encoder.submit();
		} catch (RuntimeException | Error exception) {
			if (view != null) {
				view.close();
			}
			texture.close();
			throw exception;
		}
		AbstractTexture wrapper = new ReducedPbrTexture();
		((AbstractTextureAccessor) wrapper).atlasStream$setTexture(texture);
		((AbstractTextureAccessor) wrapper).atlasStream$setTextureView(view);
		return wrapper;
	}

	private static final class ReducedPbrTexture extends AbstractTexture {
	}

	private static void swap(Object pbrAtlas, List<NativeImage> mips, String label, List<Object> reduced) {
		if (!(pbrAtlas instanceof AbstractTexture texture)) {
			AtlasStream.LOGGER.warn("Iris {} PBR texture is not available for reduction", label);
			return;
		}
		AbstractTextureAccessor accessor = (AbstractTextureAccessor) texture;
		GpuTexture old = accessor.atlasStream$getTexture();
		GpuTextureView oldView = accessor.atlasStream$getTextureView();
		if (old == null) {
			AtlasStream.LOGGER.warn("Iris {} atlas is not available for reduction", label);
			return;
		}
		var device = RenderSystem.getDevice();
		GpuTexture replacement = device.createTexture(
			"AtlasStream reduced PBR " + label + " atlas",
			GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING
				| GpuTexture.USAGE_RENDER_ATTACHMENT,
			old.getFormat(),
			mips.get(0).getWidth(),
			mips.get(0).getHeight(),
			old.getDepthOrLayers(),
			mips.size()
		);
		GpuTextureView replacementView = null;
		try {
			replacementView = device.createTextureView(replacement);
			var encoder = device.createCommandEncoder();
			for (int level = 0; level < mips.size(); level++) {
				encoder.writeToTexture(replacement, mips.get(level), level, 0, 0, 0);
			}
			encoder.submit();
		} catch (RuntimeException | Error exception) {
			if (replacementView != null) {
				replacementView.close();
			}
			replacement.close();
			throw exception;
		}
		accessor.atlasStream$setTexture(replacement);
		accessor.atlasStream$setTextureView(replacementView);
		if (oldView != null) {
			oldView.close();
		}
		old.close();
		reduced.add(pbrAtlas);
		AtlasStream.LOGGER.info(
			"Reduced Iris {} atlas to {}x{} with {} mip levels",
			label, replacement.getWidth(0), replacement.getHeight(0), mips.size()
		);
	}

	private static List<NativeImage> buildKind(
		ResourceManager resourceManager,
		List<SpriteRegion> regions,
		int lowWidth,
		int lowHeight,
		int mipLevelCount,
		int factor,
		PackTileCache tiles,
		String suffix,
		int defaultPixel,
		PixelAverager averager
	) throws IOException {
		List<NativeImage> mips = new ArrayList<>(mipLevelCount);
		try {
			NativeImage atlas = new NativeImage(lowWidth, lowHeight, true);
			mips.add(atlas);
			for (int y = 0; y < lowHeight; y++) {
				for (int x = 0; x < lowWidth; x++) {
					atlas.setPixel(x, y, defaultPixel);
				}
			}
			// Sprites occupy disjoint atlas regions, so they can be decoded and averaged in parallel.
			regions.parallelStream().forEach(region -> {
				Identifier id = Identifier.fromNamespaceAndPath(
					region.identifier().getNamespace(),
					"textures/" + region.identifier().getPath() + suffix + ".png"
				);
				Optional<Resource> resource = resourceManager.getResource(id);
				if (resource.isEmpty()) {
					return;
				}
				PackTileCache.Pack tilePack = tiles.forPack(resource.get().sourcePackId());
				String tileKey = PackTileCache.key(suffix.charAt(1), region);
				int[] cachedTile = tilePack == null ? null : tilePack.get(tileKey, AtlasRuntimeState.tilePixels(region, factor));
				if (cachedTile != null) {
					writeSprite(atlas, cachedTile, region, factor);
					tiles.hits.incrementAndGet();
					return;
				}
				tiles.decoded.incrementAndGet();
				try (InputStream input = resource.get().open(); NativeImage source = NativeImage.read(input)) {
					int[] tile = reduceSprite(source, region, factor, new int[factor * factor], averager);
					writeSprite(atlas, tile, region, factor);
					if (tilePack != null) {
						tilePack.put(tileKey, tile);
					}
				} catch (IOException | RuntimeException exception) {
					AtlasStream.LOGGER.warn("Leaving PBR sprite {} at its default: {}", id, exception.getMessage());
				}
			});
			List<SpriteRegion> lowRegions = regions.stream()
				.map(region -> AtlasRuntimeState.scaleRegion(region, factor))
				.toList();
			for (int level = 1; level < mipLevelCount; level++) {
				mips.add(AtlasRuntimeState.createMipLevel(mips.get(level - 1), lowRegions, level, averager));
			}
			return mips;
		} catch (RuntimeException | Error exception) {
			mips.forEach(NativeImage::close);
			throw exception;
		}
	}

	private static void writeSprite(
		NativeImage atlas,
		int[] tile,
		SpriteRegion region,
		int factor
	) {
		int firstLowX = -region.paddingX() / factor;
		int lastLowX = (region.width() + region.paddingX()) / factor - 1;
		int firstLowY = -region.paddingY() / factor;
		int lastLowY = (region.height() + region.paddingY()) / factor - 1;
		int tileWidth = lastLowX - firstLowX + 1;
		for (int lowY = firstLowY; lowY <= lastLowY; lowY++) {
			int targetY = region.y() / factor + lowY;
			if (targetY < 0 || targetY >= atlas.getHeight()) {
				continue;
			}
			for (int lowX = firstLowX; lowX <= lastLowX; lowX++) {
				int targetX = region.x() / factor + lowX;
				if (targetX < 0 || targetX >= atlas.getWidth()) {
					continue;
				}
				atlas.setPixel(targetX, targetY, tile[(lowY - firstLowY) * tileWidth + (lowX - firstLowX)]);
			}
		}
	}

	// The reduced pixels of a sprite including its padding, row by row; independent of where it sits in the atlas.
	private static int[] reduceSprite(
		NativeImage source,
		SpriteRegion region,
		int factor,
		int[] samples,
		PixelAverager averager
	) {
		int sourceWidth = source.getWidth();
		// Frames of an animated map are stacked vertically; the first frame is used.
		int frameHeight = (int) Math.min(
			source.getHeight(),
			Math.max(1L, (long) sourceWidth * region.height() / region.width())
		);
		int firstLowX = -region.paddingX() / factor;
		int lastLowX = (region.width() + region.paddingX()) / factor - 1;
		int firstLowY = -region.paddingY() / factor;
		int lastLowY = (region.height() + region.paddingY()) / factor - 1;
		int tileWidth = lastLowX - firstLowX + 1;
		int[] tile = new int[tileWidth * (lastLowY - firstLowY + 1)];
		for (int lowY = firstLowY; lowY <= lastLowY; lowY++) {
			for (int lowX = firstLowX; lowX <= lastLowX; lowX++) {
				int count = 0;
				for (int sampleY = 0; sampleY < factor; sampleY++) {
					int relativeY = Math.clamp(lowY * factor + sampleY, 0, region.height() - 1);
					int sourceY = relativeY * frameHeight / region.height();
					for (int sampleX = 0; sampleX < factor; sampleX++) {
						int relativeX = Math.clamp(lowX * factor + sampleX, 0, region.width() - 1);
						int sourceX = relativeX * sourceWidth / region.width();
						samples[count++] = source.getPixel(sourceX, sourceY);
					}
				}
				tile[(lowY - firstLowY) * tileWidth + (lowX - firstLowX)] = averageSamples(samples, count, averager);
			}
		}
		return tile;
	}

	// Box-filters factor*factor samples by averaging pairs-of-four down to a single pixel.
	private static int averageSamples(int[] samples, int count, PixelAverager averager) {
		int size = count;
		while (size > 1) {
			int next = 0;
			for (int index = 0; index + 3 < size; index += 4) {
				samples[next++] = averager.average(samples[index], samples[index + 1], samples[index + 2], samples[index + 3]);
			}
			size = next;
		}
		return samples[0];
	}

	static int averageNormal(int first, int second, int third, int fourth) {
		return PbrTextureDownsampler.averageLabPbrNormalPixels(new int[] {first, second, third, fourth}, 4);
	}

	static int averageSpecular(int first, int second, int third, int fourth) {
		return PbrTextureDownsampler.averageLabPbrSpecularPixels(new int[] {first, second, third, fourth}, 4);
	}

	// Registers PBR maps for an albedo texture Iris did not create, via a holder proxy (its implementation is private).
	static void registerHolder(int albedoGlId, AbstractTexture normal, AbstractTexture specular) {
		try {
			Class<?> holderInterface = Class.forName(
				"net.irisshaders.iris.pbr.texture.PBRTextureHolder",
				false,
				IrisPbrAtlasReducer.class.getClassLoader()
			);
			Object holder = java.lang.reflect.Proxy.newProxyInstance(
				holderInterface.getClassLoader(),
				new Class<?>[] {holderInterface},
				(proxy, method, arguments) -> switch (method.getName()) {
					case "normalTexture" -> normal;
					case "specularTexture" -> specular;
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == arguments[0];
					default -> "AtlasStream sparse PBR holder";
				}
			);
			irisHolders(irisManager()).put(albedoGlId, holder);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			AtlasStream.LOGGER.error("Could not register sparse PBR maps with Iris", exception);
		}
	}

	// Makes Iris discard its PBR holders so it loads full-size maps again on demand.
	static void resetIrisHolders() {
		try {
			Object manager = irisManager();
			manager.getClass().getMethod("clear").invoke(manager);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			AtlasStream.LOGGER.warn("Could not reset Iris PBR holders", exception);
		}
	}

	static void unregisterHolder(int albedoGlId) {
		try {
			irisHolders(irisManager()).remove(albedoGlId);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			AtlasStream.LOGGER.warn("Could not unregister sparse PBR maps from Iris", exception);
		}
	}
}
