package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.web.KycCompletionRequiredException;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.LoanDelinquencyBucket;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountClosureReason;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationAuditAction;
import com.bhawana.lms.domain.LoanApplicationAuditEvent;
import com.bhawana.lms.domain.LoanApplicationAssignmentEvent;
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
import com.bhawana.lms.domain.LoanPaymentChannel;
import com.bhawana.lms.domain.LoanPaymentStatus;
import com.bhawana.lms.domain.LoanPaymentTransaction;
import com.bhawana.lms.domain.LoanForeclosureQuote;
import com.bhawana.lms.domain.LoanForeclosureQuoteStatus;
import com.bhawana.lms.domain.MockDisbursementOutcome;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallmentStatus;
import com.bhawana.lms.domain.LoanProductLspMapping;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationAuditEventRepository;
import com.bhawana.lms.repo.LoanApplicationAssignmentEventRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentAccessAuditRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationIntakeAuditRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.LoanPaymentTransactionRepository;
import com.bhawana.lms.repo.LoanForeclosureQuoteRepository;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanApplicationService {

    private final AppUserRepository appUserRepository;
    private final BorrowerRepository borrowerRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final LoanApplicationAuditEventRepository loanApplicationAuditEventRepository;
    private final LoanApplicationAssignmentEventRepository loanApplicationAssignmentEventRepository;
    private final LoanApplicationDocumentAccessAuditRepository loanApplicationDocumentAccessAuditRepository;
    private final LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    private final LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository;
    private final LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;
    private final LoanPaymentTransactionRepository loanPaymentTransactionRepository;
    private final LoanForeclosureQuoteRepository loanForeclosureQuoteRepository;
    private final LoanProductRepository loanProductRepository;
    private final LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;
    private final LspRepository lspRepository;
    private final LoanProductLspMappingRepository loanProductLspMappingRepository;
    private final LoanDisbursementAdapter loanDisbursementAdapter;
    private final WebhookOutboxService webhookOutboxService;
    private final ObjectMapper objectMapper;

    public LoanApplicationService(
            AppUserRepository appUserRepository,
            BorrowerRepository borrowerRepository,
            LoanAccountRepository loanAccountRepository,
            LoanApplicationAuditEventRepository loanApplicationAuditEventRepository,
            LoanApplicationAssignmentEventRepository loanApplicationAssignmentEventRepository,
            LoanApplicationDocumentAccessAuditRepository loanApplicationDocumentAccessAuditRepository,
            LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository,
            LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository,
            LoanApplicationRepository loanApplicationRepository,
            LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository,
            LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository,
            LoanPaymentTransactionRepository loanPaymentTransactionRepository,
            LoanForeclosureQuoteRepository loanForeclosureQuoteRepository,
            LoanProductRepository loanProductRepository,
            LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository,
            LspRepository lspRepository,
            LoanProductLspMappingRepository loanProductLspMappingRepository,
            LoanDisbursementAdapter loanDisbursementAdapter,
            WebhookOutboxService webhookOutboxService,
            ObjectMapper objectMapper
    ) {
        this.appUserRepository = appUserRepository;
        this.borrowerRepository = borrowerRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanApplicationAuditEventRepository = loanApplicationAuditEventRepository;
        this.loanApplicationAssignmentEventRepository = loanApplicationAssignmentEventRepository;
        this.loanApplicationDocumentAccessAuditRepository = loanApplicationDocumentAccessAuditRepository;
        this.loanApplicationDocumentChecklistRepository = loanApplicationDocumentChecklistRepository;
        this.loanApplicationIntakeAuditRepository = loanApplicationIntakeAuditRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanApplicationStatusTransitionRepository = loanApplicationStatusTransitionRepository;
        this.loanDisbursementRequestLogRepository = loanDisbursementRequestLogRepository;
        this.loanPaymentTransactionRepository = loanPaymentTransactionRepository;
        this.loanForeclosureQuoteRepository = loanForeclosureQuoteRepository;
        this.loanProductRepository = loanProductRepository;
        this.loanRepaymentScheduleInstallmentRepository = loanRepaymentScheduleInstallmentRepository;
        this.lspRepository = lspRepository;
        this.loanProductLspMappingRepository = loanProductLspMappingRepository;
        this.loanDisbursementAdapter = loanDisbursementAdapter;
        this.webhookOutboxService = webhookOutboxService;
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
        String normalizedQuery = normalizeQuery(query);
        String normalizedStatus = normalizeOptional(status);
        String normalizedSourceChannel = normalizeOptional(sourceChannel);
        validateDateRange(disbursalDateFrom, disbursalDateTo);
        Map<UUID, LocalDate> disbursedDatesByApplicationId = loanAccountRepository.findAll().stream()
                .filter(account -> account.getLoanApplication() != null && account.getDisbursedAt() != null)
                .collect(Collectors.toMap(
                        account -> account.getLoanApplication().getId(),
                        account -> account.getDisbursedAt().atZone(ZoneOffset.UTC).toLocalDate(),
                        (left, right) -> right
                ));
        return loanApplicationRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(application -> lspId == null || application.getLsp().getId().equals(lspId))
                .filter(application -> productId == null || application.getLoanProduct().getId().equals(productId))
                .filter(application -> normalizedStatus == null
                        || application.getStatus().name().equalsIgnoreCase(normalizedStatus))
                .filter(application -> normalizedSourceChannel == null
                        || application.getSourceChannel().equalsIgnoreCase(normalizedSourceChannel))
                .filter(application -> normalizedQuery == null || matchesQuery(application, normalizedQuery))
                .filter(application -> isWithinDisbursalRange(
                        disbursedDatesByApplicationId.get(application.getId()),
                        disbursalDateFrom,
                        disbursalDateTo
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LoanApplication> listApplicationsForLsp(
            UUID lspId,
            UUID productId,
            String status,
            String sourceChannel,
            String query
    ) {
        return listApplications(lspId, productId, status, sourceChannel, query, null, null);
    }

    @Transactional(readOnly = true)
    public LoanApplication getApplication(UUID applicationId) {
        return loanApplicationRepository.findById(applicationId)
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
        return loanApplicationRepository.findByLsp_IdAndExternalLoanIdIgnoreCase(lspId, normalizedExternalLoanId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown external loan id for the authenticated LSP: " + normalizedExternalLoanId
                ));
    }

    @Transactional(readOnly = true)
    public LoanAccount getLoanAccountForLsp(UUID lspId, UUID loanAccountId) {
        LoanAccount loanAccount = loanAccountRepository.findById(loanAccountId)
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
                loanApplicationAssignmentEventRepository.findTopByLoanApplication_IdOrderByCreatedAtDesc(applicationId)
                        .map(event -> new ActivityCandidate(
                                2,
                                new LoanApplicationLastActivity(
                                        "ASSIGNMENT_UPDATED",
                                        event.getActorUsername(),
                                        event.getToAssigneeUsername() == null
                                                ? "Released back to the shared queue"
                                                : "Assigned to " + event.getToAssigneeUsername(),
                                        event.getNote(),
                                        event.getCorrelationId(),
                                        event.getCreatedAt()
                                )
                        )),
                loanApplicationDocumentChecklistRepository.findTopByLoanApplication_IdOrderByUpdatedAtDesc(applicationId)
                        .filter(this::hasMeaningfulDocumentActivity)
                        .map(item -> new ActivityCandidate(
                                3,
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
        return loanAccountRepository.findByLoanApplication_Id(applicationId);
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
    public List<LoanApplicationAssignmentEvent> listAssignmentEvents(UUID applicationId) {
        getApplication(applicationId);
        return loanApplicationAssignmentEventRepository.findTop20ByLoanApplication_IdOrderByCreatedAtDesc(applicationId);
    }

    @Transactional
    public List<LoanApplicationDocumentChecklist> listDocumentChecklist(UUID applicationId, String actorUsername) {
        LoanApplication application = getApplication(applicationId);
        ensureDocumentChecklist(application);
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
                LoanApplicationDocumentChecklistStatus.RECEIVED,
                note,
                fileName,
                fileReference,
                sourceReference,
                contentType,
                null,
                null
        );
    }

    @Transactional
    public LoanApplication createApplication(
            String actorUsername,
            UUID lspId,
            UUID productId,
            String externalLoanId,
            String sourceChannel,
            String borrowerPan,
            String borrowerFullName,
            String borrowerMobile,
            String borrowerEmail,
            LocalDate borrowerDateOfBirth,
            String borrowerCity,
            String borrowerState,
            String borrowerEmploymentType,
            BigDecimal borrowerMonthlyIncome,
            BigDecimal requestedAmount,
            int tenureMonths
    ) {
        var lsp = lspRepository.findById(lspId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown LSP id: " + lspId));
        if (lsp.getStatus() != LspStatus.ACTIVE) {
            throw new IllegalArgumentException("Loan applications can only be created for active LSPs.");
        }

        var loanProduct = loanProductRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown loan product id: " + productId));
        if (loanProduct.getStatus() != LoanProductStatus.ACTIVE) {
            throw new IllegalArgumentException("Loan applications can only be created for active loan products.");
        }

        LoanProductLspMapping mapping = loanProductLspMappingRepository.findByLsp_IdAndLoanProduct_Id(lspId, productId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Requested product is not mapped to the selected LSP."
                ));
        if (!mapping.isEnabled()) {
            throw new IllegalArgumentException("Requested product mapping is disabled for the selected LSP.");
        }

        String normalizedExternalLoanId = externalLoanId.trim();
        if (loanApplicationRepository.existsByLsp_IdAndExternalLoanIdIgnoreCase(lspId, normalizedExternalLoanId)) {
            throw new IllegalArgumentException("External loan id already exists for the selected LSP.");
        }

        BigDecimal scaledRequestedAmount = requestedAmount.setScale(2, java.math.RoundingMode.HALF_UP);
        if (scaledRequestedAmount.compareTo(loanProduct.getMinPrincipal()) < 0
                || scaledRequestedAmount.compareTo(loanProduct.getMaxPrincipal()) > 0) {
            throw new IllegalArgumentException("Requested amount is outside the configured product principal range.");
        }

        if (tenureMonths < loanProduct.getMinTenureMonths() || tenureMonths > loanProduct.getMaxTenureMonths()) {
            throw new IllegalArgumentException("Requested tenure is outside the configured product tenure range.");
        }

        Borrower borrower = borrowerRepository.findByPanIgnoreCase(normalizePan(borrowerPan))
                .map(existing -> {
                    existing.refreshProfile(
                            borrowerFullName.trim(),
                            borrowerMobile.trim(),
                            normalizeEmail(borrowerEmail),
                            borrowerDateOfBirth,
                            borrowerCity,
                            borrowerState,
                            borrowerEmploymentType,
                            normalizeMonthlyIncome(borrowerMonthlyIncome)
                    );
                    return borrowerRepository.save(existing);
                })
                .orElseGet(() -> borrowerRepository.save(new Borrower(
                        borrowerFullName.trim(),
                        normalizePan(borrowerPan),
                        borrowerMobile.trim(),
                        normalizeEmail(borrowerEmail),
                        borrowerDateOfBirth,
                        borrowerCity,
                        borrowerState,
                        borrowerEmploymentType,
                        normalizeMonthlyIncome(borrowerMonthlyIncome)
                )));

        LoanApplication application = new LoanApplication(
                borrower,
                lsp,
                loanProduct,
                normalizedExternalLoanId,
                normalizeSourceChannel(sourceChannel),
                scaledRequestedAmount,
                tenureMonths,
                LoanApplicationStatus.RECEIVED
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
                    "Cannot transition loan application from "
                            + currentStatus.name()
                            + " to "
                            + targetStatus.name()
                            + "."
            );
        }

        if (currentStatus == LoanApplicationStatus.UNDER_REVIEW
                && targetStatus == LoanApplicationStatus.APPROVED) {
            validateKycCompletionBeforeApproval(applicationId);
        }

        LoanApplicationStatusReasonCode resolvedReasonCode = validateTransitionReasonCode(targetStatus, reasonCode);
        String resolvedNote = resolveTransitionNote(note, currentStatus, targetStatus);
        application.transitionTo(targetStatus);
        LoanApplication savedApplication = loanApplicationRepository.save(application);
        loanApplicationStatusTransitionRepository.save(new LoanApplicationStatusTransition(
                savedApplication,
                currentStatus,
                targetStatus,
                normalizeActorUsername(actorUsername),
                resolvedNote,
                resolvedReasonCode,
                CorrelationIdHolder.get()
        ));
        recordAuditEvent(
                savedApplication,
                LoanApplicationAuditAction.STATUS_TRANSITION,
                currentStatus,
                targetStatus,
                actorUsername,
                resolvedNote,
                resolvedReasonCode
        );
        if (targetStatus == LoanApplicationStatus.APPROVED) {
            ensureLoanAccountForApprovedApplication(savedApplication);
        }
        webhookOutboxService.enqueueIfSubscribed(
                savedApplication.getLsp(),
                WebhookEventType.LOAN_STATUS_CHANGED,
                "LOAN_APPLICATION",
                savedApplication.getId().toString(),
                buildLoanStatusChangedPayload(savedApplication, currentStatus, targetStatus, resolvedReasonCode)
        );
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

        if (currentStatus == LoanApplicationStatus.APPROVED) {
            throw new IllegalArgumentException("Approved loan applications cannot be manually overridden.");
        }

        if (targetStatus == LoanApplicationStatus.APPROVED) {
            throw new IllegalArgumentException("Use the standard approval flow instead of a manual status update.");
        }

        if (targetStatus != LoanApplicationStatus.RECEIVED
                && targetStatus != LoanApplicationStatus.UNDER_REVIEW
                && targetStatus != LoanApplicationStatus.HOLD
                && targetStatus != LoanApplicationStatus.REJECTED) {
            throw new IllegalArgumentException("Manual status updates are not supported for " + targetStatus.name() + ".");
        }

        LoanApplicationStatusReasonCode resolvedReasonCode = requireReasonCode(
                reasonCode,
                "Manual status reason code is required."
        );
        String resolvedNote = "Manual override: " + requireNote(note);
        application.transitionTo(targetStatus);
        LoanApplication savedApplication = loanApplicationRepository.save(application);
        loanApplicationStatusTransitionRepository.save(new LoanApplicationStatusTransition(
                savedApplication,
                currentStatus,
                targetStatus,
                normalizeActorUsername(actorUsername),
                resolvedNote,
                resolvedReasonCode,
                CorrelationIdHolder.get()
        ));
        recordAuditEvent(
                savedApplication,
                LoanApplicationAuditAction.MANUAL_STATUS_OVERRIDE,
                currentStatus,
                targetStatus,
                actorUsername,
                resolvedNote,
                resolvedReasonCode
        );
        webhookOutboxService.enqueueIfSubscribed(
                savedApplication.getLsp(),
                WebhookEventType.LOAN_STATUS_CHANGED,
                "LOAN_APPLICATION",
                savedApplication.getId().toString(),
                buildLoanStatusChangedPayload(savedApplication, currentStatus, targetStatus, resolvedReasonCode)
        );
        return savedApplication;
    }

    @Transactional
    public LoanApplication assignApplication(
            UUID applicationId,
            String actorUsername,
            String assigneeUsername,
            String note
    ) {
        LoanApplication application = getApplication(applicationId);
        if (application.getStatus() == LoanApplicationStatus.APPROVED
                || application.getStatus() == LoanApplicationStatus.REJECTED) {
            throw new IllegalArgumentException("Assignment is only available for active review queue items.");
        }

        String normalizedActor = normalizeActorUsername(actorUsername);
        String normalizedAssignee = normalizeAssigneeUsername(assigneeUsername);
        String normalizedNote = normalizeOptional(note);
        String currentAssignee = application.getAssignedToUsername();

        if (normalizedAssignee == null) {
            if (currentAssignee == null) {
                throw new IllegalArgumentException("Loan application is not currently assigned.");
            }

            application.releaseAssignment();
            LoanApplication savedApplication = loanApplicationRepository.save(application);
            loanApplicationAssignmentEventRepository.save(new LoanApplicationAssignmentEvent(
                    savedApplication,
                    currentAssignee,
                    null,
                    normalizedActor,
                    normalizedNote == null ? "Released assignment" : normalizedNote,
                    CorrelationIdHolder.get()
            ));
            return savedApplication;
        }

        var assignee = appUserRepository.findByUsernameIgnoreCase(normalizedAssignee)
                .orElseThrow(() -> new IllegalArgumentException("Unknown assignee username: " + normalizedAssignee));
        if (assignee.getStatus() != UserStatus.ACTIVE) {
            throw new IllegalArgumentException("Loan applications can only be assigned to active users.");
        }

        if (normalizedAssignee.equalsIgnoreCase(currentAssignee)) {
            throw new IllegalArgumentException("Loan application is already assigned to " + normalizedAssignee + ".");
        }

        application.assignTo(assignee.getUsername(), normalizedActor);
        LoanApplication savedApplication = loanApplicationRepository.save(application);
        loanApplicationAssignmentEventRepository.save(new LoanApplicationAssignmentEvent(
                savedApplication,
                currentAssignee,
                assignee.getUsername(),
                normalizedActor,
                normalizedNote == null ? "Assigned application to " + assignee.getUsername() : normalizedNote,
                CorrelationIdHolder.get()
        ));
        return savedApplication;
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
            String reviewReason,
            String rejectionReason
    ) {
        LoanApplication application = getApplication(applicationId);
        ensureDocumentChecklist(application);

        LoanApplicationDocumentChecklist checklistItem = loanApplicationDocumentChecklistRepository
                .findByLoanApplication_IdAndDocumentType(applicationId, documentType)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown document checklist item: " + documentType.name()
                ));

        String normalizedReviewReason = normalizeOptional(reviewReason);
        String normalizedRejectionReason = normalizeOptional(rejectionReason);
        if (status == LoanApplicationDocumentChecklistStatus.VERIFIED && normalizedReviewReason == null) {
            throw new IllegalArgumentException("Review reason is required when a document is marked VERIFIED.");
        }
        if (status == LoanApplicationDocumentChecklistStatus.REJECTED && normalizedRejectionReason == null) {
            throw new IllegalArgumentException("Rejection reason is required when a document is marked REJECTED.");
        }

        checklistItem.update(
                status,
                note,
                normalizeActorUsername(actorUsername),
                fileName,
                fileReference,
                sourceReference,
                contentType,
                normalizedReviewReason,
                normalizedRejectionReason
        );
        return loanApplicationDocumentChecklistRepository.save(checklistItem);
    }

    @Transactional
    public LoanApplication initiateDisbursement(
            UUID applicationId,
            String actorUsername
    ) {
        LoanApplication application = getApplication(applicationId);
        if (application.getStatus() != LoanApplicationStatus.APPROVED) {
            throw new IllegalArgumentException("Disbursement can only be requested for approved loan applications.");
        }

        LoanAccount loanAccount = getRequiredLoanAccount(applicationId);
        if (loanAccount.getStatus() != LoanAccountStatus.PENDING_DISBURSEMENT) {
            throw new IllegalArgumentException("Disbursement has already been requested for this loan account.");
        }

        LoanDisbursementAdapter.DisbursementCommand command = new LoanDisbursementAdapter.DisbursementCommand(
                loanAccount.getAccountNumber(),
                scaleCurrency(loanAccount.getPrincipalAmount()),
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
                scaleCurrency(loanAccount.getPrincipalAmount()),
                result.providerName(),
                result.providerRequestId(),
                result.providerStatus(),
                serializeDisbursementRequest(command),
                result.responsePayloadJson(),
                CorrelationIdHolder.get()
        ));
        webhookOutboxService.enqueueIfSubscribed(
                application.getLsp(),
                WebhookEventType.LOAN_DISBURSEMENT_UPDATED,
                "LOAN_ACCOUNT",
                loanAccount.getId().toString(),
                buildDisbursementPayload(application, loanAccount)
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
        webhookOutboxService.enqueueIfSubscribed(
                application.getLsp(),
                WebhookEventType.LOAN_DISBURSEMENT_UPDATED,
                "LOAN_ACCOUNT",
                loanAccount.getId().toString(),
                buildDisbursementPayload(application, loanAccount)
        );
        return application;
    }

    @Transactional
    public LoanPaymentTransaction recordPaymentTransaction(
            UUID applicationId,
            String actorUsername,
            BigDecimal amount,
            LocalDate paymentDate,
            String reference,
            LoanPaymentChannel channel,
            LoanPaymentStatus status,
            String note
    ) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero.");
        }
        if (paymentDate == null) {
            throw new IllegalArgumentException("Payment date is required.");
        }
        if (channel == null) {
            throw new IllegalArgumentException("Payment channel is required.");
        }
        if (status == null) {
            throw new IllegalArgumentException("Payment status is required.");
        }

        LoanApplication application = getApplication(applicationId);
        if (application.getStatus() != LoanApplicationStatus.APPROVED) {
            throw new IllegalArgumentException("Payments can only be recorded for approved loan applications.");
        }

        LoanAccount loanAccount = getRequiredLoanAccount(applicationId);
        if (loanAccount.getStatus() != LoanAccountStatus.DISBURSED) {
            throw new IllegalArgumentException("Payments can only be recorded after the loan account is disbursed.");
        }

        LoanPaymentTransaction paymentTransaction = loanPaymentTransactionRepository.save(new LoanPaymentTransaction(
                loanAccount,
                normalizeActorUsername(actorUsername),
                scaleCurrency(amount),
                paymentDate,
                requireReference(reference),
                channel,
                status,
                normalizeNote(note),
                CorrelationIdHolder.get()
        ));
        recomputePaymentAllocation(loanAccount);
        LoanPaymentTransaction savedPaymentTransaction = loanPaymentTransactionRepository.findById(paymentTransaction.getId())
                .orElse(paymentTransaction);
        synchronizeLoanAccountClosureState(
                application,
                loanAccount,
                normalizeActorUsername(actorUsername),
                LoanAccountClosureReason.FULLY_REPAID
        );
        webhookOutboxService.enqueueIfSubscribed(
                application.getLsp(),
                WebhookEventType.LOAN_REPAYMENT_RECORDED,
                "LOAN_PAYMENT_TRANSACTION",
                savedPaymentTransaction.getId().toString(),
                buildRepaymentPayload(application, loanAccount, savedPaymentTransaction)
        );
        return savedPaymentTransaction;
    }

    @Transactional
    public LoanForeclosureQuote requestForeclosureQuote(UUID applicationId, String actorUsername, LocalDate effectiveDate) {
        LoanApplication application = getApplication(applicationId);
        if (application.getStatus() != LoanApplicationStatus.APPROVED) {
            throw new IllegalArgumentException("Foreclosure is only available for approved loan applications.");
        }
        if (effectiveDate == null) {
            throw new IllegalArgumentException("Foreclosure effective date is required.");
        }

        LoanAccount loanAccount = getRequiredLoanAccount(applicationId);
        if (loanAccount.getStatus() != LoanAccountStatus.DISBURSED) {
            throw new IllegalArgumentException("Foreclosure quote can only be requested for an active disbursed loan account.");
        }

        List<LoanRepaymentScheduleInstallment> installments = loanRepaymentScheduleInstallmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(loanAccount.getId());
        BigDecimal outstandingPrincipal = installments.stream()
                .map(installment -> scaleCurrency(installment.getPrincipalDue().subtract(installment.getPaidPrincipal()).max(BigDecimal.ZERO)))
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        // The current servicing model allocates against the full persisted schedule and does not
        // support waiving future interest accruals. A foreclosure quote therefore settles the
        // remaining scheduled liability, while still being tied to a specific effective date for execution.
        BigDecimal outstandingInterest = installments.stream()
                .map(installment -> scaleCurrency(installment.getInterestDue().subtract(installment.getPaidInterest()).max(BigDecimal.ZERO)))
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        BigDecimal settlementAmount = scaleCurrency(outstandingPrincipal.add(outstandingInterest));
        if (settlementAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Foreclosure quote is not available because the loan is already fully settled.");
        }

        List<LoanForeclosureQuote> existingQuotes = loanForeclosureQuoteRepository.findByLoanAccount_IdOrderByVersionDesc(
                loanAccount.getId()
        );
        existingQuotes.stream()
                .filter(quote -> quote.getStatus() == LoanForeclosureQuoteStatus.ACTIVE)
                .forEach(LoanForeclosureQuote::supersede);
        if (!existingQuotes.isEmpty()) {
            loanForeclosureQuoteRepository.saveAll(existingQuotes);
        }

        int nextVersion = existingQuotes.stream()
                .mapToInt(LoanForeclosureQuote::getVersion)
                .max()
                .orElse(0) + 1;

        return loanForeclosureQuoteRepository.save(new LoanForeclosureQuote(
                loanAccount,
                nextVersion,
                normalizeActorUsername(actorUsername),
                effectiveDate,
                scaleCurrency(outstandingPrincipal),
                scaleCurrency(outstandingInterest),
                settlementAmount
        ));
    }

    @Transactional
    public LoanForeclosureQuote requestForeclosureQuoteForLsp(
            UUID lspId,
            UUID loanAccountId,
            String actorUsername,
            LocalDate effectiveDate
    ) {
        LoanAccount loanAccount = getLoanAccountForLsp(lspId, loanAccountId);
        return requestForeclosureQuote(loanAccount.getLoanApplication().getId(), actorUsername, effectiveDate);
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
        if (settlementDate == null) {
            throw new IllegalArgumentException("Settlement date is required.");
        }

        LoanApplication application = getApplication(applicationId);
        LoanAccount loanAccount = getRequiredLoanAccount(applicationId);
        if (loanAccount.getStatus() != LoanAccountStatus.DISBURSED) {
            throw new IllegalArgumentException("Foreclosure can only be executed for an active disbursed loan account.");
        }

        LoanForeclosureQuote quote = loanForeclosureQuoteRepository.findById(quoteId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown foreclosure quote id: " + quoteId));
        if (!quote.getLoanAccount().getId().equals(loanAccount.getId())) {
            throw new IllegalArgumentException("Foreclosure quote does not belong to the selected loan account.");
        }
        if (quote.getStatus() != LoanForeclosureQuoteStatus.ACTIVE) {
            throw new IllegalArgumentException("Only an active foreclosure quote can be executed.");
        }
        if (!settlementDate.equals(quote.getEffectiveDate())) {
            throw new IllegalArgumentException("Settlement date must match the foreclosure quote effective date.");
        }

        loanPaymentTransactionRepository.save(new LoanPaymentTransaction(
                loanAccount,
                normalizeActorUsername(actorUsername),
                quote.getSettlementAmount(),
                settlementDate,
                requireReference(reference),
                LoanPaymentChannel.FORECLOSURE_SETTLEMENT,
                LoanPaymentStatus.RECEIVED,
                normalizeNote(note) == null
                        ? "Foreclosure settlement for quote v" + quote.getVersion()
                        : normalizeNote(note),
                CorrelationIdHolder.get()
        ));
        recomputePaymentAllocation(loanAccount);

        if (!allInstallmentsSettled(loanAccount)) {
            throw new IllegalStateException("Foreclosure settlement did not fully settle the repayment schedule.");
        }

        quote.execute(normalizeActorUsername(actorUsername));
        loanForeclosureQuoteRepository.save(quote);
        synchronizeLoanAccountClosureState(
                application,
                loanAccount,
                normalizeActorUsername(actorUsername),
                LoanAccountClosureReason.FORECLOSURE
        );
        recordAuditEvent(
                application,
                LoanApplicationAuditAction.FORECLOSURE_EXECUTED,
                application.getStatus(),
                application.getStatus(),
                actorUsername,
                "Foreclosure executed using quote v"
                        + quote.getVersion()
                        + " effective "
                        + quote.getEffectiveDate()
                        + " with settlement reference "
                        + requireReference(reference),
                null
        );

        webhookOutboxService.enqueueIfSubscribed(
                application.getLsp(),
                WebhookEventType.LOAN_FORECLOSURE_COMPLETED,
                "LOAN_ACCOUNT",
                loanAccount.getId().toString(),
                buildForeclosurePayload(application, loanAccount, quote)
        );
        return quote;
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

    private static String normalizePan(String pan) {
        return pan.trim().toUpperCase();
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

    private static BigDecimal normalizeMonthlyIncome(BigDecimal monthlyIncome) {
        if (monthlyIncome == null) {
            return null;
        }

        if (monthlyIncome.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Borrower monthly income must be greater than zero.");
        }

        return monthlyIncome.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static String normalizeQuery(String query) {
        if (query == null) {
            return null;
        }

        String normalized = query.trim().toLowerCase();
        return normalized.isBlank() ? null : normalized;
    }

    private static void validateDateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("disbursalDateFrom cannot be after disbursalDateTo.");
        }
    }

    private static boolean isWithinDisbursalRange(LocalDate disbursalDate, LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return true;
        }
        if (disbursalDate == null) {
            return false;
        }
        if (from != null && disbursalDate.isBefore(from)) {
            return false;
        }
        return to == null || !disbursalDate.isAfter(to);
    }

    private static boolean matchesQuery(LoanApplication application, String normalizedQuery) {
        return contains(application.getId().toString(), normalizedQuery)
                || contains(application.getBorrower().getFullName(), normalizedQuery)
                || contains(application.getBorrower().getPan(), normalizedQuery)
                || contains(application.getBorrower().getMobile(), normalizedQuery)
                || contains(application.getBorrower().getCity(), normalizedQuery)
                || contains(application.getBorrower().getState(), normalizedQuery)
                || contains(application.getBorrower().getEmploymentType(), normalizedQuery)
                || contains(application.getExternalLoanId(), normalizedQuery);
    }

    private static boolean contains(String value, String normalizedQuery) {
        return value != null && value.toLowerCase().contains(normalizedQuery);
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
        return "Transitioned loan application from "
                + currentStatus.name()
                + " to "
                + targetStatus.name();
    }

    private static String normalizeActorUsername(String actorUsername) {
        if (actorUsername == null) {
            return "system";
        }

        String normalized = actorUsername.trim();
        return normalized.isBlank() ? "system" : normalized;
    }

    private static String normalizeAssigneeUsername(String assigneeUsername) {
        String normalized = normalizeOptional(assigneeUsername);
        return normalized == null ? null : normalized.toLowerCase();
    }

    private static String requireReference(String reference) {
        String normalized = normalizeOptional(reference);
        if (normalized == null) {
            throw new IllegalArgumentException("Payment reference is required.");
        }
        return normalized;
    }

    private static String requireNote(String note) {
        String normalized = normalizeOptional(note);
        if (normalized == null) {
            throw new IllegalArgumentException("Manual status note is required.");
        }
        return normalized;
    }

    private static String normalizeNote(String note) {
        return normalizeOptional(note);
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
        return payload;
    }

    private static Map<String, Object> buildDisbursementPayload(LoanApplication application, LoanAccount loanAccount) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanApplicationId", application.getId());
        payload.put("loanAccountId", loanAccount.getId());
        payload.put("accountNumber", loanAccount.getAccountNumber());
        payload.put("loanAccountStatus", loanAccount.getStatus().name());
        payload.put("principalAmount", loanAccount.getPrincipalAmount());
        return payload;
    }

    private static Map<String, Object> buildRepaymentPayload(
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

    private static Map<String, Object> buildForeclosurePayload(
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
        return payload;
    }

    private static LoanApplicationStatusReasonCode validateTransitionReasonCode(
            LoanApplicationStatus targetStatus,
            LoanApplicationStatusReasonCode reasonCode
    ) {
        if (targetStatus == LoanApplicationStatus.HOLD || targetStatus == LoanApplicationStatus.REJECTED) {
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

    private void recordAuditEvent(
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

    private LoanAccount ensureLoanAccountForApprovedApplication(LoanApplication application) {
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

    private void generateRepaymentSchedule(LoanAccount loanAccount) {
        if (!loanRepaymentScheduleInstallmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(loanAccount.getId())
                .isEmpty()) {
            return;
        }

        BigDecimal principal = scaleCurrency(loanAccount.getPrincipalAmount());
        int tenureMonths = loanAccount.getTenureMonths();
        BigDecimal annualRate = loanAccount.getLoanProduct().getInterestRate();
        BigDecimal monthlyRate = annualRate
                .divide(BigDecimal.valueOf(1200), 10, RoundingMode.HALF_UP);
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

    private void recomputePaymentAllocation(LoanAccount loanAccount) {
        List<LoanRepaymentScheduleInstallment> installments = loanRepaymentScheduleInstallmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(loanAccount.getId());
        installments.forEach(LoanRepaymentScheduleInstallment::resetAllocation);

        List<LoanPaymentTransaction> payments = loanPaymentTransactionRepository
                .findByLoanAccount_IdOrderByPaymentDateAscCreatedAtAsc(loanAccount.getId());

        for (LoanPaymentTransaction payment : payments) {
            if (payment.getStatus() != LoanPaymentStatus.RECEIVED) {
                payment.updateAllocation(BigDecimal.ZERO.setScale(2), scaleCurrency(payment.getAmount()));
                continue;
            }

            BigDecimal remainingAmount = scaleCurrency(payment.getAmount());
            BigDecimal allocatedAmount = BigDecimal.ZERO.setScale(2);

            for (LoanRepaymentScheduleInstallment installment : installments) {
                if (remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
                if (installment.getStatus() == LoanRepaymentScheduleInstallmentStatus.PAID) {
                    continue;
                }

                BigDecimal appliedAmount = installment.applyPayment(remainingAmount);
                remainingAmount = scaleCurrency(remainingAmount.subtract(appliedAmount));
                allocatedAmount = scaleCurrency(allocatedAmount.add(appliedAmount));
            }

            payment.updateAllocation(allocatedAmount, remainingAmount);
        }

        loanRepaymentScheduleInstallmentRepository.saveAll(installments);
        loanPaymentTransactionRepository.saveAll(payments);
    }

    private void synchronizeLoanAccountClosureState(
            LoanApplication application,
            LoanAccount loanAccount,
            String actorUsername,
            LoanAccountClosureReason closureReason
    ) {
        if (!allInstallmentsSettled(loanAccount)) {
            return;
        }

        if (closureReason == LoanAccountClosureReason.FORECLOSURE) {
            loanAccount.close(LoanAccountClosureReason.FORECLOSURE, actorUsername, Instant.now());
            loanAccountRepository.save(loanAccount);
            return;
        }

        if (loanAccount.getStatus() == LoanAccountStatus.DISBURSED) {
            loanAccount.close(LoanAccountClosureReason.FULLY_REPAID, actorUsername, Instant.now());
            loanAccountRepository.save(loanAccount);
        }
    }

    private boolean allInstallmentsSettled(LoanAccount loanAccount) {
        return loanRepaymentScheduleInstallmentRepository.findByLoanAccount_IdOrderByInstallmentNumberAsc(loanAccount.getId())
                .stream()
                .allMatch(installment -> installment.getOutstandingAmount().compareTo(BigDecimal.ZERO) <= 0);
    }

    private LoanDelinquencySummary buildDelinquencySummary(List<LoanRepaymentScheduleInstallment> installments) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
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
        return loanAccountRepository.findByLoanApplication_Id(applicationId)
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

    public static int calculateDaysPastDue(LoanRepaymentScheduleInstallment installment, LocalDate today) {
        if (installment.getOutstandingAmount().compareTo(BigDecimal.ZERO) <= 0
                || !installment.getDueDate().isBefore(today)) {
            return 0;
        }
        return Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(installment.getDueDate(), today));
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
        if (checklistItem.getStatus() == LoanApplicationDocumentChecklistStatus.REJECTED) {
            return normalizeOptional(checklistItem.getRejectionReason()) != null
                    ? checklistItem.getRejectionReason()
                    : checklistItem.getNote();
        }

        if (checklistItem.getStatus() == LoanApplicationDocumentChecklistStatus.VERIFIED) {
            return normalizeOptional(checklistItem.getReviewReason()) != null
                    ? checklistItem.getReviewReason()
                    : checklistItem.getNote();
        }

        return checklistItem.getNote();
    }

    private void ensureDocumentChecklist(LoanApplication application) {
        if (!loanApplicationDocumentChecklistRepository.findByLoanApplication_IdOrderByCreatedAtAsc(application.getId()).isEmpty()) {
            return;
        }

        seedDocumentChecklist(application, "system");
    }

    private void seedDocumentChecklist(LoanApplication application, String actorUsername) {
        List<LoanApplicationDocumentChecklist> checklistItems = List.of(
                buildChecklistItem(application, LoanApplicationDocumentType.PAN_CARD, actorUsername),
                buildChecklistItem(application, LoanApplicationDocumentType.ADDRESS_PROOF, actorUsername),
                buildChecklistItem(application, LoanApplicationDocumentType.INCOME_PROOF, actorUsername),
                buildChecklistItem(application, LoanApplicationDocumentType.BANK_STATEMENT, actorUsername),
                buildChecklistItem(application, LoanApplicationDocumentType.SELFIE_PHOTOGRAPH, actorUsername)
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
                .filter(LoanApplicationDocumentChecklist::isRequired)
                .filter(item -> item.getStatus() != LoanApplicationDocumentChecklistStatus.VERIFIED
                        && item.getStatus() != LoanApplicationDocumentChecklistStatus.NOT_REQUIRED)
                .map(LoanApplicationDocumentChecklist::getDocumentType)
                .toList();

        if (!blockingDocumentTypes.isEmpty()) {
            throw new KycCompletionRequiredException(blockingDocumentTypes);
        }
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
        payload.put("borrowerCity", application.getBorrower().getCity());
        payload.put("borrowerState", application.getBorrower().getState());
        payload.put("borrowerEmploymentType", application.getBorrower().getEmploymentType());
        payload.put("borrowerMonthlyIncome", application.getBorrower().getMonthlyIncome());
        payload.put("status", application.getStatus().name());

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize intake payload audit.", exception);
        }
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
