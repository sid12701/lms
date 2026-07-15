package com.bhawana.lms.service;

import com.bhawana.lms.tenant.TenantScopedExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AlertRuleSchedulerWorker {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleSchedulerWorker.class);

    private final AlertRuleEvaluationWorker alertRuleEvaluationWorker;
    private final PostgresAdvisoryLockSupport advisoryLockSupport;
    private final PortfolioKpiProperties portfolioKpiProperties;
    private final boolean enabled;

    public AlertRuleSchedulerWorker(
            AlertRuleEvaluationWorker alertRuleEvaluationWorker,
            PostgresAdvisoryLockSupport advisoryLockSupport,
            PortfolioKpiProperties portfolioKpiProperties,
            AlertRuleProperties alertRuleProperties
    ) {
        this.alertRuleEvaluationWorker = alertRuleEvaluationWorker;
        this.advisoryLockSupport = advisoryLockSupport;
        this.portfolioKpiProperties = portfolioKpiProperties;
        this.enabled = alertRuleProperties.isSchedulerEnabled();
    }

    @Scheduled(fixedDelayString = "${app.alert-rules.scheduler-fixed-delay-ms:300000}")
    public void evaluateScheduledAlertRules() {
        if (!enabled) {
            return;
        }
        TenantScopedExecution.runAsAdmin(this::evaluateScheduledAlertRulesUnderAdminScope);
    }

    void evaluateScheduledAlertRulesUnderAdminScope() {
        long lockId = portfolioKpiProperties.getAdvisoryLockId() + 1L;
        if (!advisoryLockSupport.tryAcquire(lockId)) {
            log.debug("alert_rule_scheduler_skipped lock_not_acquired lockId={}", lockId);
            return;
        }
        try {
            AlertRuleEvaluationWorker.EvaluationSummary summary = alertRuleEvaluationWorker.evaluateScheduledRules();
            if (summary.alertsEmitted() > 0) {
                log.info(
                        "Alert rule scheduler emitted {} new alert(s) at {}",
                        summary.alertsEmitted(),
                        summary.evaluatedAt()
                );
            }
        } finally {
            advisoryLockSupport.release(lockId);
        }
    }
}
