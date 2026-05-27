package com.bhawana.lms.repo;

import com.bhawana.lms.domain.OpsAlert;
import com.bhawana.lms.domain.OpsAlertStatus;
import com.bhawana.lms.domain.OpsAlertType;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OpsAlertRepository extends JpaRepository<OpsAlert, UUID> {

    Page<OpsAlert> findByStatusOrderByCreatedAtDesc(OpsAlertStatus status, Pageable pageable);

    Page<OpsAlert> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByStatus(OpsAlertStatus status);

    boolean existsByTypeAndSubjectIdAndStatus(
            OpsAlertType type,
            UUID subjectId,
            OpsAlertStatus status
    );

    boolean existsByTypeAndCorrelationIdAndStatus(
            OpsAlertType type,
            String correlationId,
            OpsAlertStatus status
    );
}
