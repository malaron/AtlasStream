package com.malaron.atlasstream.client.atlas;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import com.malaron.atlasstream.AtlasStream;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * Per-pack cache of reduced sprite tiles. A reduced tile depends only on the sprite's source pixels and size, so
 * it is stored under the pack that provided the texture. Changing the pack stack then only reprocesses the sprites
 * of packs that changed. Anything unexpected falls back to decoding the sprite.
 */
final class PackTileCache {
	private static final int MAGIC = 0x4C4F4C54;
	private static final int FORMAT = 1;
	// Bump whenever the reduced tile output changes for the same inputs.
	private static final int TILE_VERSION = 1;
	private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	private final int factor;
	private final Map<String, Optional<Pack>> packs = new ConcurrentHashMap<>();
	private final Map<String, AtomicInteger> uncacheable = new ConcurrentHashMap<>();
	final AtomicInteger hits = new AtomicInteger();
	final AtomicInteger decoded = new AtomicInteger();

	PackTileCache(int factor) {
		this.factor = factor;
	}

	static String key(char kind, AtlasRuntimeState.SpriteRegion region) {
		return kind + ":" + region.identifier() + ":" + region.width() + "," + region.height() + ","
			+ region.paddingX() + "," + region.paddingY();
	}

	// Null when the pack cannot be fingerprinted (for example a server pack), so its sprites are always decoded.
	Pack forPack(String packId) {
		Optional<Pack> pack = packs.computeIfAbsent(packId, id -> Optional.ofNullable(open(id)));
		if (pack.isEmpty()) {
			uncacheable.computeIfAbsent(packId, ignored -> new AtomicInteger()).incrementAndGet();
			return null;
		}
		return pack.get();
	}

	private Pack open(String packId) {
		try {
			String fingerprint = fingerprint(packId);
			if (fingerprint == null) {
				return null;
			}
			return new Pack(directory().resolve(fingerprint + "-f" + factor + ".tiles"));
		} catch (IOException | RuntimeException exception) {
			AtlasStream.LOGGER.warn("Tile cache disabled for pack {}: {}", packId, exception.toString());
			return null;
		}
	}

	private static Path directory() {
		return FabricLoader.getInstance().getGameDir().resolve("cache").resolve("atlasstream").resolve("tiles");
	}

