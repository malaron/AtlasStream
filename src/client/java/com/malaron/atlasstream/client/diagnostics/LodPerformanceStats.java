package com.malaron.atlasstream.client.diagnostics;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.malaron.atlasstream.lod.SectionLod;

public final class LodPerformanceStats {
	private static final Map<SectionLod, Metrics> METRICS = new EnumMap<>(SectionLod.class);
	private static final ThreadLocal<Metrics> ACTIVE_PREPARE = new ThreadLocal<>();
	private static volatile boolean recording;
	private static long sampleStartNanos;
	private static long filterNanos;
	private static long filterCalls;
	private static long animationBlendNanos;
	private static long animationBlendRequests;
	private static long animationBlendBuilds;
	private static long nearRefreshNanos;
	private static long nearRefreshCalls;
	private static long nearRefreshPeakNanos;
	private static long nearPressureNanos;
	private static long nearPressureCalls;
	private static long nearPressureEvictions;
	private static long nearDecommittedPages;
	private static long nearDecommitCalls;
	private static long nearPrepareNanos;
	private static long nearPrepareCalls;
	private static long nearPreparePeakNanos;
	private static long nearUploadNanos;
	private static long nearUploadCalls;
	private static long nearUploadPeakNanos;

	static {
		for (SectionLod lod : SectionLod.values()) {
			METRICS.put(lod, new Metrics());
		}
	}

	private LodPerformanceStats() {
	}

	public static synchronized void start() {
		for (Metrics metrics : METRICS.values()) {
			metrics.reset();
		}
		filterNanos = 0;
		filterCalls = 0;
		animationBlendNanos = 0;
		animationBlendRequests = 0;
		animationBlendBuilds = 0;
		nearRefreshNanos = 0;
		nearRefreshCalls = 0;
		nearRefreshPeakNanos = 0;
		nearPressureNanos = 0;
		nearPressureCalls = 0;
		nearPressureEvictions = 0;
		nearDecommittedPages = 0;
		nearDecommitCalls = 0;
		nearPrepareNanos = 0;
		nearPrepareCalls = 0;
		nearPreparePeakNanos = 0;
		nearUploadNanos = 0;
		nearUploadCalls = 0;
		nearUploadPeakNanos = 0;
		sampleStartNanos = System.nanoTime();
		recording = true;
	}

	public static boolean isRecording() {
		return recording;
	}

	public static long sampleDurationSeconds() {
		return 30;
	}

	public static synchronized void recordFilter(
		long elapsedNanos,
		int highSectionCount,
		int lowSectionCount
	) {
		if (!recording) {
			return;
		}
		filterNanos += elapsedNanos;
		filterCalls++;
		Metrics activePrepare = ACTIVE_PREPARE.get();
		if (activePrepare != null) {
			activePrepare.filterNanosDuringPrepare += elapsedNanos;
		}
		METRICS.get(SectionLod.HIGH_RESOLUTION).sections += highSectionCount;
		METRICS.get(SectionLod.LOW_RESOLUTION).sections += lowSectionCount;
	}

	public static void beginPrepare(SectionLod lod) {
		if (recording) {
			ACTIVE_PREPARE.set(METRICS.get(lod));
		}
	}

	public static synchronized void recordPrepare(SectionLod lod, long elapsedNanos) {
		Metrics metrics = ACTIVE_PREPARE.get();
		ACTIVE_PREPARE.remove();
		if (recording && metrics != null) {
			metrics.prepareNanos += Math.max(0, elapsedNanos - metrics.filterNanosDuringPrepare);
			metrics.filterNanosDuringPrepare = 0;
			metrics.prepareCalls++;
		}
	}

	public static synchronized void recordDraw(SectionLod lod, long elapsedNanos) {
		if (recording) {
			Metrics metrics = METRICS.get(lod);
			metrics.drawNanos += elapsedNanos;
			metrics.drawCalls++;
		}
	}

	public static synchronized void recordAnimationBlend(long elapsedNanos) {
		if (recording) {
			animationBlendNanos += elapsedNanos;
			animationBlendRequests++;
		}
	}

	public static synchronized void recordAnimationBlendBuild() {
		if (recording) {
			animationBlendBuilds++;
		}
	}

	public static synchronized void recordNearRefresh(long elapsedNanos) {
		if (recording) {
			nearRefreshNanos += elapsedNanos;
			nearRefreshCalls++;
			nearRefreshPeakNanos = Math.max(nearRefreshPeakNanos, elapsedNanos);
		}
	}

