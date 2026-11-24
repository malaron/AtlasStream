package com.malaron.atlasstream.client.diagnostics;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.malaron.atlasstream.AtlasStream;

import com.mojang.renderpearl.api.textures.GpuTextureView;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

public final class EntityTextureDiagnostics {
	private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

	private EntityTextureDiagnostics() {
	}

	public static void sample(String renderType, GpuTextureView view) {
		int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		String key = renderType + ":" + program + ":" + System.identityHashCode(view);
		if (program == 0 || LOGGED.size() >= 32 || !LOGGED.add(key)) {
			return;
		}
		AtlasStream.LOGGER.info(
			"Entity texture context: renderType={}, albedoView={}, program={}, {}",
			renderType,
			System.identityHashCode(view),
			program,
			String.join(", ",
				sampler("gtexture", program),
				sampler("gnormal", program),
				sampler("gspecular", program),
				sampler("normal", program),
				sampler("specular", program)
			)
		);
	}

	private static String sampler(String name, int program) {
		int location = GL20.glGetUniformLocation(program, name);
		if (location < 0) {
			return name + "=absent";
		}
		int unit = GL20.glGetUniformi(program, location);
		int previousUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
		try {
			GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
			int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
			return name + "=" + texture + "@"
				+ GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) + "x"
				+ GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT)
				+ " mips=" + GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL);
		} finally {
			GL13.glActiveTexture(previousUnit);
		}
	}
}
