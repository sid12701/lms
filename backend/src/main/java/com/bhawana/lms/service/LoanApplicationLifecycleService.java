package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import com.bhawana.lms.common.util.JsonPayloadSerializer;
import com.bhawana.lms.common.util.Strings;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationAuditAction;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusReasonCode;
import com.bhawana.lms.domain.LoanInvalidationReason;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import java.util.EnumSet;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin façade for loan-application lifecycle operations. Mutations delegate to focused
 * collaborators; status changes always flow through {@link LoanApplicationStatusWriter}.
 */
@Service
public class LoanApplicationLifecycleService {

    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanApplicationOnboardingService onboardingService;
    private final LoanApplicationInvalidationService invalidationService;
    private final LoanApplicationStatusWriter statusWriter;
    private final LoanApplicationDocumentChecklistService documentChecklistService;
    private final LoanAutoApprovalRuleEngine loanAutoApprovalRuleEngine;
    private final OpsAlertEmitters opsAlertEmitters;
    private final JsonPayloadSerializer jsonPayloadSerializer;
    private final LoanAccountRepository loanAccountRepository;
    private final DisbursementIntentRepository disbursementIntentRepository;

    /**
     * H31 — financial lifecycle states that a generic status request must never write directly.
     * DISBURSED is written only by the accepted bank-outcome path (C02's
     * {@link DisbursementOutcomeApplier}, which bypasses this façade via the status writer);
     * CLOSED/FORECLOSED only by a validated settlement command (final EMI / foreclosure).
     * An admin reason is not financial evidence.
     */
    private static final Set<LoanApplicationStatus> FINANCIAL_EVIDENCE_TARGETS =
            EnumSet.of(
                    LoanApplicationStatus.DISBURSED,
                    LoanApplicationStatus.CLOSED,
                    LoanApplicationStatus.FORECLOSED);
    private final EntityManager entityManager;

    public LoanApplicationLifecycleService(
            LoanApplicationRepository loanApplicationRepository,
            LoanApplicationOnboardingService onboardingService,
            LoanApplicationInvalidationService invalidationService,
            LoanApplicationStatusWriter statusWriter,
            LoanApplicationDocumentChecklistService documentChecklistService,
            LoanAutoApprovalRuleEngine loanAutoApprovalRuleEngine,
            OpsAlertEmitters opsAlertEmitters,
            JsonPayloadSerializer jsonPayloadSerializer,
            LoanAccountRepository loanAccountRepository,
            DisbursementIntentRepository disbursementIntentRepository,
            EntityManager entityManager
    ) {
        this.loanApplicationRepository = loanApplicationRepository;
        this.onboardingService = onboardingService;
        this.invalidationService = invalidationService;
        this.statusWriter = statusWriter;
        this.documentChecklistService = documentChecklistService;
        this.loanAutoApprovalRuleEngine = loanAutoApprovalRuleEngine;
        this.opsAlertEmitters = opsAlertEmitters;
        this.jsonPayloadSerializer = jsonPayloadSerializer;
        this.loanAccountRepository = loanAccountRepository;
        this.disbursementIntentRepository = disbursementIntentRepository;
        this.entityManager = entityManager;
    }

    public LoanApplication createApplication(String actorUsername, LoanApplicationOnboardingCommand command) {
        return onboardingService.createApplication(actorUsername, command);
    }

    public LoanApplication createApplication(
            String actorUsername,
            LoanApplicationOnboardingCommand command,
            UUID enforcedLspId
    ) {
        return onboardingService.createApplication(actorUsername, command, enforcedLspId);
    }

