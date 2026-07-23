# Review Examples and Calibration

## Contents

1. Full-service review routing
2. Confirmed database/idempotency finding
3. Code-grounded probable reliability finding
4. Dead-code false positive
5. Complexity finding rejected
6. Test-quality finding
7. Follow-up verification report

## 1. Full-service review routing

User request:

```text
Use $backend-code-quality-review on the payments service. Do not modify code.
```

Expected workflow:

1. Inspect repository instructions and working-tree state.
2. Run `detect_backend_stack.py` at the repository root.
3. Confirm the payments service is Java 21/Spring Boot/Maven with JPA, PostgreSQL, Flyway, JaCoCo, SpotBugs, and Testcontainers.
4. Read the Java/Kotlin portion of `stack-tooling.md` and relevant review domains.
5. Use `./mvnw` and configured goals; do not install a new analyzer.
6. Measure tests and coverage, recording skipped container tests if Docker is unavailable.
7. Prioritize payment creation, idempotency, transaction boundaries, external processor retries, authorization, and audit records.
8. Use automated dead-code and complexity output only to select Spring annotations, repositories, serialization paths, callers, configuration, and tests for inspection.
9. Report only issues grounded through those code paths and framework semantics, separating code-grounded probable issues, false positives, limitations, and remediation without editing code.

## 2. Confirmed database/idempotency finding

Automated signal:

- Complexity tool flags a 75-line payment method.
- Coverage reports 42% branch coverage.

Manual validation:

- The controller accepts `Idempotency-Key`.
- The service performs `existsByKey`, calls the external processor, then inserts.
- No unique constraint includes tenant and key.
- Two concurrent calls can pass the lookup.

Correct classification:

```text
High severity, High confidence, Confirmed
```

The finding is not “method too long.” The material issue is a non-atomic idempotency invariant that can duplicate an external charge. Recommend a database uniqueness constraint, transactional create-or-load semantics, safe collision translation, and a concurrent integration test.

## 3. Code-grounded probable reliability finding

Automated observation:

- A retry analyzer highlights an outbound payment-provider call.

Code-grounding evidence:

- The public capture endpoint calls `PaymentService.capture`, which invokes the provider client before persisting the final ledger state.
- The configured HTTP client retries connection resets and read timeouts twice.
- The retry interceptor applies to this client method, and no application idempotency key is supplied to the provider request.
- Focused tests prove retry execution but use a stub provider that does not model duplicate processing.
- The provider's idempotency contract is external and unavailable in the repository.

Correct classification:

```text
High severity, Medium confidence, Probable
```

The code path, retry framework behavior, configuration, side effect, and test limitation are established. The only missing fact is whether the external provider deduplicates identical requests. State that fact explicitly and verify it from the provider contract or a safe sandbox test. If the client call path or retry configuration had not been inspected, this would not qualify as a probable issue.

## 4. Dead-code false positive

Tool signal:

- `ReconciliationJob.run()` has no direct callers.

Manual validation:

- The class has the framework's scheduled-job annotation.
- Scheduling is enabled in production configuration.
- A startup test verifies registration.

Correct disposition:

```text
False positive — framework-discovered entry point
```

Do not delete the job or add an arbitrary direct reference. If the tool repeatedly reports it, recommend the narrowest framework-aware configuration or suppression with the registration evidence documented.

## 5. Complexity finding rejected

Tool signal:

- A protocol status mapper has cyclomatic complexity 28.

Manual validation:

- It is an exhaustive, flat enum switch.
- Every protocol value is covered by table-driven tests.
- Each arm returns a declarative constant.
- No side effects or shared state exist.

Correct disposition:

```text
False positive for remediation purposes
```

The metric is accurate but does not expose material change risk. Splitting the switch would make the mapping harder to audit.

## 6. Test-quality finding

Evidence:

- Authorization integration tests only assert HTTP 200 for administrators.
- No negative role/tenant case exists.
- The endpoint accepts an object ID and loads it without a tenant predicate.

Classification depends on production code:

- If a global security filter proves tenant ownership, report a Moderate test gap rather than an authorization defect.
- If no compensating authorization exists, report the reachable isolation failure at High/Critical severity and mention the missing negative test as supporting evidence.

Never report “low coverage” as the root issue. Report the unverified invariant.

## 7. Follow-up verification report

```markdown
## Outcome

The duplicate-payment finding is remediated. Tenant-scoped idempotency is now enforced by a
database constraint and transactional create-or-load flow. No public response fields changed.

## Before and after

- Before: preflight lookup allowed two concurrent processor captures.
- After: the first insert owns the key; collisions load and return the original result.
- Root cause addressed: durable uniqueness is now atomic rather than advisory application logic.

## Verification

- Concurrent integration test: 20 requests, one processor invocation, one payment row.
- Migration test: empty and populated schemas pass; duplicate preflight check documented.
- Focused service tests: 34 passed.
- Full suite: 812 passed.
- JaCoCo branch coverage for PaymentService: 61% to 88%.
- SpotBugs/PMD: no new findings.

## Remaining risk

Processor-side idempotency support could not be verified in the local environment; production
configuration should be checked before rollout.
```

This report distinguishes the code invariant, test evidence, analyzer outcome, and unavailable external evidence.
