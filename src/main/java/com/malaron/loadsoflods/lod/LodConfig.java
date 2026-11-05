package com.malaron.loadsoflods.lod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record LodConfig(
	double highResolutionDistance,
	double hysteresis,
	long highResolutionCacheMiB,
	int downscaleFactor,
	boolean debugTintLowResolution,
	boolean enableOnLaunch
) {
	private static final double DEFAULT_HIGH_RESOLUTION_DISTANCE = 128.0;
	private static final double DEFAULT_HYSTERESIS = 16.0;
	public static final long DEFAULT_HIGH_RESOLUTION_CACHE_MIB = 1024;
	public static final int DEFAULT_DOWNSCALE_FACTOR = 4;
	public static final long MIN_HIGH_RESOLUTION_CACHE_MIB = 64;
	public static final long MAX_HIGH_RESOLUTION_CACHE_MIB = 16 * 1024;

	public LodConfig(double highResolutionDistance, double hysteresis, long highResolutionCacheMiB) {
		this(highResolutionDistance, hysteresis, highResolutionCacheMiB, DEFAULT_DOWNSCALE_FACTOR);
	}

	public LodConfig(double highResolutionDistance, double hysteresis, long highResolutionCacheMiB, int downscaleFactor) {
		this(highResolutionDistance, hysteresis, highResolutionCacheMiB, downscaleFactor, false);
	}

	public LodConfig(
		double highResolutionDistance,
		double hysteresis,
		long highResolutionCacheMiB,
		int downscaleFactor,
		boolean debugTintLowResolution
	) {
		this(highResolutionDistance, hysteresis, highResolutionCacheMiB, downscaleFactor, debugTintLowResolution, true);
	}

	public LodConfig {
		if (downscaleFactor != 2 && downscaleFactor != 4 && downscaleFactor != 8) {
			throw new IllegalArgumentException("Downscale factor must be 2, 4 or 8");
		}
		if (!Double.isFinite(highResolutionDistance) || highResolutionDistance < 0.0) {
			throw new IllegalArgumentException("High-resolution distance must be a finite, non-negative number (0 disables near textures)");
		}
		// Hysteresis is ignored (and kept as-is) while the distance is 0, so it may exceed it.
		if (!Double.isFinite(hysteresis) || hysteresis < 0.0
				|| (highResolutionDistance > 0.0 && hysteresis > highResolutionDistance)) {
			throw new IllegalArgumentException(
				"Hysteresis must be finite, non-negative, and no greater than the high-resolution distance"
			);
		}
		if (highResolutionCacheMiB < MIN_HIGH_RESOLUTION_CACHE_MIB
				|| highResolutionCacheMiB > MAX_HIGH_RESOLUTION_CACHE_MIB) {
			throw new IllegalArgumentException(
				"High-resolution cache budget must be between " + MIN_HIGH_RESOLUTION_CACHE_MIB
					+ " and " + MAX_HIGH_RESOLUTION_CACHE_MIB + " MiB"
			);
		}
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

		LodConfig config = new LodConfig(
			readNumber(properties, "highResolutionDistance", DEFAULT_HIGH_RESOLUTION_DISTANCE),
			readNumber(properties, "hysteresis", DEFAULT_HYSTERESIS),
			readLong(properties, "highResolutionCacheMiB", DEFAULT_HIGH_RESOLUTION_CACHE_MIB),
			(int) readLong(properties, "downscaleFactor", DEFAULT_DOWNSCALE_FACTOR),
			Boolean.parseBoolean(properties.getProperty("debugTintLowResolution", "false").trim()),
			Boolean.parseBoolean(properties.getProperty("enableOnLaunch", "true").trim())
		);
		if (!Files.exists(file)) {
			save(file, config);
		}
		return config;
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
		properties.setProperty("downscaleFactor", Integer.toString(config.downscaleFactor()));
		properties.setProperty("debugTintLowResolution", Boolean.toString(config.debugTintLowResolution()));
		properties.setProperty("enableOnLaunch", Boolean.toString(config.enableOnLaunch()));
		try {
			Path parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			try (OutputStream output = Files.newOutputStream(file)) {
				properties.store(output, "Loads of Lods client configuration (distances in blocks)");
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Could not write LOD configuration at " + file, exception);
		}
	}
}
