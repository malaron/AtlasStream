package com.malaron.loadsoflods.client.config;

import com.malaron.loadsoflods.LoadsOfLods;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

public final class LoadsOfLodsModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> {
			if (!FabricLoader.getInstance().isModLoaded("yet_another_config_lib_v3")) {
				LoadsOfLods.LOGGER.warn("Install Yet Another Config Lib to edit Loads of Lods settings in game");
				return parent;
			}
			try {
				return LodConfigScreen.create(parent);
			} catch (LinkageError error) {
				LoadsOfLods.LOGGER.error("Could not open the Loads of Lods config screen", error);
				return parent;
			}
		};
	}
}
