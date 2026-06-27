package com.bhawana.lms.seed.synthetic;

import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplicationStatus;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * Month-9 V2-D1 portfolio targets for #197. Counts scale via {@link SyntheticPortfolioSeedProperties}.
 */
public final class SyntheticPortfolioSpec {

    static final int BASE_TOTAL_APPLICATIONS = 2_500_000;
    static final int BASE_ACTIVE_UNDER_REPAYMENT = 500_000;
    static final int BASE_CLOSED = 1_450_000;
    static final int BASE_FORECLOSED = 50_000;
    static final int BASE_DISBURSED = 50_000;
    static final int BASE_APPROVED_PENDING = 75_000;
    static final int BASE_AWAITING_APPROVAL = 50_000;
    static final int BASE_INITIALIZED = 25_000;
    static final int BASE_REJECTED = 200_000;
    static final int BASE_INVALID = 100_000;
    static final int BASE_PAYMENT_TRANSACTIONS = 3_000_000;
    static final int BASE_AUDIT_ROWS = 30_000_000;

    static final BigDecimal DEFAULT_PRINCIPAL = new BigDecimal("150000.00");
    static final BigDecimal DEFAULT_INTEREST_RATE = new BigDecimal("18.50");
    static final BigDecimal DEFAULT_PROCESSING_FEE_RATE = new BigDecimal("2.00");

    private final int totalApplications;
    private final int activeUnderRepayment;
    private final int closed;
    private final int foreclosed;
    private final int disbursed;
    private final int approvedPending;
    private final int awaitingApproval;
    private final int initialized;
    private final int rejected;
    private final int invalid;
    private final int paymentTransactions;
    private final int auditRows;
    private final int lspCount;
    private final int whaleLspIndex;
    private final double whaleVolumeShare;
    private final int tenureMonths;
    private final int batchSize;

    private SyntheticPortfolioSpec(
            int totalApplications,
            int activeUnderRepayment,
            int closed,
            int foreclosed,
            int disbursed,
            int approvedPending,
            int awaitingApproval,
            int initialized,
            int rejected,
            int invalid,
            int paymentTransactions,
            int auditRows,
            int lspCount,
            int whaleLspIndex,
            double whaleVolumeShare,
            int tenureMonths,
            int batchSize
    ) {
        this.totalApplications = totalApplications;
        this.activeUnderRepayment = activeUnderRepayment;
        this.closed = closed;
        this.foreclosed = foreclosed;
        this.disbursed = disbursed;
        this.approvedPending = approvedPending;
        this.awaitingApproval = awaitingApproval;
        this.initialized = initialized;
        this.rejected = rejected;
        this.invalid = invalid;
        this.paymentTransactions = paymentTransactions;
        this.auditRows = auditRows;
        this.lspCount = lspCount;
        this.whaleLspIndex = whaleLspIndex;
        this.whaleVolumeShare = whaleVolumeShare;
        this.tenureMonths = tenureMonths;
        this.batchSize = batchSize;
    }

    public static SyntheticPortfolioSpec from(SyntheticPortfolioSeedProperties properties) {
        double scale = resolveScale(properties);
        return new SyntheticPortfolioSpec(
                scaleCount(BASE_TOTAL_APPLICATIONS, scale, properties.getApplicationCountOverride()),
                scaleCount(BASE_ACTIVE_UNDER_REPAYMENT, scale, null),
                scaleCount(BASE_CLOSED, scale, null),
                scaleCount(BASE_FORECLOSED, scale, null),
                scaleCount(BASE_DISBURSED, scale, null),
                scaleCount(BASE_APPROVED_PENDING, scale, null),
                scaleCount(BASE_AWAITING_APPROVAL, scale, null),
                scaleCount(BASE_INITIALIZED, scale, null),
                scaleCount(BASE_REJECTED, scale, null),
                scaleCount(BASE_INVALID, scale, null),
                scaleCount(BASE_PAYMENT_TRANSACTIONS, scale, null),
                scaleCount(BASE_AUDIT_ROWS, scale, null),
                Math.max(1, properties.getLspCount()),
                properties.getWhaleLspIndex(),
                properties.getWhaleVolumeShare(),
                properties.getTenureMonths(),
                Math.max(500, properties.getBatchSize())
        );
    }

