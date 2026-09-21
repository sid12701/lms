package com.bhawana.lms.domain;

import com.bhawana.lms.common.util.PersistedTimestamp;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alert_rule")
public class AlertRule {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 1000)
    private String description;

    @Column(nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AlertRuleAudience audience;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_kind", nullable = false, length = 32)
    private AlertRuleTriggerKind triggerKind;

    // No config payload column: evaluation thresholds come exclusively from typed
    // application configuration (app.alert-rules.*). A persisted JSON copy can only
    // drift from the evaluated values, so it was retired (V136, M06).
    @Column(name = "last_evaluated_at")
    private Instant lastEvaluatedAt;

    protected AlertRule() {
    }

    public static AlertRule seeded(
            UUID id,
            String code,
            String name,
            String description,
            AlertRuleAudience audience,
            AlertRuleTriggerKind triggerKind
    ) {
        AlertRule rule = new AlertRule();
        rule.id = id;
        rule.code = code;
        rule.name = name;
        rule.description = description;
        rule.enabled = true;
        rule.audience = audience;
        rule.triggerKind = triggerKind;
        return rule;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public AlertRuleAudience getAudience() {
        return audience;
    }

    public AlertRuleTriggerKind getTriggerKind() {
        return triggerKind;
    }

    public Instant getLastEvaluatedAt() {
        return lastEvaluatedAt;
    }

    public void markEvaluated(Instant evaluatedAt) {
        this.lastEvaluatedAt = PersistedTimestamp.normalize(evaluatedAt);
    }
}
