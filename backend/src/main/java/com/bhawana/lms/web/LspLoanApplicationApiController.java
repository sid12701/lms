package com.bhawana.lms.web;

import com.bhawana.lms.common.web.PagedResult;
import com.bhawana.lms.common.web.PaginationResponseBuilder;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.domain.LoanInvalidationReason;
import com.bhawana.lms.service.LoanApplicationOnboardingCommand;
import com.bhawana.lms.service.LoanApplicationService;
import com.bhawana.lms.service.LoanDisbursementService;
import com.bhawana.lms.service.LoanDocumentService;
import com.bhawana.lms.service.LspApiIdempotencyService;
import com.bhawana.lms.service.LoanRepaymentScheduleService;
import com.bhawana.lms.tenant.TenantAccessContext;
import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/lsp/loan-applications")
public class LspLoanApplicationApiController {

    private static final String DEFAULT_SOURCE_CHANNEL = "ONBOARDING_API";
    private static final String INVALID_LOAN_OPERATION_KEY = "LOAN_APPLICATION_INVALIDATION";

    private final LoanApplicationService loanApplicationService;
    private final LoanDocumentService loanDocumentService;
    private final LoanRepaymentScheduleService loanRepaymentScheduleService;
    private final LspApiIdempotencyService lspApiIdempotencyService;
    private final LoanDisbursementService loanDisbursementService;

    public LspLoanApplicationApiController(
            LoanApplicationService loanApplicationService,
            LoanDocumentService loanDocumentService,
            LoanRepaymentScheduleService loanRepaymentScheduleService,
            LspApiIdempotencyService lspApiIdempotencyService,
            LoanDisbursementService loanDisbursementService
    ) {
        this.loanApplicationService = loanApplicationService;
        this.loanDocumentService = loanDocumentService;
        this.loanRepaymentScheduleService = loanRepaymentScheduleService;
        this.lspApiIdempotencyService = lspApiIdempotencyService;
        this.loanDisbursementService = loanDisbursementService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_READ','LSP_UI_WRITE')")
    public ResponseEntity<List<LspLoanApplicationResponse>> listApplications(
            Authentication authentication,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sourceChannel,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) @Min(0) Integer offset,
            @RequestParam(required = false) @Min(1) @Max(1000) Integer limit,
            @RequestParam(required = false) String paginationDetails
    ) {
        UUID lspId = LspAuthenticationSupport.authenticatedLspId(authentication);
        boolean includePaginationDetails = PaginationResponseBuilder.includePaginationDetails(paginationDetails);
        PagedResult<LoanApplication> applicationsPage = loanApplicationService.listApplicationsForLspPage(
                lspId,
                productId,
                status,
                sourceChannel,
                query,
                offset,
                limit,
                includePaginationDetails
        );
        PagedResult<LspLoanApplicationResponse> page = new PagedResult<>(
                applicationsPage.items().stream()
                .map(LspLoanApplicationResponses::toResponse)
                .toList(),
                applicationsPage.totalCount(),
                applicationsPage.offset(),
                applicationsPage.limit()
        );
        return PaginationResponseBuilder.toListResponse(page, includePaginationDetails);
    }

    @GetMapping("/invalid-reasons")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_READ','LSP_UI_WRITE')")
    public List<LspInvalidLoanReasonOptionResponse> listInvalidLoanReasons() {
        return java.util.Arrays.stream(LoanInvalidationReason.values())
                .map(reason -> new LspInvalidLoanReasonOptionResponse(
                        reason.name(),
                        reason.getLabel(),
                        reason.requiresDetail()
                ))
                .toList();
    }

    @GetMapping("/{applicationId}")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_READ','LSP_UI_WRITE')")
    public LspLoanApplicationDetailResponse getApplication(
            Authentication authentication,
            @PathVariable UUID applicationId
    ) {
        LoanApplication application = loanApplicationService.getApplicationForLsp(
                LspAuthenticationSupport.authenticatedLspId(authentication),
                applicationId
        );
        return LspLoanApplicationResponses.toDetailResponse(application, loanApplicationService);
    }

    @GetMapping("/external/{externalLoanId}")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_READ','LSP_UI_WRITE')")
    public LspLoanApplicationDetailResponse getApplicationByExternalLoanId(
            Authentication authentication,
            @PathVariable String externalLoanId
    ) {
        LoanApplication application = loanApplicationService.getApplicationForLspByExternalLoanId(
                LspAuthenticationSupport.authenticatedLspId(authentication),
                externalLoanId
        );
        return LspLoanApplicationResponses.toDetailResponse(application, loanApplicationService);
    }

