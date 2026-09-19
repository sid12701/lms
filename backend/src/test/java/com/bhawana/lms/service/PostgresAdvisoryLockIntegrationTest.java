package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.bhawana.lms.repo.PortfolioKpiSnapshotRepository;
import com.bhawana.lms.repo.WorkerLeaseRepository;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * H12 regression tests: the scheduled-job advisory lock must live on the same connection and
 * transaction as the work it protects. These tests run real second connections through the pool
 * so a session-level acquire/release split — the original defect — fails them, not just a code
 * review.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class PostgresAdvisoryLockIntegrationTest {

    // Fits in objid's 32-bit range so the leak query can match classid = 0, objid = LOCK_ID.
    private static final long LOCK_ID = 910_042_003L;
    private static final String JOB_NAME = "h12_test_job";

    @Autowired
    private PostgresAdvisoryLockSupport advisoryLockSupport;

    @Autowired
    private PortfolioKpiSnapshotWorker portfolioKpiSnapshotWorker;

    @Autowired
    private AlertRuleSchedulerWorker alertRuleSchedulerWorker;

    @Autowired
    private PortfolioKpiProperties portfolioKpiProperties;

    @Autowired
    private PortfolioKpiSnapshotRepository portfolioKpiSnapshotRepository;

    @Autowired
    private WorkerLeaseRepository workerLeaseRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void tearDown() {
        TenantDataAccessContextHolder.clear();
    }

    @Test
    void secondConnectionCannotEnterWhileFirstHoldsTheLock() throws Exception {
        CountDownLatch insideWork = new CountDownLatch(1);
        CountDownLatch finishWork = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<String>> first = executor.submit(() ->
                    TenantScopedExecution.callAsAdmin(() ->
                            advisoryLockSupport.runWithAdvisoryLock(LOCK_ID, JOB_NAME, () -> {
                                insideWork.countDown();
                                await(finishWork);
                                return "first-run";
                            })));

            if (!insideWork.await(30, TimeUnit.SECONDS)) {
                // Surface the task's real exception instead of a bare latch timeout.
                first.get(5, TimeUnit.SECONDS);
                fail("first run never entered the lock");
            }

            // A second pooled connection must be turned away, not queue or acquire reentrantly.
            Optional<String> second = advisoryLockSupport.runWithAdvisoryLock(
                    LOCK_ID, JOB_NAME, () -> "second-run");
            assertTrue(second.isEmpty(), "second connection entered while the lock was held");

            finishWork.countDown();
            assertEquals(Optional.of("first-run"), first.get(10, TimeUnit.SECONDS));

            // Once the first transaction commits the lock is free for the next run.
            assertEquals(
                    Optional.of("after-release"),
                    advisoryLockSupport.runWithAdvisoryLock(LOCK_ID, JOB_NAME, () -> "after-release"));
        } finally {
            finishWork.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void failedRunReleasesTheLockAndTheNextRunSucceeds() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                advisoryLockSupport.runWithAdvisoryLock(LOCK_ID, JOB_NAME, () -> {
                    throw new IllegalStateException("boom");
                }));
        assertEquals("boom", failure.getMessage());

        assertEquals(
                Optional.of("recovered"),
                advisoryLockSupport.runWithAdvisoryLock(LOCK_ID, JOB_NAME, () -> "recovered"));
    }

    @Test
    void noAdvisoryLockIsLeakedBackToThePool() {
        advisoryLockSupport.runWithAdvisoryLock(LOCK_ID, JOB_NAME, () -> "done");
        assertThrows(IllegalStateException.class, () ->
                advisoryLockSupport.runWithAdvisoryLock(LOCK_ID, JOB_NAME, () -> {
                    throw new IllegalStateException("boom");
                }));

        Integer lingering = jdbcTemplate.queryForObject(
                "select count(*) from pg_locks where locktype = 'advisory'"
                        + " and classid = cast(? as oid) and objid = cast(? as oid)",
                Integer.class,
                LOCK_ID >> 32,
                LOCK_ID & 0xffffffffL);
        assertEquals(0, lingering, "advisory lock survived its transaction and leaked to the pool");
    }

    @Test
    void kpiWorkerSkipsWhileAnotherSessionHoldsItsLock() {
        long lockId = portfolioKpiProperties.getAdvisoryLockId();
        long[] snapshotsWhileLocked = new long[1];
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            holdLockOnThisTransaction(lockId);
            snapshotsWhileLocked[0] = snapshotCount();
            TenantScopedExecution.runAsAdmin(portfolioKpiSnapshotWorker::refreshSnapshotsUnderAdminScope);
            assertEquals(snapshotsWhileLocked[0], snapshotCount(), "worker ran its job while the lock was held");
        });

        TenantScopedExecution.runAsAdmin(portfolioKpiSnapshotWorker::refreshSnapshotsUnderAdminScope);
        assertTrue(
                snapshotCount() > snapshotsWhileLocked[0],
                "worker did not write snapshots after the lock was released");
    }

    @Test
    void alertRuleWorkerSkipsWhileAnotherWorkerHoldsItsLease() {
        // M07: the evaluator's singleton exclusion is a durable worker lease (multi-transaction
        // job), not the advisory lock — hold it from a contending owner and the run skips.
        OptionalLong contender = workerLeaseRepository.tryClaim(
                AlertRuleEvaluationWorker.EVALUATION_LEASE_JOB,
                "contender-owner",
                Instant.now().plusSeconds(300));
        assertTrue(contender.isPresent(), "test could not claim the evaluation lease");

        try {
            Instant evaluatedBefore = latestRuleEvaluation();
            TenantScopedExecution.runAsAdmin(alertRuleSchedulerWorker::evaluateScheduledAlertRulesUnderAdminScope);
            assertEquals(evaluatedBefore, latestRuleEvaluation(),
                    "worker evaluated rules while the lease was held");
        } finally {
            workerLeaseRepository.release(
                    AlertRuleEvaluationWorker.EVALUATION_LEASE_JOB, "contender-owner", contender.getAsLong());
        }

        Instant evaluatedBefore = latestRuleEvaluation();
        TenantScopedExecution.runAsAdmin(alertRuleSchedulerWorker::evaluateScheduledAlertRulesUnderAdminScope);
        Instant evaluatedAfter = latestRuleEvaluation();
        assertTrue(
                evaluatedAfter != null && (evaluatedBefore == null || evaluatedAfter.isAfter(evaluatedBefore)),
                "worker did not evaluate rules after the lease was released");
    }

    /** Transaction-scoped advisory lock on the calling transaction — the contender the worker must lose to. */
    private void holdLockOnThisTransaction(long lockId) {
        Boolean held = jdbcTemplate.queryForObject(
                "select pg_try_advisory_xact_lock(?)", Boolean.class, lockId);
        assertTrue(Boolean.TRUE.equals(held), "test could not take the advisory lock on its own transaction");
    }

    private long snapshotCount() {
        return TenantScopedExecution.callAsAdmin(portfolioKpiSnapshotRepository::count);
    }

    private Instant latestRuleEvaluation() {
        Timestamp latest = TenantScopedExecution.callAsAdmin(() ->
                jdbcTemplate.queryForObject("select max(last_evaluated_at) from alert_rule", Timestamp.class));
        return latest == null ? null : latest.toInstant();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting inside the locked section");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
