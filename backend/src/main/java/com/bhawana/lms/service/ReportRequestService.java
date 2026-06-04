package com.bhawana.lms.service;

import com.bhawana.lms.domain.ReportRequest;
import com.bhawana.lms.domain.ReportRequestStatus;
import com.bhawana.lms.domain.ReportType;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.ReportRequestRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportRequestService {

    private static final Logger log = LoggerFactory.getLogger(ReportRequestService.class);

    private final ReportRequestRepository reportRequestRepository;
    private final AdminReportingService adminReportingService;
    private final AppUserRepository appUserRepository;
    private final ReportNotificationService reportNotificationService;
    private final ReportStorageService reportStorageService;

    public ReportRequestService(
            ReportRequestRepository reportRequestRepository,
            AdminReportingService adminReportingService,
            AppUserRepository appUserRepository,
            ReportNotificationService reportNotificationService,
            ReportStorageService reportStorageService
    ) {
        this.reportRequestRepository = reportRequestRepository;
        this.adminReportingService = adminReportingService;
        this.appUserRepository = appUserRepository;
        this.reportNotificationService = reportNotificationService;
        this.reportStorageService = reportStorageService;
    }

    @Transactional
    public ReportRequest createPortfolioMisRequest(
            UUID lspId,
            LocalDate disbursalDateFrom,
            LocalDate disbursalDateTo,
            String recipientEmail,
            String requestedByUsername
    ) {
        validateDateRange(disbursalDateFrom, disbursalDateTo);
        ReportRequest reportRequest = new ReportRequest(
                ReportType.PORTFOLIO_MIS,
                adminReportingService.getOptionalLsp(lspId),
                disbursalDateFrom,
                disbursalDateTo,
                requestedByUsername,
                resolveNotificationEmail(recipientEmail, requestedByUsername)
        );
        return reportRequestRepository.save(reportRequest);
    }

    @Transactional(readOnly = true)
    public List<ReportRequest> listRequests() {
        return reportRequestRepository.findTop50ByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public GeneratedStoredReport getCompletedReport(UUID requestId) {
        CompletedReportDownload download = getCompletedReportDownload(requestId);
        return new GeneratedStoredReport(
                download.fileName(),
                download.mediaType(),
                download.content()
        );
    }

    @Transactional(readOnly = true)
    public CompletedReportDownload getCompletedReportDownload(UUID requestId) {
        ReportRequest reportRequest = reportRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown report request id: " + requestId));
        if (reportRequest.getStatus() != ReportRequestStatus.COMPLETED || reportRequest.getStorageKey() == null) {
            throw new IllegalArgumentException("Report is not ready for download.");
        }

        byte[] content = reportStorageService.retrieve(reportRequest.getStorageKey());
        return new CompletedReportDownload(
                reportRequest,
                reportRequest.getFileName(),
                reportRequest.getMediaType(),
                content
        );
    }

    @Transactional
    public ProcessingSummary processPendingRequests(int batchSize) {
        long startedAt = System.nanoTime();
        List<ReportRequest> pendingRequests = reportRequestRepository.claimBatchForProcessing(
                List.of(ReportRequestStatus.PENDING),
                batchSize
        );

        int completed = 0;
        int failed = 0;

        for (ReportRequest reportRequest : pendingRequests) {
            long requestStartedAt = System.nanoTime();
            reportRequest.markProcessing();
            reportRequestRepository.saveAndFlush(reportRequest);

            try {
                AdminReportingService.GeneratedReport generatedReport = switch (reportRequest.getReportType()) {
                    case PORTFOLIO_MIS -> adminReportingService.generatePortfolioMisCsv(
                            reportRequest.getLsp() == null ? null : reportRequest.getLsp().getId(),
                            reportRequest.getDisbursalDateFrom(),
                            reportRequest.getDisbursalDateTo()
                    );
                };

                ReportStorageService.StoredReport stored = reportStorageService.store(
                        new ReportStorageService.ReportStorageDescriptor(
                                reportRequest.getId(),
                                reportRequest.getReportType(),
                                generatedReport.fileName(),
                                generatedReport.mediaType()
                        ),
                        generatedReport.content()
                );

                reportRequest.markCompleted(
                        stored.fileName(),
                        stored.mediaType(),
                        stored.storageKey(),
                        Instant.now()
                );
                completed += 1;
            } catch (RuntimeException exception) {
                reportRequest.markFailed(exception.getMessage());
                failed += 1;
            }

            ReportNotificationService.NotificationResult notificationResult =
                    reportNotificationService.sendTerminalStatusNotification(reportRequest);
            if (notificationResult.attempted()) {
                if (notificationResult.errorMessage() == null) {
                    reportRequest.markNotificationSent(notificationResult.sentAt());
                } else {
                    reportRequest.markNotificationFailed(notificationResult.errorMessage());
                }
            }

            reportRequestRepository.save(reportRequest);
            log.info(
                    "report_request_processed requestId={} reportType={} requestedBy={} lspId={} finalStatus={} notificationAttempted={} notificationErrorPresent={} durationMs={}",
                    reportRequest.getId(),
                    reportRequest.getReportType(),
                    reportRequest.getRequestedByUsername(),
                    reportRequest.getLsp() == null ? null : reportRequest.getLsp().getId(),
                    reportRequest.getStatus(),
                    notificationResult.attempted(),
                    notificationResult.errorMessage() != null,
                    elapsedMillis(requestStartedAt)
            );
        }

        log.info(
                "report_request_batch_completed processed={} completed={} failed={} durationMs={}",
                pendingRequests.size(),
                completed,
                failed,
                elapsedMillis(startedAt)
        );
        return new ProcessingSummary(pendingRequests.size(), completed, failed);
    }

    private static void validateDateRange(LocalDate disbursalDateFrom, LocalDate disbursalDateTo) {
        if (disbursalDateFrom != null && disbursalDateTo != null && disbursalDateFrom.isAfter(disbursalDateTo)) {
            throw new IllegalArgumentException("disbursalDateFrom cannot be after disbursalDateTo.");
        }
    }

    private String resolveNotificationEmail(String recipientEmail, String requestedByUsername) {
        if (recipientEmail != null && !recipientEmail.isBlank()) {
            return recipientEmail.trim();
        }
        return appUserRepository.findByUsername(requestedByUsername)
                .map(appUser -> appUser.getEmail() == null ? null : appUser.getEmail().trim())
                .filter(email -> !email.isBlank())
                .orElse(null);
    }

    public record GeneratedStoredReport(
            String fileName,
            String mediaType,
            byte[] content
    ) {
    }

    public record CompletedReportDownload(
            ReportRequest reportRequest,
            String fileName,
            String mediaType,
            byte[] content
    ) {
    }

    public record ProcessingSummary(
            int processed,
            int completed,
            int failed
    ) {
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
