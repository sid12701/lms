package com.bhawana.lms.service;

import com.bhawana.lms.common.web.PagedResult;
import com.bhawana.lms.common.web.PaginationResponseBuilder;
import com.bhawana.lms.domain.OpsAlert;
import com.bhawana.lms.domain.OpsAlertSeverity;
import com.bhawana.lms.domain.OpsAlertStatus;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.repo.OpsAlertRepository;
import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import com.bhawana.lms.tenant.TenantDataAccessMode;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OpsAlertService {

    private final OpsAlertRepository opsAlertRepository;

    public OpsAlertService(OpsAlertRepository opsAlertRepository) {
        this.opsAlertRepository = opsAlertRepository;
    }

    /**
     * Creates an alert only when no open (NEW) alert already exists for the same
     * type + subject — prevents scheduled rules from spamming the inbox.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OpsAlert createAlertIfAbsent(
            OpsAlertType type,
            OpsAlertSeverity severity,
            String title,
            String message,
            String subjectType,
            UUID subjectId,
            String correlationId,
            String contextJson
    ) {
        if (subjectId != null
                && opsAlertRepository.existsByTypeAndSubjectIdAndStatus(type, subjectId, OpsAlertStatus.NEW)) {
            return null;
        }
        if (subjectId == null
                && correlationId != null
                && opsAlertRepository.existsByTypeAndCorrelationIdAndStatus(type, correlationId, OpsAlertStatus.NEW)) {
            return null;
        }
        return createAlert(type, severity, title, message, subjectType, subjectId, correlationId, contextJson);
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

    /**
     * Always paginated: F-06. The unbounded list method is gone — callers that
     * omit offset/limit get offset=0 and PaginationResponseBuilder.DEFAULT_LIMIT
     * rows, never the whole ops_alert table.
     */
    @Transactional(readOnly = true)
    public PagedResult<OpsAlert> listAlerts(
            OpsAlertStatus status,
            Integer offset,
            Integer limit,
            boolean includePaginationDetails
    ) {
        int resolvedOffset = offset == null ? 0 : offset;
        int resolvedLimit = limit == null ? PaginationResponseBuilder.DEFAULT_LIMIT : limit;
        int safeLimit = Math.max(resolvedLimit, 1);
        int pageNumber = resolvedOffset / safeLimit;

        PageRequest pageRequest = PageRequest.of(pageNumber, safeLimit);
        Page<OpsAlert> page = status == null
                ? opsAlertRepository.findAllByOrderByCreatedAtDesc(pageRequest)
                : opsAlertRepository.findByStatusOrderByCreatedAtDesc(status, pageRequest);
        return new PagedResult<>(page.getContent(), page.getTotalElements(), resolvedOffset, resolvedLimit);
    }

    @Transactional
    public OpsAlert acknowledge(UUID alertId, String actorUsername, String note) {
        if (note != null && note.length() > 500) {
            throw new IllegalArgumentException(
                    "Acknowledgement note must be 500 characters or fewer."
            );
        }
        OpsAlert alert = opsAlertRepository.findById(alertId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown ops alert id: " + alertId));
        alert.acknowledge(actorUsername, note);
        return opsAlertRepository.save(alert);
    }
}
