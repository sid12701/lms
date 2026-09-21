package com.bhawana.lms.service;

import com.bhawana.lms.config.ScheduledJobThreadingConfig;
import com.bhawana.lms.repo.PortfolioKpiSnapshotRepository;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retention for derived portfolio KPI snapshots (L04).
 *
 * <p>Snapshots are recomputed operational history — every reader asks for the
 * <em>latest</em> row per scope — so rows older than
 * {@code app.portfolio-kpi.retention-days} (default 400 days, covering a fiscal
 * year plus margin) are purged in bounded batches. The newest row per scope is
 * always preserved: it may be older than the window when a scope or the worker
 * has been silent, and deleting it would blank the dashboard rather than retire
 * history.
 */
@Component
public class PortfolioKpiSnapshotRetentionWorker {

    private static final Logger log = LoggerFactory.getLogger(PortfolioKpiSnapshotRetentionWorker.class);

    private final PortfolioKpiSnapshotRepository snapshotRepository;
    private final JobObservabilitySupport jobObservability;
    private final PortfolioKpiProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public PortfolioKpiSnapshotRetentionWorker(
            PortfolioKpiSnapshotRepository snapshotRepository,
            JobObservabilitySupport jobObservability,
            PortfolioKpiProperties properties,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.snapshotRepository = snapshotRepository;
        this.jobObservability = jobObservability;
        this.properties = properties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(
            fixedDelayString = "${app.portfolio-kpi.purge-fixed-delay-ms:21600000}",
            scheduler = ScheduledJobThreadingConfig.MAINTENANCE_TASK_SCHEDULER
    )
    public void purgeExpiredSnapshots() {
        if (!properties.isPurgeEnabled()) {
            return;
        }
        jobObservability.run("portfolio-kpi-snapshot-retention",
                () -> TenantScopedExecution.runAsAdmin(this::purgeExpiredSnapshotsUnderAdminScope));
    }

    void purgeExpiredSnapshotsUnderAdminScope() {
        Instant cutoff = clock.instant().minus(properties.getRetentionDays(), ChronoUnit.DAYS);
        int total = 0;
        for (int batch = 0; batch < properties.getPurgeMaxBatchesPerRun(); batch++) {
            Integer deleted = transactionTemplate.execute(status ->
                    snapshotRepository.deleteExpiredBatchPreservingLatest(
                            cutoff, properties.getPurgeBatchSize()));
            total += deleted == null ? 0 : deleted;
            if (deleted == null || deleted < properties.getPurgeBatchSize()) {
                break;
            }
        }
        if (total > 0) {
            log.info(
                    "portfolio_kpi_snapshots_purged deleted={} retentionDays={} cutoff={}",
                    total,
                    properties.getRetentionDays(),
                    cutoff
            );
        }
    }
}
