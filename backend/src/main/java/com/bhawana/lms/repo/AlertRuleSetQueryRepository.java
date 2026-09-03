package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanDelinquencyBucket;
import com.bhawana.lms.service.LoanDelinquencySupport;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AlertRuleSetQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AlertRuleSetQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<StaleIntakeCandidate> findStaleIntakeCandidates(Instant cutoff, int limit) {
        return jdbc.query("""
                select la.id as application_id, la.external_loan_id
                from loan_application la
                where la.status = 'INITIALIZED'
                  and la.created_at < :cutoff
                order by la.created_at asc
                limit :limit
                """,
                new MapSqlParameterSource()
                        .addValue("cutoff", toTimestamp(cutoff))
                        .addValue("limit", limit),
                (rs, rowNum) -> new StaleIntakeCandidate(
                        rs.getObject("application_id", UUID.class),
                        rs.getString("external_loan_id")
                )
        );
    }

    public List<StuckDisbursementCandidate> findStuckDisbursementCandidates(Instant cutoff, int limit) {
        return jdbc.query("""
                select la.id as application_id,
                       la.external_loan_id,
                       last_retry.created_at as last_retry_at
                from loan_application la
                join (
                    select st.loan_application_id, max(st.created_at) as created_at
                    from loan_application_status_transition st
                    where st.to_status = 'DISBURSEMENT_RETRY'
                    group by st.loan_application_id
                ) last_retry on last_retry.loan_application_id = la.id
                where la.status = 'DISBURSEMENT_RETRY'
                  and last_retry.created_at < :cutoff
                order by last_retry.created_at asc
                limit :limit
                """,
                new MapSqlParameterSource()
                        .addValue("cutoff", toTimestamp(cutoff))
                        .addValue("limit", limit),
                (rs, rowNum) -> new StuckDisbursementCandidate(
                        rs.getObject("application_id", UUID.class),
                        rs.getString("external_loan_id"),
                        rs.getTimestamp("last_retry_at").toInstant()
                )
        );
    }

    public List<DelinquencyEvaluationRow> findServicingDelinquencyRows(LocalDate today) {
        return jdbc.query("""
                select
                  app.id as application_id,
                  app.external_loan_id,
                  app.lsp_id,
                  coalesce(max(case
                      when inst.outstanding_amount > 0 and inst.due_date < :today
                      then (cast(:today as date) - inst.due_date)
                      else 0
                  end), 0) as max_days_past_due,
                  coalesce(sum(case
                      when inst.outstanding_amount > 0 and inst.due_date < :today
                      then inst.outstanding_amount
                      else 0
                  end), 0) as overdue_amount,
                  ds.last_bucket as previous_bucket,
                  ds.last_max_days_past_due as previous_max_days_past_due,
                  ds.id as state_id
                from loan_application app
                join loan_account acc on acc.loan_application_id = app.id
                left join loan_repayment_schedule_installment inst on inst.loan_account_id = acc.id
                left join loan_delinquency_state ds on ds.loan_application_id = app.id
                where app.status = 'UNDER_REPAYMENT'
                group by app.id, app.external_loan_id, app.lsp_id, ds.id, ds.last_bucket, ds.last_max_days_past_due
                """,
                new MapSqlParameterSource("today", today),
                (rs, rowNum) -> {
                    int maxDaysPastDue = rs.getInt("max_days_past_due");
                    LoanDelinquencyBucket currentBucket = LoanDelinquencySupport.resolveDelinquencyBucket(maxDaysPastDue);
                    String previousBucketRaw = rs.getString("previous_bucket");
                    LoanDelinquencyBucket previousBucket = previousBucketRaw == null
                            ? LoanDelinquencyBucket.CURRENT
                            : LoanDelinquencyBucket.valueOf(previousBucketRaw);
                    return new DelinquencyEvaluationRow(
                            rs.getObject("application_id", UUID.class),
                            rs.getString("external_loan_id"),
                            rs.getObject("lsp_id", UUID.class),
                            maxDaysPastDue,
                            rs.getBigDecimal("overdue_amount"),
                            currentBucket,
                            previousBucket,
                            rs.getInt("previous_max_days_past_due"),
                            rs.getObject("state_id", UUID.class)
                    );
                }
        );
    }

    /**
     * The oldest transaction currently open anywhere on the cluster, by {@code xact_start}.
     *
     * Deliberately not scoped to {@code datname = current_database()}: transaction ids are
     * cluster-wide, so a long-running write transaction in a different database on the same
     * cluster holds back this database's {@code pg_snapshot_xmin(pg_current_snapshot())} just as
     * much as one in this database would. Filtering it out would blind the alert to exactly the
     * failure it exists to catch.
     *
     * {@code clock_timestamp()}, not {@code now()}: {@code now()} is the transaction timestamp,
     * not the wall clock, and this repository is called from inside
     * {@code evaluateScheduledRules()}'s own transaction — {@code now() - xact_start} would read
     * zero for that transaction and under-report every other one by this transaction's own
     * elapsed time.
     *
     * {@code pid <> pg_backend_pid()} excludes the evaluating backend's own transaction — the
     * evaluation itself is an open transaction and must not report on itself.
     *
     * {@code backend_type = 'client backend'} excludes autovacuum workers, which advertise
     * {@code PROC_IN_VACUUM} and are already excluded from the xmin horizon, so alerting on them
     * would be a false positive.
     *
     * {@code query} is deliberately not selected: statement text can carry borrower PII and this
     * alert's message and context render in the ops UI. pid + application_name + username is what
     * an operator needs to find and kill the session.
     */
    public Optional<OldestOpenTransaction> findOldestOpenTransaction() {
        return jdbc.query("""
                select pid,
                       floor(extract(epoch from (clock_timestamp() - xact_start)))::bigint as age_seconds,
                       coalesce(state, '') as state,
                       coalesce(application_name, '') as application_name,
                       coalesce(usename, '') as usename
                from pg_stat_activity
                where xact_start is not null
                  and pid <> pg_backend_pid()
                  and backend_type = 'client backend'
                order by xact_start asc
                limit 1
                """,
                new MapSqlParameterSource(),
                (rs, rowNum) -> new OldestOpenTransaction(
                        rs.getLong("pid"),
                        rs.getLong("age_seconds"),
                        rs.getString("state"),
                        rs.getString("application_name"),
                        rs.getString("usename")
                )
        ).stream().findFirst();
    }

    /**
     * Whether the current role can see {@code xact_start} for sessions it does not own. A plain
     * login role without {@code pg_read_all_stats} sees {@code NULL} there for other users'
     * sessions, so {@link #findOldestOpenTransaction()} silently returns nothing and the alert
     * never fires — the worst possible failure for a launch-blocking alert. The admin DB role must
     * be superuser or hold {@code pg_read_all_stats} for this alert to see anything at all.
     */
    public boolean canReadAllBackendStats() {
        Boolean result = jdbc.queryForObject(
                "select current_setting('is_superuser')::boolean "
                        + "or pg_has_role(current_user, 'pg_read_all_stats', 'MEMBER')",
                new MapSqlParameterSource(),
                Boolean.class
        );
        return Boolean.TRUE.equals(result);
    }

    // pgjdbc cannot infer a SQL type for java.time.Instant — bind cutoff filters
    // as java.sql.Timestamp instead.
    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    public record StaleIntakeCandidate(UUID applicationId, String externalLoanId) {
    }

    public record StuckDisbursementCandidate(UUID applicationId, String externalLoanId, Instant lastRetryAt) {
    }

    public record OldestOpenTransaction(
            long pid,
            long ageSeconds,
            String state,
            String applicationName,
            String username
    ) {
    }

    public record DelinquencyEvaluationRow(
            UUID applicationId,
            String externalLoanId,
            UUID lspId,
            int maxDaysPastDue,
            BigDecimal overdueAmount,
            LoanDelinquencyBucket currentBucket,
            LoanDelinquencyBucket previousBucket,
            int previousMaxDaysPastDue,
            UUID existingStateId
    ) {
    }
}
