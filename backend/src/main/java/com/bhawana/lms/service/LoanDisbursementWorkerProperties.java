package com.bhawana.lms.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.disbursement.worker")
public class LoanDisbursementWorkerProperties {

    private boolean enabled = true;
    private long fixedDelayMs = 30_000L;
    private int maxAttempts = 5;
    private boolean autoResolveMockOutcome = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getFixedDelayMs() {
        return fixedDelayMs;
    }

    public void setFixedDelayMs(long fixedDelayMs) {
        this.fixedDelayMs = fixedDelayMs;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public boolean isAutoResolveMockOutcome() {
        return autoResolveMockOutcome;
    }

    public void setAutoResolveMockOutcome(boolean autoResolveMockOutcome) {
        this.autoResolveMockOutcome = autoResolveMockOutcome;
    }
}
