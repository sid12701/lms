package com.bhawana.lms.service;

import com.bhawana.lms.common.money.Money;
import com.bhawana.lms.common.pii.AadhaarMasking;
import com.bhawana.lms.common.pii.BankAccountMasking;
import com.bhawana.lms.common.pii.PanMasking;
import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountClosureReason;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanForeclosureQuote;
import com.bhawana.lms.domain.LoanForeclosureQuoteStatus;
import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.config.BusinessCalendar;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.repo.LoanForeclosureQuoteRepository;
import com.bhawana.lms.repo.PortfolioMisReadRepository;
import com.bhawana.lms.repo.PortfolioMisSummaryAggregate;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import com.bhawana.lms.repo.LspRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminReportingService {

    static final int EXPORT_BATCH_SIZE = PortfolioMisReadRepository.EXPORT_BATCH_SIZE;

    private static final Logger log = LoggerFactory.getLogger(AdminReportingService.class);

    private final PortfolioMisReadRepository portfolioMisReadRepository;
    private final LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;
    private final LoanForeclosureQuoteRepository loanForeclosureQuoteRepository;
    private final LspRepository lspRepository;
    private final BusinessCalendar businessCalendar;

    public AdminReportingService(
            PortfolioMisReadRepository portfolioMisReadRepository,
            LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository,
            LoanForeclosureQuoteRepository loanForeclosureQuoteRepository,
            LspRepository lspRepository,
            BusinessCalendar businessCalendar
    ) {
        this.portfolioMisReadRepository = portfolioMisReadRepository;
        this.loanRepaymentScheduleInstallmentRepository = loanRepaymentScheduleInstallmentRepository;
        this.loanForeclosureQuoteRepository = loanForeclosureQuoteRepository;
        this.lspRepository = lspRepository;
        this.businessCalendar = businessCalendar;
    }

    @Transactional(readOnly = true)
    public List<PortfolioMisRow> buildPortfolioMisReport(
            UUID lspId,
            LocalDate disbursalDateFrom,
            LocalDate disbursalDateTo
    ) {
        validateFilters(lspId, disbursalDateFrom, disbursalDateTo);
        long startedAt = System.nanoTime();
        List<PortfolioMisRow> rows = new ArrayList<>();
        forEachExportBatch(
                lspId,
                toStartOfDayInclusive(disbursalDateFrom),
                toEndOfDayExclusive(disbursalDateTo),
                batch -> rows.addAll(buildRowsForAccounts(batch))
        );
        log.debug(
                "portfolio_mis_export_query completed lspId={} disbursalDateFrom={} disbursalDateTo={} resultCount={} durationMs={}",
                lspId,
                disbursalDateFrom,
                disbursalDateTo,
                rows.size(),
                elapsedMillis(startedAt)
        );
        return rows;
    }

    @Transactional(readOnly = true)
    public PortfolioMisPage getPortfolioMisPage(
            UUID lspId,
            LocalDate disbursalDateFrom,
            LocalDate disbursalDateTo,
            int page,
            int size
    ) {
        validateFilters(lspId, disbursalDateFrom, disbursalDateTo);
        if (page < 0) page = 0;
        if (size <= 0) size = 50;
        if (size > 500) size = 500;

        long startedAt = System.nanoTime();
        PortfolioMisReadRepository.PortfolioMisAccountPage accountPage = portfolioMisReadRepository.findAccountsPage(
                lspId,
                toStartOfDayInclusive(disbursalDateFrom),
                toEndOfDayExclusive(disbursalDateTo),
                page,
                size
        );
        log.debug(
                "portfolio_mis_preview_query completed lspId={} disbursalDateFrom={} disbursalDateTo={} page={} size={} totalElements={} durationMs={}",
                lspId,
                disbursalDateFrom,
                disbursalDateTo,
                page,
                size,
                accountPage.totalElements(),
                elapsedMillis(startedAt)
        );

        List<PortfolioMisRow> rows = buildRowsForAccounts(accountPage.content());
        int totalElements = accountPage.totalElements() > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (int) accountPage.totalElements();
        return new PortfolioMisPage(rows, totalElements, page, size);
    }

    private List<PortfolioMisRow> buildRowsForAccounts(List<LoanAccount> accounts) {
        if (accounts.isEmpty()) {
            return List.of();
        }
        List<UUID> accountIds = accounts.stream().map(LoanAccount::getId).toList();

        Map<UUID, List<LoanRepaymentScheduleInstallment>> installmentsByAccount =
                loanRepaymentScheduleInstallmentRepository.findByLoanAccount_IdIn(accountIds).stream()
                        .collect(Collectors.groupingBy(inst -> inst.getLoanAccount().getId()));

        List<UUID> foreclosedAccountIds = accounts.stream()
                .filter(a -> a.getStatus() == LoanAccountStatus.FORECLOSED)
                .map(LoanAccount::getId)
                .toList();
        Map<UUID, LoanForeclosureQuote> executedQuotesByAccount = foreclosedAccountIds.isEmpty()
                ? Map.of()
                : loanForeclosureQuoteRepository
                        .findByStatusAndLoanAccount_IdIn(LoanForeclosureQuoteStatus.EXECUTED, foreclosedAccountIds)
                        .stream()
                        .collect(Collectors.toMap(
                                q -> q.getLoanAccount().getId(),
                                q -> q,
                                (left, right) -> left
                        ));

        return accounts.stream()
                .map(loanAccount -> toPortfolioMisRow(
                        loanAccount,
                        buildDelinquencySummary(loanAccount, installmentsByAccount.getOrDefault(loanAccount.getId(), List.of())),
                        installmentsByAccount.getOrDefault(loanAccount.getId(), List.of()),
                        executedQuotesByAccount.get(loanAccount.getId())
                ))
                .toList();
    }

    public record PortfolioMisPage(
            List<PortfolioMisRow> content,
            int totalElements,
            int page,
            int size
    ) {
    }

    public record PortfolioMisSummary(
            BigDecimal totalDisbursed,
            long activeLoanCount,
            BigDecimal weightedAvgInterestRate,
            BigDecimal portfolioAtRiskPct,
            long totalLoanCount
    ) {
    }

    @Transactional(readOnly = true)
    public PortfolioMisSummary getPortfolioMisSummary(
            UUID lspId,
            LocalDate disbursalDateFrom,
            LocalDate disbursalDateTo
    ) {
        validateFilters(lspId, disbursalDateFrom, disbursalDateTo);
        LocalDate par30Cutoff = businessCalendar.today().minusDays(30);
        long startedAt = System.nanoTime();
        PortfolioMisSummaryAggregate aggregate = portfolioMisReadRepository.summarize(
                lspId,
                toStartOfDayInclusive(disbursalDateFrom),
                toEndOfDayExclusive(disbursalDateTo),
                par30Cutoff
        );
        log.debug(
                "portfolio_mis_summary_query completed lspId={} disbursalDateFrom={} disbursalDateTo={} durationMs={}",
                lspId,
                disbursalDateFrom,
                disbursalDateTo,
                elapsedMillis(startedAt)
        );

        BigDecimal totalDisbursed = defaultBigDecimal(aggregate.totalDisbursed());
        long activeCount = defaultLong(aggregate.activeLoanCount());
        BigDecimal weightedSum = defaultBigDecimal(aggregate.weightedInterestAmount());
        BigDecimal weightedPrincipal = defaultBigDecimal(aggregate.weightedPrincipalAmount());
        BigDecimal atRiskPrincipal = defaultBigDecimal(aggregate.atRiskPrincipal());
        long totalCount = defaultLong(aggregate.totalLoanCount());

        BigDecimal weightedAvg = weightedPrincipal.compareTo(BigDecimal.ZERO) > 0
                ? weightedSum.divide(weightedPrincipal, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2);

        BigDecimal parPct = totalDisbursed.compareTo(BigDecimal.ZERO) > 0
                ? atRiskPrincipal.multiply(BigDecimal.valueOf(100))
                        .divide(totalDisbursed, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2);

        return new PortfolioMisSummary(totalDisbursed, activeCount, weightedAvg, parPct, totalCount);
    }

    @Transactional(readOnly = true)
    public GeneratedReport generatePortfolioMisCsv(
            UUID lspId,
            LocalDate disbursalDateFrom,
            LocalDate disbursalDateTo
    ) {
        validateFilters(lspId, disbursalDateFrom, disbursalDateTo);
        long startedAt = System.nanoTime();
        java.time.Instant disbursalFrom = toStartOfDayInclusive(disbursalDateFrom);
        java.time.Instant disbursalTo = toEndOfDayExclusive(disbursalDateTo);

        int maxInstallments = portfolioMisReadRepository.findMaxInstallmentCountForExport(
                lspId,
                disbursalFrom,
                disbursalTo
        );
        StringBuilder csv = new StringBuilder();
        PortfolioMisCsvWriter.writeHeader(csv, maxInstallments);

        int[] rowCount = {0};
        forEachExportBatch(lspId, disbursalFrom, disbursalTo, batch -> {
            List<PortfolioMisRow> rows = buildRowsForAccounts(batch);
            rowCount[0] += rows.size();
            PortfolioMisCsvWriter.appendRows(csv, rows, maxInstallments);
        });

        log.debug(
                "portfolio_mis_export_stream completed lspId={} disbursalDateFrom={} disbursalDateTo={} resultCount={} durationMs={}",
                lspId,
                disbursalDateFrom,
                disbursalDateTo,
                rowCount[0],
                elapsedMillis(startedAt)
        );

        String csvText = csv.toString();
        return new GeneratedReport(
                "portfolio-mis-" + businessCalendar.today() + ".csv",
                "text/csv;charset=UTF-8",
                csvText.getBytes(StandardCharsets.UTF_8)
        );
    }

    @FunctionalInterface
    private interface ExportBatchConsumer {
        void accept(List<LoanAccount> batch);
    }

    private void forEachExportBatch(
            UUID lspId,
            java.time.Instant disbursalFrom,
            java.time.Instant disbursalTo,
            ExportBatchConsumer consumer
    ) {
        UUID lastExclusiveId = null;
        while (true) {
            List<UUID> batchIds = portfolioMisReadRepository.findAccountIdsForExportBatch(
                    lspId,
                    disbursalFrom,
                    disbursalTo,
                    lastExclusiveId,
                    EXPORT_BATCH_SIZE
            );
            if (batchIds.isEmpty()) {
                return;
            }
            consumer.accept(portfolioMisReadRepository.findAccountsByIds(batchIds));
            lastExclusiveId = batchIds.getLast();
            if (batchIds.size() < EXPORT_BATCH_SIZE) {
                return;
            }
        }
    }

    @Transactional(readOnly = true)
    public Lsp getOptionalLsp(UUID lspId) {
        if (lspId == null) {
            return null;
        }
        return lspRepository.findById(lspId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown LSP id: " + lspId));
    }

    private void validateFilters(UUID lspId, LocalDate disbursalDateFrom, LocalDate disbursalDateTo) {
        if (lspId != null && !lspRepository.existsById(lspId)) {
            throw new ResourceNotFoundException("Unknown LSP id: " + lspId);
        }
        if (disbursalDateFrom != null && disbursalDateTo != null && disbursalDateFrom.isAfter(disbursalDateTo)) {
            throw new BusinessRuleViolationException(
                    "INVALID_DISBURSAL_DATE_RANGE",
                    "disbursalDateFrom cannot be after disbursalDateTo.",
                    Map.of("disbursalDateFrom", "cannot be after disbursalDateTo")
            );
        }
    }

    private static java.time.Instant toStartOfDayInclusive(LocalDate value) {
        return value == null ? null : value.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static java.time.Instant toEndOfDayExclusive(LocalDate value) {
        return value == null ? null : value.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static BigDecimal defaultBigDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static long defaultLong(Long value) {
        return value == null ? 0L : value;
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private PortfolioMisRow toPortfolioMisRow(
            LoanAccount loanAccount,
            DelinquencySnapshot delinquencySnapshot,
            List<LoanRepaymentScheduleInstallment> installments,
            LoanForeclosureQuote executedQuote
    ) {
        LocalDate disbursalDate = loanAccount.getDisbursedAt() == null
                ? null
                : loanAccount.getDisbursedAt().atZone(ZoneOffset.UTC).toLocalDate();

        LoanApplication application = loanAccount.getLoanApplication();
        LoanProduct product = loanAccount.getLoanProduct();
        Borrower borrower = loanAccount.getBorrower();

        // Loan year: year portion of disbursal date (or application creation year)
        Integer loanYear = disbursalDate != null ? disbursalDate.getYear()
                : application.getCreatedAt().atZone(ZoneOffset.UTC).getYear();

        // ADR 0004: report the fee actually charged at disbursement (persisted on the loan account).
        // Legacy rows predating the change carry no persisted fee — no fee was deducted at disbursal,
        // so MIS shows zero fee and net disbursed equals gross principal (no calculator fiction).
        BigDecimal processingFeeAmount = resolveProcessingFeeAmount(loanAccount);
        BigDecimal netDisbursedAmount = Money.scale(loanAccount.getPrincipalAmount().subtract(processingFeeAmount));

        // Per-EMI amount: use installment amount from the first installment, or compute principal/tenure
        BigDecimal perEmiAmount;
        if (!installments.isEmpty()) {
            perEmiAmount = installments.get(0).getInstallmentAmount();
        } else if (loanAccount.getTenureMonths() > 0) {
            perEmiAmount = loanAccount.getPrincipalAmount()
                    .divide(BigDecimal.valueOf(loanAccount.getTenureMonths()), 2, RoundingMode.HALF_UP);
        } else {
            perEmiAmount = BigDecimal.ZERO.setScale(2);
        }

        // Installment snapshots sorted by number
        List<InstallmentSnapshot> installmentSnapshots = installments.stream()
                .sorted(Comparator.comparingInt(LoanRepaymentScheduleInstallment::getInstallmentNumber))
                .map(inst -> new InstallmentSnapshot(
                        inst.getInstallmentNumber(),
                        inst.getDueDate(),
                        inst.getInstallmentAmount(),
                        inst.getPaidAmount(),
                        inst.getOutstandingAmount().compareTo(BigDecimal.ZERO) == 0
                ))
                .toList();

        // Loan status display
        String loanStatusDisplay = application.getStatus().name();

        // Foreclosure details
        BigDecimal foreclosedRepaidAmount = null;
        LocalDate foreclosureDate = null;
        if (executedQuote != null) {
            foreclosedRepaidAmount = executedQuote.getSettlementAmount();
            foreclosureDate = executedQuote.getExecutedAt() != null
                    ? executedQuote.getExecutedAt().atZone(ZoneOffset.UTC).toLocalDate()
                    : executedQuote.getEffectiveDate();
        }

        // Normal closure date
        LocalDate normalClosureDate = null;
        if (loanAccount.getClosureReason() == LoanAccountClosureReason.FULLY_REPAID
                && loanAccount.getClosedAt() != null) {
            normalClosureDate = loanAccount.getClosedAt().atZone(ZoneOffset.UTC).toLocalDate();
        }

        // Days past due (max across installments)
        int daysPastDue = delinquencySnapshot.maxDaysPastDue();

        // Borrower address: combine address lines
        String address = buildAddress(borrower);

        return new PortfolioMisRow(
                loanAccount.getLsp().getCode(),
                loanAccount.getLsp().getName(),
                application.getId().toString(),
                application.getExternalLoanId(),
                borrower.getFullName(),
                product.getCode(),
                product.getName(),
                loanAccount.getAccountNumber(),
                loanAccount.getPrincipalAmount(),
                loanAccount.getStatus().name(),
                disbursalDate,
                delinquencySnapshot.bucket(),
                delinquencySnapshot.overdueAmount(),
                loanAccount.getClosureReason() == null ? null : loanAccount.getClosureReason().name(),
                loanAccount.getClosedAt() == null ? null : loanAccount.getClosedAt().atZone(ZoneOffset.UTC).toLocalDate(),
                application.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate(),
                loanYear,
                processingFeeAmount,
                loanAccount.getPrincipalAmount(),
                netDisbursedAmount,
                loanAccount.getLoanProductVersion().getInterestRate(),
                loanAccount.getTenureMonths(),
                borrower.getId().toString(),
                perEmiAmount,
                installmentSnapshots,
                loanStatusDisplay,
                foreclosedRepaidAmount,
                foreclosureDate,
                normalClosureDate,
                daysPastDue,
                borrower.getFullName(),
                address,
                borrower.getAddressZipCode(),
                borrower.getState(),
                borrower.getIfscCode(),
                BankAccountMasking.mask(borrower.getBankAccountNumber()),
                borrower.getGender(),
                // Gap #1 + Gap #10 — every leaving surface must mask aadhaar (and bank/PAN in MIS).
                AadhaarMasking.mask(borrower.getAadharNumber()),
                PanMasking.mask(borrower.getPan()),
                borrower.getEmploymentType(),
                borrower.getMonthlyIncome()
        );
    }

    private static String buildAddress(Borrower borrower) {
        List<String> parts = new ArrayList<>();
        if (borrower.getAddressLine1() != null) {
            parts.add(borrower.getAddressLine1());
        }
        if (borrower.getAddressLine2() != null) {
            parts.add(borrower.getAddressLine2());
        }
        if (borrower.getCity() != null) {
            parts.add(borrower.getCity());
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private DelinquencySnapshot buildDelinquencySummary(
            LoanAccount loanAccount,
            List<LoanRepaymentScheduleInstallment> installments
    ) {
        if (installments.isEmpty()) {
            return new DelinquencySnapshot("CURRENT", BigDecimal.ZERO.setScale(2), 0);
        }

        LocalDate today = businessCalendar.today();
        int maxDaysPastDue = 0;
        BigDecimal overdueAmount = BigDecimal.ZERO.setScale(2);
        for (LoanRepaymentScheduleInstallment installment : installments) {
            int dpd = LoanDelinquencySupport.calculateDaysPastDue(installment, today);
            if (dpd > maxDaysPastDue) {
                maxDaysPastDue = dpd;
            }
            if (dpd > 0) {
                overdueAmount = overdueAmount.add(installment.getOutstandingAmount());
            }
        }

        return new DelinquencySnapshot(
                LoanDelinquencySupport.resolveDelinquencyBucket(maxDaysPastDue).name(),
                overdueAmount.setScale(2),
                maxDaysPastDue
        );
    }

    private record DelinquencySnapshot(
            String bucket,
            BigDecimal overdueAmount,
            int maxDaysPastDue
    ) {
    }

    public record InstallmentSnapshot(
            int installmentNumber,
            LocalDate dueDate,
            BigDecimal installmentAmount,
            BigDecimal paidAmount,
            boolean received
    ) {
    }

    /**
     * Fee charged at disbursement: the value persisted on the loan account when it was disbursed
     * (ADR 0004). Legacy loans disbursed before that change carry no persisted fee — the borrower
     * received the full principal with no fee deducted, so MIS reports zero rather than a synthetic
     * calculator figure that would misstate cash actually disbursed.
     */
    static BigDecimal resolveProcessingFeeAmount(LoanAccount loanAccount) {
        BigDecimal persisted = loanAccount.getProcessingFeeAmount();
        if (persisted != null) {
            return Money.scale(persisted);
        }
        return Money.scale(BigDecimal.ZERO);
    }

    public record PortfolioMisRow(
            String lspCode,
            String lspName,
            String applicationId,
            String externalLoanId,
            String borrowerFullName,
            String productCode,
            String productName,
            String accountNumber,
            BigDecimal principalAmount,
            String accountStatus,
            LocalDate disbursalDate,
            String delinquencyBucket,
            BigDecimal overdueAmount,
            String closureReason,
            LocalDate closedDate,
            LocalDate applicationCreatedAt,
            Integer loanYear,
            BigDecimal processingFeeAmount,
            BigDecimal disbursalAmount,
            BigDecimal netDisbursedAmount,
            BigDecimal interestRate,
            int tenureMonths,
            String borrowerId,
            BigDecimal perEmiAmount,
            List<InstallmentSnapshot> installments,
            String loanStatusDisplay,
            BigDecimal foreclosedRepaidAmount,
            LocalDate foreclosureDate,
            LocalDate normalClosureDate,
            int daysPastDue,
            String customerName,
            String address,
            String zipCode,
            String borrowerState,
            String ifscCode,
            String bankAccountNumber,
            String gender,
            String aadharNumber,
            String panNumber,
            String profession,
            BigDecimal income
    ) {
    }

    public record GeneratedReport(
            String fileName,
            String mediaType,
            byte[] content
    ) {
    }
}