    @PostMapping("/{applicationId}/invalid")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_WRITE')")
    public LspLoanApplicationDetailResponse invalidateApplication(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody LspInvalidLoanRequest request
    ) {
        UUID lspId = LspAuthenticationSupport.authenticatedLspId(authentication);
        return lspApiIdempotencyService.execute(
                lspId,
                INVALID_LOAN_OPERATION_KEY,
                idempotencyKey,
                new InvalidLoanIdempotencyFingerprint(
                        applicationId.toString(),
                        request.reasonCode().name(),
                        normalizeOptional(request.reasonText())
                ),
                LspLoanApplicationDetailResponse.class,
                () -> {
                    LoanApplication application = loanApplicationService.invalidateApplicationForLsp(
                            lspId,
                            applicationId,
                            authentication.getName(),
                            request.reasonCode(),
                            request.reasonText()
                    );
                    return LspLoanApplicationResponses.toDetailResponse(application, loanApplicationService);
                }
        );
    }

    @PostMapping
    @PreAuthorize("hasRole('LSP_API_CLIENT')")
    public LspLoanApplicationResponse createApplication(
            Authentication authentication,
            @Valid @RequestBody LspLoanApplicationRequest request
    ) {
        UUID authenticatedLspId = LspAuthenticationSupport.authenticatedLspId(authentication);
        if (!authenticatedLspId.equals(request.lspId())) {
            throw new AccessDeniedException("Request lspId does not match authenticated LSP context.");
        }

        TenantAccessContext previous = TenantDataAccessContextHolder.snapshot();
        TenantDataAccessContextHolder.useAdmin();
        try {
            LoanApplication application = loanApplicationService.createApplication(
                    authentication.getName(),
                    new LoanApplicationOnboardingCommand(
                            authenticatedLspId,
                            request.productId(),
                            request.loanProduct(),
                            request.lspLoanId(),
                            DEFAULT_SOURCE_CHANNEL,
                            request.fullName(),
                            request.emailAddress(),
                            request.mobileNumber(),
                            request.dob(),
                            request.gender(),
                            request.maritalStatus(),
                            request.fatherName(),
                            request.aadharNumber(),
                            request.panNumber(),
                            request.loanAmount(),
                            request.interestRate(),
                            request.loanTenure(),
                            request.addressLine1(),
                            request.addressLine2(),
                            request.addressCity(),
                            request.addressState(),
                            request.addressZipcode(),
                            request.spouseName(),
                            request.employmentStatus(),
                            request.organizationName(),
                            request.empId(),
                            request.employmentCity(),
                            request.employmentState(),
                            request.employmentZip(),
                            request.monthlyIncome(),
                            request.annualIncome(),
                            request.bankAccountNumber(),
                            request.bankName(),
                            request.ifscCode(),
                            request.accountHolderName(),
                            request.referencePersonName(),
                            request.referencePersonNumber()
                    )
            );
            return LspLoanApplicationResponses.toResponse(application);
        } finally {
            TenantDataAccessContextHolder.restore(previous);
        }
    }

    /**
     * Gap #4: LSP-scoped, uploads-only document checklist read. Returns every
     * checklist row for the loan that has a non-PENDING / non-NOT_REQUIRED
     * status (i.e. something was submitted), with the status folded into the
     * `PENDING | SUBMITTED` two-value enum the Gap #18 design locks in for
     * external API consumers. `downloadUrl` and `?includePending=true` are
     * deferred to post-prod (both purely additive).
     */
    @GetMapping("/{applicationId}/documents")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_READ','LSP_UI_WRITE')")
    public List<LspDocumentChecklistResponse> listSubmittedDocuments(
            Authentication authentication,
            @PathVariable UUID applicationId
    ) {
        UUID lspId = LspAuthenticationSupport.authenticatedLspId(authentication);
        return loanApplicationService.listSubmittedDocumentsForLsp(lspId, applicationId).stream()
                .map(LspLoanApplicationApiController::toLspDocumentResponse)
                .toList();
    }

    private static LspDocumentChecklistResponse toLspDocumentResponse(LoanApplicationDocumentChecklist item) {
        return new LspDocumentChecklistResponse(
                item.getDocumentType().name(),
                "SUBMITTED",
                item.getFileName(),
                item.getContentType(),
                item.getNote(),
                item.getUpdatedAt() == null ? null : item.getUpdatedAt().toString(),
                item.getUpdatedByUsername()
        );
    }

