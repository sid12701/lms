package com.bhawana.lms.service;

import com.bhawana.lms.tenant.TenantScopedExecution;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Report-queue operational meters (H27): pending backlog count and oldest pending age are
 * scrape-time aggregates over the admin-plane {@code report_request} table; the processing
 * timer is the per-request wall-clock from claim to terminal outcome. Labels carry only the
 * outcome — never request ids, requester usernames, or tenant ids.
 */
@Component
public class ReportWorkerMetrics {

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    public ReportWorkerMetrics(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
        Gauge.builder("lms.report.request.pending.count", this, ReportWorkerMetrics::pendingCount)
                .description("PENDING report requests awaiting a processing claim")
                .strongReference(true)
                .register(meterRegistry);
        Gauge.builder("lms.report.request.pending.oldest_age_seconds", this, ReportWorkerMetrics::oldestPendingAgeSeconds)
                .description("Age in seconds of the oldest PENDING report request")
                .strongReference(true)
                .register(meterRegistry);
    }

    void recordProcessing(Duration duration, String outcome) {
        Timer.builder("lms.report.request.processing.duration")
                .description("Wall-clock time to generate, store, and record one report request")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(duration);
    }

    private double pendingCount() {
        Long count = TenantScopedExecution.callAsAdmin(() -> jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM report_request WHERE status = 'PENDING'",
                Long.class
        ));
        return count == null ? 0.0 : count;
    }

    private double oldestPendingAgeSeconds() {
        Timestamp oldest = TenantScopedExecution.callAsAdmin(() -> jdbcTemplate.queryForObject(
                "SELECT MIN(created_at) FROM report_request WHERE status = 'PENDING'",
                Timestamp.class
        ));
        return oldest == null ? 0.0 : Math.max(0.0, (Instant.now().toEpochMilli() - oldest.getTime()) / 1000.0);
    }
}
