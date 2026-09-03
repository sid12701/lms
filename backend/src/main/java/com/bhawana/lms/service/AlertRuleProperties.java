package com.bhawana.lms.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.alert-rules")
public class AlertRuleProperties {

    private boolean schedulerEnabled = true;
    private long schedulerFixedDelayMs = 300_000L;
    private int staleIntakeHours = 24;
    private int stuckDisbursementHours = 2;
    private int lspRejectWindowDays = 7;
    private int lspRejectMinSamples = 10;
    private int lspRejectRatePct = 40;
    private int authBruteForceThreshold = 5;
    private int authBruteForceWindowMinutes = 10;
    private int authBruteForceDistributedThreshold = 20;
    private int authBruteForceDistributedDistinctIpMin = 5;
    private int authBruteForceDistributedWindowHours = 24;
    private int evaluationBatchLimit = 500;
    private int oldestTransactionAgeSeconds = 300;

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
}
