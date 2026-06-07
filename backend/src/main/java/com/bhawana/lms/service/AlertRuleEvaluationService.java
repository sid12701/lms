package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.AlertRule;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusTransition;
import com.bhawana.lms.domain.LoanDelinquencyBucket;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.OpsAlert;
import com.bhawana.lms.domain.OpsAlertSeverity;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.domain.WebhookEventOutbox;
import com.bhawana.lms.repo.AlertRuleRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Follow-up #2 — evaluates configured {@link AlertRule} records and emits
 * {@link com.bhawana.lms.domain.OpsAlert} rows when conditions match.
 */
@Service
public class AlertRuleEvaluationService {

    private final AlertRuleRepository alertRuleRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanApplicationDocumentChecklistRepository checklistRepository;
    private final LoanApplicationStatusTransitionRepository statusTransitionRepository;
    private final LoanApplicationService loanApplicationService;
    private final LspRepository lspRepository;
    private final OpsAlertService opsAlertService;
    private final AlertRuleProperties properties;

    public AlertRuleEvaluationService(
            AlertRuleRepository alertRuleRepository,
            LoanApplicationRepository loanApplicationRepository,
            LoanApplicationDocumentChecklistRepository checklistRepository,
            LoanApplicationStatusTransitionRepository statusTransitionRepository,
            LoanApplicationService loanApplicationService,
            LspRepository lspRepository,
            OpsAlertService opsAlertService,
            AlertRuleProperties properties
    ) {
        this.alertRuleRepository = alertRuleRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.checklistRepository = checklistRepository;
        this.statusTransitionRepository = statusTransitionRepository;
        this.loanApplicationService = loanApplicationService;
        this.lspRepository = lspRepository;
        this.opsAlertService = opsAlertService;
        this.properties = properties;
    }

    @Transactional
    public EvaluationSummary evaluateScheduledRules() {
        TenantDataAccessContextHolder.useAdmin();
        try {
            Instant evaluatedAt = Instant.now();
            int emitted = 0;
            emitted += evaluateStaleIntake(evaluatedAt);
            emitted += evaluateStuckDisbursement(evaluatedAt);
            emitted += evaluateDpdBucketTransitions(evaluatedAt);
            emitted += evaluateLspAutoRejectSpikes(evaluatedAt);
            markRuleEvaluated("STALE_INTAKE", evaluatedAt);
            markRuleEvaluated("STUCK_DISBURSEMENT", evaluatedAt);
            markRuleEvaluated("DPD_BUCKET_TRANSITION", evaluatedAt);
            markRuleEvaluated("LSP_AUTO_REJECT_SPIKE", evaluatedAt);
            return new EvaluationSummary(emitted, evaluatedAt);
        } finally {
            TenantDataAccessContextHolder.useAdmin();
        }
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
        String contextJson = "{\"eventId\":\""
                + event.getId()
                + "\",\"lspId\":\""
                + event.getLsp().getId()
                + "\",\"eventType\":\""
                + event.getEventType().name()
                + "\"}";
        opsAlertService.createAlertIfAbsent(
                OpsAlertType.WEBHOOK_DEAD_LETTER,
                OpsAlertSeverity.HIGH,
                title,
                message,
                "WEBHOOK_DELIVERY",
                event.getId(),
                "webhook-dead-letter:" + event.getId(),
                contextJson
        );
    }

