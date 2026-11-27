package com.malaron.atlasstream.streaming;

import java.util.ArrayList;
import java.util.List;

public final class VirtualTextureTileBundleBuilder {
	private final VirtualTextureTileBuilder albedo;
	private final VirtualTextureTileBuilder normal;
	private final VirtualTextureTileBuilder specular;

	public VirtualTextureTileBundleBuilder(
		VirtualTextureTileBuilder albedo,
		VirtualTextureTileBuilder normal,
		VirtualTextureTileBuilder specular
	) {
		this.albedo = albedo;
		this.normal = normal;
		this.specular = specular;
	}

	public Bundle build(VirtualTextureTileKey key, int mipLevelCount) {
		List<VirtualTextureTileBuilder.TileMip> albedoMips = albedo.build(key, mipLevelCount);
		List<VirtualTextureTileBuilder.TileMip> normalMips = normal.build(key, mipLevelCount);
		List<VirtualTextureTileBuilder.TileMip> specularMips = specular.build(key, mipLevelCount);
		int count = albedoMips.size();
		if (normalMips.size() != count || specularMips.size() != count) {
			throw new IllegalStateException("Virtual PBR tile mip chains do not match");
		}
		for (int index = 0; index < count; index++) {
			if (albedoMips.get(index).width() != normalMips.get(index).width()
					|| albedoMips.get(index).width() != specularMips.get(index).width()
					|| albedoMips.get(index).height() != normalMips.get(index).height()
					|| albedoMips.get(index).height() != specularMips.get(index).height()) {
				throw new IllegalStateException("Virtual PBR tile dimensions do not match");
			}
		}
		return new Bundle(
			new ArrayList<>(albedoMips),
			new ArrayList<>(normalMips),
			new ArrayList<>(specularMips)
		);
	}

	public record Bundle(
		List<VirtualTextureTileBuilder.TileMip> albedo,
		List<VirtualTextureTileBuilder.TileMip> normal,
		List<VirtualTextureTileBuilder.TileMip> specular
	) {
		public Bundle {
			albedo = List.copyOf(albedo);
			normal = List.copyOf(normal);
			specular = List.copyOf(specular);
		}
	}
}
