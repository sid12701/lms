package com.bhawana.lms.repo;

import com.bhawana.lms.domain.ReportRequest;
import com.bhawana.lms.domain.ReportRequestStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRequestRepository extends JpaRepository<ReportRequest, UUID> {

    List<ReportRequest> findTop50ByOrderByCreatedAtDesc();

    List<ReportRequest> findByStatusInOrderByCreatedAtAsc(List<ReportRequestStatus> statuses, Pageable pageable);
}