    @Transactional
    public LoanApplication transitionStatus(
            UUID applicationId,
            String actorUsername,
            LoanApplicationStatus targetStatus,
            String note,
            LoanApplicationStatusReasonCode reasonCode
    ) {
        if (targetStatus == null) {
            throw new IllegalArgumentException("Target status is required.");
        }
        rejectDirectFinancialTarget(targetStatus);

        // H31 generic in-flight guard: shared loan-command lock order
        // borrower → application → account → live intent. No generic mutation while the
        // account is REQUESTED/PENDING_RECONCILIATION or a live intent exists; the only
        // forward path there is reconciliation of the original reference.
        LoanApplication application = lockAndRecheckInFlight(applicationId);
        LoanApplicationStatus currentStatus = application.getStatus();
        if (currentStatus == targetStatus) {
            throw new ApiConflictException(
                    "LOAN_ALREADY_IN_STATUS",
                    "Loan application is already in status " + currentStatus.name() + "."
            );
        }
        LoanApplicationStatusTransitioner.enforceTransition(currentStatus, targetStatus);
        if (currentStatus == LoanApplicationStatus.AWAITING_APPROVAL
                && targetStatus == LoanApplicationStatus.APPROVED_PENDING_DISBURSAL) {
            documentChecklistService.validateKycCompletionBeforeApproval(applicationId);
        }

        LoanApplicationStatusReasonCode resolvedReasonCode = validateTransitionReasonCode(targetStatus, reasonCode);
        String resolvedNote = resolveTransitionNote(note, currentStatus, targetStatus);
        if (targetStatus == LoanApplicationStatus.APPROVED_PENDING_DISBURSAL) {
            resolvedNote = recordManualRuleEngineOverride(application, actorUsername, resolvedNote);
        }
        LoanApplication savedApplication = statusWriter.updateStatus(
                application,
                LoanApplicationStatusTransitionCommand.statusTransition(
                        targetStatus,
                        actorUsername,
                        resolvedNote,
                        resolvedReasonCode,
                        LoanApplicationAuditAction.STATUS_TRANSITION
                )
        );
        if (targetStatus == LoanApplicationStatus.APPROVED_PENDING_DISBURSAL) {
            statusWriter.ensureLoanAccountForApprovedApplication(savedApplication);
        }
        return savedApplication;
    }

    @Transactional
    public LoanApplication manuallyOverrideStatus(
            UUID applicationId,
            String actorUsername,
            LoanApplicationStatus targetStatus,
            String note,
            LoanApplicationStatusReasonCode reasonCode
    ) {
        if (targetStatus == null) {
            throw new IllegalArgumentException("Target status is required.");
        }
        // H31 first: a manual override is never financial evidence, even for otherwise
        // override-eligible sources. This precedes the MANUAL_OVERRIDE_NOT_ALLOWED checks so the
        // failure reason is stable and names the missing evidence.
        rejectDirectFinancialTarget(targetStatus);

        // H31 generic in-flight guard (same shared order as above): a parked
        // DISBURSEMENT_RETRY loan must stay recoverable under its original bank
        // reference instead of being hidden by a manual REJECTED.
        LoanApplication application = lockAndRecheckInFlight(applicationId);
        LoanApplicationStatus currentStatus = application.getStatus();
        if (currentStatus == targetStatus) {
            throw new ApiConflictException(
                    "LOAN_ALREADY_IN_STATUS",
                    "Loan application is already in status " + currentStatus.name() + "."
            );
        }
        if (currentStatus.blocksManualOverrideSource()) {
            throw new BusinessRuleViolationException(
                    "MANUAL_OVERRIDE_NOT_ALLOWED",
                    "Loan applications that have entered servicing cannot be manually overridden.",
                    Map.of("status", currentStatus.name())
            );
        }
        if (targetStatus.blocksManualOverrideTarget()) {
            throw new BusinessRuleViolationException(
                    "MANUAL_OVERRIDE_NOT_ALLOWED",
                    "Use the standard approval flow instead of a manual status update.",
                    Map.of("targetStatus", targetStatus.name())
            );
        }
        if (!targetStatus.isAllowedManualOverrideTarget()) {
            throw new BusinessRuleViolationException(
                    "MANUAL_OVERRIDE_NOT_ALLOWED",
                    "Manual status updates are not supported for " + targetStatus.name() + ".",
                    Map.of("targetStatus", targetStatus.name())
            );
        }

        LoanApplicationStatusReasonCode resolvedReasonCode = requireReasonCode(
                reasonCode,
                "Manual status reason code is required."
        );
        String resolvedNote = recordManualRuleEngineOverride(
                application,
                actorUsername,
                "Manual override: " + requireNote(note)
        );
        return statusWriter.updateStatus(
                application,
                LoanApplicationStatusTransitionCommand.builder()
                        .targetStatus(targetStatus)
                        .actorUsername(actorUsername)
                        .note(resolvedNote)
                        .reasonCode(resolvedReasonCode)
                        .auditAction(LoanApplicationAuditAction.MANUAL_STATUS_OVERRIDE)
                        .transitionContext(LoanApplicationStatusTransitioner.TransitionContext.MANUAL_OVERRIDE)
                        .build()
        );
    }

    @Transactional
    public LoanApplication invalidateApplicationForLsp(
            UUID lspId,
            UUID applicationId,
            String actorUsername,
            LoanInvalidationReason invalidReason,
            String invalidReasonText
    ) {
        return invalidationService.invalidateApplicationForLsp(
                lspId, applicationId, actorUsername, invalidReason, invalidReasonText);
    }

