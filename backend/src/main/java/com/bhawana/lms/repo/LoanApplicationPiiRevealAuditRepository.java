package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanApplicationPiiRevealAudit;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanApplicationPiiRevealAuditRepository extends JpaRepository<LoanApplicationPiiRevealAudit, UUID> {
}
