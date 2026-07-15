package com.bhawana.lms.seed.synthetic;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the staging-scale synthetic portfolio seeder (WI-0.1 / #197).
 *
 * <p>Disabled by default. Requires {@code --seed-synthetic-portfolio} and {@code enabled=true}.
 */
@ConfigurationProperties(prefix = "app.seed.synthetic-portfolio")
public class SyntheticPortfolioSeedProperties {

    /**
     * Master switch — must be {@code true} for the seed runner bean to execute a seed.
     */
    private boolean enabled = false;

    private boolean resetExistingData = true;

    /** Creates only account-backed UNDER_REPAYMENT loans. */
    private boolean accountOnly = false;

    /** Optional suffix used to make additive seed dimension codes unique. */
    private String seedRunId;

    /**
     * Multiplier applied to the baseline month-9 portfolio sizes (1.0 = full #197 spec).
     */
    private double scaleFactor = 1.0d;

    /**
     * When set, overrides {@link #scaleFactor} for total application count (used in CI smoke tests).
     */
    private Integer applicationCountOverride;

    private int lspCount = 10;

    /**
     * Index of the whale LSP (0-based). Receives {@link #whaleVolumeShare} of applications.
     */
    private int whaleLspIndex = 0;

    /**
     * Fraction of total applications assigned to the whale tenant (0.25 = 25%).
     */
    private double whaleVolumeShare = 0.25d;

    private int batchSize = 5_000;

    private int tenureMonths = 12;

    private String bootstrapUsername = "ops.admin";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isResetExistingData() {
        return resetExistingData;
    }

    public void setResetExistingData(boolean resetExistingData) {
        this.resetExistingData = resetExistingData;
    }

    public boolean isAccountOnly() {
        return accountOnly;
    }

    public void setAccountOnly(boolean accountOnly) {
        this.accountOnly = accountOnly;
    }

    public String getSeedRunId() {
        return seedRunId;
    }

    public void setSeedRunId(String seedRunId) {
        this.seedRunId = seedRunId;
    }

    public double getScaleFactor() {
        return scaleFactor;
    }

    public void setScaleFactor(double scaleFactor) {
        this.scaleFactor = scaleFactor;
    }

    public Integer getApplicationCountOverride() {
        return applicationCountOverride;
    }

    public void setApplicationCountOverride(Integer applicationCountOverride) {
        this.applicationCountOverride = applicationCountOverride;
    }

    public int getLspCount() {
        return lspCount;
    }

    public void setLspCount(int lspCount) {
        this.lspCount = lspCount;
    }

    public int getWhaleLspIndex() {
        return whaleLspIndex;
    }

    public void setWhaleLspIndex(int whaleLspIndex) {
        this.whaleLspIndex = whaleLspIndex;
    }

    public double getWhaleVolumeShare() {
        return whaleVolumeShare;
    }

    public void setWhaleVolumeShare(double whaleVolumeShare) {
        this.whaleVolumeShare = whaleVolumeShare;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getTenureMonths() {
        return tenureMonths;
    }

    public void setTenureMonths(int tenureMonths) {
        this.tenureMonths = tenureMonths;
    }

    public String getBootstrapUsername() {
        return bootstrapUsername;
    }

    public void setBootstrapUsername(String bootstrapUsername) {
        this.bootstrapUsername = bootstrapUsername;
    }
}
