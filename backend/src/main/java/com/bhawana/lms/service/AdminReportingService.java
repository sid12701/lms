package com.bhawana.lms.service;

import com.bhawana.lms.common.web.BusinessRuleViolationException;
import com.bhawana.lms.common.web.ResourceNotFoundException;
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
import java.util.Collections;
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
        List<LoanAccount> accounts = portfolioMisReadRepository.findAccountsForExport(
                lspId,
                toStartOfDayInclusive(disbursalDateFrom),
                toEndOfDayExclusive(disbursalDateTo)
        );
        log.debug(
                "portfolio_mis_export_query completed lspId={} disbursalDateFrom={} disbursalDateTo={} resultCount={} durationMs={}",
                lspId,
                disbursalDateFrom,
                disbursalDateTo,
                accounts.size(),
                elapsedMillis(startedAt)
        );
        return buildRowsForAccounts(accounts);
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
        List<PortfolioMisRow> rows = buildPortfolioMisReport(lspId, disbursalDateFrom, disbursalDateTo);
        String csv = buildPortfolioMisCsv(rows);
        return new GeneratedReport(
                "portfolio-mis-" + businessCalendar.today() + ".csv",
                "text/csv;charset=UTF-8",
                csv.getBytes(StandardCharsets.UTF_8)
        );
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

        // Processing fee amount = principal * processingFeeRate / 100
        BigDecimal processingFeeAmount = loanAccount.getPrincipalAmount()
                .multiply(product.getProcessingFeeRate())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

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
                product.getInterestRate(),
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
                borrower.getBankAccountNumber(),
                borrower.getGender(),
                maskAadhaar(borrower.getAadharNumber()),
                borrower.getPan(),
                borrower.getEmploymentType(),
                borrower.getMonthlyIncome()
        );
    }

    /**
     * Gap #1 + Gap #10 — every leaving surface must mask aadhaar. Format:
     * {@code XXXXXXXX<last4>}. Matches {@code BorrowerAdminController.maskAadhar}
     * so the MIS preview/CSV emit the same shape as the borrower-admin API.
     */
    private static String maskAadhaar(String aadhaar) {
        if (aadhaar == null) {
            return null;
        }
        String digits = aadhaar.replaceAll("\\s", "");
        if (digits.length() < 4) {
            return "XXXXXXXX";
        }
        return "XXXXXXXX" + digits.substring(digits.length() - 4);
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
            int dpd = LoanApplicationService.calculateDaysPastDue(installment, today);
            if (dpd > maxDaysPastDue) {
                maxDaysPastDue = dpd;
            }
            if (dpd > 0) {
                overdueAmount = overdueAmount.add(installment.getOutstandingAmount());
            }
        }

        return new DelinquencySnapshot(
                LoanApplicationService.resolveDelinquencyBucket(maxDaysPastDue).name(),
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

    public static String buildPortfolioMisCsv(List<PortfolioMisRow> rows) {
        // First pass: determine max installments across all rows
        int maxInstallments = rows.stream()
                .mapToInt(row -> row.installments() == null ? 0 : row.installments().size())
                .max()
                .orElse(0);

        StringBuilder csv = new StringBuilder();

        // Fixed header columns
        List<String> headerColumns = new ArrayList<>(List.of(
                "LSP Code",
                "LSP Name",
                "Application ID",
                "External Loan ID",
                "Borrower Name",
                "Product Code",
                "Product Name",
                "Account Number",
                "Principal Amount",
                "Account Status",
                "Disbursal Date",
                "Delinquency Bucket",
                "Overdue Amount",
                "Closure Reason",
                "Closed Date",
                "Application Created At",
                "Loan Year",
                "Processing Fee",
                "Disbursal Amount",
                "Interest Rate",
                "Tenure Months",
                "Borrower ID",
                "Per EMI Amount",
                "Loan Status",
                "Foreclosed Repaid Amount",
                "Foreclosure Date",
                "Normal Closure Date",
                "Days Past Due",
                "Customer Name",
                "Address",
                "Zip Code",
                "State",
                "IFSC Code",
                "Bank Account Number",
                "Gender",
                "Aadhar Number",
                "PAN Number",
                "Profession",
                "Income"
        ));

        // Dynamic EMI columns
        for (int i = 1; i <= maxInstallments; i++) {
            headerColumns.add("EMI " + i + " Due Date");
            headerColumns.add("EMI " + i + " Amount");
            headerColumns.add("EMI " + i + " Paid Amount");
            headerColumns.add("EMI " + i + " Received");
        }

        csv.append(String.join(",", headerColumns)).append('\n');

        for (PortfolioMisRow row : rows) {
            // Fixed columns
            csv.append(toCsvCell(row.lspCode())).append(',')
                    .append(toCsvCell(row.lspName())).append(',')
                    .append(toCsvCell(row.applicationId())).append(',')
                    .append(toCsvCell(row.externalLoanId())).append(',')
                    .append(toCsvCell(row.borrowerFullName())).append(',')
                    .append(toCsvCell(row.productCode())).append(',')
                    .append(toCsvCell(row.productName())).append(',')
                    .append(toCsvCell(row.accountNumber())).append(',')
                    .append(toCsvCell(row.principalAmount())).append(',')
                    .append(toCsvCell(row.accountStatus())).append(',')
                    .append(toCsvCell(row.disbursalDate())).append(',')
                    .append(toCsvCell(row.delinquencyBucket())).append(',')
                    .append(toCsvCell(row.overdueAmount())).append(',')
                    .append(toCsvCell(row.closureReason())).append(',')
                    .append(toCsvCell(row.closedDate())).append(',')
                    .append(toCsvCell(row.applicationCreatedAt())).append(',')
                    .append(toCsvCell(row.loanYear())).append(',')
                    .append(toCsvCell(row.processingFeeAmount())).append(',')
                    .append(toCsvCell(row.disbursalAmount())).append(',')
                    .append(toCsvCell(row.interestRate())).append(',')
                    .append(toCsvCell(row.tenureMonths())).append(',')
                    .append(toCsvCell(row.borrowerId())).append(',')
                    .append(toCsvCell(row.perEmiAmount())).append(',')
                    .append(toCsvCell(row.loanStatusDisplay())).append(',')
                    .append(toCsvCell(row.foreclosedRepaidAmount())).append(',')
                    .append(toCsvCell(row.foreclosureDate())).append(',')
                    .append(toCsvCell(row.normalClosureDate())).append(',')
                    .append(toCsvCell(row.daysPastDue())).append(',')
                    .append(toCsvCell(row.customerName())).append(',')
                    .append(toCsvCell(row.address())).append(',')
                    .append(toCsvCell(row.zipCode())).append(',')
                    .append(toCsvCell(row.borrowerState())).append(',')
                    .append(toCsvCell(row.ifscCode())).append(',')
                    .append(toCsvCell(row.bankAccountNumber())).append(',')
                    .append(toCsvCell(row.gender())).append(',')
                    .append(toCsvCell(row.aadharNumber())).append(',')
                    .append(toCsvCell(row.panNumber())).append(',')
                    .append(toCsvCell(row.profession())).append(',')
                    .append(toCsvCell(row.income()));

            // Dynamic EMI columns
            List<InstallmentSnapshot> installmentList = row.installments() != null
                    ? row.installments()
                    : Collections.emptyList();
            for (int i = 0; i < maxInstallments; i++) {
                if (i < installmentList.size()) {
                    InstallmentSnapshot snap = installmentList.get(i);
                    csv.append(',').append(toCsvCell(snap.dueDate()))
                            .append(',').append(toCsvCell(snap.installmentAmount()))
                            .append(',').append(toCsvCell(snap.paidAmount()))
                            .append(',').append(toCsvCell(snap.received() ? "Yes" : "No"));
                } else {
                    csv.append(",,,," );
                }
            }

            csv.append('\n');
        }

        return csv.toString();
    }

    private static String toCsvCell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }

        String text = value.toString();
        if (!text.contains(",") && !text.contains("\"") && !text.contains("\n")) {
            return text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
