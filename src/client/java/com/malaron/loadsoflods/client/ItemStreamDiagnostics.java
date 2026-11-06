package com.malaron.loadsoflods.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

public final class ItemStreamDiagnostics {
	private static final long RECENT_NANOS = 15_000_000_000L;
	private static final int MAX_ENTRIES = 32;
	private static final Map<TextureAtlasSprite, Long> sprites = new LinkedHashMap<>();
	private static final Map<String, Long> draws = new LinkedHashMap<>();

	private ItemStreamDiagnostics() {
	}

	public static void recordSprite(TextureAtlasSprite sprite) {
		record(sprites, sprite);
	}

	public static void recordDraw(String draw) {
		record(draws, draw);
	}

	private static <T> void record(Map<T, Long> entries, T key) {
		if (!entries.containsKey(key) && entries.size() >= MAX_ENTRIES) {
			entries.remove(entries.keySet().iterator().next());
		}
		entries.put(key, System.nanoTime());
	}

	public static List<String> report() {
		long now = System.nanoTime();
		sprites.values().removeIf(time -> now - time > RECENT_NANOS);
		draws.values().removeIf(time -> now - time > RECENT_NANOS);
		List<String> lines = new ArrayList<>();
		for (TextureAtlasSprite sprite : sprites.keySet()) {
			lines.add(AtlasPreviewState.describeItemSpriteResidency(sprite));
		}
		if (sprites.isEmpty()) {
			lines.add("No first-person item sprites seen in the last 15 seconds.");
		}
		lines.add("Recent item draws: " + (draws.isEmpty() ? "none" : String.join(", ", draws.keySet())));
		return lines;
	}
}
