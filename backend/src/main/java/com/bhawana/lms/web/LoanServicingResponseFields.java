package com.bhawana.lms.web;

import com.bhawana.lms.domain.LoanForeclosureQuote;
import com.bhawana.lms.domain.LoanPaymentTransaction;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.service.LoanApplicationLastActivity;
import com.bhawana.lms.service.LoanDelinquencySummary;
import com.bhawana.lms.service.LoanDelinquencySupport;
import com.bhawana.lms.service.LoanRepaymentScheduleSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Shared leaf field bundles for the loan-servicing response payloads (L03).
 *
 * The LSP partner surface and the internal ops surface publish identical field
 * sets for these leaf objects, but they deliberately remain separate wire
 * records: the two contracts have distinct OpenAPI component names and are
 * allowed to evolve independently (e.g. the LSP checklist row omits internal
 * storage metadata that the ops checklist exposes). What must not drift is
 * how a domain row maps onto the fields — null handling, enum names, derived
 * delinquency figures — so each bundle is computed once here and each surface
 * constructs its own wire record from it via {@code toOps()} / {@code toLsp()}.
 * Adding a surface-specific field stays a one-line change on that surface's
 * wire record; this file is not a merge of the two contracts.
 */
final class LoanServicingResponseFields {

    private LoanServicingResponseFields() {
    }

    record PaymentTransactionFields(
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
        static PaymentTransactionFields from(LoanPaymentTransaction paymentTransaction) {
            return new PaymentTransactionFields(
                    paymentTransaction.getId(),
                    paymentTransaction.getLoanAccount().getId(),
                    paymentTransaction.getRepaymentInstallment() == null
                            ? null
                            : paymentTransaction.getRepaymentInstallment().getId(),
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
                    paymentTransaction.getCreatedAt(),
                    paymentTransaction.getUpdatedAt()
            );
        }

        LoanApplicationOpsApiTypes.LoanPaymentTransactionResponse toOps() {
            return new LoanApplicationOpsApiTypes.LoanPaymentTransactionResponse(
                    id, loanAccountId, targetInstallmentId, actorUsername, amount, paymentDate,
                    reference, channel, status, allocatedAmount, unallocatedAmount, note,
                    correlationId, createdAt, updatedAt
            );
        }

        LspLoanApiController.LspPaymentTransactionResponse toLsp() {
            return new LspLoanApiController.LspPaymentTransactionResponse(
                    id, loanAccountId, targetInstallmentId, actorUsername, amount, paymentDate,
                    reference, channel, status, allocatedAmount, unallocatedAmount, note,
                    correlationId, createdAt, updatedAt
            );
        }
    }

    record ForeclosureQuoteFields(
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
        static ForeclosureQuoteFields from(LoanForeclosureQuote quote) {
            return new ForeclosureQuoteFields(
                    quote.getId(),
                    quote.getLoanAccount().getId(),
                    quote.getVersion(),
                    quote.getRequestedByUsername(),
                    quote.getExecutedByUsername(),
                    quote.getEffectiveDate(),
                    quote.getOutstandingPrincipal(),
                    quote.getOutstandingInterest(),
                    quote.getSettlementAmount(),
                    quote.getStatus().name(),
                    quote.getExecutedAt(),
                    quote.getCreatedAt(),
                    quote.getUpdatedAt()
            );
        }

        LoanApplicationOpsApiTypes.LoanForeclosureQuoteResponse toOps() {
            return new LoanApplicationOpsApiTypes.LoanForeclosureQuoteResponse(
                    id, loanAccountId, version, requestedByUsername, executedByUsername,
                    effectiveDate, outstandingPrincipal, outstandingInterest, settlementAmount,
                    status, executedAt, createdAt, updatedAt
            );
        }

        LspLoanApiController.LspForeclosureQuoteResponse toLsp() {
            return new LspLoanApiController.LspForeclosureQuoteResponse(
                    id, loanAccountId, version, requestedByUsername, executedByUsername,
                    effectiveDate, outstandingPrincipal, outstandingInterest, settlementAmount,
                    status, executedAt, createdAt, updatedAt
            );
        }
    }

