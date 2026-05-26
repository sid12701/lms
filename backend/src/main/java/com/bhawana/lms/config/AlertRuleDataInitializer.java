package com.bhawana.lms.config;

import com.bhawana.lms.domain.AlertRule;
import com.bhawana.lms.domain.AlertRuleAudience;
import com.bhawana.lms.domain.AlertRuleTriggerKind;
import com.bhawana.lms.repo.AlertRuleRepository;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ensures the seven Follow-up #2 alert rules exist. Flyway V60 seeds them for
 * Postgres deployments; this runner back-fills H2 test contexts where Flyway is
 * disabled and Hibernate {@code ddl-auto} creates the schema.
 */
@Component
public class AlertRuleDataInitializer implements ApplicationRunner {

    private final AlertRuleRepository alertRuleRepository;

    public AlertRuleDataInitializer(AlertRuleRepository alertRuleRepository) {
        this.alertRuleRepository = alertRuleRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (alertRuleRepository.count() > 0) {
            return;
        }
        alertRuleRepository.saveAll(java.util.List.of(
                AlertRule.seeded(
                        UUID.fromString("00000000-0000-4000-8000-000000000601"),
                        "STALE_INTAKE",
                        "Stale intake",
                        "INITIALIZED applications older than 24 hours with an incomplete required-document checklist.",
                        AlertRuleAudience.OPS,
                        AlertRuleTriggerKind.SCHEDULED,
                        "{\"staleHours\":24}"
                ),
                AlertRule.seeded(
                        UUID.fromString("00000000-0000-4000-8000-000000000602"),
                        "STUCK_DISBURSEMENT",
                        "Stuck disbursement retry",
                        "Applications in DISBURSEMENT_RETRY for more than 2 hours.",
                        AlertRuleAudience.OPS,
                        AlertRuleTriggerKind.SCHEDULED,
                        "{\"stuckHours\":2}"
                ),
                AlertRule.seeded(
                        UUID.fromString("00000000-0000-4000-8000-000000000603"),
                        "DPD_BUCKET_TRANSITION",
                        "DPD bucket escalation",
                        "UNDER_REPAYMENT loans whose delinquency bucket is past CURRENT.",
                        AlertRuleAudience.OPS,
                        AlertRuleTriggerKind.SCHEDULED,
                        "{}"
                ),
                AlertRule.seeded(
                        UUID.fromString("00000000-0000-4000-8000-000000000604"),
                        "LSP_AUTO_REJECT_SPIKE",
                        "LSP auto-reject rate spike",
                        "LSP partners whose auto-reject rate exceeds the configured threshold.",
                        AlertRuleAudience.SYSTEM_ADMIN,
                        AlertRuleTriggerKind.SCHEDULED,
                        "{\"windowDays\":7,\"minSamples\":10,\"rejectRatePct\":40}"
                ),
                AlertRule.seeded(
                        UUID.fromString("00000000-0000-4000-8000-000000000605"),
                        "WEBHOOK_DEAD_LETTER",
                        "Webhook dead letter",
                        "Webhook outbox event exhausted retries and reached permanent failure.",
                        AlertRuleAudience.SYSTEM_ADMIN,
                        AlertRuleTriggerKind.EVENT,
                        "{}"
                ),
                AlertRule.seeded(
                        UUID.fromString("00000000-0000-4000-8000-000000000606"),
                        "BORROWER_ACTIVE_LOAN_DUPLICATE",
                        "One open loan violation",
                        "LSP intake blocked because the borrower already has an open loan.",
                        AlertRuleAudience.SYSTEM_ADMIN,
                        AlertRuleTriggerKind.EVENT,
                        "{}"
                ),
                AlertRule.seeded(
                        UUID.fromString("00000000-0000-4000-8000-000000000607"),
                        "RATE_LIMIT_BREACH",
                        "Rate limit breach",
                        "LSP write or auth endpoint rate limit exceeded.",
                        AlertRuleAudience.SYSTEM_ADMIN,
                        AlertRuleTriggerKind.EVENT,
                        "{}"
                )
        ));
    }
}
