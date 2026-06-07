package com.bhawana.lms.web;

import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanForeclosureQuote;
import com.bhawana.lms.service.LoanApplicationService;
import com.bhawana.lms.service.LspApiIdempotencyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/lsp/loans")
public class LspLoanApiController {

    private static final String FORECLOSURE_EXECUTE_OPERATION_KEY = "FORECLOSURE_EXECUTE";

    private final LoanApplicationService loanApplicationService;
    private final LspApiIdempotencyService lspApiIdempotencyService;

    public LspLoanApiController(
            LoanApplicationService loanApplicationService,
            LspApiIdempotencyService lspApiIdempotencyService
    ) {
        this.loanApplicationService = loanApplicationService;
        this.lspApiIdempotencyService = lspApiIdempotencyService;
    }

    @GetMapping("/{loanId}")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_READ','LSP_UI_WRITE')")
    public LspLoanApplicationApiController.LspLoanApplicationDetailResponse getLoan(
            Authentication authentication,
            @PathVariable UUID loanId
    ) {
        LoanAccount loanAccount = loanApplicationService.getLoanAccountForLsp(
                LspAuthenticationSupport.authenticatedLspId(authentication),
                loanId
        );
        return LspLoanApplicationResponses.toDetailResponse(loanAccount.getLoanApplication(), loanApplicationService);
    }

    @GetMapping("/{loanId}/repayment-schedule")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_READ','LSP_UI_WRITE')")
    public List<LoanApplicationOpsController.LoanRepaymentScheduleInstallmentResponse> listRepaymentSchedule(
            Authentication authentication,
            @PathVariable UUID loanId
    ) {
        return loanApplicationService.listRepaymentScheduleForLsp(
                        LspAuthenticationSupport.authenticatedLspId(authentication),
                        loanId
                ).stream()
                .map(LoanApplicationOpsResponses::toRepaymentScheduleInstallmentResponse)
                .toList();
    }

    @GetMapping("/{loanId}/payments")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_READ','LSP_UI_WRITE')")
    public List<LoanApplicationOpsController.LoanPaymentTransactionResponse> listPayments(
            Authentication authentication,
            @PathVariable UUID loanId
    ) {
        return loanApplicationService.listPaymentTransactionsForLsp(
                        LspAuthenticationSupport.authenticatedLspId(authentication),
                        loanId
                ).stream()
                .map(LoanApplicationOpsResponses::toPaymentTransactionResponse)
                .toList();
    }

    @PostMapping("/{loanId}/payments")
    @PreAuthorize("hasRole('LSP_API_CLIENT')")
    public LoanApplicationOpsController.LoanPaymentTransactionResponse recordPayment(
            Authentication authentication,
            @PathVariable UUID loanId,
            @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody LoanApplicationOpsController.LoanPaymentTransactionRequest request
    ) {
        LoanAccount loanAccount = loanApplicationService.getLoanAccountForLsp(
                LspAuthenticationSupport.authenticatedLspId(authentication),
                loanId
        );
        return LoanApplicationOpsResponses.toPaymentTransactionResponse(loanApplicationService.recordPaymentTransaction(
                loanAccount.getLoanApplication().getId(),
                authentication.getName(),
                idempotencyKey,
                request.targetInstallmentId(),
                request.amount(),
                request.postedAt(),
                request.reference(),
                request.channel()
        ));
    }

    @PostMapping("/{loanId}/foreclosure-quote")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_WRITE')")
    public LoanApplicationOpsController.LoanForeclosureQuoteResponse requestForeclosureQuote(
            Authentication authentication,
            @PathVariable UUID loanId,
            @Valid @RequestBody LspLoanForeclosureQuoteRequest request
    ) {
        LoanForeclosureQuote quote = loanApplicationService.requestForeclosureQuoteForLsp(
                LspAuthenticationSupport.authenticatedLspId(authentication),
                loanId,
                authentication.getName(),
                request.effectiveDate()
        );
        return LoanApplicationOpsResponses.toForeclosureQuoteResponse(quote);
    }

    @PostMapping("/{loanId}/foreclosure-quotes/{quoteId}/execute")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_WRITE')")
    public LoanApplicationOpsController.LoanForeclosureQuoteResponse executeForeclosureQuote(
            Authentication authentication,
            @PathVariable UUID loanId,
            @PathVariable UUID quoteId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody LoanApplicationOpsController.LoanForeclosureExecutionRequest request
    ) {
        UUID lspId = LspAuthenticationSupport.authenticatedLspId(authentication);
        return lspApiIdempotencyService.execute(
                lspId,
                FORECLOSURE_EXECUTE_OPERATION_KEY,
                idempotencyKey,
                new ForeclosureExecuteIdempotencyFingerprint(
                        loanId.toString(),
                        quoteId.toString(),
                        request.settlementDate().toString(),
                        request.reference(),
                        request.note()
                ),
                LoanApplicationOpsController.LoanForeclosureQuoteResponse.class,
                () -> LoanApplicationOpsResponses.toForeclosureQuoteResponse(
                        loanApplicationService.executeForeclosureQuoteForLsp(
                                lspId,
                                loanId,
                                quoteId,
                                authentication.getName(),
                                request.settlementDate(),
                                request.reference(),
                                request.note()
                        )
                )
        );
    }

    public record LspLoanForeclosureQuoteRequest(@NotNull LocalDate effectiveDate) {
    }

    private record ForeclosureExecuteIdempotencyFingerprint(
            String loanAccountId,
            String quoteId,
            String settlementDate,
            String reference,
            String note
    ) {
    }
}
