package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.util.AlertContextJson;
import com.bhawana.lms.domain.AlertRule;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.OpsAlertSeverity;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.domain.WebhookEventOutbox;
import com.bhawana.lms.repo.AlertRuleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Ad-hoc ops-alert emission for domain services. Scheduled rule evaluation lives in
 * {@link AlertRuleEvaluationWorker}.
 */
@Service
public class OpsAlertEmitters {

    private static final Logger log = LoggerFactory.getLogger(OpsAlertEmitters.class);

    private final OpsAlertService opsAlertService;
    private final AlertRuleRepository alertRuleRepository;
    private final ObjectMapper objectMapper;

    public OpsAlertEmitters(
            OpsAlertService opsAlertService,
            AlertRuleRepository alertRuleRepository,
            ObjectMapper objectMapper
    ) {
        this.opsAlertService = opsAlertService;
        this.alertRuleRepository = alertRuleRepository;
        this.objectMapper = objectMapper;
    }

    public void emitWebhookDeadLetter(WebhookEventOutbox event, String errorMessage) {
        if (!isRuleEnabled("WEBHOOK_DEAD_LETTER")) {
            return;
        }
        String title = "Webhook delivery dead-lettered";
        String message = "Event "
                + event.getEventType().name()
                + " for aggregate "
                + event.getAggregateType()
                + "/"
                + event.getAggregateId()
                + " failed permanently: "
                + errorMessage;
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("eventId", event.getId());
        context.put("lspId", event.getLsp().getId());
        context.put("eventType", event.getEventType().name());
        opsAlertService.createAlertIfAbsent(
                OpsAlertType.WEBHOOK_DEAD_LETTER,
                OpsAlertSeverity.HIGH,
                title,
                message,
                "WEBHOOK_DELIVERY",
                event.getId(),
                "webhook-dead-letter:" + event.getId(),
                serializeContext(context)
        );
    }

    public void emitManualRuleEngineOverride(
            LoanApplication application,
            String actorUsername,
            LoanAutoApprovalRuleEngine.Evaluation evaluation,
            String transitionNote
    ) {
        String lspCode = application.getLsp() != null ? application.getLsp().getCode() : "unknown";
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("applicationId", application.getId());
        context.put("lspCode", lspCode);
        context.put("actorUsername", actorUsername);
        context.put("ruleEngineApproved", evaluation.approved());
        context.put(
                "failedRules",
                evaluation.failedRules().stream().map(Enum::name).toList()
        );
        opsAlertService.createAlert(
                OpsAlertType.MANUAL_RULE_ENGINE_OVERRIDE,
                OpsAlertSeverity.HIGH,
                "Manual rule-engine override: " + application.getExternalLoanId(),
                transitionNote,
                "LOAN_APPLICATION",
                application.getId(),
                CorrelationIdHolder.get(),
                serializeContext(context)
        );
    }

    public void emitLspProvidedScheduleViolation(
            LoanApplication application,
            ScheduleViolationType violationType,
            String message,
            Map<String, String> details
    ) {
        emitLspBoundViolation(application, violationType.name(), message, details, true);
    }

    public void emitLspForeclosureViolation(
            LoanApplication application,
            ForeclosureViolationType violationType,
            String message,
            Map<String, String> details
    ) {
        emitLspBoundViolation(application, violationType.name(), message, details, true);
    }

    public void emitLspBoundViolation(
            LoanApplication application,
            String violationType,
            String message,
            Map<String, String> details
    ) {
        emitLspBoundViolation(application, violationType, message, details, false);
    }

