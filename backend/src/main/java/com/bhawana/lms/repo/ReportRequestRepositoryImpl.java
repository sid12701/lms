package com.bhawana.lms.repo;

import com.bhawana.lms.domain.ReportRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class ReportRequestRepositoryImpl implements ReportRequestRepositoryCustom {

    private final EntityManager entityManager;

    ReportRequestRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<ReportRequest> claimBatchForProcessing(String owner, Instant leaseExpiresAt, int batchSize) {
        if (batchSize < 1) {
            return List.of();
        }

        // Postgres-only by construction (L03): spring.jpa.database-platform pins
        // PostgreSQLDialect, and every test context runs Testcontainers Postgres via
        // PostgresTestEnvironmentPostProcessor — including @DataJpaTest slices — so a
        // portable fallback branch was unreachable in every supported runtime. The
        // claim relies on FOR UPDATE SKIP LOCKED, which has no H2 equivalent.
        Query query = entityManager.createNativeQuery("""
                update report_request
                set status = 'PROCESSING',
                    processing_owner = :owner,
                    processing_expires_at = :leaseExpiresAt,
                    processing_attempt = processing_attempt + 1,
                    error_message = null,
                    updated_at = now()
                where id in (
                    select id
                    from report_request
                    where status = 'PENDING'
                       or (status = 'PROCESSING'
                           and (processing_expires_at is null
                                or processing_expires_at < now()))
                    order by created_at asc
                    for update skip locked
                    limit :batchSize
                )
                returning id
                """);
        query.setParameter("owner", owner);
        query.setParameter("leaseExpiresAt", leaseExpiresAt);
        query.setParameter("batchSize", batchSize);

        @SuppressWarnings("unchecked")
        List<UUID> claimedIds = ((List<Object>) query.getResultList())
                .stream()
                .map(ReportRequestRepositoryImpl::toUuid)
                .toList();
        if (claimedIds.isEmpty()) {
            return List.of();
        }

        // Re-read inside the same transaction so callers see the attempt the claim stamped.
        return entityManager.createQuery(
                        """
                                select request
                                from ReportRequest request
                                left join fetch request.lsp
                                where request.id in :ids
                                order by request.createdAt asc
                                """,
                        ReportRequest.class
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
}
