package com.bhawana.lms.security;

import com.bhawana.lms.common.api.ApiError;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.LspIpAllowlistSurface;
import com.bhawana.lms.service.LspSurfaceIpAllowlistService;
import com.bhawana.lms.service.LspSurfaceIpAllowlistService.AccessDecision;
import com.bhawana.lms.service.LspSurfaceIpAllowlistService.AllowlistSnapshot;
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
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final LspSurfaceIpAllowlistService allowlistService;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<CacheKey, CachedSnapshot> cache = new ConcurrentHashMap<>();

    public LspSurfaceIpAllowlistFilter(
            LspSurfaceIpAllowlistService allowlistService,
            ObjectMapper objectMapper
    ) {
        this.allowlistService = allowlistService;
        this.objectMapper = objectMapper;
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

        AccessDecision decision = evaluate(lspId, surface, request.getRemoteAddr());
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

        log.warn("Rejecting LSP {} request from {} for lsp {} — {}", surface, request.getRemoteAddr(), lspId, code);
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
        AllowlistSnapshot snapshot = allowlistService.loadSnapshot(lspId, surface);
        cache.put(key, new CachedSnapshot(snapshot, now.plus(CACHE_TTL)));
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
        String rawLspId = jwt.getClaimAsString("lspId");
        if (rawLspId == null || rawLspId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(rawLspId);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static LspIpAllowlistSurface resolveSurface(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || roles.isEmpty()) {
            return null;
        }
        if (roles.contains("LSP_API_CLIENT")) {
            return LspIpAllowlistSurface.API;
        }
        if (roles.stream().anyMatch(LspSurfaceIpAllowlistService::isLspUiRole)) {
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
