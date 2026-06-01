package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanDisbursementBankMismatchLog;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanDisbursementBankMismatchLogRepository extends JpaRepository<LoanDisbursementBankMismatchLog, UUID> {

    long countByLoanApplication_IdAndLsp_IdAndCreatedAtAfter(
            UUID loanApplicationId,
            UUID lspId,
            Instant since
    );
}
