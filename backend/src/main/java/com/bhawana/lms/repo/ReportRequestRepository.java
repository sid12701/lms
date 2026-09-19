package com.bhawana.lms.repo;

import com.bhawana.lms.domain.ReportRequest;
import com.bhawana.lms.domain.ReportRequestStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ReportRequestRepository extends JpaRepository<ReportRequest, UUID>, ReportRequestRepositoryCustom {

    List<ReportRequest> findTop50ByOrderByCreatedAtDesc();

    /**
     * Records the terminal outcome for the request's current lease owner. Returns 0 when the
     * fence no longer matches (lease expired and someone else reclaimed the row), so a stale
     * worker can never overwrite a live claim.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE ReportRequest request
            SET request.status = :status,
                request.fileName = :fileName,
                request.mediaType = :mediaType,
                request.storageKey = :storageKey,
                request.errorMessage = :errorMessage,
                request.completedAt = :completedAt,
                request.processingOwner = null,
                request.processingExpiresAt = null,
                request.updatedAt = :now
            WHERE request.id = :id
              AND request.processingOwner = :owner
              AND request.processingAttempt = :attempt
              AND request.status = :processingStatus
            """)
    int recordOutcomeIfOwned(
            @Param("id") UUID id,
            @Param("attempt") int attempt,
            @Param("owner") String owner,
            @Param("status") ReportRequestStatus status,
            @Param("fileName") String fileName,
            @Param("mediaType") String mediaType,
            @Param("storageKey") String storageKey,
            @Param("errorMessage") String errorMessage,
            @Param("completedAt") Instant completedAt,
            @Param("processingStatus") ReportRequestStatus processingStatus,
            @Param("now") Instant now
    );

    /**
     * Claims one notification-pending row for {@code owner}. The pending state is derived, not
     * stored: a terminal request with a recipient and no recorded send.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE ReportRequest request
            SET request.processingOwner = :owner,
                request.processingExpiresAt = :expiresAt,
                request.processingAttempt = request.processingAttempt + 1,
                request.updatedAt = :now
            WHERE request.id = :id
              AND request.status IN :terminalStatuses
              AND request.notificationEmail IS NOT NULL
              AND request.notificationSentAt IS NULL
              AND (request.processingExpiresAt IS NULL OR request.processingExpiresAt < :now)
            """)
    int tryClaimNotification(
            @Param("id") UUID id,
            @Param("owner") String owner,
            @Param("expiresAt") Instant expiresAt,
            @Param("terminalStatuses") List<ReportRequestStatus> terminalStatuses,
            @Param("now") Instant now
    );

    @Query("""
            SELECT request.id
            FROM ReportRequest request
            WHERE request.status IN :terminalStatuses
              AND request.notificationEmail IS NOT NULL
              AND request.notificationSentAt IS NULL
              AND (request.processingExpiresAt IS NULL OR request.processingExpiresAt < :now)
            ORDER BY request.createdAt ASC
            """)
    List<UUID> findClaimableNotificationIds(
            @Param("terminalStatuses") List<ReportRequestStatus> terminalStatuses,
            @Param("now") Instant now,
            Pageable pageable
    );

    /**
     * Records a notification send outcome for the claiming owner and releases the lease. A
     * failure keeps {@code notificationSentAt} null so the sweep retries it; a crash between
     * send and this write can produce one duplicate delivery — the notification contract is
     * at-least-once.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE ReportRequest request
            SET request.notificationSentAt = :sentAt,
                request.notificationErrorMessage = :errorMessage,
                request.notificationAttempts = request.notificationAttempts + 1,
                request.processingOwner = null,
                request.processingExpiresAt = null,
                request.updatedAt = :now
            WHERE request.id = :id
              AND request.processingOwner = :owner
              AND request.processingAttempt = :attempt
            """)
    int recordNotificationIfOwned(
            @Param("id") UUID id,
            @Param("attempt") int attempt,
            @Param("owner") String owner,
            @Param("sentAt") Instant sentAt,
            @Param("errorMessage") String errorMessage,
            @Param("now") Instant now
    );
}
