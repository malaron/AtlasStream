package com.malaron.atlasstream.client.diagnostics;

import java.util.HashSet;
import java.util.Set;

import com.malaron.atlasstream.AtlasStream;
import com.malaron.atlasstream.client.atlas.AtlasRuntimeState;
import com.malaron.atlasstream.client.atlas.NearAtlas;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

public final class TerrainAlphaDiagnostics {
	private static final long SAMPLE_NANOS = 5_000_000_000L;
	private static final Set<String> seen = new HashSet<>();
	private static long deadline;

	private TerrainAlphaDiagnostics() {
	}

	public static void start() {
		seen.clear();
		deadline = System.nanoTime() + SAMPLE_NANOS;
		AtlasStream.LOGGER.info("Terrain alpha diagnostics started for five seconds");
	}

	public static boolean isActive() {
		return deadline != 0;
	}

	public static void afterDraw(boolean translucent, boolean fragmentDiscard, String lod) {
		if (deadline == 0) {
			return;
		}
		if (System.nanoTime() >= deadline) {
			deadline = 0;
			AtlasStream.LOGGER.info("Terrain alpha diagnostics finished; captured {} distinct pass/program states", seen.size());
			return;
		}
		int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		String key = translucent + ":" + fragmentDiscard + ":" + lod + ":" + program;
		if (!seen.add(key)) {
			return;
		}
		if (program == 0 || !GL20.glIsProgram(program)) {
			AtlasStream.LOGGER.warn("Terrain alpha: translucent={}, fragmentDiscard={}, lod={}, no active OpenGL program after draw",
				translucent, fragmentDiscard, lod);
			return;
		}
		int cutoff = GL20.glGetUniformLocation(program, "iris_currentAlphaTest");
		int legacy = GL20.glGetUniformLocation(program, "alphaTestRef");
		String cutoffValue = cutoff < 0 ? "absent" : Float.toString(GL20.glGetUniformf(program, cutoff));
		String legacyValue = legacy < 0 ? "absent" : Float.toString(GL20.glGetUniformf(program, legacy));
		AtlasStream.LOGGER.info(
			"Terrain alpha: translucent={}, fragmentDiscard={}, lod={}, program={}, iris_currentAlphaTest={}, alphaTestRef={}",
			translucent, fragmentDiscard, lod, program, cutoffValue, legacyValue
		);
		NearAtlas near = AtlasRuntimeState.activeNearAtlas();
		AtlasStream.LOGGER.info(
			"Terrain alpha samplers: program={}, lod={}, nearAlbedo={}, {}",
			program, lod, near == null ? "unavailable" : Integer.toString(near.albedoGlId()),
			samplerInfo(program, "gtexture") + ", " + samplerInfo(program, "gcolor")
				+ ", " + samplerInfo(program, "u_AtlasStreamNearRemap")
		);
	}

	private static String samplerInfo(int program, String name) {
		int location = GL20.glGetUniformLocation(program, name);
		if (location < 0) {
			return name + "=absent";
		}
		int unit = GL20.glGetUniformi(program, location);
		int units = GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
		if (unit < 0 || unit >= units) {
			return name + "=invalid texture unit " + unit;
		}
		int previousUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
		try {
			GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
			int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
			if (texture == 0) {
				return name + "=unit " + unit + " unbound";
			}
			return name + "=unit " + unit + " texture " + texture
				+ " size " + GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH)
				+ "x" + GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT)
				+ " maxMip " + GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL);
		} finally {
			GL13.glActiveTexture(previousUnit);
		}
	}
}
