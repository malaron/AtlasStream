package com.malaron.loadsoflods.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class ByteLruCache<K, V> implements AutoCloseable {
	private final long maximumBytes;
	private final Consumer<V> disposer;
	private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75F, true);
	private long usedBytes;

	public ByteLruCache(long maximumBytes, Consumer<V> disposer) {
		if (maximumBytes <= 0) {
			throw new IllegalArgumentException("Cache budget must be positive");
		}
		this.maximumBytes = maximumBytes;
		this.disposer = Objects.requireNonNull(disposer, "disposer");
	}

	public synchronized V get(K key) {
		Entry<V> entry = entries.get(Objects.requireNonNull(key, "key"));
		return entry == null ? null : entry.value();
	}

	public synchronized boolean containsKey(K key) {
		return entries.containsKey(Objects.requireNonNull(key, "key"));
	}

	public synchronized void put(K key, V value, long weightBytes) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(value, "value");
		if (weightBytes <= 0) {
			throw new IllegalArgumentException("Cache entry weight must be positive");
		}
		if (weightBytes > maximumBytes) {
			throw new IllegalArgumentException("Cache entry exceeds the configured byte budget");
		}

		Entry<V> previous = entries.remove(key);
		if (previous != null) {
			usedBytes -= previous.weightBytes();
			disposer.accept(previous.value());
		}
		while (usedBytes > maximumBytes - weightBytes) {
			Map.Entry<K, Entry<V>> eldest = entries.entrySet().iterator().next();
			Entry<V> evicted = eldest.getValue();
			entries.remove(eldest.getKey());
			usedBytes -= evicted.weightBytes();
			disposer.accept(evicted.value());
		}
		entries.put(key, new Entry<>(value, weightBytes));
		usedBytes += weightBytes;
	}

	public synchronized boolean invalidate(K key) {
		Entry<V> removed = entries.remove(Objects.requireNonNull(key, "key"));
		if (removed == null) {
			return false;
		}
		usedBytes -= removed.weightBytes();
		disposer.accept(removed.value());
		return true;
	}

	public synchronized boolean evictLeastRecentlyUsed() {
		if (entries.isEmpty()) {
			return false;
		}
		Map.Entry<K, Entry<V>> eldest = entries.entrySet().iterator().next();
		Entry<V> evicted = eldest.getValue();
		entries.remove(eldest.getKey());
		usedBytes -= evicted.weightBytes();
		disposer.accept(evicted.value());
		return true;
	}

	public synchronized void clear() {
		RuntimeException firstFailure = null;
		for (Entry<V> entry : entries.values()) {
			try {
				disposer.accept(entry.value());
			} catch (RuntimeException exception) {
				if (firstFailure == null) {
					firstFailure = exception;
				} else {
					firstFailure.addSuppressed(exception);
				}
			}
		}
		entries.clear();
		usedBytes = 0;
		if (firstFailure != null) {
			throw firstFailure;
		}
	}

	public synchronized long usedBytes() {
		return usedBytes;
	}

	public long maximumBytes() {
		return maximumBytes;
	}

	public synchronized int size() {
		return entries.size();
	}

	@Override
	public void close() {
		clear();
	}

	private record Entry<V>(V value, long weightBytes) {
	}
}
