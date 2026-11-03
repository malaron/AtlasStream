package com.malaron.loadsoflods.client;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.malaron.loadsoflods.client.mixin.AbstractTextureAccessor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;

// Estimates GPU memory held by registered textures (assumes 4 bytes per pixel). It cannot see
// shader render targets, buffers or driver overhead, so it is a lower bound on total VRAM.
final class VramReport {
	record Entry(String name, int width, int height, int layers, int mips, long bytes) {
	}

	private VramReport() {
	}

	static List<String> build(int topCount) {
		Set<GpuTexture> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		List<Entry> entries = new ArrayList<>();
		Map<?, ?> registered = registeredTextures();
		if (registered != null) {
			for (Map.Entry<?, ?> mapEntry : registered.entrySet()) {
				if (mapEntry.getValue() instanceof AbstractTexture texture) {
					add(entries, seen, String.valueOf(mapEntry.getKey()), texture);
				}
			}
		}
		for (Map.Entry<Integer, Object> holder : IrisPbrAtlasReducer.loadedHolders().entrySet()) {
			Object value = holder.getValue();
			for (String method : new String[] {"normalTexture", "specularTexture"}) {
				try {
					Object texture = value.getClass().getMethod(method).invoke(value);
					if (texture instanceof AbstractTexture abstractTexture) {
						add(entries, seen, "iris-pbr:" + holder.getKey() + ":" + method, abstractTexture);
					}
				} catch (ReflectiveOperationException | RuntimeException ignored) {
				}
			}
		}
		long total = entries.stream().mapToLong(Entry::bytes).sum();
		entries.sort(Comparator.comparingLong(Entry::bytes).reversed());
		List<String> lines = new ArrayList<>();
		lines.add(String.format("Registered textures: %d, about %.2f GiB (lower bound; excludes shader targets/buffers)", entries.size(), gib(total)));
		for (int index = 0; index < Math.min(topCount, entries.size()); index++) {
			Entry entry = entries.get(index);
			lines.add(String.format(
				"%.2f GiB  %dx%d x%d, %d mips  %s",
				gib(entry.bytes()), entry.width(), entry.height(), entry.layers(), entry.mips(), entry.name()
			));
		}
		return lines;
	}

	private static void add(List<Entry> entries, Set<GpuTexture> seen, String name, AbstractTexture texture) {
		GpuTexture gpu;
		try {
			gpu = ((AbstractTextureAccessor) texture).loadsOfLods$getTexture();
		} catch (RuntimeException exception) {
			return;
		}
		if (gpu == null || !seen.add(gpu)) {
			return;
		}
		long bytes = 0;
		int mips = Math.max(1, gpu.getMipLevels());
		int layers = Math.max(1, gpu.getDepthOrLayers());
		for (int mip = 0; mip < mips; mip++) {
			bytes += (long) Math.max(1, gpu.getWidth(mip)) * Math.max(1, gpu.getHeight(mip)) * layers * 4L;
		}
		entries.add(new Entry(name, gpu.getWidth(0), gpu.getHeight(0), layers, mips, bytes));
	}

	private static Map<?, ?> registeredTextures() {
		Object manager = Minecraft.getInstance().getTextureManager();
		for (Field field : manager.getClass().getDeclaredFields()) {
			if (Map.class.isAssignableFrom(field.getType())) {
				try {
					field.setAccessible(true);
					Map<?, ?> map = (Map<?, ?>) field.get(manager);
					if (map != null && map.values().stream().anyMatch(AbstractTexture.class::isInstance)) {
						return map;
					}
				} catch (ReflectiveOperationException | RuntimeException ignored) {
				}
			}
		}
		return null;
	}

	private static double gib(long bytes) {
		return bytes / (1024.0 * 1024.0 * 1024.0);
	}
}
