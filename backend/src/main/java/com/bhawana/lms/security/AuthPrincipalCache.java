package com.bhawana.lms.security;

import com.bhawana.lms.domain.ApiClientStatus;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.UserStatus;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class AuthPrincipalCache {

    static final long TTL_MILLIS = 30_000L;

    private final ConcurrentHashMap<String, CacheEntry<AppUserSnapshot>> appUsers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry<ApiClientSnapshot>> apiClients = new ConcurrentHashMap<>();

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
        appUsers.put(key, new CacheEntry<>(loaded.orElse(null), now + TTL_MILLIS));
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
        apiClients.put(key, new CacheEntry<>(loaded.orElse(null), now + TTL_MILLIS));
        return loaded;
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
