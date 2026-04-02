package com.bhawana.lms.web;

import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.service.LoanApplicationService;
import jakarta.validation.Valid;
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

    private final LoanApplicationService loanApplicationService;

    public LspLoanApplicationApiController(LoanApplicationService loanApplicationService) {
        this.loanApplicationService = loanApplicationService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_READ','LSP_UI_WRITE')")
    public List<LoanApplicationOpsController.LoanApplicationResponse> listApplications(
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
    public LoanApplicationOpsController.LoanApplicationDetailResponse getApplication(
            Authentication authentication,
            @PathVariable UUID applicationId
    ) {
        LoanApplication application = loanApplicationService.getApplicationForLsp(authenticatedLspId(authentication), applicationId);
        return toDetailResponse(application);
    }

    @GetMapping("/external/{externalLoanId}")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_READ','LSP_UI_WRITE')")
    public LoanApplicationOpsController.LoanApplicationDetailResponse getApplicationByExternalLoanId(
            Authentication authentication,
            @PathVariable String externalLoanId
    ) {
        LoanApplication application = loanApplicationService.getApplicationForLspByExternalLoanId(
                authenticatedLspId(authentication),
                externalLoanId
        );
        return toDetailResponse(application);
    }

    @PostMapping
    @PreAuthorize("hasRole('LSP_API_CLIENT')")
    public LoanApplicationOpsController.LoanApplicationResponse createApplication(
            Authentication authentication,
            @Valid @RequestBody LspLoanApplicationRequest request
    ) {
        LoanApplication application = loanApplicationService.createApplication(
                authentication.getName(),
                authenticatedLspId(authentication),
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

    private LoanApplicationOpsController.LoanApplicationDetailResponse toDetailResponse(LoanApplication application) {
        UUID applicationId = application.getId();
        return new LoanApplicationOpsController.LoanApplicationDetailResponse(
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
                toLoanAccountSummary(
                        loanApplicationService.getLoanAccount(applicationId).orElse(null),
                        loanApplicationService.getLoanRepaymentScheduleSummary(applicationId).orElse(null),
                        loanApplicationService.getLoanDelinquencySummary(applicationId).orElse(null)
                ),
                loanApplicationService.getLatestActivity(applicationId)
                        .map(activity -> new LoanApplicationOpsController.LoanApplicationLastActivityResponse(
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

    private LoanApplicationOpsController.LoanAccountSummaryResponse toLoanAccountSummary(
            LoanAccount loanAccount,
            LoanApplicationService.LoanRepaymentScheduleSummary repaymentScheduleSummary,
            LoanApplicationService.LoanDelinquencySummary delinquencySummary
    ) {
        if (loanAccount == null) {
            return null;
        }
        return new LoanApplicationOpsController.LoanAccountSummaryResponse(
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
                delinquencySummary == null ? null : new LoanApplicationOpsController.LoanDelinquencySummaryResponse(
                        delinquencySummary.maxDaysPastDue(),
                        delinquencySummary.bucket().name(),
                        delinquencySummary.overdueInstallmentCount(),
                        delinquencySummary.overdueAmount()
                ),
                repaymentScheduleSummary == null ? null : new LoanApplicationOpsController.LoanRepaymentScheduleSummaryResponse(
                        repaymentScheduleSummary.installmentCount(),
                        repaymentScheduleSummary.installmentAmount(),
                        repaymentScheduleSummary.firstDueDate(),
                        repaymentScheduleSummary.finalDueDate()
                )
        );
    }

    private static LoanApplicationOpsController.LoanApplicationResponse toResponse(LoanApplication application) {
        return new LoanApplicationOpsController.LoanApplicationResponse(
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
}
