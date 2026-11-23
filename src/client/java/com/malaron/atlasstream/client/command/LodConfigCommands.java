package com.malaron.atlasstream.client.command;

import java.util.function.UnaryOperator;

import com.malaron.atlasstream.client.AtlasStreamClient;
import com.malaron.atlasstream.lod.LodConfig;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class LodConfigCommands {
	private LodConfigCommands() {
	}

	public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
		return literal("config")
			.executes(context -> show(context.getSource()))
			.then(literal("distance")
				.then(argument("chunks", IntegerArgumentType.integer(
						LodConfig.MIN_HIGH_RESOLUTION_DISTANCE_CHUNKS,
						LodConfig.MAX_HIGH_RESOLUTION_DISTANCE_CHUNKS))
					.executes(context -> update(
						context.getSource(),
						config -> config.withHighResolutionDistance(
							IntegerArgumentType.getInteger(context, "chunks") * LodConfig.BLOCKS_PER_CHUNK
						),
						"High-resolution distance updated to "
							+ IntegerArgumentType.getInteger(context, "chunks") + " chunk(s)."
					))))
			.then(literal("hysteresis")
				.then(argument("blocks", DoubleArgumentType.doubleArg(
						LodConfig.MIN_HYSTERESIS, LodConfig.MAX_HYSTERESIS))
					.executes(context -> update(
						context.getSource(),
						config -> config.withHysteresis(DoubleArgumentType.getDouble(context, "blocks")),
						"Hysteresis updated."
					))))
			.then(literal("cache")
				.then(argument("mib", LongArgumentType.longArg(
						LodConfig.MIN_HIGH_RESOLUTION_CACHE_MIB,
						LodConfig.MAX_HIGH_RESOLUTION_CACHE_MIB))
					.executes(context -> update(
						context.getSource(),
						config -> config.withHighResolutionCacheMiB(LongArgumentType.getLong(context, "mib")),
						"High-resolution cache budget updated."
					))))
			.then(literal("target-resolution")
				.then(argument("pixels", IntegerArgumentType.integer(
						LodConfig.ALLOWED_TARGET_RESOLUTIONS.getFirst(),
						LodConfig.ALLOWED_TARGET_RESOLUTIONS.getLast()))
					.executes(context -> update(
						context.getSource(),
						config -> config.withTargetResolution(IntegerArgumentType.getInteger(context, "pixels")),
						"Low-atlas sprite target updated. Rebuild/reload the atlases to apply it."
					))))
			.then(literal("debug-tint")
				.then(argument("enabled", BoolArgumentType.bool())
					.executes(context -> update(
						context.getSource(),
						config -> config.withDebugTintLowResolution(BoolArgumentType.getBool(context, "enabled")),
						"Low-resolution debug tint updated. Rebuild/reload the atlases to apply it."
					))))
			.then(literal("enable-on-launch")
				.then(argument("enabled", BoolArgumentType.bool())
					.executes(context -> update(
						context.getSource(),
						config -> config.withEnableOnLaunch(BoolArgumentType.getBool(context, "enabled")),
						"Enable-on-launch updated. Takes effect next launch."
					))));
	}

	private static int show(FabricClientCommandSource source) {
		LodConfig config = AtlasStreamClient.getConfig();
		source.sendFeedback(Component.literal(
			"LOD config: distance=" + (config.highResolutionDistance() / LodConfig.BLOCKS_PER_CHUNK)
				+ " chunks, hysteresis=" + config.hysteresis()
				+ " blocks, cache=" + config.highResolutionCacheMiB()
				+ " MiB, targetResolution=" + config.targetResolution() + "px"
				+ ", debugTint=" + config.debugTintLowResolution()
				+ ", enableOnLaunch=" + config.enableOnLaunch()
		));
		return 1;
	}

	private static int update(
		FabricClientCommandSource source,
		UnaryOperator<LodConfig> update,
		String message
	) {
		try {
			AtlasStreamClient.updateConfig(update.apply(AtlasStreamClient.getConfig()));
			source.sendFeedback(Component.literal(message + " Saved configuration."));
			return 1;
		} catch (RuntimeException exception) {
			source.sendError(Component.literal(exception.getMessage()));
			return 0;
		}
	}
}
