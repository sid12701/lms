package com.bhawana.lms.repo;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhawana.lms.repo.LoanEventPartitionRepository.PartitionChange;
import com.bhawana.lms.repo.LoanEventPartitionRepository.PartitionChange.Action;
import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seam B — the loan event log's retention enforcing itself against a real Postgres.
 *
 * <p>Two claims cannot be made anywhere else: that a partition is present <em>before</em> the first
 * write that needs it, and that a partition past retention is gone as a metadata operation rather
 * than a large delete. Both are properties of the catalog, so both are asserted against it.
 *
 * <p>Every month boundary and event timestamp here is computed by the database, not the JVM.
 * Partition bounds follow {@code date_trunc('month', ...)} in the database's own timezone, so a test
 * that derived them from the host clock would drift at a month boundary whenever the two disagree.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(LoanEventPartitionRepository.class)
class LoanEventPartitionLifecyclePostgresTest extends PostgresDataJpaTestSupport {

    /**
     * The months this class creates and drops. All fall outside V116's own range of one month back
     * to three ahead, so resetting them between tests never disturbs the partitions the rest of the
     * suite writes into.
     */
    private static final List<Integer> WORKING_MONTH_OFFSETS = List.of(-6, 4, 5, 6, 7);

    private static final int LEAD_MONTHS = 6;
    private static final int RETENTION_DAYS = 30;

    /**
     * Marks the internal-history rows this class seeds so they can be removed again.
     * {@code loan_application_audit_event} has no {@code ON DELETE CASCADE}, so a row left behind
     * blocks the {@code DELETE FROM loan_application} that later Postgres tests clean up with.
     */
    private static final String INTERNAL_HISTORY_MARKER = "seeded by partition lifecycle test";

    @Autowired private LoanEventPartitionRepository loanEventPartitionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

    @Value("${app.datasource.tenant.username}")
    private String tenantUsername;

    @Value("${app.datasource.tenant.password}")
    private String tenantPassword;

    @BeforeEach
    void resetWorkingPartitions() {
        jdbcTemplate.execute("TRUNCATE TABLE loan_event");
        WORKING_MONTH_OFFSETS.forEach(offset -> jdbcTemplate.execute(
                "DROP TABLE IF EXISTS " + partitionNameForMonthOffset(offset)
        ));
    }

