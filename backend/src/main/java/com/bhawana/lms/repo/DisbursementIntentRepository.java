package com.bhawana.lms.repo;

import com.bhawana.lms.domain.DisbursementIntent;
import com.bhawana.lms.domain.DisbursementIntentState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DisbursementIntentRepository extends JpaRepository<DisbursementIntent, UUID>, DisbursementIntentRepositoryCustom {

    @Query("""
            select intent
            from DisbursementIntent intent
            join fetch intent.loanAccount account
            join fetch account.loanApplication application
            join fetch application.lsp
            join fetch account.borrower
            where intent.id = :intentId
            """)
    Optional<DisbursementIntent> findDetailedById(@Param("intentId") UUID intentId);

    @Query("""
            select intent
            from DisbursementIntent intent
            where intent.loanAccount.id = :loanAccountId
              and intent.state not in (
                  com.bhawana.lms.domain.DisbursementIntentState.SUCCEEDED,
                  com.bhawana.lms.domain.DisbursementIntentState.FAILED,
                  com.bhawana.lms.domain.DisbursementIntentState.CANCELLED
              )
            """)
    Optional<DisbursementIntent> findLiveByLoanAccountId(@Param("loanAccountId") UUID loanAccountId);

    Optional<DisbursementIntent> findTopByLoanAccount_IdAndStateOrderByCreatedAtDesc(
            UUID loanAccountId,
            DisbursementIntentState state
    );

    long countByState(DisbursementIntentState state);

    @Query("select min(intent.updatedAt) from DisbursementIntent intent where intent.state = :state")
    Optional<Instant> findOldestUpdatedAtByState(@Param("state") DisbursementIntentState state);
}
