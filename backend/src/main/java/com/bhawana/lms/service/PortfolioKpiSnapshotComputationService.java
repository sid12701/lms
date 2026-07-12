package com.bhawana.lms.service;

import com.bhawana.lms.common.money.Money;
import com.bhawana.lms.config.BusinessCalendar;
import com.bhawana.lms.domain.LoanDelinquencyBucket;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.PortfolioKpiSnapshot;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.repo.PortfolioKpiSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortfolioKpiSnapshotComputationService {

    private static final List<LoanDelinquencyBucket> BUCKET_ORDER = List.of(
            LoanDelinquencyBucket.CURRENT,
            LoanDelinquencyBucket.DPD_1_30,
            LoanDelinquencyBucket.DPD_31_60,
            LoanDelinquencyBucket.DPD_61_90,
            LoanDelinquencyBucket.DPD_90_PLUS
    );

    private final NamedParameterJdbcTemplate jdbc;
    private final LspRepository lspRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final PortfolioKpiSnapshotRepository portfolioKpiSnapshotRepository;
    private final BusinessCalendar businessCalendar;
    private final ObjectMapper objectMapper;

    public PortfolioKpiSnapshotComputationService(
            NamedParameterJdbcTemplate jdbc,
            LspRepository lspRepository,
            LoanApplicationRepository loanApplicationRepository,
            PortfolioKpiSnapshotRepository portfolioKpiSnapshotRepository,
            BusinessCalendar businessCalendar,
            ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.lspRepository = lspRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.portfolioKpiSnapshotRepository = portfolioKpiSnapshotRepository;
        this.businessCalendar = businessCalendar;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Instant computeAndPersistSnapshots() {
        Instant computedAt = Instant.now();
        LocalDate today = businessCalendar.today();
        List<AccountSnapshotRow> accountRows = loadAccountSnapshots(today);
        ObjectNode globalStatusCounts = buildStatusCounts();
        Double avgApprovalTatHours = computeAvgApprovalTatHours(Instant.now().minus(30, ChronoUnit.DAYS));

        Map<UUID, List<AccountSnapshotRow>> byLsp = new HashMap<>();
        for (AccountSnapshotRow row : accountRows) {
            byLsp.computeIfAbsent(row.lspId(), ignored -> new ArrayList<>()).add(row);
        }

        portfolioKpiSnapshotRepository.save(buildSnapshot(
                null,
                computedAt,
                accountRows,
                globalStatusCounts,
                avgApprovalTatHours
        ));

        for (Lsp lsp : lspRepository.findAllByOrderByNameAsc()) {
            List<AccountSnapshotRow> lspRows = byLsp.getOrDefault(lsp.getId(), List.of());
            portfolioKpiSnapshotRepository.save(buildSnapshot(
                    lsp,
                    computedAt,
                    lspRows,
                    objectMapper.createObjectNode(),
                    null
            ));
        }

        return computedAt;
    }

    private PortfolioKpiSnapshot buildSnapshot(
            Lsp lsp,
            Instant computedAt,
            List<AccountSnapshotRow> rows,
            ObjectNode statusCounts,
            Double avgApprovalTatHours
    ) {
        BigDecimal totalDisbursed = zeroCurrency();
        BigDecimal totalOutstanding = zeroCurrency();
        BigDecimal totalOverdue = zeroCurrency();
        Map<LoanDelinquencyBucket, BucketAccumulator> bucketAccumulators = new EnumMap<>(LoanDelinquencyBucket.class);
        for (LoanDelinquencyBucket bucket : BUCKET_ORDER) {
            bucketAccumulators.put(bucket, new BucketAccumulator());
        }

        for (AccountSnapshotRow row : rows) {
            totalDisbursed = totalDisbursed.add(row.disbursedAmount());
            totalOutstanding = totalOutstanding.add(row.outstandingAmount());
            totalOverdue = totalOverdue.add(row.overdueAmount());
            BucketAccumulator accumulator = bucketAccumulators.get(row.bucket());
            accumulator.loanCount += 1;
            accumulator.outstandingAmount = accumulator.outstandingAmount.add(row.outstandingAmount());
        }

        ObjectNode dpdBuckets = objectMapper.createObjectNode();
        for (LoanDelinquencyBucket bucket : BUCKET_ORDER) {
            BucketAccumulator accumulator = bucketAccumulators.get(bucket);
            ObjectNode bucketNode = objectMapper.createObjectNode();
            bucketNode.put("loanCount", accumulator.loanCount);
            bucketNode.put("outstandingAmount", Money.scale(accumulator.outstandingAmount).toPlainString());
            dpdBuckets.set(bucket.name(), bucketNode);
        }

        BigDecimal avgTat = avgApprovalTatHours == null
                ? null
                : BigDecimal.valueOf(avgApprovalTatHours).setScale(2, RoundingMode.HALF_UP);

        return new PortfolioKpiSnapshot(
                lsp,
                computedAt,
                Money.scale(totalDisbursed),
                Money.scale(totalOutstanding),
                Money.scale(totalOverdue),
                statusCounts,
                dpdBuckets,
                avgTat
        );
    }

    private ObjectNode buildStatusCounts() {
        ObjectNode statusCounts = objectMapper.createObjectNode();
        loanApplicationRepository.countGroupByStatus().forEach(row ->
                statusCounts.put(row.getStatus().name(), row.getCount())
        );
        return statusCounts;
    }

    private List<AccountSnapshotRow> loadAccountSnapshots(LocalDate today) {
        return jdbc.query("""
                select
                  la.lsp_id as lsp_id,
                  case when la.disbursed_at is null then 0 else la.principal_amount end as disbursed_amount,
                  coalesce(sum(lrsi.outstanding_amount), 0) as outstanding_amount,
                  coalesce(sum(case
                      when lrsi.outstanding_amount > 0 and lrsi.due_date < :today
                      then lrsi.outstanding_amount
                      else 0
                  end), 0) as overdue_amount,
                  min(case
                      when lrsi.outstanding_amount > 0 and lrsi.due_date < :today
                      then lrsi.due_date
                      else null
                  end) as oldest_overdue_due_date
                from loan_account la
                left join loan_repayment_schedule_installment lrsi
                  on lrsi.loan_account_id = la.id
                group by la.id, la.lsp_id, la.disbursed_at, la.principal_amount
                """,
                new MapSqlParameterSource("today", today),
                (rs, rowNum) -> {
                    LocalDate oldestOverdueDueDate = rs.getDate("oldest_overdue_due_date") == null
                            ? null
                            : rs.getDate("oldest_overdue_due_date").toLocalDate();
                    int maxDaysPastDue = oldestOverdueDueDate == null
                            ? 0
                            : Math.toIntExact(ChronoUnit.DAYS.between(oldestOverdueDueDate, today));
                    return new AccountSnapshotRow(
                            rs.getObject("lsp_id", UUID.class),
                            Money.scale(rs.getBigDecimal("disbursed_amount")),
                            Money.scale(rs.getBigDecimal("outstanding_amount")),
                            Money.scale(rs.getBigDecimal("overdue_amount")),
                            LoanDelinquencySupport.resolveDelinquencyBucket(maxDaysPastDue)
                    );
                }
        );
    }

    private Double computeAvgApprovalTatHours(Instant windowStart) {
        Double avgHours = jdbc.queryForObject("""
                select avg(extract(epoch from (approval.created_at - awaiting.awaiting_at)) / 3600.0)
                from loan_application_status_transition approval
                join (
                    select st.loan_application_id,
                           approval_inner.created_at as approval_at,
                           max(st.created_at) as awaiting_at
                    from loan_application_status_transition approval_inner
                    join loan_application_status_transition st
                      on st.loan_application_id = approval_inner.loan_application_id
                     and st.to_status = 'AWAITING_APPROVAL'
                     and st.created_at <= approval_inner.created_at
                    where approval_inner.to_status = 'APPROVED_PENDING_DISBURSAL'
                      and approval_inner.created_at >= :windowStart
                    group by st.loan_application_id, approval_inner.created_at
                ) awaiting on awaiting.loan_application_id = approval.loan_application_id
                             and awaiting.approval_at = approval.created_at
                where approval.to_status = 'APPROVED_PENDING_DISBURSAL'
                  and approval.created_at >= :windowStart
                  and approval.created_at > awaiting.awaiting_at
                """,
                // pgjdbc cannot infer a SQL type for java.time.Instant — bind as Timestamp.
                new MapSqlParameterSource("windowStart", java.sql.Timestamp.from(windowStart)),
                Double.class
        );
        if (avgHours == null) {
            return null;
        }
        return BigDecimal.valueOf(avgHours).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static BigDecimal zeroCurrency() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    private record AccountSnapshotRow(
            UUID lspId,
            BigDecimal disbursedAmount,
            BigDecimal outstandingAmount,
            BigDecimal overdueAmount,
            LoanDelinquencyBucket bucket
    ) {
    }

    private static final class BucketAccumulator {
        private long loanCount;
        private BigDecimal outstandingAmount = zeroCurrency();
    }
}
