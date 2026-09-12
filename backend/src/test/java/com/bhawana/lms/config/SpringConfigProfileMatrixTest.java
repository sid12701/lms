package com.bhawana.lms.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bhawana.lms.security.SecurityProperties;
import com.bhawana.lms.service.DisbursementSimulationGuard;
import com.bhawana.lms.tenant.TenantAwareDataSourceProperties;
import com.bhawana.lms.tenant.TenantDatasourceSecurityValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Actual Spring Boot config/profile loading matrix.
 *
 * <p>Unlike the constructor-level matrix, this loads the real packaged YAML through Boot
 * ConfigData (the same mechanism production uses to select {@code application.yml} plus
 * {@code application-&lt;profile&gt;.yml}) and runs the production gates against the resulting
 * environment: profile selection and document precedence come from Boot, not from a hand-built
 * {@code YamlPropertySourceLoader}. Positive selections additionally bind the production
 * {@link SecurityProperties}/{@link TenantAwareDataSourceProperties} beans and assert the
 * resulting bound values.
 *
 * <p>Host influence is removed from every case: the OS environment source is stripped before
 * ConfigData runs (so ambient {@code APP_*}/{@code SPRING_PROFILES_ACTIVE} variables cannot
 * inject secrets or profiles), every property a negative case depends on is pinned explicitly,
 * and the repo-root {@code .env} imported by the local profile is neutralized by higher-
 * precedence pins wherever it could affect an assertion. File-content hygiene for the packaged
 * documents themselves (no implicit default profile, no personal identities in base) stays in
 * the text assertions below.
 *
 * <p>What this does NOT claim: no whole-app production boot happens here and no live
 * adapter/DB boot is exercised. The tenant gate runs with an explicit test-only stub
 * {@code DataSource} plus a non-PostgreSQL URL, which by production design skips the live
 * connection-identity probe — so these tests prove configuration gating and binding only.
 * Live tenant identity stays covered by the Postgres integration tests, and the external
 * adapter remains honestly unbuilt, which is why no whole-app production start is attempted.
 */
class SpringConfigProfileMatrixTest {

    @Test
    void baseConfigSelectsNoDefaultProfileAndShipsNoPersonalIdentities() throws Exception {
        String base = configText("application.yml");

        assertTrue(!base.contains("profiles:"));
        assertTrue(!base.contains("default: local"));
        assertTrue(!base.contains("siddhant@"));
        assertTrue(!base.contains("ops.admin"));
        assertTrue(!base.contains("lms_tenant_app_password"));
        assertTrue(base.contains("human-audience:"));
        assertTrue(base.contains("machine-audience:"));
    }

    @Test
    void localConfigCarriesExplicitLocalExamples() throws Exception {
        String local = configText("application-local.yml");

        assertTrue(local.contains("ops.admin"));
        assertTrue(local.contains("ops.admin@bhawana.local"));
        assertTrue(local.contains("ChangeMe123!"));
    }

