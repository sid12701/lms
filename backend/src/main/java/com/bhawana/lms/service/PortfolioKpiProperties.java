package com.bhawana.lms.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.portfolio-kpi")
public class PortfolioKpiProperties {

    private boolean schedulerEnabled = true;
    private long schedulerFixedDelayMs = 900_000L;
    private long advisoryLockId = 42_109L;

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
}
