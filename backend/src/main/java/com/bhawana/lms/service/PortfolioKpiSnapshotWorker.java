package com.bhawana.lms.service;

import com.bhawana.lms.config.ScheduledJobThreadingConfig;
import com.bhawana.lms.tenant.TenantScopedExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PortfolioKpiSnapshotWorker {

    private static final Logger log = LoggerFactory.getLogger(PortfolioKpiSnapshotWorker.class);

    private final PortfolioKpiSnapshotComputationService computationService;
    private final PostgresAdvisoryLockSupport advisoryLockSupport;
    private final PortfolioKpiProperties properties;
    private final JobObservabilitySupport jobObservability;

    public PortfolioKpiSnapshotWorker(
            PortfolioKpiSnapshotComputationService computationService,
            PostgresAdvisoryLockSupport advisoryLockSupport,
            PortfolioKpiProperties properties,
            JobObservabilitySupport jobObservability
    ) {
        this.computationService = computationService;
        this.advisoryLockSupport = advisoryLockSupport;
        this.properties = properties;
        this.jobObservability = jobObservability;
    }

    @Scheduled(fixedDelayString = "${app.portfolio-kpi.scheduler-fixed-delay-ms:900000}", scheduler = ScheduledJobThreadingConfig.MAINTENANCE_TASK_SCHEDULER)
    public void refreshSnapshots() {
        if (!properties.isSchedulerEnabled()) {
            return;
        }
        jobObservability.run("portfolio-kpi-snapshot", () ->
                TenantScopedExecution.runAsAdmin(this::refreshSnapshotsUnderAdminScope));
    }

    void refreshSnapshotsUnderAdminScope() {
        long lockId = properties.getAdvisoryLockId();
        var computedAt = advisoryLockSupport.runWithAdvisoryLock(
                lockId,
                "portfolio_kpi_snapshot_refresh",
                computationService::computeAndPersistSnapshots
        );
        if (computedAt.isEmpty()) {
            log.debug("portfolio_kpi_snapshot_refresh_skipped lock_not_acquired lockId={}", lockId);
            return;
        }
        log.info("portfolio_kpi_snapshot_refreshed computedAt={}", computedAt.get());
    }
}
