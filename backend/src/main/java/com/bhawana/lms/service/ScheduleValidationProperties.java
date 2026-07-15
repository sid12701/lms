package com.bhawana.lms.service;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Accepted bounds for partner-provided repayment schedule calendar and interest checks (Spec S20).
 * Values are the product-accepted defaults (2026-07-15); override via config only when retuning is required.
 */
@ConfigurationProperties(prefix = "app.schedule.validation")
public class ScheduleValidationProperties {

    /** Minimum calendar days after approval for the first due date (inclusive lower bound offset). */
    private int firstDueMinDays = 1;

    /** Maximum calendar days after approval for the first due date (inclusive upper bound offset). */
    private int firstDueMaxDays = 60;

    /** Allowed drift (days) from {@code firstDue + i months} for installment {@code i}. */
    private int cadenceToleranceDays = 7;

    /** Grace days beyond {@code approvalDate + tenureMonths} for the final due date. */
    private int horizonGraceDays = 75;

    private BigDecimal interestRowToleranceAbs = new BigDecimal("10.00");
    private BigDecimal interestRowTolerancePct = new BigDecimal("0.02");
    private BigDecimal interestTotalToleranceAbs = new BigDecimal("100.00");
    private BigDecimal interestTotalTolerancePct = new BigDecimal("0.01");

    public int getFirstDueMinDays() {
        return firstDueMinDays;
    }

    public void setFirstDueMinDays(int firstDueMinDays) {
        this.firstDueMinDays = firstDueMinDays;
    }

    public int getFirstDueMaxDays() {
        return firstDueMaxDays;
    }

    public void setFirstDueMaxDays(int firstDueMaxDays) {
        this.firstDueMaxDays = firstDueMaxDays;
    }

    public int getCadenceToleranceDays() {
        return cadenceToleranceDays;
    }

    public void setCadenceToleranceDays(int cadenceToleranceDays) {
        this.cadenceToleranceDays = cadenceToleranceDays;
    }

    public int getHorizonGraceDays() {
        return horizonGraceDays;
    }

    public void setHorizonGraceDays(int horizonGraceDays) {
        this.horizonGraceDays = horizonGraceDays;
    }

    public BigDecimal getInterestRowToleranceAbs() {
        return interestRowToleranceAbs;
    }

    public void setInterestRowToleranceAbs(BigDecimal interestRowToleranceAbs) {
        this.interestRowToleranceAbs = interestRowToleranceAbs;
    }

    public BigDecimal getInterestRowTolerancePct() {
        return interestRowTolerancePct;
    }

    public void setInterestRowTolerancePct(BigDecimal interestRowTolerancePct) {
        this.interestRowTolerancePct = interestRowTolerancePct;
    }

    public BigDecimal getInterestTotalToleranceAbs() {
        return interestTotalToleranceAbs;
    }

    public void setInterestTotalToleranceAbs(BigDecimal interestTotalToleranceAbs) {
        this.interestTotalToleranceAbs = interestTotalToleranceAbs;
    }

    public BigDecimal getInterestTotalTolerancePct() {
        return interestTotalTolerancePct;
    }

    public void setInterestTotalTolerancePct(BigDecimal interestTotalTolerancePct) {
        this.interestTotalTolerancePct = interestTotalTolerancePct;
    }
}