    private void emitLspBoundViolation(
            LoanApplication application,
            String violationType,
            String message,
            Map<String, String> details,
            boolean alwaysCreate
    ) {
        String lspCode = application.getLsp() != null ? application.getLsp().getCode() : "unknown";
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("applicationId", application.getId());
        context.put("lspCode", lspCode);
        context.put("violationType", violationType);
        if (details != null) {
            context.putAll(details);
        }
        String contextJson = serializeContext(context);
        if (alwaysCreate) {
            opsAlertService.createAlert(
                    OpsAlertType.LSP_BOUND_VIOLATION,
                    OpsAlertSeverity.HIGH,
                    "LSP bound violation: " + violationType,
                    message,
                    "LOAN_APPLICATION",
                    application.getId(),
                    CorrelationIdHolder.get(),
                    contextJson
            );
        } else {
            opsAlertService.createAlertIfAbsent(
                    OpsAlertType.LSP_BOUND_VIOLATION,
                    OpsAlertSeverity.HIGH,
                    "LSP bound violation: " + violationType,
                    message,
                    "LOAN_APPLICATION",
                    application.getId(),
                    "lsp-bound:" + application.getId() + ":" + violationType,
                    contextJson
            );
        }
    }

    public void emitHolderNameSoftMismatch(
            LoanApplication application,
            String submittedName,
            String onFileName,
            String correlationId
    ) {
        String lspCode = application.getLsp() != null ? application.getLsp().getCode() : "unknown";
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("applicationId", application.getId());
        context.put("lspCode", lspCode);
        context.put("violationType", "HOLDER_NAME_SOFT_MISMATCH");
        context.put("submittedAccountHolderName", submittedName);
        context.put("onFileAccountHolderName", onFileName);
        opsAlertService.createAlert(
                OpsAlertType.LSP_BOUND_VIOLATION,
                OpsAlertSeverity.HIGH,
                "LSP bound violation: HOLDER_NAME_SOFT_MISMATCH",
                "Account holder name soft mismatch for loan " + application.getExternalLoanId() + ".",
                "LOAN_APPLICATION",
                application.getId(),
                correlationId,
                serializeContext(context)
        );
    }

    public void emitBorrowerBankDetailsVelocity(Borrower borrower, int updateCount) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("borrowerId", borrower.getId());
        context.put("updateCount", updateCount);
        opsAlertService.createAlertIfAbsent(
                OpsAlertType.BORROWER_BANK_DETAILS_VELOCITY,
                OpsAlertSeverity.HIGH,
                "Borrower bank details updated frequently",
                "Borrower "
                        + borrower.getPan()
                        + " had "
                        + updateCount
                        + " bank-detail updates in the configured velocity window.",
                "BORROWER",
                borrower.getId(),
                "borrower-bank-velocity:" + borrower.getId(),
                serializeContext(context)
        );
    }

    public void emitDisbursementRetryExhausted(LoanApplication application, int attemptCount) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("applicationId", application.getId());
        context.put("attemptCount", attemptCount);
        opsAlertService.createAlertIfAbsent(
                OpsAlertType.DISBURSEMENT_RETRY_EXHAUSTED,
                OpsAlertSeverity.HIGH,
                "Disbursement retries exhausted",
                "Automated disbursement failed after " + attemptCount + " attempts.",
                "LOAN_APPLICATION",
                application.getId(),
                "disbursement-retry-exhausted:" + application.getId(),
                serializeContext(context)
        );
    }

    public void emitRateLimitBreach(String bucketKey, String path, long retryAfterSeconds) {
        if (!isRuleEnabled("RATE_LIMIT_BREACH")) {
            return;
        }
        String title = "API rate limit exceeded";
        String message = "Bucket "
                + bucketKey
                + " exceeded its configured limit on "
                + path
                + ". Retry after "
                + retryAfterSeconds
                + " seconds.";
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("bucket", bucketKey);
        context.put("path", path);
        opsAlertService.createAlertIfAbsent(
                OpsAlertType.RATE_LIMIT_BREACH,
                OpsAlertSeverity.HIGH,
                title,
                message,
                "SYSTEM",
                null,
                "rate-limit:" + bucketKey,
                serializeContext(context)
        );
    }

    String serializeContext(Map<String, Object> context) {
        return AlertContextJson.serialize(objectMapper, log, context);
    }

    private boolean isRuleEnabled(String code) {
        return alertRuleRepository.findByCode(code)
                .map(AlertRule::isEnabled)
                .orElse(false);
    }
}