	private static String fingerprint(String packId) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(("t" + TILE_VERSION + "/" + FORMAT + "/" + packId).getBytes(StandardCharsets.UTF_8));
			digest.update((byte) 0);
			if (packId.startsWith("file/")) {
				Path pack = FabricLoader.getInstance().getGameDir().resolve("resourcepacks")
					.resolve(packId.substring("file/".length()));
				if (!ReducedAtlasCache.addPackFiles(digest, pack)) {
					return null;
				}
			} else {
				String modId = "vanilla".equals(packId) ? "minecraft" : packId;
				Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer(modId);
				if (mod.isEmpty()) {
					return null;
				}
				digest.update(mod.get().getMetadata().getVersion().getFriendlyString().getBytes(StandardCharsets.UTF_8));
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException exception) {
			return null;
		}
	}

	// Writes new tiles back (only for packs that gained some) and logs how the build used the cache.
	void save(String label) {
		int written = 0;
		for (Optional<Pack> pack : packs.values()) {
			if (pack.isPresent() && pack.get().save()) {
				written++;
			}
		}
		for (Optional<Pack> pack : packs.values()) {
			pack.ifPresent(Pack::close);
		}
		try {
			DiskCacheBudget.prune(directory().getParent(), null);
		} catch (IOException | RuntimeException exception) {
			AtlasStream.LOGGER.warn("Could not prune the tile cache: {}", exception.toString());
		}
		StringBuilder skipped = new StringBuilder();
		uncacheable.forEach((id, count) -> skipped.append(skipped.isEmpty() ? "" : ", ").append(id).append(" x").append(count));
		AtlasStream.LOGGER.info(
			"Tile cache ({}): {} sprites from cached tiles, {} decoded, {} pack file(s) updated{}",
			label, hits.get(), decoded.get(), written, skipped.isEmpty() ? "" : "; uncacheable packs: " + skipped
		);
	}

	final class Pack {
		private record Span(long start, long end, long pixels, int count) {
		}

		private final Path file;
		private final Map<String, Span> index = new LinkedHashMap<>();
		private final ConcurrentHashMap<String, int[]> added = new ConcurrentHashMap<>();
		private Arena arena;
		private MemorySegment data;

		private Pack(Path file) throws IOException {
			this.file = file;
			if (Files.isRegularFile(file)) {
				load();
			}
		}

		private void load() throws IOException {
			Arena opened = Arena.ofShared();
			try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
				long size = channel.size();
				if (size < 20) {
					throw new IOException("too small");
				}
				MemorySegment segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, size, opened);
				if (segment.get(INT, 0) != MAGIC || segment.get(INT, 4) != FORMAT) {
					throw new IOException("bad header");
				}
				CRC32 crc = new CRC32();
				crc.update(segment.asSlice(0, size - 8).asByteBuffer());
				if (crc.getValue() != segment.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), size - 8)) {
					throw new IOException("checksum mismatch");
				}
				int entries = segment.get(INT, 8);
				long position = 12;
				Map<String, Span> parsed = new LinkedHashMap<>();
				for (int entry = 0; entry < entries; entry++) {
					long start = position;
					int keyLength = segment.get(INT, position);
					byte[] key = new byte[keyLength];
					MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, position + 4, key, 0, keyLength);
					position += 4L + keyLength;
					int count = segment.get(INT, position);
					position += 4;
					long pixels = position;
					position += 4L * count;
					if (position > size - 8) {
						throw new IOException("truncated entry");
					}
					parsed.put(new String(key, StandardCharsets.UTF_8), new Span(start, position, pixels, count));
				}
				index.putAll(parsed);
				arena = opened;
				data = segment;
			} catch (IOException | RuntimeException exception) {
				opened.close();
				AtlasStream.LOGGER.warn("Discarding unreadable tile cache {}: {}", file.getFileName(), exception.toString());
				Files.deleteIfExists(file);
				return;
			}
			Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
		}

		int[] get(String key, int expectedPixels) {
			Span span = index.get(key);
			if (span == null || span.count() != expectedPixels) {
				return null;
			}
			int[] pixels = new int[expectedPixels];
			MemorySegment.copy(data, INT, span.pixels(), pixels, 0, expectedPixels);
			return pixels;
		}

		void put(String key, int[] pixels) {
			added.put(key, pixels);
		}

		private boolean save() {
			if (DiskCacheBudget.bytes() == 0) {
				return false;
			}
			if (added.isEmpty()) {
				return false;
			}
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			try {
				Files.createDirectories(file.getParent());
				CRC32 crc = new CRC32();
				int count = added.size();
				List<Span> kept = new ArrayList<>();
				for (Map.Entry<String, Span> entry : index.entrySet()) {
					if (!added.containsKey(entry.getKey())) {
						kept.add(entry.getValue());
					}
				}
				count += kept.size();
				long estimatedSize = 20;
				for (Span span : kept) {
					estimatedSize += span.end() - span.start();
				}
				for (Map.Entry<String, int[]> entry : added.entrySet()) {
					estimatedSize += 8L + entry.getKey().getBytes(StandardCharsets.UTF_8).length
						+ entry.getValue().length * 4L;
				}
				Files.createDirectories(file.getParent());
				if (!DiskCacheBudget.hasSpaceFor(file.getParent(), estimatedSize)) {
					return false;
				}
				try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
						StandardOpenOption.TRUNCATE_EXISTING)) {
					ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
					header.putInt(MAGIC).putInt(FORMAT).putInt(count).flip();
					write(channel, crc, header);
					for (Span span : kept) {
						write(channel, crc, data.asSlice(span.start(), span.end() - span.start()).asByteBuffer());
					}
					for (Map.Entry<String, int[]> entry : added.entrySet()) {
						byte[] key = entry.getKey().getBytes(StandardCharsets.UTF_8);
						int[] pixels = entry.getValue();
						ByteBuffer buffer = ByteBuffer.allocate(8 + key.length + pixels.length * 4)
							.order(ByteOrder.LITTLE_ENDIAN);
						buffer.putInt(key.length).put(key).putInt(pixels.length);
						buffer.asIntBuffer().put(pixels);
						buffer.position(0);
						write(channel, crc, buffer);
					}
					ByteBuffer trailer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
					trailer.putLong(crc.getValue()).flip();
					while (trailer.hasRemaining()) {
						channel.write(trailer);
					}
				}
				close();
				try {
					Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (AtomicMoveNotSupportedException exception) {
					Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
				}
				return true;
			} catch (IOException | RuntimeException exception) {
				AtlasStream.LOGGER.warn("Could not store tile cache {}: {}", file.getFileName(), exception.toString());
				try {
					Files.deleteIfExists(temp);
				} catch (IOException ignored) {
				}
				return false;
			}
		}

		private static void write(FileChannel channel, CRC32 crc, ByteBuffer buffer) throws IOException {
			crc.update(buffer.duplicate());
			while (buffer.hasRemaining()) {
				channel.write(buffer);
			}
		}

		private void close() {
			if (arena != null) {
				arena.close();
				arena = null;
				data = null;
			}
		}
	}
}
