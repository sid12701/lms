package com.bhawana.lms.repo;

import com.bhawana.lms.domain.OpsAlert;
import com.bhawana.lms.domain.OpsAlertStatus;
import com.bhawana.lms.domain.OpsAlertType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OpsAlertRepository extends JpaRepository<OpsAlert, UUID> {

    List<OpsAlert> findByStatusOrderByCreatedAtDesc(OpsAlertStatus status);

    List<OpsAlert> findAllByOrderByCreatedAtDesc();

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