    private static double resolveScale(SyntheticPortfolioSeedProperties properties) {
        if (properties.getApplicationCountOverride() != null) {
            return properties.getApplicationCountOverride() / (double) BASE_TOTAL_APPLICATIONS;
        }
        return Math.max(0.000_001d, properties.getScaleFactor());
    }

    private static int scaleCount(int base, double scale, Integer overrideTotal) {
        if (overrideTotal != null && base == BASE_TOTAL_APPLICATIONS) {
            return overrideTotal;
        }
        return Math.max(0, (int) Math.round(base * scale));
    }

    public int totalApplications() {
        return totalApplications;
    }

    public int activeUnderRepayment() {
        return activeUnderRepayment;
    }

    public int closed() {
        return closed;
    }

    public int foreclosed() {
        return foreclosed;
    }

    public int disbursed() {
        return disbursed;
    }

    public int approvedPending() {
        return approvedPending;
    }

    public int awaitingApproval() {
        return awaitingApproval;
    }

    public int initialized() {
        return initialized;
    }

    public int rejected() {
        return rejected;
    }

    public int invalid() {
        return invalid;
    }

    public int paymentTransactions() {
        return paymentTransactions;
    }

    public int auditRows() {
        return auditRows;
    }

    public int lspCount() {
        return lspCount;
    }

    public int whaleLspIndex() {
        return whaleLspIndex;
    }

    public double whaleVolumeShare() {
        return whaleVolumeShare;
    }

    public int tenureMonths() {
        return tenureMonths;
    }

    public int batchSize() {
        return batchSize;
    }

    /** Applications per LSP using whale skew. */
    public int[] applicationsPerLsp() {
        int[] counts = new int[lspCount];
        int whaleApps = (int) Math.round(totalApplications * whaleVolumeShare);
        int remainder = totalApplications - whaleApps;
        int perOther = lspCount > 1 ? remainder / (lspCount - 1) : 0;
        int assigned = 0;
        for (int i = 0; i < lspCount; i++) {
            if (i == whaleLspIndex) {
                counts[i] = whaleApps;
            } else {
                counts[i] = perOther;
            }
            assigned += counts[i];
        }
        counts[lspCount - 1] += totalApplications - assigned;
        return counts;
    }

    public int accountsWithSchedules() {
        return activeUnderRepayment + closed + foreclosed + disbursed + approvedPending;
    }

    public List<StatusBucket> statusBuckets() {
        return List.of(
                new StatusBucket(LoanApplicationStatus.UNDER_REPAYMENT, activeUnderRepayment, true, LoanAccountStatus.DISBURSED),
                new StatusBucket(LoanApplicationStatus.CLOSED, closed, true, LoanAccountStatus.CLOSED),
                new StatusBucket(LoanApplicationStatus.FORECLOSED, foreclosed, true, LoanAccountStatus.FORECLOSED),
                new StatusBucket(LoanApplicationStatus.DISBURSED, disbursed, true, LoanAccountStatus.DISBURSED),
                new StatusBucket(LoanApplicationStatus.APPROVED_PENDING_DISBURSAL, approvedPending, true, LoanAccountStatus.PENDING_DISBURSEMENT),
                new StatusBucket(LoanApplicationStatus.AWAITING_APPROVAL, awaitingApproval, false, null),
                new StatusBucket(LoanApplicationStatus.INITIALIZED, initialized, false, null),
                new StatusBucket(LoanApplicationStatus.REJECTED, rejected, false, null),
                new StatusBucket(LoanApplicationStatus.INVALID, invalid, false, null)
        );
    }

    public record StatusBucket(
            LoanApplicationStatus applicationStatus,
            int count,
            boolean hasLoanAccount,
            LoanAccountStatus accountStatus
    ) {
    }

    public int[] splitProportionally(int total, int[] weights) {
        int weightSum = Arrays.stream(weights).sum();
        if (weightSum == 0) {
            return new int[weights.length];
        }
        int[] result = new int[weights.length];
        int assigned = 0;
        for (int i = 0; i < weights.length; i++) {
            result[i] = (int) ((long) total * weights[i] / weightSum);
            assigned += result[i];
        }
        result[result.length - 1] += total - assigned;
        return result;
    }
}
