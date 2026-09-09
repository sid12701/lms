package com.bhawana.lms.repo;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class DisbursementIntentRepositoryImpl implements DisbursementIntentRepositoryCustom {

    private final EntityManager entityManager;

    DisbursementIntentRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<ClaimToken> claimBatch(Instant now, int batchSize, Instant leaseExpiresAt, String leaseOwner) {
        if (now == null || batchSize < 1 || leaseExpiresAt == null || leaseOwner == null || leaseOwner.isBlank()) {
            return List.of();
        }
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
                RETURNING intent.id, intent.attempt_count
                """);
        query.setParameter("now", now);
        query.setParameter("batchSize", batchSize);
        query.setParameter("leaseExpiresAt", leaseExpiresAt);
        query.setParameter("leaseOwner", leaseOwner);

        @SuppressWarnings("unchecked")
        List<Object> rows = query.getResultList();
        return rows.stream()
                .map(row -> toClaimToken(row, leaseOwner))
                .toList();
    }

    @Override
    public java.util.Optional<ClaimToken> claimSingle(
            UUID intentId, Instant now, Instant leaseExpiresAt, String leaseOwner) {
        if (intentId == null || now == null || leaseExpiresAt == null || leaseOwner == null || leaseOwner.isBlank()) {
            return java.util.Optional.empty();
        }
        // Same conditional primitive as the batch path, but for one row: only CREATED with an
        // absent/expired lease may be claimed. The UPDATE is atomic — concurrent claimants for the
        // same id serialize, exactly one RETURNING row wins.
        Query query = entityManager.createNativeQuery("""
                UPDATE disbursement_intent intent
                SET lease_owner = :leaseOwner,
                    lease_expires_at = :leaseExpiresAt,
                    attempt_count = intent.attempt_count + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE intent.id = :intentId
                  AND intent.state = 'CREATED'
                  AND (intent.lease_expires_at IS NULL OR intent.lease_expires_at < :now)
                RETURNING intent.id, intent.attempt_count
                """);
        query.setParameter("intentId", intentId);
        query.setParameter("now", now);
        query.setParameter("leaseExpiresAt", leaseExpiresAt);
        query.setParameter("leaseOwner", leaseOwner);

        @SuppressWarnings("unchecked")
        List<Object> rows = query.getResultList();
        if (rows.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(toClaimToken(rows.get(0), leaseOwner));
    }

    private static ClaimToken toClaimToken(Object row, String leaseOwner) {
        if (row instanceof Object[] columns && columns.length >= 2) {
            return new ClaimToken(toUuid(columns[0]), leaseOwner, toAttempt(columns[1]));
        }
        // Defensive: older projection returning id only (should not happen after C03).
        return new ClaimToken(toUuid(row), leaseOwner, -1);
    }

    private static int toAttempt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private static UUID toUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(value.toString());
    }
}
