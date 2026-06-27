package com.bhawana.lms.service;

import com.bhawana.lms.domain.DisbursementDeclineKind;
import com.bhawana.lms.domain.DisbursementDisposition;
import java.util.Locale;

/**
 * Deterministic scenario catalogue for the mock ICICI Composite Pay adapter.
 *
 * <p>A scenario is selected by a reserved <em>beneficiary IFSC marker</em> (collision-free: real
 * IFSCs never begin with {@code MOCK0}). Any non-reserved IFSC resolves to {@link #SUCCESS}, so the
 * happy path is unchanged for all existing fixtures. Each scenario maps to a high-frequency ICICI
 * ActCode and its Technical/Business decline classification (see the Composite API error-code sheet).</p>
 *
 * <p>Reserved markers all satisfy the IFSC format {@code ^[A-Za-z]{4}0[A-Za-z0-9]{6}$}:</p>
 * <ul>
 *   <li>{@code MOCK0SUCCSS} — success (ActCode 0)</li>
 *   <li>{@code MOCK0INSUFF} — insufficient funds (51, BD)</li>
 *   <li>{@code MOCK0CLOSED} — invalid/closed beneficiary account (52, BD)</li>
 *   <li>{@code MOCK0FROZEN} — frozen account (3, BD)</li>
 *   <li>{@code MOCK0BADIFS} — invalid beneficiary IFSC (201, BD)</li>
 *   <li>{@code MOCK0NPCIDN} — NPCI/issuing bank down (18, TD)</li>
 *   <li>{@code MOCK0PENDOK} — pending, then success on status check (11 → 0)</li>
 *   <li>{@code MOCK0PENDFL} — pending, then technical failure on status check (11)</li>
 *   <li>{@code MOCK0DUPLIC} — duplicate transaction declined at switch (14, TD)</li>
 *   <li>{@code MOCK0STUCK0} — never reaches a terminal status (forces reconciliation parking)</li>
 * </ul>
 */
public enum MockIciciDisbursementScenario {

    SUCCESS(null, "0", "Transaction Successful",
            DisbursementDisposition.SUCCESS, DisbursementDeclineKind.NONE, false, false),
    EXPLICIT_SUCCESS("MOCK0SUCCSS", "0", "Transaction Successful",
            DisbursementDisposition.SUCCESS, DisbursementDeclineKind.NONE, false, false),
    INSUFFICIENT_FUNDS("MOCK0INSUFF", "51", "Insufficient funds",
            DisbursementDisposition.FAILED, DisbursementDeclineKind.BUSINESS, false, false),
    INVALID_ACCOUNT("MOCK0CLOSED", "52", "Invalid / closed beneficiary account",
            DisbursementDisposition.FAILED, DisbursementDeclineKind.BUSINESS, false, false),
    FROZEN_ACCOUNT("MOCK0FROZEN", "3", "Beneficiary account is frozen",
            DisbursementDisposition.FAILED, DisbursementDeclineKind.BUSINESS, false, false),
    INVALID_IFSC("MOCK0BADIFS", "201", "Invalid Beneficiary IFSC Code or NBIN",
            DisbursementDisposition.FAILED, DisbursementDeclineKind.BUSINESS, false, false),
    NPCI_DOWN("MOCK0NPCIDN", "18", "NPCI / Issuing bank is not connected or down",
            DisbursementDisposition.FAILED, DisbursementDeclineKind.TECHNICAL, false, false),
    PENDING_THEN_SUCCESS("MOCK0PENDOK", "11", "Transaction timed out; awaiting status check",
            DisbursementDisposition.SUCCESS, DisbursementDeclineKind.NONE, true, false),
    PENDING_THEN_FAILURE("MOCK0PENDFL", "11", "Transaction timed out; awaiting status check",
            DisbursementDisposition.FAILED, DisbursementDeclineKind.TECHNICAL, true, false),
    DUPLICATE("MOCK0DUPLIC", "14", "Duplicate transaction in IMPS",
            DisbursementDisposition.FAILED, DisbursementDeclineKind.TECHNICAL, false, false),
    STUCK_PENDING("MOCK0STUCK0", "11", "Transaction timed out; awaiting reconciliation",
            DisbursementDisposition.PENDING, DisbursementDeclineKind.NONE, true, true);

    private final String ifscMarker;
    private final String actCode;
    private final String message;
    private final DisbursementDisposition terminalDisposition;
    private final DisbursementDeclineKind declineKind;
    private final boolean pendingFirst;
    private final boolean neverResolves;

    MockIciciDisbursementScenario(
            String ifscMarker,
            String actCode,
            String message,
            DisbursementDisposition terminalDisposition,
            DisbursementDeclineKind declineKind,
            boolean pendingFirst,
            boolean neverResolves
    ) {
        this.ifscMarker = ifscMarker;
        this.actCode = actCode;
        this.message = message;
        this.terminalDisposition = terminalDisposition;
        this.declineKind = declineKind;
        this.pendingFirst = pendingFirst;
        this.neverResolves = neverResolves;
    }

    /** Resolves the scenario from a beneficiary IFSC; non-reserved values map to {@link #SUCCESS}. */
    public static MockIciciDisbursementScenario forIfsc(String ifsc) {
        if (ifsc == null) {
            return SUCCESS;
        }
        String normalized = ifsc.trim().toUpperCase(Locale.ROOT);
        for (MockIciciDisbursementScenario scenario : values()) {
            if (scenario.ifscMarker != null && scenario.ifscMarker.equals(normalized)) {
                return scenario;
            }
        }
        return SUCCESS;
    }

    public String actCode() {
        return actCode;
    }

    public String message() {
        return message;
    }

    public DisbursementDisposition terminalDisposition() {
        return terminalDisposition;
    }

    public DisbursementDeclineKind declineKind() {
        return declineKind;
    }

    /** True when even IMPS returns PENDING on the payment call (forces a status-check round trip). */
    public boolean pendingFirst() {
        return pendingFirst;
    }

    /** True when status check never resolves — the worker parks the transaction for reconciliation. */
    public boolean neverResolves() {
        return neverResolves;
    }

    public boolean isDuplicate() {
        return this == DUPLICATE;
    }
}
