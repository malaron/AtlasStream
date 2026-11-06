package com.malaron.loadsoflods.client.config;

import java.util.List;

import com.malaron.loadsoflods.LoadsOfLods;
import com.malaron.loadsoflods.client.AtlasBuildCommand;
import com.malaron.loadsoflods.client.AtlasPreviewState;
import com.malaron.loadsoflods.client.LoadsOfLodsClient;
import com.malaron.loadsoflods.lod.LodConfig;
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
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

// Only loaded when YACL is present; LoadsOfLodsModMenu guards the entry point.
final class LodConfigScreen {
	private LodConfigScreen() {
	}

	static Screen create(Screen parent) {
		LodConfig current = LoadsOfLodsClient.getConfig();
		LodConfig defaults = new LodConfig(128.0, 16.0, LodConfig.DEFAULT_HIGH_RESOLUTION_CACHE_MIB, LodConfig.DEFAULT_DOWNSCALE_FACTOR);
		int[] distanceChunks = {(int) Math.round(current.highResolutionDistance() / 16.0)};
		double[] hysteresis = {current.hysteresis()};
		long[] cacheMiB = {current.highResolutionCacheMiB()};
		int[] factor = {current.downscaleFactor()};
		boolean[] tint = {current.debugTintLowResolution()};
		boolean[] onLaunch = {current.enableOnLaunch()};

		boolean active = AtlasPreviewState.isDistancePreviewEnabled();
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
				AtlasBuildCommand.enableDistancePreview(
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

		Option<Integer> factorOption = Option.<Integer>createBuilder()
			.name(Component.literal("Texture reduction (Requires texture reload F3+T)"))
			.description(OptionDescription.of(Component.literal(
				"How much the far/low block atlas is shrunk (1:N per axis). Higher saves more VRAM but looks blurrier. "
					+ "Takes effect when the atlas is rebuilt; reload resource packs if LOD is already active."
			)))
			.binding(defaults.downscaleFactor(), () -> factor[0], value -> factor[0] = value)
			.controller(option -> CyclingListControllerBuilder.create(option)
				.values(List.of(2, 4, 8))
				.formatValue(value -> Component.literal("1:" + value)))
			.build();

		Option<Integer> distanceOption = Option.<Integer>createBuilder()
			.name(Component.literal("High-resolution distance (chunks)"))
			.description(OptionDescription.of(Component.literal(
				"Sections within this many chunks use full-resolution textures. Sections switch a whole chunk at a time. 0 uses the reduced textures everywhere, handy for comparing."
			)))
			.binding((int) (defaults.highResolutionDistance() / 16.0), () -> distanceChunks[0], value -> distanceChunks[0] = value)
			.controller(option -> IntegerSliderControllerBuilder.create(option)
				.range(0, 32)
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
			.controller(option -> DoubleSliderControllerBuilder.create(option).range(0.0, 128.0).step(1.0))
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
			.title(Component.literal("Loads of Lods"))
			.category(ConfigCategory.createBuilder()
				.name(Component.literal("Textures"))
				.option(activateOption)
				.option(factorOption)
				.option(distanceOption)
				.option(hysteresisOption)
				.option(cacheOption)
				.option(tintOption)
				.option(onLaunchOption)
				.build())
			.save(() -> {
				try {
					double distance = distanceChunks[0] * 16.0;
					LoadsOfLodsClient.updateConfig(new LodConfig(
						distance,
						distance > 0.0 ? Math.min(hysteresis[0], distance) : hysteresis[0],
						cacheMiB[0],
						factor[0],
						tint[0],
						onLaunch[0]
					));				} catch (RuntimeException exception) {
					LoadsOfLods.LOGGER.error("Could not save Loads of Lods settings", exception);
				}
			})
			.build();
		return new YACLScreen(config, parent) {
			@Override
			public void tick() {
				boolean enabled = AtlasPreviewState.isDistancePreviewEnabled();
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
			return AtlasPreviewState.isDistancePreviewEnabled() ? active : inactive;
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
