package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import com.bhawana.lms.domain.ReportRequest;
import com.bhawana.lms.domain.ReportRequestStatus;
import com.bhawana.lms.domain.ReportType;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.ReportRequestRepository;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReportRequestService {

    private static final Logger log = LoggerFactory.getLogger(ReportRequestService.class);
    private static final List<ReportRequestStatus> TERMINAL_STATUSES =
            List.of(ReportRequestStatus.COMPLETED, ReportRequestStatus.FAILED);
    private static final int ERROR_MESSAGE_LIMIT = 1000;

    private final ReportRequestRepository reportRequestRepository;
    private final AdminReportingService adminReportingService;
    private final AppUserRepository appUserRepository;
    private final ReportNotificationService reportNotificationService;
    private final ReportStorageService reportStorageService;
    private final ReportWorkerMetrics reportWorkerMetrics;
    private final TransactionTemplate requiresNewTransactionTemplate;
    private final String workerOwner;
    private final long processingLeaseMillis;

    public ReportRequestService(
            ReportRequestRepository reportRequestRepository,
            AdminReportingService adminReportingService,
            AppUserRepository appUserRepository,
            ReportNotificationService reportNotificationService,
            ReportStorageService reportStorageService,
            ReportWorkerMetrics reportWorkerMetrics,
            PlatformTransactionManager transactionManager,
            @Value("${app.reports.processing.lease-owner:report-worker}") String leaseOwnerPrefix,
            @Value("${app.reports.processing.lease-ms:300000}") long processingLeaseMillis
    ) {
        this.reportRequestRepository = reportRequestRepository;
        this.adminReportingService = adminReportingService;
        this.appUserRepository = appUserRepository;
        this.reportNotificationService = reportNotificationService;
        this.reportStorageService = reportStorageService;
        this.reportWorkerMetrics = reportWorkerMetrics;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.workerOwner = DisbursementIntentWorkflowService.buildWorkerOwner(leaseOwnerPrefix);
        this.processingLeaseMillis = processingLeaseMillis;
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
                .orElseThrow(() -> new ResourceNotFoundException("Unknown report request id: " + requestId));
        if (reportRequest.getStatus() != ReportRequestStatus.COMPLETED || reportRequest.getStorageKey() == null) {
            throw new ApiConflictException("REPORT_NOT_READY", "Report is not ready for download.");
        }

        byte[] content = reportStorageService.retrieve(reportRequest.getStorageKey());
        return new CompletedReportDownload(
                reportRequest,
                reportRequest.getFileName(),
                reportRequest.getMediaType(),
                content
        );
    }

    /**
     * Claims a bounded batch, then generates, stores and resolves each request with NO
     * transaction spanning generation, object storage or SMTP (H24).
     *
     * <p>The claim itself is a short committed transaction that stamps this worker's owner and
     * lease expiry, so a crash mid-batch leaves a reclaimable row instead of a hidden lock or a
     * silently re-queued one. The terminal outcome is written by a second fenced transaction —
     * if the lease was reclaimed meanwhile, a stale worker cannot overwrite the new owner's
     * work. Notifications are a durable pending state retried by {@link
     * #processPendingNotifications(int)}.
     */
    public ProcessingSummary processPendingRequests(int batchSize) {
        return TenantScopedExecution.callAsAdmin(() -> processPendingRequestsWithinAdminScope(batchSize));
    }

    /**
     * Delivers due notifications left over from completed processing: rows whose terminal state
     * committed but whose send never recorded — for example after a crash between commit and
     * send, or while delivery was disabled. Each row is claimed under the same worker lease
     * before its send so two workers cannot double-deliver; delivery is at-least-once.
     */
    public int processPendingNotifications(int batchSize) {
        if (!reportNotificationService.isDeliveryEnabled()) {
            return 0;
        }
        return TenantScopedExecution.callAsAdmin(() -> {
            List<UUID> claimableIds = requiresNewTransactionTemplate.execute(status ->
                    reportRequestRepository.findClaimableNotificationIds(
                            TERMINAL_STATUSES,
                            Instant.now(),
                            PageRequest.of(0, batchSize)
                    ));
            int attempted = 0;
            for (UUID requestId : claimableIds) {
                if (attemptNotification(requestId)) {
                    attempted += 1;
                }
            }
            return attempted;
        });
    }

    private ProcessingSummary processPendingRequestsWithinAdminScope(int batchSize) {
        long startedAt = System.nanoTime();
        List<ReportRequest> claimed = requiresNewTransactionTemplate.execute(status ->
                reportRequestRepository.claimBatchForProcessing(
                        workerOwner,
                        Instant.now().plusMillis(processingLeaseMillis),
                        batchSize
                ));

        int completed = 0;
        int failed = 0;

        for (ReportRequest reportRequest : claimed) {
            long requestStartedAt = System.nanoTime();
            ReportRequestStatus outcome = processClaimedRequest(reportRequest);
            if (outcome == ReportRequestStatus.COMPLETED) {
                completed += 1;
            } else if (outcome == ReportRequestStatus.FAILED) {
                failed += 1;
            }
            reportWorkerMetrics.recordProcessing(
                    java.time.Duration.ofMillis(elapsedMillis(requestStartedAt)),
                    outcome == null ? "claim_lost" : outcome.name().toLowerCase()
            );
            if (outcome != null) {
                attemptNotification(reportRequest.getId());
            }
            log.info(
                    "report_request_processed requestId={} reportType={} requestedBy={} lspId={} finalStatus={} durationMs={}",
                    reportRequest.getId(),
                    reportRequest.getReportType(),
                    reportRequest.getRequestedByUsername(),
                    reportRequest.getLsp() == null ? null : reportRequest.getLsp().getId(),
                    outcome,
                    elapsedMillis(requestStartedAt)
            );
        }

        log.info(
                "report_request_batch_completed processed={} completed={} failed={} durationMs={}",
                claimed.size(),
                completed,
                failed,
                elapsedMillis(startedAt)
        );
        return new ProcessingSummary(claimed.size(), completed, failed);
    }

    /**
     * Generates and stores one claimed request outside any transaction, then records the
     * outcome in a fenced write. Returns the recorded terminal status, or null when the claim
     * was lost to another worker mid-flight.
     */
    private ReportRequestStatus processClaimedRequest(ReportRequest reportRequest) {
        java.nio.file.Path tempFile = null;
        try {
            // Bounded temp file, not heap: the export streams to disk and the upload streams
            // from disk (H25). The as-of cutoff pins the snapshot to the start of this run.
            tempFile = java.nio.file.Files.createTempFile(
                    "report-request-" + reportRequest.getId() + "-", ".csv");
            Instant asOf = Instant.now();
            AdminReportingService.GeneratedReport generatedReport = switch (reportRequest.getReportType()) {
                case PORTFOLIO_MIS -> adminReportingService.generatePortfolioMisCsv(
                        reportRequest.getLsp() == null ? null : reportRequest.getLsp().getId(),
                        reportRequest.getDisbursalDateFrom(),
                        reportRequest.getDisbursalDateTo(),
                        asOf,
                        tempFile
                );
            };

            // Deterministic key per request: a reclaimed retry overwrites the same object
            // instead of stacking up orphaned duplicates.
            ReportStorageService.StoredReport stored = reportStorageService.store(
                    new ReportStorageService.ReportStorageDescriptor(
                            reportRequest.getId(),
                            reportRequest.getReportType(),
                            generatedReport.fileName(),
                            generatedReport.mediaType()
                    ),
                    tempFile
            );

            return recordOutcome(
                    reportRequest,
                    ReportRequestStatus.COMPLETED,
                    stored.fileName(),
                    stored.mediaType(),
                    stored.storageKey(),
                    null,
                    Instant.now()
            );
        } catch (RuntimeException | java.io.IOException exception) {
            return recordOutcome(
                    reportRequest,
                    ReportRequestStatus.FAILED,
                    null,
                    null,
                    null,
                    truncateError(exception.getMessage()),
                    null
            );
        } finally {
            deleteTempFile(tempFile, reportRequest.getId());
        }
    }

    private void deleteTempFile(java.nio.file.Path tempFile, UUID requestId) {
        if (tempFile == null) {
            return;
        }
        try {
            java.nio.file.Files.deleteIfExists(tempFile);
        } catch (java.io.IOException exception) {
            log.warn("report_request_temp_cleanup_failed requestId={} path={}", requestId, tempFile, exception);
        }
    }

    private ReportRequestStatus recordOutcome(
            ReportRequest reportRequest,
            ReportRequestStatus status,
            String fileName,
            String mediaType,
            String storageKey,
            String errorMessage,
            Instant completedAt
    ) {
        int updated = reportRequestRepository.recordOutcomeIfOwned(
                reportRequest.getId(),
                reportRequest.getProcessingAttempt(),
                workerOwner,
                status,
                fileName,
                mediaType,
                storageKey,
                errorMessage,
                completedAt,
                ReportRequestStatus.PROCESSING,
                Instant.now()
        );
        if (updated != 1) {
            log.warn(
                    "report_request_outcome_discarded requestId={} intendedStatus={} reason=claim_lost",
                    reportRequest.getId(),
                    status
            );
            return null;
        }
        return status;
    }

    /**
     * Claims the request's pending notification under this worker's lease, sends it outside
     * any transaction, then records the result fenced on that lease. Returns false when the
     * row was already claimed or no longer needs a send.
     */
    private boolean attemptNotification(UUID requestId) {
        if (!reportNotificationService.isDeliveryEnabled()) {
            return false;
        }
        Optional<ReportRequest> claimed = requiresNewTransactionTemplate.execute(status ->
                reportRequestRepository.tryClaimNotification(
                        requestId,
                        workerOwner,
                        Instant.now().plusMillis(processingLeaseMillis),
                        TERMINAL_STATUSES,
                        Instant.now()
                ) == 1
                        ? reportRequestRepository.findById(requestId)
                        : Optional.empty());
        if (claimed.isEmpty()) {
            return false;
        }
        ReportRequest request = claimed.get();
        ReportNotificationService.NotificationResult result =
                reportNotificationService.sendTerminalStatusNotification(request);
        int recorded = reportRequestRepository.recordNotificationIfOwned(
                request.getId(),
                request.getProcessingAttempt(),
                workerOwner,
                result.sentAt(),
                result.attempted() ? result.errorMessage() : "notification delivery was not attempted",
                Instant.now()
        );
        if (recorded != 1) {
            // The lease was lost between claim and record. If the mail went out the next
            // claim will redeliver it — the notification contract is at-least-once.
            log.warn(
                    "report_request_notification_record_discarded requestId={} reason=claim_lost attempted={}",
                    request.getId(),
                    result.attempted()
            );
        }
        return true;
    }

    private static String truncateError(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= ERROR_MESSAGE_LIMIT ? value : value.substring(0, ERROR_MESSAGE_LIMIT);
    }

    private static void validateDateRange(LocalDate disbursalDateFrom, LocalDate disbursalDateTo) {
        if (disbursalDateFrom != null && disbursalDateTo != null && disbursalDateFrom.isAfter(disbursalDateTo)) {
            throw new BusinessRuleViolationException(
                    "INVALID_DISBURSAL_DATE_RANGE",
                    "disbursalDateFrom cannot be after disbursalDateTo.",
                    Map.of("disbursalDateFrom", "cannot be after disbursalDateTo")
            );
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
