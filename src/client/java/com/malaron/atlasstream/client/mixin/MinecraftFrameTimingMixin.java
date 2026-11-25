package com.malaron.atlasstream.client.mixin;

import com.malaron.atlasstream.client.diagnostics.LodPerformanceStats;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftFrameTimingMixin {
	@Unique private long atlasStream$frameStartNanos;

	@Inject(method = "renderFrame", at = @At("HEAD"))
	private void atlasStream$startFrameTiming(boolean tick, CallbackInfo callback) {
		atlasStream$frameStartNanos = LodPerformanceStats.isRecording() ? System.nanoTime() : 0;
	}

	@Inject(method = "renderFrame", at = @At("RETURN"))
	private void atlasStream$finishFrameTiming(boolean tick, CallbackInfo callback) {
		long started = atlasStream$frameStartNanos;
		if (started != 0) {
			LodPerformanceStats.recordFrame(System.nanoTime() - started);
		}
	}
}
