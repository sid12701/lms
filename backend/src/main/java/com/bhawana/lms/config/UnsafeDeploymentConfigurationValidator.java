package com.bhawana.lms.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Fails application startup on non-local, non-test profiles when unsafe default
 * credentials would be used in production-like deployments.
 */
@Configuration
public class UnsafeDeploymentConfigurationValidator {

    private static final Set<String> DEV_ONLY_PROFILES = Set.of("local", "test");

    private static final String DEFAULT_BOOTSTRAP_PASSWORD = "ChangeMe123!";
    private static final String LOCAL_JWT_PLACEHOLDER = "change-me-local-dev-secret-change-me-local-dev";
    private static final String TEST_JWT_PLACEHOLDER = "change-me-test-secret-change-me-test-secret";
    private static final int MIN_JWT_SECRET_LENGTH = 32;

    private static final String PROP_BOOTSTRAP_PASSWORD = "app.security.bootstrap-user.password";
    private static final String PROP_JWT_SECRET = "app.security.jwt.secret";

    public UnsafeDeploymentConfigurationValidator(Environment environment) {
        if (usesDevOnlyProfile(environment)) {
            return;
        }
        validate(environment);
    }

    static void validate(Environment environment) {
        List<String> violations = new ArrayList<>();

        String bootstrapPassword = environment.getProperty(PROP_BOOTSTRAP_PASSWORD);
        if (!StringUtils.hasText(bootstrapPassword)) {
            violations.add(PROP_BOOTSTRAP_PASSWORD + " must be set.");
        } else if (DEFAULT_BOOTSTRAP_PASSWORD.equals(bootstrapPassword)) {
            violations.add(PROP_BOOTSTRAP_PASSWORD + " must not use the default development password.");
        }

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

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Unsafe configuration for a non-local deployment profile. "
                            + "Set strong secrets via environment variables or a secrets manager. Details: "
                            + String.join(" ", violations)
            );
        }
    }

    private static boolean usesDevOnlyProfile(Environment environment) {
        if (environment == null) {
            return false;
        }
        String[] activeProfiles = environment.getActiveProfiles();
        String[] profilesToCheck = activeProfiles.length == 0
                ? environment.getDefaultProfiles()
                : activeProfiles;
        if (profilesToCheck.length == 0) {
            return false;
        }
        for (String profile : profilesToCheck) {
            if (!DEV_ONLY_PROFILES.contains(profile)) {
                return false;
            }
        }
        return true;
    }
}
