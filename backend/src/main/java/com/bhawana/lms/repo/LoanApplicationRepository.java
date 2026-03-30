package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanApplication;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanApplicationRepository extends JpaRepository<LoanApplication, UUID> {

    boolean existsByLsp_IdAndExternalLoanIdIgnoreCase(UUID lspId, String externalLoanId);

    Optional<LoanApplication> findByLsp_IdAndExternalLoanIdIgnoreCase(UUID lspId, String externalLoanId);

    List<LoanApplication> findAllByOrderByCreatedAtDesc();
}
