package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.util.AlertContextJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.bhawana.lms.domain.AlertRule;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanDelinquencyBucket;
import com.bhawana.lms.domain.LoanDelinquencyState;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.OpsAlert;
import com.bhawana.lms.domain.OpsAlertSeverity;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.domain.RevocationSource;
import com.bhawana.lms.config.BusinessCalendar;
import com.bhawana.lms.repo.AlertRuleSetQueryRepository;
import com.bhawana.lms.repo.AlertRuleSetQueryRepository.DelinquencyEvaluationRow;
import com.bhawana.lms.repo.AlertRuleSetQueryRepository.StaleIntakeCandidate;
import com.bhawana.lms.repo.AlertRuleSetQueryRepository.StuckDisbursementCandidate;
import com.bhawana.lms.repo.AlertRuleRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.AuthEventAuditRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import com.bhawana.lms.repo.LoanDelinquencyStateRepository;
import com.bhawana.lms.repo.LspRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled evaluation of configured {@link AlertRule} records. Ad-hoc domain emission lives in
 * {@link OpsAlertEmitters}.
 */
@Service
public class AlertRuleEvaluationWorker {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleEvaluationWorker.class);
    private static final String AUTO_LOCKOUT_ACTOR = "SYSTEM_AUTO_LOCKOUT";

    private final AlertRuleRepository alertRuleRepository;
    private final AlertRuleSetQueryRepository alertRuleSetQueryRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanApplicationDocumentChecklistRepository checklistRepository;
    private final LoanApplicationStatusTransitionRepository statusTransitionRepository;
    private final LspRepository lspRepository;
    private final AppUserRepository appUserRepository;
    private final AuthEventAuditRepository authEventAuditRepository;
    private final SessionRevocationService sessionRevocationService;
    private final OpsAlertService opsAlertService;
    private final LoanDelinquencyStateRepository loanDelinquencyStateRepository;
    private final BusinessCalendar businessCalendar;
    private final AlertRuleProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final Counter dpdBucketTransitionCounter;

    public AlertRuleEvaluationWorker(
            AlertRuleRepository alertRuleRepository,
            AlertRuleSetQueryRepository alertRuleSetQueryRepository,
            LoanApplicationRepository loanApplicationRepository,
            LoanApplicationDocumentChecklistRepository checklistRepository,
            LoanApplicationStatusTransitionRepository statusTransitionRepository,
            LspRepository lspRepository,
            AppUserRepository appUserRepository,
            AuthEventAuditRepository authEventAuditRepository,
            SessionRevocationService sessionRevocationService,
            OpsAlertService opsAlertService,
            LoanDelinquencyStateRepository loanDelinquencyStateRepository,
            BusinessCalendar businessCalendar,
            AlertRuleProperties properties,
            Clock clock,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry
    ) {
        this.alertRuleRepository = alertRuleRepository;
        this.alertRuleSetQueryRepository = alertRuleSetQueryRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.checklistRepository = checklistRepository;
        this.statusTransitionRepository = statusTransitionRepository;
        this.lspRepository = lspRepository;
        this.appUserRepository = appUserRepository;
        this.authEventAuditRepository = authEventAuditRepository;
        this.sessionRevocationService = sessionRevocationService;
        this.opsAlertService = opsAlertService;
        this.loanDelinquencyStateRepository = loanDelinquencyStateRepository;
        this.businessCalendar = businessCalendar;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.dpdBucketTransitionCounter = Counter.builder("lms.dpd.bucket_transition")
                .description("DPD bucket worsening transitions detected during scheduled evaluation")
                .register(meterRegistry);
    }

    @Transactional
    public EvaluationSummary evaluateScheduledRules() {
        Instant evaluatedAt = clock.instant();
        int emitted = 0;
        emitted += evaluateStaleIntake(evaluatedAt);
        emitted += evaluateStuckDisbursement(evaluatedAt);
        emitted += evaluateDpdBucketTransitions(evaluatedAt);
        emitted += evaluateLspAutoRejectSpikes(evaluatedAt);
        emitted += evaluateAuthBruteForce(evaluatedAt);
        emitted += evaluateAuthBruteForceDistributed(evaluatedAt);
        markRuleEvaluated("STALE_INTAKE", evaluatedAt);
        markRuleEvaluated("STUCK_DISBURSEMENT", evaluatedAt);
        markRuleEvaluated("DPD_BUCKET_TRANSITION", evaluatedAt);
        markRuleEvaluated("LSP_AUTO_REJECT_SPIKE", evaluatedAt);
        markRuleEvaluated("AUTH_BRUTE_FORCE", evaluatedAt);
        markRuleEvaluated("AUTH_BRUTE_FORCE_DISTRIBUTED", evaluatedAt);
        return new EvaluationSummary(emitted, evaluatedAt);
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
        List<StaleIntakeCandidate> stale = alertRuleSetQueryRepository.findStaleIntakeCandidates(
                cutoff,
                properties.getEvaluationBatchLimit()
        );
        int emitted = 0;
        for (StaleIntakeCandidate candidate : stale) {
            if (isRequiredDocumentChecklistComplete(candidate.applicationId())) {
                continue;
            }
            OpsAlert created = opsAlertService.createAlertIfAbsent(
                    OpsAlertType.STALE_INTAKE,
                    OpsAlertSeverity.HIGH,
                    "Stale loan intake",
                    "Application "
                            + candidate.externalLoanId()
                            + " has been INITIALIZED for more than "
                            + properties.getStaleIntakeHours()
                            + " hours with incomplete required documents.",
                    "LOAN_APPLICATION",
                    candidate.applicationId(),
                    CorrelationIdHolder.get(),
                    alertContext(Map.of("applicationId", candidate.applicationId().toString()))
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
        List<StuckDisbursementCandidate> retrying = alertRuleSetQueryRepository.findStuckDisbursementCandidates(
                cutoff,
                properties.getEvaluationBatchLimit()
        );
        int emitted = 0;
        for (StuckDisbursementCandidate candidate : retrying) {
            OpsAlert created = opsAlertService.createAlertIfAbsent(
                    OpsAlertType.STUCK_DISBURSEMENT,
                    OpsAlertSeverity.HIGH,
                    "Disbursement retry stuck",
                    "Application "
                            + candidate.externalLoanId()
                            + " has been in DISBURSEMENT_RETRY since "
                            + candidate.lastRetryAt()
                            + ". Payout adapter may need ops intervention.",
                    "LOAN_APPLICATION",
                    candidate.applicationId(),
                    CorrelationIdHolder.get(),
                    alertContext(Map.of("applicationId", candidate.applicationId().toString()))
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
        LocalDate today = businessCalendar.today();
        List<DelinquencyEvaluationRow> rows = alertRuleSetQueryRepository.findServicingDelinquencyRows(today);
        int emitted = 0;
        for (DelinquencyEvaluationRow row : rows) {
            LoanDelinquencyBucket previousBucket = row.previousBucket();
            LoanDelinquencyBucket currentBucket = row.currentBucket();
            int currentMaxDaysPastDue = row.maxDaysPastDue();

            if (currentBucket.ordinal() > previousBucket.ordinal()) {
                String bucketLabel = currentBucket.name();
                log.info(
                        "dpd_bucket_transition applicationId={} previousBucket={} currentBucket={} maxDaysPastDue={}",
                        row.applicationId(),
                        previousBucket.name(),
                        bucketLabel,
                        currentMaxDaysPastDue
                );
                OpsAlert created = opsAlertService.createAlertIfAbsent(
                        OpsAlertType.DPD_BUCKET_TRANSITION,
                        severityForBucket(currentBucket),
                        "Delinquency bucket " + bucketLabel,
                        "Loan "
                                + row.externalLoanId()
                                + " is "
                                + currentMaxDaysPastDue
                                + " days past due (bucket "
                                + bucketLabel
                                + ", overdue ₹"
                                + row.overdueAmount()
                                + ").",
                        "LOAN_APPLICATION",
                        row.applicationId(),
                        CorrelationIdHolder.get(),
                        alertContext(Map.of(
                                "applicationId", row.applicationId().toString(),
                                "bucket", bucketLabel,
                                "previousBucket", previousBucket.name(),
                                "maxDaysPastDue", currentMaxDaysPastDue
                        ))
                );
                dpdBucketTransitionCounter.increment();
                if (created != null) {
                    emitted++;
                }
            }

            if (shouldPersistDelinquencyState(
                    row.existingStateId(),
                    row.previousBucket(),
                    row.previousMaxDaysPastDue(),
                    row.currentBucket(),
                    row.maxDaysPastDue()
            )) {
                LoanDelinquencyState state = row.existingStateId() == null
                        ? new LoanDelinquencyState(loanApplicationRepository.getReferenceById(row.applicationId()))
                        : loanDelinquencyStateRepository.findById(row.existingStateId()).orElseThrow();
                state.refresh(currentBucket, currentMaxDaysPastDue, evaluatedAt);
                loanDelinquencyStateRepository.save(state);
            }
        }
        return emitted;
    }

    private static boolean shouldPersistDelinquencyState(
            UUID existingStateId,
            LoanDelinquencyBucket previousBucket,
            int previousMaxDaysPastDue,
            LoanDelinquencyBucket currentBucket,
            int currentMaxDaysPastDue
    ) {
        if (existingStateId != null) {
            return previousBucket != currentBucket
                    || previousMaxDaysPastDue != currentMaxDaysPastDue;
        }
        return currentBucket != LoanDelinquencyBucket.CURRENT || currentMaxDaysPastDue != 0;
    }

    private int evaluateAuthBruteForce(Instant evaluatedAt) {
        if (!isRuleEnabled("AUTH_BRUTE_FORCE")) {
            return 0;
        }
        Instant since = evaluatedAt.minus(Duration.ofMinutes(properties.getAuthBruteForceWindowMinutes()));
        long threshold = properties.getAuthBruteForceThreshold();
        int emitted = 0;
        for (AuthEventAuditRepository.UsernameIpFailureProjection candidate
                : authEventAuditRepository.findLoginFailureGroupsAtOrAboveThreshold(since, threshold)) {
            AppUser user = appUserRepository.findByUsername(candidate.getUsername()).orElse(null);
            if (user == null || user.isLocked()) {
                continue;
            }
            user.lockForBruteForce(evaluatedAt);
            sessionRevocationService.revokeAllSessions(
                    user,
                    AUTO_LOCKOUT_ACTOR,
                    AppUser.LOCK_REASON_BRUTE_FORCE,
                    candidate.getActorIp(),
                    "auth-brute-force:" + user.getId(),
                    RevocationSource.BRUTE_FORCE_LOCKOUT
            );
            String contextJson = alertContext(Map.of(
                    "userId", user.getId().toString(),
                    "username", user.getUsername(),
                    "actorIp", candidate.getActorIp(),
                    "failureCount", candidate.getFailureCount(),
                    "windowMinutes", properties.getAuthBruteForceWindowMinutes()
            ));
            OpsAlert created = opsAlertService.createAlertIfAbsent(
                    OpsAlertType.AUTH_BRUTE_FORCE,
                    OpsAlertSeverity.HIGH,
                    "Auth brute-force lockout: " + user.getUsername(),
                    "User "
                            + user.getUsername()
                            + " was locked after "
                            + candidate.getFailureCount()
                            + " failed logins from IP "
                            + candidate.getActorIp()
                            + " within "
                            + properties.getAuthBruteForceWindowMinutes()
                            + " minutes.",
                    "APP_USER",
                    user.getId(),
                    "auth-brute-force:" + user.getId(),
                    contextJson
            );
            if (created != null) {
                emitted++;
            }
        }
        return emitted;
    }

    private int evaluateAuthBruteForceDistributed(Instant evaluatedAt) {
        if (!isRuleEnabled("AUTH_BRUTE_FORCE_DISTRIBUTED")) {
            return 0;
        }
        Instant since = evaluatedAt.minus(Duration.ofHours(properties.getAuthBruteForceDistributedWindowHours()));
        long threshold = properties.getAuthBruteForceDistributedThreshold();
        long distinctIpMin = properties.getAuthBruteForceDistributedDistinctIpMin();
        int emitted = 0;
        for (AuthEventAuditRepository.UsernameDistributedFailureProjection candidate
                : authEventAuditRepository.findLoginFailureUserGroupsAtOrAboveDistributedThreshold(
                        since,
                        threshold,
                        distinctIpMin
                )) {
            AppUser user = appUserRepository.findByUsername(candidate.getUsername()).orElse(null);
            if (user == null || user.isLocked()) {
                continue;
            }
            String contextJson = alertContext(Map.of(
                    "userId", user.getId().toString(),
                    "username", user.getUsername(),
                    "failureCount", candidate.getFailureCount(),
                    "distinctIpCount", candidate.getDistinctIpCount(),
                    "windowHours", properties.getAuthBruteForceDistributedWindowHours()
            ));
            OpsAlert created = opsAlertService.createAlertIfAbsent(
                    OpsAlertType.AUTH_BRUTE_FORCE_DISTRIBUTED,
                    OpsAlertSeverity.HIGH,
                    "Distributed auth brute-force: " + user.getUsername(),
                    "User "
                            + user.getUsername()
                            + " had "
                            + candidate.getFailureCount()
                            + " failed logins from "
                            + candidate.getDistinctIpCount()
                            + " distinct IPs within "
                            + properties.getAuthBruteForceDistributedWindowHours()
                            + " hours.",
                    "APP_USER",
                    user.getId(),
                    "auth-brute-force-distributed:" + user.getId(),
                    contextJson
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
                    alertContext(Map.of(
                            "lspId", lspId.toString(),
                            "rejectRatePct", rejectRatePct,
                            "intakes", intakes,
                            "rejects", rejects
                    ))
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
                .filter(item -> LoanApplicationDocumentRequirements.isIntakeRequired(item.getDocumentType()))
                .allMatch(LoanApplicationDocumentRequirements::isChecklistItemComplete);
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

    private String alertContext(Map<String, Object> context) {
        return AlertContextJson.serialize(objectMapper, log, context);
    }

    public record EvaluationSummary(int alertsEmitted, Instant evaluatedAt) {
    }
}
