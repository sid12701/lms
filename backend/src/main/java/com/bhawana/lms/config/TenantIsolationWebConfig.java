package com.bhawana.lms.config;

import com.bhawana.lms.web.InternalAdminTenantContextInterceptor;
import com.bhawana.lms.web.LspTenantContextInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class TenantIsolationWebConfig implements WebMvcConfigurer {

    private final LspTenantContextInterceptor lspTenantContextInterceptor;
    private final InternalAdminTenantContextInterceptor internalAdminTenantContextInterceptor;

    public TenantIsolationWebConfig(
            LspTenantContextInterceptor lspTenantContextInterceptor,
            InternalAdminTenantContextInterceptor internalAdminTenantContextInterceptor
    ) {
        this.lspTenantContextInterceptor = lspTenantContextInterceptor;
        this.internalAdminTenantContextInterceptor = internalAdminTenantContextInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalAdminTenantContextInterceptor)
                .addPathPatterns("/api/v1/internal/**", "/api/v1/auth/**");
        registry.addInterceptor(lspTenantContextInterceptor)
                .addPathPatterns("/api/v1/lsp/**");
    }
}
