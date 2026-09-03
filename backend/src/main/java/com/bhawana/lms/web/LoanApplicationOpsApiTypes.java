package com.bhawana.lms.web;

import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusReasonCode;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanPaymentChannel;
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
 *
 * <p>Identifiers are typed {@link UUID} and timestamps {@link Instant} so the OpenAPI schema types
 * match the request side; the wire representation (canonical UUID string / ISO-8601 UTC) is
 * unchanged.
 */
public final class LoanApplicationOpsApiTypes {

    private LoanApplicationOpsApiTypes() {
    }

    public record LoanApplicationRequest(
            @NotNull UUID lspId,
            @NotNull UUID productId,
            @NotBlank @Size(max = 128) String externalLoanId,
            @NotBlank @Size(max = 64) String sourceChannel,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{5}[0-9]{4}[A-Za-z]$", message = "PAN must be a valid 10-character PAN") String borrowerPan,
            @NotBlank @Size(max = 255) String borrowerFullName,
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
            UUID id,
            UUID borrowerId,
            String borrowerFullName,
            String borrowerPan,
            String borrowerMobile,
            String borrowerEmail,
            LocalDate borrowerDateOfBirth,
            String borrowerCity,
            String borrowerState,
            String borrowerEmploymentType,
            BigDecimal borrowerMonthlyIncome,
            UUID lspId,
            String lspCode,
            String lspName,
            UUID productId,
            String productCode,
            String productName,
            String externalLoanId,
            String accountNumber,
            String sourceChannel,
            BigDecimal requestedAmount,
            Integer tenureMonths,
            BigDecimal interestRate,
            String status,
            Instant createdAt
    ) {
    }

    public record LoanApplicationDetailResponse(
            UUID id,
            UUID loanAccountId,
            UUID borrowerId,
            String borrowerFullName,
            String borrowerPan,
            String borrowerMobile,
            String borrowerEmail,
            LocalDate borrowerDateOfBirth,
            String borrowerCity,
            String borrowerState,
            String borrowerEmploymentType,
            BigDecimal borrowerMonthlyIncome,
            UUID lspId,
            String lspCode,
            String lspName,
            UUID productId,
            String productCode,
            String productName,
            String externalLoanId,
            String sourceChannel,
            BigDecimal requestedAmount,
            Integer tenureMonths,
            BigDecimal interestRate,
            String status,
            String invalidReasonCode,
            String invalidReasonText,
            String invalidatedByUsername,
            Instant invalidatedAt,
            Instant createdAt,
            Instant updatedAt,
            LoanAccountSummaryResponse loanAccount,
            LoanApplicationLastActivityResponse lastActivity
    ) {
    }

    public record LoanAccountSummaryResponse(
            UUID id,
            String accountNumber,
            LoanAccountStatus status,
            BigDecimal principalAmount,
            Integer tenureMonths,
            Instant approvedAt,
            Instant createdAt,
            String closureReason,
            Instant closedAt,
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
            Instant occurredAt
    ) {
    }

    public record LoanApplicationIntakeAuditResponse(
            UUID id,
            UUID loanApplicationId,
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
            UUID id,
            UUID loanApplicationId,
            String actorUsername,
            String fromStatus,
            String toStatus,
            String note,
            String reasonCode,
            String correlationId,
            Instant createdAt,
            RejectionReason rejectionReason
    ) {
    }

    public record RejectionReason(java.util.List<String> failedRules) {
    }

    public record LoanApplicationAuditEventResponse(
            UUID id,
            UUID loanApplicationId,
            String action,
            String actorUsername,
            String fromStatus,
            String toStatus,
            String note,
            String reasonCode,
            String correlationId,
            Instant createdAt
    ) {
    }

    public record LoanDisbursementRequestResponse(
            UUID id,
            UUID loanAccountId,
            String actorUsername,
            BigDecimal amount,
            String providerName,
            String providerRequestId,
            String providerStatus,
            String requestPayloadJson,
            String responsePayloadJson,
            String correlationId,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record DisbursementPreviewResponse(
            UUID applicationId,
            UUID loanAccountId,
            String loanAccountNumber,
            String externalLoanId,
            BigDecimal principal,
            BigDecimal processingFee,
            BigDecimal netDisbursalAmount,
            String paymentMode,
            String beneficiaryAccountHolderName,
            String beneficiaryBankName,
            String beneficiaryIfsc,
            String maskedBeneficiaryAccountNumber,
            String beneficiarySource,
            UUID pendingIntentId,
            String pendingIntentTranRefNo,
            String pendingIntentState
    ) {
    }

    public record DisbursementReferenceResponse(
            String tranRefNo,
            String source,
            UUID intentId,
            String intentState
    ) {
    }

    public record LoanRepaymentScheduleInstallmentResponse(
            UUID id,
            UUID loanAccountId,
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
            Instant createdAt
    ) {
    }

    public record LoanPaymentTransactionResponse(
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

    public record LoanForeclosureQuoteResponse(
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

    public record LoanApplicationDocumentChecklistResponse(
            UUID id,
            UUID loanApplicationId,
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
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record LoanApplicationDocumentAccessAuditResponse(
            UUID id,
            UUID loanApplicationId,
            String action,
            String actorUsername,
            String summary,
            List<String> documentTypes,
            String correlationId,
            String actorIp,
            Long byteCount,
            Instant createdAt
    ) {
    }
}
