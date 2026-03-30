package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanApplicationAssignmentEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanApplicationAssignmentEventRepository extends JpaRepository<LoanApplicationAssignmentEvent, UUID> {

    List<LoanApplicationAssignmentEvent> findTop20ByLoanApplication_IdOrderByCreatedAtDesc(UUID loanApplicationId);
}
