package com.malaron.loadsoflods.client.config;

import java.util.List;

import com.malaron.loadsoflods.LoadsOfLods;
import com.malaron.loadsoflods.client.LoadsOfLodsClient;
import com.malaron.loadsoflods.lod.LodConfig;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.CyclingListControllerBuilder;
import dev.isxander.yacl3.api.controller.DoubleSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.LongSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

// Only loaded when YACL is present; LoadsOfLodsModMenu guards the entry point.
final class LodConfigScreen {
	private LodConfigScreen() {
	}

	static Screen create(Screen parent) {
		LodConfig current = LoadsOfLodsClient.getConfig();
		LodConfig defaults = new LodConfig(128.0, 16.0, LodConfig.DEFAULT_HIGH_RESOLUTION_CACHE_MIB, LodConfig.DEFAULT_DOWNSCALE_FACTOR);
		double[] distance = {current.highResolutionDistance()};
		double[] hysteresis = {current.hysteresis()};
		long[] cacheMiB = {current.highResolutionCacheMiB()};
		int[] factor = {current.downscaleFactor()};
		boolean[] tint = {current.debugTintLowResolution()};

		Option<Integer> factorOption = Option.<Integer>createBuilder()
			.name(Component.literal("Texture reduction"))
			.description(OptionDescription.of(Component.literal(
				"How much the far/low block atlas is shrunk (1:N per axis). Higher saves more VRAM but looks blurrier. "
					+ "Takes effect the next time you run /lods preview-distance (reload resource packs first)."
			)))
			.binding(defaults.downscaleFactor(), () -> factor[0], value -> factor[0] = value)
			.controller(option -> CyclingListControllerBuilder.create(option)
				.values(List.of(2, 4, 8))
				.formatValue(value -> Component.literal("1:" + value)))
			.build();

		Option<Double> distanceOption = Option.<Double>createBuilder()
			.name(Component.literal("High-resolution distance"))
			.description(OptionDescription.of(Component.literal(
				"Sections closer than this many blocks use full-resolution textures (non-Iris streamed mode)."
			)))
			.binding(defaults.highResolutionDistance(), () -> distance[0], value -> distance[0] = value)
			.controller(option -> DoubleSliderControllerBuilder.create(option).range(4.0, 512.0).step(2.0))
			.build();

		Option<Double> hysteresisOption = Option.<Double>createBuilder()
			.name(Component.literal("Hysteresis"))
			.description(OptionDescription.of(Component.literal(
				"Extra distance in blocks before switching back, to avoid flicker. Clamped to the high-resolution distance."
			)))
			.binding(defaults.hysteresis(), () -> hysteresis[0], value -> hysteresis[0] = value)
			.controller(option -> DoubleSliderControllerBuilder.create(option).range(0.0, 128.0).step(1.0))
			.build();

		Option<Long> cacheOption = Option.<Long>createBuilder()
			.name(Component.literal("High-resolution cache (MiB)"))
			.description(OptionDescription.of(Component.literal(
				"VRAM budget for streamed full-resolution tiles. Not used with Iris shaderpacks."
			)))
			.binding(defaults.highResolutionCacheMiB(), () -> cacheMiB[0], value -> cacheMiB[0] = value)
			.controller(option -> LongSliderControllerBuilder.create(option)
				.range(LodConfig.MIN_HIGH_RESOLUTION_CACHE_MIB, LodConfig.MAX_HIGH_RESOLUTION_CACHE_MIB)
				.step(64L))
			.build();

		Option<Boolean> tintOption = Option.<Boolean>createBuilder()
			.name(Component.literal("Debug: tint low-resolution textures"))
			.description(OptionDescription.of(Component.literal(
				"Colors the reduced atlas magenta so you can see where it is used. Applies when the preview is rebuilt (reload, then /lods preview-distance)."
			)))
			.binding(false, () -> tint[0], value -> tint[0] = value)
			.controller(TickBoxControllerBuilder::create)
			.build();

		return YetAnotherConfigLib.createBuilder()
			.title(Component.literal("Loads of Lods"))
			.category(ConfigCategory.createBuilder()
				.name(Component.literal("Textures"))
				.option(factorOption)
				.option(distanceOption)
				.option(hysteresisOption)
				.option(cacheOption)
				.option(tintOption)
				.build())
			.save(() -> {
				try {
					LoadsOfLodsClient.updateConfig(new LodConfig(
						distance[0],
						Math.min(hysteresis[0], distance[0]),
						cacheMiB[0],
						factor[0],
						tint[0]
					));
				} catch (RuntimeException exception) {
					LoadsOfLods.LOGGER.error("Could not save Loads of Lods settings", exception);
				}
			})
			.build()
			.generateScreen(parent);
	}
}
