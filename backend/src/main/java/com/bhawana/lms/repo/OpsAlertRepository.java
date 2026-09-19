package com.bhawana.lms.repo;

import com.bhawana.lms.domain.OpsAlert;
import com.bhawana.lms.domain.OpsAlertSeverity;
import com.bhawana.lms.domain.OpsAlertStatus;
import com.bhawana.lms.domain.OpsAlertType;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OpsAlertRepository extends JpaRepository<OpsAlert, UUID> {

    Page<OpsAlert> findByStatusOrderByCreatedAtDesc(OpsAlertStatus status, Pageable pageable);

    Page<OpsAlert> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * H30 — one filtered query for the alerts inbox. Every filter applies to
     * the full dataset before pagination (never to one fetched page), and the
     * caller supplies a stable sort (createdAt + id) so timestamp ties cannot
     * duplicate or omit rows across pages. Null parameters disable that
     * filter.
     */
    @Query("""
            select alert from OpsAlert alert
            where (:status is null or alert.status = :status)
            and (:severities is null or alert.severity in :severities)
            and (:subjectType is null or alert.subjectType = :subjectType)
            and (:queryLike is null
                or lower(alert.title) like :queryLike
                or lower(alert.message) like :queryLike)
            """)
    Page<OpsAlert> searchAlerts(
            @Param("status") OpsAlertStatus status,
            @Param("severities") Set<OpsAlertSeverity> severities,
            @Param("subjectType") String subjectType,
            @Param("queryLike") String queryLike,
            Pageable pageable
    );

    long countByStatus(OpsAlertStatus status);

    boolean existsByTypeAndSubjectIdAndStatus(
            OpsAlertType type,
            UUID subjectId,
            OpsAlertStatus status
    );

    boolean existsByTypeAndCorrelationIdAndStatus(
            OpsAlertType type,
            String correlationId,
            OpsAlertStatus status
    );
}
