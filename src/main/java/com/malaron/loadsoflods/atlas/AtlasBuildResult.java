package com.malaron.loadsoflods.atlas;

import java.nio.file.Path;

public record AtlasBuildResult(
	Path highResolutionAtlas,
	Path lowResolutionAtlas,
	Path layoutManifest,
	int spriteCount,
	int pageCount,
	boolean cacheHit
) {
}
