package com.bhawana.lms.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.disbursement.intent-workflow")
public class DisbursementIntentWorkflowProperties {

    // C04: the durable intent path is the only disbursement initiation path. There is no
    // enable/disable flag — money movement always goes through DisbursementIntentWorkflowService.
    // C03: leaseOwner is a configured prefix; the service appends host + startup UUID so every
    // process has a distinct owner for the claim fence. Do not set the same fully-qualified owner
    // on two processes. Tests may set a fixed prefix; uniqueness still comes from the suffix.
    private int leaseDurationSeconds = 120;
    private int claimBatchSize = 10;
    private String leaseOwner = "disbursement-worker";

    public int getLeaseDurationSeconds() {
        return leaseDurationSeconds;
    }

    public void setLeaseDurationSeconds(int leaseDurationSeconds) {
        this.leaseDurationSeconds = leaseDurationSeconds;
    }

    public int getClaimBatchSize() {
        return claimBatchSize;
    }

    public void setClaimBatchSize(int claimBatchSize) {
        this.claimBatchSize = claimBatchSize;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public void setLeaseOwner(String leaseOwner) {
        this.leaseOwner = leaseOwner;
    }
}