    public void emitManualRuleEngineOverride(
            LoanApplication application,
            String actorUsername,
            LoanAutoApprovalRuleEngine.Evaluation evaluation,
            String transitionNote
    ) {
        String lspCode = application.getLsp() != null ? application.getLsp().getCode() : "unknown";
        String contextJson = "{\"applicationId\":\""
                + application.getId()
                + "\",\"lspCode\":\""
                + escapeJson(lspCode)
                + "\",\"actorUsername\":\""
                + escapeJson(actorUsername)
                + "\",\"ruleEngineApproved\":"
                + evaluation.approved()
                + ",\"failedRules\":["
                + evaluation.failedRules().stream()
                        .map(rule -> "\"" + rule.name() + "\"")
                        .reduce((left, right) -> left + "," + right)
                        .orElse("")
                + "]}";
        opsAlertService.createAlert(
                OpsAlertType.MANUAL_RULE_ENGINE_OVERRIDE,
                OpsAlertSeverity.HIGH,
                "Manual rule-engine override: " + application.getExternalLoanId(),
                transitionNote,
                "LOAN_APPLICATION",
                application.getId(),
                CorrelationIdHolder.get(),
                contextJson
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
        StringBuilder context = new StringBuilder("{\"applicationId\":\"")
                .append(application.getId())
                .append("\",\"lspCode\":\"")
                .append(escapeJson(lspCode))
                .append("\",\"violationType\":\"")
                .append(escapeJson(violationType))
                .append("\"");
        if (details != null) {
            for (Map.Entry<String, String> entry : details.entrySet()) {
                context.append(",\"")
                        .append(escapeJson(entry.getKey()))
                        .append("\":\"")
                        .append(escapeJson(entry.getValue()))
                        .append("\"");
            }
        }
        context.append("}");
        if (alwaysCreate) {
            opsAlertService.createAlert(
                    OpsAlertType.LSP_BOUND_VIOLATION,
                    OpsAlertSeverity.HIGH,
                    "LSP bound violation: " + violationType,
                    message,
                    "LOAN_APPLICATION",
                    application.getId(),
                    CorrelationIdHolder.get(),
                    context.toString()
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
                    context.toString()
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
        String contextJson = "{\"applicationId\":\""
                + application.getId()
                + "\",\"lspCode\":\""
                + escapeJson(lspCode)
                + "\",\"violationType\":\"HOLDER_NAME_SOFT_MISMATCH\""
                + ",\"submittedAccountHolderName\":\""
                + escapeJson(submittedName)
                + "\",\"onFileAccountHolderName\":\""
                + escapeJson(onFileName)
                + "\"}";
        opsAlertService.createAlert(
                OpsAlertType.LSP_BOUND_VIOLATION,
                OpsAlertSeverity.HIGH,
                "LSP bound violation: HOLDER_NAME_SOFT_MISMATCH",
                "Account holder name soft mismatch for loan " + application.getExternalLoanId() + ".",
                "LOAN_APPLICATION",
                application.getId(),
                correlationId,
                contextJson
        );
    }

    public void emitBorrowerBankDetailsVelocity(Borrower borrower, int updateCount) {
        String contextJson = "{\"borrowerId\":\""
                + borrower.getId()
                + "\",\"updateCount\":"
                + updateCount
                + "}";
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
                contextJson
        );
    }

    public void emitDisbursementRetryExhausted(LoanApplication application, int attemptCount) {
        String contextJson = "{\"applicationId\":\""
                + application.getId()
                + "\",\"attemptCount\":"
                + attemptCount
                + "}";
        opsAlertService.createAlertIfAbsent(
                OpsAlertType.DISBURSEMENT_RETRY_EXHAUSTED,
                OpsAlertSeverity.HIGH,
                "Disbursement retries exhausted",
                "Automated disbursement failed after " + attemptCount + " attempts.",
                "LOAN_APPLICATION",
                application.getId(),
                "disbursement-retry-exhausted:" + application.getId(),
                contextJson
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
        opsAlertService.createAlertIfAbsent(
                OpsAlertType.RATE_LIMIT_BREACH,
                OpsAlertSeverity.HIGH,
                title,
                message,
                "SYSTEM",
                null,
                "rate-limit:" + bucketKey,
                "{\"bucket\":\"" + escapeJson(bucketKey) + "\",\"path\":\"" + escapeJson(path) + "\"}"
        );
    }

    @Transactional(readOnly = true)
    public List<AlertRule> listRules() {
        return alertRuleRepository.findAllByOrderByCodeAsc();
    }

    private int evaluateStaleIntake(Instant evaluatedAt) {
        if (!isRuleEnabled("STALE_INTAKE")) {
            return 0;
        }
        Instant cutoff = evaluatedAt.minus(Duration.ofHours(properties.getStaleIntakeHours()));
        List<LoanApplication> stale = loanApplicationRepository.findByStatusAndCreatedAtBefore(
                LoanApplicationStatus.INITIALIZED,
                cutoff
        );
        int emitted = 0;
        for (LoanApplication application : stale) {
            if (isRequiredDocumentChecklistComplete(application.getId())) {
                continue;
            }
            OpsAlert created = opsAlertService.createAlertIfAbsent(
                    OpsAlertType.STALE_INTAKE,
                    OpsAlertSeverity.HIGH,
                    "Stale loan intake",
                    "Application "
                            + application.getExternalLoanId()
                            + " has been INITIALIZED for more than "
                            + properties.getStaleIntakeHours()
                            + " hours with incomplete required documents.",
                    "LOAN_APPLICATION",
                    application.getId(),
                    CorrelationIdHolder.get(),
                    "{\"applicationId\":\"" + application.getId() + "\"}"
            );
            if (created != null) {
                emitted++;
            }
        }
        return emitted;
    }

    private int evaluateStuckDisbursement(Instant evaluatedAt) {
        if (!isRuleEnabled("STUCK_DISBURSEMENT")) {
            return 0;
        }
        Instant cutoff = evaluatedAt.minus(Duration.ofHours(properties.getStuckDisbursementHours()));
        List<LoanApplication> retrying = loanApplicationRepository.findByStatus(
                LoanApplicationStatus.DISBURSEMENT_RETRY
        );
        int emitted = 0;
        for (LoanApplication application : retrying) {
            LoanApplicationStatusTransition lastRetry = statusTransitionRepository
                    .findTopByLoanApplication_IdAndToStatusOrderByCreatedAtDesc(
                            application.getId(),
                            LoanApplicationStatus.DISBURSEMENT_RETRY
                    )
                    .orElse(null);
            if (lastRetry == null || !lastRetry.getCreatedAt().isBefore(cutoff)) {
                continue;
            }
            OpsAlert created = opsAlertService.createAlertIfAbsent(
                    OpsAlertType.STUCK_DISBURSEMENT,
                    OpsAlertSeverity.HIGH,
                    "Disbursement retry stuck",
                    "Application "
                            + application.getExternalLoanId()
                            + " has been in DISBURSEMENT_RETRY since "
                            + lastRetry.getCreatedAt()
                            + ". Payout adapter may need ops intervention.",
                    "LOAN_APPLICATION",
                    application.getId(),
                    CorrelationIdHolder.get(),
                    "{\"applicationId\":\"" + application.getId() + "\"}"
            );
            if (created != null) {
                emitted++;
            }
        }
        return emitted;
    }

    private int evaluateDpdBucketTransitions(Instant evaluatedAt) {
        if (!isRuleEnabled("DPD_BUCKET_TRANSITION")) {
            return 0;
        }
        List<LoanApplication> servicing = loanApplicationRepository.findByStatus(
                LoanApplicationStatus.UNDER_REPAYMENT
        );
        int emitted = 0;
        for (LoanApplication application : servicing) {
            LoanApplicationService.LoanDelinquencySummary summary = loanApplicationService
                    .getLoanDelinquencySummary(application.getId())
                    .orElse(null);
            if (summary == null || summary.bucket() == LoanDelinquencyBucket.CURRENT) {
                continue;
            }
            String bucketLabel = summary.bucket().name();
            OpsAlert created = opsAlertService.createAlertIfAbsent(
                    OpsAlertType.DPD_BUCKET_TRANSITION,
                    severityForBucket(summary.bucket()),
                    "Delinquency bucket " + bucketLabel,
                    "Loan "
                            + application.getExternalLoanId()
                            + " is "
                            + summary.maxDaysPastDue()
                            + " days past due (bucket "
                            + bucketLabel
                            + ", overdue ₹"
                            + summary.overdueAmount()
                            + ").",
                    "LOAN_APPLICATION",
                    application.getId(),
                    CorrelationIdHolder.get(),
                    "{\"applicationId\":\""
                            + application.getId()
                            + "\",\"bucket\":\""
                            + bucketLabel
                            + "\",\"maxDaysPastDue\":"
                            + summary.maxDaysPastDue()
                            + "}"
            );
            if (created != null) {
                emitted++;
            }
        }
        return emitted;
    }

    private int evaluateLspAutoRejectSpikes(Instant evaluatedAt) {
        if (!isRuleEnabled("LSP_AUTO_REJECT_SPIKE")) {
            return 0;
        }
        Instant since = evaluatedAt.minus(Duration.ofDays(properties.getLspRejectWindowDays()));
        Map<UUID, Long> rejectCounts = new HashMap<>();
        for (LoanApplicationStatusTransitionRepository.LspRejectCountProjection row
                : statusTransitionRepository.countRejectionsByLspSince(since)) {
            rejectCounts.put(row.getLspId(), row.getRejectedCount());
        }
        Map<UUID, Long> intakeCounts = new HashMap<>();
        for (LoanApplicationStatusTransitionRepository.LspIntakeCountProjection row
                : statusTransitionRepository.countIntakesByLspSince(since)) {
            intakeCounts.put(row.getLspId(), row.getIntakeCount());
        }
        int emitted = 0;
        for (Map.Entry<UUID, Long> entry : intakeCounts.entrySet()) {
            UUID lspId = entry.getKey();
            long intakes = entry.getValue();
            if (intakes < properties.getLspRejectMinSamples()) {
                continue;
            }
            long rejects = rejectCounts.getOrDefault(lspId, 0L);
            int rejectRatePct = (int) Math.round((rejects * 100.0) / intakes);
            if (rejectRatePct < properties.getLspRejectRatePct()) {
                continue;
            }
            String lspName = lspRepository.findById(lspId).map(Lsp::getName).orElse(lspId.toString());
            OpsAlert created = opsAlertService.createAlertIfAbsent(
                    OpsAlertType.LSP_AUTO_REJECT_SPIKE,
                    OpsAlertSeverity.HIGH,
                    "High auto-reject rate: " + lspName,
                    "LSP "
                            + lspName
                            + " auto-rejected "
                            + rejectRatePct
                            + "% of "
                            + intakes
                            + " intakes in the last "
                            + properties.getLspRejectWindowDays()
                            + " days (threshold "
                            + properties.getLspRejectRatePct()
                            + "%).",
                    "SYSTEM",
                    lspId,
                    CorrelationIdHolder.get(),
                    "{\"lspId\":\""
                            + lspId
                            + "\",\"rejectRatePct\":"
                            + rejectRatePct
                            + ",\"intakes\":"
                            + intakes
                            + ",\"rejects\":"
                            + rejects
                            + "}"
            );
            if (created != null) {
                emitted++;
            }
        }
        return emitted;
    }

    private boolean isRequiredDocumentChecklistComplete(UUID applicationId) {
        List<LoanApplicationDocumentChecklist> checklist = checklistRepository
                .findByLoanApplication_IdOrderByCreatedAtAsc(applicationId);
        if (checklist.isEmpty()) {
            return false;
        }
        return checklist.stream()
                .filter(item -> item.getDocumentType().isRequiredForApproval())
                .allMatch(item -> item.getStatus() == LoanApplicationDocumentChecklistStatus.SUBMITTED
                        || item.getStatus() == LoanApplicationDocumentChecklistStatus.NOT_REQUIRED);
    }

    private static OpsAlertSeverity severityForBucket(LoanDelinquencyBucket bucket) {
        return switch (bucket) {
            case DPD_90_PLUS -> OpsAlertSeverity.CRITICAL;
            case DPD_61_90, DPD_31_60, DPD_1_30 -> OpsAlertSeverity.HIGH;
            case CURRENT -> OpsAlertSeverity.HIGH;
        };
    }

    private boolean isRuleEnabled(String code) {
        return alertRuleRepository.findByCode(code)
                .map(AlertRule::isEnabled)
                .orElse(false);
    }

    private void markRuleEvaluated(String code, Instant evaluatedAt) {
        alertRuleRepository.findByCode(code).ifPresent(rule -> {
            rule.markEvaluated(evaluatedAt);
            alertRuleRepository.save(rule);
        });
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record EvaluationSummary(int alertsEmitted, Instant evaluatedAt) {
    }
}
