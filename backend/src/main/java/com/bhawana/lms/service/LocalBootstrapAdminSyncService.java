package com.bhawana.lms.service;

import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.security.SecurityProperties;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
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
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;
    private final ObjectProvider<LocalDemoPortfolioSeedService> localDemoPortfolioSeedServiceProvider;

    public LocalBootstrapAdminSyncService(
            SecurityProperties securityProperties,
            AppUserRepository appUserRepository,
            AppRoleRepository appRoleRepository,
            PasswordEncoder passwordEncoder,
            Environment environment,
            ObjectProvider<LocalDemoPortfolioSeedService> localDemoPortfolioSeedServiceProvider
    ) {
        this.securityProperties = securityProperties;
        this.appUserRepository = appUserRepository;
        this.appRoleRepository = appRoleRepository;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
        this.localDemoPortfolioSeedServiceProvider = localDemoPortfolioSeedServiceProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        // F-11: bootstrap username and derived email canonicalised to lowercase
        // so the unique indexes can satisfy the new raw-equality lookups.
        String username = securityProperties.getBootstrapUser().getUsername().trim().toLowerCase();
        String rawPassword = securityProperties.getBootstrapUser().getPassword();
        String email = (username + "@bhawana.local").toLowerCase();
        Set<RoleCode> roleCodes = securityProperties.getBootstrapUser().getRoles().stream()
                .map(this::toRoleCode)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        ensureRolesExist(roleCodes);
        Set<AppRole> roles = new LinkedHashSet<>(appRoleRepository.findByCodeIn(roleCodes));

        if (roles.size() != roleCodes.size()) {
            throw new IllegalStateException("Bootstrap user roles are not fully available.");
        }

        appUserRepository.findByUsername(username)
                .ifPresentOrElse(existingUser -> {
                    boolean passwordChanged = !passwordEncoder.matches(rawPassword, existingUser.getPasswordHash());
                    String newPasswordHash = passwordChanged ? passwordEncoder.encode(rawPassword) : null;
                    existingUser.synchronizeBootstrapAccount(email, newPasswordHash, roles);
                    appUserRepository.save(existingUser);
                }, () -> appUserRepository.save(new AppUser(
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
}
