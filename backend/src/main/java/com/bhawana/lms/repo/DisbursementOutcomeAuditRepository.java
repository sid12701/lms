package com.bhawana.lms.repo;

import com.bhawana.lms.domain.DisbursementOutcomeAudit;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisbursementOutcomeAuditRepository extends JpaRepository<DisbursementOutcomeAudit, UUID> {
    long countByLoanApplication_Id(UUID loanApplicationId);
}
