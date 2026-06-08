package com.bhawana.lms.web;

import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusReasonCode;
import com.bhawana.lms.common.web.PagedResult;
import com.bhawana.lms.domain.LoanPaymentChannel;
import com.bhawana.lms.common.web.PaginationResponseBuilder;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.web.ClientIpAddresses;
import com.bhawana.lms.service.LoanApplicationOnboardingCommand;
import com.bhawana.lms.service.LoanApplicationService;
import com.bhawana.lms.service.LoanApplicationWebhookEventProjection;
import com.bhawana.lms.service.LoanDocumentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    public ResponseEntity<List<LoanApplicationResponse>> listApplications(
            @RequestParam(required = false) UUID lspId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sourceChannel,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) String lspLoanId,
            @RequestParam(required = false) String bhawLoanId,
            @RequestParam(required = false) LocalDate disbursalDateFrom,
            @RequestParam(required = false) LocalDate disbursalDateTo,
            @RequestParam(required = false) @Min(0) Integer offset,
            @RequestParam(required = false) @Min(1) @Max(1000) Integer limit,
            @RequestParam(required = false) String paginationDetails
    ) {
        boolean includePaginationDetails = PaginationResponseBuilder.includePaginationDetails(paginationDetails);
        PagedResult<LoanApplication> applicationsPage = loanApplicationService.listApplicationsPage(
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
        Map<UUID, String> accountNumbersByApplicationId = loanApplicationService
                .getLoanAccountNumbers(applicationsPage.items().stream().map(LoanApplication::getId).toList());
        PagedResult<LoanApplicationResponse> page = new PagedResult<>(
                applicationsPage.items().stream()
                .map(application -> LoanApplicationOpsResponses.toResponse(
                        application,
                        accountNumbersByApplicationId.get(application.getId())
                ))
                .toList(),
                applicationsPage.totalCount(),
                applicationsPage.offset(),
                applicationsPage.limit()
        );
        return PaginationResponseBuilder.toListResponse(page, includePaginationDetails);
    }

    @GetMapping("/{applicationId}")
    public LoanApplicationDetailResponse getApplication(@PathVariable UUID applicationId) {
        LoanApplication application = loanApplicationService.getApplication(applicationId);
        return LoanApplicationOpsResponses.toDetailResponse(
                application,
                loanApplicationService.getLatestActivity(applicationId).orElse(null),
                loanApplicationService.getLoanAccount(applicationId).orElse(null),
                loanApplicationService.getLoanRepaymentScheduleSummary(applicationId).orElse(null),
                loanApplicationService.getLoanDelinquencySummary(applicationId).orElse(null)
        );
    }

    @GetMapping("/{applicationId}/intake-audits")
    public List<LoanApplicationIntakeAuditResponse> listIntakeAudits(
            Authentication authentication,
            @PathVariable UUID applicationId
    ) {
        return loanApplicationService.listIntakeAudits(applicationId, authentication.getName()).stream()
                .map(LoanApplicationOpsResponses::toAuditResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/status-transitions")
    public List<LoanApplicationStatusTransitionResponse> listStatusTransitions(@PathVariable UUID applicationId) {
        return loanApplicationService.listStatusTransitions(applicationId).stream()
                .map(LoanApplicationOpsResponses::toTransitionResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/audit-events")
    public List<LoanApplicationAuditEventResponse> listAuditEvents(@PathVariable UUID applicationId) {
        return loanApplicationService.listAuditEvents(applicationId).stream()
                .map(LoanApplicationOpsResponses::toAuditEventResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/document-access-audits")
    public List<LoanApplicationDocumentAccessAuditResponse> listDocumentAccessAudits(@PathVariable UUID applicationId) {
        return loanApplicationService.listDocumentAccessAudits(applicationId).stream()
                .map(LoanApplicationOpsResponses::toDocumentAccessAuditResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/disbursement-requests")
    public List<LoanDisbursementRequestResponse> listDisbursementRequests(@PathVariable UUID applicationId) {
        return loanApplicationService.listDisbursementRequests(applicationId).stream()
                .map(LoanApplicationOpsResponses::toDisbursementRequestResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/repayment-schedule")
    public List<LoanRepaymentScheduleInstallmentResponse> listRepaymentSchedule(@PathVariable UUID applicationId) {
        return loanApplicationService.listRepaymentSchedule(applicationId).stream()
                .map(LoanApplicationOpsResponses::toRepaymentScheduleInstallmentResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/payments")
    public List<LoanPaymentTransactionResponse> listPaymentTransactions(@PathVariable UUID applicationId) {
        return loanApplicationService.listPaymentTransactions(applicationId).stream()
                .map(LoanApplicationOpsResponses::toPaymentTransactionResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/webhook-events")
    public List<WebhookEventDeliveryResponse> listWebhookEvents(@PathVariable UUID applicationId) {
        return loanApplicationService.listWebhookEventsForApplication(applicationId).stream()
                .map(WebhookEventDeliveryResponse::from)
                .toList();
    }

    @GetMapping("/{applicationId}/foreclosure-quotes")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public List<LoanForeclosureQuoteResponse> listForeclosureQuotes(@PathVariable UUID applicationId) {
        return loanApplicationService.listForeclosureQuotes(applicationId).stream()
                .map(LoanApplicationOpsResponses::toForeclosureQuoteResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/kyc-documents")
    public List<LoanApplicationDocumentChecklistResponse> listDocumentChecklist(
            Authentication authentication,
            @PathVariable UUID applicationId
    ) {
        return loanApplicationService.listDocumentChecklist(applicationId, authentication.getName()).stream()
                .map(LoanApplicationOpsResponses::toDocumentChecklistResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/kyc-documents/download-all")
    public ResponseEntity<byte[]> downloadAllDocuments(
            Authentication authentication,
            HttpServletRequest request,
            @PathVariable UUID applicationId
    ) {
        LoanDocumentService.ZipBuildResult zipResult = loanApplicationService.downloadDocumentZip(
                applicationId,
                authentication.getName(),
                ClientIpAddresses.resolve(request),
                CorrelationIdHolder.get()
        );
        byte[] zipContent = zipResult.content();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"loan-" + applicationId + "-documents.zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(zipContent.length)
                .body(zipContent);
    }

    @GetMapping("/{applicationId}/kyc-documents/{documentType}/content")
    public ResponseEntity<byte[]> downloadDocumentContent(
            Authentication authentication,
            HttpServletRequest request,
            @PathVariable UUID applicationId,
            @PathVariable LoanApplicationDocumentType documentType
    ) {
        LoanDocumentService.RetrievedDocumentContent content = loanApplicationService.downloadDocumentContent(
                applicationId,
                documentType,
                authentication.getName(),
                ClientIpAddresses.resolve(request),
                CorrelationIdHolder.get()
        );
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(content.fileName()).build().toString())
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.content().length)
                .body(content.content());
    }

    @PostMapping
    public LoanApplicationResponse createApplication(
            Authentication authentication,
            @Valid @RequestBody LoanApplicationRequest request
    ) {
        LoanApplication application = loanApplicationService.createApplication(
                authentication.getName(),
                new LoanApplicationOnboardingCommand(
                        request.lspId(),
                        request.productId(),
                        null,
                        request.externalLoanId(),
                        request.sourceChannel(),
                        request.borrowerFullName(),
                        request.borrowerEmail(),
                        request.borrowerMobile(),
                        request.borrowerDateOfBirth(),
                        null,
                        null,
                        null,
                        null,
                        request.borrowerPan(),
                        request.requestedAmount(),
                        null,
                        request.tenureMonths(),
                        null,
                        null,
                        request.borrowerCity(),
                        request.borrowerState(),
                        null,
                        null,
                        request.borrowerEmploymentType(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        request.borrowerMonthlyIncome(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        );
        return LoanApplicationOpsResponses.toResponse(application);
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
        return LoanApplicationOpsResponses.toDetailResponse(
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
        return LoanApplicationOpsResponses.toDetailResponse(
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
        throw new AccessDeniedException("Your role cannot perform this loan status transition.");
    }

    private static List<String> extractRoles(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> !"ROLE_PASSWORD_CHANGE_REQUIRED".equals(authority))
                .map(role -> role.startsWith("ROLE_") ? role.substring("ROLE_".length()) : role)
                .toList();
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
        return LoanApplicationOpsResponses.toDetailResponse(
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
            HttpServletRequest httpRequest,
            @PathVariable UUID applicationId,
            @Valid @RequestBody MockDisbursementOutcomeRequest request
    ) {
        LoanApplication application = loanApplicationService.resolveMockDisbursementOutcome(
                applicationId,
                authentication.getName(),
                ClientIpAddresses.resolve(httpRequest),
                CorrelationIdHolder.get(),
                request.outcome()
        );
        return LoanApplicationOpsResponses.toDetailResponse(
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
            @org.springframework.web.bind.annotation.RequestHeader(name = "Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody LoanPaymentTransactionRequest request
    ) {
        return LoanApplicationOpsResponses.toPaymentTransactionResponse(loanApplicationService.recordPaymentTransaction(
                applicationId,
                authentication.getName(),
                idempotencyKey,
                request.targetInstallmentId(),
                request.amount(),
                request.postedAt(),
                request.reference(),
                request.channel()
        ));
    }

    @PostMapping("/{applicationId}/foreclosure-quotes")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public LoanForeclosureQuoteResponse requestForeclosureQuote(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @Valid @RequestBody LoanForeclosureQuoteRequest request
    ) {
        return LoanApplicationOpsResponses.toForeclosureQuoteResponse(loanApplicationService.requestForeclosureQuote(
                applicationId,
                authentication.getName(),
                request.effectiveDate()
        ));
    }

    @PostMapping("/{applicationId}/foreclosure-quotes/{quoteId}/execute")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public LoanForeclosureQuoteResponse executeForeclosureQuote(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @PathVariable UUID quoteId,
            @Valid @RequestBody LoanForeclosureExecutionRequest request
    ) {
        return LoanApplicationOpsResponses.toForeclosureQuoteResponse(loanApplicationService.executeForeclosureQuote(
                applicationId,
                quoteId,
                authentication.getName(),
                request.settlementDate(),
                request.reference(),
                request.note()
        ));
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
            String accountNumber,
            String sourceChannel,
            BigDecimal requestedAmount,
            Integer tenureMonths,
            String status,
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
            String invalidReasonCode,
            String invalidReasonText,
            String invalidatedByUsername,
            String invalidatedAt,
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
            String closureReason,
            String closedAt,
            String closedByUsername,
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

    public record LoanPaymentTransactionRequest(
            @NotNull UUID targetInstallmentId,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotNull @PastOrPresent LocalDate postedAt,
            @NotNull LoanPaymentChannel channel,
            @Size(max = 128) String reference
    ) {
    }

    public record LoanForeclosureExecutionRequest(
            @NotNull @PastOrPresent LocalDate settlementDate,
            @NotBlank @Size(max = 128) String reference,
            @Size(max = 500) String note
    ) {
    }

    public record LoanForeclosureQuoteRequest(
            @NotNull LocalDate effectiveDate
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
            String createdAt,
            RejectionReason rejectionReason
    ) {
    }

    public record RejectionReason(java.util.List<String> failedRules) {
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
            String targetInstallmentId,
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

    public record LoanForeclosureQuoteResponse(
            String id,
            String loanAccountId,
            Integer version,
            String requestedByUsername,
            String executedByUsername,
            LocalDate effectiveDate,
            BigDecimal outstandingPrincipal,
            BigDecimal outstandingInterest,
            BigDecimal settlementAmount,
            String status,
            String executedAt,
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
            boolean lmsManagedContent,
            String storageKey,
            String fileChecksum,
            Long fileSizeBytes,
            Instant uploadedAt,
            String uploadedByUsername,
            String updatedByUsername,
            String createdAt,
            String updatedAt
    ) {
    }

    public record WebhookEventDeliveryResponse(
            String eventId,
            String eventType,
            String targetUrl,
            String status,
            int attempts,
            String lastAttemptAt,
            Integer lastResponseCode,
            String lastError,
            String createdAt
    ) {
        static WebhookEventDeliveryResponse from(LoanApplicationWebhookEventProjection projection) {
            return new WebhookEventDeliveryResponse(
                    projection.eventId(),
                    projection.eventType(),
                    projection.targetUrl(),
                    projection.status(),
                    projection.attempts(),
                    projection.lastAttemptAt() == null ? null : projection.lastAttemptAt().toString(),
                    projection.lastResponseCode(),
                    projection.lastError(),
                    projection.createdAt() == null ? null : projection.createdAt().toString()
            );
        }
    }

    public record LoanApplicationDocumentAccessAuditResponse(
            String id,
            String loanApplicationId,
            String action,
            String actorUsername,
            String summary,
            List<String> documentTypes,
            String correlationId,
            String actorIp,
            Long byteCount,
            String createdAt
    ) {
    }
}
