package com.bhawana.lms.support;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

/**
 * Binds the shared Testcontainers Postgres datasource before any test context starts, including
 * {@code @DataJpaTest} slices that would otherwise boot embedded H2.
 */
public final class PostgresTestEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE_NAME = "sharedPostgresTestContainer";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("test"))) {
            return;
        }
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        SharedPostgresTestContainer.ensureStarted();
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.url", SharedPostgresTestContainer.jdbcUrl());
        properties.put("spring.datasource.username", SharedPostgresTestContainer.username());
        properties.put("spring.datasource.password", SharedPostgresTestContainer.password());
        properties.put("spring.datasource.driver-class-name", SharedPostgresTestContainer.driverClassName());
        properties.put("spring.flyway.enabled", true);
        properties.put("spring.jpa.hibernate.ddl-auto", "validate");
        properties.put("spring.jpa.database-platform", "org.hibernate.dialect.PostgreSQLDialect");
        properties.put(IntegrationTestDatabaseTargetGuard.EPHEMERAL_DB_PROPERTY, true);
        properties.put("spring.test.database.replace", "none");
        properties.put("spring.datasource.hikari.maximum-pool-size", 5);
        properties.put("spring.datasource.hikari.minimum-idle", 0);
        properties.put("spring.datasource.hikari.max-lifetime", 60000);
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, properties));
    }
}
