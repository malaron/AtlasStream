package com.malaron.atlasstream.client.sodium;

import java.util.concurrent.atomic.AtomicInteger;

import com.malaron.atlasstream.AtlasStream;
import com.malaron.atlasstream.render.FlowerLayerPolicy;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class TerrainLayerCorrections {
	private static volatile TargetedBlockCorrection targetedBlockCorrection;
	private static volatile boolean overlaysUseCutout = true;
	private static volatile boolean flowersUseCutout = true;
	private static final AtomicInteger overlayReports = new AtomicInteger();
	private static final AtomicInteger flowerReports = new AtomicInteger();

	private TerrainLayerCorrections() {
	}

	public static void setTargetBlockCutout(Block block, boolean enabled) {
		targetedBlockCorrection = enabled ? new TargetedBlockCorrection(block, new AtomicInteger()) : null;
	}

	public static void setOverlaysUseCutout(boolean enabled) {
		overlayReports.set(0);
		overlaysUseCutout = enabled;
	}

	public static void setFlowersUseCutout(boolean enabled) {
		flowerReports.set(0);
		flowersUseCutout = enabled;
	}

	public static Block targetBlock() {
		TargetedBlockCorrection current = targetedBlockCorrection;
		return current == null ? null : current.block();
	}

	public static boolean hasActiveCorrections() {
		return targetedBlockCorrection != null || overlaysUseCutout || flowersUseCutout;
	}

	public static ChunkSectionLayer correctTerrainLayer(
		BlockState state, ChunkSectionLayer original, boolean forceOpaque
	) {
		TargetedBlockCorrection targeted = targetedBlockCorrection;
		ChunkSectionLayer correctedLayer = targeted != null && state.is(targeted.block())
			&& original == ChunkSectionLayer.TRANSLUCENT && !forceOpaque ? ChunkSectionLayer.CUTOUT : original;
		if (targeted != null && state.is(targeted.block())) {
			int bit = 1 << (original.ordinal() * 2 + (forceOpaque ? 1 : 0));
			if ((targeted.reportedLayers().getAndUpdate(value -> value | bit) & bit) == 0) {
				AtlasStream.LOGGER.info(
					"Targeted block pass: block={}, quadLayer={}, forceOpaque={}, selectedLayer={}",
					BuiltInRegistries.BLOCK.getKey(targeted.block()), original, forceOpaque,
					forceOpaque ? ChunkSectionLayer.SOLID : correctedLayer
				);
			}
		}
		if (correctedLayer == ChunkSectionLayer.TRANSLUCENT
				&& FlowerLayerPolicy.shouldUseCutout(flowersUseCutout, state.is(BlockTags.SMALL_FLOWERS),
					original == ChunkSectionLayer.TRANSLUCENT, forceOpaque)) {
			if (flowerReports.get() < 12 && flowerReports.getAndIncrement() < 12) {
				AtlasStream.LOGGER.info("Flower cutout correction: block={}, quadLayer={}",
					BuiltInRegistries.BLOCK.getKey(state.getBlock()), original);
			}
			return ChunkSectionLayer.CUTOUT;
		}
		return correctedLayer;
	}

	public static boolean overlaysUseCutout() {
		return overlaysUseCutout;
	}

	public static boolean flowersUseCutout() {
		return flowersUseCutout;
	}

	public static void reportMatchedOverlay(BlockState state, float height) {
		if (overlayReports.get() < 12 && overlayReports.getAndIncrement() < 12) {
			AtlasStream.LOGGER.info("Overlay cutout: block={}, quadHeight={}",
				BuiltInRegistries.BLOCK.getKey(state.getBlock()), height);
		}
	}

	private record TargetedBlockCorrection(Block block, AtomicInteger reportedLayers) {
	}
}
