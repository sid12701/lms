package com.bhawana.lms.tenant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

final class TenantAwareDataSource extends DelegatingDataSource {

    TenantAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = super.getConnection();
        applyTenantContext(connection);
        return connection;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection connection = super.getConnection(username, password);
        applyTenantContext(connection);
        return connection;
    }

    private static void applyTenantContext(Connection connection) throws SQLException {
        UUID lspId = TenantDataAccessContextHolder.getCurrentLspId();
        String value = lspId == null ? "" : lspId.toString();
        // SET LOCAL: the binding is tied to the current transaction and is
        // discarded on commit/rollback, so a pooled connection never carries a
        // previous tenant's lspId into its next checkout. The tenant Hikari
        // pool is configured with autoCommit=false so this runs inside a tx
        // even for non-transactional reads.
        try (PreparedStatement statement = connection.prepareStatement(
                "select set_config('app.current_lsp_id', ?, true)"
        )) {
            statement.setString(1, value);
            statement.execute();
        }
    }
}
