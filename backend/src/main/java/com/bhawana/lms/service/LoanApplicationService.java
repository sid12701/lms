package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.web.KycCompletionRequiredException;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationAssignmentEvent;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.domain.LoanApplicationIntakeAudit;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusTransition;
import com.bhawana.lms.domain.LoanProductLspMapping;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.LoanApplicationAssignmentEventRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationIntakeAuditRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.LspRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanApplicationService {

    private final AppUserRepository appUserRepository;
    private final BorrowerRepository borrowerRepository;
    private final LoanApplicationAssignmentEventRepository loanApplicationAssignmentEventRepository;
    private final LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    private final LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository;
    private final LoanProductRepository loanProductRepository;
    private final LspRepository lspRepository;
    private final LoanProductLspMappingRepository loanProductLspMappingRepository;
    private final ObjectMapper objectMapper;

    public LoanApplicationService(
            AppUserRepository appUserRepository,
            BorrowerRepository borrowerRepository,
            LoanApplicationAssignmentEventRepository loanApplicationAssignmentEventRepository,
            LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository,
            LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository,
            LoanApplicationRepository loanApplicationRepository,
            LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository,
            LoanProductRepository loanProductRepository,
            LspRepository lspRepository,
            LoanProductLspMappingRepository loanProductLspMappingRepository,
            ObjectMapper objectMapper
    ) {
        this.appUserRepository = appUserRepository;
        this.borrowerRepository = borrowerRepository;
        this.loanApplicationAssignmentEventRepository = loanApplicationAssignmentEventRepository;
        this.loanApplicationDocumentChecklistRepository = loanApplicationDocumentChecklistRepository;
        this.loanApplicationIntakeAuditRepository = loanApplicationIntakeAuditRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanApplicationStatusTransitionRepository = loanApplicationStatusTransitionRepository;
        this.loanProductRepository = loanProductRepository;
        this.lspRepository = lspRepository;
        this.loanProductLspMappingRepository = loanProductLspMappingRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<LoanApplication> listApplications(
            UUID lspId,
            UUID productId,
            String status,
            String sourceChannel,
            String query
    ) {
        String normalizedQuery = normalizeQuery(query);
        String normalizedStatus = normalizeOptional(status);
        String normalizedSourceChannel = normalizeOptional(sourceChannel);
        return loanApplicationRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(application -> lspId == null || application.getLsp().getId().equals(lspId))
                .filter(application -> productId == null || application.getLoanProduct().getId().equals(productId))
                .filter(application -> normalizedStatus == null
                        || application.getStatus().name().equalsIgnoreCase(normalizedStatus))
                .filter(application -> normalizedSourceChannel == null
                        || application.getSourceChannel().equalsIgnoreCase(normalizedSourceChannel))
                .filter(application -> normalizedQuery == null || matchesQuery(application, normalizedQuery))
                .toList();
    }

    @Transactional(readOnly = true)
    public LoanApplication getApplication(UUID applicationId) {
        return loanApplicationRepository.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown loan application id: " + applicationId));
    }

    @Transactional(readOnly = true)
    public List<LoanApplicationStatusTransition> listStatusTransitions(UUID applicationId) {
        getApplication(applicationId);
        return loanApplicationStatusTransitionRepository.findTop20ByLoanApplication_IdOrderByCreatedAtDesc(applicationId);
    }

    @Transactional(readOnly = true)
    public List<LoanApplicationAssignmentEvent> listAssignmentEvents(UUID applicationId) {
        getApplication(applicationId);
        return loanApplicationAssignmentEventRepository.findTop20ByLoanApplication_IdOrderByCreatedAtDesc(applicationId);
    }

    @Transactional
    public List<LoanApplicationDocumentChecklist> listDocumentChecklist(UUID applicationId) {
        LoanApplication application = getApplication(applicationId);
        ensureDocumentChecklist(application);
        return loanApplicationDocumentChecklistRepository.findByLoanApplication_IdOrderByCreatedAtAsc(applicationId);
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
        return savedApplication;
    }

    @Transactional
    public LoanApplication transitionStatus(
            UUID applicationId,
            String actorUsername,
            LoanApplicationStatus targetStatus,
            String note
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

        String resolvedNote = resolveTransitionNote(note, currentStatus, targetStatus);
        application.transitionTo(targetStatus);
        LoanApplication savedApplication = loanApplicationRepository.save(application);
        loanApplicationStatusTransitionRepository.save(new LoanApplicationStatusTransition(
                savedApplication,
                currentStatus,
                targetStatus,
                normalizeActorUsername(actorUsername),
                resolvedNote,
                CorrelationIdHolder.get()
        ));
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
            String contentType
    ) {
        LoanApplication application = getApplication(applicationId);
        ensureDocumentChecklist(application);

        LoanApplicationDocumentChecklist checklistItem = loanApplicationDocumentChecklistRepository
                .findByLoanApplication_IdAndDocumentType(applicationId, documentType)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown document checklist item: " + documentType.name()
                ));

        checklistItem.update(
                status,
                note,
                normalizeActorUsername(actorUsername),
                fileName,
                fileReference,
                sourceReference,
                contentType
        );
        return loanApplicationDocumentChecklistRepository.save(checklistItem);
    }

    @Transactional(readOnly = true)
    public List<LoanApplicationIntakeAudit> listIntakeAudits(UUID applicationId) {
        getApplication(applicationId);
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

    private static boolean matchesQuery(LoanApplication application, String normalizedQuery) {
        return contains(application.getBorrower().getFullName(), normalizedQuery)
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
}
