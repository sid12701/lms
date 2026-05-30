package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.web.PagedResult;
import com.bhawana.lms.domain.LoanDelinquencyBucket;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationAuditAction;
import com.bhawana.lms.domain.LoanApplicationAuditEvent;
import com.bhawana.lms.domain.LoanApplicationDocumentAccessAudit;
import com.bhawana.lms.domain.LoanApplicationDocumentAccessAuditAction;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.domain.LoanApplicationIntakeAudit;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusReasonCode;
import com.bhawana.lms.domain.LoanApplicationStatusTransition;
import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import com.bhawana.lms.domain.LoanInvalidationReason;
import com.bhawana.lms.domain.LoanPaymentChannel;
import com.bhawana.lms.domain.LoanPaymentTransaction;
import com.bhawana.lms.domain.LoanForeclosureQuote;
import com.bhawana.lms.domain.MockDisbursementOutcome;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationAuditEventRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentAccessAuditRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationIntakeAuditRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.bhawana.lms.repo.LoanPaymentTransactionRepository;
import com.bhawana.lms.repo.LoanForeclosureQuoteRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanApplicationService {

    private final LoanAccountRepository loanAccountRepository;
    private final LoanApplicationAuditEventRepository loanApplicationAuditEventRepository;
    private final LoanApplicationDocumentAccessAuditRepository loanApplicationDocumentAccessAuditRepository;
    private final LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    private final LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository;
    private final LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;
    private final LoanPaymentTransactionRepository loanPaymentTransactionRepository;
    private final LoanForeclosureQuoteRepository loanForeclosureQuoteRepository;
    private final LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;
    private final LoanDisbursementAdapter loanDisbursementAdapter;
    private final WebhookOutboxService webhookOutboxService;
    private final com.bhawana.lms.repo.WebhookEventDeliveryAttemptRepository webhookEventDeliveryAttemptRepository;
    private final LoanApplicationQueryService loanApplicationQueryService;
    private final LoanApplicationLifecycleService loanApplicationLifecycleService;
    private final LoanRepaymentCommandService loanRepaymentCommandService;
    private final LoanForeclosureCommandService loanForeclosureCommandService;
    private final ObjectMapper objectMapper;

    public LoanApplicationService(
            LoanAccountRepository loanAccountRepository,
            LoanApplicationAuditEventRepository loanApplicationAuditEventRepository,
            LoanApplicationDocumentAccessAuditRepository loanApplicationDocumentAccessAuditRepository,
            LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository,
            LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository,
            LoanApplicationRepository loanApplicationRepository,
            LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository,
            LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository,
            LoanPaymentTransactionRepository loanPaymentTransactionRepository,
            LoanForeclosureQuoteRepository loanForeclosureQuoteRepository,
            LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository,
            LoanDisbursementAdapter loanDisbursementAdapter,
            WebhookOutboxService webhookOutboxService,
            com.bhawana.lms.repo.WebhookEventDeliveryAttemptRepository webhookEventDeliveryAttemptRepository,
            LoanApplicationQueryService loanApplicationQueryService,
            LoanApplicationLifecycleService loanApplicationLifecycleService,
            LoanRepaymentCommandService loanRepaymentCommandService,
            LoanForeclosureCommandService loanForeclosureCommandService,
            ObjectMapper objectMapper
    ) {
        this.loanAccountRepository = loanAccountRepository;
        this.loanApplicationAuditEventRepository = loanApplicationAuditEventRepository;
        this.loanApplicationDocumentAccessAuditRepository = loanApplicationDocumentAccessAuditRepository;
        this.loanApplicationDocumentChecklistRepository = loanApplicationDocumentChecklistRepository;
        this.loanApplicationIntakeAuditRepository = loanApplicationIntakeAuditRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanApplicationStatusTransitionRepository = loanApplicationStatusTransitionRepository;
        this.loanDisbursementRequestLogRepository = loanDisbursementRequestLogRepository;
        this.loanPaymentTransactionRepository = loanPaymentTransactionRepository;
        this.loanForeclosureQuoteRepository = loanForeclosureQuoteRepository;
        this.loanRepaymentScheduleInstallmentRepository = loanRepaymentScheduleInstallmentRepository;
        this.loanDisbursementAdapter = loanDisbursementAdapter;
        this.webhookOutboxService = webhookOutboxService;
        this.webhookEventDeliveryAttemptRepository = webhookEventDeliveryAttemptRepository;
        this.loanApplicationQueryService = loanApplicationQueryService;
        this.loanApplicationLifecycleService = loanApplicationLifecycleService;
        this.loanRepaymentCommandService = loanRepaymentCommandService;
        this.loanForeclosureCommandService = loanForeclosureCommandService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<LoanApplication> listApplications(
            UUID lspId,
            UUID productId,
            String status,
            String sourceChannel,
            String query,
            LocalDate disbursalDateFrom,
            LocalDate disbursalDateTo
    ) {
        return loanApplicationQueryService.listApplications(
                lspId,
                productId,
                status,
                sourceChannel,
                query,
                disbursalDateFrom,
                disbursalDateTo
        );
    }

    @Transactional(readOnly = true)
    public PagedResult<LoanApplication> listApplicationsPage(
            UUID lspId,
            UUID productId,
            String status,
            String sourceChannel,
            String query,
            String lspLoanId,
            String bhawLoanId,
            LocalDate disbursalDateFrom,
            LocalDate disbursalDateTo,
            Integer offset,
            Integer limit,
            boolean includePaginationDetails
    ) {
        return loanApplicationQueryService.listApplicationsPage(
                lspId,
                productId,
                status,
                sourceChannel,
                query,
                lspLoanId,
                bhawLoanId,
                disbursalDateFrom,
                disbursalDateTo,
                offset,
                limit,
                includePaginationDetails
        );
    }

    @Transactional(readOnly = true)
    public PagedResult<LoanApplication> listApplicationsPage(
            UUID lspId,
            UUID productId,
            String status,
            String sourceChannel,
            String query,
            LocalDate disbursalDateFrom,
            LocalDate disbursalDateTo,
            Integer offset,
            Integer limit,
            boolean includePaginationDetails
    ) {
        return listApplicationsPage(
                lspId,
                productId,
                status,
                sourceChannel,
                query,
                null,
                null,
                disbursalDateFrom,
                disbursalDateTo,
                offset,
                limit,
                includePaginationDetails
        );
    }

    @Transactional(readOnly = true)
    public Map<UUID, String> getLoanAccountNumbers(List<UUID> applicationIds) {
        if (applicationIds == null || applicationIds.isEmpty()) {
            return Map.of();
        }
        return loanAccountRepository.findAccountNumbersByLoanApplicationIdIn(applicationIds).stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        LoanAccountRepository.LoanAccountNumberProjection::getApplicationId,
                        LoanAccountRepository.LoanAccountNumberProjection::getAccountNumber
                ));
    }

    @Transactional(readOnly = true)
    public List<LoanApplication> listApplicationsForLsp(
            UUID lspId,
            UUID productId,
            String status,
            String sourceChannel,
            String query
    ) {
        return loanApplicationQueryService.listApplicationsForLsp(lspId, productId, status, sourceChannel, query);
    }

    @Transactional(readOnly = true)
    public PagedResult<LoanApplication> listApplicationsForLspPage(
            UUID lspId,
            UUID productId,
            String status,
            String sourceChannel,
            String query,
            Integer offset,
            Integer limit,
            boolean includePaginationDetails
    ) {
        return loanApplicationQueryService.listApplicationsForLspPage(
                lspId,
                productId,
                status,
                sourceChannel,
                query,
                offset,
                limit,
                includePaginationDetails
        );
    }

    @Transactional(readOnly = true)
    public LoanApplication getApplication(UUID applicationId) {
        return loanApplicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown loan application id: " + applicationId));
    }

    @Transactional(readOnly = true)
    public LoanApplication getApplicationForLsp(UUID lspId, UUID applicationId) {
        LoanApplication application = getApplication(applicationId);
        if (!application.getLsp().getId().equals(lspId)) {
            throw new IllegalArgumentException("Unknown loan application id: " + applicationId);
        }
        return application;
    }

    @Transactional(readOnly = true)
    public LoanApplication getApplicationForLspByExternalLoanId(UUID lspId, String externalLoanId) {
        String normalizedExternalLoanId = normalizeOptional(externalLoanId);
        if (normalizedExternalLoanId == null) {
            throw new IllegalArgumentException("externalLoanId is required.");
        }
        return loanApplicationRepository.findDetailedByLsp_IdAndExternalLoanIdIgnoreCase(lspId, normalizedExternalLoanId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown external loan id for the authenticated LSP: " + normalizedExternalLoanId
                ));
    }

    @Transactional(readOnly = true)
    public LoanAccount getLoanAccountForLsp(UUID lspId, UUID loanAccountId) {
        LoanAccount loanAccount = loanAccountRepository.findDetailedById(loanAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown loan id: " + loanAccountId));
        if (!loanAccount.getLsp().getId().equals(lspId)) {
            throw new IllegalArgumentException("Unknown loan id: " + loanAccountId);
        }
        return loanAccount;
    }

    @Transactional(readOnly = true)
    public List<LoanRepaymentScheduleInstallment> listRepaymentScheduleForLsp(UUID lspId, UUID loanAccountId) {
        LoanAccount loanAccount = getLoanAccountForLsp(lspId, loanAccountId);
        return loanRepaymentScheduleInstallmentRepository.findByLoanAccount_IdOrderByInstallmentNumberAsc(
                loanAccount.getId()
        );
    }

    @Transactional(readOnly = true)
    public List<LoanPaymentTransaction> listPaymentTransactionsForLsp(UUID lspId, UUID loanAccountId) {
        LoanAccount loanAccount = getLoanAccountForLsp(lspId, loanAccountId);
        return loanPaymentTransactionRepository.findTop50ByLoanAccount_IdOrderByPaymentDateDescCreatedAtDesc(
                loanAccount.getId()
        );
    }

    @Transactional(readOnly = true)
    public Optional<LoanApplicationLastActivity> getLatestActivity(UUID applicationId) {
        LoanApplication application = getApplication(applicationId);

        Stream<ActivityCandidate> candidates = Stream.of(
                loanApplicationIntakeAuditRepository.findTopByLoanApplication_IdOrderByCreatedAtDesc(applicationId)
                        .map(audit -> new ActivityCandidate(
                                0,
                                new LoanApplicationLastActivity(
                                        "INTAKE_CAPTURED",
                                        audit.getActorUsername(),
                                        "Application captured from " + application.getSourceChannel(),
                                        "External loan id " + application.getExternalLoanId(),
                                        audit.getCorrelationId(),
                                        audit.getCreatedAt()
                                )
                        )),
                loanApplicationStatusTransitionRepository.findTopByLoanApplication_IdOrderByCreatedAtDesc(applicationId)
                        .map(transition -> new ActivityCandidate(
                                1,
                                new LoanApplicationLastActivity(
                                        "STATUS_TRANSITION",
                                        transition.getActorUsername(),
                                        "Moved from " + transition.getFromStatus().name() + " to " + transition.getToStatus().name(),
                                        transition.getReasonCode() == null
                                                ? transition.getNote()
                                                : transition.getNote() + " [" + transition.getReasonCode().name() + "]",
                                        transition.getCorrelationId(),
                                        transition.getCreatedAt()
                                )
                        )),
                loanApplicationDocumentChecklistRepository.findTopByLoanApplication_IdOrderByUpdatedAtDesc(applicationId)
                        .filter(this::hasMeaningfulDocumentActivity)
                        .map(item -> new ActivityCandidate(
                                2,
                                new LoanApplicationLastActivity(
                                        "DOCUMENT_REVIEW_UPDATED",
                                        item.getUpdatedByUsername(),
                                        "Updated " + item.getDocumentType().getDisplayName()
                                                + " to " + item.getStatus().name(),
                                        resolveDocumentActivityDetail(item),
                                        null,
                                        item.getUpdatedAt()
                                )
                        ))
        ).flatMap(Optional::stream);

        return candidates.max(Comparator
                        .comparing((ActivityCandidate candidate) -> candidate.activity().occurredAt())
                        .thenComparingInt(ActivityCandidate::priority))
                .map(ActivityCandidate::activity);
    }

    @Transactional(readOnly = true)
    public List<LoanApplicationStatusTransition> listStatusTransitions(UUID applicationId) {
        getApplication(applicationId);
        return loanApplicationStatusTransitionRepository.findTop20ByLoanApplication_IdOrderByCreatedAtDesc(applicationId);
    }

    @Transactional(readOnly = true)
    public List<LoanApplicationAuditEvent> listAuditEvents(UUID applicationId) {
        getApplication(applicationId);
        return loanApplicationAuditEventRepository.findTop25ByLoanApplication_IdOrderByCreatedAtDesc(applicationId);
    }

    @Transactional(readOnly = true)
    public Optional<LoanAccount> getLoanAccount(UUID applicationId) {
        getApplication(applicationId);
        return loanAccountRepository.findDetailedByLoanApplication_Id(applicationId);
    }

    @Transactional(readOnly = true)
    public Optional<LoanRepaymentScheduleSummary> getLoanRepaymentScheduleSummary(UUID applicationId) {
        return getLoanAccount(applicationId)
                .map(account -> loanRepaymentScheduleInstallmentRepository.findByLoanAccount_IdOrderByInstallmentNumberAsc(account.getId()))
                .filter(installments -> !installments.isEmpty())
                .map(installments -> new LoanRepaymentScheduleSummary(
                        installments.size(),
                        installments.getFirst().getInstallmentAmount(),
                        installments.getFirst().getDueDate(),
                        installments.getLast().getDueDate()
                ));
    }

    @Transactional(readOnly = true)
    public Optional<LoanDelinquencySummary> getLoanDelinquencySummary(UUID applicationId) {
        return getLoanAccount(applicationId)
                .map(account -> loanRepaymentScheduleInstallmentRepository.findByLoanAccount_IdOrderByInstallmentNumberAsc(account.getId()))
                .filter(installments -> !installments.isEmpty())
                .map(this::buildDelinquencySummary);
    }

    @Transactional(readOnly = true)
    public List<LoanRepaymentScheduleInstallment> listRepaymentSchedule(UUID applicationId) {
        LoanAccount loanAccount = getRequiredLoanAccount(applicationId);
        return loanRepaymentScheduleInstallmentRepository.findByLoanAccount_IdOrderByInstallmentNumberAsc(
                loanAccount.getId()
        );
    }

    @Transactional(readOnly = true)
    public List<LoanApplicationWebhookEventProjection> listWebhookEventsForApplication(UUID applicationId) {
        if (applicationId == null || !loanApplicationRepository.existsById(applicationId)) {
            throw new com.bhawana.lms.common.web.ResourceNotFoundException(
                    "Loan application not found: " + applicationId);
        }
        return webhookOutboxService.listOutboxForLoanApplication(applicationId).stream()
                .map(this::toWebhookEventProjection)
                .toList();
    }

    private LoanApplicationWebhookEventProjection toWebhookEventProjection(
            com.bhawana.lms.domain.WebhookEventOutbox event
    ) {
        java.util.Optional<com.bhawana.lms.domain.WebhookEventDeliveryAttempt> latestAttempt =
                webhookEventDeliveryAttemptRepository.findFirstByOutboxEvent_IdOrderByCreatedAtDesc(event.getId());
        Integer responseCode = latestAttempt.map(com.bhawana.lms.domain.WebhookEventDeliveryAttempt::getResponseStatusCode)
                .orElse(null);
        return new LoanApplicationWebhookEventProjection(
                event.getId().toString(),
                event.getEventType().name(),
                event.getLsp() == null ? null : event.getLsp().getWebhookEndpointUrl(),
                mapOutboxStatusToDeliveryStatus(event.getStatus()),
                event.getAttemptCount(),
                event.getLastAttemptAt(),
                responseCode,
                event.getLastError(),
                event.getCreatedAt()
        );
    }

    private static String mapOutboxStatusToDeliveryStatus(com.bhawana.lms.domain.WebhookEventOutboxStatus status) {
        return switch (status) {
            case PENDING -> "PENDING";
            case DELIVERED -> "DELIVERED";
            case RETRYABLE_FAILURE -> "FAILED";
            case PERMANENT_FAILURE -> "DEAD_LETTERED";
        };
    }

    @Transactional(readOnly = true)
    public List<LoanDisbursementRequestLog> listDisbursementRequests(UUID applicationId) {
        LoanAccount loanAccount = getRequiredLoanAccount(applicationId);
        return loanDisbursementRequestLogRepository.findTop20ByLoanAccount_IdOrderByCreatedAtDesc(loanAccount.getId());
    }

    @Transactional(readOnly = true)
    public List<LoanPaymentTransaction> listPaymentTransactions(UUID applicationId) {
        LoanAccount loanAccount = getRequiredLoanAccount(applicationId);
        return loanPaymentTransactionRepository.findTop50ByLoanAccount_IdOrderByPaymentDateDescCreatedAtDesc(
                loanAccount.getId()
        );
    }

    @Transactional(readOnly = true)
    public List<LoanForeclosureQuote> listForeclosureQuotes(UUID applicationId) {
        LoanAccount loanAccount = getRequiredLoanAccount(applicationId);
        return loanForeclosureQuoteRepository.findByLoanAccount_IdOrderByVersionDesc(loanAccount.getId());
    }

    @Transactional(readOnly = true)
    public LoanApplicationDocumentChecklist getDocumentChecklistItem(UUID applicationId, LoanApplicationDocumentType documentType) {
        getApplication(applicationId);
        return loanApplicationDocumentChecklistRepository.findByLoanApplication_IdAndDocumentType(applicationId, documentType)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "Document checklist item not found for type " + documentType.name()
                                + " on application " + applicationId
                ));
    }

    @Transactional
    public List<LoanApplicationDocumentChecklist> listDocumentChecklist(UUID applicationId, String actorUsername) {
        LoanApplication application = getApplication(applicationId);
        loanApplicationLifecycleService.ensureDocumentChecklist(application);
        List<LoanApplicationDocumentChecklist> checklist =
                loanApplicationDocumentChecklistRepository.findByLoanApplication_IdOrderByCreatedAtAsc(applicationId);
        loanApplicationDocumentAccessAuditRepository.save(new LoanApplicationDocumentAccessAudit(
                application,
                LoanApplicationDocumentAccessAuditAction.CHECKLIST_VIEWED,
                normalizeActorUsername(actorUsername),
                "Viewed " + checklist.size() + " KYC document placeholders",
                checklist.stream().map(LoanApplicationDocumentChecklist::getDocumentType).toList(),
                CorrelationIdHolder.get()
        ));
        return checklist;
    }

    @Transactional(readOnly = true)
    public List<LoanApplicationDocumentAccessAudit> listDocumentAccessAudits(UUID applicationId) {
        getApplication(applicationId);
        return loanApplicationDocumentAccessAuditRepository.findTop20ByLoanApplication_IdOrderByCreatedAtDesc(applicationId);
    }

    /**
     * Gap #4: LSP-scoped "uploads only" document checklist read. Enforces
     * ownership (404 if the loan isn't owned by `lspId`) and filters out
     * the un-uploaded `PENDING` placeholders so external API consumers only
     * see what was actually submitted. The "project all standard types"
     * mode is deferred to post-prod and would be a separate
     * `?includePending=true` switch.
     */
    @Transactional(readOnly = true)
    public List<LoanApplicationDocumentChecklist> listSubmittedDocumentsForLsp(UUID lspId, UUID applicationId) {
        getApplicationForLsp(lspId, applicationId);
        return loanApplicationDocumentChecklistRepository
                .findByLoanApplication_IdOrderByCreatedAtAsc(applicationId)
                .stream()
                .filter(item -> item.getStatus() != LoanApplicationDocumentChecklistStatus.PENDING
                        && item.getStatus() != LoanApplicationDocumentChecklistStatus.NOT_REQUIRED)
                .toList();
    }

    @Transactional
    public LoanApplicationDocumentChecklist submitDocumentForLsp(
            UUID lspId,
            UUID applicationId,
            LoanApplicationDocumentType documentType,
            String actorUsername,
            String note,
            String fileName,
            String fileReference,
            String sourceReference,
            String contentType
    ) {
        getApplicationForLsp(lspId, applicationId);
        return updateDocumentChecklistItem(
                applicationId,
                documentType,
                actorUsername,
                LoanApplicationDocumentChecklistStatus.SUBMITTED,
                note,
                fileName,
                fileReference,
                sourceReference,
                contentType
        );
    }

    @Transactional
    public LoanApplication createApplication(String actorUsername, LoanApplicationOnboardingCommand command) {
        return loanApplicationLifecycleService.createApplication(actorUsername, command);
    }

    @Transactional
    public LoanApplication transitionStatus(
            UUID applicationId,
            String actorUsername,
            LoanApplicationStatus targetStatus,
            String note,
            LoanApplicationStatusReasonCode reasonCode
    ) {
        return loanApplicationLifecycleService.transitionStatus(applicationId, actorUsername, targetStatus, note, reasonCode);
    }

    @Transactional
    public LoanApplication manuallyOverrideStatus(
            UUID applicationId,
            String actorUsername,
            LoanApplicationStatus targetStatus,
            String note,
            LoanApplicationStatusReasonCode reasonCode
    ) {
        return loanApplicationLifecycleService.manuallyOverrideStatus(
                applicationId,
                actorUsername,
                targetStatus,
                note,
                reasonCode
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
        return loanApplicationLifecycleService.invalidateApplicationForLsp(
                lspId,
                applicationId,
                actorUsername,
                invalidReason,
                invalidReasonText
        );
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
            String contentType
    ) {
        return updateDocumentChecklistItem(
                applicationId,
                documentType,
                actorUsername,
                status,
                note,
                fileName,
                fileReference,
                sourceReference,
                contentType,
                null,
                null,
                null,
                false
        );
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
        return loanApplicationLifecycleService.updateDocumentChecklistItem(
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
        return loanApplicationLifecycleService.autoApproveIfEligibleForLsp(applicationId, actorUsername);
    }

    @Transactional
    public LoanApplication initiateDisbursement(
            UUID applicationId,
            String actorUsername
    ) {
        LoanAccount loanAccount = getRequiredLoanAccount(applicationId);
        return initiateDisbursement(
                applicationId,
                actorUsername,
                scaleCurrency(loanAccount.getPrincipalAmount())
        );
    }

    @Transactional
    public LoanApplication initiateDisbursement(
            UUID applicationId,
            String actorUsername,
            BigDecimal disbursementAmount
    ) {
        LoanApplication application = getApplication(applicationId);
        if (application.getStatus() != LoanApplicationStatus.APPROVED_PENDING_DISBURSAL
                && application.getStatus() != LoanApplicationStatus.DISBURSEMENT_RETRY) {
            throw new IllegalArgumentException("Disbursement can only be requested for applications pending disbursal or disbursement retry.");
        }
        loanApplicationLifecycleService.validateRequiredDocumentsUploadedBeforeDisbursement(applicationId);

        LoanAccount loanAccount = getRequiredLoanAccount(applicationId);
        if (loanAccount.getStatus() != LoanAccountStatus.PENDING_DISBURSEMENT
                && loanAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_FAILED
                && loanAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION) {
            throw new IllegalArgumentException("Disbursement has already been requested for this loan account.");
        }

        BigDecimal scaledDisbursementAmount = scaleCurrency(requireCurrency(disbursementAmount, "Disbursement amount"));
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
                normalizeActorUsername(actorUsername),
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
                loanApplicationLifecycleService.buildDisbursementPayload(application, loanAccount)
        );
        return application;
    }

    @Transactional
    public LoanApplication resolveMockDisbursementOutcome(
            UUID applicationId,
            String actorUsername,
            MockDisbursementOutcome outcome
    ) {
        if (outcome == null) {
            throw new IllegalArgumentException("Disbursement outcome is required.");
        }

        LoanApplication application = getApplication(applicationId);
        LoanAccount loanAccount = getRequiredLoanAccount(applicationId);
        if (loanAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_REQUESTED) {
            throw new IllegalArgumentException("Mock disbursement outcome can only be applied after a request is raised.");
        }

        LoanDisbursementRequestLog latestRequest = loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(loanAccount.getId())
                .orElseThrow(() -> new IllegalArgumentException(
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
                    loanApplicationLifecycleService.buildDisbursementPayload(application, loanAccount)
            );
        }
        return application;
    }

    @Transactional
    public LoanPaymentTransaction recordPaymentTransaction(
            UUID applicationId,
            String actorUsername,
            String idempotencyKey,
            UUID targetInstallmentId,
            BigDecimal amount,
            LocalDate postedAt,
            String reference,
            LoanPaymentChannel channel
    ) {
        return loanRepaymentCommandService.recordPaymentTransaction(
                applicationId,
                actorUsername,
                idempotencyKey,
                targetInstallmentId,
                amount,
                postedAt,
                reference,
                channel
        );
    }

    @Transactional
    public LoanForeclosureQuote requestForeclosureQuote(UUID applicationId, String actorUsername, LocalDate effectiveDate) {
        return loanForeclosureCommandService.requestForeclosureQuote(applicationId, actorUsername, effectiveDate);
    }

    @Transactional
    public LoanForeclosureQuote requestForeclosureQuoteForLsp(
            UUID lspId,
            UUID loanAccountId,
            String actorUsername,
            LocalDate effectiveDate
    ) {
        return loanForeclosureCommandService.requestForeclosureQuoteForLsp(
                lspId,
                loanAccountId,
                actorUsername,
                effectiveDate
        );
    }

    @Transactional
    public LoanForeclosureQuote executeForeclosureQuote(
            UUID applicationId,
            UUID quoteId,
            String actorUsername,
            LocalDate settlementDate,
            String reference,
            String note
    ) {
        return loanForeclosureCommandService.executeForeclosureQuote(
                applicationId,
                quoteId,
                actorUsername,
                settlementDate,
                reference,
                note
        );
    }

    @Transactional
    public List<LoanApplicationIntakeAudit> listIntakeAudits(UUID applicationId, String actorUsername) {
        LoanApplication application = getApplication(applicationId);
        loanApplicationDocumentAccessAuditRepository.save(new LoanApplicationDocumentAccessAudit(
                application,
                LoanApplicationDocumentAccessAuditAction.INTAKE_AUDITS_VIEWED,
                normalizeActorUsername(actorUsername),
                "Viewed intake audit payloads",
                List.of(),
                CorrelationIdHolder.get()
        ));
        return loanApplicationIntakeAuditRepository.findTop10ByLoanApplication_IdOrderByCreatedAtDesc(applicationId);
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static BigDecimal requireCurrency(BigDecimal value, String fieldName) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than zero.");
        }
        return value;
    }

    private static String normalizeActorUsername(String actorUsername) {
        if (actorUsername == null) {
            return "system";
        }

        String normalized = actorUsername.trim();
        return normalized.isBlank() ? "system" : normalized;
    }

    private LoanDelinquencySummary buildDelinquencySummary(List<LoanRepaymentScheduleInstallment> installments) {
        LocalDate today = currentBusinessDate();
        int maxDaysPastDue = installments.stream()
                .mapToInt(installment -> calculateDaysPastDue(installment, today))
                .max()
                .orElse(0);
        BigDecimal overdueAmount = installments.stream()
                .filter(installment -> calculateDaysPastDue(installment, today) > 0)
                .map(LoanRepaymentScheduleInstallment::getOutstandingAmount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        long overdueInstallmentCount = installments.stream()
                .filter(installment -> calculateDaysPastDue(installment, today) > 0)
                .count();
        return new LoanDelinquencySummary(
                maxDaysPastDue,
                resolveDelinquencyBucket(maxDaysPastDue),
                Math.toIntExact(overdueInstallmentCount),
                scaleCurrency(overdueAmount)
        );
    }

    private LoanAccount getRequiredLoanAccount(UUID applicationId) {
        return loanAccountRepository.findDetailedByLoanApplication_Id(applicationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Loan account is not available for application id: " + applicationId
                ));
    }

    private static String providerStatusFor(MockDisbursementOutcome outcome) {
        return switch (outcome) {
            case DISBURSED -> "DISBURSED";
            case FAILED -> "FAILED";
            case PENDING_RECONCILIATION -> "PENDING_RECONCILIATION";
        };
    }

    private static BigDecimal scaleCurrency(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public static int calculateDaysPastDue(LoanRepaymentScheduleInstallment installment, LocalDate today) {
        if (installment.getOutstandingAmount().compareTo(BigDecimal.ZERO) <= 0
                || !installment.getDueDate().isBefore(today)) {
            return 0;
        }
        return Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(installment.getDueDate(), today));
    }

    public static LocalDate currentBusinessDate() {
        return LocalDate.now(ZoneId.systemDefault());
    }

    public static LoanDelinquencyBucket resolveDelinquencyBucket(int daysPastDue) {
        if (daysPastDue <= 0) {
            return LoanDelinquencyBucket.CURRENT;
        }
        if (daysPastDue <= 30) {
            return LoanDelinquencyBucket.DPD_1_30;
        }
        if (daysPastDue <= 60) {
            return LoanDelinquencyBucket.DPD_31_60;
        }
        if (daysPastDue <= 90) {
            return LoanDelinquencyBucket.DPD_61_90;
        }
        return LoanDelinquencyBucket.DPD_90_PLUS;
    }

    private boolean hasMeaningfulDocumentActivity(LoanApplicationDocumentChecklist checklistItem) {
        return checklistItem.getUpdatedAt() != null
                && checklistItem.getCreatedAt() != null
                && checklistItem.getUpdatedAt().isAfter(checklistItem.getCreatedAt());
    }

    private static String resolveDocumentActivityDetail(LoanApplicationDocumentChecklist checklistItem) {
        return checklistItem.getNote();
    }

    public boolean hasAllRequiredLmsManagedDocuments(UUID applicationId, boolean requireForApprovalOnly) {
        return loanApplicationLifecycleService.hasAllRequiredLmsManagedDocuments(applicationId, requireForApprovalOnly);
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
        payload.put("resolvedBy", normalizeActorUsername(actorUsername));
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

    private record ActivityCandidate(int priority, LoanApplicationLastActivity activity) {
    }

    public record LoanApplicationLastActivity(
            String activityType,
            String actorUsername,
            String summary,
            String detail,
            String correlationId,
            Instant occurredAt
    ) {
    }

    public record LoanRepaymentScheduleSummary(
            int installmentCount,
            BigDecimal installmentAmount,
            LocalDate firstDueDate,
            LocalDate finalDueDate
    ) {
    }

    public record LoanDelinquencySummary(
            int maxDaysPastDue,
            LoanDelinquencyBucket bucket,
            int overdueInstallmentCount,
            BigDecimal overdueAmount
    ) {
    }

}
