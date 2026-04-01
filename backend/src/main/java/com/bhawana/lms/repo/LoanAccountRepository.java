package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanAccountRepository extends JpaRepository<LoanAccount, UUID> {

    Optional<LoanAccount> findByLoanApplication_Id(UUID applicationId);
}
