package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LspAuditEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LspAuditEventRepository extends JpaRepository<LspAuditEvent, UUID> {

    List<LspAuditEvent> findByLsp_IdOrderByCreatedAtDesc(UUID lspId);
}