    @AfterEach
    void clearRowsSeededOutsideTheCurrentMonth() {
        jdbcTemplate.execute("TRUNCATE TABLE loan_event");
        jdbcTemplate.update(
                "DELETE FROM loan_application_audit_event WHERE note = ?", INTERNAL_HISTORY_MARKER
        );
        jdbcTemplate.update(
                "DELETE FROM loan_application_status_transition WHERE note = ?", INTERNAL_HISTORY_MARKER
        );
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void partitionForAFuturePeriodExistsBeforeWritesForItArrive() {
        String futurePartition = partitionNameForMonthOffset(LEAD_MONTHS);
        assertThat(existingPartitions()).doesNotContain(futurePartition);

        List<PartitionChange> changes = loanEventPartitionRepository.maintain(LEAD_MONTHS, RETENTION_DAYS);

        assertThat(changes).contains(new PartitionChange(Action.CREATED, futurePartition));
        assertThat(existingPartitions()).contains(futurePartition);

        UUID eventId = appendEventOccurringAt(timestampWithinMonthOffset(LEAD_MONTHS));
        assertThat(partitionHolding(eventId)).isEqualTo(futurePartition);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void everyMonthUpToTheLeadHorizonIsCovered() {
        loanEventPartitionRepository.maintain(LEAD_MONTHS, RETENTION_DAYS);

        List<String> partitions = existingPartitions();
        for (int monthOffset = 0; monthOffset <= LEAD_MONTHS; monthOffset++) {
            assertThat(partitions).contains(partitionNameForMonthOffset(monthOffset));
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void tenantIsolationHoldsOnAPartitionCreatedAtRuntime() throws Exception {
        // Until this ticket every partition came from a migration that also carried the grants and
        // policies. Partitions are created at runtime now, and this asserts what makes that safe:
        // privileges and row-level security are resolved on the parent, so a partition the tenant
        // role has no grant on is still both writable and confined through loan_event.
        loanEventPartitionRepository.maintain(LEAD_MONTHS, RETENTION_DAYS);
        UUID applicationId = seedLoanApplication();
        UUID owningLspId = lspOwning(applicationId);
        UUID otherLspId = lspOwning(seedLoanApplication());
        UUID eventId = UUID.randomUUID();

        try (Connection adminConnection = dataSource.getConnection();
             Connection tenantConnection = DriverManager.getConnection(
                     adminConnection.getMetaData().getURL(), tenantUsername, tenantPassword
             )) {
            tenantConnection.createStatement().execute("SET app.current_lsp_id = '" + owningLspId + "'");
            try (var insert = tenantConnection.prepareStatement(
                    "INSERT INTO loan_event (id, lsp_id, event_type, aggregate_type, aggregate_id, "
                            + "loan_application_id, payload_json, occurred_at) "
                            + "VALUES (?, ?, 'LOAN_CREATED', 'LOAN_APPLICATION', ?, ?, CAST(? AS jsonb), ?)"
            )) {
                insert.setObject(1, eventId);
                insert.setObject(2, owningLspId);
                insert.setString(3, applicationId.toString());
                insert.setObject(4, applicationId);
                insert.setString(5, "{}");
                insert.setTimestamp(6, timestampWithinMonthOffset(LEAD_MONTHS));
                insert.executeUpdate();
            }

            assertThat(partitionHolding(eventId)).isEqualTo(partitionNameForMonthOffset(LEAD_MONTHS));
            assertThat(tenantVisibleEventCount(tenantConnection)).isOne();

            tenantConnection.createStatement().execute("SET app.current_lsp_id = '" + otherLspId + "'");
            assertThat(tenantVisibleEventCount(tenantConnection)).isZero();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void partitionBeyondTheRetentionWindowIsGoneAfterTheDropRuns() {
        String expiredPartition = createPartitionForMonthOffset(-6);
        UUID expiredEventId = appendEventOccurringAt(timestampWithinMonthOffset(-6));
        assertThat(partitionHolding(expiredEventId)).isEqualTo(expiredPartition);

        List<PartitionChange> changes = loanEventPartitionRepository.maintain(LEAD_MONTHS, RETENTION_DAYS);

        assertThat(changes).contains(new PartitionChange(Action.DROPPED, expiredPartition));
        assertThat(existingPartitions()).doesNotContain(expiredPartition);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM loan_event WHERE id = ?", Long.class, expiredEventId
        )).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aPartitionStraddlingTheRetentionCutoffIsKept() {
        // Retention is 30 days but partitions are whole months, so the month holding the cutoff still
        // holds rows inside the window. Dropping it would cut retention short of what partners are told.
        String straddlingPartition = jdbcTemplate.queryForObject(
                "SELECT 'loan_event_' || to_char("
                        + "date_trunc('month', CURRENT_TIMESTAMP - make_interval(days => ?)), 'YYYY_MM')",
                String.class,
                RETENTION_DAYS
        );
        assertThat(existingPartitions()).contains(straddlingPartition);

        loanEventPartitionRepository.maintain(LEAD_MONTHS, RETENTION_DAYS);

        assertThat(existingPartitions()).contains(straddlingPartition);
        assertThat(existingPartitions()).contains(partitionNameForMonthOffset(0));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void runningTwiceNeitherFailsNorDropsTwice() {
        createPartitionForMonthOffset(-6);

        List<PartitionChange> firstRun = loanEventPartitionRepository.maintain(LEAD_MONTHS, RETENTION_DAYS);
        List<String> partitionsAfterFirstRun = existingPartitions();
        List<PartitionChange> secondRun = loanEventPartitionRepository.maintain(LEAD_MONTHS, RETENTION_DAYS);

        assertThat(firstRun).isNotEmpty();
        assertThat(secondRun).isEmpty();
        assertThat(existingPartitions()).isEqualTo(partitionsAfterFirstRun);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void retentionNeverTouchesInternalHistory() {
        UUID applicationId = seedLoanApplication();
        UUID transitionId = UUID.randomUUID();
        UUID auditEventId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO loan_application_status_transition "
                        + "(id, loan_application_id, from_status, to_status, actor_username, note, created_at) "
                        + "VALUES (?, ?, 'INITIALIZED', 'SUBMITTED', 'ops.admin', ?, "
                        + "CURRENT_TIMESTAMP - INTERVAL '400 days')",
                transitionId, applicationId, INTERNAL_HISTORY_MARKER
        );
        jdbcTemplate.update(
                "INSERT INTO loan_application_audit_event "
                        + "(id, loan_application_id, action, actor_username, from_status, to_status, note, created_at) "
                        + "VALUES (?, ?, 'SUBMIT', 'ops.admin', 'INITIALIZED', 'SUBMITTED', ?, "
                        + "CURRENT_TIMESTAMP - INTERVAL '400 days')",
                auditEventId, applicationId, INTERNAL_HISTORY_MARKER
        );

        loanEventPartitionRepository.maintain(LEAD_MONTHS, RETENTION_DAYS);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM loan_application_status_transition WHERE id = ?", Long.class, transitionId
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM loan_application_audit_event WHERE id = ?", Long.class, auditEventId
        )).isOne();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void oldestRetainedBoundTracksTheCatalogAndMovesForwardWhenTheOldestPartitionDrops() {
        // Month -6 is created here and is older than every partition V116 itself creates (-1..3), so
        // it becomes the oldest partition in the catalog without this test ever touching V116's own
        // partitions — which the cursor-expiry seam test elsewhere in the suite depends on staying put.
        createPartitionForMonthOffset(-6);
        Instant expiredMonthLowerBound = lowerBoundForMonthOffset(-6);
        assertThat(loanEventPartitionRepository.oldestRetainedBound()).contains(expiredMonthLowerBound);

        jdbcTemplate.execute("DROP TABLE " + partitionNameForMonthOffset(-6));

        // With -6 gone, the next oldest partition in the catalog is V116's own month -1 — proving the
        // bound is read live from the catalog on every call, not cached from the first read.
        Instant nextOldestLowerBound = lowerBoundForMonthOffset(-1);
        assertThat(loanEventPartitionRepository.oldestRetainedBound()).contains(nextOldestLowerBound);
    }

    private List<String> existingPartitions() {
        return jdbcTemplate.queryForList(
                """
                SELECT child.relname
                FROM pg_inherits
                JOIN pg_class child ON child.oid = pg_inherits.inhrelid
                WHERE pg_inherits.inhparent = 'loan_event'::regclass
                ORDER BY child.relname
                """,
                String.class
        );
    }

    private String partitionNameForMonthOffset(int monthOffset) {
        return jdbcTemplate.queryForObject(
                "SELECT 'loan_event_' || to_char("
                        + "date_trunc('month', CURRENT_TIMESTAMP) + make_interval(months => ?), 'YYYY_MM')",
                String.class,
                monthOffset
        );
    }

    private String createPartitionForMonthOffset(int monthOffset) {
        jdbcTemplate.execute(
                """
                DO $do$
                DECLARE
                    partition_start TIMESTAMPTZ :=
                        date_trunc('month', CURRENT_TIMESTAMP) + make_interval(months => %d);
                BEGIN
                    EXECUTE format(
                        'CREATE TABLE %%I PARTITION OF loan_event FOR VALUES FROM (%%L) TO (%%L)',
                        'loan_event_' || to_char(partition_start, 'YYYY_MM'),
                        partition_start,
                        partition_start + INTERVAL '1 month'
                    );
                END
                $do$
                """.formatted(monthOffset)
        );
        return partitionNameForMonthOffset(monthOffset);
    }

    private Instant lowerBoundForMonthOffset(int monthOffset) {
        return jdbcTemplate.queryForObject(
                "SELECT date_trunc('month', CURRENT_TIMESTAMP) + make_interval(months => ?)",
                Timestamp.class,
                monthOffset
        ).toInstant();
    }

    private Timestamp timestampWithinMonthOffset(int monthOffset) {
        return jdbcTemplate.queryForObject(
                "SELECT date_trunc('month', CURRENT_TIMESTAMP) + make_interval(months => ?) + INTERVAL '5 days'",
                Timestamp.class,
                monthOffset
        );
    }

    private String partitionHolding(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT tableoid::regclass::text FROM loan_event WHERE id = ?",
                String.class,
                eventId
        );
    }

    private static long tenantVisibleEventCount(Connection tenantConnection) throws SQLException {
        try (var result = tenantConnection.createStatement().executeQuery("SELECT COUNT(*) FROM loan_event")) {
            result.next();
            return result.getLong(1);
        }
    }

    private UUID lspOwning(UUID applicationId) {
        return jdbcTemplate.queryForObject(
                "SELECT lsp_id FROM loan_application WHERE id = ?", UUID.class, applicationId
        );
    }

    private UUID appendEventOccurringAt(Timestamp occurredAt) {
        UUID eventId = UUID.randomUUID();
        UUID applicationId = seedLoanApplication();
        UUID lspId = lspOwning(applicationId);
        jdbcTemplate.update(
                "INSERT INTO loan_event (id, lsp_id, event_type, aggregate_type, aggregate_id, "
                        + "loan_application_id, payload_json, occurred_at) "
                        + "VALUES (?, ?, 'LOAN_CREATED', 'LOAN_APPLICATION', ?, ?, CAST(? AS jsonb), ?)",
                eventId, lspId, applicationId.toString(), applicationId, "{}", occurredAt
        );
        return eventId;
    }

    private UUID seedLoanApplication() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID lspId = UUID.randomUUID();
        UUID borrowerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID productVersionId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO lsp (id, code, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                lspId, "PARTITION-" + suffix, "Partition Lifecycle LSP"
        );
        jdbcTemplate.update(
                "INSERT INTO borrower (id, full_name, pan, mobile) VALUES (?, ?, ?, ?)",
                borrowerId, "Borrower " + suffix, "P" + suffix.substring(0, 7), "9999999999"
        );
        jdbcTemplate.update(
                "INSERT INTO loan_product (id, code, name, min_principal, max_principal, interest_rate, "
                        + "processing_fee_rate, min_tenure_months, max_tenure_months) "
                        + "VALUES (?, ?, ?, 100.00, 100000.00, 10.00, 1.00, 6, 60)",
                productId, "PARTITION-PRODUCT-" + suffix, "Partition Product"
        );
        jdbcTemplate.update(
                "INSERT INTO loan_product_version (id, loan_product_id, version_number, min_principal, "
                        + "max_principal, interest_rate, processing_fee_rate, min_tenure_months, "
                        + "max_tenure_months, effective_from) "
                        + "VALUES (?, ?, 1, 100.00, 100000.00, 10.00, 1.00, 6, 60, NOW())",
                productVersionId, productId
        );
        jdbcTemplate.update(
                "INSERT INTO loan_application (id, borrower_id, lsp_id, loan_product_id, loan_product_version_id, "
                        + "external_loan_id, source_channel, requested_amount, tenure_months, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'API', 5000.00, 12, 'INITIALIZED')",
                applicationId, borrowerId, lspId, productId, productVersionId, "PARTITION-" + suffix
        );
        return applicationId;
    }
}
