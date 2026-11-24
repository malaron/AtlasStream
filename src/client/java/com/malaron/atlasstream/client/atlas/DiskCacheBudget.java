package com.malaron.atlasstream.client.atlas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.malaron.atlasstream.client.AtlasStreamClient;

final class DiskCacheBudget {
	private DiskCacheBudget() {
	}

	static long bytes() {
		return AtlasStreamClient.getConfig().diskCacheMiB() << 20;
	}

	static boolean hasSpaceFor(Path directory, long entryBytes) throws IOException {
		long budget = bytes();
		return budget > 0
			&& entryBytes <= budget
			&& entryBytes <= Files.getFileStore(directory).getUsableSpace() - 256L * 1024 * 1024;
	}

	static void prune(Path directory, Path keep) throws IOException {
		long budget = bytes();
		if (!Files.isDirectory(directory)) {
			return;
		}
		List<Path> files;
		try (Stream<Path> list = Files.walk(directory)) {
			files = list.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(".bin")
					|| path.getFileName().toString().endsWith(".tiles"))
				.sorted(Comparator.comparing(DiskCacheBudget::modified))
				.toList();
		}
		long total = 0;
		for (Path file : files) {
			total += Files.size(file);
		}
		for (Path file : files) {
			if (total <= budget) {
				break;
			}
			if (keep == null || !file.equals(keep)) {
				total -= Files.size(file);
				Files.deleteIfExists(file);
			}
		}
	}

	private static long modified(Path path) {
		try {
			return Files.getLastModifiedTime(path).toMillis();
		} catch (IOException exception) {
			return 0L;
		}
	}
}
