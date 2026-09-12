package com.bhawana.lms.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Fails application startup on production-like deployments with unsafe defaults.
 *
 * <p>Exemptions require a nonempty explicitly active all-local/test profile set (see
 * {@link DeploymentProfiles}); unset, misspelled, production-like and mixed profiles all enforce
 * runtime safety. Secrets, the required secure cookie, storage and tenant-database settings are
 * validated together through this validator and {@code TenantDatasourceSecurityValidator}; error
 * details name the failing setting but never print secret values. A fully valid secure nonlocal
 * configuration (strong secrets, secure cookies, LOCAL storage with a root path or fully wired
 * R2, rotated tenant credentials) starts cleanly.
 *
 * <p>Simulator configuration stays enforced by {@code DisbursementSimulationGuard}, which is
 * profile-gated independently and is not weakened here: outside its explicit simulation profiles
 * the simulator adapter fails closed at construction.
 */
@Configuration
public class UnsafeDeploymentConfigurationValidator {

    private static final String DEFAULT_BOOTSTRAP_PASSWORD = "ChangeMe123!";
    private static final String LOCAL_JWT_PLACEHOLDER = "change-me-local-dev-secret-change-me-local-dev";
    private static final String TEST_JWT_PLACEHOLDER = "change-me-test-secret-change-me-test-secret";
    private static final int MIN_JWT_SECRET_LENGTH = 32;

    static final String LEGACY_TENANT_PASSWORD = "lms_tenant_app_password";

    private static final String PROP_BOOTSTRAP_USERNAME = "app.security.bootstrap-user.username";
    private static final String PROP_BOOTSTRAP_EMAIL = "app.security.bootstrap-user.email";
    private static final String PROP_BOOTSTRAP_PASSWORD = "app.security.bootstrap-user.password";
    private static final String PROP_JWT_SECRET = "app.security.jwt.secret";
    private static final String PROP_JWT_HUMAN_AUDIENCE = "app.security.jwt.human-audience";
    private static final String PROP_JWT_MACHINE_AUDIENCE = "app.security.jwt.machine-audience";
    private static final String PROP_SECURE_COOKIES = "app.security.jwt.secure-cookies";
    private static final String PROP_STORAGE_PROVIDER = "app.storage.documents.provider";
    private static final String PROP_STORAGE_ROOT_PATH = "app.storage.documents.root-path";
    private static final String PROP_STORAGE_R2_ENDPOINT = "app.storage.documents.r2.endpoint";
    private static final String PROP_STORAGE_R2_ACCESS_KEY = "app.storage.documents.r2.access-key";
    private static final String PROP_STORAGE_R2_SECRET_KEY = "app.storage.documents.r2.secret-key";
    private static final String PROP_STORAGE_R2_BUCKET = "app.storage.documents.r2.bucket";
    private static final String PROP_TENANT_USERNAME = "app.datasource.tenant.username";
    private static final String PROP_TENANT_PASSWORD = "app.datasource.tenant.password";

    public UnsafeDeploymentConfigurationValidator(Environment environment) {
        if (DeploymentProfiles.isDevExempt(environment)) {
            return;
        }
        validate(environment);
    }

