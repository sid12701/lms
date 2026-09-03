package com.bhawana.lms.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class IntegrationTestDatabaseTargetGuardTest {

    @Test
    void rejectsSupabaseUrl() throws SQLException {
        DataSource dataSource = dataSourceWithUrl(
                "jdbc:postgresql://db.abcdefghijklmnop.supabase.co:5432/postgres");

        assertThatThrownBy(() -> IntegrationTestDatabaseTargetGuard.assertEphemeralTarget(dataSource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("supabase.co")
                .hasMessageContaining("refused");
    }

    @Test
    void rejectsAzureFlexibleServerUrl() throws SQLException {
        DataSource dataSource = dataSourceWithUrl(
                "jdbc:postgresql://my-server.postgres.database.azure.com:5432/lms");

        assertThatThrownBy(() -> IntegrationTestDatabaseTargetGuard.assertEphemeralTarget(dataSource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("postgres.database.azure.com")
                .hasMessageContaining("refused");
    }

    @Test
    void allowsInMemoryH2() throws SQLException {
        DataSource dataSource = dataSourceWithUrl("jdbc:h2:mem:lms;MODE=PostgreSQL");

        assertThatCode(() -> IntegrationTestDatabaseTargetGuard.assertEphemeralTarget(dataSource))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnmarkedLocalhostPostgres() throws SQLException {
        DataSource dataSource = dataSourceWithUrl("jdbc:postgresql://localhost:32768/lms");

        assertThatThrownBy(() -> IntegrationTestDatabaseTargetGuard.assertEphemeralTarget(dataSource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("localhost")
                .hasMessageContaining("refused");
    }

    @Test
    void allowsMarkedTestcontainersLocalhostPostgres() throws SQLException {
        DataSource dataSource = dataSourceWithUrl("jdbc:postgresql://localhost:32768/lms");

        assertThatCode(() -> IntegrationTestDatabaseTargetGuard.assertEphemeralTarget(dataSource, true))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsRemoteUrlEvenWhenLegacyExternalOptInIsSet() throws SQLException {
        System.setProperty("LMS_IT_EXTERNAL_DB", "true");
        try {
            DataSource dataSource = dataSourceWithUrl(
                    "jdbc:postgresql://db.abcdefghijklmnop.supabase.co:5432/postgres");

            assertThatThrownBy(() -> IntegrationTestDatabaseTargetGuard.assertEphemeralTarget(dataSource))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("refused");
        } finally {
            System.clearProperty("LMS_IT_EXTERNAL_DB");
        }
    }

    private static DataSource dataSourceWithUrl(String jdbcUrl) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn(jdbcUrl);
        return dataSource;
    }
}
