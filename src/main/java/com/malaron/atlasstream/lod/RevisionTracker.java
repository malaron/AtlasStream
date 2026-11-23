package com.malaron.atlasstream.lod;

public final class RevisionTracker {
	private long revision;
	private boolean initialized;

	public boolean update(long currentRevision) {
		if (initialized && revision == currentRevision) {
			return false;
		}
		initialized = true;
		revision = currentRevision;
		return true;
	}
}
