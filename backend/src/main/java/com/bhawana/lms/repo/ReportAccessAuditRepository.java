package com.bhawana.lms.repo;

import com.bhawana.lms.domain.ReportAccessAudit;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportAccessAuditRepository extends JpaRepository<ReportAccessAudit, UUID> {
    long count();
}
