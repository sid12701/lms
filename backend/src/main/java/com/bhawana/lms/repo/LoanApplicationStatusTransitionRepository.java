package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusTransition;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanApplicationStatusTransitionRepository extends JpaRepository<LoanApplicationStatusTransition, UUID> {

    List<LoanApplicationStatusTransition> findTop20ByLoanApplication_IdOrderByCreatedAtDesc(UUID loanApplicationId);

    Optional<LoanApplicationStatusTransition> findTopByLoanApplication_IdOrderByCreatedAtDesc(UUID loanApplicationId);

    List<LoanApplicationStatusTransition> findByToStatusAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
            LoanApplicationStatus toStatus,
            Instant createdAt
    );

    List<LoanApplicationStatusTransition> findByLoanApplication_IdAndToStatusOrderByCreatedAtAsc(
            UUID loanApplicationId,
            LoanApplicationStatus toStatus
    );

    Optional<LoanApplicationStatusTransition> findTopByLoanApplication_IdAndToStatusOrderByCreatedAtDesc(
            UUID loanApplicationId,
            LoanApplicationStatus toStatus
    );

    @Query("""
            select transition.loanApplication.lsp.id as lspId, count(transition) as rejectedCount
            from LoanApplicationStatusTransition transition
            where transition.toStatus = com.bhawana.lms.domain.LoanApplicationStatus.REJECTED
              and transition.createdAt >= :since
            group by transition.loanApplication.lsp.id
            """)
    List<LspRejectCountProjection> countRejectionsByLspSince(@Param("since") Instant since);

    @Query("""
            select application.lsp.id as lspId, count(application) as intakeCount
            from LoanApplication application
            where application.createdAt >= :since
            group by application.lsp.id
            """)
    List<LspIntakeCountProjection> countIntakesByLspSince(@Param("since") Instant since);

    interface LspRejectCountProjection {
        UUID getLspId();

        long getRejectedCount();
    }

    interface LspIntakeCountProjection {
        UUID getLspId();

        long getIntakeCount();
    }
}
