package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LspApiIdempotencyRecord;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface LspApiIdempotencyRecordRepository extends JpaRepository<LspApiIdempotencyRecord, UUID> {

    Optional<LspApiIdempotencyRecord> findByLspIdAndOperationKeyAndIdempotencyKey(
            UUID lspId,
            String operationKey,
            String idempotencyKey
    );

    @Transactional
    long deleteByCreatedAtBefore(Instant cutoff);

    long countByResponseBody(String responseBody);

    @Query("select min(record.createdAt) from LspApiIdempotencyRecord record where record.responseBody = :responseBody")
    Optional<Instant> findOldestCreatedAtByResponseBody(@Param("responseBody") String responseBody);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE LspApiIdempotencyRecord record
            SET record.leaseOwner = :leaseOwner,
                record.leaseExpiresAt = :leaseExpiresAt,
                record.attempt = record.attempt + 1,
                record.updatedAt = :now
            WHERE record.id = :id
              AND record.responseBody = :pendingBody
              AND (record.leaseExpiresAt IS NULL OR record.leaseExpiresAt < :now)
            """)
    int tryReclaimExpiredLease(
            @Param("id") UUID id,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseExpiresAt") Instant leaseExpiresAt,
            @Param("now") Instant now,
            @Param("pendingBody") String pendingBody
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE LspApiIdempotencyRecord record
            SET record.responseStatus = :responseStatus,
                record.responseBody = :responseBody,
                record.leaseOwner = null,
                record.leaseExpiresAt = null,
                record.updatedAt = :now
            WHERE record.id = :id
              AND record.attempt = :attempt
              AND record.leaseOwner = :leaseOwner
              AND record.responseBody = :pendingBody
            """)
    int completeIfOwned(
            @Param("id") UUID id,
            @Param("attempt") int attempt,
            @Param("leaseOwner") String leaseOwner,
            @Param("responseStatus") int responseStatus,
            @Param("responseBody") String responseBody,
            @Param("pendingBody") String pendingBody,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            DELETE FROM LspApiIdempotencyRecord record
            WHERE record.id = :id
              AND record.attempt = :attempt
              AND record.leaseOwner = :leaseOwner
              AND record.responseBody = :pendingBody
            """)
    int deletePendingIfOwned(
            @Param("id") UUID id,
            @Param("attempt") int attempt,
            @Param("leaseOwner") String leaseOwner,
            @Param("pendingBody") String pendingBody
    );
}
