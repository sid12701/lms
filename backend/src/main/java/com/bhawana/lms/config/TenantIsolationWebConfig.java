package com.bhawana.lms.config;

import com.bhawana.lms.web.LspTenantContextInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class TenantIsolationWebConfig implements WebMvcConfigurer {

    private final LspTenantContextInterceptor lspTenantContextInterceptor;

    public TenantIsolationWebConfig(LspTenantContextInterceptor lspTenantContextInterceptor) {
        this.lspTenantContextInterceptor = lspTenantContextInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(lspTenantContextInterceptor)
                .addPathPatterns("/api/v1/lsp/**");
    }
}
