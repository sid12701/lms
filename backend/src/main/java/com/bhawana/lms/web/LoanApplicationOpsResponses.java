package com.bhawana.lms.web;

import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationAuditEvent;
import com.bhawana.lms.domain.LoanApplicationDocumentAccessAudit;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanApplicationIntakeAudit;
import com.bhawana.lms.domain.LoanApplicationStatusTransition;
import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import com.bhawana.lms.domain.LoanForeclosureQuote;
import com.bhawana.lms.domain.LoanPaymentTransaction;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.service.LoanApplicationDetailAssembler.LoanApplicationDetailView;
import com.bhawana.lms.service.LoanApplicationService;
import java.time.LocalDate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class LoanApplicationOpsResponses {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private LoanApplicationOpsResponses() {
    }

    public static LoanApplicationOpsController.LoanApplicationResponse toResponse(LoanApplication application) {
        return toResponse(application, null);
    }

    public static LoanApplicationOpsController.LoanApplicationResponse toResponse(
            LoanApplication application,
            String accountNumber
    ) {
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
                accountNumber,
                application.getSourceChannel(),
                application.getRequestedAmount(),
                application.getRequestedTenureMonths(),
                application.getStatus().name(),
                application.getCreatedAt().toString()
        );
    }

    public static LoanApplicationOpsController.LoanApplicationDetailResponse toDetailResponse(
            LoanApplicationDetailView detail
    ) {
        return toDetailResponse(
                detail.application(),
                detail.lastActivity().orElse(null),
                detail.loanAccount().orElse(null),
                detail.repaymentScheduleSummary().orElse(null),
                detail.delinquencySummary().orElse(null)
        );
    }

    public static LoanApplicationOpsController.LoanApplicationDetailResponse toDetailResponse(
            LoanApplication application,
            LoanApplicationService.LoanApplicationLastActivity lastActivity,
            LoanAccount loanAccount,
            LoanApplicationService.LoanRepaymentScheduleSummary repaymentScheduleSummary,
            LoanApplicationService.LoanDelinquencySummary delinquencySummary
    ) {
        return new LoanApplicationOpsController.LoanApplicationDetailResponse(
                application.getId().toString(),
                loanAccount == null ? null : loanAccount.getId().toString(),
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
                application.getInvalidReasonCode() == null ? null : application.getInvalidReasonCode().name(),
                application.getInvalidReasonText(),
                application.getInvalidatedByUsername(),
                application.getInvalidatedAt() == null ? null : application.getInvalidatedAt().toString(),
                application.getCreatedAt().toString(),
                application.getUpdatedAt().toString(),
                toLoanAccountSummary(loanAccount, repaymentScheduleSummary, delinquencySummary),
                toLastActivityResponse(lastActivity)
        );
    }

    public static LoanApplicationOpsController.LoanApplicationIntakeAuditResponse toAuditResponse(
            LoanApplicationIntakeAudit audit
    ) {
        return new LoanApplicationOpsController.LoanApplicationIntakeAuditResponse(
                audit.getId().toString(),
                audit.getLoanApplication().getId().toString(),
                audit.getActorUsername(),
                audit.getCorrelationId(),
                maskSensitivePayloadJson(audit.getPayloadJson()),
                audit.getCreatedAt()
        );
    }

    public static LoanApplicationOpsController.LoanApplicationStatusTransitionResponse toTransitionResponse(
            LoanApplicationStatusTransition transition
    ) {
        return new LoanApplicationOpsController.LoanApplicationStatusTransitionResponse(
                transition.getId().toString(),
                transition.getLoanApplication().getId().toString(),
                transition.getActorUsername(),
                transition.getFromStatus().name(),
                transition.getToStatus().name(),
                transition.getNote(),
                transition.getReasonCode() == null ? null : transition.getReasonCode().name(),
                transition.getCorrelationId(),
                transition.getCreatedAt().toString(),
                parseRejectionReason(transition.getRejectionReasonJson())
        );
    }

    private static LoanApplicationOpsController.RejectionReason parseRejectionReason(String rejectionReasonJson) {
        if (rejectionReasonJson == null || rejectionReasonJson.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = REJECTION_REASON_MAPPER.readTree(rejectionReasonJson);
            com.fasterxml.jackson.databind.JsonNode failedRulesNode = root.path("failedRules");
            if (!failedRulesNode.isArray()) {
                return null;
            }
            java.util.List<String> failedRules = new java.util.ArrayList<>();
            failedRulesNode.forEach(node -> {
                if (node.isTextual()) {
                    failedRules.add(node.asText());
                }
            });
            return new LoanApplicationOpsController.RejectionReason(java.util.List.copyOf(failedRules));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            return null;
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper REJECTION_REASON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public static LoanApplicationOpsController.LoanApplicationAuditEventResponse toAuditEventResponse(
            LoanApplicationAuditEvent event
    ) {
        return new LoanApplicationOpsController.LoanApplicationAuditEventResponse(
                event.getId().toString(),
                event.getLoanApplication().getId().toString(),
                event.getAction().name(),
                event.getActorUsername(),
                event.getFromStatus().name(),
                event.getToStatus().name(),
                event.getNote(),
                event.getReasonCode() == null ? null : event.getReasonCode().name(),
                event.getCorrelationId(),
                event.getCreatedAt().toString()
        );
    }

    public static LoanApplicationOpsController.LoanDisbursementRequestResponse toDisbursementRequestResponse(
            LoanDisbursementRequestLog request
    ) {
        return new LoanApplicationOpsController.LoanDisbursementRequestResponse(
                request.getId().toString(),
                request.getLoanAccount().getId().toString(),
                request.getActorUsername(),
                request.getAmount(),
                request.getProviderName(),
                request.getProviderRequestId(),
                request.getProviderStatus(),
                request.getRequestPayloadJson(),
                request.getResponsePayloadJson(),
                request.getCorrelationId(),
                request.getCreatedAt().toString(),
                request.getUpdatedAt().toString()
        );
    }

    public static LoanApplicationOpsController.LoanRepaymentScheduleInstallmentResponse toRepaymentScheduleInstallmentResponse(
            LoanRepaymentScheduleInstallment installment,
            LocalDate businessDate
    ) {
        int daysPastDue = LoanApplicationService.calculateDaysPastDue(
                installment,
                businessDate
        );
        return new LoanApplicationOpsController.LoanRepaymentScheduleInstallmentResponse(
                installment.getId().toString(),
                installment.getLoanAccount().getId().toString(),
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
                LoanApplicationService.resolveDelinquencyBucket(daysPastDue).name(),
                installment.getCreatedAt().toString()
        );
    }

    public static LoanApplicationOpsController.LoanPaymentTransactionResponse toPaymentTransactionResponse(
            LoanPaymentTransaction paymentTransaction
    ) {
        return new LoanApplicationOpsController.LoanPaymentTransactionResponse(
                paymentTransaction.getId().toString(),
                paymentTransaction.getLoanAccount().getId().toString(),
                paymentTransaction.getRepaymentInstallment() == null
                        ? null
                        : paymentTransaction.getRepaymentInstallment().getId().toString(),
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
                paymentTransaction.getCreatedAt().toString(),
                paymentTransaction.getUpdatedAt().toString()
        );
    }

    public static LoanApplicationOpsController.LoanForeclosureQuoteResponse toForeclosureQuoteResponse(
            LoanForeclosureQuote quote
    ) {
        return new LoanApplicationOpsController.LoanForeclosureQuoteResponse(
                quote.getId().toString(),
                quote.getLoanAccount().getId().toString(),
                quote.getVersion(),
                quote.getRequestedByUsername(),
                quote.getExecutedByUsername(),
                quote.getEffectiveDate(),
                quote.getOutstandingPrincipal(),
                quote.getOutstandingInterest(),
                quote.getSettlementAmount(),
                quote.getStatus().name(),
                quote.getExecutedAt() == null ? null : quote.getExecutedAt().toString(),
                quote.getCreatedAt().toString(),
                quote.getUpdatedAt().toString()
        );
    }

    public static LoanApplicationOpsController.LoanApplicationDocumentChecklistResponse toDocumentChecklistResponse(
            LoanApplicationDocumentChecklist checklistItem
    ) {
        return new LoanApplicationOpsController.LoanApplicationDocumentChecklistResponse(
                checklistItem.getId().toString(),
                checklistItem.getLoanApplication().getId().toString(),
                checklistItem.getDocumentType().name(),
                checklistItem.getDocumentType().getDisplayName(),
                checklistItem.isRequired(),
                checklistItem.getStatus().name(),
                checklistItem.getNote(),
                checklistItem.getFileName(),
                checklistItem.getFileReference(),
                checklistItem.getContentType(),
                checklistItem.getSourceReference(),
                checklistItem.isLmsManagedContent(),
                checklistItem.getStorageKey(),
                checklistItem.getFileChecksum(),
                checklistItem.getFileSizeBytes(),
                checklistItem.getUploadedAt(),
                checklistItem.getUploadedByUsername(),
                checklistItem.getUpdatedByUsername(),
                checklistItem.getCreatedAt().toString(),
                checklistItem.getUpdatedAt().toString()
        );
    }

    public static LoanApplicationOpsController.LoanApplicationDocumentAccessAuditResponse toDocumentAccessAuditResponse(
            LoanApplicationDocumentAccessAudit audit
    ) {
        return new LoanApplicationOpsController.LoanApplicationDocumentAccessAuditResponse(
                audit.getId().toString(),
                audit.getLoanApplication().getId().toString(),
                audit.getAction().name(),
                audit.getActorUsername(),
                audit.getSummary(),
                audit.getDocumentTypes().stream().map(Enum::name).toList(),
                audit.getCorrelationId(),
                audit.getActorIp(),
                audit.getByteCount(),
                audit.getCreatedAt().toString()
        );
    }

    private static LoanApplicationOpsController.LoanAccountSummaryResponse toLoanAccountSummary(
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

    private static LoanApplicationOpsController.LoanApplicationLastActivityResponse toLastActivityResponse(
            LoanApplicationService.LoanApplicationLastActivity lastActivity
    ) {
        if (lastActivity == null) {
            return null;
        }
        return new LoanApplicationOpsController.LoanApplicationLastActivityResponse(
                lastActivity.activityType(),
                lastActivity.actorUsername(),
                lastActivity.summary(),
                lastActivity.detail(),
                lastActivity.correlationId(),
                lastActivity.occurredAt().toString()
        );
    }

    private static String maskSensitivePayloadJson(String payloadJson) {
        try {
            JsonNode root = JSON_MAPPER.readTree(payloadJson);
            JsonNode masked = maskSensitiveNode(root);
            return JSON_MAPPER.writeValueAsString(masked);
        } catch (JsonProcessingException exception) {
            return payloadJson;
        }
    }

    private static JsonNode maskSensitiveNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                maskSensitiveNode(child);
            }
            return node;
        }
        if (!node.isObject()) {
            return node;
        }

        ObjectNode objectNode = (ObjectNode) node;
        objectNode.fieldNames().forEachRemaining(fieldName -> {
            JsonNode child = objectNode.get(fieldName);
            if (child != null && child.isTextual()) {
                objectNode.put(fieldName, switch (fieldName) {
                    case "aadharNumber", "aadhaarNumber", "aadhar", "aadhaar", "incomingAadhar" -> maskAadhar(child.asText());
                    default -> child.asText();
                });
            } else {
                maskSensitiveNode(child);
            }
        });
        return objectNode;
    }

    private static String maskAadhar(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String digits = value.replaceAll("\\s", "");
        if (digits.length() < 4) {
            return "XXXXXXXX";
        }
        return "XXXXXXXX" + digits.substring(digits.length() - 4);
    }
}
