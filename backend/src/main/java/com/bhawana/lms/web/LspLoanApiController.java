package com.bhawana.lms.web;

import com.bhawana.lms.config.BusinessCalendar;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanForeclosureQuote;
import com.bhawana.lms.domain.LoanPaymentChannel;
import com.bhawana.lms.service.LoanApplicationDetailAssembler;
import com.bhawana.lms.service.LoanForeclosureCommandService;
import com.bhawana.lms.service.LoanRepaymentCommandService;
import com.bhawana.lms.service.LoanServicingSupportService;
import com.bhawana.lms.service.LspApiIdempotencyService;
import com.bhawana.lms.common.api.StrictJson;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
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

    private final LoanApplicationDetailAssembler loanApplicationDetailAssembler;
    private final LoanServicingSupportService loanServicingSupportService;
    private final LoanRepaymentCommandService loanRepaymentCommandService;
    private final LoanForeclosureCommandService loanForeclosureCommandService;
    private final LspApiIdempotencyService lspApiIdempotencyService;
    private final BusinessCalendar businessCalendar;

    public LspLoanApiController(
            LoanApplicationDetailAssembler loanApplicationDetailAssembler,
            LoanServicingSupportService loanServicingSupportService,
            LoanRepaymentCommandService loanRepaymentCommandService,
            LoanForeclosureCommandService loanForeclosureCommandService,
            LspApiIdempotencyService lspApiIdempotencyService,
            BusinessCalendar businessCalendar
    ) {
        this.loanApplicationDetailAssembler = loanApplicationDetailAssembler;
        this.loanServicingSupportService = loanServicingSupportService;
        this.loanRepaymentCommandService = loanRepaymentCommandService;
        this.loanForeclosureCommandService = loanForeclosureCommandService;
        this.lspApiIdempotencyService = lspApiIdempotencyService;
        this.businessCalendar = businessCalendar;
    }

    @GetMapping("/{loanId}")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_READ','LSP_UI_WRITE')")
    public LspLoanApplicationApiController.LspLoanApplicationDetailResponse getLoan(
            Authentication authentication,
            @PathVariable UUID loanId
    ) {
        LoanAccount loanAccount = loanServicingSupportService.getLoanAccountForLsp(
                LspAuthenticationSupport.authenticatedLspId(authentication),
                loanId
        );
        return LspLoanApplicationResponses.toDetailResponse(
                loanApplicationDetailAssembler.getDetail(loanAccount.getLoanApplication().getId())
        );
    }

    @GetMapping("/{loanId}/repayment-schedule")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_READ','LSP_UI_WRITE')")
    public List<LspLoanApplicationApiController.LspRepaymentScheduleInstallmentResponse> listRepaymentSchedule(
            Authentication authentication,
            @PathVariable UUID loanId
    ) {
        LocalDate businessDate = businessCalendar.today();
        return loanServicingSupportService.listRepaymentScheduleForLsp(
                        LspAuthenticationSupport.authenticatedLspId(authentication),
                        loanId
                ).stream()
                .map(installment -> LspLoanApplicationResponses.toRepaymentScheduleInstallmentResponse(
                        installment,
                        businessDate
                ))
                .toList();
    }

    @GetMapping("/{loanId}/payments")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_READ','LSP_UI_WRITE')")
    public List<LspPaymentTransactionResponse> listPayments(
            Authentication authentication,
            @PathVariable UUID loanId
    ) {
        return loanServicingSupportService.listPaymentTransactionsForLsp(
                        LspAuthenticationSupport.authenticatedLspId(authentication),
                        loanId
                ).stream()
                .map(LspLoanApiResponses::toPaymentTransactionResponse)
                .toList();
    }

    @PostMapping("/{loanId}/payments")
    @PreAuthorize("hasRole('LSP_API_CLIENT')")
    public LspPaymentTransactionResponse recordPayment(
            Authentication authentication,
            @PathVariable UUID loanId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody LspPaymentTransactionRequest request
    ) {
        LoanAccount loanAccount = loanServicingSupportService.getLoanAccountForLsp(
                LspAuthenticationSupport.authenticatedLspId(authentication),
                loanId
        );
        return LspLoanApiResponses.toPaymentTransactionResponse(
                loanRepaymentCommandService.recordPaymentTransactionWithRecovery(
                        loanAccount.getLoanApplication().getId(),
                        authentication.getName(),
                        idempotencyKey,
                        request.targetInstallmentId(),
                        request.amount(),
                        request.postedAt(),
                        request.reference(),
                        request.channel()
                )
        );
    }

    @PostMapping("/{loanId}/foreclosure-quote")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_WRITE')")
    public LspForeclosureQuoteResponse requestForeclosureQuote(
            Authentication authentication,
            @PathVariable UUID loanId,
            @Valid @RequestBody LspLoanForeclosureQuoteRequest request
    ) {
        LoanForeclosureQuote quote = loanForeclosureCommandService.requestForeclosureQuoteForLsp(
                LspAuthenticationSupport.authenticatedLspId(authentication),
                loanId,
                authentication.getName(),
                request.effectiveDate()
        );
        return LspLoanApiResponses.toForeclosureQuoteResponse(quote);
    }

    @PostMapping("/{loanId}/foreclosure-quotes/{quoteId}/execute")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_WRITE')")
    public LspForeclosureQuoteResponse executeForeclosureQuote(
            Authentication authentication,
            @PathVariable UUID loanId,
            @PathVariable UUID quoteId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody LspForeclosureExecutionRequest request
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
                LspForeclosureQuoteResponse.class,
                () -> LspLoanApiResponses.toForeclosureQuoteResponse(
                        loanForeclosureCommandService.executeForeclosureQuoteForLsp(
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

    @StrictJson
    public record LspPaymentTransactionRequest(
            @NotNull UUID targetInstallmentId,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotNull @PastOrPresent LocalDate postedAt,
            @NotNull LoanPaymentChannel channel,
            @Size(max = 128) String reference
    ) {
    }

    public record LspPaymentTransactionResponse(
            UUID id,
            UUID loanAccountId,
            UUID targetInstallmentId,
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
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    @StrictJson
    public record LspForeclosureExecutionRequest(
            @NotNull @PastOrPresent LocalDate settlementDate,
            @NotBlank @Size(max = 128) String reference,
            @Size(max = 500) String note
    ) {
    }

    public record LspForeclosureQuoteResponse(
            UUID id,
            UUID loanAccountId,
            Integer version,
            String requestedByUsername,
            String executedByUsername,
            LocalDate effectiveDate,
            BigDecimal outstandingPrincipal,
            BigDecimal outstandingInterest,
            BigDecimal settlementAmount,
            String status,
            Instant executedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    @StrictJson
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
