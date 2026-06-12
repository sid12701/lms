package com.bhawana.lms.web;

import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Sets admin data-access scope for the anonymous auth entry points (login, token refresh,
 * logout, password change), which resolve principals before any authentication exists.
 * Authenticated traffic gets its scope from {@code AuthenticationTenantScopeFilter} in the
 * security filter chain; authorization remains on {@code @PreAuthorize} handlers.
 */
@Component
public class InternalAdminTenantContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        TenantDataAccessContextHolder.useAdmin();
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex
    ) {
        TenantDataAccessContextHolder.clear();
    }
}
