package com.bhawana.lms.repo;

import com.bhawana.lms.domain.DisbursementDisposition;
import com.bhawana.lms.domain.DisbursementObservation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DisbursementObservationRepository extends JpaRepository<DisbursementObservation, UUID> {

    List<DisbursementObservation> findTop50ByLoanAccount_IdOrderByObservedAtDesc(UUID loanAccountId);

    /** POLL observations for one reference (per-call identity / crash-gap analysis). */
    List<DisbursementObservation> findByLoanAccount_IdAndTranRefNoAndKind(
            UUID loanAccountId,
            String tranRefNo,
            com.bhawana.lms.domain.DisbursementObservationKind kind);

    long countByLoanAccount_Id(UUID loanAccountId);

    long countByTranRefNo(String tranRefNo);

    @Query("""
            select observation
            from DisbursementObservation observation
            where observation.loanAccount.id = :loanAccountId
              and observation.tranRefNo = :tranRefNo
              and observation.disposition = :disposition
              and observation.queryResolved = true
              and observation.duplicate = false
            order by observation.observedAt desc
            """)
    List<DisbursementObservation> findMatchingLiveEvidence(
            @Param("loanAccountId") UUID loanAccountId,
            @Param("tranRefNo") String tranRefNo,
            @Param("disposition") DisbursementDisposition disposition);

    @Query("""
            select observation
            from DisbursementObservation observation
            where observation.loanAccount.id = :loanAccountId
            order by observation.observedAt desc
            """)
    List<DisbursementObservation> findRecentByLoanAccountId(
            @Param("loanAccountId") UUID loanAccountId,
            org.springframework.data.domain.Pageable pageable);

    @Query("select min(observation.observedAt) from DisbursementObservation observation")
    Optional<java.time.Instant> findOldestObservedAt();
}
