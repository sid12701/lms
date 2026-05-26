package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanPaymentTransaction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanPaymentTransactionRepository extends JpaRepository<LoanPaymentTransaction, UUID> {

    Optional<LoanPaymentTransaction> findByIdempotencyKey(String idempotencyKey);

    List<LoanPaymentTransaction> findTop50ByLoanAccount_IdOrderByPaymentDateDescCreatedAtDesc(UUID loanAccountId);

    List<LoanPaymentTransaction> findByLoanAccount_IdOrderByPaymentDateAscCreatedAtAsc(UUID loanAccountId);

    boolean existsByLoanAccount_Id(UUID loanAccountId);
}