    @PostMapping(path = "/{applicationId}/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_WRITE')")
    public LoanApplicationOpsController.LoanApplicationDocumentChecklistResponse submitDocumentMetadata(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @Valid @RequestBody LspLoanApplicationDocumentRequest request
    ) {
        LoanApplicationDocumentChecklist checklistItem = loanApplicationService.submitDocumentForLsp(
                LspAuthenticationSupport.authenticatedLspId(authentication),
                applicationId,
                request.documentType(),
                authentication.getName(),
                request.note(),
                request.fileName(),
                request.fileReference(),
                request.sourceReference(),
                request.contentType()
        );
        return LoanApplicationOpsResponses.toDocumentChecklistResponse(checklistItem);
    }

    @PostMapping(path = "/{applicationId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_WRITE')")
    public LoanApplicationOpsController.LoanApplicationDocumentChecklistResponse uploadDocument(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @RequestParam LoanApplicationDocumentType documentType,
            @RequestParam(required = false) String note,
            @RequestParam(required = false) String sourceReference,
            @RequestPart("file") MultipartFile file
    ) {
        LoanApplicationDocumentChecklist checklistItem = loanDocumentService.submitStoredDocumentForLsp(
                LspAuthenticationSupport.authenticatedLspId(authentication),
                applicationId,
                documentType,
                authentication.getName(),
                note,
                sourceReference,
                file
        );
        return LoanApplicationOpsResponses.toDocumentChecklistResponse(checklistItem);
    }

