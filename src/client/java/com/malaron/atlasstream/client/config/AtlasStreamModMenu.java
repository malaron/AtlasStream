package com.malaron.atlasstream.client.config;

import com.malaron.atlasstream.AtlasStream;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.fabricmc.loader.api.FabricLoader;

public final class AtlasStreamModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> {
			if (!FabricLoader.getInstance().isModLoaded("yet_another_config_lib_v3")) {
				AtlasStream.LOGGER.warn("Install Yet Another Config Lib to edit AtlasStream settings in game");
				return parent;
			}
			try {
				return LodConfigScreen.create(parent);
			} catch (LinkageError error) {
				AtlasStream.LOGGER.error("Could not open the AtlasStream config screen", error);
				return parent;
			}
		};
	}
}
