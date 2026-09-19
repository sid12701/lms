package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanApplicationApprovalEvidence;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanApplicationApprovalEvidenceRepository extends JpaRepository<LoanApplicationApprovalEvidence, UUID> {

    /** Rows of every approval, newest approval first; the first approvalId is the current one. */
    List<LoanApplicationApprovalEvidence> findByLoanApplicationIdOrderByApprovedAtDesc(UUID loanApplicationId);
}
