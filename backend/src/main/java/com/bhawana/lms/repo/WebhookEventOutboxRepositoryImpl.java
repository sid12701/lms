package com.bhawana.lms.repo;

import com.bhawana.lms.domain.WebhookEventOutbox;
import com.bhawana.lms.domain.WebhookEventOutboxStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.EntityManager;
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
    public List<WebhookEventOutbox> claimDispatchBatch(Instant now, int batchSize) {
        if (now == null || batchSize < 1) {
            return List.of();
        }

        if (!isPostgres()) {
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
                            order by event.createdAt asc
                            """,
                    WebhookEventOutbox.class
            );
            query.setParameter("pendingStatus", WebhookEventOutboxStatus.PENDING);
            query.setParameter("retryableFailureStatus", WebhookEventOutboxStatus.RETRYABLE_FAILURE);
            query.setParameter("now", now);
            query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
            query.setMaxResults(batchSize);
            return query.getResultList();
        }

        List<UUID> claimedIds = claimIds(now, batchSize);
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

    private List<UUID> claimIds(Instant now, int batchSize) {
        Query query = entityManager.createNativeQuery("""
                select event.id
                from webhook_event_outbox event
                where event.status = 'PENDING'
                   or (
                        event.status = 'RETRYABLE_FAILURE'
                        and (event.next_attempt_at is null or event.next_attempt_at <= :now)
                   )
                order by event.created_at asc
                for update skip locked
                limit :batchSize
                """);
        query.setParameter("now", now);
        query.setParameter("batchSize", batchSize);

        @SuppressWarnings("unchecked")
        List<Object> rows = query.getResultList();
        return rows.stream()
                .map(WebhookEventOutboxRepositoryImpl::toUuid)
                .toList();
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
