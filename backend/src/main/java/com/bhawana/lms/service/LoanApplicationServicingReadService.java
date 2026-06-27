package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.util.Strings;
import com.bhawana.lms.common.api.error.DocumentNotFoundException;
import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import com.bhawana.lms.config.BusinessCalendar;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationAuditEvent;
import com.bhawana.lms.domain.LoanApplicationDocumentAccessAudit;
import com.bhawana.lms.domain.LoanApplicationDocumentAccessAuditAction;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.domain.LoanApplicationIntakeAudit;
import com.bhawana.lms.domain.LoanApplicationStatusTransition;
import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import com.bhawana.lms.domain.LoanForeclosureQuote;
import com.bhawana.lms.domain.LoanPaymentTransaction;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationAuditEventRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentAccessAuditRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationIntakeAuditRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.bhawana.lms.repo.LoanForeclosureQuoteRepository;
import com.bhawana.lms.repo.LoanPaymentTransactionRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanApplicationServicingReadService {

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
    private final LoanApplicationQueryService loanApplicationQueryService;
    private final LoanApplicationDocumentChecklistService documentChecklistService;
    private final LoanDocumentService loanDocumentService;
    private final WebhookOutboxService webhookOutboxService;
    private final com.bhawana.lms.repo.WebhookEventDeliveryAttemptRepository webhookEventDeliveryAttemptRepository;
    private final BusinessCalendar businessCalendar;
    private final org.springframework.transaction.support.TransactionTemplate documentAccessAuditTransactionTemplate;

    public LoanApplicationServicingReadService(
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
            LoanApplicationQueryService loanApplicationQueryService,
            LoanApplicationDocumentChecklistService documentChecklistService,
            LoanDocumentService loanDocumentService,
            WebhookOutboxService webhookOutboxService,
            com.bhawana.lms.repo.WebhookEventDeliveryAttemptRepository webhookEventDeliveryAttemptRepository,
            BusinessCalendar businessCalendar,
            org.springframework.transaction.PlatformTransactionManager transactionManager
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
        this.loanApplicationQueryService = loanApplicationQueryService;
        this.documentChecklistService = documentChecklistService;
        this.loanDocumentService = loanDocumentService;
        this.webhookOutboxService = webhookOutboxService;
        this.webhookEventDeliveryAttemptRepository = webhookEventDeliveryAttemptRepository;
        this.businessCalendar = businessCalendar;
        this.documentAccessAuditTransactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
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
    public Optional<LoanApplicationLastActivity> getLatestActivity(UUID applicationId) {
        LoanApplication application = loanApplicationQueryService.getApplication(applicationId);

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
                                        item.getNote(),
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
        loanApplicationQueryService.getApplication(applicationId);
        return loanApplicationStatusTransitionRepository.findTop20ByLoanApplication_IdOrderByCreatedAtDesc(applicationId);
    }

    @Transactional(readOnly = true)
    public List<LoanApplicationAuditEvent> listAuditEvents(UUID applicationId) {
        loanApplicationQueryService.getApplication(applicationId);
        return loanApplicationAuditEventRepository.findTop25ByLoanApplication_IdOrderByCreatedAtDesc(applicationId);
    }

    @Transactional(readOnly = true)
    public Optional<LoanAccount> getLoanAccount(UUID applicationId) {
        loanApplicationQueryService.getApplication(applicationId);
        return loanAccountRepository.findDetailedByLoanApplication_Id(applicationId);
    }

    @Transactional(readOnly = true)
    public Optional<LoanRepaymentScheduleSummary> getLoanRepaymentScheduleSummary(UUID applicationId) {
        return getLoanAccount(applicationId)
                .map(account -> loanRepaymentScheduleInstallmentRepository
                        .findByLoanAccount_IdOrderByInstallmentNumberAsc(account.getId()))
                .flatMap(LoanRepaymentScheduleSummary::fromInstallments);
    }

    @Transactional(readOnly = true)
    public Optional<LoanDelinquencySummary> getLoanDelinquencySummary(UUID applicationId) {
        return getLoanAccount(applicationId)
                .map(account -> loanRepaymentScheduleInstallmentRepository
                        .findByLoanAccount_IdOrderByInstallmentNumberAsc(account.getId()))
                .flatMap(installments -> LoanDelinquencySupport.summarize(installments, businessCalendar.today()));
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
            throw new ResourceNotFoundException("Loan application not found: " + applicationId);
        }
        List<com.bhawana.lms.domain.WebhookEventOutbox> events =
                webhookOutboxService.listOutboxForLoanApplication(applicationId);
        Map<UUID, Integer> latestResponseCodes = resolveLatestDeliveryResponseCodes(events);
        return events.stream()
                .map(event -> toWebhookEventProjection(
                        event,
                        latestResponseCodes.get(event.getId())
                ))
                .toList();
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
    public LoanApplicationDocumentChecklist getDocumentChecklistItem(
            UUID applicationId,
            LoanApplicationDocumentType documentType
    ) {
        loanApplicationQueryService.getApplication(applicationId);
        return loanApplicationDocumentChecklistRepository.findByLoanApplication_IdAndDocumentType(applicationId, documentType)
                .orElseThrow(() -> new DocumentNotFoundException(
                        "Document checklist item not found for type " + documentType.name()
                                + " on application " + applicationId
                ));
    }

    @Transactional
    public List<LoanApplicationDocumentChecklist> listDocumentChecklist(UUID applicationId, String actorUsername) {
        LoanApplication application = loanApplicationQueryService.getApplication(applicationId);
        documentChecklistService.ensureDocumentChecklist(application);
        List<LoanApplicationDocumentChecklist> checklist =
                loanApplicationDocumentChecklistRepository.findByLoanApplication_IdOrderByCreatedAtAsc(applicationId);
        loanApplicationDocumentAccessAuditRepository.save(new LoanApplicationDocumentAccessAudit(
                application,
                LoanApplicationDocumentAccessAuditAction.CHECKLIST_VIEWED,
                Strings.normalizeActor(actorUsername),
                "Viewed " + checklist.size() + " KYC documents",
                checklist.stream().map(LoanApplicationDocumentChecklist::getDocumentType).toList(),
                CorrelationIdHolder.get()
        ));
        return checklist;
    }

    @Transactional(readOnly = true)
    public List<LoanApplicationDocumentAccessAudit> listDocumentAccessAudits(UUID applicationId) {
        loanApplicationQueryService.getApplication(applicationId);
        return loanApplicationDocumentAccessAuditRepository.findTop20ByLoanApplication_IdOrderByCreatedAtDesc(applicationId);
    }

    /**
     * Open a single stored document for inline preview ({@code inlinePreview=true})
     * or download ({@code inlinePreview=false}) and record the matching access audit
     * row ({@code SINGLE_DOCUMENT_PREVIEWED} vs {@code SINGLE_DOCUMENT_DOWNLOADED}).
     *
     * <p>The bytes are streamed rather than buffered into heap, and the object-storage
     * round-trip happens outside any database transaction — only the short audit
     * insert runs transactionally — so a pooled DB connection is never held while the
     * (potentially large/slow) document is read. The inline-preview allowlist is
     * enforced and existence/availability failures surface <em>before</em> the audit
     * row is written, so a rejected/missing document is never logged as a successful
     * access. The caller (the MVC layer) must close the returned stream once the
     * response body has been written; if the audit write fails the stream is closed
     * here and the failure is propagated, so access is denied without leaking it.
     */
    public LoanDocumentService.StreamedDocumentContent accessDocumentContent(
            UUID applicationId,
            LoanApplicationDocumentType documentType,
            boolean inlinePreview,
            String actorUsername,
            String actorIp,
            String correlationId
    ) {
        LoanDocumentService.StreamedDocumentContent content =
                loanDocumentService.openDocumentStream(applicationId, documentType, inlinePreview);
        try {
            recordDocumentAccessAudit(
                    applicationId,
                    documentType,
                    inlinePreview,
                    actorUsername,
                    actorIp,
                    correlationId,
                    content.contentLength()
            );
        } catch (RuntimeException auditFailure) {
            closeQuietly(content);
            throw auditFailure;
        }
        return content;
    }

    private void recordDocumentAccessAudit(
            UUID applicationId,
            LoanApplicationDocumentType documentType,
            boolean inlinePreview,
            String actorUsername,
            String actorIp,
            String correlationId,
            long byteCount
    ) {
        LoanApplicationDocumentAccessAuditAction action = inlinePreview
                ? LoanApplicationDocumentAccessAuditAction.SINGLE_DOCUMENT_PREVIEWED
                : LoanApplicationDocumentAccessAuditAction.SINGLE_DOCUMENT_DOWNLOADED;
        String verb = inlinePreview ? "Previewed " : "Downloaded ";
        documentAccessAuditTransactionTemplate.executeWithoutResult(status -> {
            LoanApplication application = loanApplicationQueryService.getApplication(applicationId);
            loanApplicationDocumentAccessAuditRepository.save(new LoanApplicationDocumentAccessAudit(
                    application,
                    action,
                    Strings.normalizeActor(actorUsername),
                    verb + documentType.name(),
                    List.of(documentType),
                    correlationId,
                    actorIp,
                    byteCount
            ));
        });
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (java.io.IOException ignored) {
            // Best-effort cleanup; the caller propagates the original failure.
        }
    }

    @Transactional
    public LoanDocumentService.ZipBuildResult downloadDocumentZip(
            UUID applicationId,
            String actorUsername,
            String actorIp,
            String correlationId
    ) {
        LoanApplication application = loanApplicationQueryService.getApplication(applicationId);
        LoanDocumentService.ZipBuildResult zipResult = loanDocumentService.buildDocumentZip(applicationId);
        loanApplicationDocumentAccessAuditRepository.save(new LoanApplicationDocumentAccessAudit(
                application,
                LoanApplicationDocumentAccessAuditAction.BULK_ZIP_DOWNLOADED,
                Strings.normalizeActor(actorUsername),
                "Downloaded ZIP with " + zipResult.includedTypes().size() + " documents",
                zipResult.includedTypes(),
                correlationId,
                actorIp,
                (long) zipResult.content().length
        ));
        return zipResult;
    }

    @Transactional
    public List<LoanApplicationIntakeAudit> listIntakeAudits(UUID applicationId, String actorUsername) {
        LoanApplication application = loanApplicationQueryService.getApplication(applicationId);
        loanApplicationDocumentAccessAuditRepository.save(new LoanApplicationDocumentAccessAudit(
                application,
                LoanApplicationDocumentAccessAuditAction.INTAKE_AUDITS_VIEWED,
                Strings.normalizeActor(actorUsername),
                "Viewed intake audit payloads",
                List.of(),
                CorrelationIdHolder.get()
        ));
        return loanApplicationIntakeAuditRepository.findTop10ByLoanApplication_IdOrderByCreatedAtDesc(applicationId);
    }

    private LoanAccount getRequiredLoanAccount(UUID applicationId) {
        return loanAccountRepository.findDetailedByLoanApplication_Id(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan account is not available for application id: " + applicationId
                ));
    }

    private Map<UUID, Integer> resolveLatestDeliveryResponseCodes(
            List<com.bhawana.lms.domain.WebhookEventOutbox> events
    ) {
        if (events.isEmpty()) {
            return Map.of();
        }
        List<UUID> outboxEventIds = events.stream()
                .map(com.bhawana.lms.domain.WebhookEventOutbox::getId)
                .toList();
        Map<UUID, Integer> latestResponseCodes = new HashMap<>();
        for (com.bhawana.lms.domain.WebhookEventDeliveryAttempt attempt
                : webhookEventDeliveryAttemptRepository.findByOutboxEvent_IdInOrderByCreatedAtDesc(outboxEventIds)) {
            UUID outboxEventId = attempt.getOutboxEvent().getId();
            latestResponseCodes.putIfAbsent(outboxEventId, attempt.getResponseStatusCode());
        }
        return latestResponseCodes;
    }

    private LoanApplicationWebhookEventProjection toWebhookEventProjection(
            com.bhawana.lms.domain.WebhookEventOutbox event,
            Integer responseCode
    ) {
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
            case PENDING, IN_FLIGHT -> "PENDING";
            case DELIVERED -> "DELIVERED";
            case RETRYABLE_FAILURE -> "FAILED";
            case PERMANENT_FAILURE -> "DEAD_LETTERED";
        };
    }

    private boolean hasMeaningfulDocumentActivity(LoanApplicationDocumentChecklist checklistItem) {
        return checklistItem.getUpdatedAt() != null
                && checklistItem.getCreatedAt() != null
                && checklistItem.getUpdatedAt().isAfter(checklistItem.getCreatedAt());
    }

    private record ActivityCandidate(int priority, LoanApplicationLastActivity activity) {
    }
}
