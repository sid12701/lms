package com.bhawana.lms.domain;

/**
 * Money-movement rail used for a disbursement, mirroring the ICICI Composite Pay
 * {@code x-priority} routing. Only the two rails relevant to small-ticket lending are modelled
 * (IMPS is near-real-time; NEFT is deferred/batch). RTGS and UPI are intentionally out of scope.
 */
public enum DisbursementPaymentMode {

    /** Immediate Payment Service — near-real-time, terminal status usually returned synchronously. */
    IMPS("0100"),

    /** National Electronic Funds Transfer — deferred; terminal status only via status check. */
    NEFT("0010");

    private final String xPriority;

    DisbursementPaymentMode(String xPriority) {
        this.xPriority = xPriority;
    }

    /** ICICI Composite Pay {@code x-priority} header value for this rail. */
    public String xPriority() {
        return xPriority;
    }
}
