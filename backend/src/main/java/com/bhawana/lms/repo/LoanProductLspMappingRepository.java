package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanProductLspMapping;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanProductLspMappingRepository extends JpaRepository<LoanProductLspMapping, UUID> {

    /**
     * Admin listing joins both owning entities in the same statement.
     *
     * {@code loanProduct} and {@code lsp} are both EAGER {@code @ManyToOne}, so
     * without this graph Hibernate follows the root select with up to two extra
     * selects per row. The mapping table is small today; the graph keeps the
     * listing at one statement as it grows.
     */
    @Override
    @EntityGraph(attributePaths = {"loanProduct", "lsp"})
    List<LoanProductLspMapping> findAll();

    @EntityGraph(attributePaths = "lsp")
    List<LoanProductLspMapping> findAllByLoanProduct_Id(UUID loanProductId);

    List<LoanProductLspMapping> findAllByLsp_IdAndEnabledTrue(UUID lspId);

    @Modifying(clearAutomatically = true)
    @Query("delete from LoanProductLspMapping m where m.loanProduct.id = :loanProductId")
    long deleteByLoanProductId(@Param("loanProductId") UUID loanProductId);

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
