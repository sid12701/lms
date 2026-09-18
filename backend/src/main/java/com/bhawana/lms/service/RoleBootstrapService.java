package com.bhawana.lms.service;

import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// This runner is the only app_role provisioner — no Flyway migration inserts role rows.
// ApplicationRunner execution order is bean-registration order unless declared, which varies
// across packaged-jar builds, so seeders that resolve roles (the demo portfolio under
// LocalBootstrapAdminSyncService) could hit an empty catalog and die with ROLE_UNAVAILABLE.
@Component
@Order(0)
public class RoleBootstrapService implements ApplicationRunner {

    private static final Map<RoleCode, String> DEFAULT_ROLES = Map.of(
            RoleCode.SYSTEM_ADMIN, "Full access to all tenants and system controls",
            RoleCode.OPS_USER, "Operational access across loan lifecycle workflows",
            RoleCode.PRODUCT_ADMIN, "Loan product configuration and mapping control",
            RoleCode.LSP_UI_READ, "Read-only tenant UI access",
            RoleCode.LSP_UI_WRITE, "Read-write tenant UI access",
            RoleCode.LSP_API_CLIENT, "Machine-to-machine tenant integration access"
    );

    private final AppRoleRepository appRoleRepository;

    public RoleBootstrapService(AppRoleRepository appRoleRepository) {
        this.appRoleRepository = appRoleRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        TenantScopedExecution.runAsAdmin(() -> DEFAULT_ROLES.forEach((code, description) -> {
            if (!appRoleRepository.existsByCode(code)) {
                appRoleRepository.save(new AppRole(code, description));
            }
        }));
    }
}
