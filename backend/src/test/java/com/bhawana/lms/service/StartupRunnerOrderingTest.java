package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.OrderUtils;

/**
 * ApplicationRunner execution order is bean-registration order unless declared, and
 * registration order varies across packaged-jar builds. These assertions pin the declared
 * ranks for the runners that share startup state so the ordering cannot regress silently.
 */
class StartupRunnerOrderingTest {

    @Test
    void roleCatalogRunsBeforeBootstrapAdminSync() {
        // The demo portfolio under LocalBootstrapAdminSyncService resolves the full role
        // catalog through UserAdminService.createUser; it must find app_role already seeded
        // or startup dies with ROLE_UNAVAILABLE.
        assertThat(OrderUtils.getOrder(RoleBootstrapService.class))
                .isLessThan(OrderUtils.getOrder(LocalBootstrapAdminSyncService.class));
    }

    @Test
    void sampleCatalogRunsAfterDemoPortfolioReset() {
        // The demo portfolio reset truncates lsp/loan_product on a cold boot; a sample
        // catalog seeded before it would be discarded before the application reports ready.
        assertThat(OrderUtils.getOrder(SampleCatalogSeedService.class))
                .isGreaterThan(OrderUtils.getOrder(LocalBootstrapAdminSyncService.class));
    }
}
