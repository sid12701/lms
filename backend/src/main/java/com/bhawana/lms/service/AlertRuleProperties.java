package com.bhawana.lms.service;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The single authority for alert-rule evaluation (M06). Every threshold the
 * {@link AlertRuleEvaluationWorker} enforces is bound here from
 * {@code app.alert-rules.*}; the rules API exposes these same values read-only as
 * each rule's effective configuration.
 *
 * <p>{@code ignoreUnknownFields = false} plus the constraints below make a
 * misspelled or out-of-range operator override fail at startup instead of
 * silently evaluating with defaults.
 */
@Validated
@ConfigurationProperties(prefix = "app.alert-rules", ignoreUnknownFields = false)
public class AlertRuleProperties {

    private boolean schedulerEnabled = true;
    @Min(1)
    private long schedulerFixedDelayMs = 300_000L;
    @Min(1)
    private int staleIntakeHours = 24;
    @Min(1)
    private int stuckDisbursementHours = 2;
    @Min(1)
    private int lspRejectWindowDays = 7;
    @Min(1)
    private int lspRejectMinSamples = 10;
    @Min(1)
    @Max(100)
    private int lspRejectRatePct = 40;
    @Min(1)
    private int authBruteForceThreshold = 5;
    @Min(1)
    private int authBruteForceWindowMinutes = 10;
    @Min(1)
    private int authBruteForceDistributedThreshold = 20;
    @Min(1)
    private int authBruteForceDistributedDistinctIpMin = 5;
    @Min(1)
    private int authBruteForceDistributedWindowHours = 24;
    @Min(1)
    private int evaluationBatchLimit = 500;
    @Min(1)
    private int oldestTransactionAgeSeconds = 300;
    @Min(1)
    private long evaluationLeaseMs = 300_000;
    @Min(1)
    private int evaluationStatementTimeoutMs = 60_000;

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

    public int getStaleIntakeHours() {
        return staleIntakeHours;
    }

    public void setStaleIntakeHours(int staleIntakeHours) {
        this.staleIntakeHours = staleIntakeHours;
    }

    public int getStuckDisbursementHours() {
        return stuckDisbursementHours;
    }

    public void setStuckDisbursementHours(int stuckDisbursementHours) {
        this.stuckDisbursementHours = stuckDisbursementHours;
    }

    public int getLspRejectWindowDays() {
        return lspRejectWindowDays;
    }

    public void setLspRejectWindowDays(int lspRejectWindowDays) {
        this.lspRejectWindowDays = lspRejectWindowDays;
    }

    public int getLspRejectMinSamples() {
        return lspRejectMinSamples;
    }

    public void setLspRejectMinSamples(int lspRejectMinSamples) {
        this.lspRejectMinSamples = lspRejectMinSamples;
    }

    public int getLspRejectRatePct() {
        return lspRejectRatePct;
    }

    public void setLspRejectRatePct(int lspRejectRatePct) {
        this.lspRejectRatePct = lspRejectRatePct;
    }

    public int getAuthBruteForceThreshold() {
        return authBruteForceThreshold;
    }

    public void setAuthBruteForceThreshold(int authBruteForceThreshold) {
        this.authBruteForceThreshold = authBruteForceThreshold;
    }

    public int getAuthBruteForceWindowMinutes() {
        return authBruteForceWindowMinutes;
    }

    public void setAuthBruteForceWindowMinutes(int authBruteForceWindowMinutes) {
        this.authBruteForceWindowMinutes = authBruteForceWindowMinutes;
    }

    public int getAuthBruteForceDistributedThreshold() {
        return authBruteForceDistributedThreshold;
    }

    public void setAuthBruteForceDistributedThreshold(int authBruteForceDistributedThreshold) {
        this.authBruteForceDistributedThreshold = authBruteForceDistributedThreshold;
    }

    public int getAuthBruteForceDistributedDistinctIpMin() {
        return authBruteForceDistributedDistinctIpMin;
    }

    public void setAuthBruteForceDistributedDistinctIpMin(int authBruteForceDistributedDistinctIpMin) {
        this.authBruteForceDistributedDistinctIpMin = authBruteForceDistributedDistinctIpMin;
    }

    public int getAuthBruteForceDistributedWindowHours() {
        return authBruteForceDistributedWindowHours;
    }

    public void setAuthBruteForceDistributedWindowHours(int authBruteForceDistributedWindowHours) {
        this.authBruteForceDistributedWindowHours = authBruteForceDistributedWindowHours;
    }

    public int getEvaluationBatchLimit() {
        return evaluationBatchLimit;
    }

    public void setEvaluationBatchLimit(int evaluationBatchLimit) {
        this.evaluationBatchLimit = evaluationBatchLimit;
    }

    public int getOldestTransactionAgeSeconds() {
        return oldestTransactionAgeSeconds;
    }

    public void setOldestTransactionAgeSeconds(int oldestTransactionAgeSeconds) {
        this.oldestTransactionAgeSeconds = oldestTransactionAgeSeconds;
    }

    public long getEvaluationLeaseMs() {
        return evaluationLeaseMs;
    }

    public void setEvaluationLeaseMs(long evaluationLeaseMs) {
        this.evaluationLeaseMs = evaluationLeaseMs;
    }

    public int getEvaluationStatementTimeoutMs() {
        return evaluationStatementTimeoutMs;
    }

    public void setEvaluationStatementTimeoutMs(int evaluationStatementTimeoutMs) {
        this.evaluationStatementTimeoutMs = evaluationStatementTimeoutMs;
    }
}
