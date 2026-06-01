package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.web.ApiConflictException;
import com.bhawana.lms.common.web.DocumentUploadRequiredException;
import com.bhawana.lms.common.web.KycCompletionRequiredException;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountClosureReason;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationAuditAction;
import com.bhawana.lms.domain.LoanApplicationAuditEvent;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.domain.LoanApplicationIntakeAudit;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusReasonCode;
import com.bhawana.lms.domain.LoanApplicationStatusTransition;
import com.bhawana.lms.domain.LoanInvalidationReason;
import com.bhawana.lms.domain.LoanForeclosureQuote;
import com.bhawana.lms.domain.LoanPaymentTransaction;
import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductLspMapping;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.OpsAlertSeverity;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationAuditEventRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationIntakeAuditRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import com.bhawana.lms.repo.LspRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanApplicationLifecycleService {

    private final BorrowerRepository borrowerRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final LoanApplicationAuditEventRepository loanApplicationAuditEventRepository;
    private final LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    private final LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository;
    private final LoanProductRepository loanProductRepository;
    private final LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;
    private final LspRepository lspRepository;
    private final LoanProductLspMappingRepository loanProductLspMappingRepository;
    private final OpsAlertService opsAlertService;
    private final BorrowerActiveLoanChecker borrowerActiveLoanChecker;
    private final WebhookOutboxService webhookOutboxService;
    private final LoanAutoApprovalRuleEngine loanAutoApprovalRuleEngine;
    private final AlertRuleEvaluationService alertRuleEvaluationService;
    private final ObjectMapper objectMapper;

    public LoanApplicationLifecycleService(
            BorrowerRepository borrowerRepository,
            LoanAccountRepository loanAccountRepository,
            LoanApplicationAuditEventRepository loanApplicationAuditEventRepository,
            LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository,
            LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository,
            LoanApplicationRepository loanApplicationRepository,
            LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository,
            LoanProductRepository loanProductRepository,
            LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository,
            LspRepository lspRepository,
            LoanProductLspMappingRepository loanProductLspMappingRepository,
            OpsAlertService opsAlertService,
            BorrowerActiveLoanChecker borrowerActiveLoanChecker,
            WebhookOutboxService webhookOutboxService,
            LoanAutoApprovalRuleEngine loanAutoApprovalRuleEngine,
            @Lazy AlertRuleEvaluationService alertRuleEvaluationService,
            ObjectMapper objectMapper
    ) {
        this.borrowerRepository = borrowerRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanApplicationAuditEventRepository = loanApplicationAuditEventRepository;
        this.loanApplicationDocumentChecklistRepository = loanApplicationDocumentChecklistRepository;
        this.loanApplicationIntakeAuditRepository = loanApplicationIntakeAuditRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanApplicationStatusTransitionRepository = loanApplicationStatusTransitionRepository;
        this.loanProductRepository = loanProductRepository;
        this.loanRepaymentScheduleInstallmentRepository = loanRepaymentScheduleInstallmentRepository;
        this.lspRepository = lspRepository;
        this.loanProductLspMappingRepository = loanProductLspMappingRepository;
        this.opsAlertService = opsAlertService;
        this.borrowerActiveLoanChecker = borrowerActiveLoanChecker;
        this.webhookOutboxService = webhookOutboxService;
        this.loanAutoApprovalRuleEngine = loanAutoApprovalRuleEngine;
        this.alertRuleEvaluationService = alertRuleEvaluationService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public LoanApplication createApplication(String actorUsername, LoanApplicationOnboardingCommand command) {
        var lsp = lspRepository.findById(command.lspId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown LSP id: " + command.lspId()));
        if (lsp.getStatus() != LspStatus.ACTIVE) {
            throw new IllegalArgumentException("Loan applications can only be created for active LSPs.");
        }

        var loanProduct = resolveLoanProduct(command);
        if (loanProduct.getStatus() != LoanProductStatus.ACTIVE) {
            throw new IllegalArgumentException("Loan applications can only be created for active loan products.");
        }

        validateInterestRate(command.interestRate(), loanProduct.getInterestRate());

        LoanProductLspMapping mapping = loanProductLspMappingRepository.findByLsp_IdAndLoanProduct_Id(command.lspId(), loanProduct.getId())
                .orElseThrow(() -> new IllegalArgumentException("Requested product is not mapped to the selected LSP."));
        if (!mapping.isEnabled()) {
            throw new IllegalArgumentException("Requested product mapping is disabled for the selected LSP.");
        }

        String normalizedExternalLoanId = requireField(command.lspLoanId(), "LSP loan id");
        if (loanApplicationRepository.existsByLsp_IdAndExternalLoanIdIgnoreCase(command.lspId(), normalizedExternalLoanId)) {
            throw new IllegalArgumentException("External loan id already exists for the selected LSP.");
        }

        BigDecimal scaledRequestedAmount = scaleCurrency(requireCurrency(command.loanAmount(), "Loan amount"));
        if (scaledRequestedAmount.compareTo(loanProduct.getMinPrincipal()) < 0
                || scaledRequestedAmount.compareTo(loanProduct.getMaxPrincipal()) > 0) {
            throw new IllegalArgumentException("Requested amount is outside the configured product principal range.");
        }

        int tenureMonths = requireTenure(command.loanTenure());
        if (tenureMonths < loanProduct.getMinTenureMonths() || tenureMonths > loanProduct.getMaxTenureMonths()) {
            throw new IllegalArgumentException("Requested tenure is outside the configured product tenure range.");
        }

        BigDecimal monthlyIncome = normalizeMonthlyIncome(command.monthlyIncome(), command.annualIncome());
        BigDecimal annualIncome = normalizeAnnualIncome(command.monthlyIncome(), command.annualIncome());
        Borrower borrower = resolveBorrowerForOnboarding(
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
        seedDocumentChecklist(savedApplication, actorUsername);
        webhookOutboxService.enqueueIfSubscribed(
                savedApplication.getLsp(),
                WebhookEventType.LOAN_CREATED,
                "LOAN_APPLICATION",
                savedApplication.getId().toString(),
                savedApplication.getId(),
                buildLoanCreatedPayload(savedApplication)
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
            throw new IllegalArgumentException("Loan application is already in status " + currentStatus.name() + ".");
        }
        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw new IllegalArgumentException(
                    "Cannot transition loan application from " + currentStatus.name() + " to " + targetStatus.name() + "."
            );
        }
        if (currentStatus == LoanApplicationStatus.AWAITING_APPROVAL
                && targetStatus == LoanApplicationStatus.APPROVED_PENDING_DISBURSAL) {
            validateKycCompletionBeforeApproval(applicationId);
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
            throw new IllegalArgumentException("Loan application is already in status " + currentStatus.name() + ".");
        }
        if (currentStatus == LoanApplicationStatus.APPROVED_PENDING_DISBURSAL
                || currentStatus == LoanApplicationStatus.DISBURSED
                || currentStatus == LoanApplicationStatus.UNDER_REPAYMENT
                || currentStatus == LoanApplicationStatus.INVALID
                || currentStatus == LoanApplicationStatus.CLOSED
                || currentStatus == LoanApplicationStatus.FORECLOSED) {
            throw new IllegalArgumentException("Loan applications that have entered servicing cannot be manually overridden.");
        }
        if (targetStatus == LoanApplicationStatus.APPROVED_PENDING_DISBURSAL
                || targetStatus == LoanApplicationStatus.DISBURSED
                || targetStatus == LoanApplicationStatus.UNDER_REPAYMENT
                || targetStatus == LoanApplicationStatus.INVALID
                || targetStatus == LoanApplicationStatus.CLOSED
                || targetStatus == LoanApplicationStatus.FORECLOSED) {
            throw new IllegalArgumentException("Use the standard approval flow instead of a manual status update.");
        }
        if (targetStatus != LoanApplicationStatus.INITIALIZED
                && targetStatus != LoanApplicationStatus.AWAITING_APPROVAL
                && targetStatus != LoanApplicationStatus.DISBURSEMENT_RETRY
                && targetStatus != LoanApplicationStatus.REJECTED) {
            throw new IllegalArgumentException("Manual status updates are not supported for " + targetStatus.name() + ".");
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
                LoanApplicationAuditAction.MANUAL_STATUS_OVERRIDE
        );
    }

    private String recordManualRuleEngineOverride(
            LoanApplication application,
            String actorUsername,
            String note
    ) {
        LoanAutoApprovalRuleEngine.Evaluation evaluation = loanAutoApprovalRuleEngine.evaluate(application);
        alertRuleEvaluationService.emitManualRuleEngineOverride(application, actorUsername, evaluation, note);
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
    public LoanApplicationDocumentChecklist updateDocumentChecklistItem(
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
        LoanApplication application = getApplication(applicationId);
        ensureDocumentChecklist(application);

        LoanApplicationDocumentChecklist checklistItem = loanApplicationDocumentChecklistRepository
                .findByLoanApplication_IdAndDocumentType(applicationId, documentType)
                .orElseThrow(() -> new IllegalArgumentException("Unknown document checklist item: " + documentType.name()));
        boolean wasComplete = allRequiredDocumentsUploaded(applicationId);

        checklistItem.update(
                status,
                note,
                normalizeActorUsername(actorUsername),
                fileName,
                fileReference,
                sourceReference,
                contentType,
                fileSizeBytes,
                fileChecksum,
                storageKey,
                lmsManagedContent
        );
        LoanApplicationDocumentChecklist savedChecklistItem = loanApplicationDocumentChecklistRepository.save(checklistItem);
        boolean isComplete = allRequiredDocumentsUploaded(applicationId);
        if (!wasComplete && isComplete) {
            webhookOutboxService.enqueueIfSubscribed(
                    application.getLsp(),
                    WebhookEventType.DOCUMENTS_UPLOADED,
                    "LOAN_APPLICATION",
                    application.getId().toString(),
                    application.getId(),
                    buildDocumentsUploadedPayload(application)
            );
        }
        return savedChecklistItem;
    }

    /**
     * Gap #11 + Follow-up #1 — re-evaluates the auto-approval rule set on
     * every doc upload / field update. On success: moves the application
     * forward through {@code INITIALIZED → AWAITING_APPROVAL →
     * APPROVED_PENDING_DISBURSAL}. On failure from {@code AWAITING_APPROVAL}:
     * transitions to {@code REJECTED} with a structured
     * {@code rejection_reason_json} listing the failed rule codes. Callers
     * outside the {@code INITIALIZED | AWAITING_APPROVAL} window short-circuit
     * to a no-op for backward compatibility (the disbursement service still
     * calls this method even after approval).
     */
    @Transactional
    public LoanApplication autoApproveIfEligibleForLsp(UUID applicationId, String actorUsername) {
        LoanApplication application = getApplication(applicationId);
        LoanApplicationStatus currentStatus = application.getStatus();
        if (currentStatus != LoanApplicationStatus.INITIALIZED
                && currentStatus != LoanApplicationStatus.AWAITING_APPROVAL) {
            return application;
        }

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
        if (!loanApplicationDocumentChecklistRepository.findByLoanApplication_IdOrderByCreatedAtAsc(application.getId()).isEmpty()) {
            return;
        }
        seedDocumentChecklist(application, "system");
    }

    public void validateRequiredDocumentsUploadedBeforeDisbursement(UUID applicationId) {
        LoanApplication application = getApplication(applicationId);
        ensureDocumentChecklist(application);

        List<LoanApplicationDocumentType> blockingDocumentTypes = loanApplicationDocumentChecklistRepository
                .findByLoanApplication_IdOrderByCreatedAtAsc(applicationId)
                .stream()
                .filter(item -> item.getDocumentType().isRequiredForDisbursement())
                .filter(item -> item.getStatus() != LoanApplicationDocumentChecklistStatus.SUBMITTED
                        && item.getStatus() != LoanApplicationDocumentChecklistStatus.NOT_REQUIRED)
                .map(LoanApplicationDocumentChecklist::getDocumentType)
                .toList();

        if (!blockingDocumentTypes.isEmpty()) {
            throw new DocumentUploadRequiredException(blockingDocumentTypes);
        }
    }

    public boolean hasAllRequiredLmsManagedDocuments(UUID applicationId, boolean requireForApprovalOnly) {
        LoanApplication application = getApplication(applicationId);
        ensureDocumentChecklist(application);
        return loanApplicationDocumentChecklistRepository.findByLoanApplication_IdOrderByCreatedAtAsc(applicationId)
                .stream()
                .filter(item -> requireForApprovalOnly
                        ? item.getDocumentType().isRequiredForApproval()
                        : item.getDocumentType().isRequiredForDisbursement())
                .allMatch(item -> (item.getStatus() == LoanApplicationDocumentChecklistStatus.SUBMITTED
                        || item.getStatus() == LoanApplicationDocumentChecklistStatus.NOT_REQUIRED)
                        && (item.getStatus() == LoanApplicationDocumentChecklistStatus.NOT_REQUIRED
                                || item.isLmsManagedContent()));
    }

    private boolean allRequiredDocumentsUploaded(UUID applicationId) {
        return loanApplicationDocumentChecklistRepository.findByLoanApplication_IdOrderByCreatedAtAsc(applicationId)
                .stream()
                .filter(item -> item.getDocumentType().isRequiredForDisbursement())
                .allMatch(item -> item.getStatus() == LoanApplicationDocumentChecklistStatus.SUBMITTED
                        || item.getStatus() == LoanApplicationDocumentChecklistStatus.NOT_REQUIRED);
    }

    private Map<String, Object> buildDocumentsUploadedPayload(LoanApplication application) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanApplicationId", application.getId());
        payload.put("externalLoanId", application.getExternalLoanId());
        payload.put("allRequiredDocumentsUploaded", true);
        payload.put(
                "documentTypes",
                loanApplicationDocumentChecklistRepository.findByLoanApplication_IdOrderByCreatedAtAsc(application.getId())
                        .stream()
                        .filter(item -> item.getDocumentType().isRequiredForDisbursement())
                        .filter(item -> item.getStatus() == LoanApplicationDocumentChecklistStatus.SUBMITTED
                                || item.getStatus() == LoanApplicationDocumentChecklistStatus.NOT_REQUIRED)
                        .map(item -> item.getDocumentType().name())
                        .toList()
        );
        return payload;
    }

    public LoanApplication updateApplicationStatus(
            LoanApplication application,
            LoanApplicationStatus targetStatus,
            String actorUsername,
            String note,
            LoanApplicationStatusReasonCode reasonCode,
            LoanApplicationAuditAction auditAction
    ) {
        return updateApplicationStatus(application, targetStatus, actorUsername, note, reasonCode, auditAction, null);
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
        LoanApplicationStatus currentStatus = application.getStatus();
        if (currentStatus == targetStatus) {
            return application;
        }

        application.transitionTo(targetStatus);
        LoanApplication savedApplication = loanApplicationRepository.save(application);
        String resolvedNote = normalizeOptional(note) == null
                ? defaultTransitionNote(currentStatus, targetStatus)
                : normalizeOptional(note);
        loanApplicationStatusTransitionRepository.save(new LoanApplicationStatusTransition(
                savedApplication,
                currentStatus,
                targetStatus,
                normalizeActorUsername(actorUsername),
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
                buildLoanStatusChangedPayload(savedApplication, currentStatus, targetStatus, reasonCode)
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
        generateRepaymentSchedule(loanAccount);
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
                normalizeActorUsername(actorUsername),
                fromStatus,
                toStatus,
                note,
                reasonCode,
                CorrelationIdHolder.get()
        ));
    }

    public Map<String, Object> buildDisbursementPayload(LoanApplication application, LoanAccount loanAccount) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanApplicationId", application.getId());
        payload.put("loanAccountId", loanAccount.getId());
        payload.put("accountNumber", loanAccount.getAccountNumber());
        payload.put("loanAccountStatus", loanAccount.getStatus().name());
        payload.put("principalAmount", loanAccount.getPrincipalAmount());
        return payload;
    }

    public Map<String, Object> buildRepaymentPayload(
            LoanApplication application,
            LoanAccount loanAccount,
            LoanPaymentTransaction paymentTransaction
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanApplicationId", application.getId());
        payload.put("loanAccountId", loanAccount.getId());
        payload.put("paymentTransactionId", paymentTransaction.getId());
        payload.put("reference", paymentTransaction.getReference());
        payload.put("amount", paymentTransaction.getAmount());
        payload.put("allocatedAmount", paymentTransaction.getAllocatedAmount());
        payload.put("unallocatedAmount", paymentTransaction.getUnallocatedAmount());
        payload.put("paymentStatus", paymentTransaction.getStatus().name());
        payload.put("paymentDate", paymentTransaction.getPaymentDate());
        return payload;
    }

    public Map<String, Object> buildLoanFullyRepaidPayload(
            LoanApplication application,
            LoanAccount loanAccount
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanApplicationId", application.getId());
        payload.put("loanAccountId", loanAccount.getId());
        payload.put("accountNumber", loanAccount.getAccountNumber());
        payload.put("closureReason", loanAccount.getClosureReason().name());
        payload.put("closedAt", loanAccount.getClosedAt());
        return payload;
    }

    public Map<String, Object> buildForeclosurePayload(
            LoanApplication application,
            LoanAccount loanAccount,
            LoanForeclosureQuote quote
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanApplicationId", application.getId());
        payload.put("loanAccountId", loanAccount.getId());
        payload.put("accountNumber", loanAccount.getAccountNumber());
        payload.put("foreclosureQuoteId", quote.getId());
        payload.put("quoteVersion", quote.getVersion());
        payload.put("effectiveDate", quote.getEffectiveDate());
        payload.put("settlementAmount", quote.getSettlementAmount());
        payload.put("closureReason", LoanAccountClosureReason.FORECLOSURE.name());
        payload.put("closedAt", loanAccount.getClosedAt());
        payload.put("applicationStatus", application.getStatus().name());
        return payload;
    }

    public Map<String, Object> buildForeclosureQuotePayload(
            LoanApplication application,
            LoanAccount loanAccount,
            LoanForeclosureQuote quote
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanApplicationId", application.getId());
        payload.put("loanAccountId", loanAccount.getId());
        payload.put("accountNumber", loanAccount.getAccountNumber());
        payload.put("foreclosureQuoteId", quote.getId());
        payload.put("quoteVersion", quote.getVersion());
        payload.put("effectiveDate", quote.getEffectiveDate());
        payload.put("settlementAmount", quote.getSettlementAmount());
        return payload;
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
            throw new IllegalArgumentException("Loan application is already marked invalid.");
        }
        if (currentStatus == LoanApplicationStatus.DISBURSED
                || currentStatus == LoanApplicationStatus.UNDER_REPAYMENT
                || currentStatus == LoanApplicationStatus.CLOSED
                || currentStatus == LoanApplicationStatus.FORECLOSED) {
            throw new IllegalArgumentException("Loan applications that have entered servicing cannot be marked invalid.");
        }

        if (loanAccount != null) {
            if (loanAccount.getStatus() == LoanAccountStatus.DISBURSED
                    || loanAccount.getStatus() == LoanAccountStatus.CLOSED
                    || loanAccount.getStatus() == LoanAccountStatus.FORECLOSED) {
                throw new IllegalArgumentException("Loan applications that have entered servicing cannot be marked invalid.");
            }
            if (loanAccount.getStatus() == LoanAccountStatus.INVALID) {
                throw new IllegalArgumentException("Loan application is already marked invalid.");
            }
        }

        String normalizedActor = normalizeActorUsername(actorUsername);
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
                buildLoanStatusChangedPayload(
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
                .orElseThrow(() -> new IllegalArgumentException("Unknown loan application id: " + applicationId));
    }

    private Borrower resolveBorrowerForOnboarding(
            com.bhawana.lms.domain.Lsp lsp,
            LoanApplicationOnboardingCommand command,
            BigDecimal monthlyIncome,
            BigDecimal annualIncome,
            String actorUsername
    ) {
        String normalizedPan = normalizePan(command.panNumber());
        String normalizedMobile = normalizeMobile(command.mobileNumber());
        String normalizedAadhar = normalizeAadhar(command.aadharNumber());
        String normalizedFullName = normalizeFullName(command.fullName());

        Borrower borrowerByPan = borrowerRepository.findByPan(normalizedPan).orElse(null);
        Borrower borrowerByMobile = borrowerRepository.findTop10ByMobileOrderByUpdatedAtDesc(normalizedMobile)
                .stream()
                .findFirst()
                .orElse(null);

        if (borrowerByPan != null) {
            if (borrowerByMobile != null && !borrowerByMobile.getId().equals(borrowerByPan.getId())) {
                raiseBorrowerIdentityConflict(
                        lsp,
                        borrowerByMobile,
                        command,
                        actorUsername,
                        "Incoming PAN matches an existing borrower, but the submitted mobile number is already associated with a different borrower."
                );
            }
            validateImmutableBorrowerIdentity(lsp, borrowerByPan, normalizedAadhar, command, actorUsername);
            raiseActiveLoanDuplicateIfPresent(lsp, borrowerByPan, command, actorUsername);
            borrowerByPan.mergeLatestProfile(
                    normalizedFullName,
                    normalizedMobile,
                    normalizeEmail(command.emailAddress()),
                    command.dob(),
                    command.gender(),
                    command.maritalStatus(),
                    command.fatherName(),
                    normalizedAadhar,
                    command.addressCity(),
                    command.addressState(),
                    command.addressLine1(),
                    command.addressLine2(),
                    command.addressZipcode(),
                    command.spouseName(),
                    command.employmentStatus(),
                    command.organizationName(),
                    command.empId(),
                    command.employmentCity(),
                    command.employmentState(),
                    command.employmentZip(),
                    monthlyIncome,
                    annualIncome,
                    command.bankAccountNumber(),
                    command.bankName(),
                    command.ifscCode(),
                    command.accountHolderName(),
                    command.referencePersonName(),
                    command.referencePersonNumber()
            );
            borrowerByPan.grantVisibilityTo(lsp);
            return borrowerRepository.save(borrowerByPan);
        }

        if (borrowerByMobile != null) {
            raiseBorrowerIdentityConflict(
                    lsp,
                    borrowerByMobile,
                    command,
                    actorUsername,
                    "Incoming mobile number already belongs to an existing borrower with a different PAN."
            );
        }

        Borrower borrower = new Borrower(
                normalizedFullName,
                normalizedPan,
                normalizedMobile,
                normalizeEmail(command.emailAddress()),
                command.dob(),
                command.gender(),
                command.maritalStatus(),
                command.fatherName(),
                normalizedAadhar,
                command.addressCity(),
                command.addressState(),
                command.addressLine1(),
                command.addressLine2(),
                command.addressZipcode(),
                command.spouseName(),
                command.employmentStatus(),
                command.organizationName(),
                command.empId(),
                command.employmentCity(),
                command.employmentState(),
                command.employmentZip(),
                monthlyIncome,
                annualIncome,
                command.bankAccountNumber(),
                command.bankName(),
                command.ifscCode(),
                command.accountHolderName(),
                command.referencePersonName(),
                command.referencePersonNumber()
        );
        borrower.grantVisibilityTo(lsp);
        return borrowerRepository.save(borrower);
    }

    private void validateImmutableBorrowerIdentity(
            com.bhawana.lms.domain.Lsp lsp,
            Borrower borrower,
            String normalizedAadhar,
            LoanApplicationOnboardingCommand command,
            String actorUsername
    ) {
        String currentAadhar = normalizeAadhar(borrower.getAadharNumber());
        if (currentAadhar != null && normalizedAadhar != null && !currentAadhar.equals(normalizedAadhar)) {
            raiseBorrowerIdentityConflict(
                    lsp,
                    borrower,
                    command,
                    actorUsername,
                    "Incoming Aadhaar does not match the existing borrower identity for the submitted PAN."
            );
        }
    }

    private void raiseBorrowerIdentityConflict(
            com.bhawana.lms.domain.Lsp lsp,
            Borrower existingBorrower,
            LoanApplicationOnboardingCommand command,
            String actorUsername,
            String reason
    ) {
        opsAlertService.createAlert(
                OpsAlertType.BORROWER_IDENTITY_CONFLICT,
                OpsAlertSeverity.HIGH,
                "Borrower identity mismatch detected",
                reason + " Internal ops review is required before this borrower can be onboarded again.",
                "BORROWER",
                existingBorrower == null ? null : existingBorrower.getId(),
                CorrelationIdHolder.get(),
                serializeBorrowerConflictContext(lsp, existingBorrower, command, actorUsername, reason)
        );
        throw new ApiConflictException(
                "BORROWER_IDENTITY_CONFLICT",
                "Borrower identity conflict detected. Internal ops has been alerted."
        );
    }

    private void raiseActiveLoanDuplicateIfPresent(
            com.bhawana.lms.domain.Lsp lsp,
            Borrower existingBorrower,
            LoanApplicationOnboardingCommand command,
            String actorUsername
    ) {
        if (existingBorrower == null) {
            return;
        }
        List<com.bhawana.lms.domain.LoanAccount> openLoans =
                borrowerActiveLoanChecker.findOpenLoansAcrossAllLsps(existingBorrower.getId());
        if (openLoans.isEmpty()) {
            return;
        }

        String reason = "Borrower already has " + openLoans.size() + " open loan(s) across LSPs. "
                + "Concurrent loan onboarding is blocked.";
        opsAlertService.createAlert(
                OpsAlertType.BORROWER_ACTIVE_LOAN_DUPLICATE,
                OpsAlertSeverity.HIGH,
                "Borrower already has an open loan",
                reason + " Internal ops review is required before this borrower can be onboarded for a new loan.",
                "BORROWER",
                existingBorrower.getId(),
                CorrelationIdHolder.get(),
                serializeActiveLoanDuplicateContext(lsp, existingBorrower, openLoans, command, actorUsername)
        );
        throw new ApiConflictException(
                "BORROWER_HAS_ACTIVE_LOAN",
                "Borrower already has an open loan. Onboarding blocked."
        );
    }

    private String serializeActiveLoanDuplicateContext(
            com.bhawana.lms.domain.Lsp lsp,
            Borrower existingBorrower,
            List<com.bhawana.lms.domain.LoanAccount> openLoans,
            LoanApplicationOnboardingCommand command,
            String actorUsername
    ) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("actorUsername", actorUsername);
        payload.put("incomingLspId", lsp == null ? null : lsp.getId());
        payload.put("incomingLspCode", lsp == null ? null : lsp.getCode());
        payload.put("incomingPan", normalizePan(command.panNumber()));
        payload.put("incomingMobile", normalizeMobile(command.mobileNumber()));
        payload.put("borrowerId", existingBorrower.getId());
        payload.put("borrowerPan", existingBorrower.getPan());
        java.util.List<java.util.Map<String, Object>> loanEntries = new java.util.ArrayList<>(openLoans.size());
        for (com.bhawana.lms.domain.LoanAccount loan : openLoans) {
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("loanAccountId", loan.getId());
            entry.put("applicationId", loan.getLoanApplication() == null ? null : loan.getLoanApplication().getId());
            entry.put("lspId", loan.getLsp() == null ? null : loan.getLsp().getId());
            entry.put("lspCode", loan.getLsp() == null ? null : loan.getLsp().getCode());
            entry.put("status", loan.getStatus() == null ? null : loan.getStatus().name());
            loanEntries.add(entry);
        }
        payload.put("openLoans", loanEntries);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return null;
        }
    }

    private LoanApplication getApplicationForLsp(UUID lspId, UUID applicationId) {
        LoanApplication application = getApplication(applicationId);
        if (!application.getLsp().getId().equals(lspId)) {
            throw new IllegalArgumentException("Unknown loan application id: " + applicationId);
        }
        return application;
    }

    private void seedDocumentChecklist(LoanApplication application, String actorUsername) {
        List<LoanApplicationDocumentChecklist> checklistItems = List.of(
                buildChecklistItem(application, LoanApplicationDocumentType.PAN_CARD, actorUsername),
                buildChecklistItem(application, LoanApplicationDocumentType.AADHAAR_FILE, actorUsername),
                buildChecklistItem(application, LoanApplicationDocumentType.ADDRESS_PROOF, actorUsername),
                buildChecklistItem(application, LoanApplicationDocumentType.INCOME_PROOF, actorUsername),
                buildChecklistItem(application, LoanApplicationDocumentType.BANK_STATEMENT, actorUsername),
                buildChecklistItem(application, LoanApplicationDocumentType.SELFIE_PHOTOGRAPH, actorUsername),
                buildChecklistItem(application, LoanApplicationDocumentType.KFS, actorUsername),
                buildChecklistItem(application, LoanApplicationDocumentType.LOAN_AGREEMENT, actorUsername)
        );
        loanApplicationDocumentChecklistRepository.saveAll(checklistItems);
    }

    private LoanApplicationDocumentChecklist buildChecklistItem(
            LoanApplication application,
            LoanApplicationDocumentType documentType,
            String actorUsername
    ) {
        return new LoanApplicationDocumentChecklist(
                application,
                documentType,
                documentType.isRequiredByDefault(),
                documentType.isRequiredByDefault()
                        ? LoanApplicationDocumentChecklistStatus.PENDING
                        : LoanApplicationDocumentChecklistStatus.NOT_REQUIRED,
                documentType.isRequiredByDefault() ? "Awaiting " + documentType.getDisplayName() : "Optional placeholder",
                actorUsername
        );
    }

    private void validateKycCompletionBeforeApproval(UUID applicationId) {
        LoanApplication application = getApplication(applicationId);
        ensureDocumentChecklist(application);

        List<LoanApplicationDocumentType> blockingDocumentTypes = loanApplicationDocumentChecklistRepository
                .findByLoanApplication_IdOrderByCreatedAtAsc(applicationId)
                .stream()
                .filter(item -> item.getDocumentType().isRequiredForApproval())
                .filter(item -> item.getStatus() != LoanApplicationDocumentChecklistStatus.SUBMITTED
                        && item.getStatus() != LoanApplicationDocumentChecklistStatus.NOT_REQUIRED)
                .map(LoanApplicationDocumentChecklist::getDocumentType)
                .toList();

        if (!blockingDocumentTypes.isEmpty()) {
            throw new KycCompletionRequiredException(blockingDocumentTypes);
        }
    }

    private LoanProduct resolveLoanProduct(LoanApplicationOnboardingCommand command) {
        if (command.productId() != null) {
            return loanProductRepository.findById(command.productId())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown loan product id: " + command.productId()));
        }
        String loanProductCode = normalizeOptional(command.loanProduct());
        if (loanProductCode == null) {
            throw new IllegalArgumentException("Loan product is required.");
        }
        return loanProductRepository.findByCodeIgnoreCase(loanProductCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown loan product code: " + loanProductCode));
    }

    private void generateRepaymentSchedule(LoanAccount loanAccount) {
        if (!loanRepaymentScheduleInstallmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(loanAccount.getId())
                .isEmpty()) {
            return;
        }

        BigDecimal principal = scaleCurrency(loanAccount.getPrincipalAmount());
        int tenureMonths = loanAccount.getTenureMonths();
        BigDecimal annualRate = loanAccount.getLoanProduct().getInterestRate();
        BigDecimal monthlyRate = annualRate.divide(BigDecimal.valueOf(1200), 10, RoundingMode.HALF_UP);
        BigDecimal emiAmount = calculateMonthlyEmi(principal, monthlyRate, tenureMonths);
        LocalDate firstDueDate = loanAccount.getApprovedAt().atZone(ZoneOffset.UTC).toLocalDate().plusMonths(1);

        BigDecimal remainingPrincipal = principal;
        List<LoanRepaymentScheduleInstallment> installments = new java.util.ArrayList<>();
        for (int installmentNumber = 1; installmentNumber <= tenureMonths; installmentNumber++) {
            BigDecimal openingPrincipal = scaleCurrency(remainingPrincipal);
            BigDecimal interestDue = scaleCurrency(openingPrincipal.multiply(monthlyRate));
            BigDecimal installmentAmount = emiAmount;
            BigDecimal principalDue = scaleCurrency(installmentAmount.subtract(interestDue));
            if (installmentNumber == tenureMonths) {
                principalDue = openingPrincipal;
                installmentAmount = scaleCurrency(principalDue.add(interestDue));
            }
            BigDecimal closingPrincipal = scaleCurrency(openingPrincipal.subtract(principalDue));
            if (closingPrincipal.compareTo(BigDecimal.ZERO) < 0) {
                closingPrincipal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }
            installments.add(new LoanRepaymentScheduleInstallment(
                    loanAccount,
                    installmentNumber,
                    firstDueDate.plusMonths(installmentNumber - 1L),
                    openingPrincipal,
                    principalDue,
                    interestDue,
                    installmentAmount,
                    closingPrincipal
            ));
            remainingPrincipal = closingPrincipal;
        }

        loanRepaymentScheduleInstallmentRepository.saveAll(installments);
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
        payload.put("borrowerPan", application.getBorrower().getPan());
        payload.put("borrowerFullName", application.getBorrower().getFullName());
        payload.put("borrowerMobile", application.getBorrower().getMobile());
        payload.put("borrowerEmail", application.getBorrower().getEmail());
        payload.put("borrowerDateOfBirth", application.getBorrower().getDateOfBirth());
        payload.put("borrowerGender", application.getBorrower().getGender());
        payload.put("borrowerMaritalStatus", application.getBorrower().getMaritalStatus());
        payload.put("borrowerFatherName", application.getBorrower().getFatherName());
        payload.put("borrowerAadharNumber", application.getBorrower().getAadharNumber());
        payload.put("addressLine1", application.getBorrower().getAddressLine1());
        payload.put("addressLine2", application.getBorrower().getAddressLine2());
        payload.put("borrowerCity", application.getBorrower().getCity());
        payload.put("borrowerState", application.getBorrower().getState());
        payload.put("addressZipCode", application.getBorrower().getAddressZipCode());
        payload.put("spouseName", application.getBorrower().getSpouseName());
        payload.put("borrowerEmploymentType", application.getBorrower().getEmploymentType());
        payload.put("organizationName", application.getBorrower().getOrganizationName());
        payload.put("employeeId", application.getBorrower().getEmployeeId());
        payload.put("employmentCity", application.getBorrower().getEmploymentCity());
        payload.put("employmentState", application.getBorrower().getEmploymentState());
        payload.put("employmentZip", application.getBorrower().getEmploymentZip());
        payload.put("borrowerMonthlyIncome", application.getBorrower().getMonthlyIncome());
        payload.put("borrowerAnnualIncome", application.getBorrower().getAnnualIncome());
        payload.put("bankAccountNumber", application.getBorrower().getBankAccountNumber());
        payload.put("bankName", application.getBorrower().getBankName());
        payload.put("ifscCode", application.getBorrower().getIfscCode());
        payload.put("accountHolderName", application.getBorrower().getAccountHolderName());
        payload.put("referencePersonName", application.getBorrower().getReferencePersonName());
        payload.put("referencePersonNumber", application.getBorrower().getReferencePersonNumber());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize loan application intake payload.", exception);
        }
    }

    private static Map<String, Object> buildLoanCreatedPayload(LoanApplication application) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanApplicationId", application.getId());
        payload.put("externalLoanId", application.getExternalLoanId());
        payload.put("status", application.getStatus().name());
        payload.put("borrowerId", application.getBorrower().getId());
        payload.put("requestedAmount", application.getRequestedAmount());
        payload.put("tenureMonths", application.getRequestedTenureMonths());
        return payload;
    }

    private static Map<String, Object> buildLoanStatusChangedPayload(
            LoanApplication application,
            LoanApplicationStatus fromStatus,
            LoanApplicationStatus toStatus,
            LoanApplicationStatusReasonCode reasonCode
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanApplicationId", application.getId());
        payload.put("externalLoanId", application.getExternalLoanId());
        payload.put("fromStatus", fromStatus.name());
        payload.put("toStatus", toStatus.name());
        payload.put("reasonCode", reasonCode == null ? null : reasonCode.name());
        payload.put("invalidReasonCode", application.getInvalidReasonCode() == null ? null : application.getInvalidReasonCode().name());
        payload.put("invalidReasonText", application.getInvalidReasonText());
        payload.put("invalidatedByUsername", application.getInvalidatedByUsername());
        payload.put("invalidatedAt", application.getInvalidatedAt());
        return payload;
    }

    private static String normalizePan(String pan) {
        return pan.trim().toUpperCase();
    }

    private static String normalizeMobile(String mobile) {
        return mobile.trim();
    }

    private static String normalizeFullName(String fullName) {
        return fullName.trim();
    }

    private static String normalizeAadhar(String aadharNumber) {
        String normalized = normalizeOptional(aadharNumber);
        return normalized == null ? null : normalized.replace(" ", "");
    }

    private static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String normalized = email.trim();
        return normalized.isBlank() ? null : normalized.toLowerCase();
    }

    private static String normalizeSourceChannel(String sourceChannel) {
        return sourceChannel.trim().toUpperCase();
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static String normalizeActorUsername(String actorUsername) {
        if (actorUsername == null) {
            return "system";
        }
        String normalized = actorUsername.trim();
        return normalized.isBlank() ? "system" : normalized;
    }

    private static String requireNote(String note) {
        String normalized = normalizeOptional(note);
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
            throw new IllegalArgumentException(message);
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
        String normalized = normalizeOptional(invalidReasonText);
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

    private String serializeBorrowerConflictContext(
            com.bhawana.lms.domain.Lsp lsp,
            Borrower existingBorrower,
            LoanApplicationOnboardingCommand command,
            String actorUsername,
            String reason
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason);
        payload.put("actorUsername", normalizeActorUsername(actorUsername));
        payload.put("lspId", lsp.getId());
        payload.put("lspCode", lsp.getCode());
        payload.put("incomingPan", normalizePan(command.panNumber()));
        payload.put("incomingMobile", normalizeMobile(command.mobileNumber()));
        payload.put("incomingAadhar", normalizeAadhar(command.aadharNumber()));
        payload.put("incomingFullName", normalizeFullName(command.fullName()));
        if (existingBorrower != null) {
            payload.put("existingBorrowerId", existingBorrower.getId());
            payload.put("existingPan", existingBorrower.getPan());
            payload.put("existingMobile", existingBorrower.getMobile());
            payload.put("existingAadhar", existingBorrower.getAadharNumber());
            payload.put("existingFullName", existingBorrower.getFullName());
            payload.put("existingVisibleLspIds", existingBorrower.getVisibleLspIds());
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize borrower identity conflict alert context.", exception);
        }
    }

    private static void validateInterestRate(BigDecimal requestedInterestRate, BigDecimal configuredInterestRate) {
        if (requestedInterestRate == null) {
            return;
        }
        BigDecimal normalizedRequestedRate = requestedInterestRate.setScale(2, RoundingMode.HALF_UP);
        BigDecimal normalizedConfiguredRate = configuredInterestRate.setScale(2, RoundingMode.HALF_UP);
        if (normalizedRequestedRate.compareTo(normalizedConfiguredRate) != 0) {
            throw new IllegalArgumentException("Requested interest rate does not match the configured product interest rate.");
        }
    }

    private static BigDecimal normalizeMonthlyIncome(BigDecimal monthlyIncome, BigDecimal annualIncome) {
        if (monthlyIncome != null) {
            return scaleCurrency(monthlyIncome);
        }
        if (annualIncome == null) {
            return null;
        }
        return scaleCurrency(annualIncome.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP));
    }

    private static BigDecimal normalizeAnnualIncome(BigDecimal monthlyIncome, BigDecimal annualIncome) {
        if (annualIncome != null) {
            return scaleCurrency(annualIncome);
        }
        if (monthlyIncome == null) {
            return null;
        }
        return scaleCurrency(monthlyIncome.multiply(BigDecimal.valueOf(12)));
    }

    private static BigDecimal requireCurrency(BigDecimal value, String fieldName) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than zero.");
        }
        return value;
    }

    private static int requireTenure(Integer loanTenure) {
        if (loanTenure == null || loanTenure < 1) {
            throw new IllegalArgumentException("Loan tenure must be at least 1 month.");
        }
        return loanTenure;
    }

    private static String requireField(String value, String fieldName) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return normalized;
    }

    private static BigDecimal calculateMonthlyEmi(BigDecimal principal, BigDecimal monthlyRate, int tenureMonths) {
        if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            return scaleCurrency(principal.divide(BigDecimal.valueOf(tenureMonths), 2, RoundingMode.HALF_UP));
        }
        BigDecimal rateDecimal = monthlyRate;
        BigDecimal onePlusRatePower = BigDecimal
                .valueOf(Math.pow(BigDecimal.ONE.add(rateDecimal).doubleValue(), tenureMonths));
        BigDecimal numerator = principal.multiply(rateDecimal).multiply(onePlusRatePower, MathContext.DECIMAL64);
        BigDecimal denominator = onePlusRatePower.subtract(BigDecimal.ONE);
        return scaleCurrency(numerator.divide(denominator, 2, RoundingMode.HALF_UP));
    }

    private static String generateAccountNumber(LoanApplication application) {
        String compactId = application.getId().toString().replace("-", "").toUpperCase();
        return "LMS-LN-" + compactId.substring(0, 12);
    }

    private static BigDecimal scaleCurrency(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
