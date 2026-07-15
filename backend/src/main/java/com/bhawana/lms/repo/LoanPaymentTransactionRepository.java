package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanPaymentTransaction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanPaymentTransactionRepository extends JpaRepository<LoanPaymentTransaction, UUID> {

    @EntityGraph(attributePaths = {"loanAccount", "repaymentInstallment"})
    Optional<LoanPaymentTransaction> findFirstByIdempotencyKeyOrderByCreatedAtAsc(String idempotencyKey);

    @EntityGraph(attributePaths = {"loanAccount", "repaymentInstallment"})
    List<LoanPaymentTransaction> findTop50ByLoanAccount_IdOrderByPaymentDateDescCreatedAtDesc(UUID loanAccountId);

    @EntityGraph(attributePaths = {"loanAccount", "repaymentInstallment"})
    List<LoanPaymentTransaction> findByLoanAccount_IdOrderByPaymentDateAscCreatedAtAsc(UUID loanAccountId);

    boolean existsByLoanAccount_Id(UUID loanAccountId);
}
