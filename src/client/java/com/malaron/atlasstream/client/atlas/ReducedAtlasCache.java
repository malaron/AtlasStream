package com.malaron.atlasstream.client.atlas;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import com.malaron.atlasstream.AtlasStream;

import com.mojang.blaze3d.platform.NativeImage;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Disk cache of the reduced albedo and PBR atlas images. An entry is only ever found through a fingerprint of
 * everything that produced it (pack stack, atlas layout and build settings), so changing packs never reuses stale
 * data. Anything unexpected falls back to a normal build.
 */
final class ReducedAtlasCache {
	private static final int MAGIC = 0x4C4F4C41;
	private static final int FORMAT = 1;
	// Bump whenever the reduced atlas build output changes for the same inputs.
	private static final int BUILD_VERSION = 3;
	private static final int MAX_IMAGES_PER_KIND = 16;
	private static final int HEADER_BYTES = 20;

	private ReducedAtlasCache() {
	}

	record Entry(List<NativeImage> albedo, List<NativeImage> normal, List<NativeImage> specular) {
		void close() {
			albedo.forEach(NativeImage::close);
			normal.forEach(NativeImage::close);
			specular.forEach(NativeImage::close);
		}
	}

	private static Path directory() {
		return FabricLoader.getInstance().getGameDir().resolve("cache").resolve("atlasstream");
	}

	// Null when the inputs cannot be fingerprinted reliably (for example a server resource pack), which disables caching.
	static String fingerprint(
		ResourceManager resourceManager,
		List<AtlasRuntimeState.SpriteRegion> regions,
		int atlasWidth,
		int atlasHeight,
		int mipLevelCount,
		int targetResolution,
		boolean reducePbr,
		boolean debugTint
	) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			add(digest, "v" + BUILD_VERSION + "/" + FORMAT);
			add(digest, atlasWidth + "x" + atlasHeight + "x" + mipLevelCount + "/" + targetResolution
				+ "/" + reducePbr + "/" + debugTint);
			for (AtlasRuntimeState.SpriteRegion region : regions) {
				add(digest, region.identifier() + ":" + region.x() + "," + region.y() + "," + region.width() + ","
					+ region.height() + "," + region.paddingX() + "," + region.paddingY() + "," + region.animated());
			}
			List<String> mods = FabricLoader.getInstance().getAllMods().stream()
				.map(ModContainer::getMetadata)
				.map(metadata -> metadata.getId() + "@" + metadata.getVersion().getFriendlyString())
				.sorted()
				.toList();
			add(digest, String.join(";", mods));
			Path packFolder = FabricLoader.getInstance().getGameDir().resolve("resourcepacks");
			List<PackResources> packs = resourceManager.listPacks().toList();
			for (PackResources pack : packs) {
				String id = pack.packId();
				add(digest, "pack:" + id);
				if (id.startsWith("file/")) {
					if (!addPackFiles(digest, packFolder.resolve(id.substring("file/".length())))) {
						return null;
					}
				} else if (!"vanilla".equals(id) && !FabricLoader.getInstance().isModLoaded(id)) {
					return null;
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException | IOException | RuntimeException exception) {
			AtlasStream.LOGGER.warn("Reduced atlas cache disabled: {}", exception.toString());
			return null;
		}
	}

	private static void add(MessageDigest digest, String value) {
		digest.update(value.getBytes(StandardCharsets.UTF_8));
		digest.update((byte) 0);
	}

	static boolean addPackFiles(MessageDigest digest, Path pack) throws IOException {
		if (!Files.exists(pack)) {
			return false;
		}
		if (Files.isDirectory(pack)) {
			List<Path> files;
			try (Stream<Path> walk = Files.walk(pack)) {
				files = walk.filter(Files::isRegularFile).sorted().toList();
			}
			for (Path file : files) {
				add(digest, pack.relativize(file) + ":" + Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis());
			}
		} else {
			add(digest, Files.size(pack) + ":" + Files.getLastModifiedTime(pack).toMillis());
		}
		return true;
	}

