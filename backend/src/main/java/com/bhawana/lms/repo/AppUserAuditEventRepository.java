package com.bhawana.lms.repo;

import com.bhawana.lms.domain.AppUserAuditEvent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserAuditEventRepository extends JpaRepository<AppUserAuditEvent, UUID> {

    Optional<AppUserAuditEvent> findTopByUser_IdOrderByCreatedAtDesc(UUID userId);
}
