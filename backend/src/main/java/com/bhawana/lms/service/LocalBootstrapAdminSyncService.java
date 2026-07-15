package com.bhawana.lms.service;

import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.AppUserAuditEvent;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.AppUserAuditEventRepository;
import com.bhawana.lms.security.SecurityProperties;
import com.bhawana.lms.tenant.AdminScopedTransactionExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

// F-19: refresh_token rows FK to app_user, so the configured bootstrap admin
// must exist as a real app_user row in every profile (not just "local").
// Demo-portfolio seeding stays guarded by the app.seed.demo-portfolio.enabled
// property so production profiles do not seed sample data.
@Component
public class LocalBootstrapAdminSyncService implements ApplicationRunner {

    private static final Map<RoleCode, String> DEFAULT_ROLE_DESCRIPTIONS = Map.of(
            RoleCode.SYSTEM_ADMIN, "Full access to all tenants and system controls",
            RoleCode.OPS_USER, "Operational access across loan lifecycle workflows",
            RoleCode.PRODUCT_ADMIN, "Loan product configuration and mapping control",
            RoleCode.LSP_UI_READ, "Read-only tenant UI access",
            RoleCode.LSP_UI_WRITE, "Read-write tenant UI access",
            RoleCode.LSP_API_CLIENT, "Machine-to-machine tenant integration access"
    );

    private final SecurityProperties securityProperties;
    private final AppUserRepository appUserRepository;
    private final AppRoleRepository appRoleRepository;
    private final AppUserAuditEventRepository appUserAuditEventRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;
    private final ObjectProvider<LocalDemoPortfolioSeedService> localDemoPortfolioSeedServiceProvider;
    private final AdminScopedTransactionExecutor adminScopedTransactionExecutor;
    private final ObjectMapper objectMapper;

    public LocalBootstrapAdminSyncService(
            SecurityProperties securityProperties,
            AppUserRepository appUserRepository,
            AppRoleRepository appRoleRepository,
            AppUserAuditEventRepository appUserAuditEventRepository,
            PasswordEncoder passwordEncoder,
            Environment environment,
            ObjectProvider<LocalDemoPortfolioSeedService> localDemoPortfolioSeedServiceProvider,
            AdminScopedTransactionExecutor adminScopedTransactionExecutor,
            ObjectMapper objectMapper
    ) {
        this.securityProperties = securityProperties;
        this.appUserRepository = appUserRepository;
        this.appRoleRepository = appRoleRepository;
        this.appUserAuditEventRepository = appUserAuditEventRepository;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
        this.localDemoPortfolioSeedServiceProvider = localDemoPortfolioSeedServiceProvider;
        this.adminScopedTransactionExecutor = adminScopedTransactionExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        adminScopedTransactionExecutor.run(this::bootstrapAdmin);
    }

    /**
     * On-demand bootstrap heal (Spec S10): re-upserts the configured bootstrap
     * admin without requiring a process restart. Idempotent.
     */
    public void syncBootstrapAdmin(String actorUsername, String correlationId) {
        adminScopedTransactionExecutor.run(() -> {
            BootstrapSyncResult result = bootstrapAdmin();
            appUserAuditEventRepository.save(new AppUserAuditEvent(
                    result.user(),
                    actorUsername,
                    serializeAudit(Map.of(
                            "eventType", "BOOTSTRAP_SYNC",
                            "userExisted", result.userExisted()
                    )),
                    serializeAudit(Map.of(
                            "eventType", "BOOTSTRAP_SYNC",
                            "result", result.userExisted() ? "SYNCHRONIZED" : "RESTORED",
                            "username", result.user().getUsername()
                    )),
                    correlationId
            ));
        });
    }

    private BootstrapSyncResult bootstrapAdmin() {
        // F-11: bootstrap username and derived email canonicalised to lowercase
        // so the unique indexes can satisfy the new raw-equality lookups.
        String username = securityProperties.getBootstrapUser().getUsername().trim().toLowerCase();
        String rawPassword = securityProperties.getBootstrapUser().getPassword();
        // Email is the login identifier; honour the configured bootstrap email
        // (getEmail() falls back to <username>@bhawana.local when unset).
        String email = securityProperties.getBootstrapUser().getEmail();
        Set<RoleCode> roleCodes = securityProperties.getBootstrapUser().getRoles().stream()
                .map(this::toRoleCode)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        ensureRolesExist(roleCodes);
        Set<AppRole> roles = new LinkedHashSet<>(appRoleRepository.findByCodeIn(roleCodes));

        if (roles.size() != roleCodes.size()) {
            throw new IllegalStateException("Bootstrap user roles are not fully available.");
        }

        var existingBootstrapUser = appUserRepository.findByUsername(username);
        boolean userExisted = existingBootstrapUser.isPresent();
        AppUser user = existingBootstrapUser
                .map(existingUser -> {
                    boolean passwordChanged = !passwordEncoder.matches(rawPassword, existingUser.getPasswordHash());
                    String newPasswordHash = passwordChanged ? passwordEncoder.encode(rawPassword) : null;
                    existingUser.synchronizeBootstrapAccount(email, newPasswordHash, roles);
                    if (isLocalProfile() && existingUser.isLocked()) {
                        existingUser.unlockForReset();
                    }
                    return appUserRepository.save(existingUser);
                })
                .orElseGet(() -> appUserRepository.save(new AppUser(
                        username,
                        email,
                        passwordEncoder.encode(rawPassword),
                        UserStatus.ACTIVE,
                        null,
                        roles
                )));

        if (environment.getProperty("app.seed.demo-portfolio.enabled", Boolean.class, false)) {
            localDemoPortfolioSeedServiceProvider.ifAvailable(LocalDemoPortfolioSeedService::seedDemoPortfolio);
        }
        return new BootstrapSyncResult(user, userExisted);
    }

    private boolean isLocalProfile() {
        return java.util.Arrays.stream(environment.getActiveProfiles())
                .anyMatch("local"::equalsIgnoreCase);
    }

    private RoleCode toRoleCode(String roleName) {
        String normalized = roleName.startsWith("ROLE_")
                ? roleName.substring("ROLE_".length())
                : roleName;
        return RoleCode.valueOf(normalized);
    }

    private void ensureRolesExist(Set<RoleCode> roleCodes) {
        for (RoleCode roleCode : roleCodes) {
            if (!appRoleRepository.existsByCode(roleCode)) {
                String description = DEFAULT_ROLE_DESCRIPTIONS.getOrDefault(roleCode, roleCode.name());
                appRoleRepository.save(new AppRole(roleCode, description));
            }
        }
    }

    private String serializeAudit(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize bootstrap sync audit event.", exception);
        }
    }

    private record BootstrapSyncResult(AppUser user, boolean userExisted) {
    }
}
