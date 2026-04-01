package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanApplicationAuditEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanApplicationAuditEventRepository extends JpaRepository<LoanApplicationAuditEvent, UUID> {

    List<LoanApplicationAuditEvent> findTop25ByLoanApplication_IdOrderByCreatedAtDesc(UUID applicationId);
}
