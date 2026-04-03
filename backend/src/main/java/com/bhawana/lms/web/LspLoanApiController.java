package com.bhawana.lms.web;

import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanForeclosureQuote;
import com.bhawana.lms.service.LoanApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/lsp/loans")
public class LspLoanApiController {

    private final LoanApplicationService loanApplicationService;

    public LspLoanApiController(LoanApplicationService loanApplicationService) {
        this.loanApplicationService = loanApplicationService;
    }

    @GetMapping("/{loanId}")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_READ','LSP_UI_WRITE')")
    public LoanApplicationOpsController.LoanApplicationDetailResponse getLoan(
            Authentication authentication,
            @PathVariable UUID loanId
    ) {
        LoanAccount loanAccount = loanApplicationService.getLoanAccountForLsp(authenticatedLspId(authentication), loanId);
        UUID applicationId = loanAccount.getLoanApplication().getId();
        return LoanApplicationOpsController.toDetailResponse(
                loanAccount.getLoanApplication(),
                loanApplicationService.getLatestActivity(applicationId).orElse(null),
                loanAccount,
                loanApplicationService.getLoanRepaymentScheduleSummary(applicationId).orElse(null),
                loanApplicationService.getLoanDelinquencySummary(applicationId).orElse(null)
        );
    }

    @GetMapping("/{loanId}/repayment-schedule")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_READ','LSP_UI_WRITE')")
    public List<LoanApplicationOpsController.LoanRepaymentScheduleInstallmentResponse> listRepaymentSchedule(
            Authentication authentication,
            @PathVariable UUID loanId
    ) {
        return loanApplicationService.listRepaymentScheduleForLsp(authenticatedLspId(authentication), loanId).stream()
                .map(LoanApplicationOpsController::toRepaymentScheduleInstallmentResponse)
                .toList();
    }

    @GetMapping("/{loanId}/payments")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_READ','LSP_UI_WRITE')")
    public List<LoanApplicationOpsController.LoanPaymentTransactionResponse> listPayments(
            Authentication authentication,
            @PathVariable UUID loanId
    ) {
        return loanApplicationService.listPaymentTransactionsForLsp(authenticatedLspId(authentication), loanId).stream()
                .map(LoanApplicationOpsController::toPaymentTransactionResponse)
                .toList();
    }

    @PostMapping("/{loanId}/foreclosure-quote")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_WRITE')")
    public LoanApplicationOpsController.LoanForeclosureQuoteResponse requestForeclosureQuote(
            Authentication authentication,
            @PathVariable UUID loanId,
            @Valid @RequestBody LspLoanForeclosureQuoteRequest request
    ) {
        LoanForeclosureQuote quote = loanApplicationService.requestForeclosureQuoteForLsp(
                authenticatedLspId(authentication),
                loanId,
                authentication.getName(),
                request.effectiveDate()
        );
        return LoanApplicationOpsController.toForeclosureQuoteResponse(quote);
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

    public record LspLoanForeclosureQuoteRequest(@NotNull LocalDate effectiveDate) {
    }
}
