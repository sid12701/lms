package com.bhawana.lms.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReportRequestProcessingWorker {

    private static final Logger log = LoggerFactory.getLogger(ReportRequestProcessingWorker.class);

    private final ReportRequestService reportRequestService;
    private final boolean processingEnabled;
    private final int batchSize;

    public ReportRequestProcessingWorker(
            ReportRequestService reportRequestService,
            @Value("${app.reports.processing.enabled:true}") boolean processingEnabled,
            @Value("${app.reports.processing.batch-size:10}") int batchSize
    ) {
        this.reportRequestService = reportRequestService;
        this.processingEnabled = processingEnabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.reports.processing.fixed-delay-ms:15000}")
    public void processPendingRequests() {
        if (!processingEnabled) {
            return;
        }

        ReportRequestService.ProcessingSummary summary = reportRequestService.processPendingRequests(batchSize);
        if (summary.processed() > 0) {
            log.info(
                    "Processed {} report requests: completed={}, failed={}",
                    summary.processed(),
                    summary.completed(),
                    summary.failed()
            );
        }
    }
}
