package com.bhawana.lms.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AlertRuleSchedulerWorker {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleSchedulerWorker.class);

    private final AlertRuleEvaluationService alertRuleEvaluationService;
    private final boolean enabled;

    public AlertRuleSchedulerWorker(
            AlertRuleEvaluationService alertRuleEvaluationService,
            AlertRuleProperties properties
    ) {
        this.alertRuleEvaluationService = alertRuleEvaluationService;
        this.enabled = properties.isSchedulerEnabled();
    }

    @Scheduled(fixedDelayString = "${app.alert-rules.scheduler-fixed-delay-ms:300000}")
    public void evaluateScheduledAlertRules() {
        if (!enabled) {
            return;
        }
        AlertRuleEvaluationService.EvaluationSummary summary = alertRuleEvaluationService.evaluateScheduledRules();
        if (summary.alertsEmitted() > 0) {
            log.info(
                    "Alert rule scheduler emitted {} new alert(s) at {}",
                    summary.alertsEmitted(),
                    summary.evaluatedAt()
            );
        }
    }
}
