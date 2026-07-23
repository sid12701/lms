package com.bhawana.lms.service;

import com.bhawana.lms.common.money.Money;
import com.bhawana.lms.config.BusinessCalendar;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanDelinquencyBucket;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.OpsAlert;
import com.bhawana.lms.domain.OpsAlertStatus;
import com.bhawana.lms.domain.PortfolioKpiSnapshot;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.repo.OpsAlertRepository;
import com.bhawana.lms.repo.PortfolioKpiSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HomeDashboardService {

    private static final List<LoanDelinquencyBucket> BUCKET_ORDER = List.of(
            LoanDelinquencyBucket.CURRENT,
            LoanDelinquencyBucket.DPD_1_30,
            LoanDelinquencyBucket.DPD_31_60,
            LoanDelinquencyBucket.DPD_61_90,
            LoanDelinquencyBucket.DPD_90_PLUS
    );

    private final LspRepository lspRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final OpsAlertRepository opsAlertRepository;
    private final PortfolioKpiSnapshotRepository portfolioKpiSnapshotRepository;
    private final PortfolioKpiSnapshotComputationService portfolioKpiSnapshotComputationService;
    private final BusinessCalendar businessCalendar;

    public HomeDashboardService(
            LspRepository lspRepository,
            LoanAccountRepository loanAccountRepository,
            LoanApplicationRepository loanApplicationRepository,
            OpsAlertRepository opsAlertRepository,
            PortfolioKpiSnapshotRepository portfolioKpiSnapshotRepository,
            PortfolioKpiSnapshotComputationService portfolioKpiSnapshotComputationService,
            BusinessCalendar businessCalendar
    ) {
        this.lspRepository = lspRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.opsAlertRepository = opsAlertRepository;
        this.portfolioKpiSnapshotRepository = portfolioKpiSnapshotRepository;
        this.portfolioKpiSnapshotComputationService = portfolioKpiSnapshotComputationService;
        this.businessCalendar = businessCalendar;
    }

    @Transactional
    public HomeDashboardSummary getSummary() {
        PortfolioKpiSnapshot globalSnapshot = resolveGlobalSnapshot();
        Map<UUID, PortfolioKpiSnapshot> perLspSnapshots = portfolioKpiSnapshotRepository.findLatestPerLsp().stream()
                .filter(snapshot -> snapshot.getLspId() != null)
                .collect(Collectors.toMap(PortfolioKpiSnapshot::getLspId, Function.identity(), (left, right) -> left));

        List<Lsp> lsps = lspRepository.findAllByOrderByNameAsc().stream()
                .sorted(Comparator.comparing(Lsp::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        LocalDate today = businessCalendar.today();
        List<PriorityAccount> priorityAccounts = loanAccountRepository
                .findHomeDashboardPriorityAccounts(today, PageRequest.of(0, 8))
                .stream()
                .map(snapshot -> toPriorityAccount(snapshot, today))
                .toList();

        BigDecimal totalDisbursedAmount = globalSnapshot.getTotalDisbursed();
        BigDecimal totalOutstandingAmount = globalSnapshot.getTotalOutstanding();
        BucketTotals dpd90PlusTotals = bucketTotals(globalSnapshot, LoanDelinquencyBucket.DPD_90_PLUS);

        List<LspBreakdown> lspBreakdown = lsps.stream()
                .map(lsp -> toLspBreakdown(
                        lsp,
                        perLspSnapshots.get(lsp.getId()),
                        totalDisbursedAmount,
                        dpd90PlusTotals.outstandingAmount()
                ))
                .toList();

        List<StatusCount> applicationsByStatus = readStatusCounts(globalSnapshot);
        int applicationsAwaitingApproval = statusCount(applicationsByStatus, "AWAITING_APPROVAL");
        int applicationsInDisbursement = Math.addExact(
                statusCount(applicationsByStatus, "APPROVED_PENDING_DISBURSAL"),
                statusCount(applicationsByStatus, "DISBURSEMENT_RETRY")
        );
        List<DpdBucketCount> dpdBuckets = readDpdBucketCounts(globalSnapshot);
        var openAlertPage = opsAlertRepository
                .findByStatusOrderByCreatedAtDesc(OpsAlertStatus.NEW, PageRequest.of(0, 5));
        long openAlerts = openAlertPage.getTotalElements();
        List<OpenAlertSummary> openAlertSummaries = openAlertPage
                .getContent()
                .stream()
                .map(this::toOpenAlertSummary)
                .toList();
        List<RecentApplication> recentApplications = loanApplicationRepository
                .findTop8ByOrderByCreatedAtDesc()
                .stream()
                .map(this::toRecentApplication)
                .toList();

        Double avgApprovalTatHours = globalSnapshot.getAvgApprovalTatHours() == null
                ? null
                : globalSnapshot.getAvgApprovalTatHours().setScale(1, RoundingMode.HALF_UP).doubleValue();

        return new HomeDashboardSummary(
                Money.scale(totalDisbursedAmount),
                Money.scale(totalOutstandingAmount),
                Money.scale(dpd90PlusTotals.outstandingAmount()),
                dpd90PlusTotals.loanCount(),
                applicationsAwaitingApproval,
                applicationsInDisbursement,
                avgApprovalTatHours,
                applicationsByStatus,
                dpdBuckets,
                openAlerts,
                openAlertSummaries,
                lspBreakdown,
                priorityAccounts,
                recentApplications,
                globalSnapshot.getComputedAt()
        );
    }

    private PortfolioKpiSnapshot resolveGlobalSnapshot() {
        return portfolioKpiSnapshotRepository.findLatestGlobal()
                .orElseGet(() -> {
                    portfolioKpiSnapshotComputationService.computeAndPersistSnapshots();
                    return portfolioKpiSnapshotRepository.findLatestGlobal()
                            .orElseThrow(() -> new IllegalStateException("Portfolio KPI snapshot unavailable."));
                });
    }

    private List<StatusCount> readStatusCounts(PortfolioKpiSnapshot snapshot) {
        JsonNode statusCounts = snapshot.getStatusCounts();
        return java.util.stream.StreamSupport.stream(
                        ((Iterable<String>) statusCounts::fieldNames).spliterator(),
                        false
                )
                .map(field -> new StatusCount(field, statusCounts.get(field).asLong()))
                .sorted(Comparator.comparing(StatusCount::status))
                .toList();
    }

    private static int statusCount(List<StatusCount> counts, String status) {
        return counts.stream()
                .filter(count -> count.status().equals(status))
                .findFirst()
                .map(StatusCount::count)
                .map(Math::toIntExact)
                .orElse(0);
    }

    private List<DpdBucketCount> readDpdBucketCounts(PortfolioKpiSnapshot snapshot) {
        return BUCKET_ORDER.stream()
                .map(bucket -> new DpdBucketCount(
                        bucket.name(),
                        bucketTotals(snapshot, bucket).loanCount()
                ))
                .toList();
    }

    private BucketTotals bucketTotals(PortfolioKpiSnapshot snapshot, LoanDelinquencyBucket bucket) {
        JsonNode bucketNode = snapshot.getDpdBuckets().get(bucket.name());
        if (bucketNode == null || bucketNode.isNull()) {
            return new BucketTotals(0L, zeroCurrency());
        }
        long loanCount = bucketNode.path("loanCount").asLong(0L);
        String outstandingRaw = bucketNode.path("outstandingAmount").asText("0");
        return new BucketTotals(loanCount, new BigDecimal(outstandingRaw));
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
            PortfolioKpiSnapshot snapshot,
            BigDecimal totalDisbursedAmount,
            BigDecimal totalDpd90PlusAmount
    ) {
        if (snapshot == null) {
            return new LspBreakdown(
                    lsp.getId().toString(),
                    lsp.getCode(),
                    lsp.getName(),
                    zeroCurrency(),
                    zeroCurrency(),
                    zeroCurrency(),
                    0L,
                    zeroCurrency(),
                    zeroCurrency(),
                    BUCKET_ORDER.stream()
                            .map(bucket -> new LspBucketBreakdown(bucket.name(), zeroCurrency(), 0L))
                            .toList()
            );
        }

        BucketTotals dpd90PlusTotals = bucketTotals(snapshot, LoanDelinquencyBucket.DPD_90_PLUS);
        List<LspBucketBreakdown> bucketBreakdown = BUCKET_ORDER.stream()
                .map(bucket -> {
                    BucketTotals totals = bucketTotals(snapshot, bucket);
                    return new LspBucketBreakdown(
                            bucket.name(),
                            Money.scale(totals.outstandingAmount()),
                            totals.loanCount()
                    );
                })
                .toList();

        return new LspBreakdown(
                lsp.getId().toString(),
                lsp.getCode(),
                lsp.getName(),
                Money.scale(snapshot.getTotalDisbursed()),
                Money.scale(snapshot.getTotalOutstanding()),
                Money.scale(dpd90PlusTotals.outstandingAmount()),
                dpd90PlusTotals.loanCount(),
                percentage(snapshot.getTotalDisbursed(), totalDisbursedAmount),
                percentage(dpd90PlusTotals.outstandingAmount(), totalDpd90PlusAmount),
                bucketBreakdown
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

    private record BucketTotals(long loanCount, BigDecimal outstandingAmount) {
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
            List<RecentApplication> recentApplications,
            Instant dataAsOf
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
