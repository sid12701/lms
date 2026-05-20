package com.bhawana.lms.repo;

import com.bhawana.lms.domain.ReportRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRequestRepository extends JpaRepository<ReportRequest, UUID>, ReportRequestRepositoryCustom {

    List<ReportRequest> findTop50ByOrderByCreatedAtDesc();
}
