package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanDelinquencyBucket;
import com.bhawana.lms.service.LoanDelinquencySupport;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AlertRuleSetQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final DataSource dataSource;
    private volatile Boolean h2Database;

    public AlertRuleSetQueryRepository(NamedParameterJdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    private boolean h2Database() {
        Boolean cached = h2Database;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (h2Database == null) {
                h2Database = detectH2();
            }
            return h2Database;
        }
    }

    private boolean detectH2() {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("h2");
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to detect database product for alert rule queries.", exception);
        }
    }

    private String daysPastDueExpression() {
        if (h2Database()) {
            return "datediff('DAY', inst.due_date, :today)";
        }
        return "(cast(:today as date) - inst.due_date)";
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
        String daysPastDue = daysPastDueExpression();
        return jdbc.query("""
                select
                  app.id as application_id,
                  app.external_loan_id,
                  coalesce(max(case
                      when inst.outstanding_amount > 0 and inst.due_date < :today
                      then %s
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
                group by app.id, app.external_loan_id, ds.id, ds.last_bucket, ds.last_max_days_past_due
                """.formatted(daysPastDue),
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

    // pgjdbc cannot infer a SQL type for java.time.Instant — bind cutoff filters
    // as java.sql.Timestamp instead.
    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    public record StaleIntakeCandidate(UUID applicationId, String externalLoanId) {
    }

    public record StuckDisbursementCandidate(UUID applicationId, String externalLoanId, Instant lastRetryAt) {
    }

    public record DelinquencyEvaluationRow(
            UUID applicationId,
            String externalLoanId,
            int maxDaysPastDue,
            BigDecimal overdueAmount,
            LoanDelinquencyBucket currentBucket,
            LoanDelinquencyBucket previousBucket,
            int previousMaxDaysPastDue,
            UUID existingStateId
    ) {
    }
}
