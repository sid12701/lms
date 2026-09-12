package com.bhawana.lms.repo;

import com.bhawana.lms.domain.DisbursementIntent;
import com.bhawana.lms.domain.DisbursementIntentState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DisbursementIntentRepository extends JpaRepository<DisbursementIntent, UUID>, DisbursementIntentRepositoryCustom {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select intent from DisbursementIntent intent where intent.id = :intentId")
    Optional<DisbursementIntent> findByIdForUpdate(@Param("intentId") UUID intentId);

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
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
    Optional<DisbursementIntent> findLiveByLoanAccountIdForUpdate(@Param("loanAccountId") UUID loanAccountId);

    /**
     * Cross-LSP live-instruction probe for the bank-edit gate. Any non-terminal
     * intent (CREATED, REQUESTED, UNKNOWN) attached to any of the borrower's accounts means
     * money may already have left an LSP disbursal account, so shared bank details are frozen.
     */
    @Query("""
            select case when count(intent) > 0 then true else false end
            from DisbursementIntent intent
            join intent.loanAccount account
            join account.borrower borrower
            where borrower.id = :borrowerId
              and intent.state in (
                  com.bhawana.lms.domain.DisbursementIntentState.CREATED,
                  com.bhawana.lms.domain.DisbursementIntentState.REQUESTED,
                  com.bhawana.lms.domain.DisbursementIntentState.UNKNOWN
              )
            """)
    boolean existsLiveIntentByBorrowerId(@Param("borrowerId") UUID borrowerId);

    Optional<DisbursementIntent> findTopByLoanAccount_IdAndStateOrderByCreatedAtDesc(
            UUID loanAccountId,
            DisbursementIntentState state
    );

    /** Earliest intent per account (original-evidence aging). */
    Optional<DisbursementIntent> findTopByLoanAccount_IdOrderByCreatedAtAsc(UUID loanAccountId);

    @Query("""
            select intent
            from DisbursementIntent intent
            join fetch intent.loanAccount account
            where intent.state in (
                com.bhawana.lms.domain.DisbursementIntentState.SUCCEEDED,
                com.bhawana.lms.domain.DisbursementIntentState.FAILED
            )
              and account.status = com.bhawana.lms.domain.LoanAccountStatus.DISBURSEMENT_REQUESTED
            order by intent.createdAt asc
            """)
    List<DisbursementIntent> findStrandedTerminalIntents(Pageable pageable);

    long countByState(DisbursementIntentState state);

    @Query("select min(intent.updatedAt) from DisbursementIntent intent where intent.state = :state")
    Optional<Instant> findOldestUpdatedAtByState(@Param("state") DisbursementIntentState state);
}
