package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanApplicationRepository extends JpaRepository<LoanApplication, UUID> {

    @EntityGraph(attributePaths = {"borrower", "lsp", "loanProduct"})
    Optional<LoanApplication> findDetailedById(UUID id);

    boolean existsByLsp_IdAndExternalLoanIdIgnoreCase(UUID lspId, String externalLoanId);

    @EntityGraph(attributePaths = {"borrower", "lsp", "loanProduct"})
    Optional<LoanApplication> findDetailedByLsp_IdAndExternalLoanIdIgnoreCase(UUID lspId, String externalLoanId);

    Optional<LoanApplication> findByLsp_IdAndExternalLoanIdIgnoreCase(UUID lspId, String externalLoanId);

    @EntityGraph(attributePaths = {"borrower", "lsp", "loanProduct"})
    List<LoanApplication> findDetailedByOrderByCreatedAtDesc();

    List<LoanApplication> findAllByOrderByCreatedAtDesc();

    @Query("""
            select application.lsp.id as lspId,
                   count(application) as loanApplicationCount,
                   coalesce(sum(case when application.status in :approvedStatuses then 1 else 0 end), 0) as approvedLoanCount
            from LoanApplication application
            group by application.lsp.id
            """)
    List<LspApplicationSummaryProjection> summarizeApplicationsByLsp(
            @Param("approvedStatuses") Collection<LoanApplicationStatus> approvedStatuses
    );

    @Query("""
            select application.lsp.id as lspId,
                   count(application) as loanApplicationCount,
                   coalesce(sum(case when application.status in :approvedStatuses then 1 else 0 end), 0) as approvedLoanCount
            from LoanApplication application
            where application.lsp.id = :lspId
            group by application.lsp.id
            """)
    Optional<LspApplicationSummaryProjection> summarizeApplicationsForLsp(
            @Param("lspId") UUID lspId,
            @Param("approvedStatuses") Collection<LoanApplicationStatus> approvedStatuses
    );

    interface LspApplicationSummaryProjection {
        UUID getLspId();

        long getLoanApplicationCount();

        long getApprovedLoanCount();
    }
}
