# Severity, Confidence, Evidence, and Reporting

## Contents

1. Code-grounding gate and classification model
2. Severity levels
3. Confidence levels
4. Finding dispositions
5. Evidence contract
6. Root-cause and remediation requirements
7. Finding template
8. Final report template
9. Review quality bar

## 1. Code-grounding gate and classification model

Automated output is not a finding, candidate finding, or probable issue. It is navigation input used to select code for inspection. Before classifying anything as an issue, establish all of the following from repository evidence:

- Production entry point and complete path to the relevant side effect.
- Callers, callees, data flow, and state transitions involved.
- Actual language/framework semantics governing the behavior.
- Effective configuration, persistence mappings, generated/registered behavior, and deployment relevance.
- Focused tests and what they prove or fail to prove.
- Compensating controls at code, database, framework, gateway, and operational boundaries.

If these cannot be established, do not place the observation in confirmed or probable issue sections and do not assign severity. Record a precise tool/environment limitation only when the missing visibility materially affects review coverage.

Classify every code-grounded issue on three independent axes:

- **Severity**: impact if the issue occurs.
- **Confidence**: strength of evidence that the issue exists and is reachable.
- **Disposition**: confirmed or probable. Record rejected observations separately as false positives or tool limitations; they are not findings.

Do not inflate severity because a scanner labels something “critical.” Do not lower severity because a defect is difficult to trigger if its realistic impact is catastrophic. State exploitability, frequency, blast radius, and compensating controls separately.

Use `Critical / High / Moderate / Low` in reports. Optionally include `P0 / P1 / P2 / P3` when the repository uses priority notation.

## 2. Severity levels

### Critical / P0

Use only for a reachable condition likely to cause one or more of:

- Unauthorized privileged access or practical remote code execution.
- Broad confidential-data or secret exposure.
- Irrecoverable or cross-tenant data corruption/loss.
- Unbounded financial misstatement or duplicate money movement.
- System-wide outage with no practical containment or recovery.
- Authentication/authorization bypass on a critical surface.

Require high confidence for a confirmed Critical finding. A probable Critical issue is allowed only when the complete code path and runtime semantics are established and one explicitly named external or deployment fact prevents confirmation.

### High / P1

Use for material correctness, security, reliability, performance, or operational failures such as:

- Tenant isolation failure with constrained reach.
- Duplicate side effects caused by missing idempotency or unsafe retries.
- Transactional partial updates that break a core invariant.
- Consistent N+1/query explosion on a high-volume endpoint.
- Race condition capable of corrupting shared state.
- Exception handling that converts failures into false success.
- Backward-incompatible API change without versioning or migration.
- Unbounded resource consumption reachable under expected load.
- Critical workflow with no effective tests and strong evidence of fragile behavior.

### Moderate / P2

Use for defects with limited current blast radius or maintainability problems with a concrete failure mode:

- Complex orchestration with partial coverage and realistic regression risk.
- Incorrect status code or validation behavior affecting clients.
- Missing timeout, retry bound, or cancellation on a noncritical dependency.
- Non-atomic update that can create repairable inconsistency.
- Duplicate policy logic already diverging or likely to diverge.
- Mis-scoped transaction or inefficient query on a moderate-volume path.
- Sensitive context omitted from logs, preventing practical diagnosis.
- Dependency/configuration issue that materially weakens production operation.

### Low / P3

Use sparingly for a material but localized issue:

- Misleading abstraction or obsolete code that demonstrably increases change risk.
- Weak assertion in a noncritical test.
- Minor resource leak with bounded lifetime.
- Inconsistent error mapping with limited client impact.

Do not report naming, formatting, stylistic preference, or theoretical future flexibility as Low. Exclude it.

## 3. Confidence levels

### High confidence

Use when at least one is true:

- A focused test or safe reproduction demonstrates the behavior.
- A complete reachable path from input to side effect is visible.
- The language/framework contract makes the outcome deterministic.
- Production configuration and caller behavior confirm the condition.

### Medium confidence

Use when code and configuration strongly indicate the issue, but runtime state, traffic shape, deployment configuration, or an external contract is unavailable.

### Low confidence

Do not use Low confidence for a reported issue. If the traced code and framework evidence are too weak for Medium confidence, exclude it from issue sections and state the material inspection limitation without severity.

Never express confidence as false numerical precision. Explain the missing evidence.

## 4. Finding dispositions

### Confirmed issue

The path is reachable, the violated invariant is established, and the impact is supported by code, tests, configuration, or a safe reproduction.

### Probable issue

The implementation path, reachability, framework/runtime semantics, and potential invariant violation are grounded in code, but one material fact outside the available repository evidence is missing—for example the deployed database isolation setting, broker delivery guarantee, production traffic cardinality, or an external provider contract. State the exact missing fact and verification needed. Do not use this disposition when callers, registration, configuration, or framework behavior have not been inspected.

### False positive

The automated signal does not represent a defect. Record why, such as:

- Framework/reflection/DI entry point.
- Generated or externally consumed API surface.
- Database constraint or transaction control prevents the claimed state.
- Query is preloaded/batched despite a loop in application code.
- Exception is intentionally translated at a boundary.
- Test double or fixture is not production code.

Do not add suppression comments during review. Recommend a narrow suppression only when the tool will otherwise keep producing the same verified false positive.

### Tool limitation

The tool could not analyze the relevant construct, language, generated source, runtime path, environment, or coverage mapping. Describe what was skipped and how it affects review coverage; do not assign issue severity or confidence to the limitation itself.

