package com.bhawana.lms.service;

import com.bhawana.lms.tenant.TenantScopedExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WebhookOutboxDispatchWorker {

    private static final Logger log = LoggerFactory.getLogger(WebhookOutboxDispatchWorker.class);

    private final WebhookOutboxService webhookOutboxService;
    private final boolean deliveryEnabled;
    private final int batchSize;

    public WebhookOutboxDispatchWorker(
            WebhookOutboxService webhookOutboxService,
            @Value("${app.webhooks.delivery.enabled:true}") boolean deliveryEnabled,
            @Value("${app.webhooks.delivery.batch-size:20}") int batchSize
    ) {
        this.webhookOutboxService = webhookOutboxService;
        this.deliveryEnabled = deliveryEnabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.webhooks.delivery.fixed-delay-ms:60000}")
    public void dispatchPendingEvents() {
        if (!deliveryEnabled) {
            return;
        }

        WebhookOutboxService.DispatchSummary summary = TenantScopedExecution.callAsAdmin(
                () -> webhookOutboxService.dispatchPending(batchSize)
        );
        if (summary.processed() > 0) {
            log.info(
                    "Processed {} webhook outbox events: delivered={}, retryableFailures={}, permanentFailures={}",
                    summary.processed(),
                    summary.delivered(),
                    summary.retryableFailures(),
                    summary.permanentFailures()
            );
        }
    }
}
