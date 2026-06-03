package com.bhawana.lms.repo;

import com.bhawana.lms.domain.WebhookEventOutbox;
import com.bhawana.lms.domain.WebhookEventOutboxStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;

@Repository
class WebhookEventOutboxRepositoryImpl implements WebhookEventOutboxRepositoryCustom {

    private final EntityManager entityManager;

    WebhookEventOutboxRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<WebhookEventOutbox> claimDispatchBatch(Instant now, int batchSize, Instant claimExpiresAt) {
        if (now == null || batchSize < 1 || claimExpiresAt == null) {
            return List.of();
        }

        if (isPostgres()) {
            List<UUID> claimedIds = claimIdsPostgres(now, batchSize, claimExpiresAt);
            return loadClaimedEvents(claimedIds);
        }

        return claimWithJpa(now, batchSize, claimExpiresAt);
    }

    private List<UUID> claimIdsPostgres(Instant now, int batchSize, Instant claimExpiresAt) {
        Query query = entityManager.createNativeQuery("""
                WITH picked AS (
                    SELECT event.id
                    FROM webhook_event_outbox event
                    WHERE event.status = 'PENDING'
                       OR (
                            event.status = 'RETRYABLE_FAILURE'
                            AND (event.next_attempt_at IS NULL OR event.next_attempt_at <= :now)
                       )
                       OR (
                            event.status = 'IN_FLIGHT'
                            AND event.claim_expires_at IS NOT NULL
                            AND event.claim_expires_at < :now
                       )
                    ORDER BY event.created_at ASC
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batchSize
                )
                UPDATE webhook_event_outbox event
                SET status = 'IN_FLIGHT',
                    claim_expires_at = :claimExpiresAt,
                    updated_at = CURRENT_TIMESTAMP
                FROM picked
                WHERE event.id = picked.id
                RETURNING event.id
                """);
        query.setParameter("now", now);
        query.setParameter("batchSize", batchSize);
        query.setParameter("claimExpiresAt", claimExpiresAt);

        @SuppressWarnings("unchecked")
        List<Object> rows = query.getResultList();
        return rows.stream()
                .map(WebhookEventOutboxRepositoryImpl::toUuid)
                .toList();
    }

    private List<WebhookEventOutbox> claimWithJpa(Instant now, int batchSize, Instant claimExpiresAt) {
        TypedQuery<WebhookEventOutbox> query = entityManager.createQuery(
                """
                        select event
                        from WebhookEventOutbox event
                        join fetch event.lsp
                        where event.status = :pendingStatus
                           or (
                                event.status = :retryableFailureStatus
                                and (event.nextAttemptAt is null or event.nextAttemptAt <= :now)
                           )
                           or (
                                event.status = :inFlightStatus
                                and event.claimExpiresAt is not null
                                and event.claimExpiresAt < :now
                           )
                        order by event.createdAt asc
                        """,
                WebhookEventOutbox.class
        );
        query.setParameter("pendingStatus", WebhookEventOutboxStatus.PENDING);
        query.setParameter("retryableFailureStatus", WebhookEventOutboxStatus.RETRYABLE_FAILURE);
        query.setParameter("inFlightStatus", WebhookEventOutboxStatus.IN_FLIGHT);
        query.setParameter("now", now);
        query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        query.setMaxResults(batchSize);
        List<WebhookEventOutbox> candidates = query.getResultList();
        for (WebhookEventOutbox event : candidates) {
            event.claim(claimExpiresAt);
        }
        entityManager.flush();
        return candidates;
    }

    private List<WebhookEventOutbox> loadClaimedEvents(List<UUID> claimedIds) {
        if (claimedIds.isEmpty()) {
            return List.of();
        }

        return entityManager.createQuery(
                        """
                                select event
                                from WebhookEventOutbox event
                                join fetch event.lsp
                                where event.id in :ids
                                order by event.createdAt asc
                                """,
                        WebhookEventOutbox.class
                )
                .setParameter("ids", claimedIds)
                .getResultList();
    }

    private static UUID toUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(String.valueOf(value));
    }

    private boolean isPostgres() {
        return entityManager.unwrap(Session.class).doReturningWork(connection ->
                connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres")
        );
    }
}
