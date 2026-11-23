package com.malaron.atlasstream.client.atlas;

import com.malaron.atlasstream.client.AtlasStreamClient;
import com.malaron.atlasstream.lod.LodConfig;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

/**
 * The block atlas is only used for its layout: the albedo comes from the resource pack and is streamed or
 * downscaled by this mod. A virtual atlas therefore keeps the stitched layout but never allocates the full GPU
 * texture, which lets packs larger than the GPU's texture limit load.
 */
public final class VirtualAtlasPolicy {
	/** Sodium encodes texture coordinates in 16 bits, so a larger layout would lose texel precision. */
	public static final int MAX_VIRTUAL_SIZE = 65536;

	private static Boolean always;

	private VirtualAtlasPolicy() {
	}

	public static boolean available(Identifier location) {
		return TextureAtlas.LOCATION_BLOCKS.equals(location) && FabricLoader.getInstance().isModLoaded("sodium");
	}

	public static boolean shouldVirtualize(Identifier location, int width, int height, int maxSupportedSize) {
		return available(location) && (width > maxSupportedSize || height > maxSupportedSize || always());
	}

	private static boolean always() {
		if (always == null) {
			always = LodConfig.readAlwaysVirtualAtlas(AtlasStreamClient.configFile());
		}
		return always;
	}
}
