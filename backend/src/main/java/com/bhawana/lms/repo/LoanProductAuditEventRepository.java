package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanProductAuditEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanProductAuditEventRepository extends JpaRepository<LoanProductAuditEvent, UUID> {

    List<LoanProductAuditEvent> findTop25ByLoanProduct_IdOrderByCreatedAtDesc(UUID productId);
}
