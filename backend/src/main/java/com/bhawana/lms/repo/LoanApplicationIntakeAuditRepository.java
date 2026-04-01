package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanApplicationIntakeAudit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanApplicationIntakeAuditRepository extends JpaRepository<LoanApplicationIntakeAudit, UUID> {

    List<LoanApplicationIntakeAudit> findTop10ByLoanApplication_IdOrderByCreatedAtDesc(UUID loanApplicationId);

    Optional<LoanApplicationIntakeAudit> findTopByLoanApplication_IdOrderByCreatedAtDesc(UUID loanApplicationId);
}
