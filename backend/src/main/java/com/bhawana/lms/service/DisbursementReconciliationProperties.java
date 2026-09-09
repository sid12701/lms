package com.bhawana.lms.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * H02 — bounded reconciliation queue tuning. Backoff keeps provider load constant no matter
 * how many loans park: {@code next_poll_at = now + min(maxDelay, initialDelay * 2^pollCount)}.
 * {@code escalationAge} surfaces stale money to operators; the queue page size bounds every
 * poll sweep and every API response.
 */
@ConfigurationProperties(prefix = "app.disbursement.reconciliation")
public class DisbursementReconciliationProperties {

    private long initialDelaySeconds = 60L;
    private long maxDelaySeconds = 3600L;
    private long escalationAgeSeconds = 86400L;
    private int queuePollBatchSize = 20;
    private int queuePageSize = 50;

    public long getInitialDelaySeconds() {
        return initialDelaySeconds;
    }

    public void setInitialDelaySeconds(long initialDelaySeconds) {
        this.initialDelaySeconds = Math.max(1L, initialDelaySeconds);
    }

    public long getMaxDelaySeconds() {
        return maxDelaySeconds;
    }

    public void setMaxDelaySeconds(long maxDelaySeconds) {
        this.maxDelaySeconds = Math.max(1L, maxDelaySeconds);
    }

    public long getEscalationAgeSeconds() {
        return escalationAgeSeconds;
    }

    public void setEscalationAgeSeconds(long escalationAgeSeconds) {
        this.escalationAgeSeconds = Math.max(0L, escalationAgeSeconds);
    }

    public int getQueuePollBatchSize() {
        return queuePollBatchSize;
    }

    public void setQueuePollBatchSize(int queuePollBatchSize) {
        // H02: floor at two — the sweep always slices a discovery reserve off this size, and a
        // batch of one would silently disable inventory of never-queued accounts forever.
        this.queuePollBatchSize = Math.max(2, queuePollBatchSize);
    }

    public int getQueuePageSize() {
        return queuePageSize;
    }

    public void setQueuePageSize(int queuePageSize) {
        this.queuePageSize = Math.max(1, queuePageSize);
    }

    public java.time.Instant nextPollAt(int pollCount) {
        long shift = Math.min(pollCount, 10);
        long delay = initialDelaySeconds * (1L << shift);
        return java.time.Instant.now().plusSeconds(Math.min(delay, maxDelaySeconds));
    }
}
