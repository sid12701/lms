package com.bhawana.lms.repo;

import com.bhawana.lms.domain.BorrowerBankDetailsUpdateAudit;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BorrowerBankDetailsUpdateAuditRepository extends JpaRepository<BorrowerBankDetailsUpdateAudit, UUID> {

    long countByBorrower_IdAndCreatedAtAfter(UUID borrowerId, Instant since);
}
