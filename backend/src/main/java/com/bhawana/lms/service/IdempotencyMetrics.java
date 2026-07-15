package com.bhawana.lms.service;

import com.bhawana.lms.repo.AdminApiIdempotencyRecordRepository;
import com.bhawana.lms.repo.LspApiIdempotencyRecordRepository;
import com.bhawana.lms.tenant.TenantScopedExecution;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Operational gauges for idempotency claims that have not completed yet. */
@Component
public class IdempotencyMetrics {

    private final LspApiIdempotencyRecordRepository lspRepository;
    private final AdminApiIdempotencyRecordRepository adminRepository;

    public IdempotencyMetrics(
            LspApiIdempotencyRecordRepository lspRepository,
            AdminApiIdempotencyRecordRepository adminRepository,
            MeterRegistry meterRegistry
    ) {
        this.lspRepository = lspRepository;
        this.adminRepository = adminRepository;
        Gauge.builder("lms.idempotency.pending.count", this, metrics -> metrics.pendingCount())
                .description("Combined number of pending LSP and admin idempotency claims")
                .register(meterRegistry);
        Gauge.builder(
                        "lms.idempotency.pending.oldest_age_seconds",
                        this,
                        metrics -> metrics.oldestPendingAgeSeconds()
                )
                .description("Age in seconds of the oldest pending idempotency claim")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    private double pendingCount() {
        return TenantScopedExecution.callAsAdmin(() ->
                lspRepository.countByResponseBody(IdempotencyRecordState.PENDING_RESPONSE_BODY)
                        + adminRepository.countByResponseBody(IdempotencyRecordState.PENDING_RESPONSE_BODY));
    }

    private double oldestPendingAgeSeconds() {
        return TenantScopedExecution.callAsAdmin(() -> {
            Optional<Instant> lspOldest = lspRepository
                    .findOldestCreatedAtByResponseBody(IdempotencyRecordState.PENDING_RESPONSE_BODY);
            Optional<Instant> adminOldest = adminRepository
                    .findOldestCreatedAtByResponseBody(IdempotencyRecordState.PENDING_RESPONSE_BODY);
            return earliest(lspOldest, adminOldest)
                    .map(oldest -> Math.max(0L, Duration.between(oldest, Instant.now()).toSeconds()))
                    .orElse(0L);
        });
    }

    private static Optional<Instant> earliest(Optional<Instant> left, Optional<Instant> right) {
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        return Optional.of(left.get().isBefore(right.get()) ? left.get() : right.get());
    }
}
