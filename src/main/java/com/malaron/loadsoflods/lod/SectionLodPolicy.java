package com.malaron.loadsoflods.lod;

import java.util.Objects;

public final class SectionLodPolicy {
	private static final int SECTION_SIZE = 16;

	private SectionLodPolicy() {
	}

	public static SectionLod initialLevel(
		int sectionX,
		int sectionY,
		int sectionZ,
		double cameraX,
		double cameraY,
		double cameraZ,
		LodConfig config
	) {
		Objects.requireNonNull(config, "config");
		if (config.highResolutionDistance() <= 0.0) {
			return SectionLod.LOW_RESOLUTION;
		}
		return distanceToSection(sectionX, sectionY, sectionZ, cameraX, cameraY, cameraZ)
			<= config.highResolutionDistance()
			? SectionLod.HIGH_RESOLUTION
			: SectionLod.LOW_RESOLUTION;
	}

	public static SectionLod updateLevel(
		SectionLod currentLevel,
		int sectionX,
		int sectionY,
		int sectionZ,
		double cameraX,
		double cameraY,
		double cameraZ,
		LodConfig config
	) {
		Objects.requireNonNull(currentLevel, "currentLevel");
		Objects.requireNonNull(config, "config");
		if (config.highResolutionDistance() <= 0.0) {
			return SectionLod.LOW_RESOLUTION;
		}
		double distance = distanceToSection(sectionX, sectionY, sectionZ, cameraX, cameraY, cameraZ);
		double boundary = config.highResolutionDistance();
		double hysteresis = config.hysteresis();

		if (currentLevel == SectionLod.HIGH_RESOLUTION) {
			return distance - boundary <= hysteresis
				? SectionLod.HIGH_RESOLUTION
				: SectionLod.LOW_RESOLUTION;
		}
		double entryBoundary = Math.max(boundary - hysteresis, boundary * 0.5);
		return distance < entryBoundary
			? SectionLod.HIGH_RESOLUTION
			: SectionLod.LOW_RESOLUTION;
	}

	private static double distanceToSection(
		int sectionX,
		int sectionY,
		int sectionZ,
		double cameraX,
		double cameraY,
		double cameraZ
	) {
		if (!Double.isFinite(cameraX) || !Double.isFinite(cameraY) || !Double.isFinite(cameraZ)) {
			throw new IllegalArgumentException("Camera position must contain only finite coordinates");
		}
		double minX = (double) sectionX * SECTION_SIZE;
		double minY = (double) sectionY * SECTION_SIZE;
		double minZ = (double) sectionZ * SECTION_SIZE;
		double dx = distanceOutside(cameraX, minX, minX + SECTION_SIZE);
		double dy = distanceOutside(cameraY, minY, minY + SECTION_SIZE);
		double dz = distanceOutside(cameraZ, minZ, minZ + SECTION_SIZE);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static double distanceOutside(double coordinate, double minimum, double maximum) {
		return Math.max(Math.max(minimum - coordinate, coordinate - maximum), 0.0);
	}
}
