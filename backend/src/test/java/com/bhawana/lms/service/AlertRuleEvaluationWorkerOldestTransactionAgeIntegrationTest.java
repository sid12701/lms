package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhawana.lms.domain.OpsAlert;
import com.bhawana.lms.domain.OpsAlertStatus;
import com.bhawana.lms.repo.OpsAlertRepository;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.SharedPostgresTestContainer;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;

/**
 * The condition this rule watches — the age of the oldest open transaction across the whole
 * Postgres cluster — is a property of the database's own session state, not of anything reachable
 * through the app's HTTP surface, so unlike the feed-facing tickets in this series this is driven
 * directly against {@link AlertRuleEvaluationWorker#evaluateScheduledRules()} with a second real
 * database connection holding a transaction open beside it.
 *
 * The held connection is a raw {@link DriverManager} connection, not one borrowed from the app's
 * pool: it is the honest simulation of the ADR 0007 failure mode (a {@code pg_dump}, a leaked
 * connection, a stuck analytics query — something outside the app's own pool entirely), and it
 * avoids starving the 5-connection Hikari pool that {@code evaluateScheduledRules()} itself needs
 * two connections from.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class AlertRuleEvaluationWorkerOldestTransactionAgeIntegrationTest {

    private static final String OLDEST_TRANSACTION_AGE_TYPE = "OLDEST_TRANSACTION_AGE";
    private static final String HELD_CONNECTION_APPLICATION_NAME = "lms-it-oldest-open-txn";

    @Autowired
    private AlertRuleEvaluationWorker alertRuleEvaluationWorker;

    @Autowired
    private OpsAlertRepository opsAlertRepository;

    @Autowired
    private AlertRuleProperties alertRuleProperties;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    private Connection heldConnection;
    private int originalThresholdSeconds;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
        originalThresholdSeconds = alertRuleProperties.getOldestTransactionAgeSeconds();
    }

    @AfterEach
    void tearDown() throws SQLException {
        // A leaked open transaction here would stall the xmin horizon for every later test in the
        // JVM (including the feed tests), so this is not optional even when an assertion above
        // already failed.
        if (heldConnection != null) {
            heldConnection.rollback();
            heldConnection.close();
            heldConnection = null;
        }
        alertRuleProperties.setOldestTransactionAgeSeconds(originalThresholdSeconds);
    }

    @Test
    void oldestTransactionAgeAlertFiresAboveThresholdAndClearsOnceTransactionCompletes() throws Exception {
        long backendPid = openHeldTransaction();

        // Phase 1: below the threshold, nothing fires. This is what proves the threshold is a
        // real gate rather than decoration -- without it, phase 2 alone would pass for a
        // hard-coded rule too.
        alertRuleProperties.setOldestTransactionAgeSeconds(3600);
        alertRuleEvaluationWorker.evaluateScheduledRules();
        assertThat(countNewOldestTransactionAgeAlerts()).isZero();

        // Phase 2 (and 3): crossing the threshold fires, and the threshold is exactly the
        // configured value -- this differs from phase 1 only in the property.
        alertRuleProperties.setOldestTransactionAgeSeconds(1);
        Thread.sleep(1200);
        alertRuleEvaluationWorker.evaluateScheduledRules();

        List<OpsAlert> fired = findNewOldestTransactionAgeAlerts();
        assertThat(fired).hasSize(1);
        OpsAlert alert = fired.get(0);
        // The identity assertion: this is the only thing stopping a stray open transaction from
        // another cached Spring context making this test pass for the wrong reason.
        assertThat(alert.getContextJson()).contains("\"pid\":" + backendPid);
        assertThat(alert.getContextJson()).contains("\"applicationName\":\"" + HELD_CONNECTION_APPLICATION_NAME + "\"");
        assertThat(alert.getMessage()).contains("no new events reach any LSP");

        // Phase 4: it clears once the transaction completes. createAlertIfAbsent suppresses a
        // second alert while the first is still NEW, so the alert must be acknowledged before
        // re-evaluating -- otherwise this assertion would pass identically whether the rule had
        // actually stopped firing or was still firing and being deduped.
        closeHeldTransaction();
        acknowledge(alert);

        alertRuleEvaluationWorker.evaluateScheduledRules();
        assertThat(countNewOldestTransactionAgeAlerts()).isZero();
    }

    private long openHeldTransaction() throws SQLException {
        heldConnection = DriverManager.getConnection(
                SharedPostgresTestContainer.jdbcUrl(),
                SharedPostgresTestContainer.username(),
                SharedPostgresTestContainer.password());
        heldConnection.setAutoCommit(false);
        try (Statement statement = heldConnection.createStatement()) {
            statement.execute("set application_name = '" + HELD_CONNECTION_APPLICATION_NAME + "'");
            // Forces a real XID assignment: this both guarantees xact_start is set and makes the
            // held transaction genuinely hold back pg_snapshot_xmin(pg_current_snapshot()), which
            // is the actual stall ADR 0007 describes, not merely an idle session.
            statement.execute("select pg_current_xact_id()");
        }
        try (Statement statement = heldConnection.createStatement();
                ResultSet resultSet = statement.executeQuery("select pg_backend_pid()")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private void closeHeldTransaction() throws SQLException {
        if (heldConnection != null) {
            heldConnection.commit();
            heldConnection.close();
            heldConnection = null;
        }
    }

    private void acknowledge(OpsAlert alert) {
        TenantScopedExecution.runAsAdmin(() -> {
            OpsAlert managed = opsAlertRepository.findById(alert.getId()).orElseThrow();
            managed.acknowledge("ops.user", "Reviewed for test");
            opsAlertRepository.save(managed);
        });
    }

    private long countNewOldestTransactionAgeAlerts() {
        return findNewOldestTransactionAgeAlerts().size();
    }

    // The type is compared as a string rather than through the OpsAlertType enum constant: at red
    // time OpsAlertType.OLDEST_TRANSACTION_AGE does not exist yet, and this keeps the failure a
    // real assertion failure rather than a compile error, same technique as issue 08.
    private List<OpsAlert> findNewOldestTransactionAgeAlerts() {
        return TenantScopedExecution.callAsAdmin(() -> opsAlertRepository.findAll().stream()
                .filter(a -> a.getType().name().equals(OLDEST_TRANSACTION_AGE_TYPE))
                .filter(a -> a.getStatus() == OpsAlertStatus.NEW)
                .toList());
    }
}
