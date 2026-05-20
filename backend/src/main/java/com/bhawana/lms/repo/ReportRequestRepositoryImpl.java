package com.bhawana.lms.repo;

import com.bhawana.lms.domain.ReportRequest;
import com.bhawana.lms.domain.ReportRequestStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;

@Repository
class ReportRequestRepositoryImpl implements ReportRequestRepositoryCustom {

    private final EntityManager entityManager;

    ReportRequestRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<ReportRequest> claimBatchForProcessing(List<ReportRequestStatus> statuses, int batchSize) {
        if (statuses == null || statuses.isEmpty() || batchSize < 1) {
            return List.of();
        }

        if (!isPostgres()) {
            TypedQuery<ReportRequest> query = entityManager.createQuery(
                    """
                            select request
                            from ReportRequest request
                            left join fetch request.lsp
                            where request.status in :statuses
                            order by request.createdAt asc
                            """,
                    ReportRequest.class
            );
            query.setParameter("statuses", statuses);
            query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
            query.setMaxResults(batchSize);
            return query.getResultList();
        }

        List<UUID> claimedIds = claimIds(statuses, batchSize);
        if (claimedIds.isEmpty()) {
            return List.of();
        }

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

    private List<UUID> claimIds(List<ReportRequestStatus> statuses, int batchSize) {
        String statusPlaceholders = IntStream.range(0, statuses.size())
                .mapToObj(index -> ":status" + index)
                .collect(Collectors.joining(", "));

        Query query = entityManager.createNativeQuery("""
                select request.id
                from report_request request
                where request.status in (%s)
                order by request.created_at asc
                for update skip locked
                limit :batchSize
                """.formatted(statusPlaceholders));

        for (int index = 0; index < statuses.size(); index++) {
            query.setParameter("status" + index, statuses.get(index).name());
        }
        query.setParameter("batchSize", batchSize);

        @SuppressWarnings("unchecked")
        List<Object> rows = query.getResultList();
        return rows.stream()
                .map(ReportRequestRepositoryImpl::toUuid)
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
