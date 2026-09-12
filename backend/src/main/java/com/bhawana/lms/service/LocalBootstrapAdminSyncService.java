package com.bhawana.lms.service;

import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.AppUserAuditEvent;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.AppUserAuditEventRepository;
import com.bhawana.lms.security.AuthPrincipalCache;
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
import org.springframework.util.StringUtils;

// F-19: refresh_token rows FK to app_user, so the configured bootstrap admin
// must exist as a real app_user row in every profile (not just "local").
// Demo-portfolio seeding stays guarded by the app.seed.demo-portfolio.enabled
// property so production profiles do not seed sample data.
//
// Startup creates the bootstrap admin only when absent. An existing row's
// password hash, roles, status and lock state are never rewritten by a restart; only the
// explicit authorized sync route (POST /api/v1/internal/system/bootstrap-sync, SYSTEM_ADMIN)
// restores a missing row, with a durable audit that carries no password material. There is no
// startup reset boolean. Recovery after deletion requires another active SYSTEM_ADMIN: the
// sync route re-checks actor liveness instead of trusting a cached snapshot, so a deleted
// subject cannot restore itself (not even inside the principal-cache staleness bound).
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
    private final AuthPrincipalCache authPrincipalCache;

    public LocalBootstrapAdminSyncService(
            SecurityProperties securityProperties,
            AppUserRepository appUserRepository,
            AppRoleRepository appRoleRepository,
            AppUserAuditEventRepository appUserAuditEventRepository,
            PasswordEncoder passwordEncoder,
            Environment environment,
            ObjectProvider<LocalDemoPortfolioSeedService> localDemoPortfolioSeedServiceProvider,
            AdminScopedTransactionExecutor adminScopedTransactionExecutor,
            ObjectMapper objectMapper,
            AuthPrincipalCache authPrincipalCache
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
        this.authPrincipalCache = authPrincipalCache;
    }

    @Override
    public void run(ApplicationArguments args) {
        adminScopedTransactionExecutor.run(this::bootstrapAdmin);
    }

    /**
     * On-demand bootstrap heal (Spec S10): creates the configured bootstrap admin when absent
     * without requiring a process restart. Never mutates an existing row and never resets
     * credentials: the explicit password/session reset path for any managed user (including the
     * bootstrap admin) is the existing authorized admin reset
     * ({@code UserAdminService.resetUserPassword} by a peer SYSTEM_ADMIN — audited, temporary
     * password, sessions revoked). Idempotent and audited; the audit carries no password
     * material.
     */
    public void syncBootstrapAdmin(String actorUsername, String correlationId) {
        adminScopedTransactionExecutor.run(() -> {
            requireActiveActor(actorUsername);
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
                            "result", result.userExisted() ? "PRESENT" : "RESTORED",
                            "username", result.user().getUsername()
                    )),
                    correlationId
            ));
        });
    }

    private BootstrapSyncResult bootstrapAdmin() {
        // F-11: bootstrap username and derived email canonicalised to lowercase
        // so the unique indexes can satisfy the new raw-equality lookups.
        String username = requireConfiguredText(
                securityProperties.getBootstrapUser().getUsername(),
                "app.security.bootstrap-user.username");
        username = username.trim().toLowerCase();
        String email = requireConfiguredText(
                securityProperties.getBootstrapUser().getEmail(),
                "app.security.bootstrap-user.email");
        email = email.trim().toLowerCase();
        Set<RoleCode> roleCodes = securityProperties.getBootstrapUser().getRoles().stream()
                .map(this::toRoleCode)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (roleCodes.isEmpty()) {
            throw new IllegalStateException("Bootstrap user roles must be configured explicitly.");
        }
        ensureRolesExist(roleCodes);
        Set<AppRole> roles = new LinkedHashSet<>(appRoleRepository.findByCodeIn(roleCodes));

        if (roles.size() != roleCodes.size()) {
            throw new IllegalStateException("Bootstrap user roles are not fully available.");
        }

        var existingBootstrapUser = appUserRepository.findByUsername(username);
        if (existingBootstrapUser.isPresent()) {
            // Restart/sync must not reset an operator-changed password, roles, status or
            // lock state. Creation happens only below, when absent.
            return new BootstrapSyncResult(existingBootstrapUser.get(), true);
        }

        String rawPassword = requireConfiguredText(
                securityProperties.getBootstrapUser().getPassword(),
                "app.security.bootstrap-user.password");
        AppUser created = appUserRepository.save(new AppUser(
                username,
                email,
                passwordEncoder.encode(rawPassword),
                UserStatus.ACTIVE,
                null,
                roles
        ));

        if (environment.getProperty("app.seed.demo-portfolio.enabled", Boolean.class, false)) {
            localDemoPortfolioSeedServiceProvider.ifAvailable(LocalDemoPortfolioSeedService::seedDemoPortfolio);
        }
        return new BootstrapSyncResult(created, false);
    }

    /**
     * The sync route must not become self-service for a deleted subject: evict any cached
     * snapshot for the actor and require a live ACTIVE managed row. Recovery therefore needs a
     * different active SYSTEM_ADMIN (endpoint authorization) than the account being restored.
     */
    private void requireActiveActor(String actorUsername) {
        if (!StringUtils.hasText(actorUsername)) {
            throw new IllegalStateException("Bootstrap sync requires an authenticated actor.");
        }
        String canonicalActor = actorUsername.trim().toLowerCase();
        authPrincipalCache.evictAppUser(canonicalActor);
        AppUser actor = appUserRepository.findByUsername(canonicalActor)
                .orElseThrow(() -> new IllegalStateException(
                        "Bootstrap sync requires an active managed admin; unknown actor."));
        if (actor.getStatus() != UserStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Bootstrap sync requires an active managed admin; actor is not active.");
        }
    }

    private static String requireConfiguredText(String value, String property) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(property + " must be configured explicitly.");
        }
        return value;
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
