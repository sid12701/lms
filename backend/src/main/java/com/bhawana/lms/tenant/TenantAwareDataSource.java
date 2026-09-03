package com.bhawana.lms.tenant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

final class TenantAwareDataSource extends DelegatingDataSource {

    private final String tenantRole;
    private final TenantConnectionStrategy connectionStrategy;

    TenantAwareDataSource(DataSource targetDataSource, String tenantRole, TenantConnectionStrategy connectionStrategy) {
        super(targetDataSource);
        this.tenantRole = tenantRole;
        this.connectionStrategy = connectionStrategy == null
                ? TenantConnectionStrategy.DIRECT_LOGIN
                : connectionStrategy;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return withTenantContext(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return withTenantContext(super.getConnection(username, password));
    }

    private Connection withTenantContext(Connection connection) throws SQLException {
        try {
            applyTenantContext(connection);
        } catch (SQLException | RuntimeException ex) {
            // Return the connection to the pool on failure; otherwise every
            // failed SET ROLE/set_config permanently drains the tenant pool.
            try {
                connection.close();
            } catch (SQLException closeException) {
                ex.addSuppressed(closeException);
            }
            throw ex;
        }
        return connection;
    }

    private void applyTenantContext(Connection connection) throws SQLException {
        if (connectionStrategy == TenantConnectionStrategy.ASSUME_ROLE) {
            requireValidTenantRole();
            try (var roleStatement = connection.createStatement()) {
                // Transaction-scoped: PgBouncer transaction mode reuses connections
                // across clients; a connect-time SET ROLE would leak identity.
                roleStatement.execute("SET LOCAL ROLE " + tenantRole);
            }
        }
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

    private void requireValidTenantRole() {
        if (tenantRole == null || tenantRole.isBlank()) {
            throw new IllegalStateException("Tenant role is required for ASSUME_ROLE connection strategy.");
        }
        if (!tenantRole.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalStateException("Invalid tenant role: " + tenantRole);
        }
    }
}
