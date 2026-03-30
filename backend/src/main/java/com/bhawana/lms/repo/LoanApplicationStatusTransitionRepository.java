package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanApplicationStatusTransition;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanApplicationStatusTransitionRepository extends JpaRepository<LoanApplicationStatusTransition, UUID> {

    List<LoanApplicationStatusTransition> findTop20ByLoanApplication_IdOrderByCreatedAtDesc(UUID loanApplicationId);
}
