package com.bhawana.lms.tenant;

import com.bhawana.lms.config.DeploymentProfiles;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TenantDatasourceSecurityValidator implements ApplicationRunner {

    static final String LEGACY_DEFAULT_PASSWORD = "lms_tenant_app_password";

    private final TenantAwareDataSourceProperties tenantProperties;
    private final DataSourceProperties dataSourceProperties;
    private final DataSource routingDataSource;
    private final Environment environment;

    public TenantDatasourceSecurityValidator(
            TenantAwareDataSourceProperties tenantProperties,
            DataSourceProperties dataSourceProperties,
            @Qualifier("dataSource") DataSource routingDataSource,
            Environment environment
    ) {
        this.tenantProperties = tenantProperties;
        this.dataSourceProperties = dataSourceProperties;
        this.routingDataSource = routingDataSource;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Exemptions require a nonempty explicitly active all-local/test profile set.
        // Unset, misspelled, production-like and mixed (e.g. local+prod) profiles all validate.
        if (DeploymentProfiles.isDevExempt(environment)) {
            return;
        }

        validateTenantCredentials(tenantProperties.getUsername(), tenantProperties.getPassword());
        assertTenantConnectionIdentity();
    }

    /**
     * Fail-fast credential rules shared by startup validation and unit tests. The live connection
     * identity probe in {@link #assertTenantConnectionIdentity()} runs separately at startup.
     */
    static void validateTenantCredentials(String username, String password) {
        if (!StringUtils.hasText(username)) {
            throw new IllegalStateException(
                    "APP_TENANT_DATASOURCE_USERNAME is required outside the local profile."
            );
        }
        if (!StringUtils.hasText(password)) {
            throw new IllegalStateException(
                    "APP_TENANT_DATASOURCE_PASSWORD is required outside the local profile. "
                            + "Set a non-default tenant password in the secret store and rotate the "
                            + "lms_tenant_app role before deploying."
            );
        }
        if (LEGACY_DEFAULT_PASSWORD.equals(password)) {
            throw new IllegalStateException(
                    "Tenant datasource password is still the legacy default (lms_tenant_app_password). "
                            + "Rotate APP_TENANT_DATASOURCE_PASSWORD and ALTER ROLE lms_tenant_app PASSWORD "
                            + "on each environment before deploying."
            );
        }
    }

    private void assertTenantConnectionIdentity() {
        String jdbcUrl = dataSourceProperties.getUrl();
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql:")) {
            return;
        }

        String expectedRole = tenantProperties.getUsername();
        if (!StringUtils.hasText(expectedRole)) {
            throw new IllegalStateException(
                    "APP_TENANT_DATASOURCE_USERNAME is required outside the local profile."
            );
        }

        UUID probeLspId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        TenantAccessContext previous = TenantDataAccessContextHolder.snapshot();
        TenantDataAccessContextHolder.useTenant(probeLspId);
        try (Connection connection = routingDataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String currentUser = queryCurrentUser(connection);
                if (!expectedRole.equals(currentUser)) {
                    throw new IllegalStateException(
                            "Tenant datasource is not running as the restricted tenant role. "
                                    + "Expected current_user="
                                    + expectedRole
                                    + " but got "
                                    + currentUser
                                    + ". Check app.datasource.tenant.connection-strategy and credentials."
                    );
                }
            } finally {
                connection.rollback();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Unable to verify tenant datasource role identity at startup.",
                    exception
            );
        } finally {
            TenantDataAccessContextHolder.restore(previous);
        }
    }

    private static String queryCurrentUser(Connection connection) throws SQLException {
        try (ResultSet resultSet = connection.createStatement().executeQuery("select current_user")) {
            if (!resultSet.next()) {
                throw new IllegalStateException("Tenant role identity probe returned no rows.");
            }
            return resultSet.getString(1);
        }
    }
}
