package com.bhawana.lms.tenant;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TenantDatasourceSecurityValidator implements ApplicationRunner {

    static final String LEGACY_DEFAULT_PASSWORD = "lms_tenant_app_password";

    private final TenantAwareDataSourceProperties tenantProperties;
    private final Environment environment;

    public TenantDatasourceSecurityValidator(
            TenantAwareDataSourceProperties tenantProperties,
            Environment environment
    ) {
        this.tenantProperties = tenantProperties;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (isLocalOrTestProfile()) {
            return;
        }

        String password = tenantProperties.getPassword();
        if (!StringUtils.hasText(password)) {
            throw new IllegalStateException(
                    "APP_TENANT_DATASOURCE_PASSWORD is required outside the local profile. "
                            + "Set a non-default tenant password in the secret store and rotate the "
                            + "lms_tenant_app role before deploying."
            );
        }
        if (LEGACY_DEFAULT_PASSWORD.equals(password)) {
            throw new IllegalStateException(
                    "Tenant datasource password is still the legacy default (lms_tenant_app_password). "
                            + "Rotate APP_TENANT_DATASOURCE_PASSWORD and ALTER ROLE lms_tenant_app PASSWORD "
                            + "on each environment before deploying."
            );
        }
    }

    private boolean isLocalOrTestProfile() {
        String[] activeProfiles = environment.getActiveProfiles();
        String[] profilesToCheck = activeProfiles.length == 0
                ? environment.getDefaultProfiles()
                : activeProfiles;
        for (String profile : profilesToCheck) {
            if ("local".equals(profile) || "test".equals(profile)) {
                return true;
            }
        }
        return false;
    }
}
