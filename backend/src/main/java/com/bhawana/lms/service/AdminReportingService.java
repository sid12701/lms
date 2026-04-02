package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import com.bhawana.lms.repo.LspRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminReportingService {

    private final LoanAccountRepository loanAccountRepository;
    private final LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;
    private final LspRepository lspRepository;

    public AdminReportingService(
            LoanAccountRepository loanAccountRepository,
            LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository,
            LspRepository lspRepository
    ) {
        this.loanAccountRepository = loanAccountRepository;
        this.loanRepaymentScheduleInstallmentRepository = loanRepaymentScheduleInstallmentRepository;
        this.lspRepository = lspRepository;
    }

    @Transactional(readOnly = true)
    public List<PortfolioMisRow> buildPortfolioMisReport(
            UUID lspId,
            LocalDate disbursalDateFrom,
            LocalDate disbursalDateTo
    ) {
        if (lspId != null && !lspRepository.existsById(lspId)) {
            throw new IllegalArgumentException("Unknown LSP id: " + lspId);
        }
        if (disbursalDateFrom != null && disbursalDateTo != null && disbursalDateFrom.isAfter(disbursalDateTo)) {
            throw new IllegalArgumentException("disbursalDateFrom cannot be after disbursalDateTo.");
        }

        return loanAccountRepository.findAll().stream()
                .filter(loanAccount -> lspId == null || loanAccount.getLsp().getId().equals(lspId))
                .map(loanAccount -> toPortfolioMisRow(
                        loanAccount,
                        buildDelinquencySummary(loanAccount)
                ))
                .filter(row -> isWithinDisbursalRange(row.disbursalDate(), disbursalDateFrom, disbursalDateTo))
                .sorted(Comparator
                        .comparing(PortfolioMisRow::lspName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(PortfolioMisRow::disbursalDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(PortfolioMisRow::applicationCreatedAt, Comparator.reverseOrder()))
                .toList();
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
                "portfolio-mis-" + LocalDate.now(ZoneOffset.UTC) + ".csv",
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

    private PortfolioMisRow toPortfolioMisRow(LoanAccount loanAccount, DelinquencySnapshot delinquencySnapshot) {
        LocalDate disbursalDate = loanAccount.getDisbursedAt() == null
                ? null
                : loanAccount.getDisbursedAt().atZone(ZoneOffset.UTC).toLocalDate();

        return new PortfolioMisRow(
                loanAccount.getLsp().getCode(),
                loanAccount.getLsp().getName(),
                loanAccount.getLoanApplication().getId().toString(),
                loanAccount.getLoanApplication().getExternalLoanId(),
                loanAccount.getBorrower().getFullName(),
                loanAccount.getLoanProduct().getCode(),
                loanAccount.getLoanProduct().getName(),
                loanAccount.getAccountNumber(),
                loanAccount.getPrincipalAmount(),
                loanAccount.getStatus().name(),
                disbursalDate,
                delinquencySnapshot.bucket(),
                delinquencySnapshot.overdueAmount(),
                loanAccount.getClosureReason() == null ? null : loanAccount.getClosureReason().name(),
                loanAccount.getClosedAt() == null ? null : loanAccount.getClosedAt().atZone(ZoneOffset.UTC).toLocalDate(),
                loanAccount.getLoanApplication().getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate()
        );
    }

    private DelinquencySnapshot buildDelinquencySummary(LoanAccount loanAccount) {
        List<LoanRepaymentScheduleInstallment> installments =
                loanRepaymentScheduleInstallmentRepository.findByLoanAccount_IdOrderByInstallmentNumberAsc(loanAccount.getId());
        if (installments.isEmpty()) {
            return new DelinquencySnapshot("CURRENT", BigDecimal.ZERO.setScale(2));
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int maxDaysPastDue = installments.stream()
                .mapToInt(installment -> LoanApplicationService.calculateDaysPastDue(installment, today))
                .max()
                .orElse(0);
        BigDecimal overdueAmount = installments.stream()
                .filter(installment -> LoanApplicationService.calculateDaysPastDue(installment, today) > 0)
                .map(LoanRepaymentScheduleInstallment::getOutstandingAmount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);

        return new DelinquencySnapshot(
                LoanApplicationService.resolveDelinquencyBucket(maxDaysPastDue).name(),
                overdueAmount.setScale(2)
        );
    }

    private boolean isWithinDisbursalRange(
            LocalDate disbursalDate,
            LocalDate disbursalDateFrom,
            LocalDate disbursalDateTo
    ) {
        if (disbursalDateFrom == null && disbursalDateTo == null) {
            return true;
        }
        if (disbursalDate == null) {
            return false;
        }
        if (disbursalDateFrom != null && disbursalDate.isBefore(disbursalDateFrom)) {
            return false;
        }
        return disbursalDateTo == null || !disbursalDate.isAfter(disbursalDateTo);
    }

    private record DelinquencySnapshot(
            String bucket,
            BigDecimal overdueAmount
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
            LocalDate applicationCreatedAt
    ) {
    }

    public record GeneratedReport(
            String fileName,
            String mediaType,
            byte[] content
    ) {
    }

    public static String buildPortfolioMisCsv(List<PortfolioMisRow> rows) {
        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",",
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
                "Closed Date"
        )).append('\n');

        for (PortfolioMisRow row : rows) {
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
                    .append(toCsvCell(row.closedDate()))
                    .append('\n');
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
