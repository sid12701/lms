package com.bhawana.lms.service;

import com.bhawana.lms.common.money.Money;
import com.bhawana.lms.config.BusinessCalendar;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanDelinquencyBucket;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.OpsAlert;
import com.bhawana.lms.domain.OpsAlertStatus;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.repo.OpsAlertRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HomeDashboardService {

    private static final List<LoanApplicationStatus> IN_DISBURSEMENT_STATUSES = List.of(
            LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
            LoanApplicationStatus.DISBURSEMENT_RETRY
    );

    private final LspRepository lspRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository;
    private final OpsAlertRepository opsAlertRepository;
    private final BusinessCalendar businessCalendar;

    public HomeDashboardService(
            LspRepository lspRepository,
            LoanAccountRepository loanAccountRepository,
            LoanApplicationRepository loanApplicationRepository,
            LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository,
            OpsAlertRepository opsAlertRepository,
            BusinessCalendar businessCalendar
    ) {
        this.lspRepository = lspRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanApplicationStatusTransitionRepository = loanApplicationStatusTransitionRepository;
        this.opsAlertRepository = opsAlertRepository;
        this.businessCalendar = businessCalendar;
    }

    @Transactional(readOnly = true)
    public HomeDashboardSummary getSummary() {
        List<LoanDelinquencyBucket> bucketOrder = List.of(
                LoanDelinquencyBucket.CURRENT,
                LoanDelinquencyBucket.DPD_1_30,
                LoanDelinquencyBucket.DPD_31_60,
                LoanDelinquencyBucket.DPD_61_90,
                LoanDelinquencyBucket.DPD_90_PLUS
        );
        List<Lsp> lsps = lspRepository.findAllByOrderByNameAsc().stream()
                .sorted(Comparator.comparing(Lsp::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        LocalDate today = businessCalendar.today();
        List<AccountSnapshot> accountSnapshots = loanAccountRepository.findHomeDashboardAccountSnapshots(today).stream()
                .map(snapshot -> toAccountSnapshot(snapshot, today))
                .toList();
        List<PriorityAccount> priorityAccounts = loanAccountRepository
                .findHomeDashboardPriorityAccounts(today, PageRequest.of(0, 8))
                .stream()
                .map(snapshot -> toPriorityAccount(snapshot, today))
                .toList();

        BigDecimal totalDisbursedAmount = accountSnapshots.stream()
                .map(AccountSnapshot::disbursedAmount)
                .reduce(zeroCurrency(), BigDecimal::add);
        BigDecimal totalOutstandingAmount = accountSnapshots.stream()
                .map(AccountSnapshot::outstandingAmount)
                .reduce(zeroCurrency(), BigDecimal::add);
        BigDecimal dpd90PlusAmount = accountSnapshots.stream()
                .filter(snapshot -> snapshot.bucket() == LoanDelinquencyBucket.DPD_90_PLUS)
                .map(AccountSnapshot::outstandingAmount)
                .reduce(zeroCurrency(), BigDecimal::add);
        long dpd90PlusLoanCount = accountSnapshots.stream()
                .filter(snapshot -> snapshot.bucket() == LoanDelinquencyBucket.DPD_90_PLUS)
                .count();

        List<LspBreakdown> lspBreakdown = lsps.stream()
                .map(lsp -> toLspBreakdown(
                        lsp,
                        accountSnapshots.stream()
                                .filter(snapshot -> snapshot.lspId().equals(lsp.getId()))
                                .toList(),
                        totalDisbursedAmount,
                        dpd90PlusAmount,
                        bucketOrder
                ))
                .toList();

        int applicationsAwaitingApproval = Math.toIntExact(
                loanApplicationRepository.countByStatus(LoanApplicationStatus.AWAITING_APPROVAL));
        int applicationsInDisbursement = Math.toIntExact(
                loanApplicationRepository.countByStatusIn(IN_DISBURSEMENT_STATUSES));
        List<StatusCount> applicationsByStatus = loanApplicationRepository.countGroupByStatus().stream()
                .map(row -> new StatusCount(row.getStatus().name(), row.getCount()))
                .sorted(Comparator.comparing(StatusCount::status))
                .toList();
        List<DpdBucketCount> dpdBuckets = bucketOrder.stream()
                .map(bucket -> new DpdBucketCount(
                        bucket.name(),
                        accountSnapshots.stream().filter(snapshot -> snapshot.bucket() == bucket).count()
                ))
                .toList();
        long openAlerts = opsAlertRepository.countByStatus(OpsAlertStatus.NEW);
        List<OpenAlertSummary> openAlertSummaries = opsAlertRepository
                .findByStatusOrderByCreatedAtDesc(OpsAlertStatus.NEW, PageRequest.of(0, 5))
                .getContent()
                .stream()
                .map(this::toOpenAlertSummary)
                .toList();
        List<RecentApplication> recentApplications = loanApplicationRepository
                .findTop8ByOrderByCreatedAtDesc()
                .stream()
                .map(this::toRecentApplication)
                .toList();

        return new HomeDashboardSummary(
                Money.scale(totalDisbursedAmount),
                Money.scale(totalOutstandingAmount),
                Money.scale(dpd90PlusAmount),
                dpd90PlusLoanCount,
                applicationsAwaitingApproval,
                applicationsInDisbursement,
                computeAvgApprovalTatHours(Instant.now().minus(30, ChronoUnit.DAYS)),
                applicationsByStatus,
                dpdBuckets,
                openAlerts,
                openAlertSummaries,
                lspBreakdown,
                priorityAccounts,
                recentApplications
        );
    }

    private RecentApplication toRecentApplication(LoanApplication application) {
        return new RecentApplication(
                application.getId().toString(),
                application.getExternalLoanId(),
                application.getBorrower().getFullName(),
                application.getLsp().getName(),
                application.getLoanProduct().getName(),
                application.getStatus().name(),
                Money.scale(application.getRequestedAmount()),
                application.getCreatedAt().toString()
        );
    }

    private Double computeAvgApprovalTatHours(Instant windowStart) {
        List<com.bhawana.lms.domain.LoanApplicationStatusTransition> approvals =
                loanApplicationStatusTransitionRepository.findByToStatusAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                        LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                        windowStart
                );
        if (approvals.isEmpty()) {
            return null;
        }

        double totalHours = 0.0;
        int samples = 0;
        for (var approval : approvals) {
            UUID applicationId = approval.getLoanApplication().getId();
            Instant approvalAt = approval.getCreatedAt();
            Instant awaitingAt = loanApplicationStatusTransitionRepository
                    .findByLoanApplication_IdAndToStatusOrderByCreatedAtAsc(
                            applicationId,
                            LoanApplicationStatus.AWAITING_APPROVAL
                    )
                    .stream()
                    .map(com.bhawana.lms.domain.LoanApplicationStatusTransition::getCreatedAt)
                    .filter(createdAt -> !createdAt.isAfter(approvalAt))
                    .max(Instant::compareTo)
                    .orElse(null);
            if (awaitingAt == null || !approvalAt.isAfter(awaitingAt)) {
                continue;
            }
            double hours = ChronoUnit.MINUTES.between(awaitingAt, approvalAt) / 60.0;
            totalHours += hours;
            samples += 1;
        }
        if (samples == 0) {
            return null;
        }
        return BigDecimal.valueOf(totalHours / samples)
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private OpenAlertSummary toOpenAlertSummary(OpsAlert alert) {
        return new OpenAlertSummary(
                alert.getId().toString(),
                alert.getSeverity().name(),
                alert.getTitle(),
                alert.getSubjectType() == null ? "UNKNOWN" : alert.getSubjectType(),
                alert.getSubjectId() == null ? "" : alert.getSubjectId().toString(),
                alert.getCreatedAt().toString()
        );
    }

    private AccountSnapshot toAccountSnapshot(
            LoanAccountRepository.HomeDashboardAccountSnapshotProjection snapshot,
            LocalDate today
    ) {
        BigDecimal disbursedAmount = defaultCurrency(snapshot.getDisbursedAmount());
        BigDecimal outstandingAmount = defaultCurrency(snapshot.getOutstandingAmount());
        int maxDaysPastDue = snapshot.getOldestOverdueDueDate() == null
                ? 0
                : Math.toIntExact(ChronoUnit.DAYS.between(snapshot.getOldestOverdueDueDate(), today));
        LoanDelinquencyBucket bucket = LoanDelinquencySupport.resolveDelinquencyBucket(maxDaysPastDue);

        return new AccountSnapshot(
                snapshot.getLspId(),
                Money.scale(disbursedAmount),
                Money.scale(outstandingAmount),
                bucket
        );
    }

    private PriorityAccount toPriorityAccount(
            LoanAccountRepository.HomeDashboardPriorityAccountProjection snapshot,
            LocalDate today
    ) {
        LocalDate oldestOverdueDueDate = snapshot.getOldestOverdueDueDate();
        int daysPastDue = oldestOverdueDueDate == null
                ? 0
                : Math.max(0, Math.toIntExact(ChronoUnit.DAYS.between(oldestOverdueDueDate, today)));

        return new PriorityAccount(
                snapshot.getApplicationId().toString(),
                snapshot.getExternalLoanId(),
                snapshot.getCustomerName(),
                snapshot.getLspCode(),
                Money.scale(defaultCurrency(snapshot.getPrincipalAmount())),
                Money.scale(defaultCurrency(snapshot.getInterestRate())),
                snapshot.getLoanStatus().name(),
                Money.scale(defaultCurrency(snapshot.getOverdueAmount())),
                daysPastDue
        );
    }

    private LspBreakdown toLspBreakdown(
            Lsp lsp,
            List<AccountSnapshot> snapshots,
            BigDecimal totalDisbursedAmount,
            BigDecimal totalDpd90PlusAmount,
            List<LoanDelinquencyBucket> bucketOrder
    ) {
        BigDecimal disbursedAmount = snapshots.stream()
                .map(AccountSnapshot::disbursedAmount)
                .reduce(zeroCurrency(), BigDecimal::add);
        BigDecimal outstandingAmount = snapshots.stream()
                .map(AccountSnapshot::outstandingAmount)
                .reduce(zeroCurrency(), BigDecimal::add);
        BigDecimal dpd90PlusAmount = snapshots.stream()
                .filter(snapshot -> snapshot.bucket() == LoanDelinquencyBucket.DPD_90_PLUS)
                .map(AccountSnapshot::outstandingAmount)
                .reduce(zeroCurrency(), BigDecimal::add);
        long dpd90PlusLoanCount = snapshots.stream()
                .filter(snapshot -> snapshot.bucket() == LoanDelinquencyBucket.DPD_90_PLUS)
                .count();
        List<LspBucketBreakdown> bucketBreakdown = bucketOrder.stream()
                .map(bucket -> toBucketBreakdown(bucket, snapshots))
                .toList();

        return new LspBreakdown(
                lsp.getId().toString(),
                lsp.getCode(),
                lsp.getName(),
                Money.scale(disbursedAmount),
                Money.scale(outstandingAmount),
                Money.scale(dpd90PlusAmount),
                dpd90PlusLoanCount,
                percentage(disbursedAmount, totalDisbursedAmount),
                percentage(dpd90PlusAmount, totalDpd90PlusAmount),
                bucketBreakdown
        );
    }

    private LspBucketBreakdown toBucketBreakdown(
            LoanDelinquencyBucket bucket,
            List<AccountSnapshot> snapshots
    ) {
        BigDecimal outstandingAmount = snapshots.stream()
                .filter(snapshot -> snapshot.bucket() == bucket)
                .map(AccountSnapshot::outstandingAmount)
                .reduce(zeroCurrency(), BigDecimal::add);
        long loanCount = snapshots.stream()
                .filter(snapshot -> snapshot.bucket() == bucket)
                .count();

        return new LspBucketBreakdown(
                bucket.name(),
                Money.scale(outstandingAmount),
                loanCount
        );
    }

    private static BigDecimal zeroCurrency() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal defaultCurrency(BigDecimal value) {
        return value == null ? zeroCurrency() : value;
    }

    private static BigDecimal percentage(BigDecimal value, BigDecimal total) {
        if (total.compareTo(BigDecimal.ZERO) <= 0 || value.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return value.multiply(BigDecimal.valueOf(100))
                .divide(total, 2, RoundingMode.HALF_UP);
    }

    private record AccountSnapshot(
            UUID lspId,
            BigDecimal disbursedAmount,
            BigDecimal outstandingAmount,
            LoanDelinquencyBucket bucket
    ) {
    }

    public record HomeDashboardSummary(
            BigDecimal totalDisbursedAmount,
            BigDecimal totalOutstandingAmount,
            BigDecimal dpd90PlusAmount,
            long dpd90PlusLoanCount,
            int applicationsAwaitingApproval,
            int applicationsInDisbursement,
            Double avgApprovalTatHours,
            List<StatusCount> applicationsByStatus,
            List<DpdBucketCount> dpdBuckets,
            long openAlerts,
            List<OpenAlertSummary> openAlertSummaries,
            List<LspBreakdown> lspBreakdown,
            List<PriorityAccount> priorityAccounts,
            List<RecentApplication> recentApplications
    ) {
    }

    public record RecentApplication(
            String id,
            String externalLoanId,
            String borrowerNameMasked,
            String lspName,
            String productName,
            String status,
            BigDecimal requestedAmount,
            String createdAt
    ) {
    }

    public record StatusCount(String status, long count) {
    }

    public record DpdBucketCount(String bucket, long count) {
    }

    public record OpenAlertSummary(
            String id,
            String severity,
            String title,
            String subjectType,
            String subjectId,
            String createdAt
    ) {
    }

    public record PriorityAccount(
            String applicationId,
            String externalLoanId,
            String customerName,
            String lspCode,
            BigDecimal principalAmount,
            BigDecimal interestRate,
            String loanStatusDisplay,
            BigDecimal overdueAmount,
            int daysPastDue
    ) {
    }

    public record LspBreakdown(
            String lspId,
            String lspCode,
            String lspName,
            BigDecimal disbursedAmount,
            BigDecimal outstandingAmount,
            BigDecimal dpd90PlusAmount,
            long dpd90PlusLoanCount,
            BigDecimal shareOfDisbursedPercent,
            BigDecimal shareOfDpd90PlusPercent,
            List<LspBucketBreakdown> bucketBreakdown
    ) {
    }

    public record LspBucketBreakdown(
            String bucket,
            BigDecimal outstandingAmount,
            long loanCount
    ) {
    }
}
