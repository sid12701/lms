package com.bhawana.lms.service;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the mock ICICI Composite Pay adapter and its deferred status-check lifecycle.
 * These knobs disappear once the production adapter replaces the mock behind the same seam.
 */
@ConfigurationProperties(prefix = "app.disbursement.mock")
public class LoanDisbursementMockProperties {

    /** IMPS per-transaction ceiling (ICICI: INR 5,00,000). Above this the rail switches to NEFT. */
    private BigDecimal impsMaxAmount = new BigDecimal("500000");

    private final StatusCheck statusCheck = new StatusCheck();

    public BigDecimal getImpsMaxAmount() {
        return impsMaxAmount;
    }

    public void setImpsMaxAmount(BigDecimal impsMaxAmount) {
        this.impsMaxAmount = impsMaxAmount;
    }

    public StatusCheck getStatusCheck() {
        return statusCheck;
    }

    public static class StatusCheck {

        /** Polls that return "not yet queryable" (ICICI CheckStatusCode 100) before a terminal verdict. */
        private int minPolls = 1;

        /** Poll cap for a still-pending transaction before it is parked for reconciliation. */
        private int maxPolls = 6;

        public int getMinPolls() {
            return minPolls;
        }

        public void setMinPolls(int minPolls) {
            this.minPolls = minPolls;
        }

        public int getMaxPolls() {
            return maxPolls;
        }

        public void setMaxPolls(int maxPolls) {
            this.maxPolls = maxPolls;
        }
    }
}
