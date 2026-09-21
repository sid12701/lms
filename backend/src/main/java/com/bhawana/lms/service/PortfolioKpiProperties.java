package com.bhawana.lms.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.portfolio-kpi")
public class PortfolioKpiProperties {

    private boolean schedulerEnabled = true;
    private long schedulerFixedDelayMs = 900_000L;
    private long advisoryLockId = 42_109L;
    /**
     * Derived-snapshot retention (L04): rows older than this are purged in bounded
     * batches, except the newest row per scope (per-LSP or global), which is kept so
     * a silent worker can never blank a scope's last reading. Snapshots are derived
     * operational data — not immutable financial/audit records.
     */
    private int retentionDays = 400;
    private boolean purgeEnabled = true;
    private long purgeFixedDelayMs = 21_600_000L;
    private int purgeBatchSize = 1_000;
    private int purgeMaxBatchesPerRun = 50;

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }

    public long getSchedulerFixedDelayMs() {
        return schedulerFixedDelayMs;
    }

    public void setSchedulerFixedDelayMs(long schedulerFixedDelayMs) {
        this.schedulerFixedDelayMs = schedulerFixedDelayMs;
    }

    public long getAdvisoryLockId() {
        return advisoryLockId;
    }

    public void setAdvisoryLockId(long advisoryLockId) {
        this.advisoryLockId = advisoryLockId;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public boolean isPurgeEnabled() {
        return purgeEnabled;
    }

    public void setPurgeEnabled(boolean purgeEnabled) {
        this.purgeEnabled = purgeEnabled;
    }

    public long getPurgeFixedDelayMs() {
        return purgeFixedDelayMs;
    }

    public void setPurgeFixedDelayMs(long purgeFixedDelayMs) {
        this.purgeFixedDelayMs = purgeFixedDelayMs;
    }

    public int getPurgeBatchSize() {
        return purgeBatchSize;
    }

    public void setPurgeBatchSize(int purgeBatchSize) {
        this.purgeBatchSize = purgeBatchSize;
    }

    public int getPurgeMaxBatchesPerRun() {
        return purgeMaxBatchesPerRun;
    }

    public void setPurgeMaxBatchesPerRun(int purgeMaxBatchesPerRun) {
        this.purgeMaxBatchesPerRun = purgeMaxBatchesPerRun;
    }
}
