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
}
