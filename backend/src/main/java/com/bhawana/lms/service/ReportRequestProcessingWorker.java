package com.bhawana.lms.service;

import com.bhawana.lms.config.ScheduledJobThreadingConfig;
import com.bhawana.lms.tenant.TenantScopedExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReportRequestProcessingWorker {

    private static final Logger log = LoggerFactory.getLogger(ReportRequestProcessingWorker.class);

    private final ReportRequestService reportRequestService;
    private final JobObservabilitySupport jobObservability;
    private final boolean processingEnabled;
    private final int batchSize;

    public ReportRequestProcessingWorker(
            ReportRequestService reportRequestService,
            JobObservabilitySupport jobObservability,
            @Value("${app.reports.processing.enabled:true}") boolean processingEnabled,
            @Value("${app.reports.processing.batch-size:10}") int batchSize
    ) {
        this.reportRequestService = reportRequestService;
        this.jobObservability = jobObservability;
        this.processingEnabled = processingEnabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.reports.processing.fixed-delay-ms:15000}", scheduler = ScheduledJobThreadingConfig.REPORTING_TASK_SCHEDULER)
    public void processPendingRequests() {
        if (!processingEnabled) {
            return;
        }

        ReportRequestService.ProcessingSummary summary = jobObservability.run("report-processing", () ->
                TenantScopedExecution.callAsAdmin(() -> reportRequestService.processPendingRequests(batchSize)));
        if (summary.processed() > 0) {
            log.info(
                    "Processed {} report requests: completed={}, failed={}",
                    summary.processed(),
                    summary.completed(),
                    summary.failed()
            );
        }
    }

    /**
     * Drains notifications whose terminal state committed but whose send never recorded — a
     * crash between commit and send, a lost notification lease, or delivery re-enabled after
     * downtime (H24). Processing attempts the send inline first; this sweep is the durable
     * retry path.
     */
    @Scheduled(
            fixedDelayString = "${app.reports.notifications.sweep-delay-ms:30000}",
            scheduler = ScheduledJobThreadingConfig.REPORTING_TASK_SCHEDULER
    )
    public void processPendingNotifications() {
        if (!processingEnabled) {
            return;
        }

        int attempted = jobObservability.run("report-notifications", () ->
                TenantScopedExecution.callAsAdmin(() -> reportRequestService.processPendingNotifications(batchSize)));
        if (attempted > 0) {
            log.info("Attempted {} pending report notification(s).", attempted);
        }
    }
}
