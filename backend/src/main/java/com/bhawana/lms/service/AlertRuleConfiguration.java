package com.bhawana.lms.service;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Read-only view of the effective per-rule configuration (M06).
 *
 * <p>{@link AlertRuleProperties} is the single authority every rule in
 * {@link AlertRuleEvaluationWorker} evaluates against; this component renders the
 * same bean into the per-rule shape the API returns, so a displayed threshold can
 * never diverge from the evaluated one. The field names deliberately keep the
 * legacy {@code config_json} vocabulary ({@code staleHours}, {@code windowDays}…)
 * so existing operator runbooks still read correctly.
 *
 * <p>Rules with no tunable threshold (event-driven rules, the DPD sweep) map to an
 * empty object — not to a stale persisted payload. Engine mechanics (batch limit,
 * lease, statement timeout, scheduler cadence) are worker settings, not rule
 * configuration, and are intentionally not exposed per rule.
 */
@Component
public class AlertRuleConfiguration {

    /** Value surfaced as {@code configSource} on the rules API: config is env-managed, never DB-editable. */
    public static final String CONFIG_SOURCE = "application-config";

    private final AlertRuleProperties properties;

    public AlertRuleConfiguration(AlertRuleProperties properties) {
        this.properties = properties;
    }

    /**
     * The evaluated tunables for one rule code. Unknown or threshold-free codes
     * return an empty map; callers never see a persisted blob that evaluation
     * would ignore.
     */
    public Map<String, Object> effectiveConfig(String ruleCode) {
        Map<String, Object> config = new LinkedHashMap<>();
        switch (ruleCode) {
            case "STALE_INTAKE" -> config.put("staleHours", properties.getStaleIntakeHours());
            case "STUCK_DISBURSEMENT" -> config.put("stuckHours", properties.getStuckDisbursementHours());
            case "LSP_AUTO_REJECT_SPIKE" -> {
                config.put("windowDays", properties.getLspRejectWindowDays());
                config.put("minSamples", properties.getLspRejectMinSamples());
                config.put("rejectRatePct", properties.getLspRejectRatePct());
            }
            case "AUTH_BRUTE_FORCE" -> {
                config.put("threshold", properties.getAuthBruteForceThreshold());
                config.put("windowMinutes", properties.getAuthBruteForceWindowMinutes());
            }
            case "AUTH_BRUTE_FORCE_DISTRIBUTED" -> {
                config.put("threshold", properties.getAuthBruteForceDistributedThreshold());
                config.put("distinctIpMin", properties.getAuthBruteForceDistributedDistinctIpMin());
                config.put("windowHours", properties.getAuthBruteForceDistributedWindowHours());
            }
            case "OLDEST_TRANSACTION_AGE" ->
                config.put("ageSeconds", properties.getOldestTransactionAgeSeconds());
            default -> {
                // No tunable configuration (e.g. EVENT rules, DPD_BUCKET_TRANSITION).
            }
        }
        return java.util.Collections.unmodifiableMap(config);
    }
}
