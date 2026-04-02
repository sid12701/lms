package com.bhawana.lms.web;

import com.bhawana.lms.domain.ReportRequest;
import com.bhawana.lms.service.AdminReportingService;
import com.bhawana.lms.service.ReportRequestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/reports")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class ReportAdminController {

    private final AdminReportingService adminReportingService;
    private final ReportRequestService reportRequestService;

    public ReportAdminController(
            AdminReportingService adminReportingService,
            ReportRequestService reportRequestService
    ) {
        this.adminReportingService = adminReportingService;
        this.reportRequestService = reportRequestService;
    }

    @GetMapping(value = "/portfolio-mis", produces = "text/csv")
    public ResponseEntity<byte[]> downloadPortfolioMisReport(
            @RequestParam(required = false) UUID lspId,
            @RequestParam(required = false) LocalDate disbursalDateFrom,
            @RequestParam(required = false) LocalDate disbursalDateTo
    ) {
        AdminReportingService.GeneratedReport report = adminReportingService.generatePortfolioMisCsv(
                lspId,
                disbursalDateFrom,
                disbursalDateTo
        );

        return downloadResponse(report.fileName(), report.mediaType(), report.content());
    }

    @PostMapping("/portfolio-mis/requests")
    public ReportRequestResponse createPortfolioMisRequest(
            Authentication authentication,
            @Valid @RequestBody PortfolioMisReportRequest request
    ) {
        return toResponse(reportRequestService.createPortfolioMisRequest(
                request.lspId(),
                request.disbursalDateFrom(),
                request.disbursalDateTo(),
                request.recipientEmail(),
                authentication.getName()
        ));
    }

    @GetMapping("/requests")
    public List<ReportRequestResponse> listReportRequests() {
        return reportRequestService.listRequests().stream()
                .map(ReportAdminController::toResponse)
                .toList();
    }

    @GetMapping("/requests/{requestId}/download")
    public ResponseEntity<byte[]> downloadGeneratedReport(@PathVariable UUID requestId) {
        ReportRequestService.GeneratedStoredReport report = reportRequestService.getCompletedReport(requestId);
        return downloadResponse(report.fileName(), report.mediaType(), report.content());
    }

    private static ResponseEntity<byte[]> downloadResponse(String fileName, String mediaType, byte[] content) {
        MediaType contentType = MediaType.parseMediaType(mediaType);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(contentType)
                .body(content);
    }

    private static ReportRequestResponse toResponse(ReportRequest reportRequest) {
        return new ReportRequestResponse(
                reportRequest.getId().toString(),
                reportRequest.getReportType().name(),
                reportRequest.getStatus().name(),
                reportRequest.getRequestedByUsername(),
                reportRequest.getLsp() == null ? null : reportRequest.getLsp().getId().toString(),
                reportRequest.getLsp() == null ? null : reportRequest.getLsp().getCode(),
                reportRequest.getLsp() == null ? null : reportRequest.getLsp().getName(),
                reportRequest.getDisbursalDateFrom(),
                reportRequest.getDisbursalDateTo(),
                reportRequest.getNotificationEmail(),
                reportRequest.getNotificationSentAt() == null ? null : reportRequest.getNotificationSentAt().toString(),
                reportRequest.getNotificationErrorMessage(),
                reportRequest.getFileName(),
                reportRequest.getMediaType(),
                reportRequest.getErrorMessage(),
                reportRequest.getCompletedAt() == null ? null : reportRequest.getCompletedAt().toString(),
                reportRequest.getCreatedAt().toString(),
                reportRequest.getUpdatedAt().toString()
        );
    }

    public record PortfolioMisReportRequest(
            UUID lspId,
            LocalDate disbursalDateFrom,
            LocalDate disbursalDateTo,
            @Email String recipientEmail
    ) {
    }

    public record ReportRequestResponse(
            String id,
            String reportType,
            String status,
            String requestedByUsername,
            String lspId,
            String lspCode,
            String lspName,
            LocalDate disbursalDateFrom,
            LocalDate disbursalDateTo,
            String notificationEmail,
            String notificationSentAt,
            String notificationErrorMessage,
            String fileName,
            String mediaType,
            String errorMessage,
            String completedAt,
            String createdAt,
            String updatedAt
    ) {
    }
}
