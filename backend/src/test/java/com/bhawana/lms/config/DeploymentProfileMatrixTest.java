package com.bhawana.lms.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bhawana.lms.service.DisbursementSimulationGuard;
import com.bhawana.lms.tenant.TenantAwareDataSourceProperties;
import com.bhawana.lms.tenant.TenantDatasourceSecurityValidator;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

/**
 * Foundation regression: deployment-profile gating must be fail-closed.
 *
 * <p>Exemptions require a nonempty explicitly active all-local/test profile set. Unset (implicit
 * default), misspelled, prod and local+prod combinations must all run safety checks. Secrets, the
 * required secure cookie, storage and database settings are validated together; a fully valid
 * secure nonlocal configuration must still start.
 */
class DeploymentProfileMatrixTest {

    private static final String LEGACY_TENANT_PASSWORD = "lms_tenant_app_password";

    @Test
    void unsetProfileWithImplicitLocalDefaultStillRunsSafetyChecks() {
        // Simulates application.yml's spring.profiles.default=local: no active profile, but the
        // default-profile fallback silently selects local and exempts weak configuration.
        MockEnvironment environment = weakSecretsEnvironment();
        environment.setDefaultProfiles("local");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new UnsafeDeploymentConfigurationValidator(environment),
                "Unset profile must not inherit a local exemption from default profiles"
        );
        assertTrue(exception.getMessage().contains("app.security.bootstrap-user.password"));
    }

    @Test
    void unsetProfileWithNoDefaultsRunsSafetyChecks() {
        MockEnvironment environment = weakSecretsEnvironment();

        assertThrows(
                IllegalStateException.class,
                () -> new UnsafeDeploymentConfigurationValidator(environment),
                "Unset profile must enforce runtime safety"
        );
    }

    @Test
    void misspelledProfileRunsSafetyChecks() {
        MockEnvironment environment = weakSecretsEnvironment();
        environment.setActiveProfiles("loca");

        assertThrows(
                IllegalStateException.class,
                () -> new UnsafeDeploymentConfigurationValidator(environment)
        );
    }

    @Test
    void prodProfileRunsSafetyChecks() {
        MockEnvironment environment = weakSecretsEnvironment();
        environment.setActiveProfiles("prod");

        assertThrows(
                IllegalStateException.class,
                () -> new UnsafeDeploymentConfigurationValidator(environment)
        );
    }

    @Test
    void localPlusProdCombinationRunsTenantSafetyChecks() {
        TenantAwareDataSourceProperties properties = new TenantAwareDataSourceProperties();
        properties.setUsername("lms_tenant_app");
        properties.setPassword(LEGACY_TENANT_PASSWORD);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local", "prod");

        TenantDatasourceSecurityValidator validator = tenantValidator(properties, environment);

        // Pre-fix the tenant validator skips when ANY profile is local/test, so local+prod
        // silently bypasses the legacy-password check.
        assertThrows(
                IllegalStateException.class,
                () -> validator.run(new DefaultApplicationArguments(new String[0]))
        );
    }

    @Test
    void localPlusProdCombinationRunsSecretSafetyChecks() {
        MockEnvironment environment = weakSecretsEnvironment();
        environment.setActiveProfiles("local", "prod");

        assertThrows(
                IllegalStateException.class,
                () -> new UnsafeDeploymentConfigurationValidator(environment)
        );
    }

    @Test
    void explicitLocalProfileKeepsExemption() {
        MockEnvironment environment = weakSecretsEnvironment();
        environment.setActiveProfiles("local");

        assertDoesNotThrow(() -> new UnsafeDeploymentConfigurationValidator(environment));
    }

    @Test
    void explicitTestProfileKeepsExemption() {
        MockEnvironment environment = weakSecretsEnvironment();
        environment.setActiveProfiles("test");

        assertDoesNotThrow(() -> new UnsafeDeploymentConfigurationValidator(environment));
    }

    @Test
    void insecureCookieSettingFailsOutsideDevProfiles() {
        MockEnvironment environment = strongSecretsEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("app.security.jwt.secure-cookies", "false");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> UnsafeDeploymentConfigurationValidator.validate(environment)
        );
        assertTrue(exception.getMessage().contains("secure-cookies"));
    }

    @Test
    void partiallyConfiguredR2StorageFailsOutsideDevProfiles() {
        MockEnvironment environment = strongSecretsEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("app.storage.documents.provider", "R2");
        environment.setProperty("app.storage.documents.r2.endpoint", "");
        environment.setProperty("app.storage.documents.r2.bucket", "lms-docs");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> UnsafeDeploymentConfigurationValidator.validate(environment)
        );
        assertTrue(exception.getMessage().contains("app.storage.documents"));
    }

    @Test
    void legacyTenantPasswordFailsThroughSharedSecretChecks() {
        MockEnvironment environment = strongSecretsEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty(
                "app.datasource.tenant.password",
                LEGACY_TENANT_PASSWORD
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> UnsafeDeploymentConfigurationValidator.validate(environment)
        );
        assertTrue(exception.getMessage().contains("app.datasource.tenant.password"));
    }

    @Test
    void insecureCookieHasNoSettingSpecificLeak() {
        MockEnvironment environment = strongSecretsEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("app.security.jwt.secure-cookies", "false");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> UnsafeDeploymentConfigurationValidator.validate(environment)
        );
        String message = exception.getMessage();
        assertTrue(!message.contains("StrongBootstrap") && !message.contains("production-jwt-secret"));
    }

    @Test
    void validSecureNonlocalConfigurationStarts() {
        MockEnvironment environment = strongSecretsEnvironment();
        environment.setActiveProfiles("prod");

        assertDoesNotThrow(() -> UnsafeDeploymentConfigurationValidator.validate(environment));
        assertDoesNotThrow(() -> tenantValidator(tenantProperties(environment), environment)
                .run(new DefaultApplicationArguments(new String[0])));
    }

    @Test
    void simulationGuardDeniesUnsetAndMixedProfilesWithoutWeakening() {
        MockEnvironment unset = new MockEnvironment();
        assertTrue(!new DisbursementSimulationGuard(unset).isSimulationAllowed());

        MockEnvironment mixed = new MockEnvironment();
        mixed.setActiveProfiles("test", "prod");
        assertTrue(!new DisbursementSimulationGuard(mixed).isSimulationAllowed());

        MockEnvironment localOnly = new MockEnvironment();
        localOnly.setActiveProfiles("local");
        assertTrue(new DisbursementSimulationGuard(localOnly).isSimulationAllowed());
    }

    private static MockEnvironment weakSecretsEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.security.bootstrap-user.password", "ChangeMe123!");
        environment.setProperty(
                "app.security.jwt.secret",
                "change-me-local-dev-secret-change-me-local-dev"
        );
        environment.setProperty("app.security.jwt.secure-cookies", "true");
        environment.setProperty("app.storage.documents.provider", "LOCAL");
        environment.setProperty("app.storage.documents.root-path", "/tmp/lms-documents");
        environment.setProperty("app.datasource.tenant.username", "lms_tenant_app");
        environment.setProperty("app.datasource.tenant.password", "rotated-tenant-password-for-tests");
        return environment;
    }

    private static MockEnvironment strongSecretsEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.security.bootstrap-user.username", "ops.admin");
        environment.setProperty("app.security.bootstrap-user.email", "ops.admin@example.com");
        environment.setProperty("app.security.bootstrap-user.password", "StrongBootstrapPassword123!");
        environment.setProperty(
                "app.security.jwt.secret",
                "production-jwt-secret-with-sufficient-length-xyz"
        );
        environment.setProperty("app.security.jwt.secure-cookies", "true");
        environment.setProperty("app.storage.documents.provider", "LOCAL");
        environment.setProperty("app.storage.documents.root-path", "/tmp/lms-documents");
        environment.setProperty("app.datasource.tenant.username", "lms_tenant_app");
        environment.setProperty("app.datasource.tenant.password", "rotated-tenant-password-for-tests");
        return environment;
    }

    private static TenantAwareDataSourceProperties tenantProperties(Environment environment) {
        TenantAwareDataSourceProperties properties = new TenantAwareDataSourceProperties();
        properties.setUsername(environment.getProperty("app.datasource.tenant.username"));
        properties.setPassword(environment.getProperty("app.datasource.tenant.password"));
        return properties;
    }

    private static TenantDatasourceSecurityValidator tenantValidator(
            TenantAwareDataSourceProperties properties,
            MockEnvironment environment
    ) {
        return new TenantDatasourceSecurityValidator(
                properties,
                new DataSourceProperties(),
                stubRoutingDataSource(),
                environment
        );
    }

    private static DataSource stubRoutingDataSource() {
        return new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                "jdbc:h2:mem:h19validator;DB_CLOSE_DELAY=-1", true);
    }
}
