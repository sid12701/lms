package com.bhawana.lms.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.borrower.bank-details")
public class BorrowerBankDetailsProperties {

    private int velocityMaxUpdates = 3;
    private int velocityWindowDays = 7;
    private int mismatchMaxAttempts = 3;
    private int mismatchWindowMinutes = 15;

    public int getVelocityMaxUpdates() {
        return velocityMaxUpdates;
    }

    public void setVelocityMaxUpdates(int velocityMaxUpdates) {
        this.velocityMaxUpdates = velocityMaxUpdates;
    }

    public int getVelocityWindowDays() {
        return velocityWindowDays;
    }

    public void setVelocityWindowDays(int velocityWindowDays) {
        this.velocityWindowDays = velocityWindowDays;
    }

    public int getMismatchMaxAttempts() {
        return mismatchMaxAttempts;
    }

    public void setMismatchMaxAttempts(int mismatchMaxAttempts) {
        this.mismatchMaxAttempts = mismatchMaxAttempts;
    }

    public int getMismatchWindowMinutes() {
        return mismatchWindowMinutes;
    }

    public void setMismatchWindowMinutes(int mismatchWindowMinutes) {
        this.mismatchWindowMinutes = mismatchWindowMinutes;
    }
}
