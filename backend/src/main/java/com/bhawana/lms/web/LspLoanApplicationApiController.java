package com.bhawana.lms.web;

import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.service.LoanApplicationOnboardingCommand;
import com.bhawana.lms.service.LoanApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/lsp/loan-applications")
public class LspLoanApplicationApiController {

    private static final String DEFAULT_SOURCE_CHANNEL = "ONBOARDING_API";

    private final LoanApplicationService loanApplicationService;

    public LspLoanApplicationApiController(LoanApplicationService loanApplicationService) {
        this.loanApplicationService = loanApplicationService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_READ','LSP_UI_WRITE')")
    public List<LspLoanApplicationResponse> listApplications(
            Authentication authentication,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sourceChannel,
            @RequestParam(required = false, name = "q") String query
    ) {
        UUID lspId = authenticatedLspId(authentication);
        return loanApplicationService.listApplicationsForLsp(lspId, productId, status, sourceChannel, query).stream()
                .map(LspLoanApplicationApiController::toResponse)
                .toList();
    }

    @GetMapping("/{applicationId}")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_READ','LSP_UI_WRITE')")
    public LspLoanApplicationDetailResponse getApplication(
            Authentication authentication,
            @PathVariable UUID applicationId
    ) {
        LoanApplication application = loanApplicationService.getApplicationForLsp(authenticatedLspId(authentication), applicationId);
        return toDetailResponse(application, loanApplicationService);
    }

    @GetMapping("/external/{externalLoanId}")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_READ','LSP_UI_WRITE')")
    public LspLoanApplicationDetailResponse getApplicationByExternalLoanId(
            Authentication authentication,
            @PathVariable String externalLoanId
    ) {
        LoanApplication application = loanApplicationService.getApplicationForLspByExternalLoanId(
                authenticatedLspId(authentication),
                externalLoanId
        );
        return toDetailResponse(application, loanApplicationService);
    }

    @PostMapping
    @PreAuthorize("hasRole('LSP_API_CLIENT')")
    public LspLoanApplicationResponse createApplication(
            Authentication authentication,
            @Valid @RequestBody LspLoanApplicationRequest request
    ) {
        UUID authenticatedLspId = authenticatedLspId(authentication);
        if (!authenticatedLspId.equals(request.lspId())) {
            throw new AccessDeniedException("Request lspId does not match authenticated LSP context.");
        }

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
        return toResponse(application);
    }

    @PostMapping("/{applicationId}/documents")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_WRITE')")
    public LoanApplicationOpsController.LoanApplicationDocumentChecklistResponse submitDocument(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @Valid @RequestBody LspLoanApplicationDocumentRequest request
    ) {
        LoanApplicationDocumentChecklist checklistItem = loanApplicationService.submitDocumentForLsp(
                authenticatedLspId(authentication),
                applicationId,
                request.documentType(),
                authentication.getName(),
                request.note(),
                request.fileName(),
                request.fileReference(),
                request.sourceReference(),
                request.contentType()
        );
        return LoanApplicationOpsController.toDocumentChecklistResponse(checklistItem);
    }

    static LspLoanApplicationDetailResponse toDetailResponse(
            LoanApplication application,
            LoanApplicationService loanApplicationService
    ) {
        UUID applicationId = application.getId();
        return new LspLoanApplicationDetailResponse(
                application.getId().toString(),
                application.getBorrower().getId().toString(),
                application.getBorrower().getFullName(),
                application.getBorrower().getEmail(),
                application.getBorrower().getMobile(),
                application.getBorrower().getDateOfBirth(),
                application.getBorrower().getGender(),
                application.getBorrower().getMaritalStatus(),
                application.getBorrower().getFatherName(),
                application.getBorrower().getAadharNumber(),
                application.getBorrower().getPan(),
                application.getLoanProduct().getId().toString(),
                application.getLoanProduct().getCode(),
                application.getLoanProduct().getName(),
                application.getRequestedAmount(),
                application.getLoanProduct().getInterestRate(),
                application.getRequestedTenureMonths(),
                application.getExternalLoanId(),
                application.getLsp().getId().toString(),
                application.getLsp().getCode(),
                application.getLsp().getName(),
                application.getBorrower().getAddressLine1(),
                application.getBorrower().getAddressLine2(),
                application.getBorrower().getCity(),
                application.getBorrower().getState(),
                application.getBorrower().getAddressZipCode(),
                application.getBorrower().getSpouseName(),
                application.getBorrower().getEmploymentType(),
                application.getBorrower().getOrganizationName(),
                application.getBorrower().getEmployeeId(),
                application.getBorrower().getEmploymentCity(),
                application.getBorrower().getEmploymentState(),
                application.getBorrower().getEmploymentZip(),
                application.getBorrower().getMonthlyIncome(),
                application.getBorrower().getAnnualIncome(),
                application.getBorrower().getBankAccountNumber(),
                application.getBorrower().getBankName(),
                application.getBorrower().getIfscCode(),
                application.getBorrower().getAccountHolderName(),
                application.getBorrower().getReferencePersonName(),
                application.getBorrower().getReferencePersonNumber(),
                application.getSourceChannel(),
                application.getStatus().name(),
                application.getAssignedToUsername(),
                application.getAssignedByUsername(),
                application.getAssignedAt(),
                application.getCreatedAt().toString(),
                application.getUpdatedAt().toString(),
                toLoanAccountSummary(
                        loanApplicationService.getLoanAccount(applicationId).orElse(null),
                        loanApplicationService.getLoanRepaymentScheduleSummary(applicationId).orElse(null),
                        loanApplicationService.getLoanDelinquencySummary(applicationId).orElse(null)
                ),
                loanApplicationService.getLatestActivity(applicationId)
                        .map(activity -> new LoanApplicationLastActivityResponse(
                                activity.activityType(),
                                activity.actorUsername(),
                                activity.summary(),
                                activity.detail(),
                                activity.correlationId(),
                                activity.occurredAt().toString()
                        ))
                        .orElse(null)
        );
    }

    private static LspLoanAccountSummaryResponse toLoanAccountSummary(
            LoanAccount loanAccount,
            LoanApplicationService.LoanRepaymentScheduleSummary repaymentScheduleSummary,
            LoanApplicationService.LoanDelinquencySummary delinquencySummary
    ) {
        if (loanAccount == null) {
            return null;
        }
        return new LspLoanAccountSummaryResponse(
                loanAccount.getId().toString(),
                loanAccount.getAccountNumber(),
                loanAccount.getStatus().name(),
                loanAccount.getPrincipalAmount(),
                loanAccount.getTenureMonths(),
                loanAccount.getApprovedAt().toString(),
                loanAccount.getCreatedAt().toString(),
                loanAccount.getClosureReason() == null ? null : loanAccount.getClosureReason().name(),
                loanAccount.getClosedAt() == null ? null : loanAccount.getClosedAt().toString(),
                loanAccount.getClosedByUsername(),
                delinquencySummary == null ? null : new LspLoanDelinquencySummaryResponse(
                        delinquencySummary.maxDaysPastDue(),
                        delinquencySummary.bucket().name(),
                        delinquencySummary.overdueInstallmentCount(),
                        delinquencySummary.overdueAmount()
                ),
                repaymentScheduleSummary == null ? null : new LspLoanRepaymentScheduleSummaryResponse(
                        repaymentScheduleSummary.installmentCount(),
                        repaymentScheduleSummary.installmentAmount(),
                        repaymentScheduleSummary.firstDueDate(),
                        repaymentScheduleSummary.finalDueDate()
                )
        );
    }

    static LspLoanApplicationResponse toResponse(LoanApplication application) {
        return new LspLoanApplicationResponse(
                application.getId().toString(),
                application.getBorrower().getId().toString(),
                application.getBorrower().getFullName(),
                application.getBorrower().getEmail(),
                application.getBorrower().getMobile(),
                application.getBorrower().getDateOfBirth(),
                application.getBorrower().getGender(),
                application.getBorrower().getMaritalStatus(),
                application.getBorrower().getFatherName(),
                application.getBorrower().getAadharNumber(),
                application.getBorrower().getPan(),
                application.getLoanProduct().getId().toString(),
                application.getLoanProduct().getCode(),
                application.getLoanProduct().getName(),
                application.getRequestedAmount(),
                application.getLoanProduct().getInterestRate(),
                application.getRequestedTenureMonths(),
                application.getExternalLoanId(),
                application.getLsp().getId().toString(),
                application.getLsp().getCode(),
                application.getLsp().getName(),
                application.getBorrower().getAddressLine1(),
                application.getBorrower().getAddressLine2(),
                application.getBorrower().getCity(),
                application.getBorrower().getState(),
                application.getBorrower().getAddressZipCode(),
                application.getBorrower().getSpouseName(),
                application.getBorrower().getEmploymentType(),
                application.getBorrower().getOrganizationName(),
                application.getBorrower().getEmployeeId(),
                application.getBorrower().getEmploymentCity(),
                application.getBorrower().getEmploymentState(),
                application.getBorrower().getEmploymentZip(),
                application.getBorrower().getMonthlyIncome(),
                application.getBorrower().getAnnualIncome(),
                application.getBorrower().getBankAccountNumber(),
                application.getBorrower().getBankName(),
                application.getBorrower().getIfscCode(),
                application.getBorrower().getAccountHolderName(),
                application.getBorrower().getReferencePersonName(),
                application.getBorrower().getReferencePersonNumber(),
                application.getSourceChannel(),
                application.getStatus().name(),
                application.getAssignedToUsername(),
                application.getAssignedByUsername(),
                application.getAssignedAt(),
                application.getCreatedAt().toString()
        );
    }

    private static UUID authenticatedLspId(Authentication authentication) {
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            String rawLspId = jwt.getClaimAsString("lspId");
            if (rawLspId != null && !rawLspId.isBlank()) {
                return UUID.fromString(rawLspId);
            }
        }
        throw new AccessDeniedException("Authenticated LSP context is missing.");
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
            String assignedToUsername,
            String assignedByUsername,
            Instant assignedAt,
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
            String assignedToUsername,
            String assignedByUsername,
            Instant assignedAt,
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

    public record LoanApplicationLastActivityResponse(
            String activityType,
            String actorUsername,
            String summary,
            String detail,
            String correlationId,
            String occurredAt
    ) {
    }
}
