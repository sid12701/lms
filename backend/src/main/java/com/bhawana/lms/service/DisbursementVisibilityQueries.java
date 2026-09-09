package com.bhawana.lms.service;

import com.bhawana.lms.tenant.TenantScopedExecution;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * H27 — bounded aggregate reads behind the disbursement visibility gauges. Each method issues a
 * single aggregate SQL statement (counts plus the oldest stable creation timestamp); no entities
 * are loaded and no borrower/bank identifiers leave the database.
 *
 * <p>Buckets reuse only authoritative states already owned by the disbursement lifecycle:
 * <ul>
 *   <li>pending — live {@code CREATED}/{@code REQUESTED} intents (normal pipeline);</li>
 *   <li>unknown — {@code UNKNOWN} intents awaiting reconciliation;</li>
 *   <li>unapplied — terminal {@code SUCCEEDED}/{@code FAILED} intent still joined to a
 *       {@code DISBURSEMENT_REQUESTED} account (the C02 repair backlog);</li>
 *   <li>parked without intent — {@code DISBURSEMENT_REQUESTED}/
 *       {@code DISBURSEMENT_PENDING_RECONCILIATION} accounts with no live intent (parked loans
 *       and legacy mismatches, including pre-intent rows). Terminal intents
 *       ({@code SUCCEEDED}/{@code FAILED}/{@code CANCELLED}) never hold the live slot and are
 *       otherwise ignored.</li>
 * </ul>
 * Ages come from the stable {@code created_at} timestamp (intent creation, account creation),
 * never from {@code updated_at}, so polls/retries cannot reset the clock. This component defines
 * no queue semantics of its own; the H02 reconciliation queue, when it lands, remains the owner
 * of recovery selection.
 */
@Component
public class DisbursementVisibilityQueries {

    private final JdbcTemplate jdbcTemplate;

    public DisbursementVisibilityQueries(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Count plus oldest creation timestamp for one bucket; empty when nothing matches. */
    public record BucketSnapshot(long count, Instant oldestCreatedAt) {
        static BucketSnapshot empty() {
            return new BucketSnapshot(0, null);
        }
    }

    /** Live pipeline: intents created and submitted, awaiting execution or a terminal result. */
    public BucketSnapshot pendingIntents() {
        return TenantScopedExecution.callAsAdmin(() -> singleStateBucket("CREATED", "REQUESTED"));
    }

    /** Intents parked in uncertainty, awaiting reconciliation against the original reference. */
    public BucketSnapshot unknownIntents() {
        return TenantScopedExecution.callAsAdmin(() -> singleStateBucket("UNKNOWN"));
    }

    /** Recorded terminal evidence whose loan move is still missing (C02 repair backlog). */
    public BucketSnapshot unappliedTerminalIntents() {
        return TenantScopedExecution.callAsAdmin(() -> jdbcTemplate.query(
                """
                        SELECT COUNT(*) AS count,
                               MIN(i.created_at) AS oldest_created_at
                        FROM disbursement_intent i
                        JOIN loan_account a ON a.id = i.loan_account_id
                        WHERE i.state IN ('SUCCEEDED', 'FAILED')
                          AND a.status = 'DISBURSEMENT_REQUESTED'
                        """,
                resultSet -> {
                    resultSet.next();
                    return toSnapshot(resultSet.getLong("count"), resultSet.getTimestamp("oldest_created_at"));
                }));
    }

    /**
     * Requested/parked accounts with no live intent: manual-reconciliation parking and legacy
     * rows (including pre-intent logs) that normal polling no longer selects. Age is the stable
     * account creation time.
     */
    public BucketSnapshot parkedAccountsWithoutLiveIntent() {
        return TenantScopedExecution.callAsAdmin(() -> jdbcTemplate.query(
                """
                        SELECT COUNT(*) AS count,
                               MIN(a.created_at) AS oldest_created_at
                        FROM loan_account a
                        WHERE a.status IN ('DISBURSEMENT_REQUESTED', 'DISBURSEMENT_PENDING_RECONCILIATION')
                          AND NOT EXISTS (
                              SELECT 1
                              FROM disbursement_intent i
                              WHERE i.loan_account_id = a.id
                                AND i.state NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                          )
                        """,
                resultSet -> {
                    resultSet.next();
                    return toSnapshot(resultSet.getLong("count"), resultSet.getTimestamp("oldest_created_at"));
                }));
    }

    private BucketSnapshot singleStateBucket(String... states) {
        String placeholders = String.join(",", java.util.Collections.nCopies(states.length, "?"));
        List<BucketSnapshot> rows = jdbcTemplate.query(
                "SELECT COUNT(*) AS count, MIN(created_at) AS oldest_created_at"
                        + " FROM disbursement_intent WHERE state IN (" + placeholders + ")",
                (resultSet, rowNum) ->
                        toSnapshot(resultSet.getLong("count"), resultSet.getTimestamp("oldest_created_at")),
                (Object[]) states);
        long total = rows.stream().mapToLong(BucketSnapshot::count).sum();
        Instant oldest = rows.stream()
                .map(BucketSnapshot::oldestCreatedAt)
                .filter(Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(null);
        return total == 0 ? BucketSnapshot.empty() : new BucketSnapshot(total, oldest);
    }

    private static BucketSnapshot toSnapshot(long count, Timestamp oldestCreatedAt) {
        if (count == 0 || oldestCreatedAt == null) {
            return BucketSnapshot.empty();
        }
        return new BucketSnapshot(count, oldestCreatedAt.toInstant());
    }
}