    @PostMapping(path = "/{applicationId}/documents/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_WRITE')")
    public List<LoanApplicationOpsController.LoanApplicationDocumentChecklistResponse> uploadDocumentsBatch(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @Valid @RequestPart("documents") List<LspBatchDocumentUploadRequest> documents,
            @RequestPart("files") List<MultipartFile> files
    ) {
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("At least one document metadata item is required.");
        }
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("At least one document file is required.");
        }
        if (documents.size() != files.size()) {
            throw new IllegalArgumentException("Document metadata and file counts must match.");
        }

        List<LoanDocumentService.BatchDocumentUpload> uploads = new ArrayList<>(documents.size());
        for (int index = 0; index < documents.size(); index++) {
            LspBatchDocumentUploadRequest metadata = documents.get(index);
            uploads.add(new LoanDocumentService.BatchDocumentUpload(
                    metadata.documentType(),
                    metadata.note(),
                    metadata.sourceReference(),
                    files.get(index)
            ));
        }

        return loanDocumentService.submitStoredDocumentsForLsp(
                        LspAuthenticationSupport.authenticatedLspId(authentication),
                        applicationId,
                        authentication.getName(),
                        uploads
                ).stream()
                .map(LoanApplicationOpsResponses::toDocumentChecklistResponse)
                .toList();
    }

    @PutMapping("/{applicationId}/repayment-schedule")
    @PreAuthorize("hasRole('LSP_API_CLIENT')")
    public List<LoanApplicationOpsController.LoanRepaymentScheduleInstallmentResponse> upsertRepaymentSchedule(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @Valid @RequestBody LspRepaymentScheduleUpsertRequest request
    ) {
        UUID lspId = LspAuthenticationSupport.authenticatedLspId(authentication);
        List<com.bhawana.lms.domain.LoanRepaymentScheduleInstallment> installments =
                request.mode() == LspRepaymentScheduleMode.GENERATED
                        ? loanRepaymentScheduleService.replaceWithGeneratedScheduleForLsp(lspId, applicationId)
                        : loanRepaymentScheduleService.replaceWithProvidedScheduleForLsp(
                                lspId,
                                applicationId,
                                request.installments().stream()
                                        .map(installment -> new LoanRepaymentScheduleService.InstallmentDraft(
                                                installment.installmentNumber(),
                                                installment.dueDate(),
                                                installment.openingPrincipal(),
                                                installment.principalDue(),
                                                installment.interestDue(),
                                                installment.installmentAmount(),
                                                installment.closingPrincipal()
                                        ))
                                        .toList()
                        );
        return installments.stream()
                .map(LoanApplicationOpsResponses::toRepaymentScheduleInstallmentResponse)
                .toList();
    }

    @PostMapping("/{applicationId}/disbursement-bank-check")
    @PreAuthorize("hasRole('LSP_API_CLIENT')")
    public void verifyDisbursementBankDetails(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @Valid @RequestBody LspLoanDisbursementRequest request
    ) {
        loanDisbursementService.verifyDisbursementBankDetailsForLsp(
                LspAuthenticationSupport.authenticatedLspId(authentication),
                applicationId,
                request.bankAccountNumber(),
                request.ifscCode(),
                request.accountHolderName()
        );
    }

    public record LspLoanApplicationRequest(
            @NotNull UUID lspId,
            UUID productId,
            @Size(max = 128) String loanProduct,
            @NotBlank String lspLoanId,
            @NotBlank String fullName,
            @Email String emailAddress,
            @NotBlank @Pattern(regexp = "^[0-9]{10,15}$", message = "Mobile must contain 10 to 15 digits") String mobileNumber,
            @Past LocalDate dob,
            @Size(max = 32) String gender,
            @Size(max = 32) String maritalStatus,
            @Size(max = 255) String fatherName,
            @Pattern(regexp = "^[0-9]{12}$", message = "Aadhaar number must contain 12 digits") String aadharNumber,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{5}[0-9]{4}[A-Za-z]$", message = "PAN must be a valid 10-character PAN") String panNumber,
            @NotNull @DecimalMin("0.01") BigDecimal loanAmount,
            @DecimalMin("0.01") BigDecimal interestRate,
            @NotNull @Min(1) Integer loanTenure,
            @Size(max = 255) String addressLine1,
            @Size(max = 255) String addressLine2,
            @Size(max = 128) String addressCity,
            @Size(max = 128) String addressState,
            @Size(max = 16) String addressZipcode,
            @Size(max = 255) String spouseName,
            @Size(max = 64) String employmentStatus,
            @Size(max = 255) String organizationName,
            @Size(max = 128) String empId,
            @Size(max = 128) String employmentCity,
            @Size(max = 128) String employmentState,
            @Size(max = 16) String employmentZip,
            @DecimalMin("0.01") BigDecimal monthlyIncome,
            @DecimalMin("0.01") BigDecimal annualIncome,
            @Size(max = 64) String bankAccountNumber,
            @Size(max = 255) String bankName,
            @Pattern(regexp = "^[A-Za-z]{4}0[A-Za-z0-9]{6}$", message = "IFSC code must be valid") String ifscCode,
            @Size(max = 255) String accountHolderName,
            @Size(max = 255) String referencePersonName,
            @Pattern(regexp = "^[0-9]{10,15}$", message = "Reference person number must contain 10 to 15 digits") String referencePersonNumber
    ) {
        @AssertTrue(message = "Either productId or loanProduct is required")
        public boolean hasProductSelection() {
            return productId != null || (loanProduct != null && !loanProduct.isBlank());
        }

        @AssertTrue(message = "Either monthlyIncome or annualIncome is required")
        public boolean hasIncomeInformation() {
            return monthlyIncome != null || annualIncome != null;
        }
    }

    public record LspLoanApplicationDocumentRequest(
            @NotNull LoanApplicationDocumentType documentType,
            @Size(max = 500) String note,
            @Size(max = 255) String fileName,
            @Size(max = 500) String fileReference,
            @Size(max = 500) String sourceReference,
            @Size(max = 128) String contentType
    ) {
    }

    public record LspBatchDocumentUploadRequest(
            @NotNull LoanApplicationDocumentType documentType,
            @Size(max = 500) String note,
            @Size(max = 500) String sourceReference
    ) {
    }

    public record LspRepaymentScheduleUpsertRequest(
            @NotNull LspRepaymentScheduleMode mode,
            List<LspRepaymentScheduleInstallmentRequest> installments
    ) {
        public LspRepaymentScheduleUpsertRequest {
            if (installments == null) {
                installments = List.of();
            }
        }
    }

    public record LspRepaymentScheduleInstallmentRequest(
            @NotNull @Min(1) Integer installmentNumber,
            @NotNull LocalDate dueDate,
            @NotNull @DecimalMin("0.00") BigDecimal openingPrincipal,
            @NotNull @DecimalMin("0.00") BigDecimal principalDue,
            @NotNull @DecimalMin("0.00") BigDecimal interestDue,
            @NotNull @DecimalMin("0.00") BigDecimal installmentAmount,
            @NotNull @DecimalMin("0.00") BigDecimal closingPrincipal
    ) {
    }

    public record LspLoanDisbursementRequest(
            @NotNull @DecimalMin("0.01") BigDecimal disbursalAmount,
            @NotBlank @Size(max = 64) String bankAccountNumber,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{4}0[A-Za-z0-9]{6}$", message = "IFSC code must be valid") String ifscCode,
            @Size(max = 255) String accountHolderName
    ) {
    }

    public record LspInvalidLoanRequest(
            @NotNull LoanInvalidationReason reasonCode,
            @Size(max = 500) String reasonText
    ) {
    }

    public record LspInvalidLoanReasonOptionResponse(
            String code,
            String label,
            boolean requiresText
    ) {
    }

    public enum LspRepaymentScheduleMode {
        GENERATED,
        LSP_PROVIDED
    }

    public record LspLoanApplicationResponse(
            String id,
            String borrowerId,
            String fullName,
            String emailAddress,
            String mobileNumber,
            LocalDate dob,
            String gender,
            String maritalStatus,
            String fatherName,
            String aadharNumber,
            String panNumber,
            String loanProductId,
            String loanProductCode,
            String loanProductName,
            BigDecimal loanAmount,
            BigDecimal interestRate,
            Integer loanTenure,
            String lspLoanId,
            String lspId,
            String lspCode,
            String lspName,
            String addressLine1,
            String addressLine2,
            String addressCity,
            String addressState,
            String addressZipcode,
            String spouseName,
            String employmentStatus,
            String organizationName,
            String empId,
            String employmentCity,
            String employmentState,
            String employmentZip,
            BigDecimal monthlyIncome,
            BigDecimal annualIncome,
            String bankAccountNumber,
            String bankName,
            String ifscCode,
            String accountHolderName,
            String referencePersonName,
            String referencePersonNumber,
            String sourceChannel,
            String status,
            String createdAt
    ) {
    }

    public record LspLoanApplicationDetailResponse(
            String id,
            String borrowerId,
            String fullName,
            String emailAddress,
            String mobileNumber,
            LocalDate dob,
            String gender,
            String maritalStatus,
            String fatherName,
            String aadharNumber,
            String panNumber,
            String loanProductId,
            String loanProductCode,
            String loanProductName,
            BigDecimal loanAmount,
            BigDecimal interestRate,
            Integer loanTenure,
            String lspLoanId,
            String lspId,
            String lspCode,
            String lspName,
            String addressLine1,
            String addressLine2,
            String addressCity,
            String addressState,
            String addressZipcode,
            String spouseName,
            String employmentStatus,
            String organizationName,
            String empId,
            String employmentCity,
            String employmentState,
            String employmentZip,
            BigDecimal monthlyIncome,
            BigDecimal annualIncome,
            String bankAccountNumber,
            String bankName,
            String ifscCode,
            String accountHolderName,
            String referencePersonName,
            String referencePersonNumber,
            String sourceChannel,
            String status,
            String invalidReasonCode,
            String invalidReasonText,
            String invalidatedByUsername,
            String invalidatedAt,
            String createdAt,
            String updatedAt,
            LspLoanAccountSummaryResponse loanAccount,
            LoanApplicationLastActivityResponse lastActivity
    ) {
    }

    public record LspLoanAccountSummaryResponse(
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
            LspLoanDelinquencySummaryResponse delinquency,
            LspLoanRepaymentScheduleSummaryResponse repaymentSchedule
    ) {
    }

    public record LspLoanDelinquencySummaryResponse(
            Integer maxDaysPastDue,
            String bucket,
            Integer overdueInstallmentCount,
            BigDecimal overdueAmount
    ) {
    }

    public record LspLoanRepaymentScheduleSummaryResponse(
            Integer installmentCount,
            BigDecimal installmentAmount,
            LocalDate firstDueDate,
            LocalDate finalDueDate
    ) {
    }

    /**
     * Gap #4: minimum-viable LSP-facing document checklist row.
     * `status` is one of `PENDING | SUBMITTED` (per Gap #18 lock-in).
     * Optional fields are nullable so JSON omission keeps the wire shape
     * tight when not yet populated.
     */
    public record LspDocumentChecklistResponse(
            String documentType,
            String status,
            String fileName,
            String contentType,
            String note,
            String uploadedAt,
            String uploadedByUsername
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

    private record InvalidLoanIdempotencyFingerprint(
            String applicationId,
            String reasonCode,
            String reasonText
    ) {
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }
}
