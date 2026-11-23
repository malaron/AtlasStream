package com.malaron.atlasstream.streaming.compact;

import com.malaron.atlasstream.lod.SectionLodPolicy;

public final class CompactAtlasPriority {
	private static final double IMMEDIATE_SECTION_REACH = 4.0;
	private CompactAtlasPriority() {
	}

	public static boolean isImmediateSection(
		int sectionX, int sectionY, int sectionZ, double cameraX, double cameraY, double cameraZ
	) {
		return SectionLodPolicy.distanceToSection(sectionX, sectionY, sectionZ, cameraX, cameraY, cameraZ)
			<= IMMEDIATE_SECTION_REACH;
	}

	// Lower values are more important.
	public static int compare(boolean firstPreferred, double firstDistance, boolean secondPreferred, double secondDistance) {
		int tier = Boolean.compare(secondPreferred, firstPreferred);
		return tier != 0 ? tier : Double.compare(firstDistance, secondDistance);
	}

	public static boolean canEvict(
		boolean waitingPreferred, double waitingDistance,
		boolean residentPreferred, double residentDistance, double margin
	) {
		if (waitingPreferred != residentPreferred) {
			return waitingPreferred;
		}
		return residentDistance > waitingDistance + margin;
	}
}
