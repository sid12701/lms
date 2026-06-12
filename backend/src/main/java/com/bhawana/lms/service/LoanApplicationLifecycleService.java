package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.money.Money;
import com.bhawana.lms.common.util.Strings;
import com.bhawana.lms.common.web.ApiConflictException;
import com.bhawana.lms.common.web.BusinessRuleViolationException;
import com.bhawana.lms.common.web.ResourceNotFoundException;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.BorrowerProfile;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountClosureReason;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationAuditAction;
import com.bhawana.lms.domain.LoanApplicationAuditEvent;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.domain.LoanApplicationIntakeAudit;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusReasonCode;
import com.bhawana.lms.domain.LoanApplicationStatusTransition;
import com.bhawana.lms.domain.LoanInvalidationReason;
import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductLspMapping;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationAuditEventRepository;
import com.bhawana.lms.repo.LoanApplicationIntakeAuditRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.LspRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanApplicationLifecycleService {

    private final LoanAccountRepository loanAccountRepository;
    private final LoanApplicationAuditEventRepository loanApplicationAuditEventRepository;
    private final LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository;
    private final LoanProductRepository loanProductRepository;
    private final LoanRepaymentScheduleService loanRepaymentScheduleService;
    private final LspRepository lspRepository;
    private final LoanProductLspMappingRepository loanProductLspMappingRepository;
    private final BorrowerOnboardingService borrowerOnboardingService;
    private final LoanApplicationDocumentChecklistService documentChecklistService;
    private final WebhookOutboxService webhookOutboxService;
    private final LoanAutoApprovalRuleEngine loanAutoApprovalRuleEngine;
    private final OpsAlertEmitters opsAlertEmitters;
    private final ObjectMapper objectMapper;

    public LoanApplicationLifecycleService(
            LoanAccountRepository loanAccountRepository,
            LoanApplicationAuditEventRepository loanApplicationAuditEventRepository,
            LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository,
            LoanApplicationRepository loanApplicationRepository,
            LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository,
            LoanProductRepository loanProductRepository,
            @Lazy LoanRepaymentScheduleService loanRepaymentScheduleService,
            LspRepository lspRepository,
            LoanProductLspMappingRepository loanProductLspMappingRepository,
            BorrowerOnboardingService borrowerOnboardingService,
            LoanApplicationDocumentChecklistService documentChecklistService,
            WebhookOutboxService webhookOutboxService,
            LoanAutoApprovalRuleEngine loanAutoApprovalRuleEngine,
            OpsAlertEmitters opsAlertEmitters,
            ObjectMapper objectMapper
    ) {
        this.loanAccountRepository = loanAccountRepository;
        this.loanApplicationAuditEventRepository = loanApplicationAuditEventRepository;
        this.loanApplicationIntakeAuditRepository = loanApplicationIntakeAuditRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanApplicationStatusTransitionRepository = loanApplicationStatusTransitionRepository;
        this.loanProductRepository = loanProductRepository;
        this.loanRepaymentScheduleService = loanRepaymentScheduleService;
        this.lspRepository = lspRepository;
        this.loanProductLspMappingRepository = loanProductLspMappingRepository;
        this.borrowerOnboardingService = borrowerOnboardingService;
        this.documentChecklistService = documentChecklistService;
        this.webhookOutboxService = webhookOutboxService;
        this.loanAutoApprovalRuleEngine = loanAutoApprovalRuleEngine;
        this.opsAlertEmitters = opsAlertEmitters;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public LoanApplication createApplication(String actorUsername, LoanApplicationOnboardingCommand command) {
        var lsp = lspRepository.findById(command.lspId())
                .orElseThrow(() -> new ResourceNotFoundException("Unknown LSP id: " + command.lspId()));
        if (lsp.getStatus() != LspStatus.ACTIVE) {
            throw new BusinessRuleViolationException(
                    "LSP_NOT_ACTIVE",
                    "Loan applications can only be created for active LSPs.",
                    Map.of("lspId", command.lspId().toString())
            );
        }

        var loanProduct = resolveLoanProduct(command);
        if (loanProduct.getStatus() != LoanProductStatus.ACTIVE) {
            throw new BusinessRuleViolationException(
                    "PRODUCT_NOT_ACTIVE",
                    "Loan applications can only be created for active loan products.",
                    Map.of("productId", loanProduct.getId().toString())
            );
        }

        validateInterestRate(command.interestRate(), loanProduct.getInterestRate());

        LoanProductLspMapping mapping = loanProductLspMappingRepository.findByLsp_IdAndLoanProduct_Id(command.lspId(), loanProduct.getId())
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "PRODUCT_NOT_MAPPED",
                        "Requested product is not mapped to the selected LSP.",
                        Map.of(
                                "lspId", command.lspId().toString(),
                                "productId", loanProduct.getId().toString()
                        )
                ));
        if (!mapping.isEnabled()) {
            throw new BusinessRuleViolationException(
                    "PRODUCT_MAPPING_DISABLED",
                    "Requested product mapping is disabled for the selected LSP.",
                    Map.of(
                            "lspId", command.lspId().toString(),
                            "productId", loanProduct.getId().toString()
                    )
            );
        }

        String normalizedExternalLoanId = requireField(command.lspLoanId(), "LSP loan id");
        if (loanApplicationRepository.existsByLsp_IdAndExternalLoanIdIgnoreCase(command.lspId(), normalizedExternalLoanId)) {
            throw new ApiConflictException(
                    "DUPLICATE_EXTERNAL_LOAN_ID",
                    "External loan id already exists for the selected LSP."
            );
        }

        BigDecimal scaledRequestedAmount = Money.scale(Money.requirePositive(command.loanAmount(), "Loan amount"));
        if (scaledRequestedAmount.compareTo(loanProduct.getMinPrincipal()) < 0
                || scaledRequestedAmount.compareTo(loanProduct.getMaxPrincipal()) > 0) {
            throw new BusinessRuleViolationException(
                    "AMOUNT_OUT_OF_RANGE",
                    "Requested amount is outside the configured product principal range.",
                    Map.of("loanAmount", scaledRequestedAmount.toPlainString())
            );
        }

        int tenureMonths = requireTenure(command.loanTenure());
        if (tenureMonths < loanProduct.getMinTenureMonths() || tenureMonths > loanProduct.getMaxTenureMonths()) {
            throw new BusinessRuleViolationException(
                    "TENURE_OUT_OF_RANGE",
                    "Requested tenure is outside the configured product tenure range.",
                    Map.of("loanTenure", String.valueOf(tenureMonths))
            );
        }

        BorrowerProfile borrowerProfile = command.borrowerProfile();
        BigDecimal monthlyIncome = normalizeMonthlyIncome(
                borrowerProfile.monthlyIncome(),
                borrowerProfile.annualIncome()
        );
        BigDecimal annualIncome = normalizeAnnualIncome(
                borrowerProfile.monthlyIncome(),
                borrowerProfile.annualIncome()
        );
        Borrower borrower = borrowerOnboardingService.resolveBorrowerForOnboarding(
                lsp,
                command,
                monthlyIncome,
                annualIncome,
                actorUsername
        );

        LoanApplication application = new LoanApplication(
                borrower,
                lsp,
                loanProduct,
                normalizedExternalLoanId,
                normalizeSourceChannel(command.sourceChannel()),
                scaledRequestedAmount,
                tenureMonths,
                LoanApplicationStatus.INITIALIZED
        );
        LoanApplication savedApplication = loanApplicationRepository.save(application);
        loanApplicationIntakeAuditRepository.save(new LoanApplicationIntakeAudit(
                savedApplication,
                actorUsername,
                CorrelationIdHolder.get(),
                serializePayload(savedApplication)
        ));
        documentChecklistService.seedDocumentChecklist(savedApplication, actorUsername);
        webhookOutboxService.enqueueIfSubscribed(
                savedApplication.getLsp(),
                WebhookEventType.LOAN_CREATED,
                "LOAN_APPLICATION",
                savedApplication.getId().toString(),
                savedApplication.getId(),
                LoanWebhookPayloads.loanCreated(savedApplication)
        );
        return savedApplication;
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

        LoanApplication application = getApplication(applicationId);
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
        LoanApplication savedApplication = updateApplicationStatus(
                application,
                targetStatus,
                actorUsername,
                resolvedNote,
                resolvedReasonCode,
                LoanApplicationAuditAction.STATUS_TRANSITION
        );
        if (targetStatus == LoanApplicationStatus.APPROVED_PENDING_DISBURSAL) {
            ensureLoanAccountForApprovedApplication(savedApplication);
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

        LoanApplication application = getApplication(applicationId);
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
        return updateApplicationStatus(
                application,
                targetStatus,
                actorUsername,
                resolvedNote,
                resolvedReasonCode,
                LoanApplicationAuditAction.MANUAL_STATUS_OVERRIDE,
                null,
                LoanApplicationStatusTransitioner.TransitionContext.MANUAL_OVERRIDE
        );
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

    @Transactional
    public LoanApplication invalidateApplicationForLsp(
            UUID lspId,
            UUID applicationId,
            String actorUsername,
            LoanInvalidationReason invalidReason,
            String invalidReasonText
    ) {
        LoanApplication application = getApplicationForLsp(lspId, applicationId);
        LoanAccount loanAccount = loanAccountRepository.findByLoanApplication_Id(applicationId).orElse(null);
        return invalidateApplication(application, loanAccount, actorUsername, invalidReason, invalidReasonText);
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

    public boolean hasAllRequiredDocumentsUploaded(UUID applicationId) {
        return documentChecklistService.hasAllRequiredDocumentsUploaded(applicationId);
    }

    /**
     * Gap #11 + Follow-up #1 — re-evaluates the auto-approval rule set on
     * every doc upload / field update. On success: moves the application
     * forward through {@code INITIALIZED → AWAITING_APPROVAL →
     * APPROVED_PENDING_DISBURSAL}. On failure from {@code AWAITING_APPROVAL}:
     * transitions to {@code REJECTED} with a structured
     * {@code rejection_reason_json} listing the failed rule codes.
     */
    @Transactional
    public LoanApplication autoApproveIfEligibleForLsp(UUID applicationId, String actorUsername) {
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
            savedApplication = updateApplicationStatus(
                    savedApplication,
                    LoanApplicationStatus.AWAITING_APPROVAL,
                    actorUsername,
                    "Application moved to approval after all auto-approval rules passed.",
                    null,
                    LoanApplicationAuditAction.STATUS_TRANSITION
            );
        }
        if (savedApplication.getStatus() == LoanApplicationStatus.AWAITING_APPROVAL) {
            savedApplication = updateApplicationStatus(
                    savedApplication,
                    LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                    actorUsername,
                    "Loan auto-approved by the rule engine after all eligibility checks passed.",
                    null,
                    LoanApplicationAuditAction.STATUS_TRANSITION
            );
            ensureLoanAccountForApprovedApplication(savedApplication);
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
        return updateApplicationStatus(
                application,
                LoanApplicationStatus.REJECTED,
                actorUsername,
                note,
                LoanApplicationStatusReasonCode.FAILED_VERIFICATION,
                LoanApplicationAuditAction.STATUS_TRANSITION,
                rejectionJson
        );
    }

    private String serializeRejectionReason(LoanAutoApprovalRuleEngine.Evaluation evaluation) {
        try {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("failedRules", evaluation.failedRules().stream().map(Enum::name).toList());
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize auto-rejection reason payload.", exception);
        }
    }

    public void ensureDocumentChecklist(LoanApplication application) {
        documentChecklistService.ensureDocumentChecklist(application);
    }

    public void validateRequiredDocumentsUploadedBeforeDisbursement(UUID applicationId) {
        documentChecklistService.validateRequiredDocumentsUploadedBeforeDisbursement(applicationId);
    }

    /** All eight intake-required document types must be submitted with LMS-managed content before disbursement. */
    public boolean hasAllRequiredLmsManagedDocuments(UUID applicationId) {
        return documentChecklistService.hasAllRequiredLmsManagedDocuments(applicationId);
    }

    public LoanApplication updateApplicationStatus(
            LoanApplication application,
            LoanApplicationStatus targetStatus,
            String actorUsername,
            String note,
            LoanApplicationStatusReasonCode reasonCode,
            LoanApplicationAuditAction auditAction
    ) {
        return updateApplicationStatus(
                application,
                targetStatus,
                actorUsername,
                note,
                reasonCode,
                auditAction,
                null,
                LoanApplicationStatusTransitioner.TransitionContext.STANDARD
        );
    }

    public LoanApplication updateApplicationStatus(
            LoanApplication application,
            LoanApplicationStatus targetStatus,
            String actorUsername,
            String note,
            LoanApplicationStatusReasonCode reasonCode,
            LoanApplicationAuditAction auditAction,
            String rejectionReasonJson
    ) {
        return updateApplicationStatus(
                application,
                targetStatus,
                actorUsername,
                note,
                reasonCode,
                auditAction,
                rejectionReasonJson,
                LoanApplicationStatusTransitioner.TransitionContext.STANDARD
        );
    }

    public LoanApplication updateApplicationStatus(
            LoanApplication application,
            LoanApplicationStatus targetStatus,
            String actorUsername,
            String note,
            LoanApplicationStatusReasonCode reasonCode,
            LoanApplicationAuditAction auditAction,
            String rejectionReasonJson,
            LoanApplicationStatusTransitioner.TransitionContext transitionContext
    ) {
        LoanApplicationStatus currentStatus = application.getStatus();
        if (currentStatus == targetStatus) {
            return application;
        }

        LoanApplicationStatusTransitioner.enforceTransition(currentStatus, targetStatus, transitionContext);
        application.transitionTo(targetStatus);
        LoanApplication savedApplication = loanApplicationRepository.save(application);
        String resolvedNote = Strings.normalizeOptional(note) == null
                ? defaultTransitionNote(currentStatus, targetStatus)
                : Strings.normalizeOptional(note);
        loanApplicationStatusTransitionRepository.save(new LoanApplicationStatusTransition(
                savedApplication,
                currentStatus,
                targetStatus,
                Strings.normalizeActor(actorUsername),
                resolvedNote,
                reasonCode,
                CorrelationIdHolder.get(),
                rejectionReasonJson
        ));
        recordAuditEvent(
                savedApplication,
                auditAction,
                currentStatus,
                targetStatus,
                actorUsername,
                resolvedNote,
                reasonCode
        );
        webhookOutboxService.enqueueIfSubscribed(
                savedApplication.getLsp(),
                WebhookEventType.LOAN_STATUS_CHANGED,
                "LOAN_APPLICATION",
                savedApplication.getId().toString(),
                savedApplication.getId(),
                LoanWebhookPayloads.loanStatusChanged(savedApplication, currentStatus, targetStatus, reasonCode)
        );
        return savedApplication;
    }

    public LoanAccount ensureLoanAccountForApprovedApplication(LoanApplication application) {
        LoanAccount loanAccount = loanAccountRepository.findByLoanApplication_Id(application.getId())
                .orElseGet(() -> loanAccountRepository.save(new LoanAccount(
                        application,
                        application.getBorrower(),
                        application.getLsp(),
                        application.getLoanProduct(),
                        generateAccountNumber(application),
                        application.getRequestedAmount(),
                        application.getTenureMonths(),
                        LoanAccountStatus.PENDING_DISBURSEMENT,
                        Instant.now()
                )));
        loanRepaymentScheduleService.generateIfAbsent(loanAccount);
        return loanAccount;
    }

    public void recordAuditEvent(
            LoanApplication application,
            LoanApplicationAuditAction action,
            LoanApplicationStatus fromStatus,
            LoanApplicationStatus toStatus,
            String actorUsername,
            String note,
            LoanApplicationStatusReasonCode reasonCode
    ) {
        loanApplicationAuditEventRepository.save(new LoanApplicationAuditEvent(
                application,
                action,
                Strings.normalizeActor(actorUsername),
                fromStatus,
                toStatus,
                note,
                reasonCode,
                CorrelationIdHolder.get()
        ));
    }

    public LoanApplication invalidateApplication(
            LoanApplication application,
            LoanAccount loanAccount,
            String actorUsername,
            LoanInvalidationReason invalidReason,
            String invalidReasonText
    ) {
        if (invalidReason == null) {
            throw new IllegalArgumentException("Invalid loan reason is required.");
        }

        LoanApplicationStatus currentStatus = application.getStatus();
        if (currentStatus == LoanApplicationStatus.INVALID) {
            throw new ApiConflictException("LOAN_ALREADY_INVALID", "Loan application is already marked invalid.");
        }
        if (currentStatus.hasEnteredServicing()) {
            throw new BusinessRuleViolationException(
                    "INVALIDATION_NOT_ALLOWED",
                    "Loan applications that have entered servicing cannot be marked invalid.",
                    Map.of("status", currentStatus.name())
            );
        }

        if (loanAccount != null) {
            if (loanAccount.getStatus() == LoanAccountStatus.DISBURSED
                    || loanAccount.getStatus() == LoanAccountStatus.CLOSED
                    || loanAccount.getStatus() == LoanAccountStatus.FORECLOSED) {
                throw new BusinessRuleViolationException(
                        "INVALIDATION_NOT_ALLOWED",
                        "Loan applications that have entered servicing cannot be marked invalid.",
                        Map.of("loanAccountStatus", loanAccount.getStatus().name())
                );
            }
            if (loanAccount.getStatus() == LoanAccountStatus.INVALID) {
                throw new ApiConflictException("LOAN_ALREADY_INVALID", "Loan application is already marked invalid.");
            }
        }

        String normalizedActor = Strings.normalizeActor(actorUsername);
        String normalizedInvalidReasonText = normalizeInvalidReasonText(invalidReason, invalidReasonText);
        Instant invalidatedAt = Instant.now();
        String invalidationNote = buildInvalidationNote(invalidReason, normalizedInvalidReasonText);

        application.markInvalid(
                invalidReason,
                normalizedInvalidReasonText,
                normalizedActor,
                invalidatedAt
        );

        if (loanAccount != null) {
            loanAccount.markInvalid();
            loanAccountRepository.save(loanAccount);
        }

        LoanApplication savedApplication = loanApplicationRepository.save(application);
        loanApplicationStatusTransitionRepository.save(new LoanApplicationStatusTransition(
                savedApplication,
                currentStatus,
                LoanApplicationStatus.INVALID,
                normalizedActor,
                invalidationNote,
                null,
                CorrelationIdHolder.get()
        ));
        recordAuditEvent(
                savedApplication,
                LoanApplicationAuditAction.INVALIDATED,
                currentStatus,
                LoanApplicationStatus.INVALID,
                normalizedActor,
                invalidationNote,
                null
        );
        webhookOutboxService.enqueueIfSubscribed(
                savedApplication.getLsp(),
                WebhookEventType.LOAN_STATUS_CHANGED,
                "LOAN_APPLICATION",
                savedApplication.getId().toString(),
                savedApplication.getId(),
                LoanWebhookPayloads.loanStatusChanged(
                        savedApplication,
                        currentStatus,
                        LoanApplicationStatus.INVALID,
                        null
                )
        );
        return savedApplication;
    }

    private LoanApplication getApplication(UUID applicationId) {
        return loanApplicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown loan application id: " + applicationId));
    }

    private LoanApplication getApplicationForLsp(UUID lspId, UUID applicationId) {
        LoanApplication application = getApplication(applicationId);
        if (!application.getLsp().getId().equals(lspId)) {
            throw new ResourceNotFoundException("Unknown loan application id: " + applicationId);
        }
        return application;
    }

    private LoanProduct resolveLoanProduct(LoanApplicationOnboardingCommand command) {
        if (command.productId() != null) {
            return loanProductRepository.findById(command.productId())
                    .orElseThrow(() -> new ResourceNotFoundException("Unknown loan product id: " + command.productId()));
        }
        String loanProductCode = Strings.normalizeOptional(command.loanProduct());
        if (loanProductCode == null) {
            throw new IllegalArgumentException("Loan product is required.");
        }
        return loanProductRepository.findByCodeIgnoreCase(loanProductCode)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown loan product code: " + loanProductCode));
    }

    private String serializePayload(LoanApplication application) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanApplicationId", application.getId());
        payload.put("lspId", application.getLsp().getId());
        payload.put("lspCode", application.getLsp().getCode());
        payload.put("productId", application.getLoanProduct().getId());
        payload.put("productCode", application.getLoanProduct().getCode());
        payload.put("externalLoanId", application.getExternalLoanId());
        payload.put("sourceChannel", application.getSourceChannel());
        payload.put("requestedAmount", application.getRequestedAmount());
        payload.put("tenureMonths", application.getRequestedTenureMonths());
        payload.putAll(BorrowerProfile.fromEntity(application.getBorrower()).intakeAuditEntries());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize loan application intake payload.", exception);
        }
    }

    private static String normalizeSourceChannel(String sourceChannel) {
        return sourceChannel.trim().toUpperCase();
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

    private static String normalizeInvalidReasonText(
            LoanInvalidationReason invalidReason,
            String invalidReasonText
    ) {
        String normalized = Strings.normalizeOptional(invalidReasonText);
        if (invalidReason.requiresDetail() && normalized == null) {
            throw new IllegalArgumentException("Other invalid loan reason text is required when reason is OTHERS.");
        }
        if (!invalidReason.requiresDetail() && normalized != null) {
            throw new IllegalArgumentException("Other invalid loan reason text is only allowed when reason is OTHERS.");
        }
        return normalized;
    }

    private static String buildInvalidationNote(
            LoanInvalidationReason invalidReason,
            String invalidReasonText
    ) {
        if (invalidReason.requiresDetail()) {
            return "Loan marked invalid. Reason: " + invalidReason.getLabel() + " - " + invalidReasonText;
        }
        return "Loan marked invalid. Reason: " + invalidReason.getLabel();
    }

    private static void validateInterestRate(BigDecimal requestedInterestRate, BigDecimal configuredInterestRate) {
        if (requestedInterestRate == null) {
            return;
        }
        BigDecimal normalizedRequestedRate = requestedInterestRate.setScale(2, RoundingMode.HALF_UP);
        BigDecimal normalizedConfiguredRate = configuredInterestRate.setScale(2, RoundingMode.HALF_UP);
        if (normalizedRequestedRate.compareTo(normalizedConfiguredRate) != 0) {
            throw new BusinessRuleViolationException(
                    "INTEREST_RATE_MISMATCH",
                    "Requested interest rate does not match the configured product interest rate.",
                    Map.of(
                            "interestRate", normalizedRequestedRate.toPlainString(),
                            "configuredInterestRate", normalizedConfiguredRate.toPlainString()
                    )
            );
        }
    }

    private static BigDecimal normalizeMonthlyIncome(BigDecimal monthlyIncome, BigDecimal annualIncome) {
        if (monthlyIncome != null) {
            return Money.scale(monthlyIncome);
        }
        if (annualIncome == null) {
            return null;
        }
        return Money.scale(annualIncome.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP));
    }

    private static BigDecimal normalizeAnnualIncome(BigDecimal monthlyIncome, BigDecimal annualIncome) {
        if (annualIncome != null) {
            return Money.scale(annualIncome);
        }
        if (monthlyIncome == null) {
            return null;
        }
        return Money.scale(monthlyIncome.multiply(BigDecimal.valueOf(12)));
    }

    private static int requireTenure(Integer loanTenure) {
        if (loanTenure == null || loanTenure < 1) {
            throw new IllegalArgumentException("Loan tenure must be at least 1 month.");
        }
        return loanTenure;
    }

    private static String requireField(String value, String fieldName) {
        String normalized = Strings.normalizeOptional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return normalized;
    }

    private static String generateAccountNumber(LoanApplication application) {
        String compactId = application.getId().toString().replace("-", "").toUpperCase();
        return "LMS-LN-" + compactId.substring(0, 12);
    }

}
