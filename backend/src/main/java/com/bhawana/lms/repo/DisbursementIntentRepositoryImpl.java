package com.bhawana.lms.repo;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;

@Repository
class DisbursementIntentRepositoryImpl implements DisbursementIntentRepositoryCustom {

    private final EntityManager entityManager;

    DisbursementIntentRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<UUID> claimBatch(Instant now, int batchSize, Instant leaseExpiresAt, String leaseOwner) {
        if (now == null || batchSize < 1 || leaseExpiresAt == null || leaseOwner == null || leaseOwner.isBlank()) {
            return List.of();
        }
        if (isPostgres()) {
            return claimPostgres(now, batchSize, leaseExpiresAt, leaseOwner);
        }
        return claimWithJpa(now, batchSize, leaseExpiresAt, leaseOwner);
    }

    private List<UUID> claimPostgres(Instant now, int batchSize, Instant leaseExpiresAt, String leaseOwner) {
        Query query = entityManager.createNativeQuery("""
                WITH picked AS (
                    SELECT intent.id
                    FROM disbursement_intent intent
                    WHERE intent.state = 'CREATED'
                      AND (intent.lease_expires_at IS NULL OR intent.lease_expires_at < :now)
                    ORDER BY intent.created_at ASC
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batchSize
                )
                UPDATE disbursement_intent intent
                SET lease_owner = :leaseOwner,
                    lease_expires_at = :leaseExpiresAt,
                    attempt_count = intent.attempt_count + 1,
                    updated_at = CURRENT_TIMESTAMP
                FROM picked
                WHERE intent.id = picked.id
                RETURNING intent.id
                """);
        query.setParameter("now", now);
        query.setParameter("batchSize", batchSize);
        query.setParameter("leaseExpiresAt", leaseExpiresAt);
        query.setParameter("leaseOwner", leaseOwner);

        @SuppressWarnings("unchecked")
        List<Object> rows = query.getResultList();
        return rows.stream().map(DisbursementIntentRepositoryImpl::toUuid).toList();
    }

    private List<UUID> claimWithJpa(Instant now, int batchSize, Instant leaseExpiresAt, String leaseOwner) {
        TypedQuery<com.bhawana.lms.domain.DisbursementIntent> query = entityManager.createQuery(
                """
                        select intent
                        from DisbursementIntent intent
                        where intent.state = com.bhawana.lms.domain.DisbursementIntentState.CREATED
                          and (intent.leaseExpiresAt is null or intent.leaseExpiresAt < :now)
                        order by intent.createdAt asc
                        """,
                com.bhawana.lms.domain.DisbursementIntent.class
        );
        query.setParameter("now", now);
        query.setMaxResults(batchSize);
        query.setLockMode(LockModeType.PESSIMISTIC_WRITE);

        List<com.bhawana.lms.domain.DisbursementIntent> claimed = query.getResultList();
        for (com.bhawana.lms.domain.DisbursementIntent intent : claimed) {
            intent.stampLease(leaseOwner, leaseExpiresAt);
        }
        return claimed.stream().map(com.bhawana.lms.domain.DisbursementIntent::getId).toList();
    }

    private boolean isPostgres() {
        Session session = entityManager.unwrap(Session.class);
        return session.doReturningWork(connection -> {
            String product = connection.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase().contains("postgres");
        });
    }

    private static UUID toUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(value.toString());
    }
}
