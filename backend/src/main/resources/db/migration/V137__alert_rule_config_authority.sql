-- M06: retire alert_rule.config_json. The seeded JSON was never read by the evaluator —
-- AlertRuleEvaluationWorker thresholds come exclusively from typed configuration
-- (app.alert-rules.* / AlertRuleProperties), so the column could only ever present a stale,
-- misleading copy of the real rule configuration. The API now exposes the effective typed
-- configuration instead of this dead column.
--
-- The historical values were static copies of the application defaults (no operator ever
-- edited them through any code path), so nothing needs backfilling or preserving.

ALTER TABLE alert_rule DROP COLUMN config_json;

COMMENT ON TABLE alert_rule IS
    'Alert rule catalogue: identity, audience and the enabled flag only. Evaluation thresholds live in typed application configuration (app.alert-rules.*); the API exposes them read-only as effectiveConfig.';
