package com.bhawana.lms.repo;

import com.bhawana.lms.domain.AuthEventAudit;
import com.bhawana.lms.domain.AuthEventType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthEventAuditRepository extends JpaRepository<AuthEventAudit, UUID> {

    @Query("""
            SELECT event
            FROM AuthEventAudit event
            WHERE (:username IS NULL OR event.username = :username)
              AND (:eventType IS NULL OR event.eventType = :eventType)
            ORDER BY event.createdAt DESC
            """)
    Page<AuthEventAudit> search(
            @Param("username") String username,
            @Param("eventType") AuthEventType eventType,
            Pageable pageable
    );

    long countByUsernameAndEventType(String username, AuthEventType eventType);

    @Query("""
            SELECT event.username AS username, event.actorIp AS actorIp, COUNT(event) AS failureCount
            FROM AuthEventAudit event
            WHERE event.eventType = com.bhawana.lms.domain.AuthEventType.LOGIN_FAILED
              AND event.createdAt >= :since
              AND event.actorIp IS NOT NULL
            GROUP BY event.username, event.actorIp
            HAVING COUNT(event) >= :threshold
            """)
    List<UsernameIpFailureProjection> findLoginFailureGroupsAtOrAboveThreshold(
            @Param("since") Instant since,
            @Param("threshold") long threshold
    );

    @Query("""
            SELECT event.username AS username,
                   COUNT(event) AS failureCount,
                   COUNT(DISTINCT event.actorIp) AS distinctIpCount
            FROM AuthEventAudit event
            WHERE event.eventType = com.bhawana.lms.domain.AuthEventType.LOGIN_FAILED
              AND event.createdAt >= :since
              AND event.actorIp IS NOT NULL
            GROUP BY event.username
            HAVING COUNT(event) >= :threshold
               AND COUNT(DISTINCT event.actorIp) >= :distinctIpMin
            """)
    List<UsernameDistributedFailureProjection> findLoginFailureUserGroupsAtOrAboveDistributedThreshold(
            @Param("since") Instant since,
            @Param("threshold") long threshold,
            @Param("distinctIpMin") long distinctIpMin
    );

    interface UsernameIpFailureProjection {
        String getUsername();

        String getActorIp();

        long getFailureCount();
    }

    interface UsernameDistributedFailureProjection {
        String getUsername();

        long getFailureCount();

        long getDistinctIpCount();
    }
}
