package com.bhawana.lms.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("test")
class TenantConnectionStrategyPostgresIntegrationTest extends PostgresDataJpaTestSupport {

    @Autowired
    @Qualifier("dataSource")
    private DataSource routingDataSource;

    @DynamicPropertySource
    static void assumeRoleStrategy(DynamicPropertyRegistry registry) {
        registry.add("app.datasource.tenant.connection-strategy", () -> TenantConnectionStrategy.ASSUME_ROLE.name());
    }

    @Test
    void assumeRoleStrategyRunsTenantQueriesAsTenantRole() throws Exception {
        UUID lspId = UUID.randomUUID();
        TenantDataAccessContextHolder.useTenant(lspId);
        try (Connection connection = routingDataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (ResultSet resultSet = connection.createStatement().executeQuery("select current_user")) {
                resultSet.next();
                assertEquals("lms_tenant_app", resultSet.getString(1));
            } finally {
                connection.rollback();
            }
        } finally {
            TenantDataAccessContextHolder.clear();
        }
    }
}
