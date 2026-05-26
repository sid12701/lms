package com.bhawana.lms.repo;

import com.bhawana.lms.domain.AppUserAuditEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserAuditEventRepository extends JpaRepository<AppUserAuditEvent, UUID> {
}
