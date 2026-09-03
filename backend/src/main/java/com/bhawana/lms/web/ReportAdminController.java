package com.bhawana.lms.web;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.web.ClientIpAddresses;
import com.bhawana.lms.domain.ReportRequest;
import com.bhawana.lms.service.AdminReportingService;
import com.bhawana.lms.service.AdminApiIdempotencyService;
import com.bhawana.lms.service.ReportAccessAuditService;
import com.bhawana.lms.service.ReportRequestService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/reports")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class ReportAdminController {

    private static final String REPORT_REQUEST_CREATE = "REPORT_REQUEST_CREATE";

    private final AdminReportingService adminReportingService;
    private final ReportRequestService reportRequestService;
    private final ReportAccessAuditService reportAccessAuditService;
    private final AdminApiIdempotencyService adminApiIdempotencyService;

    public ReportAdminController(
            AdminReportingService adminReportingService,
            ReportRequestService reportRequestService,
            ReportAccessAuditService reportAccessAuditService,
            AdminApiIdempotencyService adminApiIdempotencyService
    ) {
        this.adminReportingService = adminReportingService;
        this.reportRequestService = reportRequestService;
        this.reportAccessAuditService = reportAccessAuditService;
        this.adminApiIdempotencyService = adminApiIdempotencyService;
    }

    @GetMapping("/portfolio-mis/preview")
    public PortfolioMisPreviewPage previewPortfolioMisReport(
            @RequestParam(required = false) UUID lspId,
            @RequestParam(required = false) LocalDate disbursalDateFrom,
            @RequestParam(required = false) LocalDate disbursalDateTo,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size
    ) {
        AdminReportingService.PortfolioMisPage result = adminReportingService.getPortfolioMisPage(
                lspId, disbursalDateFrom, disbursalDateTo, page, size);
        List<PortfolioMisPreviewRow> content = result.content().stream()
                .map(ReportAdminController::toPreviewRow)
                .toList();
        return new PortfolioMisPreviewPage(content, result.totalElements(), result.page(), result.size());
    }

    @GetMapping("/portfolio-mis/summary")
    public AdminReportingService.PortfolioMisSummary getPortfolioMisSummary(
            @RequestParam(required = false) UUID lspId,
            @RequestParam(required = false) LocalDate disbursalDateFrom,
            @RequestParam(required = false) LocalDate disbursalDateTo
    ) {
        return adminReportingService.getPortfolioMisSummary(lspId, disbursalDateFrom, disbursalDateTo);
    }

    @GetMapping(value = "/portfolio-mis", produces = "text/csv")
    public ResponseEntity<byte[]> downloadPortfolioMisReport(
            @AuthenticationPrincipal Jwt principal,
            HttpServletRequest httpRequest,
            @RequestParam(required = false) UUID lspId,
            @RequestParam(required = false) LocalDate disbursalDateFrom,
            @RequestParam(required = false) LocalDate disbursalDateTo
    ) {
        AdminReportingService.GeneratedReport report = adminReportingService.generatePortfolioMisCsv(
                lspId,
                disbursalDateFrom,
                disbursalDateTo
        );

        reportAccessAuditService.recordMisCsvDownloaded(
                principal.getSubject(),
                ClientIpAddresses.resolve(httpRequest),
                CorrelationIdHolder.get(),
                lspId,
                disbursalDateFrom,
                disbursalDateTo,
                report.content().length
        );

        return downloadResponse(report.fileName(), report.mediaType(), report.content());
    }

    @PostMapping("/portfolio-mis/requests")
    public ReportRequestResponse createPortfolioMisRequest(
            Authentication authentication,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PortfolioMisReportRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doCreatePortfolioMisRequest(authentication, request);
        }
        return adminApiIdempotencyService.execute(
                REPORT_REQUEST_CREATE,
                idempotencyKey,
                request,
                ReportRequestResponse.class,
                () -> doCreatePortfolioMisRequest(authentication, request)
        );
    }

    private ReportRequestResponse doCreatePortfolioMisRequest(
            Authentication authentication,
            PortfolioMisReportRequest request
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

    /**
     * Manually drains the async report queue for E2E and environments where
     * {@code app.reports.processing.enabled=false}.
     */
    @PostMapping("/requests/process")
    public ProcessReportRequestsResponse processReportRequests(
            @RequestParam(defaultValue = "10") int batchSize
    ) {
        ReportRequestService.ProcessingSummary summary = reportRequestService.processPendingRequests(batchSize);
        return new ProcessReportRequestsResponse(summary.processed(), summary.completed(), summary.failed());
    }

    @GetMapping("/requests/{requestId}/download")
    public ResponseEntity<byte[]> downloadGeneratedReport(
            @AuthenticationPrincipal Jwt principal,
            HttpServletRequest httpRequest,
            @PathVariable UUID requestId
    ) {
        ReportRequestService.CompletedReportDownload download = reportRequestService.getCompletedReportDownload(requestId);
        reportAccessAuditService.recordMisRequestDownloaded(
                principal.getSubject(),
                ClientIpAddresses.resolve(httpRequest),
                CorrelationIdHolder.get(),
                download.reportRequest(),
                download.content().length
        );
        return downloadResponse(download.fileName(), download.mediaType(), download.content());
    }

    private static ResponseEntity<byte[]> downloadResponse(String fileName, String mediaType, byte[] content) {
        MediaType contentType = MediaType.parseMediaType(mediaType);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
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

    public record ProcessReportRequestsResponse(
            int processed,
            int completed,
            int failed
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

    public record InstallmentPreviewRow(
            int installmentNumber,
            LocalDate dueDate,
            BigDecimal installmentAmount,
            BigDecimal paidAmount,
            boolean received
    ) {
    }

    public record PortfolioMisPreviewPage(
            List<PortfolioMisPreviewRow> content,
            int totalElements,
            int page,
            int size
    ) {
    }

    public record PortfolioMisPreviewRow(
            String lspCode,
            String lspName,
            String applicationId,
            String externalLoanId,
            String borrowerFullName,
            String productCode,
            String productName,
            String accountNumber,
            BigDecimal principalAmount,
            String accountStatus,
            LocalDate disbursalDate,
            String delinquencyBucket,
            BigDecimal overdueAmount,
            String closureReason,
            LocalDate closedDate,
            LocalDate applicationCreatedAt,
            Integer loanYear,
            BigDecimal processingFeeAmount,
            BigDecimal disbursalAmount,
            BigDecimal netDisbursedAmount,
            BigDecimal interestRate,
            int tenureMonths,
            String borrowerId,
            BigDecimal perEmiAmount,
            List<InstallmentPreviewRow> installments,
            String loanStatusDisplay,
            BigDecimal foreclosedRepaidAmount,
            LocalDate foreclosureDate,
            LocalDate normalClosureDate,
            int daysPastDue,
            String customerName,
            String address,
            String zipCode,
            String borrowerState,
            String ifscCode,
            String bankAccountNumber,
            String gender,
            String aadharNumber,
            String panNumber,
            String profession,
            BigDecimal income
    ) {
    }

    private static PortfolioMisPreviewRow toPreviewRow(AdminReportingService.PortfolioMisRow row) {
        List<InstallmentPreviewRow> installmentPreviews = row.installments() == null
                ? List.of()
                : row.installments().stream()
                        .map(snap -> new InstallmentPreviewRow(
                                snap.installmentNumber(),
                                snap.dueDate(),
                                snap.installmentAmount(),
                                snap.paidAmount(),
                                snap.received()
                        ))
                        .toList();

        return new PortfolioMisPreviewRow(
                row.lspCode(),
                row.lspName(),
                row.applicationId(),
                row.externalLoanId(),
                row.borrowerFullName(),
                row.productCode(),
                row.productName(),
                row.accountNumber(),
                row.principalAmount(),
                row.accountStatus(),
                row.disbursalDate(),
                row.delinquencyBucket(),
                row.overdueAmount(),
                row.closureReason(),
                row.closedDate(),
                row.applicationCreatedAt(),
                row.loanYear(),
                row.processingFeeAmount(),
                row.disbursalAmount(),
                row.netDisbursedAmount(),
                row.interestRate(),
                row.tenureMonths(),
                row.borrowerId(),
                row.perEmiAmount(),
                installmentPreviews,
                row.loanStatusDisplay(),
                row.foreclosedRepaidAmount(),
                row.foreclosureDate(),
                row.normalClosureDate(),
                row.daysPastDue(),
                row.customerName(),
                row.address(),
                row.zipCode(),
                row.borrowerState(),
                row.ifscCode(),
                row.bankAccountNumber(),
                row.gender(),
                row.aadharNumber(),
                row.panNumber(),
                row.profession(),
                row.income()
        );
    }
}
