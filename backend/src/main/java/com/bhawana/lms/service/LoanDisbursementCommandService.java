package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.money.LoanFeeCalculator;
import com.bhawana.lms.common.money.Money;
import com.bhawana.lms.common.util.Strings;
import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import com.bhawana.lms.domain.DisbursementDeclineKind;
import com.bhawana.lms.domain.DisbursementDisposition;
import com.bhawana.lms.domain.DisbursementPaymentMode;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import com.bhawana.lms.domain.MockDisbursementOutcome;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns disbursement <em>initiation</em> (raising a provider request) and the orchestration of the
 * resolution flows (manual mock outcome, worker auto-resolve, status-check poll). The terminal
 * verdict is normalised into a {@link DisbursementOutcomeApplier.ProviderOutcome} and applied to
 * persistent state by {@link DisbursementOutcomeApplier}.
 */
@Service
public class LoanDisbursementCommandService {

    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;
    private final LoanDisbursementAdapter loanDisbursementAdapter;
    private final WebhookOutboxService webhookOutboxService;
    private final LoanApplicationQueryService loanApplicationQueryService;
    private final LoanApplicationDocumentChecklistService loanApplicationDocumentChecklistService;
    private final LoanApplicationStatusWriter loanApplicationStatusWriter;
    private final DisbursementOutcomeApplier disbursementOutcomeApplier;
    private final LoanDisbursementMockProperties mockProperties;
    private final ObjectMapper objectMapper;

    public LoanDisbursementCommandService(
            LoanApplicationRepository loanApplicationRepository,
            LoanAccountRepository loanAccountRepository,
            LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository,
            LoanDisbursementAdapter loanDisbursementAdapter,
            WebhookOutboxService webhookOutboxService,
            LoanApplicationQueryService loanApplicationQueryService,
            LoanApplicationDocumentChecklistService loanApplicationDocumentChecklistService,
            LoanApplicationStatusWriter loanApplicationStatusWriter,
            DisbursementOutcomeApplier disbursementOutcomeApplier,
            LoanDisbursementMockProperties mockProperties,
            ObjectMapper objectMapper
    ) {
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanDisbursementRequestLogRepository = loanDisbursementRequestLogRepository;
        this.loanDisbursementAdapter = loanDisbursementAdapter;
        this.webhookOutboxService = webhookOutboxService;
        this.loanApplicationQueryService = loanApplicationQueryService;
        this.loanApplicationDocumentChecklistService = loanApplicationDocumentChecklistService;
        this.loanApplicationStatusWriter = loanApplicationStatusWriter;
        this.disbursementOutcomeApplier = disbursementOutcomeApplier;
        this.mockProperties = mockProperties;
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
        return initiateDisbursement(applicationId, actorUsername, netDisbursalAmount(loanAccount));
    }