    static void validate(Environment environment) {
        List<String> violations = new ArrayList<>();

        validateBootstrapIdentity(environment, violations);
        validateJwtSecret(environment, violations);
        validateJwtAudiences(environment, violations);
        validateSecureCookies(environment, violations);
        validateStorage(environment, violations);
        validateTenantCredentials(environment, violations);

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Unsafe configuration for a non-local deployment profile. "
                            + "Set strong secrets via environment variables or a secrets manager. Details: "
                            + String.join(" ", violations)
            );
        }
    }

    private static void validateBootstrapIdentity(Environment environment, List<String> violations) {
        if (!StringUtils.hasText(environment.getProperty(PROP_BOOTSTRAP_USERNAME))) {
            violations.add(PROP_BOOTSTRAP_USERNAME + " must be set explicitly.");
        }
        if (!StringUtils.hasText(environment.getProperty(PROP_BOOTSTRAP_EMAIL))) {
            violations.add(PROP_BOOTSTRAP_EMAIL + " must be set explicitly.");
        }
        String bootstrapPassword = environment.getProperty(PROP_BOOTSTRAP_PASSWORD);
        if (!StringUtils.hasText(bootstrapPassword)) {
            violations.add(PROP_BOOTSTRAP_PASSWORD + " must be set.");
        } else if (DEFAULT_BOOTSTRAP_PASSWORD.equals(bootstrapPassword)) {
            violations.add(PROP_BOOTSTRAP_PASSWORD + " must not use the default development password.");
        }
    }

    private static void validateJwtSecret(Environment environment, List<String> violations) {
        String jwtSecret = environment.getProperty(PROP_JWT_SECRET);
        if (!StringUtils.hasText(jwtSecret)) {
            violations.add(PROP_JWT_SECRET + " must be set.");
        } else {
            if (jwtSecret.length() < MIN_JWT_SECRET_LENGTH) {
                violations.add(PROP_JWT_SECRET + " must be at least " + MIN_JWT_SECRET_LENGTH + " characters.");
            }
            if (LOCAL_JWT_PLACEHOLDER.equals(jwtSecret) || TEST_JWT_PLACEHOLDER.equals(jwtSecret)) {
                violations.add(PROP_JWT_SECRET + " must not use a development placeholder value.");
            }
        }
    }

    private static void validateJwtAudiences(Environment environment, List<String> violations) {
        // Equal audiences would let one branch's tokens verify on the other. Bind-time
        // bean validation rejects this too; this startup check covers placeholder-resolved
        // environments where binding has not yet run.
        String humanAudience = environment.getProperty(PROP_JWT_HUMAN_AUDIENCE);
        String machineAudience = environment.getProperty(PROP_JWT_MACHINE_AUDIENCE);
        if (StringUtils.hasText(humanAudience)
                && StringUtils.hasText(machineAudience)
                && humanAudience.equals(machineAudience)) {
            violations.add(PROP_JWT_HUMAN_AUDIENCE + " and " + PROP_JWT_MACHINE_AUDIENCE + " must differ.");
        }
    }

    private static void validateSecureCookies(Environment environment, List<String> violations) {
        Boolean secureCookies = environment.getProperty(PROP_SECURE_COOKIES, Boolean.class, Boolean.TRUE);
        if (!Boolean.TRUE.equals(secureCookies)) {
            violations.add(PROP_SECURE_COOKIES + " must be true outside local/test profiles.");
        }
    }

    private static void validateStorage(Environment environment, List<String> violations) {
        String provider = environment.getProperty(PROP_STORAGE_PROVIDER, String.class, "LOCAL");
        if ("R2".equalsIgnoreCase(provider == null ? "" : provider.trim())) {
            requireStorageSetting(environment, violations, PROP_STORAGE_R2_ENDPOINT);
            requireStorageSetting(environment, violations, PROP_STORAGE_R2_ACCESS_KEY);
            requireStorageSetting(environment, violations, PROP_STORAGE_R2_SECRET_KEY);
            requireStorageSetting(environment, violations, PROP_STORAGE_R2_BUCKET);
        } else {
            if (!StringUtils.hasText(environment.getProperty(PROP_STORAGE_ROOT_PATH))) {
                violations.add(PROP_STORAGE_ROOT_PATH + " must be set for LOCAL document storage.");
            }
        }
    }

    private static void requireStorageSetting(
            Environment environment, List<String> violations, String property) {
        if (!StringUtils.hasText(environment.getProperty(property))) {
            violations.add(property + " must be set when R2 document storage is selected.");
        }
    }

    private static void validateTenantCredentials(Environment environment, List<String> violations) {
        if (!StringUtils.hasText(environment.getProperty(PROP_TENANT_USERNAME))) {
            violations.add(PROP_TENANT_USERNAME + " must be set explicitly.");
        }
        String password = environment.getProperty(PROP_TENANT_PASSWORD);
        if (!StringUtils.hasText(password)) {
            violations.add(PROP_TENANT_PASSWORD + " must be set.");
        } else if (LEGACY_TENANT_PASSWORD.equals(password)) {
            violations.add(PROP_TENANT_PASSWORD + " must not use the legacy default value.");
        }
    }
}
