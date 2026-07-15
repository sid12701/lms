package com.bhawana.lms.repo;

import com.bhawana.lms.domain.BorrowerPiiRevealAudit;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BorrowerPiiRevealAuditRepository extends JpaRepository<BorrowerPiiRevealAudit, UUID> {

    long countByBorrower_Id(UUID borrowerId);
}
