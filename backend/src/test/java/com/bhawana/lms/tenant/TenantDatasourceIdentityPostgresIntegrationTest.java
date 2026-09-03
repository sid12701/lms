package com.bhawana.lms.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;

/**
 * C13: tenant and admin pools must be distinct physical datasources on PostgreSQL so tenant RLS
 * bindings are not silently bypassed by routing every connection through the admin pool.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class TenantDatasourceIdentityPostgresIntegrationTest extends PostgresDataJpaTestSupport {

    @Autowired
    @Qualifier("adminDataSource")
    private DataSource adminDataSource;

    @Autowired
    @Qualifier("tenantPhysicalDataSource")
    private DataSource tenantPhysicalDataSource;

    @Test
    void tenantAndAdminDatasourcesAreDifferentObjectsOnPostgres() {
        assertThat(tenantPhysicalDataSource).isNotSameAs(adminDataSource);
    }
}
