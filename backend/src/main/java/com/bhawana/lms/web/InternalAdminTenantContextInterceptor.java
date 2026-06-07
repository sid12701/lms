package com.bhawana.lms.web;

import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Sets admin data-access scope for internal staff and auth HTTP entry points.
 * Authorization remains on {@code @PreAuthorize} handlers; this only tags the datasource scope.
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
