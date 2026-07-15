package com.bhawana.lms.service;

import com.bhawana.lms.repo.AdminApiIdempotencyRecordRepository;
import com.bhawana.lms.repo.LspApiIdempotencyRecordRepository;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyRecordRetentionWorker {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyRecordRetentionWorker.class);

    private final LspApiIdempotencyRecordRepository lspApiIdempotencyRecordRepository;
    private final AdminApiIdempotencyRecordRepository adminApiIdempotencyRecordRepository;
    private final boolean enabled;
    private final int retentionDays;

    public IdempotencyRecordRetentionWorker(
            LspApiIdempotencyRecordRepository lspApiIdempotencyRecordRepository,
            AdminApiIdempotencyRecordRepository adminApiIdempotencyRecordRepository,
            IdempotencyProperties properties
    ) {
        this.lspApiIdempotencyRecordRepository = lspApiIdempotencyRecordRepository;
        this.adminApiIdempotencyRecordRepository = adminApiIdempotencyRecordRepository;
        this.enabled = properties.isPurgeEnabled();
        this.retentionDays = properties.getRetentionDays();
    }

    @Scheduled(fixedDelayString = "${app.idempotency.purge-fixed-delay-ms:3600000}")
    public void purgeExpiredRecords() {
        if (!enabled) {
            return;
        }
        TenantScopedExecution.runAsAdmin(this::purgeExpiredRecordsUnderAdminScope);
    }

    void purgeExpiredRecordsUnderAdminScope() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        long lspRecords = lspApiIdempotencyRecordRepository.deleteByCreatedAtBefore(cutoff);
        long adminRecords = adminApiIdempotencyRecordRepository.deleteByCreatedAtBefore(cutoff);
        if (lspRecords > 0 || adminRecords > 0) {
            log.info(
                    "idempotency_records_purged lspRecords={} adminRecords={} retentionDays={}",
                    lspRecords,
                    adminRecords,
                    retentionDays
            );
        }
    }
}
