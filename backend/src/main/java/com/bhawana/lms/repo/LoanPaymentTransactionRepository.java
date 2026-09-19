package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanPaymentTransaction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LoanPaymentTransactionRepository extends JpaRepository<LoanPaymentTransaction, UUID> {

    @EntityGraph(attributePaths = {"loanAccount", "repaymentInstallment"})
    Optional<LoanPaymentTransaction> findFirstByIdempotencyKeyOrderByCreatedAtAsc(String idempotencyKey);

    @EntityGraph(attributePaths = {"loanAccount", "repaymentInstallment"})
    List<LoanPaymentTransaction> findTop50ByLoanAccount_IdOrderByPaymentDateDescCreatedAtDesc(UUID loanAccountId);

    /**
     * Bounded payment-history page for the LSP endpoint (M14). Ordering — and therefore page
     * boundaries — comes from the caller's {@link Pageable} sort.
     */
    @EntityGraph(attributePaths = {"loanAccount", "repaymentInstallment"})
    Page<LoanPaymentTransaction> findByLoanAccount_Id(UUID loanAccountId, Pageable pageable);

    @EntityGraph(attributePaths = {"loanAccount", "repaymentInstallment"})
    List<LoanPaymentTransaction> findByLoanAccount_IdOrderByPaymentDateAscCreatedAtAsc(UUID loanAccountId);

    boolean existsByLoanAccount_Id(UUID loanAccountId);

    /** The settlement receipt that redeemed a foreclosure quote; at most one exists (V130). */
    Optional<LoanPaymentTransaction> findByForeclosureQuote_Id(UUID foreclosureQuoteId);

    /**
     * Reconciliation inventory (H09): received receipts targeted at an installment that has been
     * paid less than the receipts targeted at it claim to have allocated — history rewritten by
     * the pre-fix foreclosure replay, or legacy double allocations. These are evidence for an
     * operator; nothing repairs them automatically.
     */
    @Query(value = """
            select p.*
            from loan_payment_transaction p
            join loan_repayment_schedule_installment i on i.id = p.repayment_installment_id
            where p.status = 'RECEIVED'
              and i.paid_amount < (
                  select sum(t.allocated_amount)
                  from loan_payment_transaction t
                  where t.repayment_installment_id = i.id
                    and t.status = 'RECEIVED'
              )
            order by p.loan_account_id, i.installment_number, p.created_at
            """, nativeQuery = true)
    List<LoanPaymentTransaction> findReceiptsExceedingTheirInstallmentPayment();
}
