package com.bhawana.lms.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.idempotency")
public class IdempotencyProperties {

    private int retentionDays = 90;
    private boolean purgeEnabled = true;
    private long purgeFixedDelayMs = 3_600_000L;
    private int leaseDurationSeconds = 60;
    private String leaseOwner = defaultLeaseOwner();
    /**
     * How long a duplicate request may poll for the in-flight owner's completion
     * before answering 409 IDEMPOTENCY_IN_PROGRESS. Default 0: the waiter still
     * performs one final re-check before throwing, so a just-completed record
     * replays while live duplicates never pin a request thread.
     */
    private int completionWaitSeconds = 0;
    /**
     * Upper bound for the Retry-After hint on IDEMPOTENCY_IN_PROGRESS responses.
     * Without a cap the hint could span the full remaining lease (lease-duration
     * seconds), which would strand clients for far longer than the duplicate
     * needs to wait before its next attempt.
     */
    private long retryAfterCapSeconds = 5;

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

    public long getRetryAfterCapSeconds() {
        return retryAfterCapSeconds;
    }

    public void setRetryAfterCapSeconds(long retryAfterCapSeconds) {
        this.retryAfterCapSeconds = retryAfterCapSeconds;
    }

    private static String defaultLeaseOwner() {
        String hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isBlank()) {
            return hostname;
        }
        return "lms-api";
    }
}
