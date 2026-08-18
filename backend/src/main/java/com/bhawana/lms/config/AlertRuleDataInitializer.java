package com.bhawana.lms.config;

import com.bhawana.lms.domain.AlertRule;
import com.bhawana.lms.domain.AlertRuleAudience;
import com.bhawana.lms.domain.AlertRuleTriggerKind;
import com.bhawana.lms.repo.AlertRuleRepository;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Back-fills the nine alert rules for contexts that build the schema without Flyway.
 *
 * <p>Wherever Flyway runs, the migrations are the source of truth: V60 seeds seven rules,
 * V119 retires the webhook dead-letter one of them, and V95 and V118 add three more. Both
 * paths below are then no-ops — {@code seedIfEmpty} returns early on a non-empty table and
 * {@code ensureRule} skips a code that already exists.
 */
@Component
public class AlertRuleDataInitializer implements ApplicationRunner {

    private final AlertRuleRepository alertRuleRepository;

    public AlertRuleDataInitializer(AlertRuleRepository alertRuleRepository) {
        this.alertRuleRepository = alertRuleRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        TenantScopedExecution.runAsAdmin(() -> {
            seedIfEmpty();
            ensureRule(
                    UUID.fromString("00000000-0000-4000-8000-000000000608"),
                    "AUTH_BRUTE_FORCE",
                    "Auth brute-force lockout",
                    "Locks an account after repeated failed password logins from the same username and IP within a short window.",
                    AlertRuleAudience.SYSTEM_ADMIN,
                    AlertRuleTriggerKind.SCHEDULED,
                    "{\"threshold\":5,\"windowMinutes\":10}"
            );
            ensureRule(
                    UUID.fromString("00000000-0000-4000-8000-000000000609"),
                    "AUTH_BRUTE_FORCE_DISTRIBUTED",
                    "Distributed auth brute-force",
                    "Alerts when a username sees many failed logins from many distinct IPs over a longer window (no account lock).",
                    AlertRuleAudience.SYSTEM_ADMIN,
                    AlertRuleTriggerKind.SCHEDULED,
                    "{\"threshold\":20,\"distinctIpMin\":5,\"windowHours\":24}"
            );
            ensureRule(
                    UUID.fromString("00000000-0000-4000-8000-000000000610"),
                    "OLDEST_TRANSACTION_AGE",
                    "Oldest open transaction age",
                    "A long-running database transaction anywhere on the cluster holds back the loan event feed snapshot for every LSP until it completes.",
                    AlertRuleAudience.SYSTEM_ADMIN,
                    AlertRuleTriggerKind.SCHEDULED,
                    "{\"ageSeconds\":300}"
            );
        });
    }

    private void seedIfEmpty() {
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
                        "An API rate limit was exceeded — LSP write, partner event feed, document read, or auth endpoint.",
                        AlertRuleAudience.SYSTEM_ADMIN,
                        AlertRuleTriggerKind.EVENT,
                        "{}"
                )
        ));
    }

    private void ensureRule(
            UUID id,
            String code,
            String name,
            String description,
            AlertRuleAudience audience,
            AlertRuleTriggerKind triggerKind,
            String configJson
    ) {
        if (alertRuleRepository.findByCode(code).isPresent()) {
            return;
        }
        alertRuleRepository.save(AlertRule.seeded(id, code, name, description, audience, triggerKind, configJson));
    }
}
