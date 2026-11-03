package com.malaron.loadsoflods.lod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodConfigTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void createsDefaultConfigurationWhenMissing() throws IOException {
		Path file = temporaryDirectory.resolve("config").resolve("loads-of-lods.properties");

		assertEquals(new LodConfig(128.0, 16.0, 1024), LodConfig.load(file));
		assertTrue(Files.exists(file));
		assertTrue(Files.readString(file).contains("highResolutionCacheMiB=1024"));
	}

	@Test
	void loadsConfiguredValues() throws IOException {
		Path file = temporaryDirectory.resolve("loads-of-lods.properties");
		Files.writeString(file, "highResolutionDistance=256\nhysteresis=24\nhighResolutionCacheMiB=2048\n");

		assertEquals(new LodConfig(256.0, 24.0, 2048), LodConfig.load(file));
	}

	@Test
	void rejectsInvalidConfiguration() throws IOException {
		Path file = temporaryDirectory.resolve("loads-of-lods.properties");
		Files.writeString(file, "highResolutionDistance=64\nhysteresis=80\n");

		assertThrows(IllegalArgumentException.class, () -> LodConfig.load(file));
	}

	@Test
	void rejectsCacheBudgetsOutsideSupportedRange() throws IOException {
		Path file = temporaryDirectory.resolve("loads-of-lods.properties");
		Files.writeString(file, "highResolutionCacheMiB=32\n");

		assertThrows(IllegalArgumentException.class, () -> LodConfig.load(file));
	}
}
