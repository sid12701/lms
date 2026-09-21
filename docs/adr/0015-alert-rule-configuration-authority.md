# ADR 0015 — Alert-rule configuration authority is typed application config; the API exposes it read-only

- **Status:** Accepted (2026-09-21)
- **Drives:** audit finding M06 — the rules API returned a seeded `config_json` blob that the evaluator never read, so displayed "configuration" was not the executed configuration
- **Related:** M07 batching work on `AlertRuleEvaluationWorker` (PRs #351/#353)

## Context

`alert_rule.config_json` was seeded by migrations and surfaced through `GET /api/v1/internal/alerts/rules`, but `AlertRuleEvaluationWorker` evaluates exclusively from `AlertRuleProperties` bound under `app.alert-rules.*`. The seeded values were static copies of the defaults; no code path ever let an operator edit them. An operator reading the API could therefore see a "configuration" that was not, and could never become, the source of runtime behaviour — and any future edit to the column would silently do nothing.

## Decision

The smaller of the spec's two options: YAML-backed rules with a read-only API view, not database-managed rule configuration.

1. **`AlertRuleProperties` is the single configuration authority.** The class is now `@Validated` with `@Min`/`@Max` constraints and `ignoreUnknownFields = false`, so a misspelled or out-of-range override fails at startup instead of silently evaluating with defaults.
2. **`alert_rule.config_json` is dropped (V136).** The historical payloads were never validated and never read; dropping the column is the retirement the spec calls for, and guarantees historical unvalidated JSON can never become authoritative by accident. The table keeps identity, audience, and the operator-controlled `enabled` flag — enabled state stays in the database where operators toggle it.
3. **The API renders the evaluated values.** `AlertRuleConfiguration` maps `AlertRuleProperties` onto each rule's tunables, and `AlertRuleResponse` replaces `configJson` with `effectiveConfig` (a map) plus `configSource: "application-config"`. A displayed threshold is therefore the evaluated threshold by construction. Rules without tunables (EVENT rules, the DPD sweep) return an empty object.
4. **No runtime rule editing was introduced.** The spec permits either direction; database-managed configuration would need per-rule schemas, versioning, and a write path the product does not currently need. If that requirement ever arrives it lands as a new versioned contract, not as a resurrection of `config_json`.

## Consequences

- Changing `app.alert-rules.*` moves both the evaluation boundary and the displayed value in the same deploy — proven by `AlertRuleEffectiveConfigIntegrationTest`, which overrides `stale-intake-hours` and asserts the same value drives an alert and appears in the API.
- Invalid or unknown `app.alert-rules.*` keys fail fast at startup (`AlertRulePropertiesValidationTest`) rather than silently producing a rule that evaluates differently from what it displays.
- The response contract changed (`configJson` removed); the OpenAPI snapshot and generated frontend types were regenerated in the same change.
