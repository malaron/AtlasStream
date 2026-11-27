package com.malaron.atlasstream.lod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

public record LodConfig(
	double highResolutionDistance,
	double hysteresis,
	long highResolutionCacheMiB,
	long diskCacheMiB,
	int targetResolution,
	boolean debugTintLowResolution,
	boolean enableOnLaunch
) {
	public static final int BLOCKS_PER_CHUNK = 16;
	public static final double DEFAULT_HIGH_RESOLUTION_DISTANCE = 128.0;
	public static final double DEFAULT_HYSTERESIS = 16.0;
	public static final int MIN_HIGH_RESOLUTION_DISTANCE_CHUNKS = 0;
	public static final int MAX_HIGH_RESOLUTION_DISTANCE_CHUNKS = 32;
	public static final double MIN_HYSTERESIS = 0.0;
	public static final double MAX_HYSTERESIS = 128.0;
	public static final long DEFAULT_HIGH_RESOLUTION_CACHE_MIB = 1024;
	public static final int DEFAULT_TARGET_RESOLUTION = 64;
	public static final List<Integer> ALLOWED_TARGET_RESOLUTIONS = List.of(16, 32, 64, 128, 256);
	public static final long MIN_HIGH_RESOLUTION_CACHE_MIB = 64;
	public static final long MAX_HIGH_RESOLUTION_CACHE_MIB = 16 * 1024;
	public static final long DEFAULT_DISK_CACHE_MIB = 6 * 1024;
	public static final long MAX_DISK_CACHE_MIB = 64 * 1024;

	public LodConfig(double highResolutionDistance, double hysteresis, long highResolutionCacheMiB) {
		this(highResolutionDistance, hysteresis, highResolutionCacheMiB, DEFAULT_DISK_CACHE_MIB,
			DEFAULT_TARGET_RESOLUTION, false, true);
	}

	public LodConfig(double highResolutionDistance, double hysteresis, long highResolutionCacheMiB, int targetResolution) {
		this(highResolutionDistance, hysteresis, highResolutionCacheMiB, DEFAULT_DISK_CACHE_MIB,
			targetResolution, false, true);
	}

	public LodConfig(
		double highResolutionDistance,
		double hysteresis,
		long highResolutionCacheMiB,
		int targetResolution,
		boolean debugTintLowResolution
	) {
		this(highResolutionDistance, hysteresis, highResolutionCacheMiB, DEFAULT_DISK_CACHE_MIB,
			targetResolution, debugTintLowResolution, true);
	}

	public LodConfig {
		if (!ALLOWED_TARGET_RESOLUTIONS.contains(targetResolution)) {
			throw new IllegalArgumentException(
				"Low-resolution target must be one of " + ALLOWED_TARGET_RESOLUTIONS
			);
		}
		if (!Double.isFinite(highResolutionDistance)
				|| highResolutionDistance < MIN_HIGH_RESOLUTION_DISTANCE_CHUNKS * (double) BLOCKS_PER_CHUNK
				|| highResolutionDistance > MAX_HIGH_RESOLUTION_DISTANCE_CHUNKS * (double) BLOCKS_PER_CHUNK) {
			throw new IllegalArgumentException(
				"High-resolution distance must be between "
					+ MIN_HIGH_RESOLUTION_DISTANCE_CHUNKS + " and "
					+ MAX_HIGH_RESOLUTION_DISTANCE_CHUNKS + " chunks"
			);
		}
		// Hysteresis is ignored (and kept as-is) while the distance is 0, so it may exceed it.
		if (!Double.isFinite(hysteresis) || hysteresis < MIN_HYSTERESIS || hysteresis > MAX_HYSTERESIS
				|| (highResolutionDistance > 0.0 && hysteresis > highResolutionDistance)) {
			throw new IllegalArgumentException(
				"Hysteresis must be between " + MIN_HYSTERESIS + " and " + MAX_HYSTERESIS
					+ " and no greater than the high-resolution distance"
			);
		}
		if (highResolutionCacheMiB < MIN_HIGH_RESOLUTION_CACHE_MIB
				|| highResolutionCacheMiB > MAX_HIGH_RESOLUTION_CACHE_MIB) {
			throw new IllegalArgumentException(
				"High-resolution cache budget must be between " + MIN_HIGH_RESOLUTION_CACHE_MIB
					+ " and " + MAX_HIGH_RESOLUTION_CACHE_MIB + " MiB"
			);
		}
		if (diskCacheMiB < 0 || diskCacheMiB > MAX_DISK_CACHE_MIB) {
			throw new IllegalArgumentException(
				"Disk cache budget must be between 0 and " + MAX_DISK_CACHE_MIB + " MiB"
			);
		}
	}

	public static LodConfig defaults() {
		return new LodConfig(
			DEFAULT_HIGH_RESOLUTION_DISTANCE,
			DEFAULT_HYSTERESIS,
			DEFAULT_HIGH_RESOLUTION_CACHE_MIB,
			DEFAULT_DISK_CACHE_MIB,
			DEFAULT_TARGET_RESOLUTION,
			false,
			true
		);
	}

	public static double clampHysteresis(double highResolutionDistance, double hysteresis) {
		double bounded = Math.max(MIN_HYSTERESIS, Math.min(MAX_HYSTERESIS, hysteresis));
		return highResolutionDistance > 0.0 ? Math.min(bounded, highResolutionDistance) : bounded;
	}

	public LodConfig withHighResolutionDistance(double distance) {
		return new LodConfig(
			distance,
			clampHysteresis(distance, hysteresis),
			highResolutionCacheMiB,
			diskCacheMiB,
			targetResolution,
			debugTintLowResolution,
			enableOnLaunch
		);
	}

	public LodConfig withHysteresis(double value) {
		return new LodConfig(
			highResolutionDistance,
			clampHysteresis(highResolutionDistance, value),
			highResolutionCacheMiB,
			diskCacheMiB,
			targetResolution,
			debugTintLowResolution,
			enableOnLaunch
		);
	}

	public LodConfig withHighResolutionCacheMiB(long value) {
		return new LodConfig(
			highResolutionDistance, hysteresis, value, diskCacheMiB, targetResolution,
			debugTintLowResolution, enableOnLaunch
		);
	}

	public LodConfig withDiskCacheMiB(long value) {
		return new LodConfig(
			highResolutionDistance, hysteresis, highResolutionCacheMiB, value, targetResolution,
			debugTintLowResolution, enableOnLaunch
		);
	}

	public LodConfig withTargetResolution(int value) {
		return new LodConfig(
			highResolutionDistance, hysteresis, highResolutionCacheMiB, diskCacheMiB, value,
			debugTintLowResolution, enableOnLaunch
		);
	}

	public LodConfig withDebugTintLowResolution(boolean enabled) {
		return new LodConfig(
			highResolutionDistance, hysteresis, highResolutionCacheMiB, diskCacheMiB, targetResolution,
			enabled, enableOnLaunch
		);
	}

	public LodConfig withEnableOnLaunch(boolean enabled) {
		return new LodConfig(
			highResolutionDistance, hysteresis, highResolutionCacheMiB, diskCacheMiB, targetResolution,
			debugTintLowResolution, enabled
		);
	}

	public static LodConfig load(Path file) {
		Properties properties = new Properties();
		if (Files.exists(file)) {
			try (InputStream input = Files.newInputStream(file)) {
				properties.load(input);
			} catch (IOException exception) {
				throw new IllegalStateException("Could not read LOD configuration at " + file, exception);
			}
		}

		int targetResolution = readTargetResolution(properties);
		LodConfig config = new LodConfig(
			readNumber(properties, "highResolutionDistance", DEFAULT_HIGH_RESOLUTION_DISTANCE),
			readNumber(properties, "hysteresis", DEFAULT_HYSTERESIS),
			readLong(properties, "highResolutionCacheMiB", DEFAULT_HIGH_RESOLUTION_CACHE_MIB),
			readLong(properties, "diskCacheMiB", DEFAULT_DISK_CACHE_MIB),
			targetResolution,
			Boolean.parseBoolean(properties.getProperty("debugTintLowResolution", "false").trim()),
			Boolean.parseBoolean(properties.getProperty("enableOnLaunch", "true").trim())
		);
		if (!Files.exists(file)) {
			save(file, config);
		}
		return config;
	}

	private static int readTargetResolution(Properties properties) {
		String target = properties.getProperty("targetResolution");
		if (target != null) {
			try {
				return Integer.parseInt(target.trim());
			} catch (NumberFormatException exception) {
				throw new IllegalStateException("Invalid integer value for 'targetResolution': " + target, exception);
			}
		}
		// Older builds stored a global downscale factor. Choose the matching vanilla-friendly target.
		// The legacy value is intentionally not imported as a target (1:4 is not a resolution).
		return DEFAULT_TARGET_RESOLUTION;
	}

	// Hand-edited key: "always" keeps the block atlas virtual (never allocated on the GPU) even when it would fit.
	public static boolean readAlwaysVirtualAtlas(Path file) {
		if (!Files.exists(file)) {
			return false;
		}
		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(file)) {
			properties.load(input);
		} catch (IOException exception) {
			return false;
		}
		return "always".equalsIgnoreCase(properties.getProperty("virtualAtlas", "auto").trim());
	}

	// Hand-edited key (experimental): lets held and dropped block items stream from the virtual cache under Iris.
	public static boolean readVirtualItemsWithIris(Path file) {
		if (!Files.exists(file)) {
			return false;
		}
		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(file)) {
			properties.load(input);
		} catch (IOException exception) {
			return false;
		}
		return "true".equalsIgnoreCase(properties.getProperty("virtualItemsWithIris", "false").trim());
	}

	private static double readNumber(Properties properties, String key, double defaultValue) {
		String value = properties.getProperty(key);
		if (value == null) {
			return defaultValue;
		}
		try {
			return Double.parseDouble(value.trim());
		} catch (NumberFormatException exception) {
			throw new IllegalStateException("Invalid numeric value for '" + key + "': " + value, exception);
		}
	}

	private static long readLong(Properties properties, String key, long defaultValue) {
		String value = properties.getProperty(key);
		if (value == null) {
			return defaultValue;
		}
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException exception) {
			throw new IllegalStateException("Invalid integer value for '" + key + "': " + value, exception);
		}
	}

	public static void save(Path file, LodConfig config) {
		Properties properties = new Properties();
		// Keep keys that are only edited by hand (such as virtualAtlas).
		if (Files.exists(file)) {
			try (InputStream input = Files.newInputStream(file)) {
				properties.load(input);
			} catch (IOException exception) {
				throw new IllegalStateException("Could not read LOD configuration at " + file, exception);
			}
		}
		properties.setProperty("highResolutionDistance", Double.toString(config.highResolutionDistance()));
		properties.setProperty("hysteresis", Double.toString(config.hysteresis()));
		properties.setProperty("highResolutionCacheMiB", Long.toString(config.highResolutionCacheMiB()));
		properties.setProperty("diskCacheMiB", Long.toString(config.diskCacheMiB()));
		properties.setProperty("targetResolution", Integer.toString(config.targetResolution()));
		properties.setProperty("debugTintLowResolution", Boolean.toString(config.debugTintLowResolution()));
		properties.setProperty("enableOnLaunch", Boolean.toString(config.enableOnLaunch()));
		try {
			Path parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			try (OutputStream output = Files.newOutputStream(file)) {
				properties.store(output, "AtlasStream client configuration (distances in blocks)");
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Could not write LOD configuration at " + file, exception);
		}
	}
}
