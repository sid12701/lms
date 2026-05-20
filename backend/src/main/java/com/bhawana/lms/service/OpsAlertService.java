package com.bhawana.lms.service;

import com.bhawana.lms.domain.OpsAlert;
import com.bhawana.lms.domain.OpsAlertSeverity;
import com.bhawana.lms.domain.OpsAlertStatus;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.repo.OpsAlertRepository;
import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import com.bhawana.lms.tenant.TenantDataAccessMode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OpsAlertService {

    private final OpsAlertRepository opsAlertRepository;

    public OpsAlertService(OpsAlertRepository opsAlertRepository) {
        this.opsAlertRepository = opsAlertRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OpsAlert createAlert(
            OpsAlertType type,
            OpsAlertSeverity severity,
            String title,
            String message,
            String subjectType,
            UUID subjectId,
            String correlationId,
            String contextJson
    ) {
        // Ops alerts are a global/internal facility and must not ride on the
        // tenant datasource. Flip to admin for this REQUIRES_NEW boundary and
        // restore the caller's context on exit so the outer tenant-bound
        // transaction (if any) continues correctly after we return.
        TenantDataAccessMode previousMode = TenantDataAccessContextHolder.getMode();
        UUID previousLspId = TenantDataAccessContextHolder.getCurrentLspId();
        TenantDataAccessContextHolder.useAdmin();
        try {
            return opsAlertRepository.save(new OpsAlert(
                    type,
                    severity,
                    title,
                    message,
                    subjectType,
                    subjectId,
                    correlationId,
                    contextJson
            ));
        } finally {
            if (previousMode == TenantDataAccessMode.TENANT && previousLspId != null) {
                TenantDataAccessContextHolder.useTenant(previousLspId);
            } else {
                TenantDataAccessContextHolder.useAdmin();
            }
        }
    }

    @Transactional(readOnly = true)
    public List<OpsAlert> listAlerts(OpsAlertStatus status) {
        return status == null
                ? opsAlertRepository.findAllByOrderByCreatedAtDesc()
                : opsAlertRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    @Transactional
    public OpsAlert acknowledge(UUID alertId, String actorUsername) {
        OpsAlert alert = opsAlertRepository.findById(alertId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown ops alert id: " + alertId));
        alert.acknowledge(actorUsername);
        return opsAlertRepository.save(alert);
    }
}
