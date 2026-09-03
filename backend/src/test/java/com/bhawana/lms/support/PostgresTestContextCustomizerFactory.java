package com.bhawana.lms.support;

import java.util.List;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;

/**
 * Binds every Spring test context to the shared Testcontainers Postgres datasource and Flyway
 * validation profile.
 */
public final class PostgresTestContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass, List<ContextConfigurationAttributes> config) {
        return new PostgresTestContextCustomizer();
    }

    private static final class PostgresTestContextCustomizer implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
            SharedPostgresTestContainer.ensureStarted();
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    context,
                    "spring.datasource.url=" + SharedPostgresTestContainer.jdbcUrl(),
                    "spring.datasource.username=" + SharedPostgresTestContainer.username(),
                    "spring.datasource.password=" + SharedPostgresTestContainer.password(),
                    "spring.datasource.driver-class-name=" + SharedPostgresTestContainer.driverClassName(),
                    "spring.flyway.enabled=true",
                    "spring.jpa.hibernate.ddl-auto=validate",
                    "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
                    IntegrationTestDatabaseTargetGuard.EPHEMERAL_DB_PROPERTY + "=true"
            );
        }

        @Override
        public int hashCode() {
            return PostgresTestContextCustomizer.class.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            return other != null && getClass() == other.getClass();
        }
    }
}
