package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanDelinquencyBucket;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import com.bhawana.lms.repo.LspRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HomeDashboardService {

    private final LspRepository lspRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;

    public HomeDashboardService(
            LspRepository lspRepository,
            LoanAccountRepository loanAccountRepository,
            LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository
    ) {
        this.lspRepository = lspRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanRepaymentScheduleInstallmentRepository = loanRepaymentScheduleInstallmentRepository;
    }

    @Transactional(readOnly = true)
    public HomeDashboardSummary getSummary() {
        List<Lsp> lsps = lspRepository.findAll().stream()
                .sorted(Comparator.comparing(Lsp::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<LoanAccount> loanAccounts = loanAccountRepository.findAll();
        Map<UUID, List<LoanRepaymentScheduleInstallment>> installmentsByLoanAccountId =
                loanRepaymentScheduleInstallmentRepository.findAll().stream()
                        .collect(Collectors.groupingBy(installment -> installment.getLoanAccount().getId()));

        List<AccountSnapshot> accountSnapshots = loanAccounts.stream()
                .map(loanAccount -> toAccountSnapshot(
                        loanAccount,
                        installmentsByLoanAccountId.getOrDefault(loanAccount.getId(), List.of())
                ))
                .toList();

        BigDecimal totalDisbursedAmount = accountSnapshots.stream()
                .map(AccountSnapshot::disbursedAmount)
                .reduce(zeroCurrency(), BigDecimal::add);
        BigDecimal totalOutstandingAmount = accountSnapshots.stream()
                .map(AccountSnapshot::outstandingAmount)
                .reduce(zeroCurrency(), BigDecimal::add);
        BigDecimal dpd90PlusAmount = accountSnapshots.stream()
                .filter(AccountSnapshot::dpd90Plus)
                .map(AccountSnapshot::outstandingAmount)
                .reduce(zeroCurrency(), BigDecimal::add);
        long dpd90PlusLoanCount = accountSnapshots.stream()
                .filter(AccountSnapshot::dpd90Plus)
                .count();

        List<LspBreakdown> lspBreakdown = lsps.stream()
                .map(lsp -> toLspBreakdown(
                        lsp,
                        accountSnapshots.stream()
                                .filter(snapshot -> snapshot.lspId().equals(lsp.getId()))
                                .toList(),
                        totalDisbursedAmount,
                        dpd90PlusAmount
                ))
                .toList();

        return new HomeDashboardSummary(
                scaleCurrency(totalDisbursedAmount),
                scaleCurrency(totalOutstandingAmount),
                scaleCurrency(dpd90PlusAmount),
                dpd90PlusLoanCount,
                lspBreakdown
        );
    }

    private AccountSnapshot toAccountSnapshot(
            LoanAccount loanAccount,
            List<LoanRepaymentScheduleInstallment> installments
    ) {
        BigDecimal disbursedAmount = loanAccount.getDisbursedAt() == null
                ? zeroCurrency()
                : scaleCurrency(loanAccount.getPrincipalAmount());
        BigDecimal outstandingAmount = installments.stream()
                .map(LoanRepaymentScheduleInstallment::getOutstandingAmount)
                .reduce(zeroCurrency(), BigDecimal::add);
        int maxDaysPastDue = installments.stream()
                .mapToInt(installment -> LoanApplicationService.calculateDaysPastDue(installment, LocalDate.now(ZoneOffset.UTC)))
                .max()
                .orElse(0);
        LoanDelinquencyBucket bucket = LoanApplicationService.resolveDelinquencyBucket(maxDaysPastDue);

        return new AccountSnapshot(
                loanAccount.getId(),
                loanAccount.getLsp().getId(),
                scaleCurrency(disbursedAmount),
                scaleCurrency(outstandingAmount),
                bucket == LoanDelinquencyBucket.DPD_90_PLUS
        );
    }

    private LspBreakdown toLspBreakdown(
            Lsp lsp,
            List<AccountSnapshot> snapshots,
            BigDecimal totalDisbursedAmount,
            BigDecimal totalDpd90PlusAmount
    ) {
        BigDecimal disbursedAmount = snapshots.stream()
                .map(AccountSnapshot::disbursedAmount)
                .reduce(zeroCurrency(), BigDecimal::add);
        BigDecimal outstandingAmount = snapshots.stream()
                .map(AccountSnapshot::outstandingAmount)
                .reduce(zeroCurrency(), BigDecimal::add);
        BigDecimal dpd90PlusAmount = snapshots.stream()
                .filter(AccountSnapshot::dpd90Plus)
                .map(AccountSnapshot::outstandingAmount)
                .reduce(zeroCurrency(), BigDecimal::add);
        long dpd90PlusLoanCount = snapshots.stream()
                .filter(AccountSnapshot::dpd90Plus)
                .count();

        return new LspBreakdown(
                lsp.getId().toString(),
                lsp.getCode(),
                lsp.getName(),
                scaleCurrency(disbursedAmount),
                scaleCurrency(outstandingAmount),
                scaleCurrency(dpd90PlusAmount),
                dpd90PlusLoanCount,
                percentage(disbursedAmount, totalDisbursedAmount),
                percentage(dpd90PlusAmount, totalDpd90PlusAmount)
        );
    }

    private static BigDecimal zeroCurrency() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaleCurrency(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percentage(BigDecimal value, BigDecimal total) {
        if (total.compareTo(BigDecimal.ZERO) <= 0 || value.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return value.multiply(BigDecimal.valueOf(100))
                .divide(total, 2, RoundingMode.HALF_UP);
    }

    private record AccountSnapshot(
            UUID loanAccountId,
            UUID lspId,
            BigDecimal disbursedAmount,
            BigDecimal outstandingAmount,
            boolean dpd90Plus
    ) {
    }

    public record HomeDashboardSummary(
            BigDecimal totalDisbursedAmount,
            BigDecimal totalOutstandingAmount,
            BigDecimal dpd90PlusAmount,
            long dpd90PlusLoanCount,
            List<LspBreakdown> lspBreakdown
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
            BigDecimal shareOfDpd90PlusPercent
    ) {
    }
}
