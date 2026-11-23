package com.malaron.atlasstream.streaming;

public final class NearMovementLookahead {
	private static final double LOOKAHEAD_BLOCKS = 12.0;
	private static final double MIN_MOVEMENT = 0.05;
	private static final double MAX_MOVEMENT = 32.0;
	private static final long HOLD_NANOS = 1_000_000_000L;
	private boolean initialized;
	private double previousX;
	private double previousZ;
	private double offsetX;
	private double offsetZ;
	private long lastMovement;

	public void update(double x, double z, long now) {
		if (!initialized) {
			initialized = true;
			previousX = x;
			previousZ = z;
			return;
		}
		double dx = x - previousX;
		double dz = z - previousZ;
		double distance = Math.hypot(dx, dz);
		if (distance > MAX_MOVEMENT) {
			offsetX = 0;
			offsetZ = 0;
			lastMovement = 0;
		} else if (distance >= MIN_MOVEMENT) {
			offsetX = LOOKAHEAD_BLOCKS * dx / distance;
			offsetZ = LOOKAHEAD_BLOCKS * dz / distance;
			lastMovement = now;
		} else if (lastMovement != 0 && now - lastMovement > HOLD_NANOS) {
			offsetX = 0;
			offsetZ = 0;
			lastMovement = 0;
		}
		if (distance >= MIN_MOVEMENT) {
			previousX = x;
			previousZ = z;
		}
	}

	public double targetX(double cameraX) {
		return cameraX + offsetX;
	}

	public double targetZ(double cameraZ) {
		return cameraZ + offsetZ;
	}
}
