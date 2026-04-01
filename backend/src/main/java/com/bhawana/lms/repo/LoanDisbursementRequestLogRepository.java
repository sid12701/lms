package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanDisbursementRequestLogRepository extends JpaRepository<LoanDisbursementRequestLog, UUID> {

    List<LoanDisbursementRequestLog> findTop20ByLoanAccount_IdOrderByCreatedAtDesc(UUID loanAccountId);
}
