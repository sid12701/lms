package com.bhawana.lms.service;

import com.bhawana.lms.common.money.Money;
import com.bhawana.lms.common.util.Strings;
import com.bhawana.lms.domain.DisbursementDeclineKind;
import com.bhawana.lms.domain.DisbursementDisposition;
import com.bhawana.lms.domain.DisbursementOutcomeAuditOutcome;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationAuditAction;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusReasonCode;
import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import com.bhawana.lms.domain.LoanEventType;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Applies a terminal disbursement verdict to persistent state. Every resolution path (manual ops
 * mock outcome, worker auto-resolve, status-check poll) normalises the provider response into a
 * {@link ProviderOutcome} and hands it here, so the account state, application transition,
 * loan event, ops alert and outcome audit are written in exactly one place.
 *
 * <p>This runs inside the caller's transaction (the orchestrating methods on
 * {@link LoanDisbursementCommandService} are either {@code @Transactional} or open a
 * {@code TransactionTemplate} around the call); it deliberately carries no transaction annotation
 * so the request-log, account, transition, loan event and audit writes stay atomic with the
 * surrounding command. {@link LoanEventLog} enforces that boundary rather than trusting it.
 */
@Service
public class DisbursementOutcomeApplier {

    private final LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final LoanApplicationStatusWriter loanApplicationStatusWriter;
    private final LoanEventLog loanEventLog;
    private final OpsAlertEmitters opsAlertEmitters;
    private final DisbursementOutcomeAuditService disbursementOutcomeAuditService;
    private final ObjectMapper objectMapper;

    public DisbursementOutcomeApplier(
            LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository,
            LoanAccountRepository loanAccountRepository,
            LoanApplicationStatusWriter loanApplicationStatusWriter,
            LoanEventLog loanEventLog,
            OpsAlertEmitters opsAlertEmitters,
            DisbursementOutcomeAuditService disbursementOutcomeAuditService,
            ObjectMapper objectMapper
    ) {
        this.loanDisbursementRequestLogRepository = loanDisbursementRequestLogRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanApplicationStatusWriter = loanApplicationStatusWriter;
        this.loanEventLog = loanEventLog;
        this.opsAlertEmitters = opsAlertEmitters;
        this.disbursementOutcomeAuditService = disbursementOutcomeAuditService;
        this.objectMapper = objectMapper;
    }

    /**
     * Maps the normalised {@link ProviderOutcome} onto the loan-account state, the application
     * transition (technical declines retry; business declines reject), the loan event and the
     * outcome audit, and persists the provider response on the request log.
     */
    LoanApplication apply(
            LoanApplication application,
            LoanAccount loanAccount,
            LoanDisbursementRequestLog latestRequest,
            ProviderOutcome outcome,
            String actorUsername,
            String actorIp,
            String correlationId
    ) {
        Resolution resolution = resolve(outcome);

        latestRequest.updateOutcome(
                resolution.providerStatus(),
                outcome.actCode(),
                outcome.bankRrn(),
                outcome.declineKind(),
                serializeDisbursementOutcomeResponse(latestRequest, outcome, resolution, actorUsername)
        );
        loanDisbursementRequestLogRepository.save(latestRequest);

        // ADR 0004: on success the processing fee actually charged is the principal minus the net
        // cash sent to the adapter. Persisted only on DISBURSED (no fee on retry/pending).
        BigDecimal chargedProcessingFee = resolution.accountStatus() == LoanAccountStatus.DISBURSED
                ? Money.scale(Money.scale(loanAccount.getPrincipalAmount()).subtract(latestRequest.getAmount()))
                : null;
        loanAccount.updateDisbursementStatus(resolution.accountStatus(), Instant.now(), chargedProcessingFee);
        loanAccountRepository.save(loanAccount);

        loanApplicationStatusWriter.updateStatus(
                application,
                LoanApplicationStatusTransitionCommand.builder()
                        .targetStatus(resolution.applicationStatus())
                        .actorUsername(actorUsername)
                        .note(resolution.note())
                        .reasonCode(resolution.reasonCode())
                        .auditAction(LoanApplicationAuditAction.STATUS_TRANSITION)
                        .transitionContext(resolution.transitionContext())
                        .build()
        );

        // Every resolution is a recorded fact: there is no disbursement outcome a partner may not
        // observe, including the reconciliation park that resolves to no terminal verdict yet.
        loanEventLog.append(
                application.getLsp(),
                resolution.eventType(),
                "LOAN_ACCOUNT",
                loanAccount.getId().toString(),
                application.getId(),
                LoanEventPayloads.disbursement(application, loanAccount, latestRequest)
        );

        if (resolution.businessDecline()) {
            opsAlertEmitters.emitLspBoundViolation(
                    application,
                    "PROVIDER_BUSINESS_DECLINE",
                    "Disbursement declined by provider (business decline, ActCode "
                            + outcome.actCode() + "). " + outcome.message(),
                    Map.of("actCode", outcome.actCode() == null ? "" : outcome.actCode())
            );
        } else if (resolution.accountStatus() == LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION) {
            // A transaction parked for manual reconciliation leaves money movement in limbo; surface
            // it to ops (deduped) so a stuck disbursement is never silently abandoned.
            opsAlertEmitters.emitLspBoundViolation(
                    application,
                    "DISBURSEMENT_PENDING_RECONCILIATION",
                    "Disbursement parked for manual reconciliation. " + outcome.message(),
                    Map.of("actCode", outcome.actCode() == null ? "" : outcome.actCode())
            );
        }

        disbursementOutcomeAuditService.recordOutcomeApplied(
                application,
                loanAccount,
                actorUsername,
                actorIp,
                correlationId,
                resolution.auditOutcome(),
                latestRequest.getProviderRequestId()
        );
        return application;
    }

