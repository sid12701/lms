package com.bhawana.lms.service;

import com.bhawana.lms.config.BusinessCalendar;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanDelinquencyBucket;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import com.bhawana.lms.common.web.PagedResult;
import com.bhawana.lms.common.web.ResourceNotFoundException;
import com.bhawana.lms.common.web.PaginationResponseBuilder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BorrowerDirectoryService {

    private static final List<LoanAccountStatus> ACTIVE_LOAN_STATUSES = List.of(
            LoanAccountStatus.PENDING_DISBURSEMENT,
            LoanAccountStatus.DISBURSEMENT_REQUESTED,
            LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION,
            LoanAccountStatus.DISBURSEMENT_FAILED,
            LoanAccountStatus.DISBURSED
    );

    private final BorrowerRepository borrowerRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;
    private final BusinessCalendar businessCalendar;

    public BorrowerDirectoryService(
            BorrowerRepository borrowerRepository,
            LoanAccountRepository loanAccountRepository,
            LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository,
            BusinessCalendar businessCalendar
    ) {
        this.borrowerRepository = borrowerRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanRepaymentScheduleInstallmentRepository = loanRepaymentScheduleInstallmentRepository;
        this.businessCalendar = businessCalendar;
    }

    /**
     * Paginated, optionally-searched list of borrowers for the admin borrowers
     * directory page. Search matches a case-insensitive substring across the
     * borrower's `fullName`, `pan`, `mobile`, and `email`. Pagination follows
     * the same `paginationDetails=ON` convention as the loan-application list.
     */
    @Transactional(readOnly = true)
    public PagedResult<Borrower> listBorrowers(
            String query,
            Integer offset,
            Integer limit,
            boolean includePaginationDetails
    ) {
        String normalizedQuery = normalizeBorrowerSearchQuery(query);
        boolean paginationRequested = PaginationResponseBuilder.isPaginationRequested(
                offset, limit, includePaginationDetails);
        int resolvedOffset = PaginationResponseBuilder.resolveOffset(offset, paginationRequested);
        int resolvedLimit = PaginationResponseBuilder.resolveLimit(limit, paginationRequested);

        Sort sort = Sort.by(Sort.Direction.ASC, "fullName").and(Sort.by(Sort.Direction.ASC, "id"));

        if (!paginationRequested) {
            List<Borrower> all = listBorrowerPage(
                    normalizedQuery,
                    PageRequest.of(0, Integer.MAX_VALUE, sort)
            ).getContent();
            return new PagedResult<>(all, all.size(), 0, all.size());
        }

        int pageNumber = resolvedOffset / Math.max(resolvedLimit, 1);
        Page<Borrower> page = listBorrowerPage(
                normalizedQuery,
                PageRequest.of(pageNumber, Math.max(resolvedLimit, 1), sort)
        );
        return new PagedResult<>(page.getContent(), page.getTotalElements(), resolvedOffset, resolvedLimit);
    }

    private Page<Borrower> listBorrowerPage(String normalizedQuery, PageRequest pageRequest) {
        if (normalizedQuery == null) {
            return borrowerRepository.findAllBorrowers(pageRequest);
        }
        return borrowerRepository.searchBorrowers(normalizedQuery, pageRequest);
    }

    /** Blank search means "return all" — keep null out of TRIM/LIKE so PostgreSQL binds text, not bytea. */
    private static String normalizeBorrowerSearchQuery(String query) {
        if (query == null) {
            return null;
        }
        String trimmed = query.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Transactional(readOnly = true)
    public BorrowerDetailView getBorrowerDetail(UUID borrowerId) {
        Borrower borrower = borrowerRepository.findById(borrowerId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown borrower id: " + borrowerId));

        List<LoanAccount> loans = loanAccountRepository.findDetailedByBorrower_Id(borrowerId).stream()
                .sorted(Comparator.comparing(LoanAccount::getCreatedAt).reversed())
                .toList();

        List<BorrowerLoanView> loanViews = loans.stream()
                .map(account -> new BorrowerLoanView(
                        account.getId(),
                        account.getLoanApplication() == null ? null : account.getLoanApplication().getId(),
                        account.getAccountNumber(),
                        account.getLsp() == null ? null : account.getLsp().getId(),
                        account.getLsp() == null ? null : account.getLsp().getCode(),
                        account.getLsp() == null ? null : account.getLsp().getName(),
                        account.getLoanProduct() == null ? null : account.getLoanProduct().getCode(),
                        account.getStatus(),
                        account.getPrincipalAmount(),
                        account.getTenureMonths(),
                        account.getApprovedAt(),
                        account.getDisbursedAt(),
                        account.getClosureReason() == null ? null : account.getClosureReason().name(),
                        account.getClosedAt(),
                        account.getClosedByUsername(),
                        account.getCreatedAt()
                ))
                .toList();

        BorrowerDelinquencyAggregate delinquency = computeDelinquencyAggregate(loans);
        return new BorrowerDetailView(borrower, loanViews, delinquency);
    }

    /**
     * Gap #6: server-side aggregate of delinquency across every active loan
     * for a borrower. "Active" means a loan account not in CLOSED, FORECLOSED,
     * or INVALID — i.e. one where future installments can still go overdue.
     *
     * `activeOverdueAmount` sums the outstanding portion of each installment
     * whose due-date is in the past and that has unpaid balance. `maxDaysPastDue`
     * is the worst DPD across those installments, and `bucket` is the
     * corresponding DPD bucket per `LoanApplicationService.resolveDelinquencyBucket`.
     * `overdueLoanCount` counts distinct active loans contributing at least
     * one overdue installment — useful for ops triage where the headline tile
     * is the count of stuck loans rather than the count of stuck installments.
     */
    private BorrowerDelinquencyAggregate computeDelinquencyAggregate(List<LoanAccount> loans) {
        LocalDate today = businessCalendar.today();
        BigDecimal totalOverdue = BigDecimal.ZERO.setScale(2);
        int maxDpd = 0;
        int overdueLoanCount = 0;

        for (LoanAccount account : loans) {
            if (account.getStatus() == null || !ACTIVE_LOAN_STATUSES.contains(account.getStatus())) {
                continue;
            }
            List<LoanRepaymentScheduleInstallment> installments = loanRepaymentScheduleInstallmentRepository
                    .findByLoanAccount_IdOrderByInstallmentNumberAsc(account.getId());
            if (installments.isEmpty()) {
                continue;
            }
            BigDecimal loanOverdue = BigDecimal.ZERO.setScale(2);
            int loanMaxDpd = 0;
            for (LoanRepaymentScheduleInstallment installment : installments) {
                int dpd = LoanApplicationService.calculateDaysPastDue(installment, today);
                if (dpd > 0) {
                    loanOverdue = loanOverdue.add(installment.getOutstandingAmount());
                    if (dpd > loanMaxDpd) {
                        loanMaxDpd = dpd;
                    }
                }
            }
            if (loanOverdue.compareTo(BigDecimal.ZERO) > 0 || loanMaxDpd > 0) {
                overdueLoanCount++;
                totalOverdue = totalOverdue.add(loanOverdue);
                if (loanMaxDpd > maxDpd) {
                    maxDpd = loanMaxDpd;
                }
            }
        }

        LoanDelinquencyBucket bucket = LoanApplicationService.resolveDelinquencyBucket(maxDpd);
        return new BorrowerDelinquencyAggregate(
                totalOverdue.setScale(2),
                maxDpd,
                overdueLoanCount,
                bucket
        );
    }

    public record BorrowerDetailView(
            Borrower borrower,
            List<BorrowerLoanView> loans,
            BorrowerDelinquencyAggregate delinquency
    ) {
    }

    /**
     * Gap #6: aggregate delinquency across the borrower's active loans.
     * Authoritative server-side calculation; avoids client/server drift on
     * bucket definitions.
     */
    public record BorrowerDelinquencyAggregate(
            BigDecimal activeOverdueAmount,
            int maxDaysPastDue,
            int overdueLoanCount,
            LoanDelinquencyBucket bucket
    ) {
    }

    public record BorrowerLoanView(
            UUID loanAccountId,
            UUID applicationId,
            String accountNumber,
            UUID lspId,
            String lspCode,
            String lspName,
            String loanProductCode,
            LoanAccountStatus status,
            BigDecimal principalAmount,
            int tenureMonths,
            java.time.Instant approvedAt,
            java.time.Instant disbursedAt,
            String closureReason,
            java.time.Instant closedAt,
            String closedByUsername,
            java.time.Instant createdAt
    ) {
    }
}
