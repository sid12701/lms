package com.bhawana.lms.service;

import com.bhawana.lms.domain.DisbursementIntentState;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.tenant.TenantScopedExecution;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Operational gauges for ambiguous payout work that must be reconciled, never reissued. */
@Component
public class DisbursementIntentMetrics {

    private final DisbursementIntentRepository repository;

    public DisbursementIntentMetrics(DisbursementIntentRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        Gauge.builder("lms.disbursement.intent.unknown.count", this, metrics -> metrics.unknownCount())
                .description("Number of disbursement intents awaiting provider reconciliation")
                .register(meterRegistry);
        Gauge.builder(
                        "lms.disbursement.intent.unknown.oldest_age_seconds",
                        this,
                        metrics -> metrics.oldestUnknownAgeSeconds()
                )
                .description("Age in seconds of the oldest disbursement intent awaiting reconciliation")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    private double unknownCount() {
        return TenantScopedExecution.callAsAdmin(() -> repository.countByState(DisbursementIntentState.UNKNOWN));
    }

    private double oldestUnknownAgeSeconds() {
        return TenantScopedExecution.callAsAdmin(() -> repository
                .findOldestUpdatedAtByState(DisbursementIntentState.UNKNOWN)
                .map(oldest -> Math.max(0L, Duration.between(oldest, Instant.now()).toSeconds()))
                .orElse(0L));
    }
}
