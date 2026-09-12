package com.bhawana.lms.security;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;

/**
 * Spring {@link Cache} with a fixed TTL for trusted JWKS documents. Used by
 * {@link TrustedJwksJwtDecoderFactory} so {@code jwks-cache-ttl} is enforced.
 */
final class ExpiringConcurrentMapCache implements Cache {

    private final String name;
    private final Duration ttl;
    private final ConcurrentMap<Object, TimedEntry> store = new ConcurrentHashMap<>();

    ExpiringConcurrentMapCache(String name, Duration ttl) {
        this.name = name;
        this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(10) : ttl;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return store;
    }

    @Override
    public ValueWrapper get(Object key) {
        TimedEntry entry = store.get(key);
        if (entry == null || entry.isExpired()) {
            if (entry != null) {
                store.remove(key, entry);
            }
            return null;
        }
        return new SimpleValueWrapper(entry.value());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        if (wrapper == null) {
            return null;
        }
        Object value = wrapper.get();
        if (value != null && type != null && !type.isInstance(value)) {
            throw new IllegalStateException(
                    "Cached value is not of required type [" + type.getName() + "]: " + value);
        }
        return (T) value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        TimedEntry existing = store.get(key);
        if (existing != null && !existing.isExpired()) {
            return (T) existing.value();
        }
        try {
            T loaded = valueLoader.call();
            put(key, loaded);
            return loaded;
        } catch (Exception exception) {
            throw new ValueRetrievalException(key, valueLoader, exception);
        }
    }

    @Override
    public void put(Object key, Object value) {
        store.put(key, new TimedEntry(value, Instant.now().plus(ttl)));
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        TimedEntry created = new TimedEntry(value, Instant.now().plus(ttl));
        TimedEntry previous = store.putIfAbsent(key, created);
        if (previous == null || previous.isExpired()) {
            if (previous != null) {
                store.replace(key, previous, created);
            }
            return null;
        }
        return new SimpleValueWrapper(previous.value());
    }

    @Override
    public void evict(Object key) {
        store.remove(key);
    }

    @Override
    public boolean evictIfPresent(Object key) {
        return store.remove(key) != null;
    }

    @Override
    public void clear() {
        store.clear();
    }

    @Override
    public boolean invalidate() {
        clear();
        return true;
    }

    boolean isExpired(Object key) {
        TimedEntry entry = store.get(key);
        return entry == null || entry.isExpired();
    }

    private record TimedEntry(Object value, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