    /**
     * ADR 0004 — cash sent to the borrower is the requested principal minus the processing fee.
     * The recorded principal (the debt the borrower repays) is unchanged. Rejects the rare case
     * where the fee would consume the entire principal, leaving nothing to disburse.
     */
    private BigDecimal netDisbursalAmount(LoanAccount loanAccount) {
        BigDecimal principal = Money.scale(loanAccount.getPrincipalAmount());
        BigDecimal fee = LoanFeeCalculator.computeProcessingFee(
                principal,
                loanAccount.getLoanProduct().getProcessingFeeRate()
        );
        BigDecimal net = Money.scale(principal.subtract(fee));
        if (net.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessRuleViolationException(
                    "DISBURSEMENT_FEE_EXCEEDS_PRINCIPAL",
                    "Processing fee leaves no net amount to disburse to the borrower.",
                    Map.of("principal", principal.toPlainString(), "processingFee", fee.toPlainString())
            );
        }
        return net;
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
        loanApplicationDocumentChecklistService.validateRequiredDocumentsUploadedBeforeDisbursement(applicationId);

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
        DisbursementPaymentMode paymentMode = selectPaymentMode(scaledDisbursementAmount);
        String tranRefNo = newTranRefNo();
        LoanDisbursementAdapter.DisbursementCommand command = new LoanDisbursementAdapter.DisbursementCommand(
                loanAccount.getAccountNumber(),
                scaledDisbursementAmount,
                application.getBorrower().getFullName(),
                application.getExternalLoanId(),
                application.getLsp().getCode(),
                paymentMode,
                tranRefNo,
                application.getBorrower().getBankAccountNumber(),
                application.getBorrower().getIfscCode()
        );
        LoanDisbursementAdapter.DisbursementResult result = loanDisbursementAdapter.requestDisbursement(command);

        loanAccount.markDisbursementRequested();
        loanAccountRepository.save(loanAccount);
        LoanDisbursementRequestLog requestLog = loanDisbursementRequestLogRepository.save(new LoanDisbursementRequestLog(
                loanAccount,
                Strings.normalizeActor(actorUsername),
                scaledDisbursementAmount,
                result.providerName(),
                result.providerRequestId(),
                result.providerStatus(),
                result.paymentMode(),
                tranRefNo,
                result.actCode(),
                result.bankRrn(),
                result.declineKind(),
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
                LoanWebhookPayloads.disbursement(application, loanAccount, requestLog)
        );
        return application;
    }

    /**
     * Worker hook: after {@link #initiateDisbursement} raises a request, resolve a terminal provider
     * verdict (IMPS success / decline) inline. PENDING transactions are left for the status-check
     * worker. Returns the disposition observed, or {@code null} when there is nothing to resolve.
     */
    @Transactional
    public DisbursementDisposition autoResolveAfterInitiate(
            UUID applicationId,
            String actorUsername,
            String actorIp,
            String correlationId
    ) {
        LoanApplication application = loanApplicationQueryService.getApplication(applicationId);
        LoanAccount loanAccount = getRequiredLoanAccount(applicationId);
        if (loanAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_REQUESTED) {
            return null;
        }
        LoanDisbursementRequestLog latestRequest = requireLatestRequest(applicationId, loanAccount.getId());
        DisbursementDisposition disposition = dispositionOf(latestRequest);
        if (disposition == DisbursementDisposition.PENDING) {
            return DisbursementDisposition.PENDING;
        }
        disbursementOutcomeApplier.apply(
                application,
                loanAccount,
                latestRequest,
                new DisbursementOutcomeApplier.ProviderOutcome(
                        disposition,
                        latestRequest.getDeclineKind(),
                        latestRequest.getProviderActCode(),
                        latestRequest.getBankRrn(),
                        "Resolved from provider payment response."
                ),
                actorUsername,
                actorIp,
                correlationId
        );
        return disposition;
    }

    /**
     * Status-check worker hook: polls a PENDING transaction (ICICI {@code /composite-status}) and
     * resolves it once terminal, or parks it for reconciliation once the poll cap is reached.
     *
     * @return true when the transaction reached a terminal/parked state; false when still pending.
     */
    @Transactional
    public boolean pollPendingDisbursement(
            UUID applicationId,
            String actorUsername,
            String actorIp,
            String correlationId
    ) {
        LoanApplication application = loanApplicationQueryService.getApplication(applicationId);
        LoanAccount loanAccount = getRequiredLoanAccount(applicationId);
        if (loanAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_REQUESTED) {
            return false;
        }
        LoanDisbursementRequestLog latestRequest = loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(loanAccount.getId())
                .orElse(null);
        if (latestRequest == null || dispositionOf(latestRequest) != DisbursementDisposition.PENDING) {
            return false;
        }

        LoanDisbursementAdapter.DisbursementStatusResult statusResult = loanDisbursementAdapter.checkStatus(
                new LoanDisbursementAdapter.DisbursementStatusQuery(
                        latestRequest.getTranRefNo(),
                        latestRequest.getPaymentMode(),
                        loanAccount.getBorrower().getIfscCode(),
                        latestRequest.getStatusCheckCount()
                )
        );
        latestRequest.recordStatusCheck();
        loanDisbursementRequestLogRepository.save(latestRequest);

        boolean queryResolvedTerminal = statusResult.isQueryResolved()
                && statusResult.disposition() != DisbursementDisposition.PENDING;
        if (queryResolvedTerminal) {
            disbursementOutcomeApplier.apply(
                    application,
                    loanAccount,
                    latestRequest,
                    new DisbursementOutcomeApplier.ProviderOutcome(
                            statusResult.disposition(),
                            statusResult.declineKind(),
                            statusResult.actCode(),
                            statusResult.bankRrn(),
                            statusResult.message()
                    ),
                    actorUsername,
                    actorIp,
                    correlationId
            );
            return true;
        }

        if (latestRequest.getStatusCheckCount() >= mockProperties.getStatusCheck().getMaxPolls()) {
            disbursementOutcomeApplier.apply(
                    application,
                    loanAccount,
                    latestRequest,
                    new DisbursementOutcomeApplier.ProviderOutcome(
                            DisbursementDisposition.PENDING,
                            DisbursementDeclineKind.NONE,
                            latestRequest.getProviderActCode(),
                            latestRequest.getBankRrn(),
                            "Status check exhausted; parked for manual reconciliation."
                    ),
                    actorUsername,
                    actorIp,
                    correlationId
            );
            return true;
        }
        return false;
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

        LoanDisbursementRequestLog latestRequest = requireLatestRequest(applicationId, loanAccount.getId());
        return disbursementOutcomeApplier.apply(
                application,
                loanAccount,
                latestRequest,
                toProviderOutcome(outcome),
                actorUsername,
                actorIp,
                correlationId
        );
    }

    private static DisbursementOutcomeApplier.ProviderOutcome toProviderOutcome(MockDisbursementOutcome outcome) {
        return switch (outcome) {
            case DISBURSED -> new DisbursementOutcomeApplier.ProviderOutcome(
                    DisbursementDisposition.SUCCESS, DisbursementDeclineKind.NONE, "0", null,
                    "Mock disbursement completed successfully.");
            case FAILED -> new DisbursementOutcomeApplier.ProviderOutcome(
                    DisbursementDisposition.FAILED, DisbursementDeclineKind.TECHNICAL, null, null,
                    "Mock disbursement failed in the simulated provider.");
            case PENDING_RECONCILIATION -> new DisbursementOutcomeApplier.ProviderOutcome(
                    DisbursementDisposition.PENDING, DisbursementDeclineKind.NONE, null, null,
                    "Mock disbursement is awaiting reconciliation.");
        };
    }

    private DisbursementPaymentMode selectPaymentMode(BigDecimal amount) {
        return amount.compareTo(mockProperties.getImpsMaxAmount()) > 0
                ? DisbursementPaymentMode.NEFT
                : DisbursementPaymentMode.IMPS;
    }

    private static String newTranRefNo() {
        // ICICI client-generated unique reference; kept <= 16 chars to satisfy the NEFT field limit.
        return "ICI" + UUID.randomUUID().toString().replace("-", "").substring(0, 13).toUpperCase();
    }

    private static DisbursementDisposition dispositionOf(LoanDisbursementRequestLog log) {
        // On initiation the provider status is the raw disposition name (SUCCESS/FAILED/PENDING).
        try {
            return DisbursementDisposition.valueOf(log.getProviderStatus());
        } catch (IllegalArgumentException ignored) {
            return DisbursementDisposition.PENDING;
        }
    }

    private LoanDisbursementRequestLog requireLatestRequest(UUID applicationId, UUID loanAccountId) {
        return loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(loanAccountId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Disbursement request log is not available for application id: " + applicationId
                ));
    }

