package com.bhawana.lms.support;

import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Postgres + Flyway integration-test support. Datasource properties are bound globally by
 * {@link PostgresTestContextCustomizerFactory}; subclasses only need tenant-context hygiene.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class PostgresDataJpaTestSupport {

    @BeforeEach
    void bindAdminTenantContextForJpaTests() {
        TenantDataAccessContextHolder.useAdmin();
    }

    @AfterEach
    void clearTenantContextAfterJpaTest() {
        TenantDataAccessContextHolder.clear();
    }
}
