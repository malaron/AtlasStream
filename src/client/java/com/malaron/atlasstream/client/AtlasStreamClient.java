package com.malaron.atlasstream.client;

import com.malaron.atlasstream.AtlasStream;
import com.malaron.atlasstream.client.atlas.AtlasRuntimeState;
import com.malaron.atlasstream.client.command.LodCommands;
import com.malaron.atlasstream.client.diagnostics.LodDebugEntry;
import com.malaron.atlasstream.lod.LodConfig;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

public class AtlasStreamClient implements ClientModInitializer {
	private static LodConfig config;
	public static LodConfig getConfig() {
		if (config == null) {
			throw new IllegalStateException("Client configuration has not been initialized");
		}
		return config;
	}

	public static LodConfig getConfigIfLoaded() {
		return config;
	}

	public static java.nio.file.Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("atlasstream.properties");
	}

	// Distance, hysteresis and cache budget are read live; target resolution applies on the next preview build.
	public static void updateConfig(LodConfig updated) {
		LodConfig.save(configFile(), updated);
		config = updated;
	}

	@Override
	public void onInitializeClient() {
		LodCommands.register();
		LodDebugEntry.register();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED
			.register(LodDebugEntry::enableOnce);
		ClientTickEvents.END_CLIENT_TICK.register(client -> AtlasRuntimeState.serviceVirtualTextureRequests());
		config = LodConfig.load(
			FabricLoader.getInstance().getConfigDir().resolve("atlasstream.properties")
		);
		AtlasStream.LOGGER.info(
			"Loaded texture LOD settings: high-resolution distance {} blocks, hysteresis {} blocks, high-resolution cache target {} MiB, disk cache target {} MiB",
			config.highResolutionDistance(),
			config.hysteresis(),
			config.highResolutionCacheMiB(),
			config.diskCacheMiB()
		);
		if (config.enableOnLaunch()) {
			AtlasRuntimeState.armLaunchRebuild();
		}
	}
}