package com.bhawana.lms.repo;

import com.bhawana.lms.domain.AuthEventAudit;
import com.bhawana.lms.domain.AuthEventType;
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
}