	public static synchronized void recordNearPressure(long elapsedNanos, int evictions) {
		if (recording) {
			nearPressureNanos += elapsedNanos;
			nearPressureCalls++;
			nearPressureEvictions += evictions;
		}
	}

	public static synchronized void recordNearPrepare(long elapsedNanos) {
		if (recording) {
			nearPrepareNanos += elapsedNanos;
			nearPrepareCalls++;
			nearPreparePeakNanos = Math.max(nearPreparePeakNanos, elapsedNanos);
		}
	}

	public static synchronized void recordNearDecommit(int pages, int calls) {
		if (recording) {
			nearDecommittedPages += pages;
			nearDecommitCalls += calls;
		}
	}

	public static synchronized void recordNearUpload(long elapsedNanos) {
		if (recording) {
			nearUploadNanos += elapsedNanos;
			nearUploadCalls++;
			nearUploadPeakNanos = Math.max(nearUploadPeakNanos, elapsedNanos);
		}
	}

	public static synchronized List<String> stopAndFormat() {
		if (!recording) {
			return List.of();
		}
		recording = false;
		double seconds = (System.nanoTime() - sampleStartNanos) / 1_000_000_000.0;
		List<String> lines = new ArrayList<>();
		lines.add(String.format(Locale.ROOT, "LOD CPU sample: %.1f seconds", seconds));
		lines.add(String.format(
			Locale.ROOT,
			"Filter both LOD lists: %.1f ms/%d scans (included in prepare timing where run)",
			toMilliseconds(filterNanos),
			filterCalls
		));
		for (SectionLod lod : SectionLod.values()) {
			Metrics metrics = METRICS.get(lod);
			lines.add(String.format(
				Locale.ROOT,
				"%s: sections %d; prepare excluding filtering %.1f ms/%d calls; render submit %.1f ms/%d calls",
				lod == SectionLod.HIGH_RESOLUTION ? "High" : "Low",
				metrics.sections,
				toMilliseconds(metrics.prepareNanos),
				metrics.prepareCalls,
				toMilliseconds(metrics.drawNanos),
				metrics.drawCalls
			));
		}
		lines.add(String.format(
			Locale.ROOT,
			"Low-atlas animation blends: %.1f ms/%d requests; %d built, %d reused",
			toMilliseconds(animationBlendNanos),
			animationBlendRequests,
			animationBlendBuilds,
			animationBlendRequests - animationBlendBuilds
		));
		lines.add(String.format(
			Locale.ROOT,
			"Near-atlas refresh: %.1f ms/%d calls, peak %.1f ms",
			toMilliseconds(nearRefreshNanos),
			nearRefreshCalls,
			toMilliseconds(nearRefreshPeakNanos)
		));
		lines.add(String.format(
			Locale.ROOT,
			"  Slot pressure (included in refresh): %.1f ms/%d calls, %d evictions",
			toMilliseconds(nearPressureNanos), nearPressureCalls, nearPressureEvictions
		));
		lines.add(String.format(
			Locale.ROOT,
			"  Sparse pages released: %d across %d decommit calls",
			nearDecommittedPages, nearDecommitCalls
		));
		lines.add(String.format(
			Locale.ROOT,
			"Near sprite start: %.1f ms/%d calls, peak %.1f ms; finish/upload: %.1f ms/%d calls, peak %.1f ms",
			toMilliseconds(nearPrepareNanos), nearPrepareCalls, toMilliseconds(nearPreparePeakNanos),
			toMilliseconds(nearUploadNanos), nearUploadCalls, toMilliseconds(nearUploadPeakNanos)
		));
		lines.add("Render-thread timings can include driver waits; they exclude GPU execution and other world-render work.");
		return List.copyOf(lines);
	}

	private static double toMilliseconds(long nanos) {
		return nanos / 1_000_000.0;
	}

	private static final class Metrics {
		private long sections;
		private long prepareNanos;
		private long prepareCalls;
		private long filterNanosDuringPrepare;
		private long drawNanos;
		private long drawCalls;

		private void reset() {
			sections = 0;
			prepareNanos = 0;
			prepareCalls = 0;
			filterNanosDuringPrepare = 0;
			drawNanos = 0;
			drawCalls = 0;
		}
	}
}