    private Resolution resolve(ProviderOutcome outcome) {
        return switch (outcome.disposition()) {
            case SUCCESS -> new Resolution(
                    LoanAccountStatus.DISBURSED,
                    LoanApplicationStatus.DISBURSED,
                    LoanApplicationStatusTransitioner.TransitionContext.STANDARD,
                    null,
                    "Loan disbursement completed successfully.",
                    LoanEventType.DISBURSEMENT_COMPLETED,
                    DisbursementOutcomeAuditOutcome.DISBURSED,
                    "DISBURSED",
                    false
            );
            case FAILED -> outcome.declineKind() == DisbursementDeclineKind.BUSINESS
                    ? new Resolution(
                            LoanAccountStatus.DISBURSEMENT_FAILED,
                            LoanApplicationStatus.REJECTED,
                            LoanApplicationStatusTransitioner.TransitionContext.WORKER,
                            LoanApplicationStatusReasonCode.FAILED_VERIFICATION,
                            "Disbursement declined by provider (business decline, ActCode "
                                    + outcome.actCode() + "). " + outcome.message(),
                            LoanEventType.DISBURSEMENT_FAILED,
                            DisbursementOutcomeAuditOutcome.FAILED,
                            "FAILED",
                            true
                    )
                    : new Resolution(
                            LoanAccountStatus.DISBURSEMENT_FAILED,
                            LoanApplicationStatus.DISBURSEMENT_RETRY,
                            LoanApplicationStatusTransitioner.TransitionContext.STANDARD,
                            LoanApplicationStatusReasonCode.POLICY_EXCEPTION,
                            "Loan disbursement requires retry after failed/pending-reconciliation outcome.",
                            LoanEventType.DISBURSEMENT_FAILED,
                            DisbursementOutcomeAuditOutcome.FAILED,
                            "FAILED",
                            false
                    );
            case PENDING -> new Resolution(
                    LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION,
                    LoanApplicationStatus.DISBURSEMENT_RETRY,
                    LoanApplicationStatusTransitioner.TransitionContext.STANDARD,
                    LoanApplicationStatusReasonCode.POLICY_EXCEPTION,
                    "Loan disbursement requires retry after failed/pending-reconciliation outcome.",
                    LoanEventType.DISBURSEMENT_PENDING_RECONCILIATION,
                    DisbursementOutcomeAuditOutcome.PENDING_RECONCILIATION,
                    "PENDING_RECONCILIATION",
                    false
            );
        };
    }

    private String serializeDisbursementOutcomeResponse(
            LoanDisbursementRequestLog requestLog,
            ProviderOutcome outcome,
            Resolution resolution,
            String actorUsername
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", requestLog.getProviderName());
        payload.put("providerRequestId", requestLog.getProviderRequestId());
        payload.put("tranRefNo", requestLog.getTranRefNo());
        payload.put("status", resolution.providerStatus());
        payload.put("actCode", outcome.actCode());
        payload.put("bankRRN", outcome.bankRrn());
        payload.put("declineKind", outcome.declineKind() == null ? null : outcome.declineKind().name());
        payload.put("resolvedBy", Strings.normalizeActor(actorUsername));
        payload.put("message", outcome.message());

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize disbursement outcome payload.", exception);
        }
    }

    /** Normalised provider verdict that drives {@link #apply}. */
    record ProviderOutcome(
            DisbursementDisposition disposition,
            DisbursementDeclineKind declineKind,
            String actCode,
            String bankRrn,
            String message
    ) {
    }

    /** Derived target states for a resolved disbursement. */
    private record Resolution(
            LoanAccountStatus accountStatus,
            LoanApplicationStatus applicationStatus,
            LoanApplicationStatusTransitioner.TransitionContext transitionContext,
            LoanApplicationStatusReasonCode reasonCode,
            String note,
            LoanEventType eventType,
            DisbursementOutcomeAuditOutcome auditOutcome,
            String providerStatus,
            boolean businessDecline
    ) {
    }
}
