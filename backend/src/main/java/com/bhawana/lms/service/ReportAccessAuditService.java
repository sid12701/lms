package com.bhawana.lms.service;

import com.bhawana.lms.domain.ReportAccessAudit;
import com.bhawana.lms.domain.ReportAccessAuditAction;
import com.bhawana.lms.domain.ReportAccessAuditReportType;
import com.bhawana.lms.domain.ReportRequest;
import com.bhawana.lms.repo.ReportAccessAuditRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportAccessAuditService {

    private final ReportAccessAuditRepository reportAccessAuditRepository;
    private final ObjectMapper objectMapper;

    public ReportAccessAuditService(
            ReportAccessAuditRepository reportAccessAuditRepository,
            ObjectMapper objectMapper
    ) {
        this.reportAccessAuditRepository = reportAccessAuditRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void recordMisCsvDownloaded(
            String actorUsername,
            String actorIp,
            String correlationId,
            UUID lspId,
            LocalDate disbursalDateFrom,
            LocalDate disbursalDateTo,
            long byteCount
    ) {
        reportAccessAuditRepository.save(new ReportAccessAudit(
                actorUsername,
                actorIp,
                correlationId,
                ReportAccessAuditAction.MIS_CSV_DOWNLOADED,
                ReportAccessAuditReportType.PORTFOLIO_MIS,
                serializeFilterPayload(lspId, disbursalDateFrom, disbursalDateTo),
                byteCount,
                null
        ));
    }

    @Transactional
    public void recordMisRequestDownloaded(
            String actorUsername,
            String actorIp,
            String correlationId,
            ReportRequest reportRequest,
            long byteCount
    ) {
        UUID lspId = reportRequest.getLsp() == null ? null : reportRequest.getLsp().getId();
        reportAccessAuditRepository.save(new ReportAccessAudit(
                actorUsername,
                actorIp,
                correlationId,
                ReportAccessAuditAction.MIS_REQUEST_DOWNLOADED,
                ReportAccessAuditReportType.PORTFOLIO_MIS,
                serializeFilterPayload(lspId, reportRequest.getDisbursalDateFrom(), reportRequest.getDisbursalDateTo()),
                byteCount,
                reportRequest
        ));
    }

    private String serializeFilterPayload(UUID lspId, LocalDate disbursalDateFrom, LocalDate disbursalDateTo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lspId == null ? null : lspId.toString());
        payload.put("disbursalDateFrom", disbursalDateFrom == null ? null : disbursalDateToString(disbursalDateFrom));
        payload.put("disbursalDateTo", disbursalDateTo == null ? null : disbursalDateToString(disbursalDateTo));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize report access filter payload.", exception);
        }
    }

    private static String disbursalDateToString(LocalDate date) {
        return date.toString();
    }
}
