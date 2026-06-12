package com.bhawana.lms.web;

import com.bhawana.lms.tenant.TenantAccessContext;
import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Derives the tenant data-access scope from the authenticated principal, immediately after
 * bearer-token authentication: principals carrying an {@code lspId} claim get tenant scope,
 * all other authenticated principals get admin scope.
 * <p>
 * Anonymous requests are left unscoped on purpose — unscoped data access fails closed at
 * {@code TenantRoutingDataSource}. Anonymous auth endpoints (login/refresh) get admin scope
 * from {@code InternalAdminTenantContextInterceptor}; lookups that must run before
 * authentication completes (JWT session validators) use {@code TenantScopedExecution} directly.
 */
@Component
public class AuthenticationTenantScopeFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        TenantAccessContext previous = TenantDataAccessContextHolder.snapshot();
        applyScopeFromAuthentication();
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantDataAccessContextHolder.restore(previous);
        }
    }

    private static void applyScopeFromAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return;
        }
        UUID lspId = LspAuthenticationSupport.tryAuthenticatedLspId(authentication);
        if (lspId != null) {
            TenantDataAccessContextHolder.useTenant(lspId);
        } else {
            TenantDataAccessContextHolder.useAdmin();
        }
    }
}
