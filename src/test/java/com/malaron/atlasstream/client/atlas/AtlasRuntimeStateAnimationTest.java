package com.malaron.atlasstream.client.atlas;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.client.resources.metadata.animation.AnimationFrame;

class AtlasRuntimeStateAnimationTest {
	@Test
	void removesInvalidResourcePackFrameIndices() {
		List<AnimationFrame> frames = java.util.stream.IntStream.concat(
			java.util.stream.IntStream.rangeClosed(16, 31),
			java.util.stream.IntStream.range(0, 16)
		).mapToObj(AnimationFrame::new).toList();

		List<AnimationFrame> valid = AtlasRuntimeState.validAnimationFrames(frames, 4, 2);

		assertEquals(List.of(0, 1, 2, 3), valid.stream().map(AnimationFrame::index).toList());
	}

	@Test
	void fallsBackToEveryPhysicalFrameWhenMetadataIsEntirelyInvalid() {
		List<AnimationFrame> frames = List.of(
			new AnimationFrame(8), new AnimationFrame(9), new AnimationFrame(-1)
		);

		List<AnimationFrame> valid = AtlasRuntimeState.validAnimationFrames(frames, 4, 2);

		assertEquals(List.of(0, 1, 2, 3), valid.stream().map(AnimationFrame::index).toList());
	}
}