    private LoanAccount getRequiredLoanAccount(UUID applicationId) {
        return loanAccountRepository.findDetailedByLoanApplication_Id(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan account is not available for application id: " + applicationId
                ));
    }

    private LoanAccount resolveLoanAccountForDisbursement(LoanApplication application) {
        return loanAccountRepository.findDetailedByLoanApplication_Id(application.getId())
                .orElseGet(() -> loanApplicationStatusWriter.ensureLoanAccountForApprovedApplication(application));
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

    private String serializeDisbursementRequest(LoanDisbursementAdapter.DisbursementCommand command) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", MockLoanDisbursementAdapter.PROVIDER_NAME);
        payload.put("paymentMode", command.paymentMode() == null ? null : command.paymentMode().name());
        payload.put("tranRefNo", command.tranRefNo());
        payload.put("loanAccountNumber", command.loanAccountNumber());
        payload.put("amount", command.amount());
        payload.put("borrowerName", command.borrowerName());
        payload.put("beneficiaryAccountNumber", command.beneficiaryAccountNumber());
        payload.put("beneficiaryIfsc", command.beneficiaryIfsc());
        payload.put("externalLoanId", command.externalLoanId());
        payload.put("lspCode", command.lspCode());

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize disbursement request payload.", exception);
        }
    }
}