    record ScheduleInstallmentFields(
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
        static ScheduleInstallmentFields from(
                LoanRepaymentScheduleInstallment installment,
                LocalDate businessDate
        ) {
            int daysPastDue = LoanDelinquencySupport.calculateDaysPastDue(installment, businessDate);
            return new ScheduleInstallmentFields(
                    installment.getId(),
                    installment.getLoanAccount().getId(),
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
                    LoanDelinquencySupport.resolveDelinquencyBucket(daysPastDue).name(),
                    installment.getCreatedAt()
            );
        }

        LoanApplicationOpsApiTypes.LoanRepaymentScheduleInstallmentResponse toOps() {
            return new LoanApplicationOpsApiTypes.LoanRepaymentScheduleInstallmentResponse(
                    id, loanAccountId, installmentNumber, dueDate, openingPrincipal, principalDue,
                    interestDue, installmentAmount, closingPrincipal, status, paidPrincipal,
                    paidInterest, paidAmount, outstandingAmount, daysPastDue, delinquencyBucket,
                    createdAt
            );
        }

        LspLoanApplicationApiController.LspRepaymentScheduleInstallmentResponse toLsp() {
            return new LspLoanApplicationApiController.LspRepaymentScheduleInstallmentResponse(
                    id, loanAccountId, installmentNumber, dueDate, openingPrincipal, principalDue,
                    interestDue, installmentAmount, closingPrincipal, status, paidPrincipal,
                    paidInterest, paidAmount, outstandingAmount, daysPastDue, delinquencyBucket,
                    createdAt
            );
        }
    }

    record DelinquencySummaryFields(
            Integer maxDaysPastDue,
            String bucket,
            Integer overdueInstallmentCount,
            BigDecimal overdueAmount
    ) {
        static DelinquencySummaryFields from(LoanDelinquencySummary summary) {
            return new DelinquencySummaryFields(
                    summary.maxDaysPastDue(),
                    summary.bucket().name(),
                    summary.overdueInstallmentCount(),
                    summary.overdueAmount()
            );
        }

        LoanApplicationOpsApiTypes.LoanDelinquencySummaryResponse toOps() {
            return new LoanApplicationOpsApiTypes.LoanDelinquencySummaryResponse(
                    maxDaysPastDue, bucket, overdueInstallmentCount, overdueAmount
            );
        }

        LspLoanApplicationApiController.LspLoanDelinquencySummaryResponse toLsp() {
            return new LspLoanApplicationApiController.LspLoanDelinquencySummaryResponse(
                    maxDaysPastDue, bucket, overdueInstallmentCount, overdueAmount
            );
        }
    }

    record ScheduleSummaryFields(
            Integer installmentCount,
            BigDecimal installmentAmount,
            LocalDate firstDueDate,
            LocalDate finalDueDate
    ) {
        static ScheduleSummaryFields from(LoanRepaymentScheduleSummary summary) {
            return new ScheduleSummaryFields(
                    summary.installmentCount(),
                    summary.installmentAmount(),
                    summary.firstDueDate(),
                    summary.finalDueDate()
            );
        }

        LoanApplicationOpsApiTypes.LoanRepaymentScheduleSummaryResponse toOps() {
            return new LoanApplicationOpsApiTypes.LoanRepaymentScheduleSummaryResponse(
                    installmentCount, installmentAmount, firstDueDate, finalDueDate
            );
        }

        LspLoanApplicationApiController.LspLoanRepaymentScheduleSummaryResponse toLsp() {
            return new LspLoanApplicationApiController.LspLoanRepaymentScheduleSummaryResponse(
                    installmentCount, installmentAmount, firstDueDate, finalDueDate
            );
        }
    }

    record LastActivityFields(
            String activityType,
            String actorUsername,
            String summary,
            String detail,
            String correlationId,
            Instant occurredAt
    ) {
        static LastActivityFields from(LoanApplicationLastActivity activity) {
            return new LastActivityFields(
                    activity.activityType(),
                    activity.actorUsername(),
                    activity.summary(),
                    activity.detail(),
                    activity.correlationId(),
                    activity.occurredAt()
            );
        }

        LoanApplicationOpsApiTypes.LoanApplicationLastActivityResponse toOps() {
            return new LoanApplicationOpsApiTypes.LoanApplicationLastActivityResponse(
                    activityType, actorUsername, summary, detail, correlationId, occurredAt
            );
        }

        LspLoanApplicationApiController.LoanApplicationLastActivityResponse toLsp() {
            return new LspLoanApplicationApiController.LoanApplicationLastActivityResponse(
                    activityType, actorUsername, summary, detail, correlationId, occurredAt
            );
        }
    }
}
