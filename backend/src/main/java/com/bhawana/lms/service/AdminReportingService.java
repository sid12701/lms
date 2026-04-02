package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import com.bhawana.lms.repo.LspRepository;
import java.math.BigDecimal;
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
}
