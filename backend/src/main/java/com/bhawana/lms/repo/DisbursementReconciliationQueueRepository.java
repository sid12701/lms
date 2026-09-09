package com.bhawana.lms.repo;

import com.bhawana.lms.domain.DisbursementReconciliationQueueEntry;
import com.bhawana.lms.domain.DisbursementReconciliationReason;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DisbursementReconciliationQueueRepository
        extends JpaRepository<DisbursementReconciliationQueueEntry, UUID> {

    /**
     * H02 — due rows for the automatic sweep, oldest first. Held
     * {@code CONFLICTING_EVIDENCE} rows are excluded: contradictions need explicit operator
     * resolution and must never be auto-polled — selecting them by age would let a stale
     * conflict starve every recoverable entry behind it. They stay visible via the queue
     * page/summary APIs.
     */
    @Query("""
            select entry
            from DisbursementReconciliationQueueEntry entry
            join fetch entry.loanAccount account
            where entry.nextPollAt <= :now
              and entry.reason <> com.bhawana.lms.domain.DisbursementReconciliationReason.CONFLICTING_EVIDENCE
            order by entry.nextPollAt asc
            """)
    List<DisbursementReconciliationQueueEntry> findDueForPoll(
            @Param("now") Instant now, Pageable pageable);

    @Query("""
            select entry.reason as reason, count(entry) as entryCount
            from DisbursementReconciliationQueueEntry entry
            group by entry.reason
            """)
    List<ReasonCount> countByReason();

    @Query("select min(entry.firstSeenAt) from DisbursementReconciliationQueueEntry entry")
    Optional<Instant> findOldestFirstSeenAt();

    /**
     * H02 — true offset page for operators, oldest first with a stable id tie-break so
     * non-multiple offsets page deterministically. Bind the row offset directly instead
     * of converting it to a page number and losing the remainder.
     */
    @Query(value = """
            select q.* from disbursement_reconciliation_queue q
            order by q.first_seen_at asc, q.loan_account_id asc
            limit :limit offset :offset
            """, nativeQuery = true)
    List<DisbursementReconciliationQueueEntry> findPageByAgeOffset(
            @Param("limit") int limit, @Param("offset") int offset);

    interface ReasonCount {
        DisbursementReconciliationReason getReason();

        long getEntryCount();
    }
}
