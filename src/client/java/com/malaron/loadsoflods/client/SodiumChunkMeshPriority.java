package com.malaron.loadsoflods.client;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import com.malaron.loadsoflods.LoadsOfLods;

public final class SodiumChunkMeshPriority {
	private static final ThreadLocal<Double> DISTANCE_SQUARED = new ThreadLocal<>();
	private static final ThreadLocal<Long> SECTION_KEY = new ThreadLocal<>();
	private static final AtomicBoolean ACCESS_FAILURE_LOGGED = new AtomicBoolean();
	private static volatile TaskAccess taskAccess;

	private SodiumChunkMeshPriority() {
	}

	public static void begin(Object task) {
		DISTANCE_SQUARED.remove();
		SECTION_KEY.remove();
		try {
			TaskAccess access = accessFor(task.getClass());
			Object section = access.renderSection().invoke(task);
			long sectionKey = packSection(
				Math.floorDiv(invokeNumber(access.sectionCenterX(), section).intValue(), 16),
				Math.floorDiv(invokeNumber(access.sectionCenterY(), section).intValue(), 16),
				Math.floorDiv(invokeNumber(access.sectionCenterZ(), section).intValue(), 16)
			);
			SECTION_KEY.set(sectionKey);
			AtlasPreviewState.clearSectionSpriteUsage(sectionKey);
			Object camera = access.absoluteCameraPosition().invoke(task);
			double deltaX = invokeNumber(access.cameraX(), camera).doubleValue()
				- invokeNumber(access.sectionCenterX(), section).doubleValue();
			double deltaY = invokeNumber(access.cameraY(), camera).doubleValue()
				- invokeNumber(access.sectionCenterY(), section).doubleValue();
			double deltaZ = invokeNumber(access.cameraZ(), camera).doubleValue()
				- invokeNumber(access.sectionCenterZ(), section).doubleValue();
			DISTANCE_SQUARED.set(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			if (ACCESS_FAILURE_LOGGED.compareAndSet(false, true)) {
				LoadsOfLods.LOGGER.error(
					"Could not read Sodium chunk mesh distance; affected texture requests will receive lowest priority",
					exception
				);
			}
			DISTANCE_SQUARED.set(Double.POSITIVE_INFINITY);
		}
	}

	public static void end() {
		DISTANCE_SQUARED.remove();
		SECTION_KEY.remove();
	}

	// Section of the chunk mesh task running on this thread, or Long.MIN_VALUE when unknown.
	public static long currentSectionKey() {
		Long key = SECTION_KEY.get();
		return key == null ? Long.MIN_VALUE : key;
	}

	public static long packSection(int x, int y, int z) {
		return ((long) x << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
	}

	public static int sectionX(long key) {
		return (int) (key >> 42);
	}

	public static int sectionY(long key) {
		return (int) ((key << 22) >> 43);
	}

	public static int sectionZ(long key) {
		return (int) ((key << 43) >> 43);
	}

	public static double distanceSquared() {
		Double distance = DISTANCE_SQUARED.get();
		return distance == null ? Double.POSITIVE_INFINITY : distance;
	}

	private static TaskAccess accessFor(Class<?> taskClass) throws ReflectiveOperationException {
		TaskAccess current = taskAccess;
		if (current != null && current.taskClass() == taskClass) {
			return current;
		}
		synchronized (SodiumChunkMeshPriority.class) {
			current = taskAccess;
			if (current == null || current.taskClass() != taskClass) {
				current = createAccess(taskClass);
				taskAccess = current;
			}
			return current;
		}
	}

	private static TaskAccess createAccess(Class<?> taskClass) throws ReflectiveOperationException {
		Method renderSection = taskClass.getMethod("getRenderSection");
		Method absoluteCameraPosition = taskClass.getMethod("getAbsoluteCameraPos");
		Class<?> sectionClass = renderSection.getReturnType();
		Class<?> cameraClass = absoluteCameraPosition.getReturnType();
		return new TaskAccess(
			taskClass,
			renderSection,
			absoluteCameraPosition,
			sectionClass.getMethod("getCenterX"),
			sectionClass.getMethod("getCenterY"),
			sectionClass.getMethod("getCenterZ"),
			cameraClass.getMethod("x"),
			cameraClass.getMethod("y"),
			cameraClass.getMethod("z")
		);
	}

	private static Number invokeNumber(Method method, Object target)
			throws ReflectiveOperationException {
		return (Number) method.invoke(target);
	}

	private record TaskAccess(
		Class<?> taskClass,
		Method renderSection,
		Method absoluteCameraPosition,
		Method sectionCenterX,
		Method sectionCenterY,
		Method sectionCenterZ,
		Method cameraX,
		Method cameraY,
		Method cameraZ
	) {
	}
}