    @Test
    void unsetProfileFailsClosedOnRealBaseConfig() {
        baseRunner(new String[0], weakPins()).run(context -> {
            Environment environment = context.getEnvironment();
            // Base document ships the production-safe default (secure cookies on) and no
            // bootstrap identity of its own; the pinned weak secrets fail the production gate.
            assertEquals("true", environment.getProperty("app.security.jwt.secure-cookies"));
            assertTrue(environment.getProperty("app.security.bootstrap-user.password", "").contains("ChangeMe123!"));

            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> new UnsafeDeploymentConfigurationValidator(environment));
            assertTrue(exception.getMessage().contains("app.security.bootstrap-user.password"));
        });
    }

    @Test
    void misspelledProfileFailsClosedOnRealBaseConfig() {
        baseRunner(new String[]{"loca"}, weakPins()).run(context -> {
            Environment environment = context.getEnvironment();

            assertThrows(
                    IllegalStateException.class,
                    () -> new UnsafeDeploymentConfigurationValidator(environment));
        });
    }

    @Test
    void caseVariantProfileFailsClosedOnRealBaseConfig() {
        // Spring resolves application-<profile>.yml case-sensitively: LOCAL never loads the
        // local document, so it must not inherit the local exemption either.
        baseRunner(new String[]{"LOCAL"}, weakPins()).run(context -> {
            Environment environment = context.getEnvironment();

            assertThrows(
                    IllegalStateException.class,
                    () -> new UnsafeDeploymentConfigurationValidator(environment));
        });
    }

    @Test
    void prodProfileFailsClosedOnRealBaseConfig() {
        // No application-prod.yml ships, so prod resolves to the base document plus pins.
        baseRunner(new String[]{"prod"}, weakPins()).run(context -> {
            Environment environment = context.getEnvironment();

            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> new UnsafeDeploymentConfigurationValidator(environment));
            assertTrue(exception.getMessage().contains("app.security.bootstrap-user.password"));
        });
    }

    @Test
    void localPlusProdCombinationFailsClosedOnRealConfigs() {
        baseRunner(new String[]{"local", "prod"}, weakPins()).run(context -> {
            Environment environment = context.getEnvironment();

            assertThrows(
                    IllegalStateException.class,
                    () -> new UnsafeDeploymentConfigurationValidator(environment));
        });
    }

    @Test
    void explicitLocalProfileBindsProductionBeansAndIsExempt() {
        // Pins neutralize the repo-root .env imported by the local document and prove
        // precedence (explicit values beat packaged files); the literal secure-cookies=false
        // and the local JWT placeholder default prove the local document itself loaded.
        baseRunner(new String[]{"local"}, localPins())
                .withUserConfiguration(BoundBeans.class)
                .run(context -> {
                    Environment environment = context.getEnvironment();
                    assertEquals("false", environment.getProperty("app.security.jwt.secure-cookies"));

                    SecurityProperties bound = context.getBean(SecurityProperties.class);
                    assertEquals("runner.pin.user", bound.getBootstrapUser().getUsername());
                    assertEquals("runner.pin.user@example.com", bound.getBootstrapUser().getEmail());
                    assertEquals(
                            "local-dev-jwt-secret-at-least-32-characters",
                            bound.getJwt().getSecret());

                    assertDoesNotThrow(() -> new UnsafeDeploymentConfigurationValidator(environment));
                    assertDoesNotThrow(() -> tenantGate(context).run(
                            new DefaultApplicationArguments(new String[0])));
                });
    }

    @Test
    void explicitTestProfileBindsPackagedTestValuesAndIsExempt() {
        // application-test.yml carries literal values and no .env import, so these assertions
        // prove the real packaged test document binds through production beans.
        baseRunner(new String[]{"test"})
                .withUserConfiguration(BoundBeans.class)
                .run(context -> {
                    Environment environment = context.getEnvironment();

                    SecurityProperties bound = context.getBean(SecurityProperties.class);
                    assertEquals("test.admin", bound.getBootstrapUser().getUsername());
                    assertEquals("test.admin@bhawana.local", bound.getBootstrapUser().getEmail());
                    assertEquals(
                            "change-me-test-secret-change-me-test-secret",
                            bound.getJwt().getSecret());
                    assertTrue(!bound.getJwt().isSecureCookies());

                    TenantAwareDataSourceProperties tenant = context.getBean(TenantAwareDataSourceProperties.class);
                    assertEquals("lms_tenant_app", tenant.getUsername());

                    assertDoesNotThrow(() -> new UnsafeDeploymentConfigurationValidator(environment));
                });
    }

    @Test
    void validSecureNonlocalConfigPassesWithExplicitTestOnlyAdapter() {
        baseRunner(new String[]{"prod"}, strongPins())
                .withUserConfiguration(BoundBeans.class)
                .run(context -> {
                    Environment environment = context.getEnvironment();

                    SecurityProperties bound = context.getBean(SecurityProperties.class);
                    assertEquals("ops.admin", bound.getBootstrapUser().getUsername());
                    assertEquals(
                            "production-jwt-secret-with-sufficient-length-xyz",
                            bound.getJwt().getSecret());
                    assertTrue(bound.getJwt().isSecureCookies());

                    TenantAwareDataSourceProperties tenant = context.getBean(TenantAwareDataSourceProperties.class);
                    assertEquals("rotated-tenant-password-for-tests", tenant.getPassword());

                    assertDoesNotThrow(() -> new UnsafeDeploymentConfigurationValidator(environment));
                    // Explicit test-only adapter wiring: a stub DataSource plus a
                    // non-PostgreSQL URL, which by production design skips the live
                    // connection-identity probe. This proves the credential rules pass with
                    // rotated secrets; it does not boot a live adapter or database.
                    assertDoesNotThrow(() -> tenantGate(context).run(
                            new DefaultApplicationArguments(new String[0])));
                });
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

    /**
     * Production property beans bound from the real ConfigData environment. Validators are
     * deliberately NOT registered here: they are instantiated in the test bodies against the
     * live environment so both pass and fail-closed paths are asserted explicitly.
     */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({SecurityProperties.class, TenantAwareDataSourceProperties.class})
    static class BoundBeans {
    }

    private static ApplicationContextRunner baseRunner(String[] activeProfiles, String... pins) {
        return new ApplicationContextRunner(AnnotationConfigApplicationContext::new)
                .withInitializer(context -> {
                    ConfigurableEnvironment environment =
                            (ConfigurableEnvironment) context.getEnvironment();
                    // Strip OS environment before ConfigData runs: ambient APP_* values must not
                    // inject secrets and SPRING_PROFILES_ACTIVE must not inject profiles.
                    // JVM system properties are retained only for JVM-provided placeholders
                    // such as java.io.tmpdir.
                    environment.getPropertySources().remove(
                            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    for (String profile : activeProfiles) {
                        environment.addActiveProfile(profile);
                    }
                })
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(pins);
    }

    private static String[] weakPins() {
        // Deterministic weak deployment on top of the real base document: every secret the
        // production gate reads is pinned, so ambient host values cannot turn a negative case
        // into a pass. Storage is valid LOCAL wiring to keep failures focused on secrets.
        return new String[]{
                "app.security.bootstrap-user.username=",
                "app.security.bootstrap-user.email=",
                "app.security.bootstrap-user.password=ChangeMe123!",
                "app.security.jwt.secret=change-me-local-dev-secret-change-me-local-dev",
                "app.storage.documents.provider=LOCAL",
                "app.storage.documents.root-path=/tmp/lms-config-test",
                "app.datasource.tenant.username=lms_tenant_app",
                "app.datasource.tenant.password=lms_tenant_app_password"
        };
    }

    private static String[] localPins() {
        // Neutralize the repo-root .env imported by the local document; values differ from
        // both the packaged local examples and any ambient checkout state to prove precedence.
        return new String[]{
                "app.security.bootstrap-user.username=runner.pin.user",
                "app.security.bootstrap-user.email=runner.pin.user@example.com",
                "app.security.bootstrap-user.password=RunnerPinPassword123!",
                "app.datasource.tenant.password=rotated-tenant-password-for-tests"
        };
    }

    private static String[] strongPins() {
        // Simulated secret-store wiring for a secure nonlocal deployment.
        return new String[]{
                "app.security.bootstrap-user.username=ops.admin",
                "app.security.bootstrap-user.email=ops.admin@example.com",
                "app.security.bootstrap-user.password=StrongBootstrapPassword123!",
                "app.security.jwt.secret=production-jwt-secret-with-sufficient-length-xyz",
                "app.security.jwt.secure-cookies=true",
                "app.storage.documents.provider=LOCAL",
                "app.storage.documents.root-path=/var/lib/lms-documents",
                "app.datasource.tenant.username=lms_tenant_app",
                "app.datasource.tenant.password=rotated-tenant-password-for-tests"
        };
    }

    private static TenantDatasourceSecurityValidator tenantGate(
            org.springframework.context.ConfigurableApplicationContext context) {
        TenantAwareDataSourceProperties tenantProperties =
                context.getBean(TenantAwareDataSourceProperties.class);
        // Explicit test-only adapter: the stub DataSource only satisfies constructor wiring
        // and the non-PostgreSQL URL skips the live connection-identity probe by production
        // design. No live adapter or database boot is claimed from this wiring.
        DataSourceProperties dataSourceProperties = new DataSourceProperties();
        dataSourceProperties.setUrl("jdbc:h2:mem:h19config;DB_CLOSE_DELAY=-1");
        SingleConnectionDataSource stubDataSource =
                new SingleConnectionDataSource("jdbc:h2:mem:h19config;DB_CLOSE_DELAY=-1", true);
        return new TenantDatasourceSecurityValidator(
                tenantProperties,
                dataSourceProperties,
                stubDataSource,
                context.getEnvironment());
    }

    private static String configText(String resource) throws Exception {
        try (java.io.InputStream input =
                     new org.springframework.core.io.ClassPathResource(resource).getInputStream()) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
