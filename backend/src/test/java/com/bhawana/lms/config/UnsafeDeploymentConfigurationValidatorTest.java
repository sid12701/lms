package com.bhawana.lms.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class UnsafeDeploymentConfigurationValidatorTest {

    @Test
    void prodProfileRejectsDefaultBootstrapPassword() {
        MockEnvironment environment = secureEnvironment();
        environment.setProperty("app.security.bootstrap-user.password", "ChangeMe123!");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> UnsafeDeploymentConfigurationValidator.validate(environment)
        );
        assertTrue(exception.getMessage().contains("bootstrap-user.password"));
    }

    @Test
    void prodProfileRejectsDevelopmentJwtPlaceholder() {
        MockEnvironment environment = secureEnvironment();
        environment.setProperty(
                "app.security.jwt.secret",
                "change-me-local-dev-secret-change-me-local-dev"
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> UnsafeDeploymentConfigurationValidator.validate(environment)
        );
        assertTrue(exception.getMessage().contains("jwt.secret"));
    }

    @Test
    void prodProfileRejectsMissingBootstrapIdentity() {
        MockEnvironment environment = secureEnvironment();
        environment.setProperty("app.security.bootstrap-user.username", "");
        environment.setProperty("app.security.bootstrap-user.email", "");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> UnsafeDeploymentConfigurationValidator.validate(environment)
        );
        assertTrue(exception.getMessage().contains("bootstrap-user.username"));
        assertTrue(exception.getMessage().contains("bootstrap-user.email"));
    }

    @Test
    void prodProfileRejectsEqualHumanAndMachineAudiences() {
        MockEnvironment environment = secureEnvironment();
        environment.setProperty("app.security.jwt.human-audience", "bhawana-lms-shared");
        environment.setProperty("app.security.jwt.machine-audience", "bhawana-lms-shared");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> UnsafeDeploymentConfigurationValidator.validate(environment)
        );
        assertTrue(exception.getMessage().contains("must differ"));
    }

    @Test
    void prodProfileAcceptsStrongSecrets() {
        MockEnvironment environment = secureEnvironment();

        assertDoesNotThrow(() -> UnsafeDeploymentConfigurationValidator.validate(environment));
    }

    @Test
    void localProfileSkipsValidationViaConstructor() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        environment.setProperty("app.security.bootstrap-user.password", "ChangeMe123!");
        environment.setProperty(
                "app.security.jwt.secret",
                "change-me-local-dev-secret-change-me-local-dev"
        );

        assertDoesNotThrow(() -> new UnsafeDeploymentConfigurationValidator(environment));
    }

    @Test
    void unsetProfileDoesNotSkipValidationViaConstructor() {
        MockEnvironment environment = secureEnvironment();
        environment.setProperty("app.security.bootstrap-user.password", "ChangeMe123!");

        assertThrows(
                IllegalStateException.class,
                () -> new UnsafeDeploymentConfigurationValidator(environment)
        );
    }

    private static MockEnvironment prodEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }

    private static MockEnvironment secureEnvironment() {
        MockEnvironment environment = prodEnvironment();
        environment.setProperty("app.security.bootstrap-user.username", "ops.admin");
        environment.setProperty("app.security.bootstrap-user.email", "ops.admin@example.com");
        environment.setProperty("app.security.bootstrap-user.password", "StrongBootstrapPassword123!");
        environment.setProperty(
                "app.security.jwt.secret",
                "production-jwt-secret-with-sufficient-length"
        );
        environment.setProperty("app.security.jwt.secure-cookies", "true");
        environment.setProperty("app.storage.documents.provider", "LOCAL");
        environment.setProperty("app.storage.documents.root-path", "/tmp/lms-documents");
        environment.setProperty("app.datasource.tenant.username", "lms_tenant_app");
        environment.setProperty("app.datasource.tenant.password", "rotated-tenant-password-for-tests");
        return environment;
    }
}
