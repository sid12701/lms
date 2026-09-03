package com.bhawana.lms.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lifecycle settings for the partner-facing loan event log (ADR 0007).
 *
 * <p>{@code retentionDays} is the platform's published floor, not an exact age: partitions are whole
 * months, so the month holding the cutoff is kept until everything in it has aged out.
 */
@ConfigurationProperties(prefix = "app.loan-event-log")
public class LoanEventLogProperties {

    private int retentionDays = 30;
    private boolean partitionMaintenanceEnabled = true;
    private long partitionMaintenanceFixedDelayMs = 3_600_000L;

    /**
     * How many months past the current one are kept partitioned. The lead only has to outlast the
     * longest gap between maintenance runs, so the default is far wider than it needs to be: a write
     * landing with no partition present fails the lifecycle change that produced it.
     */
    private int partitionLeadMonths = 3;

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public boolean isPartitionMaintenanceEnabled() {
        return partitionMaintenanceEnabled;
    }

    public void setPartitionMaintenanceEnabled(boolean partitionMaintenanceEnabled) {
        this.partitionMaintenanceEnabled = partitionMaintenanceEnabled;
    }

    public long getPartitionMaintenanceFixedDelayMs() {
        return partitionMaintenanceFixedDelayMs;
    }

    public void setPartitionMaintenanceFixedDelayMs(long partitionMaintenanceFixedDelayMs) {
        this.partitionMaintenanceFixedDelayMs = partitionMaintenanceFixedDelayMs;
    }

    public int getPartitionLeadMonths() {
        return partitionLeadMonths;
    }

    public void setPartitionLeadMonths(int partitionLeadMonths) {
        this.partitionLeadMonths = partitionLeadMonths;
    }
}
