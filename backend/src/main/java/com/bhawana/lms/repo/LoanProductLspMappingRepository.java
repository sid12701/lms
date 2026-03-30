package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanProductLspMapping;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanProductLspMappingRepository extends JpaRepository<LoanProductLspMapping, UUID> {

    List<LoanProductLspMapping> findAllByLoanProduct_Id(UUID loanProductId);

    long deleteByLoanProduct_Id(UUID loanProductId);

    Optional<LoanProductLspMapping> findByLsp_IdAndLoanProduct_Id(UUID lspId, UUID loanProductId);
}
