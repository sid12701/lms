package com.bhawana.lms.web;

import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationAuditEvent;
import com.bhawana.lms.domain.LoanApplicationAssignmentEvent;
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
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.domain.MockDisbursementOutcome;
import com.bhawana.lms.service.LoanApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/ops/loan-applications")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS_USER')")
public class LoanApplicationOpsController {

    private final LoanApplicationService loanApplicationService;

    public LoanApplicationOpsController(LoanApplicationService loanApplicationService) {
        this.loanApplicationService = loanApplicationService;
    }

    @GetMapping
    public List<LoanApplicationResponse> listApplications(
            @RequestParam(required = false) UUID lspId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sourceChannel,
            @RequestParam(required = false, name = "q") String query
    ) {
        return loanApplicationService.listApplications(lspId, productId, status, sourceChannel, query).stream()
                .map(LoanApplicationOpsController::toResponse)
                .toList();
    }

    @GetMapping("/{applicationId}")
    public LoanApplicationDetailResponse getApplication(@PathVariable UUID applicationId) {
        LoanApplication application = loanApplicationService.getApplication(applicationId);
        return toDetailResponse(
                application,
                loanApplicationService.getLatestActivity(applicationId).orElse(null),
                loanApplicationService.getLoanAccount(applicationId).orElse(null),
                loanApplicationService.getLoanRepaymentScheduleSummary(applicationId).orElse(null),
                loanApplicationService.getLoanDelinquencySummary(applicationId).orElse(null)
        );
    }

