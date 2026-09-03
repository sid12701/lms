package com.bhawana.lms.repo;

import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import java.util.Collection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanApplicationRepository extends JpaRepository<LoanApplication, UUID> {

    @EntityGraph(attributePaths = {"borrower", "lsp", "loanProduct", "loanProductVersion"})
    Optional<LoanApplication> findDetailedById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select application from LoanApplication application where application.id = :id")
    Optional<LoanApplication> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Serializes approval decisions for every application belonging to the same borrower.
     *
     * <p>The lock must be acquired before evaluating the cross-LSP one-open-loan rule and
     * held through loan-account creation. Selecting the borrower (rather than the application)
     * gives concurrent applications for the same borrower one shared database lock.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select application.borrower
            from LoanApplication application
            where application.id = :applicationId
            """)
    Optional<Borrower> findBorrowerByApplicationIdForUpdate(@Param("applicationId") UUID applicationId);

    boolean existsByLsp_IdAndExternalLoanIdIgnoreCase(UUID lspId, String externalLoanId);

    @EntityGraph(attributePaths = {"borrower", "lsp", "loanProduct", "loanProductVersion"})
    Optional<LoanApplication> findDetailedByLsp_IdAndExternalLoanIdIgnoreCase(UUID lspId, String externalLoanId);

    @EntityGraph(attributePaths = {"borrower", "lsp", "loanProduct", "loanProductVersion"})
    Optional<LoanApplication> findTopByBorrower_IdAndLsp_IdOrderByCreatedAtDesc(UUID borrowerId, UUID lspId);

    Optional<LoanApplication> findByLsp_IdAndExternalLoanIdIgnoreCase(UUID lspId, String externalLoanId);

    @EntityGraph(attributePaths = {"borrower", "lsp", "loanProduct", "loanProductVersion"})
    List<LoanApplication> findTop8ByOrderByCreatedAtDesc();

    long countByStatus(LoanApplicationStatus status);

    long countByStatusIn(Collection<LoanApplicationStatus> statuses);

    @Query("""
            select application.status as status, count(application) as count
            from LoanApplication application
            group by application.status
            """)
    List<ApplicationStatusCountProjection> countGroupByStatus();

    interface ApplicationStatusCountProjection {
        LoanApplicationStatus getStatus();

        long getCount();
    }

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

    @EntityGraph(attributePaths = {"borrower", "lsp", "loanProduct", "loanProductVersion"})
    List<LoanApplication> findByStatusAndCreatedAtBefore(
            LoanApplicationStatus status,
            Instant createdAtBefore
    );

    @EntityGraph(attributePaths = {"borrower", "lsp", "loanProduct", "loanProductVersion"})
    List<LoanApplication> findByStatus(LoanApplicationStatus status);

    boolean existsByBorrower_IdAndLsp_IdAndStatusIn(
            UUID borrowerId,
            UUID lspId,
            Collection<LoanApplicationStatus> statuses
    );
}
