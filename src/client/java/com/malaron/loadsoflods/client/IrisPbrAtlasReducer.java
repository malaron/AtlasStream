package com.malaron.loadsoflods.client;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.malaron.loadsoflods.LoadsOfLods;
import com.malaron.loadsoflods.cache.PbrTextureDownsampler;
import com.malaron.loadsoflods.client.AtlasPreviewState.PixelAverager;
import com.malaron.loadsoflods.client.AtlasPreviewState.SpriteRegion;
import com.malaron.loadsoflods.client.mixin.AbstractTextureAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

// Shrinks Iris's LabPBR normal/specular atlases with per-channel averaging. The albedo path's
// alpha-weighted averaging would zero PBR data, because alpha is emissive or POM height there.
final class IrisPbrAtlasReducer {
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

	static Images build(
		ResourceManager resourceManager,
		List<SpriteRegion> regions,
		int lowWidth,
		int lowHeight,
		int mipLevelCount,
		int factor
	) throws IOException {
		List<NativeImage> normal = buildKind(
			resourceManager, regions, lowWidth, lowHeight, mipLevelCount, factor,
			"_n", DEFAULT_NORMAL, IrisPbrAtlasReducer::averageNormal
		);
		try {
			List<NativeImage> specular = buildKind(
				resourceManager, regions, lowWidth, lowHeight, mipLevelCount, factor,
				"_s", DEFAULT_SPECULAR, IrisPbrAtlasReducer::averageSpecular
			);
			return new Images(normal, specular);
		} catch (IOException | RuntimeException | Error exception) {
			normal.forEach(NativeImage::close);
			throw exception;
		}
	}

	@SuppressWarnings("unchecked")
	static Map<Integer, Object> loadedHolders() {
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
			LoadsOfLods.LOGGER.error("Could not detach Iris PBR holder", exception);
			return null;
		}
	}

	// Returns the PBR atlas objects that now own reduced textures, so Iris's full-size animation uploads can be skipped.
	static List<Object> apply(Object holder, GpuTexture reducedAlbedo, Images images, int factor) {
		List<Object> reduced = new ArrayList<>();
		if (holder == null) {
			LoadsOfLods.LOGGER.info("Iris had not loaded PBR maps for the block atlas; nothing to reduce");
			return reduced;
		}
		try {
			Class<?> holderInterface = Class.forName(
				"net.irisshaders.iris.pbr.texture.PBRTextureHolder",
				false,
				IrisPbrAtlasReducer.class.getClassLoader()
			);
			try {
				swap(holderInterface.getMethod("normalTexture").invoke(holder), images.normal(), factor, "normal", reduced);
				swap(holderInterface.getMethod("specularTexture").invoke(holder), images.specular(), factor, "specular", reduced);
			} finally {
				int newId = (Integer) reducedAlbedo.getClass().getMethod("iris$getGlId").invoke(reducedAlbedo);
				irisHolders(irisManager()).put(newId, holder);
			}
		} catch (ReflectiveOperationException | RuntimeException exception) {
			LoadsOfLods.LOGGER.error("Could not reduce Iris PBR atlases", exception);
		}
		return reduced;
	}

	private static void swap(Object pbrAtlas, List<NativeImage> mips, int factor, String label, List<Object> reduced) {
		if (!(pbrAtlas instanceof AbstractTexture texture)) {
			LoadsOfLods.LOGGER.warn("Iris {} PBR texture is not available for reduction", label);
			return;
		}
		AbstractTextureAccessor accessor = (AbstractTextureAccessor) texture;
		GpuTexture old = accessor.loadsOfLods$getTexture();
		GpuTextureView oldView = accessor.loadsOfLods$getTextureView();
		if (old == null || old.getWidth(0) / factor != mips.get(0).getWidth()
				|| old.getHeight(0) / factor != mips.get(0).getHeight()) {
			LoadsOfLods.LOGGER.warn("Iris {} atlas size does not match the albedo atlas; leaving it full resolution", label);
			return;
		}
		var device = RenderSystem.getDevice();
		GpuTexture replacement = device.createTexture(
			"Loads of Lods reduced PBR " + label + " atlas",
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
		accessor.loadsOfLods$setTexture(replacement);
		accessor.loadsOfLods$setTextureView(replacementView);
		if (oldView != null) {
			oldView.close();
		}
		old.close();
		reduced.add(pbrAtlas);
		LoadsOfLods.LOGGER.info(
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
			int[] samples = new int[factor * factor];
			for (SpriteRegion region : regions) {
				Identifier id = Identifier.fromNamespaceAndPath(
					region.identifier().getNamespace(),
					"textures/" + region.identifier().getPath() + suffix + ".png"
				);
				Optional<Resource> resource = resourceManager.getResource(id);
				if (resource.isEmpty()) {
					continue;
				}
				try (InputStream input = resource.get().open(); NativeImage source = NativeImage.read(input)) {
					writeSprite(atlas, source, region, factor, samples, averager);
				} catch (IOException | RuntimeException exception) {
					LoadsOfLods.LOGGER.warn("Leaving PBR sprite {} at its default: {}", id, exception.getMessage());
				}
			}
			List<SpriteRegion> lowRegions = regions.stream()
				.map(region -> AtlasPreviewState.scaleRegion(region, factor))
				.toList();
			for (int level = 1; level < mipLevelCount; level++) {
				mips.add(AtlasPreviewState.createMipLevel(mips.get(level - 1), lowRegions, level, averager));
			}
			return mips;
		} catch (RuntimeException | Error exception) {
			mips.forEach(NativeImage::close);
			throw exception;
		}
	}

	private static void writeSprite(
		NativeImage atlas,
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
				atlas.setPixel(targetX, targetY, averageSamples(samples, count, averager));
			}
		}
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

	private static int averageNormal(int first, int second, int third, int fourth) {
		return PbrTextureDownsampler.averageLabPbrNormalPixels(new int[] {first, second, third, fourth}, 4);
	}

	private static int averageSpecular(int first, int second, int third, int fourth) {
		return PbrTextureDownsampler.averageLabPbrSpecularPixels(new int[] {first, second, third, fourth}, 4);
	}
}
