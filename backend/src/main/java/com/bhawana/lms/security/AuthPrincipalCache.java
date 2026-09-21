package com.bhawana.lms.security;

import com.bhawana.lms.domain.ApiClientStatus;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.UserStatus;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Per-process principal snapshot cache (L04). The TTL is the documented bound on
 * cross-instance revocation delay for principal state: a mutation evicts on the
 * instance that performs it, and every other instance converges within
 * {@code app.security.principal-cache-ttl} (default 30 s).
 *
 * <p>Keys are globally unique principal identities (canonical username / client id),
 * so there is no cross-tenant key collision surface. Entries expire structurally:
 * reads check {@code expiresAtMillis} and the maps sweep expired entries whenever
 * they grow past {@link #SWEEP_THRESHOLD}, so dead principals cannot accumulate
 * unboundedly in a long-lived process.
 */
@Component
public class AuthPrincipalCache {

    /**
     * Distinct-key count at which a lookup opportunistically sweeps expired entries.
     * Live deployments hold at most one entry per principal; the threshold only
     * guards against churn through many distinct subjects.
     */
    static final int SWEEP_THRESHOLD = 4096;

    private final long ttlMillis;
    private final ConcurrentHashMap<String, CacheEntry<AppUserSnapshot>> appUsers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry<ApiClientSnapshot>> apiClients = new ConcurrentHashMap<>();

    @Autowired
    public AuthPrincipalCache(SecurityProperties securityProperties) {
        this(securityProperties.getPrincipalCacheTtl().toMillis());
    }

    /** Test seam: construct with an explicit TTL without wiring full properties. */
    AuthPrincipalCache(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    /** Configured snapshot TTL in milliseconds — the per-instance staleness bound. */
    public long ttlMillis() {
        return ttlMillis;
    }

    public Optional<AppUserSnapshot> getAppUser(String username, Supplier<Optional<AppUserSnapshot>> loader) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        String key = username.trim();
        long now = System.currentTimeMillis();
        CacheEntry<AppUserSnapshot> cached = appUsers.get(key);
        if (cached != null && cached.expiresAtMillis() > now) {
            return Optional.ofNullable(cached.value());
        }
        Optional<AppUserSnapshot> loaded = loader.get();
        appUsers.put(key, new CacheEntry<>(loaded.orElse(null), now + ttlMillis));
        sweepExpiredIfOversized(appUsers, now);
        return loaded;
    }

    public Optional<ApiClientSnapshot> getApiClient(String clientId, Supplier<Optional<ApiClientSnapshot>> loader) {
        if (clientId == null || clientId.isBlank()) {
            return Optional.empty();
        }
        String key = clientId.trim();
        long now = System.currentTimeMillis();
        CacheEntry<ApiClientSnapshot> cached = apiClients.get(key);
        if (cached != null && cached.expiresAtMillis() > now) {
            return Optional.ofNullable(cached.value());
        }
        Optional<ApiClientSnapshot> loaded = loader.get();
        apiClients.put(key, new CacheEntry<>(loaded.orElse(null), now + ttlMillis));
        sweepExpiredIfOversized(apiClients, now);
        return loaded;
    }

    private static <T> void sweepExpiredIfOversized(ConcurrentHashMap<String, CacheEntry<T>> map, long now) {
        if (map.size() > SWEEP_THRESHOLD) {
            map.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        }
    }

    /** Test seam: removes every entry whose TTL has passed at {@code nowMillis}. */
    void sweepExpiredForTest(long nowMillis) {
        appUsers.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= nowMillis);
        apiClients.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= nowMillis);
    }

    int appUserEntryCount() {
        return appUsers.size();
    }

    int apiClientEntryCount() {
        return apiClients.size();
    }

    public void evictAppUser(String username) {
        if (username != null && !username.isBlank()) {
            appUsers.remove(username.trim());
        }
    }

    public void evictApiClient(String clientId) {
        if (clientId != null && !clientId.isBlank()) {
            apiClients.remove(clientId.trim());
        }
    }

    public void evictAllApiClients() {
        apiClients.clear();
    }

    public record AppUserSnapshot(
            long tokenVersion,
            long passwordChangedAtMillis,
            boolean passwordChangeRequired,
            UserStatus status,
            LspStatus lspStatus
    ) {
    }

    public record ApiClientSnapshot(
            long lspTokenVersion,
            long apiClientTokenVersion,
            LspStatus lspStatus,
            ApiClientStatus apiClientStatus
    ) {
    }

    private record CacheEntry<T>(T value, long expiresAtMillis) {
    }
}