    @Transactional
    public DocumentChecklistUpdateResult updateDocumentChecklistItem(
            UUID applicationId,
            LoanApplicationDocumentType documentType,
            String actorUsername,
            LoanApplicationDocumentChecklistStatus status,
            String note,
            String fileName,
            String fileReference,
            String sourceReference,
            String contentType,
            Long fileSizeBytes,
            String fileChecksum,
            String storageKey,
            boolean lmsManagedContent
    ) {
        return documentChecklistService.updateDocumentChecklistItem(
                applicationId,
                documentType,
                actorUsername,
                status,
                note,
                fileName,
                fileReference,
                sourceReference,
                contentType,
                fileSizeBytes,
                fileChecksum,
                storageKey,
                lmsManagedContent
        );
    }

    @Transactional
    public LoanApplication autoApproveIfEligibleForLsp(UUID applicationId, String actorUsername) {
        lockBorrowerForApproval(applicationId);
        LoanApplication application = getApplication(applicationId);
        LoanApplicationStatus currentStatus = application.getStatus();
        LoanApplicationStatusTransitioner.enforceAutoApprovalAllowed(currentStatus);

        LoanAutoApprovalRuleEngine.Evaluation evaluation = loanAutoApprovalRuleEngine.evaluate(application);

        if (!evaluation.approved()) {
            if (currentStatus == LoanApplicationStatus.AWAITING_APPROVAL) {
                return autoRejectApplication(application, actorUsername, evaluation);
            }
            return application;
        }

        LoanApplication savedApplication = application;
        if (savedApplication.getStatus() == LoanApplicationStatus.INITIALIZED) {
            savedApplication = statusWriter.updateStatus(
                    savedApplication,
                    LoanApplicationStatusTransitionCommand.statusTransition(
                            LoanApplicationStatus.AWAITING_APPROVAL,
                            actorUsername,
                            "Application moved to approval after all auto-approval rules passed.",
                            null,
                            LoanApplicationAuditAction.STATUS_TRANSITION
                    )
            );
        }
        if (savedApplication.getStatus() == LoanApplicationStatus.AWAITING_APPROVAL) {
            savedApplication = statusWriter.updateStatus(
                    savedApplication,
                    LoanApplicationStatusTransitionCommand.statusTransition(
                            LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                            actorUsername,
                            "Loan auto-approved by the rule engine after all eligibility checks passed.",
                            null,
                            LoanApplicationAuditAction.STATUS_TRANSITION
                    )
            );
            statusWriter.ensureLoanAccountForApprovedApplication(savedApplication);
        }
        return savedApplication;
    }

    private LoanApplication autoRejectApplication(
            LoanApplication application,
            String actorUsername,
            LoanAutoApprovalRuleEngine.Evaluation evaluation
    ) {
        String rejectionJson = serializeRejectionReason(evaluation);
        String failedRuleList = evaluation.failedRules().stream()
                .map(Enum::name)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        String note = "Auto-rejected by rule engine. Failed rules: " + failedRuleList;
        return statusWriter.updateStatus(
                application,
                LoanApplicationStatusTransitionCommand.withRejection(
                        LoanApplicationStatus.REJECTED,
                        actorUsername,
                        note,
                        LoanApplicationStatusReasonCode.FAILED_VERIFICATION,
                        LoanApplicationAuditAction.STATUS_TRANSITION,
                        rejectionJson
                )
        );
    }

