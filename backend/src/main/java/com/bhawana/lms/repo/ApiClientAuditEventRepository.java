package com.bhawana.lms.repo;

import com.bhawana.lms.domain.ApiClientAuditEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiClientAuditEventRepository extends JpaRepository<ApiClientAuditEvent, UUID> {
}
