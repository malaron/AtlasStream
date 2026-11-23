package com.malaron.atlasstream.client.diagnostics;

import com.malaron.atlasstream.AtlasStream;
import com.malaron.atlasstream.client.atlas.AtlasRuntimeState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import org.jspecify.annotations.Nullable;

public final class LodDebugEntry implements DebugScreenEntry {
	static final Identifier ID = Identifier.fromNamespaceAndPath(AtlasStream.MOD_ID, "lod_status");

	public static void register() {
		DebugScreenEntries.register(ID, new LodDebugEntry());
	}

	// New entries start disabled; turn this one on the first time only so it shows without digging through F3 options.
	public static void enableOnce(Minecraft client) {
		java.nio.file.Path marker = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
			.resolve("atlasstream.debug-entry-enabled");
		if (java.nio.file.Files.exists(marker)) {
			return;
		}
		client.debugEntries.setStatus(ID, DebugScreenEntryStatus.IN_OVERLAY);
		try {
			java.nio.file.Files.writeString(marker, "");
		} catch (java.io.IOException exception) {
			AtlasStream.LOGGER.warn("Could not record the F3 entry default", exception);
		}
	}

	@Override
	public void display(
		DebugScreenDisplayer displayer,
		@Nullable Level level,
		@Nullable LevelChunk clientChunk,
		@Nullable LevelChunk serverChunk
	) {
		for (String line : AtlasRuntimeState.debugLines()) {
			displayer.addLine(line);
		}
		if (AtlasRuntimeState.hasLowAtlas()) {
			for (String line : VramReport.summary()) {
				displayer.addLine("[AtlasStream] " + line);
			}
		}
	}

	@Override
	public boolean isAllowed(boolean reducedDebugInfo) {
		return true;
	}
}
