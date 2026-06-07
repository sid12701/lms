package com.bhawana.lms.web;

import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * In the {@code test} profile, HTTP interceptors clear tenant scope after each request.
 * Integration tests often perform MockMvc calls and then touch repositories on the same thread;
 * this filter restores admin scope after every request so those assertions keep working.
 */
@Component
@Profile("test")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TestTenantContextRestoreFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantDataAccessContextHolder.useAdmin();
        }
    }
}