## 5. Evidence contract

Every reported confirmed or probable issue must include:

1. **Location**: repository-relative or clickable absolute file path, symbol, and tight line reference.
2. **Execution path**: production entry point, callers/callees, state transitions, and side effect.
3. **Framework/runtime semantics**: exact registration, lifecycle, transaction, serialization, validation, retry, concurrency, or cleanup behavior that determines the outcome.
4. **Effective configuration**: relevant application, environment, persistence, gateway, and deployment configuration available in the repository.
5. **Trigger**: request, input, state, schedule, concurrency interleaving, deployment condition, or data shape needed.
6. **Violated invariant**: what must remain true but does not.
7. **Impact**: correctness, security, availability, latency, data, money, operability, or maintenance consequence.
8. **Reachability**: why production can execute the path, including DI, reflection, scheduling, routing, or external callers.
9. **Tests inspected**: focused tests and the behavior they prove or leave exposed.
10. **Compensating controls**: constraints, authorization, retries, monitoring, framework behavior, or boundary protections considered.
11. **Automated evidence**: command/tool output when relevant, explicitly secondary to source evidence.
12. **Severity and confidence**: each with a one-sentence rationale.

For performance findings, add expected cardinality, query count, allocation/resource growth, or benchmark/profile evidence. For security findings, add trust boundary, source-to-sink path, authorization context, and data sensitivity. For concurrency findings, provide the interleaving or delivery sequence.

## 6. Root-cause and remediation requirements

Do not stop at “add validation,” “split this method,” or “handle the exception.” Explain why the current ownership or invariant failed.

A remediation recommendation must include:

- The canonical layer that should own the fix.
- The smallest safe behavioral change.
- Whether a database constraint, transaction, API contract, or operational control is required.
- Compatibility and migration considerations.
- Focused regression tests.
- Verification and rollout signals.
- Tradeoffs of meaningful alternatives.

Avoid recommending abstractions solely to reduce line count or complexity scores. A new layer must own a coherent policy, side effect, lifecycle, or boundary used by real consumers.

## 7. Finding template

```markdown
### [High][High confidence] Duplicate payment can occur after client retry

Location: `services/payments/PaymentService.java:118` — `capturePayment`

Evidence: The POST handler accepts a client idempotency key, but the service checks for an
existing payment before entering the transaction. Two requests can both observe absence and
insert separate captures. No unique database constraint covers `(merchant_id, idempotency_key)`.

Execution path: `POST /payments` -> `PaymentController.capture` ->
`PaymentService.capturePayment` -> processor capture -> `PaymentRepository.save`.

Framework/runtime behavior: Spring transaction interception begins at the service method, but
the existence check and later insert do not serialize concurrent requests. PostgreSQL's default
isolation does not make the read-then-insert sequence atomic.

Configuration: The production profile enables this controller and uses the same JPA mapping;
Flyway migrations contain no tenant/key uniqueness constraint.

Trigger: Two concurrent retries with the same key before either transaction commits.

Impact: The external processor may be charged twice and the ledger records two payments.

Tests and controls inspected: Service tests cover sequential retries only. No database constraint,
provider idempotency key, gateway deduplication, or concurrent integration test compensates for
the race.

Severity/confidence: High because duplicate money movement affects a core workflow; High
confidence because the reachable path, transaction semantics, schema, and concurrent
interleaving are established from code and configuration.

Root cause: Idempotency is implemented as a preflight lookup rather than an atomic persistence
invariant.

Recommendation: Add the unique constraint, create-or-load within the transaction, translate the
constraint collision into the original result, and retain the same response contract.

Verification: Add a concurrent integration test, run the migration against an empty and populated
schema, and verify duplicate-key metrics remain visible.
```

## 8. Final report template

```markdown
# Back-End Code Quality Review

## Outcome

- Scope and services reviewed
- Detected stack and architecture
- Overall release/readiness assessment
- Test, coverage, build, and analyzer summary

## Confirmed issues

### Critical
...

### High
...

### Moderate
...

### Low
...

## Probable issues requiring evidence

- Code-grounded issue, traced path, established framework behavior, exact unavailable external/deployment fact, and verification action

## False positives rejected

- Tool signal and concrete reason it is not actionable

## Tool and environment limitations

- Skipped commands, unavailable infrastructure, unmapped coverage, unsupported code

## Test and coverage assessment

- Critical paths covered/uncovered, assertion quality, integration gaps, flaky/brittle patterns

## Prioritized remediation plan

1. Behavior-protection tests
2. Critical/high corrections
3. Bounded maintainability work
4. Follow-up measurement

## Commands and verification

- Exact command — result and relevant counts
```

Put findings before general summaries when reviewing a diff or PR. If there are no confirmed findings, lead with that result and list residual risk.

## 9. Review quality bar

A report fails this skill's standard if it:

- Copies scanner severity without validation.
- Reports or prioritizes an analyzer observation without tracing it through production code.
- Omits a path, symbol, trigger, or impact.
- Omits callers, framework/runtime semantics, effective configuration, tests, or compensating controls.
- Labels code dead without checking framework/runtime discovery.
- Calls complexity a defect without identifying a failure or change-risk mechanism.
- Suggests a generic wrapper or layer without a clear owner and consumer.
- Treats missing line coverage as proof of broken behavior.
- Claims security, performance, or race impact without a source-to-sink path, cardinality, or interleaving.
- Hides skipped tests or infrastructure failures.
- Mixes confirmed issues with speculation.
- Uses the probable section as a holding area for incomplete source inspection.
