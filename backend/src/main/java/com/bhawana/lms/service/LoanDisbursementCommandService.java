package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.money.Money;
import com.bhawana.lms.common.util.Strings;
import com.bhawana.lms.common.web.ApiConflictException;
import com.bhawana.lms.common.web.BusinessRuleViolationException;
import com.bhawana.lms.common.web.ResourceNotFoundException;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationAuditAction;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusReasonCode;
import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import com.bhawana.lms.domain.MockDisbursementOutcome;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanDisbursementCommandService {

    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;
    private final LoanDisbursementAdapter loanDisbursementAdapter;
    private final WebhookOutboxService webhookOutboxService;
    private final LoanApplicationQueryService loanApplicationQueryService;
    private final LoanApplicationLifecycleService loanApplicationLifecycleService;
    private final DisbursementOutcomeAuditService disbursementOutcomeAuditService;
    private final ObjectMapper objectMapper;

    public LoanDisbursementCommandService(
            LoanApplicationRepository loanApplicationRepository,
            LoanAccountRepository loanAccountRepository,
            LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository,
            LoanDisbursementAdapter loanDisbursementAdapter,
            WebhookOutboxService webhookOutboxService,
            LoanApplicationQueryService loanApplicationQueryService,
            LoanApplicationLifecycleService loanApplicationLifecycleService,
            DisbursementOutcomeAuditService disbursementOutcomeAuditService,
            ObjectMapper objectMapper
    ) {
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanDisbursementRequestLogRepository = loanDisbursementRequestLogRepository;
        this.loanDisbursementAdapter = loanDisbursementAdapter;
        this.webhookOutboxService = webhookOutboxService;
        this.loanApplicationQueryService = loanApplicationQueryService;
        this.loanApplicationLifecycleService = loanApplicationLifecycleService;
        this.disbursementOutcomeAuditService = disbursementOutcomeAuditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public LoanApplication initiateDisbursement(UUID applicationId, String actorUsername) {
        lockApplicationForDisbursement(applicationId);
        LoanApplication application = loanApplicationQueryService.getApplication(applicationId);
        if (isDisbursementAlreadyComplete(application.getStatus())) {
            return application;
        }
        LoanAccount loanAccount = resolveLoanAccountForDisbursement(application);
        if (loanAccount.getStatus() == LoanAccountStatus.DISBURSEMENT_REQUESTED) {
            return application;
        }
        return initiateDisbursement(applicationId, actorUsername, Money.scale(loanAccount.getPrincipalAmount()));
    }

    @Transactional
    public LoanApplication initiateDisbursement(
            UUID applicationId,
            String actorUsername,
            BigDecimal disbursementAmount
    ) {
        lockApplicationForDisbursement(applicationId);
        LoanApplication application = loanApplicationQueryService.getApplication(applicationId);
        if (isDisbursementAlreadyComplete(application.getStatus())) {
            return application;
        }
        if (application.getStatus() != LoanApplicationStatus.APPROVED_PENDING_DISBURSAL
                && application.getStatus() != LoanApplicationStatus.DISBURSEMENT_RETRY) {
            throw new BusinessRuleViolationException(
                    "DISBURSEMENT_NOT_ALLOWED",
                    "Disbursement can only be requested for applications pending disbursal or disbursement retry.",
                    Map.of()
            );
        }
        loanApplicationLifecycleService.validateRequiredDocumentsUploadedBeforeDisbursement(applicationId);

        LoanAccount loanAccount = resolveLoanAccountForDisbursement(application);
        if (loanAccount.getStatus() == LoanAccountStatus.DISBURSEMENT_REQUESTED) {
            return application;
        }
        if (loanAccount.getStatus() != LoanAccountStatus.PENDING_DISBURSEMENT
                && loanAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_FAILED
                && loanAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION) {
            throw new ApiConflictException(
                    "DISBURSEMENT_ALREADY_REQUESTED",
                    "Disbursement has already been requested for this loan account."
            );
        }

        BigDecimal scaledDisbursementAmount = Money.scale(Money.requirePositive(disbursementAmount, "Disbursement amount"));
        LoanDisbursementAdapter.DisbursementCommand command = new LoanDisbursementAdapter.DisbursementCommand(
                loanAccount.getAccountNumber(),
                scaledDisbursementAmount,
                application.getBorrower().getFullName(),
                application.getExternalLoanId(),
                application.getLsp().getCode()
        );
        LoanDisbursementAdapter.DisbursementResult result = loanDisbursementAdapter.requestDisbursement(command);

        loanAccount.markDisbursementRequested();
        loanAccountRepository.save(loanAccount);
        loanDisbursementRequestLogRepository.save(new LoanDisbursementRequestLog(
                loanAccount,
                Strings.normalizeActor(actorUsername),
                scaledDisbursementAmount,
                result.providerName(),
                result.providerRequestId(),
                result.providerStatus(),
                serializeDisbursementRequest(command),
                result.responsePayloadJson(),
                CorrelationIdHolder.get()
        ));
        webhookOutboxService.enqueueIfSubscribed(
                application.getLsp(),
                WebhookEventType.DISBURSEMENT_REQUESTED,
                "LOAN_ACCOUNT",
                loanAccount.getId().toString(),
                application.getId(),
                LoanWebhookPayloads.disbursement(application, loanAccount)
        );
        return application;
    }

    @Transactional
    public LoanApplication resolveMockDisbursementOutcome(
            UUID applicationId,
            String actorUsername,
            MockDisbursementOutcome outcome
    ) {
        return resolveMockDisbursementOutcome(
                applicationId,
                actorUsername,
                null,
                CorrelationIdHolder.get(),
                outcome
        );
    }

    @Transactional
    public LoanApplication resolveMockDisbursementOutcome(
            UUID applicationId,
            String actorUsername,
            String actorIp,
            String correlationId,
            MockDisbursementOutcome outcome
    ) {
        if (outcome == null) {
            throw new IllegalArgumentException("Disbursement outcome is required.");
        }

        LoanApplication application = loanApplicationQueryService.getApplication(applicationId);
        LoanAccount loanAccount = getRequiredLoanAccount(applicationId);
        if (loanAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_REQUESTED) {
            throw new BusinessRuleViolationException(
                    "DISBURSEMENT_NOT_REQUESTED",
                    "Mock disbursement outcome can only be applied after a request is raised.",
                    Map.of()
            );
        }

        LoanDisbursementRequestLog latestRequest = loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(loanAccount.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Disbursement request log is not available for application id: " + applicationId
                ));

        LoanAccountStatus resolvedAccountStatus = switch (outcome) {
            case DISBURSED -> LoanAccountStatus.DISBURSED;
            case FAILED -> LoanAccountStatus.DISBURSEMENT_FAILED;
            case PENDING_RECONCILIATION -> LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION;
        };

        latestRequest.updateOutcome(
                providerStatusFor(outcome),
                serializeDisbursementOutcomeResponse(latestRequest, outcome, actorUsername)
        );
        loanDisbursementRequestLogRepository.save(latestRequest);

        loanAccount.updateDisbursementStatus(resolvedAccountStatus, Instant.now());
        loanAccountRepository.save(loanAccount);
        if (outcome == MockDisbursementOutcome.DISBURSED) {
            loanApplicationLifecycleService.updateApplicationStatus(
                    application,
                    LoanApplicationStatus.DISBURSED,
                    actorUsername,
                    "Loan disbursement completed successfully.",
                    null,
                    LoanApplicationAuditAction.STATUS_TRANSITION
            );
        } else {
            loanApplicationLifecycleService.updateApplicationStatus(
                    application,
                    LoanApplicationStatus.DISBURSEMENT_RETRY,
                    actorUsername,
                    "Loan disbursement requires retry after failed/pending-reconciliation outcome.",
                    LoanApplicationStatusReasonCode.POLICY_EXCEPTION,
                    LoanApplicationAuditAction.STATUS_TRANSITION
            );
        }
        WebhookEventType eventType = switch (outcome) {
            case DISBURSED -> WebhookEventType.DISBURSEMENT_COMPLETED;
            case FAILED -> WebhookEventType.DISBURSEMENT_FAILED;
            case PENDING_RECONCILIATION -> null;
        };
        if (eventType != null) {
            webhookOutboxService.enqueueIfSubscribed(
                    application.getLsp(),
                    eventType,
                    "LOAN_ACCOUNT",
                    loanAccount.getId().toString(),
                    application.getId(),
                    LoanWebhookPayloads.disbursement(application, loanAccount)
            );
        }
        disbursementOutcomeAuditService.recordMockOutcomeApplied(
                application,
                loanAccount,
                actorUsername,
                actorIp,
                correlationId,
                outcome,
                latestRequest.getProviderRequestId()
        );
        return application;
    }

    private LoanAccount getRequiredLoanAccount(UUID applicationId) {
        return loanAccountRepository.findDetailedByLoanApplication_Id(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan account is not available for application id: " + applicationId
                ));
    }

    private LoanAccount resolveLoanAccountForDisbursement(LoanApplication application) {
        return loanAccountRepository.findDetailedByLoanApplication_Id(application.getId())
                .orElseGet(() -> loanApplicationLifecycleService.ensureLoanAccountForApprovedApplication(application));
    }

    private void lockApplicationForDisbursement(UUID applicationId) {
        loanApplicationRepository.findByIdForUpdate(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown loan application id: " + applicationId));
    }

    private static boolean isDisbursementAlreadyComplete(LoanApplicationStatus status) {
        return status == LoanApplicationStatus.DISBURSED
                || status == LoanApplicationStatus.UNDER_REPAYMENT
                || status == LoanApplicationStatus.CLOSED
                || status == LoanApplicationStatus.FORECLOSED;
    }

    private static String providerStatusFor(MockDisbursementOutcome outcome) {
        return switch (outcome) {
            case DISBURSED -> "DISBURSED";
            case FAILED -> "FAILED";
            case PENDING_RECONCILIATION -> "PENDING_RECONCILIATION";
        };
    }

    private String serializeDisbursementRequest(LoanDisbursementAdapter.DisbursementCommand command) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", "MOCK_DISBURSEMENT");
        payload.put("loanAccountNumber", command.loanAccountNumber());
        payload.put("amount", command.amount());
        payload.put("borrowerName", command.borrowerName());
        payload.put("externalLoanId", command.externalLoanId());
        payload.put("lspCode", command.lspCode());

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize disbursement request payload.", exception);
        }
    }

    private String serializeDisbursementOutcomeResponse(
            LoanDisbursementRequestLog requestLog,
            MockDisbursementOutcome outcome,
            String actorUsername
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", requestLog.getProviderName());
        payload.put("providerRequestId", requestLog.getProviderRequestId());
        payload.put("status", providerStatusFor(outcome));
        payload.put("resolvedBy", Strings.normalizeActor(actorUsername));
        payload.put("message", switch (outcome) {
            case DISBURSED -> "Mock disbursement completed successfully.";
            case FAILED -> "Mock disbursement failed in the simulated provider.";
            case PENDING_RECONCILIATION -> "Mock disbursement is awaiting reconciliation.";
        });

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize disbursement outcome payload.", exception);
        }
    }
}
