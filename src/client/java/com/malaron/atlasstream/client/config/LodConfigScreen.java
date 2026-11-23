package com.malaron.atlasstream.client.config;

import java.util.List;

import com.malaron.atlasstream.AtlasStream;
import com.malaron.atlasstream.client.AtlasStreamClient;
import com.malaron.atlasstream.client.atlas.AtlasRuntimeState;
import com.malaron.atlasstream.client.command.LodCommands;
import com.malaron.atlasstream.lod.LodConfig;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.CyclingListControllerBuilder;
import dev.isxander.yacl3.api.controller.DoubleSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.LongSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import dev.isxander.yacl3.gui.YACLScreen;

// Only loaded when YACL is present; AtlasStreamModMenu guards the entry point.
final class LodConfigScreen {
	private LodConfigScreen() {
	}

	static Screen create(Screen parent) {
		LodConfig current = AtlasStreamClient.getConfig();
		LodConfig defaults = LodConfig.defaults();
		int[] distanceChunks = {
			(int) Math.round(current.highResolutionDistance() / LodConfig.BLOCKS_PER_CHUNK)
		};
		double[] hysteresis = {current.hysteresis()};
		long[] cacheMiB = {current.highResolutionCacheMiB()};
		int[] targetResolution = {current.targetResolution()};
		boolean[] tint = {current.debugTintLowResolution()};
		boolean[] onLaunch = {current.enableOnLaunch()};

		boolean active = AtlasRuntimeState.isDistancePreviewEnabled();
		ButtonOption activateOption = ButtonOption.createBuilder()
			.name(Component.literal("Distance-based LOD"))
			.text(new ActivationLabel())
			.available(!active)
			.description(OptionDescription.of(Component.literal(
				"Activate distance-based LOD in the current world using saved settings. Save changes first. "
					+ "If it is already active, reload resource packs to apply texture reduction or debug tint changes."
			)))
			.action((screen, button) -> {
				if (screen.pendingChanges()) {
					screen.setSaveButtonMessage(
						Component.literal("Save first").withStyle(ChatFormatting.RED),
						Component.literal("Save your settings before activating distance-based LOD.")
					);
					return;
				}
				Minecraft client = Minecraft.getInstance();
				if (client.level == null || client.player == null) {
					screen.setSaveButtonMessage(
						Component.literal("Join a world").withStyle(ChatFormatting.RED),
						Component.literal("Distance-based LOD can only be activated in a world.")
					);
					return;
				}
				screen.onClose();
				LodCommands.enableDistancePreview(
					client,
					message -> client.gui.chatListener().handleSystemMessage(message, true),
					message -> client.gui.chatListener().handleSystemMessage(message.copy().withStyle(ChatFormatting.RED), true)
				);
			})
			.build();

		Option<Boolean> onLaunchOption = Option.<Boolean>createBuilder()
			.name(Component.literal("Enable on launch"))
			.description(OptionDescription.of(Component.literal(
				"Build the reduced atlas automatically after the game starts, once you join a world. Takes effect next launch."
			)))
			.binding(true, () -> onLaunch[0], value -> onLaunch[0] = value)
			.controller(TickBoxControllerBuilder::create)
			.build();

		Option<Integer> targetResolutionOption = Option.<Integer>createBuilder()
			.name(Component.literal("Low-resolution sprite target (Requires texture reload F3+T)"))
			.description(OptionDescription.of(Component.literal(
				"Maximum size of a sprite in the far/low atlas. Larger resource-pack sprites are downscaled; "
					+ "sprites already at or below this size keep their original resolution. Pixels are not upscaled. "
					+ "Takes effect when the atlas is rebuilt; reload resource packs if LOD is already active."
			)))
			.binding(defaults.targetResolution(), () -> targetResolution[0], value -> targetResolution[0] = value)
			.controller(option -> CyclingListControllerBuilder.create(option)
				.values(LodConfig.ALLOWED_TARGET_RESOLUTIONS)
				.formatValue(value -> Component.literal(value + "px")))
			.build();

		Option<Integer> distanceOption = Option.<Integer>createBuilder()
			.name(Component.literal("High-resolution distance (chunks)"))
			.description(OptionDescription.of(Component.literal(
				"Sections within this many chunks use full-resolution textures. Sections switch a whole chunk at a time. 0 uses the reduced textures everywhere, handy for comparing."
			)))
			.binding(
				(int) (defaults.highResolutionDistance() / LodConfig.BLOCKS_PER_CHUNK),
				() -> distanceChunks[0],
				value -> distanceChunks[0] = value
			)
			.controller(option -> IntegerSliderControllerBuilder.create(option)
				.range(
					LodConfig.MIN_HIGH_RESOLUTION_DISTANCE_CHUNKS,
					LodConfig.MAX_HIGH_RESOLUTION_DISTANCE_CHUNKS
				)
				.step(1)
				.formatValue(value -> Component.literal(value == 0 ? "Off" : value + (value == 1 ? " chunk" : " chunks"))))
			.build();

		Option<Double> hysteresisOption = Option.<Double>createBuilder()
			.name(Component.literal("Hysteresis"))
			.description(OptionDescription.of(Component.literal(
				"Extra distance in blocks before switching back, to avoid flicker. Clamped to the high-resolution distance. "
					+ "The Iris near-entry threshold stays at least half the high-resolution distance."
			)))
			.binding(defaults.hysteresis(), () -> hysteresis[0], value -> hysteresis[0] = value)
			.controller(option -> DoubleSliderControllerBuilder.create(option)
				.range(LodConfig.MIN_HYSTERESIS, LodConfig.MAX_HYSTERESIS)
				.step(1.0))
			.build();

		Option<Long> cacheOption = Option.<Long>createBuilder()
			.name(Component.literal("High-resolution cache (MiB)"))
			.description(OptionDescription.of(Component.literal(
				"VRAM budget for full-resolution textures. Without Iris it sizes the streamed tile cache; with Iris it is the soft limit for the near atlas, above which sprites no nearby section uses are released."
			)))
			.binding(defaults.highResolutionCacheMiB(), () -> cacheMiB[0], value -> cacheMiB[0] = value)
			.controller(option -> LongSliderControllerBuilder.create(option)
				.range(LodConfig.MIN_HIGH_RESOLUTION_CACHE_MIB, LodConfig.MAX_HIGH_RESOLUTION_CACHE_MIB)
				.step(64L))
			.build();

		Option<Boolean> tintOption = Option.<Boolean>createBuilder()
			.name(Component.literal("Debug: tint low-resolution textures"))
			.description(OptionDescription.of(Component.literal(
				"Colors the reduced atlas magenta so you can see where it is used. Applies when the atlas is rebuilt."
			)))
			.binding(false, () -> tint[0], value -> tint[0] = value)
			.controller(TickBoxControllerBuilder::create)
			.build();

		YetAnotherConfigLib config = YetAnotherConfigLib.createBuilder()
			.title(Component.literal("AtlasStream"))
			.category(ConfigCategory.createBuilder()
				.name(Component.literal("Textures"))
				.option(activateOption)
				.option(targetResolutionOption)
				.option(distanceOption)
				.option(hysteresisOption)
				.option(cacheOption)
				.option(tintOption)
				.option(onLaunchOption)
				.build())
			.save(() -> {
				try {
					double distance = distanceChunks[0] * LodConfig.BLOCKS_PER_CHUNK;
					AtlasStreamClient.updateConfig(new LodConfig(
						distance,
						LodConfig.clampHysteresis(distance, hysteresis[0]),
						cacheMiB[0],
						targetResolution[0],
						tint[0],
						onLaunch[0]
					));
				} catch (RuntimeException exception) {
					AtlasStream.LOGGER.error("Could not save AtlasStream settings", exception);
				}
			})
			.build();
		return new YACLScreen(config, parent) {
			@Override
			public void tick() {
				boolean enabled = AtlasRuntimeState.isDistancePreviewEnabled();
				if (activateOption.available() == enabled) {
					activateOption.setAvailable(!enabled);
				}
				super.tick();
			}
		};
	}

	// YACL keeps one label component; swapping delegates avoids stale cached visual-order text.
	private static final class ActivationLabel implements Component {
		private final Component active = Component.literal("Active");
		private final Component inactive = Component.literal("Activate");

		private Component current() {
			return AtlasRuntimeState.isDistancePreviewEnabled() ? active : inactive;
		}

		@Override
		public Style getStyle() {
			return current().getStyle();
		}

		@Override
		public ComponentContents getContents() {
			return current().getContents();
		}

		@Override
		public List<Component> getSiblings() {
			return current().getSiblings();
		}

		@Override
		public FormattedCharSequence getVisualOrderText() {
			return current().getVisualOrderText();
		}
	}
}
