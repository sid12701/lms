package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;

/**
 * Spec S10 — regression guards for Home/Audit Instant→JDBC binds on PostgreSQL.
 * These paths historically failed only under pgjdbc (H2 masked the bug).
 */
@SpringBootTest(properties = "app.rate-limit.enabled=false")
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class HomeAndAuditInstantBindPostgresTest extends PostgresDataJpaTestSupport {

    @Autowired
    private HomeDashboardService homeDashboardService;

    @Autowired
    private AuditExplorerService auditExplorerService;

    @Test
    void homeDashboardSummaryDoesNotFailOnPostgresInstantBinds() {
        assertThatCode(() -> homeDashboardService.getSummary()).doesNotThrowAnyException();
    }

    @Test
    void auditExplorerSearchAcceptsInstantRangeOnPostgres() {
        Instant until = Instant.now();
        Instant since = until.minus(7, ChronoUnit.DAYS);
        AuditExplorerQuery query = new AuditExplorerQuery(
                AuditExplorerQuery.ALL_STREAMS,
                null,
                null,
                null,
                null,
                null,
                since,
                until,
                null,
                null,
                25
        );
        assertThatCode(() -> auditExplorerService.search(query)).doesNotThrowAnyException();
    }
}
