package com.bhawana.lms.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bhawana.lms.domain.ApiClientStatus;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.UserStatus;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Documents and pins the bounded stale behavior of {@link AuthPrincipalCache} (L04).
 *
 * <p>Principal snapshots may lag the database by at most the configured TTL per
 * instance; revocation paths evict explicitly for immediacy, and fail-closed
 * validation plus the bootstrap-sync actor re-check bound the residual window. The
 * cross-instance SLA is proven here with two independent cache instances standing
 * in for two processes sharing one database.
 */
class AuthPrincipalCacheBoundedStaleTest {

    @Test
    void defaultRevocationBoundIsThirtySeconds() {
        assertEquals(Duration.ofSeconds(30), new SecurityProperties().getPrincipalCacheTtl());
        assertEquals(30_000L, new AuthPrincipalCache(new SecurityProperties()).ttlMillis());
    }

    @Test
    void cachedSnapshotIsReusedWithinBoundAndReloadedAfterEvict() {
        AuthPrincipalCache cache = new AuthPrincipalCache(30_000L);
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

    /**
     * Two cache instances stand in for two API instances sharing one database.
     * Revoking on instance A evicts only A's entry; B keeps serving the stale
     * snapshot — but only until its TTL elapses, then it reloads. That elapsed
     * TTL is the documented maximum revocation delay across instances.
     */
    @Test
    void crossInstanceRevocationConvergesWithinTtl() throws InterruptedException {
        long ttlMillis = 40;
        AuthPrincipalCache instanceA = new AuthPrincipalCache(ttlMillis);
        AuthPrincipalCache instanceB = new AuthPrincipalCache(ttlMillis);
        AtomicInteger sharedStoreVersion = new AtomicInteger(0);
        AtomicInteger loadsOnB = new AtomicInteger();

        // A principal lookup lands on both instances before the revocation.
        java.util.function.Supplier<Optional<AuthPrincipalCache.AppUserSnapshot>> loader = () -> Optional.of(
                new AuthPrincipalCache.AppUserSnapshot(
                        sharedStoreVersion.get(), 0L, false, UserStatus.ACTIVE, null));
        assertEquals(0L, instanceA.getAppUser("shared.user", loader).orElseThrow().tokenVersion());
        assertEquals(0L, instanceB.getAppUser("shared.user", () -> {
            loadsOnB.incrementAndGet();
            return loader.get();
        }).orElseThrow().tokenVersion());

        // The revocation commits on the instance that performed it (A) — bump the
        // "database" version and evict A's entry, exactly like the revocation paths do.
        sharedStoreVersion.incrementAndGet();
        instanceA.evictAppUser("shared.user");
        assertEquals(1L, instanceA.getAppUser("shared.user", loader).orElseThrow().tokenVersion());

        // Instance B still serves the revoked snapshot inside the TTL window — this is
        // the accepted stale window, bounded by TTL and nothing more.
        assertEquals(0L, instanceB.getAppUser("shared.user", () -> {
            loadsOnB.incrementAndGet();
            return loader.get();
        }).orElseThrow().tokenVersion());
        assertEquals(1, loadsOnB.get());

        Thread.sleep(ttlMillis + 30);
        assertEquals(
                1L,
                instanceB.getAppUser("shared.user", () -> {
                    loadsOnB.incrementAndGet();
                    return loader.get();
                }).orElseThrow().tokenVersion(),
                "after the TTL every instance must observe the revocation");
    }

    /**
     * No cross-tenant (or cross-principal-kind) key collision: a human username and an
     * API client id that happen to be textually identical live in separate maps, and
     * distinct identities never share an entry.
     */
    @Test
    void identicallyNamedUserAndClientNeverCollide() {
        AuthPrincipalCache cache = new AuthPrincipalCache(30_000L);
        AuthPrincipalCache.AppUserSnapshot userSnapshot =
                new AuthPrincipalCache.AppUserSnapshot(7L, 0L, false, UserStatus.ACTIVE, null);
        AuthPrincipalCache.ApiClientSnapshot clientSnapshot =
                new AuthPrincipalCache.ApiClientSnapshot(9L, 3L, LspStatus.ACTIVE, ApiClientStatus.ACTIVE);

        cache.getAppUser("partner-42", () -> Optional.of(userSnapshot));
        cache.getApiClient("partner-42", () -> Optional.of(clientSnapshot));

        assertEquals(7L, cache.getAppUser("partner-42", Optional::empty).orElseThrow().tokenVersion());
        assertEquals(
                9L,
                cache.getApiClient("partner-42", Optional::empty).orElseThrow().lspTokenVersion());
        assertEquals(1, cache.appUserEntryCount());
        assertEquals(1, cache.apiClientEntryCount());
    }

    @Test
    void expiredEntriesAreSweptStructurally() throws InterruptedException {
        AuthPrincipalCache cache = new AuthPrincipalCache(20);
        cache.getAppUser("gone.user", () -> Optional.of(
                new AuthPrincipalCache.AppUserSnapshot(0L, 0L, false, UserStatus.ACTIVE, null)));
        Thread.sleep(40);
        cache.sweepExpiredForTest(System.currentTimeMillis());
        assertEquals(0, cache.appUserEntryCount());
    }
}
