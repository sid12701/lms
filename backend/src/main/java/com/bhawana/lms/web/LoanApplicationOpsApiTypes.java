package com.bhawana.lms.web;

import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusReasonCode;
import com.bhawana.lms.domain.LoanPaymentChannel;
import com.bhawana.lms.service.LoanApplicationWebhookEventProjection;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Request/response wire contracts for the internal ops loan-application API. Kept separate from
 * {@link LoanApplicationOpsController} (endpoints) and {@link LoanApplicationOpsResponses} (mapping)
 * so the HTTP surface is one scannable type catalogue. Internal-only; not part of the LSP contract.
 */
public final class LoanApplicationOpsApiTypes {

    private LoanApplicationOpsApiTypes() {
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
            String loanAccountId,
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
