package com.bhawana.lms.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.idempotency")
public class IdempotencyProperties {

    private int retentionDays = 90;
    private boolean purgeEnabled = true;
    private long purgeFixedDelayMs = 3_600_000L;
    private int leaseDurationSeconds = 60;
    private String leaseOwner = defaultLeaseOwner();
    private int completionWaitSeconds = 30;

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

    public int getLeaseDurationSeconds() {
        return leaseDurationSeconds;
    }

    public void setLeaseDurationSeconds(int leaseDurationSeconds) {
        this.leaseDurationSeconds = leaseDurationSeconds;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public void setLeaseOwner(String leaseOwner) {
        this.leaseOwner = leaseOwner;
    }

    public int getCompletionWaitSeconds() {
        return completionWaitSeconds;
    }

    public void setCompletionWaitSeconds(int completionWaitSeconds) {
        this.completionWaitSeconds = completionWaitSeconds;
    }

    private static String defaultLeaseOwner() {
        String hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isBlank()) {
            return hostname;
        }
        return "lms-api";
    }
}
