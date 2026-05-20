package com.bhawana.lms.security;

import com.bhawana.lms.common.api.ApiError;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.LspIpAllowlistEntry;
import com.bhawana.lms.repo.LspIpAllowlistRepository;
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
public class LspIpAllowlistFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LspIpAllowlistFilter.class);
    private static final String LSP_PATH_PREFIX = "/api/v1/lsp/";
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final LspIpAllowlistRepository lspIpAllowlistRepository;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<UUID, CachedAllowlist> cache = new ConcurrentHashMap<>();

    public LspIpAllowlistFilter(
            LspIpAllowlistRepository lspIpAllowlistRepository,
            ObjectMapper objectMapper
    ) {
        this.lspIpAllowlistRepository = lspIpAllowlistRepository;
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
        if (lspId == null) {
            filterChain.doFilter(request, response);
            return;
        }

        List<IpAddressMatcher> matchers = loadMatchers(lspId);
        if (matchers.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        String remoteAddress = request.getRemoteAddr();
        if (remoteAddress == null || !matchesAny(matchers, remoteAddress)) {
            log.warn("Rejecting LSP request from {} — not in allowlist for lsp {}", remoteAddress, lspId);
            writeApiError(
                    response,
                    HttpServletResponse.SC_FORBIDDEN,
                    "IP_NOT_ALLOWED",
                    "Source IP is not in the LSP allowlist.",
                    request.getRequestURI()
            );
            return;
        }

        filterChain.doFilter(request, response);
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

    private List<IpAddressMatcher> loadMatchers(UUID lspId) {
        CachedAllowlist cached = cache.get(lspId);
        Instant now = Instant.now();
        if (cached != null && cached.expiresAt.isAfter(now)) {
            return cached.matchers;
        }

        List<LspIpAllowlistEntry> entries = lspIpAllowlistRepository.findByLsp_Id(lspId);
        List<IpAddressMatcher> matchers = entries.stream()
                .map(entry -> safeMatcher(entry.getCidr()))
                .filter(java.util.Objects::nonNull)
                .toList();
        cache.put(lspId, new CachedAllowlist(matchers, now.plus(CACHE_TTL)));
        return matchers;
    }

    private static IpAddressMatcher safeMatcher(String cidr) {
        try {
            return new IpAddressMatcher(cidr);
        } catch (IllegalArgumentException exception) {
            log.warn("Skipping malformed CIDR in lsp_ip_allowlist: {}", cidr);
            return null;
        }
    }

    private static boolean matchesAny(List<IpAddressMatcher> matchers, String remoteAddress) {
        for (IpAddressMatcher matcher : matchers) {
            if (matcher.matches(remoteAddress)) {
                return true;
            }
        }
        return false;
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

    public void invalidateCache(UUID lspId) {
        if (lspId == null) {
            cache.clear();
        } else {
            cache.remove(lspId);
        }
    }

    private record CachedAllowlist(List<IpAddressMatcher> matchers, Instant expiresAt) {
    }
}