    @GetMapping("/{applicationId}/intake-audits")
    public List<LoanApplicationIntakeAuditResponse> listIntakeAudits(@PathVariable UUID applicationId) {
        return loanApplicationService.listIntakeAudits(applicationId).stream()
                .map(LoanApplicationOpsController::toAuditResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/status-transitions")
    public List<LoanApplicationStatusTransitionResponse> listStatusTransitions(@PathVariable UUID applicationId) {
        return loanApplicationService.listStatusTransitions(applicationId).stream()
                .map(LoanApplicationOpsController::toTransitionResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/assignment-events")
    public List<LoanApplicationAssignmentEventResponse> listAssignmentEvents(@PathVariable UUID applicationId) {
        return loanApplicationService.listAssignmentEvents(applicationId).stream()
                .map(LoanApplicationOpsController::toAssignmentEventResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/audit-events")
    public List<LoanApplicationAuditEventResponse> listAuditEvents(@PathVariable UUID applicationId) {
        return loanApplicationService.listAuditEvents(applicationId).stream()
                .map(LoanApplicationOpsController::toAuditEventResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/disbursement-requests")
    public List<LoanDisbursementRequestResponse> listDisbursementRequests(@PathVariable UUID applicationId) {
        return loanApplicationService.listDisbursementRequests(applicationId).stream()
                .map(LoanApplicationOpsController::toDisbursementRequestResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/repayment-schedule")
    public List<LoanRepaymentScheduleInstallmentResponse> listRepaymentSchedule(@PathVariable UUID applicationId) {
        return loanApplicationService.listRepaymentSchedule(applicationId).stream()
                .map(LoanApplicationOpsController::toRepaymentScheduleInstallmentResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/payments")
    public List<LoanPaymentTransactionResponse> listPaymentTransactions(@PathVariable UUID applicationId) {
        return loanApplicationService.listPaymentTransactions(applicationId).stream()
                .map(LoanApplicationOpsController::toPaymentTransactionResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/kyc-documents")
    public List<LoanApplicationDocumentChecklistResponse> listDocumentChecklist(@PathVariable UUID applicationId) {
        return loanApplicationService.listDocumentChecklist(applicationId).stream()
                .map(LoanApplicationOpsController::toDocumentChecklistResponse)
                .toList();
    }

    @PostMapping
    public LoanApplicationResponse createApplication(
            Authentication authentication,
            @Valid @RequestBody LoanApplicationRequest request
    ) {
        LoanApplication application = loanApplicationService.createApplication(
                authentication.getName(),
                request.lspId(),
                request.productId(),
                request.externalLoanId(),
                request.sourceChannel(),
                request.borrowerPan(),
                request.borrowerFullName(),
                request.borrowerMobile(),
                request.borrowerEmail(),
                request.borrowerDateOfBirth(),
                request.borrowerCity(),
                request.borrowerState(),
                request.borrowerEmploymentType(),
                request.borrowerMonthlyIncome(),
                request.requestedAmount(),
                request.tenureMonths()
        );
        return toResponse(application);
    }

    @PostMapping("/{applicationId}/status-transitions")
    public LoanApplicationDetailResponse transitionStatus(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @Valid @RequestBody LoanApplicationStatusTransitionRequest request
    ) {
        LoanApplication currentApplication = loanApplicationService.getApplication(applicationId);
        authorizeStatusTransition(extractRoles(authentication), currentApplication.getStatus(), request.targetStatus());
        LoanApplication application = loanApplicationService.transitionStatus(
                applicationId,
                authentication.getName(),
                request.targetStatus(),
                request.note(),
                request.reasonCode()
        );
        return toDetailResponse(
                application,
                loanApplicationService.getLatestActivity(applicationId).orElse(null),
                loanApplicationService.getLoanAccount(applicationId).orElse(null),
                loanApplicationService.getLoanRepaymentScheduleSummary(applicationId).orElse(null),
                loanApplicationService.getLoanDelinquencySummary(applicationId).orElse(null)
        );
    }

    @PostMapping("/{applicationId}/manual-status")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public LoanApplicationDetailResponse manuallyOverrideStatus(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @Valid @RequestBody ManualStatusUpdateRequest request
    ) {
        LoanApplication application = loanApplicationService.manuallyOverrideStatus(
                applicationId,
                authentication.getName(),
                request.targetStatus(),
                request.note(),
                request.reasonCode()
        );
        return toDetailResponse(
                application,
                loanApplicationService.getLatestActivity(applicationId).orElse(null),
                loanApplicationService.getLoanAccount(applicationId).orElse(null),
                loanApplicationService.getLoanRepaymentScheduleSummary(applicationId).orElse(null),
                loanApplicationService.getLoanDelinquencySummary(applicationId).orElse(null)
        );
    }

    private static void authorizeStatusTransition(
            List<String> actorRoles,
            LoanApplicationStatus currentStatus,
            LoanApplicationStatus targetStatus
    ) {
        if (actorRoles.contains("SYSTEM_ADMIN")) {
            return;
        }

        if (actorRoles.contains("OPS_USER")
                && ((currentStatus == LoanApplicationStatus.RECEIVED
                        && targetStatus == LoanApplicationStatus.UNDER_REVIEW)
                        || (currentStatus == LoanApplicationStatus.UNDER_REVIEW
                        && targetStatus == LoanApplicationStatus.HOLD)
                        || (currentStatus == LoanApplicationStatus.HOLD
                        && targetStatus == LoanApplicationStatus.UNDER_REVIEW))) {
            return;
        }

        throw new AccessDeniedException("Your role cannot perform this loan status transition.");
    }

    private static List<String> extractRoles(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> !"ROLE_PASSWORD_CHANGE_REQUIRED".equals(authority))
                .map(role -> role.startsWith("ROLE_") ? role.substring("ROLE_".length()) : role)
                .toList();
    }

    @PostMapping("/{applicationId}/assignment")
    public LoanApplicationDetailResponse assignApplication(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @Valid @RequestBody LoanApplicationAssignmentRequest request
    ) {
        LoanApplication application = loanApplicationService.assignApplication(
                applicationId,
                authentication.getName(),
                request.assigneeUsername(),
                request.note()
        );
        return toDetailResponse(
                application,
                loanApplicationService.getLatestActivity(applicationId).orElse(null),
                loanApplicationService.getLoanAccount(applicationId).orElse(null),
                loanApplicationService.getLoanRepaymentScheduleSummary(applicationId).orElse(null),
                loanApplicationService.getLoanDelinquencySummary(applicationId).orElse(null)
        );
    }

    @PutMapping("/{applicationId}/kyc-documents/{documentType}")
    public LoanApplicationDocumentChecklistResponse updateDocumentChecklistItem(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @PathVariable LoanApplicationDocumentType documentType,
            @Valid @RequestBody LoanApplicationDocumentChecklistUpdateRequest request
    ) {
        return toDocumentChecklistResponse(loanApplicationService.updateDocumentChecklistItem(
                applicationId,
                documentType,
                authentication.getName(),
                request.status(),
                request.note(),
                request.fileName(),
                request.fileReference(),
                request.sourceReference(),
                request.contentType(),
                request.reviewReason(),
                request.rejectionReason()
        ));
    }

    @PostMapping("/{applicationId}/disbursement-requests")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public LoanApplicationDetailResponse initiateDisbursement(
            Authentication authentication,
            @PathVariable UUID applicationId
    ) {
        LoanApplication application = loanApplicationService.initiateDisbursement(
                applicationId,
                authentication.getName()
        );
        return toDetailResponse(
                application,
                loanApplicationService.getLatestActivity(applicationId).orElse(null),
                loanApplicationService.getLoanAccount(applicationId).orElse(null),
                loanApplicationService.getLoanRepaymentScheduleSummary(applicationId).orElse(null),
                loanApplicationService.getLoanDelinquencySummary(applicationId).orElse(null)
        );
    }

    @PostMapping("/{applicationId}/disbursement-requests/mock-outcome")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public LoanApplicationDetailResponse applyMockDisbursementOutcome(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @Valid @RequestBody MockDisbursementOutcomeRequest request
    ) {
        LoanApplication application = loanApplicationService.resolveMockDisbursementOutcome(
                applicationId,
                authentication.getName(),
                request.outcome()
        );
        return toDetailResponse(
                application,
                loanApplicationService.getLatestActivity(applicationId).orElse(null),
                loanApplicationService.getLoanAccount(applicationId).orElse(null),
                loanApplicationService.getLoanRepaymentScheduleSummary(applicationId).orElse(null),
                loanApplicationService.getLoanDelinquencySummary(applicationId).orElse(null)
        );
    }

    @PostMapping("/{applicationId}/payments")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public LoanPaymentTransactionResponse recordPaymentTransaction(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @Valid @RequestBody LoanPaymentTransactionRequest request
    ) {
        return toPaymentTransactionResponse(loanApplicationService.recordPaymentTransaction(
                applicationId,
                authentication.getName(),
                request.amount(),
                request.paymentDate(),
                request.reference(),
                request.channel(),
                request.status(),
                request.note()
        ));
    }

    private static LoanApplicationResponse toResponse(LoanApplication application) {
        return new LoanApplicationResponse(
                application.getId().toString(),
                application.getBorrower().getId().toString(),
                application.getBorrower().getFullName(),
                application.getBorrower().getPan(),
                application.getBorrower().getMobile(),
                application.getBorrower().getEmail(),
                application.getBorrower().getDateOfBirth(),
                application.getBorrower().getCity(),
                application.getBorrower().getState(),
                application.getBorrower().getEmploymentType(),
                application.getBorrower().getMonthlyIncome(),
                application.getLsp().getId().toString(),
                application.getLsp().getCode(),
                application.getLsp().getName(),
                application.getLoanProduct().getId().toString(),
                application.getLoanProduct().getCode(),
                application.getLoanProduct().getName(),
                application.getExternalLoanId(),
                application.getSourceChannel(),
                application.getRequestedAmount(),
                application.getRequestedTenureMonths(),
                application.getStatus().name(),
                application.getAssignedToUsername(),
                application.getAssignedByUsername(),
                application.getAssignedAt(),
                application.getCreatedAt().toString()
        );
    }

    private static LoanApplicationDetailResponse toDetailResponse(
            LoanApplication application,
            LoanApplicationService.LoanApplicationLastActivity lastActivity,
            LoanAccount loanAccount,
            LoanApplicationService.LoanRepaymentScheduleSummary repaymentScheduleSummary,
            LoanApplicationService.LoanDelinquencySummary delinquencySummary
    ) {
        return new LoanApplicationDetailResponse(
                application.getId().toString(),
                application.getBorrower().getId().toString(),
                application.getBorrower().getFullName(),
                application.getBorrower().getPan(),
                application.getBorrower().getMobile(),
                application.getBorrower().getEmail(),
                application.getBorrower().getDateOfBirth(),
                application.getBorrower().getCity(),
                application.getBorrower().getState(),
                application.getBorrower().getEmploymentType(),
                application.getBorrower().getMonthlyIncome(),
                application.getLsp().getId().toString(),
                application.getLsp().getCode(),
                application.getLsp().getName(),
                application.getLoanProduct().getId().toString(),
                application.getLoanProduct().getCode(),
                application.getLoanProduct().getName(),
                application.getExternalLoanId(),
                application.getSourceChannel(),
                application.getRequestedAmount(),
                application.getRequestedTenureMonths(),
                application.getStatus().name(),
                application.getAssignedToUsername(),
                application.getAssignedByUsername(),
                application.getAssignedAt(),
                application.getCreatedAt().toString(),
                application.getUpdatedAt().toString(),
                loanAccount == null ? null : new LoanAccountSummaryResponse(
                        loanAccount.getId().toString(),
                        loanAccount.getAccountNumber(),
                        loanAccount.getStatus().name(),
                        loanAccount.getPrincipalAmount(),
                        loanAccount.getTenureMonths(),
                        loanAccount.getApprovedAt().toString(),
                        loanAccount.getCreatedAt().toString(),
                        delinquencySummary == null ? null : new LoanDelinquencySummaryResponse(
                                delinquencySummary.maxDaysPastDue(),
                                delinquencySummary.bucket().name(),
                                delinquencySummary.overdueInstallmentCount(),
                                delinquencySummary.overdueAmount()
                        ),
                        repaymentScheduleSummary == null ? null : new LoanRepaymentScheduleSummaryResponse(
                                repaymentScheduleSummary.installmentCount(),
                                repaymentScheduleSummary.installmentAmount(),
                                repaymentScheduleSummary.firstDueDate(),
                                repaymentScheduleSummary.finalDueDate()
                        )
                ),
                lastActivity == null ? null : new LoanApplicationLastActivityResponse(
                        lastActivity.activityType(),
                        lastActivity.actorUsername(),
                        lastActivity.summary(),
                        lastActivity.detail(),
                        lastActivity.correlationId(),
                        lastActivity.occurredAt().toString()
                )
        );
    }

    private static LoanApplicationIntakeAuditResponse toAuditResponse(LoanApplicationIntakeAudit audit) {
        return new LoanApplicationIntakeAuditResponse(
                audit.getId().toString(),
                audit.getLoanApplication().getId().toString(),
                audit.getActorUsername(),
                audit.getCorrelationId(),
                audit.getPayloadJson(),
                audit.getCreatedAt()
        );
    }

    private static LoanApplicationStatusTransitionResponse toTransitionResponse(LoanApplicationStatusTransition transition) {
        return new LoanApplicationStatusTransitionResponse(
                transition.getId().toString(),
                transition.getLoanApplication().getId().toString(),
                transition.getActorUsername(),
                transition.getFromStatus().name(),
                transition.getToStatus().name(),
                transition.getNote(),
                transition.getReasonCode() == null ? null : transition.getReasonCode().name(),
                transition.getCorrelationId(),
                transition.getCreatedAt().toString()
        );
    }

    private static LoanApplicationAssignmentEventResponse toAssignmentEventResponse(LoanApplicationAssignmentEvent event) {
        return new LoanApplicationAssignmentEventResponse(
                event.getId().toString(),
                event.getLoanApplication().getId().toString(),
                event.getFromAssigneeUsername(),
                event.getToAssigneeUsername(),
                event.getActorUsername(),
                event.getNote(),
                event.getCorrelationId(),
                event.getCreatedAt().toString()
        );
    }

    private static LoanApplicationAuditEventResponse toAuditEventResponse(LoanApplicationAuditEvent event) {
        return new LoanApplicationAuditEventResponse(
                event.getId().toString(),
                event.getLoanApplication().getId().toString(),
                event.getAction().name(),
                event.getActorUsername(),
                event.getFromStatus().name(),
                event.getToStatus().name(),
                event.getNote(),
                event.getReasonCode() == null ? null : event.getReasonCode().name(),
                event.getCorrelationId(),
                event.getCreatedAt().toString()
        );
    }

    private static LoanDisbursementRequestResponse toDisbursementRequestResponse(LoanDisbursementRequestLog request) {
        return new LoanDisbursementRequestResponse(
                request.getId().toString(),
                request.getLoanAccount().getId().toString(),
                request.getActorUsername(),
                request.getAmount(),
                request.getProviderName(),
                request.getProviderRequestId(),
                request.getProviderStatus(),
                request.getRequestPayloadJson(),
                request.getResponsePayloadJson(),
                request.getCorrelationId(),
                request.getCreatedAt().toString(),
                request.getUpdatedAt().toString()
        );
    }

    private static LoanRepaymentScheduleInstallmentResponse toRepaymentScheduleInstallmentResponse(
            LoanRepaymentScheduleInstallment installment
    ) {
        int daysPastDue = LoanApplicationService.calculateDaysPastDue(installment, LocalDate.now(ZoneOffset.UTC));
        return new LoanRepaymentScheduleInstallmentResponse(
                installment.getId().toString(),
                installment.getLoanAccount().getId().toString(),
                installment.getInstallmentNumber(),
                installment.getDueDate(),
                installment.getOpeningPrincipal(),
                installment.getPrincipalDue(),
                installment.getInterestDue(),
                installment.getInstallmentAmount(),
                installment.getClosingPrincipal(),
                installment.getStatus().name(),
                installment.getPaidPrincipal(),
                installment.getPaidInterest(),
                installment.getPaidAmount(),
                installment.getOutstandingAmount(),
                daysPastDue,
                LoanApplicationService.resolveDelinquencyBucket(daysPastDue).name(),
                installment.getCreatedAt().toString()
        );
    }

    private static LoanPaymentTransactionResponse toPaymentTransactionResponse(LoanPaymentTransaction paymentTransaction) {
        return new LoanPaymentTransactionResponse(
                paymentTransaction.getId().toString(),
                paymentTransaction.getLoanAccount().getId().toString(),
                paymentTransaction.getActorUsername(),
                paymentTransaction.getAmount(),
                paymentTransaction.getPaymentDate(),
                paymentTransaction.getReference(),
                paymentTransaction.getChannel().name(),
                paymentTransaction.getStatus().name(),
                paymentTransaction.getAllocatedAmount(),
                paymentTransaction.getUnallocatedAmount(),
                paymentTransaction.getNote(),
                paymentTransaction.getCorrelationId(),
                paymentTransaction.getCreatedAt().toString(),
                paymentTransaction.getUpdatedAt().toString()
        );
    }

    private static LoanApplicationDocumentChecklistResponse toDocumentChecklistResponse(
            LoanApplicationDocumentChecklist checklistItem
    ) {
        return new LoanApplicationDocumentChecklistResponse(
                checklistItem.getId().toString(),
                checklistItem.getLoanApplication().getId().toString(),
                checklistItem.getDocumentType().name(),
                checklistItem.getDocumentType().getDisplayName(),
                checklistItem.isRequired(),
                checklistItem.getStatus().name(),
                checklistItem.getNote(),
                checklistItem.getFileName(),
                checklistItem.getFileReference(),
                checklistItem.getContentType(),
                checklistItem.getSourceReference(),
                checklistItem.getReviewReason(),
                checklistItem.getRejectionReason(),
                checklistItem.getUploadedAt(),
                checklistItem.getUploadedByUsername(),
                checklistItem.getUpdatedByUsername(),
                checklistItem.getCreatedAt().toString(),
                checklistItem.getUpdatedAt().toString()
        );
    }

    public record LoanApplicationRequest(
            @NotNull UUID lspId,
            @NotNull UUID productId,
            @NotBlank String externalLoanId,
            @NotBlank String sourceChannel,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{5}[0-9]{4}[A-Za-z]$", message = "PAN must be a valid 10-character PAN") String borrowerPan,
            @NotBlank String borrowerFullName,
            @NotBlank @Pattern(regexp = "^[0-9]{10,15}$", message = "Mobile must contain 10 to 15 digits") String borrowerMobile,
            @Email String borrowerEmail,
            @Past LocalDate borrowerDateOfBirth,
            @Size(max = 128) String borrowerCity,
            @Size(max = 128) String borrowerState,
            @Size(max = 64) String borrowerEmploymentType,
            @DecimalMin(value = "0.01") BigDecimal borrowerMonthlyIncome,
            @NotNull @DecimalMin("0.01") BigDecimal requestedAmount,
            @NotNull @Min(1) Integer tenureMonths
    ) {
    }

    public record LoanApplicationResponse(
            String id,
            String borrowerId,
            String borrowerFullName,
            String borrowerPan,
            String borrowerMobile,
            String borrowerEmail,
            LocalDate borrowerDateOfBirth,
            String borrowerCity,
            String borrowerState,
            String borrowerEmploymentType,
            BigDecimal borrowerMonthlyIncome,
            String lspId,
            String lspCode,
            String lspName,
            String productId,
            String productCode,
            String productName,
            String externalLoanId,
            String sourceChannel,
            BigDecimal requestedAmount,
            Integer tenureMonths,
            String status,
            String assignedToUsername,
            String assignedByUsername,
            Instant assignedAt,
            String createdAt
    ) {
    }

    public record LoanApplicationDetailResponse(
            String id,
            String borrowerId,
            String borrowerFullName,
            String borrowerPan,
            String borrowerMobile,
            String borrowerEmail,
            LocalDate borrowerDateOfBirth,
            String borrowerCity,
            String borrowerState,
            String borrowerEmploymentType,
            BigDecimal borrowerMonthlyIncome,
            String lspId,
            String lspCode,
            String lspName,
            String productId,
            String productCode,
            String productName,
            String externalLoanId,
            String sourceChannel,
            BigDecimal requestedAmount,
            Integer tenureMonths,
            String status,
            String assignedToUsername,
            String assignedByUsername,
            Instant assignedAt,
            String createdAt,
            String updatedAt,
            LoanAccountSummaryResponse loanAccount,
            LoanApplicationLastActivityResponse lastActivity
    ) {
    }

    public record LoanAccountSummaryResponse(
            String id,
            String accountNumber,
            String status,
            BigDecimal principalAmount,
            Integer tenureMonths,
            String approvedAt,
            String createdAt,
            LoanDelinquencySummaryResponse delinquency,
            LoanRepaymentScheduleSummaryResponse repaymentSchedule
    ) {
    }

    public record LoanDelinquencySummaryResponse(
            Integer maxDaysPastDue,
            String bucket,
            Integer overdueInstallmentCount,
            BigDecimal overdueAmount
    ) {
    }

    public record LoanRepaymentScheduleSummaryResponse(
            Integer installmentCount,
            BigDecimal installmentAmount,
            LocalDate firstDueDate,
            LocalDate finalDueDate
    ) {
    }

    public record LoanApplicationLastActivityResponse(
            String activityType,
            String actorUsername,
            String summary,
            String detail,
            String correlationId,
            String occurredAt
    ) {
    }

    public record LoanApplicationIntakeAuditResponse(
            String id,
            String loanApplicationId,
            String actorUsername,
            String correlationId,
            String payloadJson,
            Instant createdAt
    ) {
    }

    public record LoanApplicationStatusTransitionRequest(
            @NotNull LoanApplicationStatus targetStatus,
            @Size(max = 500) String note,
            LoanApplicationStatusReasonCode reasonCode
    ) {
    }

    public record ManualStatusUpdateRequest(
            @NotNull LoanApplicationStatus targetStatus,
            @NotBlank @Size(max = 500) String note,
            @NotNull LoanApplicationStatusReasonCode reasonCode
    ) {
    }

    public record LoanApplicationAssignmentRequest(
            String assigneeUsername,
            @Size(max = 500) String note
    ) {
    }

    public record MockDisbursementOutcomeRequest(
            @NotNull MockDisbursementOutcome outcome
    ) {
    }

    public record LoanPaymentTransactionRequest(
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotNull @PastOrPresent LocalDate paymentDate,
            @NotBlank @Size(max = 128) String reference,
            @NotNull LoanPaymentChannel channel,
            @NotNull LoanPaymentStatus status,
            @Size(max = 500) String note
    ) {
    }

    public record LoanApplicationDocumentChecklistUpdateRequest(
            @NotNull LoanApplicationDocumentChecklistStatus status,
            @Size(max = 500) String note,
            @Size(max = 255) String fileName,
            @Size(max = 500) String fileReference,
            @Size(max = 500) String sourceReference,
            @Size(max = 128) String contentType,
            @Size(max = 500) String reviewReason,
            @Size(max = 500) String rejectionReason
    ) {
    }

    public record LoanApplicationStatusTransitionResponse(
            String id,
            String loanApplicationId,
            String actorUsername,
            String fromStatus,
            String toStatus,
            String note,
            String reasonCode,
            String correlationId,
            String createdAt
    ) {
    }

    public record LoanApplicationAssignmentEventResponse(
            String id,
            String loanApplicationId,
            String fromAssigneeUsername,
            String toAssigneeUsername,
            String actorUsername,
            String note,
            String correlationId,
            String createdAt
    ) {
    }

    public record LoanApplicationAuditEventResponse(
            String id,
            String loanApplicationId,
            String action,
            String actorUsername,
            String fromStatus,
            String toStatus,
            String note,
            String reasonCode,
            String correlationId,
            String createdAt
    ) {
    }

    public record LoanDisbursementRequestResponse(
            String id,
            String loanAccountId,
            String actorUsername,
            BigDecimal amount,
            String providerName,
            String providerRequestId,
            String providerStatus,
            String requestPayloadJson,
            String responsePayloadJson,
            String correlationId,
            String createdAt,
            String updatedAt
    ) {
    }

    public record LoanRepaymentScheduleInstallmentResponse(
            String id,
            String loanAccountId,
            Integer installmentNumber,
            LocalDate dueDate,
            BigDecimal openingPrincipal,
            BigDecimal principalDue,
            BigDecimal interestDue,
            BigDecimal installmentAmount,
            BigDecimal closingPrincipal,
            String status,
            BigDecimal paidPrincipal,
            BigDecimal paidInterest,
            BigDecimal paidAmount,
            BigDecimal outstandingAmount,
            Integer daysPastDue,
            String delinquencyBucket,
            String createdAt
    ) {
    }

    public record LoanPaymentTransactionResponse(
            String id,
            String loanAccountId,
            String actorUsername,
            BigDecimal amount,
            LocalDate paymentDate,
            String reference,
            String channel,
            String status,
            BigDecimal allocatedAmount,
            BigDecimal unallocatedAmount,
            String note,
            String correlationId,
            String createdAt,
            String updatedAt
    ) {
    }

    public record LoanApplicationDocumentChecklistResponse(
            String id,
            String loanApplicationId,
            String documentType,
            String documentDisplayName,
            boolean required,
            String status,
            String note,
            String fileName,
            String fileReference,
            String contentType,
            String sourceReference,
            String reviewReason,
            String rejectionReason,
            Instant uploadedAt,
            String uploadedByUsername,
            String updatedByUsername,
            String createdAt,
            String updatedAt
    ) {
    }
}
