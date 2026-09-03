package com.bhawana.lms.support;

import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Singleton Testcontainers Postgres used by every integration test. Started once per JVM with
 * reuse and tmpfs so the full suite shares one fast ephemeral database.
 */
public final class SharedPostgresTestContainer {

    private static final String IMAGE = "postgres:17-alpine";

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> CONTAINER = new PostgreSQLContainer<>(IMAGE)
            .withDatabaseName("lms")
            .withUsername("lms")
            .withPassword("lms")
            .withReuse(true)
            .withTmpFs(Map.of("/var/lib/postgresql/data", "rw"))
            .withCommand("postgres", "-c", "max_connections=300");

    private SharedPostgresTestContainer() {
    }

    public static void ensureStarted() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
    }

    public static String jdbcUrl() {
        ensureStarted();
        return CONTAINER.getJdbcUrl();
    }

    public static String username() {
        ensureStarted();
        return CONTAINER.getUsername();
    }

    public static String password() {
        ensureStarted();
        return CONTAINER.getPassword();
    }

    public static String driverClassName() {
        ensureStarted();
        return CONTAINER.getDriverClassName();
    }
}
