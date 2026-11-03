package com.malaron.loadsoflods.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import com.malaron.loadsoflods.LoadsOfLods;
import com.malaron.loadsoflods.lod.LodConfig;

public class LoadsOfLodsClient implements ClientModInitializer {
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

	@Override
	public void onInitializeClient() {
		AtlasBuildCommand.register();
		LodDebugEntry.register();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED
			.register(LodDebugEntry::enableOnce);
		ClientTickEvents.END_CLIENT_TICK.register(client -> AtlasPreviewState.serviceVirtualTextureRequests());
		config = LodConfig.load(
			FabricLoader.getInstance().getConfigDir().resolve("loads-of-lods.properties")
		);
		LoadsOfLods.LOGGER.info(
			"Loaded texture LOD settings: high-resolution distance {} blocks, hysteresis {} blocks, high-resolution cache target {} MiB",
			config.highResolutionDistance(),
			config.hysteresis(),
			config.highResolutionCacheMiB()
		);
	}
}