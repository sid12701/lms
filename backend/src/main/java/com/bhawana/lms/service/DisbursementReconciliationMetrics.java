package com.bhawana.lms.service;

import com.bhawana.lms.repo.DisbursementReconciliationQueueRepository;
import com.bhawana.lms.tenant.TenantScopedExecution;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * H02 — operational gauges for the explicit reconciliation queue: counts AND oldest age for
 * all unresolved states (the H27 exporter consumes the queue API built on the same source).
 */
@Component
public class DisbursementReconciliationMetrics {

    private final DisbursementReconciliationQueueRepository queueRepository;

    public DisbursementReconciliationMetrics(
            DisbursementReconciliationQueueRepository queueRepository, MeterRegistry meterRegistry) {
        this.queueRepository = queueRepository;
        Gauge.builder("lms.disbursement.reconciliation.queue.count", this, metrics -> metrics.queueCount())
                .description("Number of loan accounts awaiting disbursement reconciliation")
                .register(meterRegistry);
        Gauge.builder(
                        "lms.disbursement.reconciliation.queue.oldest_age_seconds",
                        this,
                        metrics -> metrics.oldestQueueAgeSeconds()
                )
                .description("Age in seconds of the oldest loan account awaiting disbursement reconciliation")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    private double queueCount() {
        return TenantScopedExecution.callAsAdmin(queueRepository::count);
    }

    private double oldestQueueAgeSeconds() {
        return TenantScopedExecution.callAsAdmin(() -> queueRepository
                .findOldestFirstSeenAt()
                .map(oldest -> Math.max(0L, Duration.between(oldest, Instant.now()).toSeconds()))
                .orElse(0L));
    }
}