    private String serializeRejectionReason(LoanAutoApprovalRuleEngine.Evaluation evaluation) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("failedRules", evaluation.failedRules().stream().map(Enum::name).toList());
        return jsonPayloadSerializer.serialize(payload);
    }

    private String recordManualRuleEngineOverride(
            LoanApplication application,
            String actorUsername,
            String note
    ) {
        LoanAutoApprovalRuleEngine.Evaluation evaluation = loanAutoApprovalRuleEngine.evaluate(application);
        opsAlertEmitters.emitManualRuleEngineOverride(application, actorUsername, evaluation, note);
        String failedRuleList = evaluation.failedRules().stream()
                .map(Enum::name)
                .reduce((left, right) -> left + "," + right)
                .orElse("none");
        return note
                + " [ruleEngineApproved="
                + evaluation.approved()
                + "; failedRules="
                + failedRuleList
                + "]";
    }

    private LoanApplication getApplication(UUID applicationId) {
        return loanApplicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown loan application id: " + applicationId));
    }

    private static void rejectDirectFinancialTarget(LoanApplicationStatus targetStatus) {
        if (FINANCIAL_EVIDENCE_TARGETS.contains(targetStatus)) {
            throw new BusinessRuleViolationException(
                    "FINANCIAL_STATUS_REQUIRES_EVIDENCE",
                    "Direct status requests cannot write " + targetStatus.name() + "."
                            + " DISBURSED is written only by the accepted bank-outcome path and CLOSED/FORECLOSED"
                            + " only by a validated settlement command.",
                    Map.of("targetStatus", targetStatus.name())
            );
        }
    }

    private void lockBorrowerForApproval(UUID applicationId) {
        loanApplicationRepository.findBorrowerByApplicationIdForUpdate(applicationId)
                .map(lockedBorrower -> {
                    // Refresh after the lock wait: a cached borrower copy must never carry a
                    // stale bank instruction into approval or account creation.
                    entityManager.refresh(lockedBorrower);
                    return lockedBorrower;
                })
                .orElseThrow(() -> new ResourceNotFoundException("Unknown loan application id: " + applicationId));
    }

    /**
     * H31 generic in-flight guard: shared loan-command lock order
     * borrower → application → account → live intent.
     *
     * <p>The borrower lock comes first so approval decisions and generic mutations
     * serialize on the same shared lock; the locked application re-read then observes
     * a concurrently committed intent creation or invalidation. No generic mutation
     * is allowed while the account is {@code DISBURSEMENT_REQUESTED} /
     * {@code DISBURSEMENT_PENDING_RECONCILIATION} or a live intent exists — the only
     * forward path there is reconciliation of the original reference.
     */
    private LoanApplication lockAndRecheckInFlight(UUID applicationId) {
        lockBorrowerForApproval(applicationId);
        LoanApplication lockedApplication = loanApplicationRepository.findByIdForUpdate(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown loan application id: " + applicationId));
        loanAccountRepository.findByLoanApplication_IdForUpdate(applicationId).ifPresent(lockedAccount -> {
            if (lockedAccount.getStatus() == LoanAccountStatus.DISBURSEMENT_REQUESTED
                    || lockedAccount.getStatus() == LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION) {
                throw new ApiConflictException(
                        "DISBURSEMENT_IN_PROGRESS",
                        "Loan status cannot be changed while a disbursement is queued or awaiting reconciliation."
                                + " Reconcile the disbursement outcome first."
                );
            }
            if (disbursementIntentRepository.findLiveByLoanAccountIdForUpdate(lockedAccount.getId()).isPresent()) {
                throw new ApiConflictException(
                        "DISBURSEMENT_IN_PROGRESS",
                        "Loan status cannot be changed while a live disbursement intent exists."
                                + " Reconcile the disbursement outcome first."
                );
            }
        });
        return lockedApplication;
    }

    private static String requireNote(String note) {
        String normalized = Strings.normalizeOptional(note);
        if (normalized == null) {
            throw new IllegalArgumentException("Manual status note is required.");
        }
        return normalized;
    }

    private static LoanApplicationStatusReasonCode validateTransitionReasonCode(
            LoanApplicationStatus targetStatus,
            LoanApplicationStatusReasonCode reasonCode
    ) {
        if (targetStatus == LoanApplicationStatus.REJECTED
                || targetStatus == LoanApplicationStatus.DISBURSEMENT_RETRY) {
            return requireReasonCode(
                    reasonCode,
                    "Reason code is required when a loan application is moved to " + targetStatus.name() + "."
            );
        }
        return reasonCode;
    }

    private static LoanApplicationStatusReasonCode requireReasonCode(
            LoanApplicationStatusReasonCode reasonCode,
            String message
    ) {
        if (reasonCode == null) {
            throw new BusinessRuleViolationException(
                    "REASON_CODE_REQUIRED",
                    message,
                    Map.of("reasonCode", "required")
            );
        }
        return reasonCode;
    }

    private static String resolveTransitionNote(
            String note,
            LoanApplicationStatus currentStatus,
            LoanApplicationStatus targetStatus
    ) {
        if (note == null) {
            return defaultTransitionNote(currentStatus, targetStatus);
        }
        String normalizedNote = note.trim();
        return normalizedNote.isBlank()
                ? defaultTransitionNote(currentStatus, targetStatus)
                : normalizedNote;
    }

    private static String defaultTransitionNote(
            LoanApplicationStatus currentStatus,
            LoanApplicationStatus targetStatus
    ) {
        return "Transitioned loan application from " + currentStatus.name() + " to " + targetStatus.name();
    }
}
