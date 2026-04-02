package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanPaymentTransaction;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanPaymentTransactionRepository extends JpaRepository<LoanPaymentTransaction, UUID> {

    List<LoanPaymentTransaction> findTop50ByLoanAccount_IdOrderByPaymentDateDescCreatedAtDesc(UUID loanAccountId);

    List<LoanPaymentTransaction> findByLoanAccount_IdOrderByPaymentDateAscCreatedAtAsc(UUID loanAccountId);
}
