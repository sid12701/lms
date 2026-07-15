package com.bhawana.lms.service;

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

    public PortfolioKpiSnapshotWorker(
            PortfolioKpiSnapshotComputationService computationService,
            PostgresAdvisoryLockSupport advisoryLockSupport,
            PortfolioKpiProperties properties
    ) {
        this.computationService = computationService;
        this.advisoryLockSupport = advisoryLockSupport;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.portfolio-kpi.scheduler-fixed-delay-ms:900000}")
    public void refreshSnapshots() {
        if (!properties.isSchedulerEnabled()) {
            return;
        }
        TenantScopedExecution.runAsAdmin(this::refreshSnapshotsUnderAdminScope);
    }

    void refreshSnapshotsUnderAdminScope() {
        long lockId = properties.getAdvisoryLockId();
        if (!advisoryLockSupport.tryAcquire(lockId)) {
            log.debug("portfolio_kpi_snapshot_refresh_skipped lock_not_acquired lockId={}", lockId);
            return;
        }
        try {
            var computedAt = computationService.computeAndPersistSnapshots();
            log.info("portfolio_kpi_snapshot_refreshed computedAt={}", computedAt);
        } finally {
            advisoryLockSupport.release(lockId);
        }
    }
}
