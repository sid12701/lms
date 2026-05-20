package com.bhawana.lms.web;

import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LspTenantContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID lspId = authentication == null
                ? null
                : LspAuthenticationSupport.tryAuthenticatedLspId(authentication);
        if (lspId == null) {
            throw new AccessDeniedException("Authenticated LSP tenant context is required for this endpoint.");
        }
        TenantDataAccessContextHolder.useTenant(lspId);
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
