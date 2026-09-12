package com.bhawana.lms.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bhawana.lms.domain.UserStatus;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Documents and pins the bounded stale behavior of {@link AuthPrincipalCache}.
 *
 * <p>Principal snapshots may lag the database by at most {@code TTL_MILLIS} per instance;
 * revocation paths evict explicitly for immediacy, and fail-closed validation plus the
 * bootstrap-sync actor re-check bound the residual window.
 */
class AuthPrincipalCacheBoundedStaleTest {

    @Test
    void revocationBoundIsThirtySeconds() {
        assertEquals(30_000L, AuthPrincipalCache.TTL_MILLIS);
    }

    @Test
    void cachedSnapshotIsReusedWithinBoundAndReloadedAfterEvict() {
        AuthPrincipalCache cache = new AuthPrincipalCache();
        AtomicInteger loads = new AtomicInteger();
        AuthPrincipalCache.AppUserSnapshot first =
                new AuthPrincipalCache.AppUserSnapshot(0L, 0L, false, UserStatus.ACTIVE, null);
        AuthPrincipalCache.AppUserSnapshot second =
                new AuthPrincipalCache.AppUserSnapshot(1L, 0L, false, UserStatus.ACTIVE, null);

        Optional<AuthPrincipalCache.AppUserSnapshot> loaded = cache.getAppUser("cached.user", () -> {
            loads.incrementAndGet();
            return Optional.of(first);
        });
        assertTrue(loaded.isPresent());
        assertEquals(0L, loaded.get().tokenVersion());

        // Within the bound the loader is not consulted again, even though the database moved on.
        Optional<AuthPrincipalCache.AppUserSnapshot> stale = cache.getAppUser("cached.user", () -> {
            loads.incrementAndGet();
            return Optional.of(second);
        });
        assertTrue(stale.isPresent());
        assertEquals(0L, stale.get().tokenVersion());
        assertEquals(1, loads.get());

        // Explicit eviction (the revocation-path behavior) forces a fresh load.
        cache.evictAppUser("cached.user");
        Optional<AuthPrincipalCache.AppUserSnapshot> fresh = cache.getAppUser("cached.user", () -> {
            loads.incrementAndGet();
            return Optional.of(second);
        });
        assertTrue(fresh.isPresent());
        assertEquals(1L, fresh.get().tokenVersion());
        assertEquals(2, loads.get());
    }
}
