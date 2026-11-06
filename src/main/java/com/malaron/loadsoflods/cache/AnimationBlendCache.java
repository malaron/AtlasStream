package com.malaron.loadsoflods.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Keeps blended frames alive until a batch of texture uploads has been submitted.
 */
public final class AnimationBlendCache<K, V> implements AutoCloseable {
	private final ByteLruCache<K, V> cache;
	private final Consumer<V> disposer;
	private boolean batchActive;

	public AnimationBlendCache(long maximumBytes, Consumer<V> disposer) {
		this.disposer = Objects.requireNonNull(disposer, "disposer");
		this.cache = new ByteLruCache<>(maximumBytes, disposer);
	}

	public Batch beginBatch() {
		if (batchActive) {
			throw new IllegalStateException("An animation blend batch is already active");
		}
		batchActive = true;
		return new Batch();
	}

	public void clear() {
		if (batchActive) {
			throw new IllegalStateException("Cannot clear an active animation blend batch");
		}
		cache.clear();
	}

	@Override
	public void close() {
		clear();
	}

	public final class Batch implements AutoCloseable {
		private final Map<K, Pending<V>> pending = new LinkedHashMap<>();
		private boolean committed;
		private boolean closed;

		public V getOrCreate(K key, long weightBytes, Supplier<V> create) {
			if (closed || committed) {
				throw new IllegalStateException("Animation blend batch is no longer writable");
			}
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(create, "create");
			if (weightBytes <= 0) {
				throw new IllegalArgumentException("Animation blend weight must be positive");
			}
			Pending<V> staged = pending.get(key);
			if (staged != null) {
				return staged.value();
			}
			V cached = cache.get(key);
			if (cached != null) {
				return cached;
			}
			V value = Objects.requireNonNull(create.get(), "created value");
			pending.put(key, new Pending<>(value, weightBytes));
			return value;
		}

		public void commit() {
			if (closed || committed) {
				throw new IllegalStateException("Animation blend batch is no longer writable");
			}
			committed = true;
			var iterator = pending.entrySet().iterator();
			while (iterator.hasNext()) {
				Map.Entry<K, Pending<V>> entry = iterator.next();
				Pending<V> value = entry.getValue();
				if (value.weightBytes() <= cache.maximumBytes()) {
					cache.put(entry.getKey(), value.value(), value.weightBytes());
					iterator.remove();
				}
			}
		}

		@Override
		public void close() {
			if (closed) {
				return;
			}
			closed = true;
			RuntimeException firstFailure = null;
			try {
				for (Pending<V> value : pending.values()) {
					try {
						disposer.accept(value.value());
					} catch (RuntimeException exception) {
						if (firstFailure == null) {
							firstFailure = exception;
						} else {
							firstFailure.addSuppressed(exception);
						}
					}
				}
			} finally {
				pending.clear();
				batchActive = false;
			}
			if (firstFailure != null) {
				throw firstFailure;
			}
		}
	}

	private record Pending<V>(V value, long weightBytes) {
	}
}
