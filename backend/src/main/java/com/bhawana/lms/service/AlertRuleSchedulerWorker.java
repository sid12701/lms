package com.bhawana.lms.service;

import com.bhawana.lms.config.ScheduledJobThreadingConfig;
import com.bhawana.lms.tenant.TenantScopedExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AlertRuleSchedulerWorker {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleSchedulerWorker.class);

    private final AlertRuleEvaluationWorker alertRuleEvaluationWorker;
    private final JobObservabilitySupport jobObservability;
    private final boolean enabled;

    public AlertRuleSchedulerWorker(
            AlertRuleEvaluationWorker alertRuleEvaluationWorker,
            JobObservabilitySupport jobObservability,
            AlertRuleProperties alertRuleProperties
    ) {
        this.alertRuleEvaluationWorker = alertRuleEvaluationWorker;
        this.jobObservability = jobObservability;
        this.enabled = alertRuleProperties.isSchedulerEnabled();
    }

    @Scheduled(fixedDelayString = "${app.alert-rules.scheduler-fixed-delay-ms:300000}", scheduler = ScheduledJobThreadingConfig.MAINTENANCE_TASK_SCHEDULER)
    public void evaluateScheduledAlertRules() {
        if (!enabled) {
            return;
        }
        jobObservability.run("alert-rule-evaluation", () ->
                TenantScopedExecution.runAsAdmin(this::evaluateScheduledAlertRulesUnderAdminScope));
    }

    /**
     * Singleton exclusion lives inside the evaluation (a durable worker lease with fencing),
     * not a transaction-scoped advisory lock: the run is a sequence of short per-rule and
     * per-batch transactions (M07), so a lock spanning it would recreate exactly the one long
     * transaction the lease exists to avoid.
     */
    void evaluateScheduledAlertRulesUnderAdminScope() {
        AlertRuleEvaluationWorker.EvaluationSummary result =
                alertRuleEvaluationWorker.evaluateScheduledRules();
        if (result.evaluatedAt() == null) {
            log.debug("alert_rule_scheduler_skipped lease_not_acquired");
            return;
        }
        log.debug("alert_rule_scheduler_completed evaluatedAt={}", result.evaluatedAt());
        if (result.alertsEmitted() > 0) {
            log.info(
                    "Alert rule scheduler emitted {} new alert(s) at {}",
                    result.alertsEmitted(),
                    result.evaluatedAt()
            );
        }
    }
}
