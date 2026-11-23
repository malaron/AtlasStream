package com.malaron.atlasstream.client.streaming;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;

import net.minecraft.resources.Identifier;

public final class VirtualItemPipeline extends RenderPipeline {
	private static final BindGroupLayout VIRTUAL_SAMPLERS = BindGroupLayout.builder()
		.withUniform("u_VirtualTileAtlas", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("u_VirtualPageTable", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("u_VirtualAtlasMetadata", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("u_AtlasStreamLowRemap", UniformType.COMBINED_IMAGE_SAMPLER)
		.build();
	private static final Map<RenderPipeline, VirtualItemPipeline> PIPELINES = new IdentityHashMap<>();

	private VirtualItemPipeline(RenderPipeline original) {
		super(
			Identifier.fromNamespaceAndPath("atlasstream", original.getLocation().getPath()),
			original.getShaders(),
			original.getShaderDefines(),
			layouts(original),
			original.getColorTargetStates().toArray(new ColorTargetState[0]),
			original.getDepthStencilState(),
			original.getPolygonMode(),
			original.isCull(),
			original.getVertexFormatBindings().toArray(new VertexFormat[0]),
			original.getPrimitiveTopology(),
			original.pushConstantSize(),
			original.getSortKey()
		);
	}

	private static ArrayList<BindGroupLayout> layouts(RenderPipeline original) {
		ArrayList<BindGroupLayout> result = new ArrayList<>(original.getBindGroupLayouts());
		result.add(VIRTUAL_SAMPLERS);
		return result;
	}

	public static VirtualItemPipeline forPipeline(RenderPipeline original) {
		return PIPELINES.computeIfAbsent(original, VirtualItemPipeline::new);
	}
}
