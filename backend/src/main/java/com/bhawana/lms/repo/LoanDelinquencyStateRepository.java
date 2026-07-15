package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanDelinquencyState;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanDelinquencyStateRepository extends JpaRepository<LoanDelinquencyState, UUID> {

    Optional<LoanDelinquencyState> findByLoanApplication_Id(UUID loanApplicationId);
}
