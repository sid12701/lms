package com.bhawana.lms.security;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.RemoteKeySourceException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSetCacheRefreshEvaluator;
import com.nimbusds.jose.jwk.source.JWKSetSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.cache.Cache;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestOperations;

/**
 * Fetch JWKS from a single configured URI with Spring cache integration. Token-supplied
 * {@code jku}/{@code x5u} never choose destinations.
 */
final class TrustedRemoteJwkSetSource<C extends SecurityContext> implements JWKSetSource<C> {

    private static final MediaType APPLICATION_JWK_SET_JSON =
            new MediaType("application", "jwk-set+json");

    private final RestOperations restOperations;
    private final Cache cache;
    private final String jwkSetUri;
    private final Duration unknownKidMinInterval;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicReference<Instant> lastUnresolvedMissingKeyRefreshAt = new AtomicReference<>();
    private volatile JWKSet jwkSet;
    // Guarded by lock: failed requests also consume the refresh budget.
    private Instant failedFetchRetryAt;

    TrustedRemoteJwkSetSource(
            RestOperations restOperations,
            Cache cache,
            String jwkSetUri,
            Duration unknownKidMinInterval
    ) {
        this.restOperations = restOperations;
        this.cache = cache;
        this.jwkSetUri = jwkSetUri;
        this.unknownKidMinInterval = unknownKidMinInterval == null
                || unknownKidMinInterval.isNegative()
                || unknownKidMinInterval.isZero()
                ? Duration.ofSeconds(30)
                : unknownKidMinInterval;
    }

    @Override
    public JWKSet getJWKSet(JWKSetCacheRefreshEvaluator refreshEvaluator, long currentTime, C context)
            throws KeySourceException {
        boolean missingKeyRefresh = refreshEvaluator != null
                && jwkSet != null
                && refreshEvaluator.requiresRefresh(jwkSet);
        if (cache instanceof ExpiringConcurrentMapCache expiringCache && expiringCache.isExpired(jwkSetUri)) {
            lastUnresolvedMissingKeyRefreshAt.set(null);
        }
        if (missingKeyRefresh && shouldThrottleUnresolvedMissingKeyRefresh()) {
            throw new KeySourceException("Unknown signing key and JWKS refresh is throttled.");
        }
        lock.lock();
        try {
            if (refreshEvaluator != null && refreshEvaluator.requiresRefresh(jwkSet)) {
                cache.invalidate();
            }
            cache.get(jwkSetUri, () -> {
                if (failedFetchRetryAt != null && Instant.now().isBefore(failedFetchRetryAt)) {
                    throw new KeySourceException("JWKS retrieval failed recently; retry is throttled.");
                }
                JWKSet fetched;
                try {
                    fetched = fetchJwkSet();
                    failedFetchRetryAt = null;
                } catch (KeySourceException failure) {
                    failedFetchRetryAt = Instant.now().plus(unknownKidMinInterval);
                    throw failure;
                }
                jwkSet = fetched;
                if (missingKeyRefresh) {
                    lastUnresolvedMissingKeyRefreshAt.set(Instant.now());
                }
                return fetched;
            });
            return jwkSet;
        } catch (Cache.ValueRetrievalException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof KeySourceException keySourceException) {
                throw keySourceException;
            }
            throw new RemoteKeySourceException(exception.getMessage(), cause);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
    }

    private JWKSet fetchJwkSet() throws KeySourceException {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON, APPLICATION_JWK_SET_JSON));
            ResponseEntity<String> response = restOperations.exchange(
                    jwkSetUri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RemoteKeySourceException(
                        "Could not retrieve JWKS from " + jwkSetUri,
                        new IllegalStateException("HTTP " + response.getStatusCode()));
            }
            String body = response.getBody();
            return JWKSet.parse(body);
        } catch (RestClientException | ParseException exception) {
            throw new RemoteKeySourceException("Could not retrieve JWKS from " + jwkSetUri, exception);
        }
    }

    private boolean shouldThrottleUnresolvedMissingKeyRefresh() {
        Instant last = lastUnresolvedMissingKeyRefreshAt.get();
        if (last == null) {
            return false;
        }
        return !Instant.now().isAfter(last.plus(unknownKidMinInterval));
    }

}
