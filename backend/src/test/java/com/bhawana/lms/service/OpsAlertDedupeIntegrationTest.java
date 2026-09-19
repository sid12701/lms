package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhawana.lms.domain.OpsAlert;
import com.bhawana.lms.domain.OpsAlertSeverity;
import com.bhawana.lms.domain.OpsAlertStatus;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.repo.OpsAlertRepository;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M08 acceptance: alert deduplication is a canonical key enforced at the database. A second
 * emitter for the same key must not create a second active alert — even when its existence
 * check passed while the first insert was still uncommitted (the check-then-insert race the
 * partial unique indexes now close). Acknowledgement frees the key so recurrence files a new
 * alert, and different subject types or subjects never merge.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class OpsAlertDedupeIntegrationTest {

    @Autowired
    private OpsAlertService opsAlertService;

    @Autowired
    private OpsAlertRepository opsAlertRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    @Test
    void concurrentEmissionsOfTheSameConditionCreateOneActiveAlert() throws Exception {
        UUID subjectId = UUID.randomUUID();
        CountDownLatch firstInsertCommitted = new CountDownLatch(1);
        CountDownLatch winnerInsertedUncommitted = new CountDownLatch(1);

        // The contender holds an UNCOMMITTED duplicate row on a second connection: the
        // service's existence check cannot see it, so the service must rely on the unique
        // index — its insert blocks on speculative insertion until the contender commits,
        // then loses as a constraint violation and is treated as "alert already exists".
        Future<?> contender = executor.submit(() ->
                TenantScopedExecution.runAsAdmin(() ->
                        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                            insertRawAlert(subjectId);
                            winnerInsertedUncommitted.countDown();
                            awaitLatch(firstInsertCommitted);
                        })));

        assertThat(winnerInsertedUncommitted.await(15, TimeUnit.SECONDS)).isTrue();

        Future<OpsAlert> emitted = executor.submit(() -> createSubjectAlert(subjectId));
        // The service insert is blocked behind the contender's uncommitted row; releasing the
        // contender lets it fail with the unique violation instead of writing a second row.
        firstInsertCommitted.countDown();
        contender.get(15, TimeUnit.SECONDS);

        assertThat(emitted.get(15, TimeUnit.SECONDS)).isNull();
        assertThat(activeAlertCount(subjectId)).isEqualTo(1);
    }

    @Test
    void nullSubjectAlertsDedupeOnTypeAndCorrelationId() {
        OpsAlert first = opsAlertService.createAlertIfAbsent(
                OpsAlertType.RATE_LIMIT_BREACH,
                OpsAlertSeverity.HIGH,
                "Rate limit breached",
                "Bucket exceeded.",
                "SYSTEM",
                null,
                "rate-limit:test",
                null
        );
        OpsAlert second = opsAlertService.createAlertIfAbsent(
                OpsAlertType.RATE_LIMIT_BREACH,
                OpsAlertSeverity.HIGH,
                "Rate limit breached",
                "Bucket exceeded again.",
                "SYSTEM",
                null,
                "rate-limit:test",
                null
        );
        OpsAlert differentKey = opsAlertService.createAlertIfAbsent(
                OpsAlertType.RATE_LIMIT_BREACH,
                OpsAlertSeverity.HIGH,
                "Rate limit breached",
                "A genuinely different condition.",
                "SYSTEM",
                null,
                "rate-limit:other",
                null
        );

        assertThat(first).isNotNull();
        assertThat(second).isNull();
        assertThat(differentKey).isNotNull();
        assertThat(activeAlerts()).hasSize(2);
    }

    @Test
    void acknowledgedAlertFreesTheKeySoRecurrenceFilesANewAlert() {
        UUID subjectId = UUID.randomUUID();
        OpsAlert first = createSubjectAlert(subjectId);
        TenantScopedExecution.runAsAdmin(() -> {
            OpsAlert alert = opsAlertRepository.findById(first.getId()).orElseThrow();
            alert.acknowledge("ops.user", "Reviewed");
            opsAlertRepository.save(alert);
        });

        OpsAlert recurrence = createSubjectAlert(subjectId);

        assertThat(recurrence).isNotNull();
        assertThat(recurrence.getId()).isNotEqualTo(first.getId());
        assertThat(activeAlertCount(subjectId)).isEqualTo(1);
    }

    @Test
    void distinctSubjectsAndSubjectTypesDoNotMerge() {
        UUID subjectId = UUID.randomUUID();

        OpsAlert loanAlert = createSubjectAlert(subjectId);
        OpsAlert sameIdDifferentType = opsAlertService.createAlertIfAbsent(
                OpsAlertType.DPD_BUCKET_TRANSITION,
                OpsAlertSeverity.HIGH,
                "Different subject type",
                "Same subject id under a different subject type must not dedupe.",
                "LOAN_ACCOUNT",
                subjectId,
                "corr-b",
                null
        );
        OpsAlert otherSubject = opsAlertService.createAlertIfAbsent(
                OpsAlertType.DPD_BUCKET_TRANSITION,
                OpsAlertSeverity.HIGH,
                "Different subject",
                "A different loan must file its own alert.",
                "LOAN_APPLICATION",
                UUID.randomUUID(),
                "corr-b",
                null
        );

        assertThat(loanAlert).isNotNull();
        assertThat(sameIdDifferentType).isNotNull();
        assertThat(otherSubject).isNotNull();
        assertThat(activeAlerts()).hasSize(3);
    }

    @Test
    void alwaysCreateAlertsBypassTheDedupeFence() {
        // V134: occurrence-recording emitters (LSP bound violations, manual escalations,
        // onboarding conflicts) intentionally file one alert per occurrence — the dedupe
        // fence must not swallow or reject their repeats for the same subject.
        UUID subjectId = UUID.randomUUID();

        OpsAlert first = opsAlertService.createAlert(
                OpsAlertType.LSP_BOUND_VIOLATION,
                OpsAlertSeverity.HIGH,
                "LSP bound violation: FORECLOSURE_QUOTE_DATE_INVALID",
                "First occurrence.",
                "LOAN_APPLICATION",
                subjectId,
                "corr-occurrence-1",
                null
        );
        OpsAlert second = opsAlertService.createAlert(
                OpsAlertType.LSP_BOUND_VIOLATION,
                OpsAlertSeverity.HIGH,
                "LSP bound violation: SETTLEMENT_DATE_MISMATCH",
                "Second occurrence for the same application must file its own alert.",
                "LOAN_APPLICATION",
                subjectId,
                "corr-occurrence-2",
                null
        );

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(activeAlertCount(subjectId)).isEqualTo(2);
    }

    private OpsAlert createSubjectAlert(UUID subjectId) {
        return opsAlertService.createAlertIfAbsent(
                OpsAlertType.DPD_BUCKET_TRANSITION,
                OpsAlertSeverity.HIGH,
                "Delinquency bucket DPD_1_30",
                "Loan is past due.",
                "LOAN_APPLICATION",
                subjectId,
                "corr-a",
                null
        );
    }

    private void insertRawAlert(UUID subjectId) {
        jdbcTemplate.update(
                """
                INSERT INTO ops_alert
                    (id, type, severity, status, title, message, subject_type, subject_id,
                     correlation_id, context_json, created_at, dedupe_protected)
                VALUES (?, ?, ?, 'NEW', ?, ?, ?, ?, ?, NULL, ?, true)
                """,
                UUID.randomUUID(),
                OpsAlertType.DPD_BUCKET_TRANSITION.name(),
                OpsAlertSeverity.HIGH.name(),
                "Delinquency bucket DPD_1_30",
                "Contender row still uncommitted.",
                "LOAN_APPLICATION",
                subjectId,
                "corr-a",
                Timestamp.from(Instant.now())
        );
    }

    private long activeAlertCount(UUID subjectId) {
        return activeAlerts().stream()
                .filter(alert -> subjectId.equals(alert.getSubjectId()))
                .count();
    }

    private List<OpsAlert> activeAlerts() {
        return TenantScopedExecution.callAsAdmin(() -> opsAlertRepository.findAll().stream()
                .filter(alert -> alert.getStatus() == OpsAlertStatus.NEW)
                .toList());
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding the uncommitted alert.", interrupted);
        }
    }
}
