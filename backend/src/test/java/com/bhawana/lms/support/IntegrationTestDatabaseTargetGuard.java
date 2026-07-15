package com.bhawana.lms.support;

import java.net.URI;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refuses integration-test bulk deletes against databases that are not ephemeral
 * (in-memory H2 or an explicitly marked Testcontainers datasource). Other targets require an explicit
 * {@value #EXTERNAL_DB_PROPERTY}={@code true} opt-in.
 */
public final class IntegrationTestDatabaseTargetGuard {

    public static final String EXTERNAL_DB_PROPERTY = "LMS_IT_EXTERNAL_DB";
    public static final String EPHEMERAL_DB_PROPERTY = "lms.it.ephemeral-database";

    private static final Logger log = LoggerFactory.getLogger(IntegrationTestDatabaseTargetGuard.class);

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
        if (normalized.startsWith("jdbc:h2:") || explicitlyEphemeral) {
            return;
        }
        if (isExplicitExternalOptIn()) {
            log.warn(
                    "Integration-test cleanup targeting NON-EPHEMERAL database {} — permitted only because {}=true",
                    describeHost(normalized),
                    EXTERNAL_DB_PROPERTY);
            return;
        }
        throw new IllegalStateException(
                "Integration-test cleanup refused for non-ephemeral database host "
                        + describeHost(normalized)
                        + ". Use in-memory H2, mark a Testcontainers datasource with "
                        + EPHEMERAL_DB_PROPERTY
                        + "=true, or set "
                        + EXTERNAL_DB_PROPERTY
                        + "=true to run external-db tests consciously.");
    }

    private static boolean isExplicitExternalOptIn() {
        return "true".equalsIgnoreCase(System.getenv(EXTERNAL_DB_PROPERTY))
                || "true".equalsIgnoreCase(System.getProperty(EXTERNAL_DB_PROPERTY));
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
