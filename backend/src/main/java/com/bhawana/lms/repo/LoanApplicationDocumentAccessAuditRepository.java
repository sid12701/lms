package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanApplicationDocumentAccessAudit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanApplicationDocumentAccessAuditRepository extends JpaRepository<LoanApplicationDocumentAccessAudit, UUID> {

    List<LoanApplicationDocumentAccessAudit> findTop20ByLoanApplication_IdOrderByCreatedAtDesc(UUID loanApplicationId);
}
