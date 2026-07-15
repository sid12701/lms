package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanProductVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanProductVersionRepository extends JpaRepository<LoanProductVersion, UUID> {

    Optional<LoanProductVersion> findTopByLoanProduct_IdOrderByVersionNumberDesc(UUID loanProductId);

    List<LoanProductVersion> findByLoanProduct_IdOrderByVersionNumberDesc(UUID loanProductId);
}
