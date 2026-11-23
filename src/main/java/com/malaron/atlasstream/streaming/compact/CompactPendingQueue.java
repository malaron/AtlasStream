package com.malaron.atlasstream.streaming.compact;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Queue;

public final class CompactPendingQueue<T> {
	private final ArrayDeque<T> pending = new ArrayDeque<>();
	private boolean dirty;

	public void drainFrom(Queue<T> incoming) {
		T item;
		while ((item = incoming.poll()) != null) {
			pending.addLast(item);
			dirty = true;
		}
	}

	public void prioritiesChanged() {
		dirty = true;
	}

	public T peek(Comparator<T> comparator) {
		sortIfNeeded(comparator);
		return pending.peekFirst();
	}

	public T poll(Comparator<T> comparator) {
		sortIfNeeded(comparator);
		return pending.pollFirst();
	}

	public boolean isEmpty() {
		return pending.isEmpty();
	}

	public int size() {
		return pending.size();
	}

	public boolean contains(T item) {
		return pending.contains(item);
	}

	public void clear() {
		pending.clear();
		dirty = false;
	}

	private void sortIfNeeded(Comparator<T> comparator) {
		if (!dirty) {
			return;
		}
		ArrayList<T> sorted = new ArrayList<>(pending);
		sorted.sort(comparator);
		pending.clear();
		pending.addAll(sorted);
		dirty = false;
	}
}
