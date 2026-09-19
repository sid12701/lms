package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * H27 on a real scheduled entry point: an enabled disbursement tick records a successful
 * {@code lms.job.runs} outcome and a throwing tick records a failure and propagates, while
 * the pooled scheduler thread is left without correlation context either way.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // Worker stays disabled at context start so scheduled ticks no-op instantly;
        // tests enable it on the properties bean for the direct run() call only.
        // Delays stay pinned regardless: even if a tick somehow lands mid-test while the
        // flag is flipped on, it cannot fire — the first tick is hours out either way.
        "app.disbursement.worker.fixed-delay-ms=3600000",
        "app.disbursement.worker.status-check-delay-ms=3600000",
        "app.disbursement.worker.reconciliation-delay-ms=3600000"
})
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class WorkerJobObservabilityIntegrationTest {

    @Autowired
    private LoanDisbursementWorker loanDisbursementWorker;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @Autowired
    private LoanDisbursementWorkerProperties loanDisbursementWorkerProperties;

    @MockitoSpyBean
    private LoanDisbursementWorkerService loanDisbursementWorkerService;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
        loanDisbursementWorkerProperties.setEnabled(true);
    }

    @AfterEach
    void tearDown() {
        loanDisbursementWorkerProperties.setEnabled(false);
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    @Test
    void enabledTickRecordsASuccessRunAndLeavesNoContext() {
        double before = runCount("success");

        loanDisbursementWorker.run();

        assertThat(runCount("success")).isEqualTo(before + 1);
        assertThat(meterRegistry.get("lms.job.run.duration")
                .tag("job", "disbursement-pending").timer().count())
                .isGreaterThanOrEqualTo(1L);
        assertThat(meterRegistry.get("lms.job.last_success_epoch_seconds")
                .tag("job", "disbursement-pending").gauge().value())
                .isPositive();
        assertThat(CorrelationIdHolder.get()).isNull();
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void failingTickRecordsAFailureRunAndPropagates() {
        doThrow(new IllegalStateException("forced worker failure"))
                .when(loanDisbursementWorkerService)
                .processPendingDisbursements();
        double before = runCount("failure");

        assertThatThrownBy(loanDisbursementWorker::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("forced worker failure");

        assertThat(runCount("failure")).isEqualTo(before + 1);
        assertThat(CorrelationIdHolder.get()).isNull();
        assertThat(MDC.get("correlationId")).isNull();

        reset(loanDisbursementWorkerService);
    }

    private double runCount(String outcome) {
        var counter = meterRegistry.find("lms.job.runs")
                .tags("job", "disbursement-pending", "outcome", outcome)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }
}
