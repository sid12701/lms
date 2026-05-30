package com.bhawana.lms.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class UnsafeDeploymentConfigurationValidatorTest {

    @Test
    void prodProfileRejectsDefaultBootstrapPassword() {
        MockEnvironment environment = prodEnvironment();
        environment.setProperty("app.security.bootstrap-user.password", "ChangeMe123!");
        environment.setProperty(
                "app.security.jwt.secret",
                "production-jwt-secret-with-sufficient-length"
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> UnsafeDeploymentConfigurationValidator.validate(environment)
        );
        assertTrue(exception.getMessage().contains("bootstrap-user.password"));
    }

    @Test
    void prodProfileRejectsDevelopmentJwtPlaceholder() {
        MockEnvironment environment = prodEnvironment();
        environment.setProperty("app.security.bootstrap-user.password", "StrongBootstrapPassword123!");
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
    void prodProfileAcceptsStrongSecrets() {
        MockEnvironment environment = prodEnvironment();
        environment.setProperty("app.security.bootstrap-user.password", "StrongBootstrapPassword123!");
        environment.setProperty(
                "app.security.jwt.secret",
                "production-jwt-secret-with-sufficient-length"
        );

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

    private static MockEnvironment prodEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }
}
