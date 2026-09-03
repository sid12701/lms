package com.bhawana.lms.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class TenantAwareDataSourceTest {

    @AfterEach
    void clearTenantContext() {
        TenantDataAccessContextHolder.clear();
    }

    @Test
    void directLoginStrategyOnlySetsTenantGuc() throws SQLException {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement preparedStatement = mock(PreparedStatement.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("select set_config('app.current_lsp_id', ?, true)"))
                .thenReturn(preparedStatement);

        TenantAwareDataSource dataSource = new TenantAwareDataSource(
                delegate,
                "lms_tenant_app",
                TenantConnectionStrategy.DIRECT_LOGIN
        );
        UUID lspId = UUID.randomUUID();
        TenantDataAccessContextHolder.useTenant(lspId);
        dataSource.getConnection();

        InOrder inOrder = inOrder(connection);
        inOrder.verify(connection, org.mockito.Mockito.never()).createStatement();
        inOrder.verify(connection).prepareStatement("select set_config('app.current_lsp_id', ?, true)");
    }

    @Test
    void assumeRoleStrategySetsLocalRoleInsideTransaction() throws SQLException {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement preparedStatement = mock(PreparedStatement.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement("select set_config('app.current_lsp_id', ?, true)"))
                .thenReturn(preparedStatement);

        TenantAwareDataSource dataSource = new TenantAwareDataSource(
                delegate,
                "lms_tenant_app",
                TenantConnectionStrategy.ASSUME_ROLE
        );
        UUID lspId = UUID.randomUUID();
        TenantDataAccessContextHolder.useTenant(lspId);
        dataSource.getConnection();

        InOrder inOrder = inOrder(statement, connection);
        inOrder.verify(statement).execute("SET LOCAL ROLE lms_tenant_app");
        inOrder.verify(connection).prepareStatement("select set_config('app.current_lsp_id', ?, true)");
    }

    @Test
    void assumeRoleStrategyRejectsInvalidRoleNames() throws SQLException {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(delegate.getConnection()).thenReturn(connection);
        TenantAwareDataSource dataSource = new TenantAwareDataSource(
                delegate,
                "bad-role-name",
                TenantConnectionStrategy.ASSUME_ROLE
        );
        TenantDataAccessContextHolder.useTenant(UUID.randomUUID());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> dataSource.getConnection()
        );
        assertTrue(exception.getMessage().contains("Invalid tenant role"));
    }
}
