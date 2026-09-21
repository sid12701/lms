package com.bhawana.lms.security;

import com.bhawana.lms.common.api.ApiError;
import com.bhawana.lms.common.web.ClientIpAddresses;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.LspIpAllowlistSurface;
import com.bhawana.lms.service.LspSurfaceIpAllowlistService;
import com.bhawana.lms.service.LspSurfaceIpAllowlistService.AccessDecision;
import com.bhawana.lms.service.LspSurfaceIpAllowlistService.AllowlistSnapshot;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class LspSurfaceIpAllowlistFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LspSurfaceIpAllowlistFilter.class);
    private static final String LSP_PATH_PREFIX = "/api/v1/lsp/";
    /**
     * Distinct-key count at which a lookup opportunistically sweeps expired snapshots.
     * Keys are (lspId, surface) pairs — bounded by the LSP catalogue — so the threshold
     * only guards a churn/leak pathology, never normal operation (L04).
     */
    private static final int SWEEP_THRESHOLD = 1024;

    private final LspSurfaceIpAllowlistService allowlistService;
    private final ObjectMapper objectMapper;
    /**
     * Per-process snapshot TTL (L04): an allowlist change on one instance takes effect
     * here within this bound — the documented maximum cross-instance revocation delay
     * for allowlist state. {@code app.security.lsp-ip-allowlist-cache-ttl}, default 60 s.
     */
    private final Duration cacheTtl;
    private final ConcurrentHashMap<CacheKey, CachedSnapshot> cache = new ConcurrentHashMap<>();

    public LspSurfaceIpAllowlistFilter(
            LspSurfaceIpAllowlistService allowlistService,
            ObjectMapper objectMapper,
            SecurityProperties securityProperties
    ) {
        this.allowlistService = allowlistService;
        this.objectMapper = objectMapper;
        this.cacheTtl = securityProperties.getLspIpAllowlistCacheTtl();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(LSP_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID lspId = extractLspId(authentication);
        LspIpAllowlistSurface surface = resolveSurface(authentication);
        if (lspId == null || surface == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = ClientIpAddresses.resolve(request);
        AccessDecision decision = evaluate(lspId, surface, clientIp);
        if (decision == AccessDecision.ALLOW) {
            filterChain.doFilter(request, response);
            return;
        }

        String code = decision == AccessDecision.DENY_ENFORCEMENT_EMPTY
                ? "IP_ENFORCEMENT_EMPTY_LIST"
                : "IP_NOT_ALLOWED";
        String message = decision == AccessDecision.DENY_ENFORCEMENT_EMPTY
                ? "IP allowlist enforcement is enabled but no CIDR entries are configured for this surface."
                : "Source IP is not in the LSP allowlist for this surface.";

        log.warn("Rejecting LSP {} request from {} for lsp {} — {}", surface, clientIp, lspId, code);
        writeApiError(response, HttpServletResponse.SC_FORBIDDEN, code, message, request.getRequestURI());
    }

    private AccessDecision evaluate(UUID lspId, LspIpAllowlistSurface surface, String remoteAddress) {
        AllowlistSnapshot snapshot = loadSnapshot(lspId, surface);
        if (snapshot.cidrs().isEmpty()) {
            return snapshot.enforcementEnabled() ? AccessDecision.DENY_ENFORCEMENT_EMPTY : AccessDecision.ALLOW;
        }
        if (remoteAddress == null || !matchesAny(snapshot.cidrs(), remoteAddress)) {
            return AccessDecision.DENY_NOT_ALLOWED;
        }
        return AccessDecision.ALLOW;
    }

    private AllowlistSnapshot loadSnapshot(UUID lspId, LspIpAllowlistSurface surface) {
        CacheKey key = new CacheKey(lspId, surface);
        Instant now = Instant.now();
        CachedSnapshot cached = cache.get(key);
        if (cached != null && cached.expiresAt.isAfter(now)) {
            return cached.snapshot;
        }
        // Perimeter check against admin-owned config; runs before tenant scope applies (ADR 0005).
        AllowlistSnapshot snapshot = TenantScopedExecution.callAsAdmin(
                () -> allowlistService.loadSnapshot(lspId, surface)
        );
        cache.put(key, new CachedSnapshot(snapshot, now.plus(cacheTtl)));
        if (cache.size() > SWEEP_THRESHOLD) {
            cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt.isAfter(now));
        }
        return snapshot;
    }

    public void invalidateCache(UUID lspId, LspIpAllowlistSurface surface) {
        if (lspId == null && surface == null) {
            cache.clear();
            return;
        }
        if (lspId == null) {
            cache.keySet().removeIf(key -> key.surface == surface);
            return;
        }
        if (surface == null) {
            cache.keySet().removeIf(key -> key.lspId.equals(lspId));
            return;
        }
        cache.remove(new CacheKey(lspId, surface));
    }

    private static boolean matchesAny(List<String> cidrs, String remoteAddress) {
        for (String cidr : cidrs) {
            try {
                if (new IpAddressMatcher(cidr).matches(remoteAddress)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // skip malformed CIDR
            }
        }
        return false;
    }

    private UUID extractLspId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        return MachinePrincipalLspResolver.resolveLspId(jwt).orElse(null);
    }

    private static LspIpAllowlistSurface resolveSurface(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt)) {
            return null;
        }
        // Authority mapping establishes local identity. External app-role strings (or a
        // stripped human machine-role claim) must not choose or bypass the IP surface.
        List<String> roles = authentication.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .toList();
        if (roles.contains("ROLE_LSP_API_CLIENT")) {
            return LspIpAllowlistSurface.API;
        }
        if (roles.contains("ROLE_LSP_UI_READ") || roles.contains("ROLE_LSP_UI_WRITE")) {
            return LspIpAllowlistSurface.UI;
        }
        return null;
    }

    private void writeApiError(
            HttpServletResponse response,
            int status,
            String code,
            String message,
            String path
    ) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getWriter(), ApiError.of(
                status,
                code,
                message,
                path,
                CorrelationIdHolder.get(),
                Map.of()
        ));
    }

    private record CacheKey(UUID lspId, LspIpAllowlistSurface surface) {
    }

    private record CachedSnapshot(AllowlistSnapshot snapshot, Instant expiresAt) {
    }
}
