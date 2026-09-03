package com.bhawana.lms.support;

import java.net.URI;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Refuses integration-test bulk deletes against databases that are not ephemeral
 * (in-memory H2 or an explicitly marked Testcontainers datasource).
 *
 * <p>There is deliberately no opt-in escape hatch: an environment variable that re-enables bulk
 * deletes against a developer-configured database is exactly how a seeded portfolio was destroyed
 * once already. Point integration tests at Testcontainers Postgres, never at a real database.
 */
public final class IntegrationTestDatabaseTargetGuard {

    public static final String EPHEMERAL_DB_PROPERTY = "lms.it.ephemeral-database";

    private IntegrationTestDatabaseTargetGuard() {
    }

    public static void assertEphemeralTarget(DataSource dataSource) {
        assertEphemeralTarget(dataSource, false);
    }

    public static void assertEphemeralTarget(DataSource dataSource, boolean explicitlyEphemeral) {
        try (var connection = dataSource.getConnection()) {
            assertEphemeralTarget(connection.getMetaData().getURL(), explicitlyEphemeral);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to resolve JDBC URL for integration-test cleanup guard", exception);
        }
    }

    static void assertEphemeralTarget(String jdbcUrl) {
        assertEphemeralTarget(jdbcUrl, false);
    }

    static void assertEphemeralTarget(String jdbcUrl, boolean explicitlyEphemeral) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalStateException("Integration-test cleanup refused: JDBC URL is blank");
        }
        String normalized = jdbcUrl.toLowerCase();
        if (isBlockedHostedDatabase(normalized)) {
            throw new IllegalStateException(
                    "Integration-test cleanup refused for hosted database host "
                            + describeHost(normalized)
                            + ". Integration tests must never bulk-delete a shared or production database.");
        }
        if (normalized.startsWith("jdbc:h2:") || explicitlyEphemeral) {
            return;
        }
        throw new IllegalStateException(
                "Integration-test cleanup refused for non-ephemeral database host "
                        + describeHost(normalized)
                        + ". Use in-memory H2, or mark a Testcontainers datasource with "
                        + EPHEMERAL_DB_PROPERTY
                        + "=true. Integration tests must never bulk-delete a real database.");
    }

    private static boolean isBlockedHostedDatabase(String normalizedUrl) {
        return normalizedUrl.contains("supabase.co")
                || normalizedUrl.contains("postgres.database.azure.com");
    }

    private static String describeHost(String normalizedUrl) {
        try {
            String withoutJdbcScheme = normalizedUrl.substring("jdbc:".length());
            URI uri = URI.create(withoutJdbcScheme);
            if (uri.getHost() != null) {
                return uri.getHost();
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return normalizedUrl;
    }
}
