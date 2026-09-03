package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.util.Strings;
import com.bhawana.lms.domain.DisbursementDeclineKind;
import com.bhawana.lms.domain.DisbursementDisposition;
import com.bhawana.lms.domain.DisbursementIntent;
import com.bhawana.lms.domain.DisbursementIntentState;
import com.bhawana.lms.domain.DisbursementPaymentMode;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import com.bhawana.lms.domain.LoanEventType;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable disbursement intent workflow (Spec S3): Tx-A intent creation, out-of-transaction provider
 * calls, and Tx-B outcome persistence. {@link LoanDisbursementCommandService} delegates here when
 * {@link DisbursementIntentWorkflowProperties#isEnabled()} is true.
 */
@Service
public class DisbursementIntentWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(DisbursementIntentWorkflowService.class);

    private final DisbursementIntentRepository disbursementIntentRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;
    private final LoanDisbursementAdapter loanDisbursementAdapter;
    private final LoanEventLog loanEventLog;
    private final LoanApplicationQueryService loanApplicationQueryService;
    private final DisbursementIntentWorkflowProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public DisbursementIntentWorkflowService(
            DisbursementIntentRepository disbursementIntentRepository,
            LoanAccountRepository loanAccountRepository,
            LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository,
            LoanDisbursementAdapter loanDisbursementAdapter,
            LoanEventLog loanEventLog,
            LoanApplicationQueryService loanApplicationQueryService,
            DisbursementIntentWorkflowProperties properties,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate
    ) {
        this.disbursementIntentRepository = disbursementIntentRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanDisbursementRequestLogRepository = loanDisbursementRequestLogRepository;
        this.loanDisbursementAdapter = loanDisbursementAdapter;
        this.loanEventLog = loanEventLog;
        this.loanApplicationQueryService = loanApplicationQueryService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    @Transactional
    public DisbursementIntent createIntent(
            LoanApplication application,
            LoanAccount loanAccount,
            BigDecimal disbursementAmount,
            DisbursementPaymentMode paymentMode,
            String actorUsername
    ) {
        disbursementIntentRepository.findLiveByLoanAccountId(loanAccount.getId()).ifPresent(existing -> {
            throw new ApiConflictException(
                    "DISBURSEMENT_ALREADY_REQUESTED",
                    "A live disbursement intent already exists for this loan account."
            );
        });

        UUID intentId = UUID.randomUUID();
        DisbursementIntent intent = new DisbursementIntent(
                intentId,
                loanAccount,
                DisbursementIntentReference.deriveTranRefNo(intentId),
                disbursementAmount,
                paymentMode,
                application.getBorrower().getFullName(),
                application.getBorrower().getBankAccountNumber(),
                application.getBorrower().getIfscCode(),
                Strings.normalizeActor(actorUsername),
                CorrelationIdHolder.get()
        );
        loanAccount.markDisbursementRequested();
        loanAccountRepository.save(loanAccount);
        return disbursementIntentRepository.save(intent);
    }

    public List<UUID> executeClaimableIntents() {
        Instant now = Instant.now();
        Instant leaseExpiresAt = now.plusSeconds(properties.getLeaseDurationSeconds());
        List<UUID> claimedIds = transactionTemplate.execute(status -> disbursementIntentRepository.claimBatch(
                now,
                properties.getClaimBatchSize(),
                leaseExpiresAt,
                properties.getLeaseOwner()
        ));
        if (claimedIds == null) {
            return List.of();
        }
        return claimedIds.stream()
                .map(this::executeClaimedIntent)
                .flatMap(Optional::stream)
                .toList();
    }

    public Optional<UUID> executeForApplication(UUID applicationId) {
        LoanAccount loanAccount = loanAccountRepository.findByLoanApplication_Id(applicationId).orElse(null);
        if (loanAccount == null) {
            return Optional.empty();
        }
        Optional<DisbursementIntent> intent = disbursementIntentRepository.findLiveByLoanAccountId(loanAccount.getId())
                .filter(candidate -> candidate.getState() == DisbursementIntentState.CREATED);
        if (intent.isEmpty()) {
            return Optional.empty();
        }
        DisbursementIntent claimed = transactionTemplate.execute(status -> claimIntent(intent.get().getId()));
        if (claimed == null) {
            return Optional.empty();
        }
        return executeClaimedIntent(claimed.getId());
    }

    public Optional<UUID> executeClaimedIntent(UUID intentId) {
        ProviderCallContext context = transactionTemplate.execute(status -> loadProviderCallContext(intentId));
        if (context == null) {
            return Optional.empty();
        }

        LoanDisbursementAdapter.DisbursementResult result;
        try {
            result = loanDisbursementAdapter.requestDisbursement(context.command());
        } catch (RuntimeException exception) {
            log.warn("Disbursement provider call failed for intent {}: {}", intentId, exception.getMessage());
            transactionTemplate.executeWithoutResult(status -> persistUnknownProviderOutcome(intentId, context, exception.getMessage()));
            return Optional.of(context.applicationId());
        }

        transactionTemplate.executeWithoutResult(status -> persistProviderOutcome(intentId, context, result));
        return Optional.of(context.applicationId());
    }

    public Optional<StatusPollContext> loadStatusPollContext(UUID applicationId) {
        LoanApplication application = loanApplicationQueryService.getApplication(applicationId);
        LoanAccount loanAccount = loanAccountRepository.findDetailedByLoanApplication_Id(applicationId).orElse(null);
        if (loanAccount == null) {
            return Optional.empty();
        }
        LoanDisbursementRequestLog latestRequest = loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(loanAccount.getId())
                .orElse(null);
        if (latestRequest == null || dispositionOf(latestRequest) != DisbursementDisposition.PENDING) {
            return Optional.empty();
        }
        return Optional.of(new StatusPollContext(application, loanAccount, latestRequest));
    }

    @Transactional
    public void markIntentFromStatusPoll(UUID loanAccountId, LoanDisbursementAdapter.DisbursementStatusResult statusResult) {
        disbursementIntentRepository.findLiveByLoanAccountId(loanAccountId).ifPresent(intent -> {
            intent.recordProviderResponse(
                    mapIntentState(statusResult.disposition()),
                    intent.getProviderRequestId(),
                    statusResult.actCode(),
                    statusResult.bankRrn(),
                    statusResult.declineKind()
            );
            disbursementIntentRepository.save(intent);
        });
    }

    @Transactional
    DisbursementIntent claimIntent(UUID intentId) {
        Instant now = Instant.now();
        DisbursementIntent intent = disbursementIntentRepository.findById(intentId).orElse(null);
        if (intent == null || !intent.getState().isClaimable()) {
            return null;
        }
        if (intent.getLeaseExpiresAt() != null && intent.getLeaseExpiresAt().isAfter(now)) {
            return null;
        }
        intent.stampLease(properties.getLeaseOwner(), now.plusSeconds(properties.getLeaseDurationSeconds()));
        return intent;
    }

    ProviderCallContext loadProviderCallContext(UUID intentId) {
        DisbursementIntent intent = disbursementIntentRepository.findDetailedById(intentId).orElse(null);
        Instant now = Instant.now();
        if (intent == null
                || !intent.getState().isClaimable()
                || !properties.getLeaseOwner().equals(intent.getLeaseOwner())
                || intent.getLeaseExpiresAt() == null
                || !intent.getLeaseExpiresAt().isAfter(now)) {
            return null;
        }
        LoanAccount loanAccount = intent.getLoanAccount();
        LoanApplication application = loanAccount.getLoanApplication();
        LoanDisbursementAdapter.DisbursementCommand command = new LoanDisbursementAdapter.DisbursementCommand(
                loanAccount.getAccountNumber(),
                intent.getAmount(),
                intent.getBeneficiaryName(),
                application.getExternalLoanId(),
                application.getLsp().getCode(),
                intent.getPaymentMode(),
                intent.getTranRefNo(),
                intent.getBeneficiaryAccountNumber(),
                intent.getBeneficiaryIfsc()
        );
        LoanDisbursementRequestLog requestLog = loanDisbursementRequestLogRepository.save(new LoanDisbursementRequestLog(
                loanAccount,
                intent.getCreatedBy(),
                intent.getAmount(),
                loanDisbursementAdapter.providerName(),
                intent.getTranRefNo(),
                DisbursementDisposition.PENDING.name(),
                intent.getPaymentMode(),
                intent.getTranRefNo(),
                null,
                null,
                DisbursementDeclineKind.NONE,
                serializeDisbursementRequest(command),
                serializePendingResponse(),
                intent.getCorrelationId()
        ));
        intent.markProviderCallStarted();
        disbursementIntentRepository.save(intent);
        return new ProviderCallContext(
                intentId,
                application.getId(),
                loanAccount.getId(),
                requestLog.getId(),
                command);
    }

    @Transactional
    void persistProviderOutcome(
            UUID intentId,
            ProviderCallContext context,
            LoanDisbursementAdapter.DisbursementResult result
    ) {
        DisbursementIntent intent = disbursementIntentRepository.findById(intentId).orElseThrow();
        LoanAccount loanAccount = loanAccountRepository.findById(context.loanAccountId()).orElseThrow();
        LoanApplication application = loanApplicationQueryService.getApplication(context.applicationId());

        LoanDisbursementRequestLog requestLog = loanDisbursementRequestLogRepository
                .findById(context.requestLogId())
                .orElseThrow();
        requestLog.updateProviderSubmission(
                result.providerName(),
                result.providerRequestId(),
                result.providerStatus(),
                result.paymentMode(),
                result.actCode(),
                result.bankRrn(),
                result.declineKind(),
                result.responsePayloadJson()
        );
        loanDisbursementRequestLogRepository.save(requestLog);

        intent.recordProviderResponse(
                mapIntentState(result.disposition()),
                result.providerRequestId(),
                result.actCode(),
                result.bankRrn(),
                result.declineKind()
        );
        disbursementIntentRepository.save(intent);

        loanEventLog.append(
                application.getLsp(),
                LoanEventType.DISBURSEMENT_REQUESTED,
                "LOAN_ACCOUNT",
                loanAccount.getId().toString(),
                application.getId(),
                LoanEventPayloads.disbursement(application, loanAccount, requestLog)
        );
    }

    @Transactional
    void persistUnknownProviderOutcome(UUID intentId, ProviderCallContext context, String message) {
        DisbursementIntent intent = disbursementIntentRepository.findById(intentId).orElseThrow();
        LoanAccount loanAccount = loanAccountRepository.findById(context.loanAccountId()).orElseThrow();
        LoanApplication application = loanApplicationQueryService.getApplication(context.applicationId());

        String payload = serializeUnknownResponse(message);
        LoanDisbursementRequestLog requestLog = loanDisbursementRequestLogRepository
                .findById(context.requestLogId())
                .orElseThrow();
        requestLog.updateOutcome(
                DisbursementDisposition.PENDING.name(),
                null,
                null,
                DisbursementDeclineKind.NONE,
                payload
        );
        loanDisbursementRequestLogRepository.save(requestLog);

        intent.recordProviderResponse(
                DisbursementIntentState.UNKNOWN,
                intent.getTranRefNo(),
                null,
                null,
                DisbursementDeclineKind.NONE
        );
        disbursementIntentRepository.save(intent);

        loanEventLog.append(
                application.getLsp(),
                LoanEventType.DISBURSEMENT_REQUESTED,
                "LOAN_ACCOUNT",
                loanAccount.getId().toString(),
                application.getId(),
                LoanEventPayloads.disbursement(application, loanAccount, requestLog)
        );
    }

    private static DisbursementIntentState mapIntentState(DisbursementDisposition disposition) {
        return switch (disposition) {
            case SUCCESS -> DisbursementIntentState.SUCCEEDED;
            case FAILED -> DisbursementIntentState.FAILED;
            case PENDING -> DisbursementIntentState.REQUESTED;
        };
    }

    private static DisbursementDisposition dispositionOf(LoanDisbursementRequestLog log) {
        try {
            return DisbursementDisposition.valueOf(log.getProviderStatus());
        } catch (IllegalArgumentException ignored) {
            return DisbursementDisposition.PENDING;
        }
    }

    private String serializeDisbursementRequest(LoanDisbursementAdapter.DisbursementCommand command) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", loanDisbursementAdapter.providerName());
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

    private String serializeUnknownResponse(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "disposition", DisbursementDisposition.PENDING.name(),
                    "message", message == null ? "Provider call failed with unknown outcome." : message
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize unknown provider response.", exception);
        }
    }

    private String serializePendingResponse() {
        return serializeUnknownResponse("Provider call initiated; terminal response not yet recorded.");
    }

    record ProviderCallContext(
            UUID intentId,
            UUID applicationId,
            UUID loanAccountId,
            UUID requestLogId,
            LoanDisbursementAdapter.DisbursementCommand command
    ) {
    }

    public record StatusPollContext(
            LoanApplication application,
            LoanAccount loanAccount,
            LoanDisbursementRequestLog latestRequest
    ) {
        public LoanDisbursementAdapter.DisbursementStatusQuery query() {
            return new LoanDisbursementAdapter.DisbursementStatusQuery(
                    latestRequest.getTranRefNo(),
                    latestRequest.getPaymentMode(),
                    loanAccount.getBorrower().getIfscCode(),
                    latestRequest.getStatusCheckCount()
            );
        }
    }
}
