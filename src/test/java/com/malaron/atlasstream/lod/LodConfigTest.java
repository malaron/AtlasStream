package com.malaron.atlasstream.lod;

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
		Path file = temporaryDirectory.resolve("config").resolve("atlasstream.properties");

		assertEquals(new LodConfig(128.0, 16.0, 1024, 64), LodConfig.load(file));
		assertTrue(Files.exists(file));
		assertTrue(Files.readString(file).contains("highResolutionCacheMiB=1024"));
		assertTrue(Files.readString(file).contains("diskCacheMiB=6144"));
		assertTrue(Files.readString(file).contains("targetResolution=64"));
	}

	@Test
	void savesAndReloadsEditedValues() {
		Path file = temporaryDirectory.resolve("atlasstream.properties");
		LodConfig edited = new LodConfig(96.0, 8.0, 512, 32);

		LodConfig.save(file, edited);

		assertEquals(edited, LodConfig.load(file));
	}

	@Test
	void keepsHysteresisWhenDistanceIsZero() {
		Path file = temporaryDirectory.resolve("atlasstream.properties");
		LodConfig off = new LodConfig(0.0, 16.0, 512, 64);

		LodConfig.save(file, off);

		assertEquals(off, LodConfig.load(file));
		assertThrows(IllegalArgumentException.class, () -> new LodConfig(8.0, 16.0, 512, 64));
	}

	@Test
	void rejectsUnsupportedDownscaleFactor() throws IOException {
		Path file = temporaryDirectory.resolve("atlasstream.properties");
		Files.writeString(file, "targetResolution=3\n");

		assertThrows(IllegalArgumentException.class, () -> LodConfig.load(file));
	}

	@Test
	void supportsEveryReductionOfferedByTheConfigUi() {
		for (int target : LodConfig.ALLOWED_TARGET_RESOLUTIONS) {
			assertEquals(target, new LodConfig(128.0, 16.0, 512, target).targetResolution());
		}
	}

	@Test
	void appliesSharedUiAndCommandBoundariesWhenUpdating() {
		LodConfig config = LodConfig.defaults();

		assertEquals(32.0, config.withHighResolutionDistance(32.0).highResolutionDistance());
		assertEquals(16.0, config.withHighResolutionDistance(32.0).hysteresis());
		assertEquals(32.0, config.withHighResolutionDistance(32.0).withHysteresis(128.0).hysteresis());
		assertEquals(128.0, config.withHighResolutionDistance(0.0).withHysteresis(128.0).hysteresis());
		assertThrows(IllegalArgumentException.class, () -> config.withHighResolutionDistance(528.0));
		assertThrows(IllegalArgumentException.class, () -> config.withTargetResolution(17));
	}

	@Test
	void loadsConfiguredValues() throws IOException {
		Path file = temporaryDirectory.resolve("atlasstream.properties");
		Files.writeString(file, "highResolutionDistance=256\nhysteresis=24\nhighResolutionCacheMiB=2048\n");

		assertEquals(new LodConfig(256.0, 24.0, 2048), LodConfig.load(file));
	}

	@Test
	void migratesLegacyFactorConfigurationToTargetResolution() throws IOException {
		Path file = temporaryDirectory.resolve("atlasstream.properties");
		Files.writeString(file, "downscaleFactor=4\n");

		assertEquals(
			LodConfig.DEFAULT_TARGET_RESOLUTION,
			LodConfig.load(file).targetResolution()
		);
	}

	@Test
	void rejectsInvalidConfiguration() throws IOException {
		Path file = temporaryDirectory.resolve("atlasstream.properties");
		Files.writeString(file, "highResolutionDistance=64\nhysteresis=80\n");

		assertThrows(IllegalArgumentException.class, () -> LodConfig.load(file));
	}

	@Test
	void rejectsCacheBudgetsOutsideSupportedRange() throws IOException {
		Path file = temporaryDirectory.resolve("atlasstream.properties");
		Files.writeString(file, "highResolutionCacheMiB=32\n");

		assertThrows(IllegalArgumentException.class, () -> LodConfig.load(file));

		Files.writeString(file, "diskCacheMiB=-1\n");
		assertThrows(IllegalArgumentException.class, () -> LodConfig.load(file));
	}

	@Test
	void updatesDiskCacheBudget() {
		LodConfig config = LodConfig.defaults();

		assertEquals(0L, config.withDiskCacheMiB(0).diskCacheMiB());
		assertEquals(32L * 1024, config.withDiskCacheMiB(32L * 1024).diskCacheMiB());
		assertThrows(IllegalArgumentException.class, () -> config.withDiskCacheMiB(64L * 1024 + 1));
	}
}
