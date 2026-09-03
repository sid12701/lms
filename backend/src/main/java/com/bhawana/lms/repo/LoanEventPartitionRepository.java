package com.bhawana.lms.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The loan event log's partition lifecycle: months created ahead of the writes that need them, and
 * months dropped once everything they can hold is past retention (ADR 0007).
 *
 * <p>Both halves live in {@code maintain_loan_event_partitions} (V117) rather than here, because
 * both are DDL whose bounds have to be computed the same way V116 computed them — in the database's
 * timezone, from the database's clock. This class chooses the policy and reports what changed.
 */
@Repository
public class LoanEventPartitionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public LoanEventPartitionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Brings the partition set in line with the policy and returns only what changed — an empty list
     * meaning the log was already correctly partitioned.
     *
     * <p>Idempotent: a month already present is left alone, and a partition already dropped cannot be
     * dropped again. Concurrent callers serialise inside the function, so running this on every pod
     * is safe.
     *
     * <p>{@code retentionDays} is a floor. Partitions are whole months, so the month holding the
     * cutoff is kept until every row it can hold has aged out; effective retention is between
     * {@code retentionDays} and {@code retentionDays} plus one month.
     */
    public List<PartitionChange> maintain(int leadMonths, int retentionDays) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("leadMonths", leadMonths)
                .addValue("retentionDays", retentionDays);

        return jdbc.query(
                "SELECT action, partition_name FROM maintain_loan_event_partitions(:leadMonths, :retentionDays)",
                parameters,
                (resultSet, rowNumber) -> new PartitionChange(
                        PartitionChange.Action.valueOf(resultSet.getString("action")),
                        resultSet.getString("partition_name")
                )
        );
    }

    /**
     * The lower bound of the oldest partition {@code loan_event} currently has — the earliest
     * {@code occurred_at} the log can still serve. Empty when the table has no partitions at all.
     *
     * <p>Read from the catalog rather than computed as {@code now() - retentionDays}: retention is a
     * floor, not an exact age, because partitions are whole months (ADR 0007 point 5). The month
     * holding the 30-day cutoff is kept until everything in it ages out, so a flat subtraction would
     * disagree with the log by up to a month — and cursor expiry, which is what this method feeds,
     * must never 410 a partner for an event the feed is still serving (CONTEXT.md § Retention window).
     *
     * <p>Mirrors the drop loop in {@code maintain_loan_event_partitions} (V117), reading {@code FROM}
     * instead of {@code TO} out of {@code pg_get_expr(child.relpartbound, child.oid)}. A bound that
     * does not parse — a DEFAULT partition — yields {@code NULL} from the regexp match, which
     * {@code MIN()} ignores, so it is silently excluded rather than winning as "oldest".
     *
     * <p>A catalog read needs no grant of its own and is unaffected by row-level security, so this
     * runs on whatever connection — tenant or admin — the caller already holds.
     */
    public Optional<Instant> oldestRetainedBound() {
        Timestamp oldestBound = jdbc.queryForObject(
                """
                SELECT MIN((regexp_match(
                        pg_get_expr(child.relpartbound, child.oid),
                        'FROM \\(''([^'']+)''\\)'
                    ))[1]::TIMESTAMPTZ) AS oldest_bound
                FROM pg_inherits
                JOIN pg_class child ON child.oid = pg_inherits.inhrelid
                WHERE pg_inherits.inhparent = 'loan_event'::regclass
                """,
                new MapSqlParameterSource(),
                Timestamp.class
        );
        return Optional.ofNullable(oldestBound).map(Timestamp::toInstant);
    }

    /** One partition the maintenance run added or removed. */
    public record PartitionChange(Action action, String partitionName) {

        public enum Action {
            CREATED,
            DROPPED
        }
    }
}
