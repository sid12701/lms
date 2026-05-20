package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanProductLspMapping;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LoanProductLspMappingRepository extends JpaRepository<LoanProductLspMapping, UUID> {

    List<LoanProductLspMapping> findAllByLoanProduct_Id(UUID loanProductId);

    List<LoanProductLspMapping> findAllByLsp_IdAndEnabledTrue(UUID lspId);

    long deleteByLoanProduct_Id(UUID loanProductId);

    Optional<LoanProductLspMapping> findByLsp_IdAndLoanProduct_Id(UUID lspId, UUID loanProductId);

    @Query("""
            select mapping.loanProduct.id as productId,
                   mapping.lsp.id as lspId
            from LoanProductLspMapping mapping
            order by mapping.loanProduct.id asc, mapping.lsp.id asc
            """)
    List<ProductLspMappingRefProjection> findAllMappingRefs();

    interface ProductLspMappingRefProjection {
        UUID getProductId();

        UUID getLspId();
    }
}