	static Entry load(String key, int width, int height, int mipLevels, boolean expectPbr) {
		Path file = directory().resolve(key + ".bin");
		if (!Files.isRegularFile(file)) {
			return null;
		}
		List<NativeImage> albedo = new ArrayList<>();
		List<NativeImage> normal = new ArrayList<>();
		List<NativeImage> specular = new ArrayList<>();
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
			readFully(channel, header);
			header.flip();
			int albedoCount = header.getInt(8);
			int normalCount = header.getInt(12);
			int specularCount = header.getInt(16);
			if (header.getInt(0) != MAGIC || header.getInt(4) != FORMAT
					|| albedoCount != mipLevels
					|| normalCount != (expectPbr ? mipLevels : 0)
					|| specularCount != (expectPbr ? mipLevels : 0)
					|| albedoCount > MAX_IMAGES_PER_KIND) {
				throw new IOException("unexpected header");
			}
			int total = albedoCount + normalCount + specularCount;
			ByteBuffer dimensions = ByteBuffer.allocate(total * 8).order(ByteOrder.LITTLE_ENDIAN);
			readFully(channel, dimensions);
			dimensions.flip();
			long expectedSize = HEADER_BYTES + (long) total * 8 + 8;
			int[] widths = new int[total];
			int[] heights = new int[total];
			for (int i = 0; i < total; i++) {
				widths[i] = dimensions.getInt();
				heights[i] = dimensions.getInt();
				if (widths[i] <= 0 || heights[i] <= 0 || (long) widths[i] * heights[i] * 4L > Integer.MAX_VALUE) {
					throw new IOException("invalid image size");
				}
				expectedSize += (long) widths[i] * heights[i] * 4L;
			}
			if (widths[0] != width || heights[0] != height || channel.size() != expectedSize) {
				throw new IOException("size mismatch");
			}
			CRC32 crc = new CRC32();
			for (int i = 0; i < total; i++) {
				NativeImage image = new NativeImage(widths[i], heights[i], false);
				(i < albedoCount ? albedo : i < albedoCount + normalCount ? normal : specular).add(image);
				ByteBuffer pixels = image.getPixelBytes();
				readFully(channel, pixels);
				pixels.flip();
				crc.update(pixels);
			}
			ByteBuffer trailer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
			readFully(channel, trailer);
			if (trailer.getLong(0) != crc.getValue()) {
				throw new IOException("checksum mismatch");
			}
		} catch (IOException | RuntimeException exception) {
			AtlasStream.LOGGER.warn("Discarding reduced atlas cache entry {}: {}", file.getFileName(), exception.toString());
			new Entry(albedo, normal, specular).close();
			try {
				Files.deleteIfExists(file);
			} catch (IOException ignored) {
			}
			return null;
		}
		try {
			Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
		} catch (IOException ignored) {
		}
		return new Entry(albedo, normal, specular);
	}

	private static void readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
		while (buffer.hasRemaining()) {
			if (channel.read(buffer) < 0) {
				throw new IOException("unexpected end of file");
			}
		}
	}

	// Never throws: a failed write only means the next load builds from scratch again.
	static void store(String key, List<NativeImage> albedo, IrisPbrAtlasReducer.Images pbr) {
		Path directory = directory();
		Path temp = directory.resolve(key + ".tmp");
		Path target = directory.resolve(key + ".bin");
		List<NativeImage> normal = pbr == null ? List.of() : pbr.normal();
		List<NativeImage> specular = pbr == null ? List.of() : pbr.specular();
		List<NativeImage> all = new ArrayList<>(albedo);
		all.addAll(normal);
		all.addAll(specular);
		try {
			Files.createDirectories(directory);
			long size = HEADER_BYTES + all.size() * 8L + 8;
			for (NativeImage image : all) {
				size += (long) image.getWidth() * image.getHeight() * 4L;
			}
			if (!DiskCacheBudget.hasSpaceFor(directory, size)) {
				return;
			}
			CRC32 crc = new CRC32();
			try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
				ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES + all.size() * 8).order(ByteOrder.LITTLE_ENDIAN);
				header.putInt(MAGIC).putInt(FORMAT).putInt(albedo.size()).putInt(normal.size()).putInt(specular.size());
				for (NativeImage image : all) {
					header.putInt(image.getWidth()).putInt(image.getHeight());
				}
				header.flip();
				writeFully(channel, header);
				for (NativeImage image : all) {
					ByteBuffer pixels = image.getPixelBytes();
					crc.update(pixels.duplicate());
					writeFully(channel, pixels);
				}
				ByteBuffer trailer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
				trailer.putLong(crc.getValue()).flip();
				writeFully(channel, trailer);
			}
			try {
				Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
			}
			AtlasStream.LOGGER.info("Saved reduced atlas cache entry ({} MiB)", size >> 20);
			DiskCacheBudget.prune(directory(), target);
		} catch (IOException | RuntimeException exception) {
			AtlasStream.LOGGER.warn("Could not save the reduced atlas cache: {}", exception.toString());
			try {
				Files.deleteIfExists(temp);
			} catch (IOException ignored) {
			}
		}
	}

	private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
		while (buffer.hasRemaining()) {
			channel.write(buffer);
		}
	}

}
