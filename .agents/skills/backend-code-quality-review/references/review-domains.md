# Back-End Review Domains

## Contents

1. How to use this reference
2. Dead, unused, duplicate, and obsolete code
3. Complexity and unit size
4. Architecture and separation of concerns
5. Abstraction quality and over-engineering
6. Errors, retries, timeouts, and recovery
7. API contracts, validation, compatibility, and idempotency
8. Database access, transactions, queries, and migrations
9. Concurrency, jobs, and asynchronous processing
10. Authentication, authorization, input, and secrets
11. Logging, observability, and auditability
12. Dependencies, configuration, and environment handling
13. Tests and coverage
14. Performance and scalability
15. Production readiness

## 1. How to use this reference

Select domains based on the detected stack, entry points, changes, and risk. Do not mechanically produce one finding per section.

For every domain:

1. Identify the invariant or operational expectation.
2. Trace from entry point through side effects and failure handling.
3. Examine tests, configuration, framework defaults, and persistence constraints.
4. Quantify reach, cardinality, or interleaving when relevant.
5. Report only material, evidence-backed issues.

Prioritize paths involving authentication, authorization, money, tenancy, state transitions, migrations, deletion, external side effects, background jobs, and high-volume reads.

## 2. Dead, unused, duplicate, and obsolete code

### Investigate

- Unreachable files, functions, endpoints, event handlers, feature paths, migrations, flags, configuration, dependencies, and exports.
- Duplicate validation, authorization, retry, mapping, query, or state-transition logic.
- Deprecated endpoints/models retained without compatibility consumers.
- Old implementations shadowed by a new path.
- Code reachable only from tests or development fixtures.

### Validate before reporting

Check routing, DI/component scanning, reflection, serialization, service loaders, annotations/decorators, framework conventions, templates, migrations, scheduled jobs, message subscriptions, plugins, command registration, generated code, and external consumers.

An unused private helper with no dynamic path is easier to confirm than a public endpoint or schema. Public API compatibility may require apparently unused code.

### Material findings

Report when obsolete code creates security exposure, divergent business policy, dependency retention, migration ambiguity, operational confusion, or meaningful change risk. Avoid flooding the report with harmless unused constants unless cleanup was the requested scope.

## 3. Complexity and unit size

### Investigate

- High cyclomatic/cognitive complexity combined with low branch coverage.
- Large methods/classes/controllers/services/modules that own unrelated invariants.
- Deeply nested conditionals, mode flags, nullable state, and repeated exception branches.
- Orchestrators mixing validation, business policy, persistence, external calls, and response mapping.

### Validate before reporting

Read the whole unit and its callers. Declarative mappings, schema definitions, generated code, protocol switchboards, and explicit error-state dispatch can score high while remaining clear.

Complexity is actionable when it causes hidden invariants, contradictory states, partial updates, duplicated policy, weak testing, or unsafe changes. Do not prescribe arbitrary line limits.

### Preferred remediation

- Make invalid states unrepresentable.
- Move a policy to the layer that owns it.
- Separate pure decision logic from side effects.
- Use a transaction/state machine/strategy only when the domain already has real states or variants.
- Keep feature-specific logic local when extraction would create a large prop/parameter surface or indirection.

## 4. Architecture and separation of concerns

### Investigate

- Controllers/handlers containing domain policy or persistence details.
- Domain code importing web, ORM, queue, or vendor-specific types.
- Repositories deciding authorization or API semantics.
- Cross-feature imports bypassing public boundaries.
- Cyclic dependencies, service locators, and global registries.
- Business invariants enforced inconsistently in several entry points.
- Read/write models or tenant boundaries mixed without an explicit contract.

### Validate before reporting

Infer the repository's actual architecture from modules, build boundaries, tests, and existing conventions. Do not impose hexagonal, clean, or layered architecture by preference.

Report a boundary issue only when it increases coupling, bypasses an invariant, complicates testing, or creates inconsistent behavior.

## 5. Abstraction quality and over-engineering

### Investigate

- Pass-through services, repositories, helpers, adapters, DTOs, and wrappers.
- Generic base classes with one consumer.
- “Manager,” “processor,” or “utility” layers that hide ownership.
- Generic repositories that erase ORM/query capabilities or transaction semantics.
- Helpers that merely rename one framework call.
- Multiple nearly identical abstractions competing as canonical paths.
- Plugin/factory/configuration systems for fixed behavior.

### Decision rule

An abstraction earns its keep when it owns a coherent policy, lifecycle, side effect, protocol boundary, or multiple real variants. Testability alone does not justify wrapping every dependency when the framework already supports substitution.

Prefer direct, boring code when it reduces the concepts a maintainer must chase. Prefer an explicit domain abstraction when it eliminates repeated policy or makes an invariant enforceable.

## 6. Errors, retries, timeouts, and recovery

### Trace

- Exception/error origin, translation boundaries, and final client/job outcome.
- Whether failures trigger rollback, acknowledgment, retry, compensation, or dead-letter handling.
- Timeout, cancellation, retry count, backoff, jitter, and retryable-error classification.
- Resource cleanup for connections, bodies, files, locks, spans, and transactions.
- Circuit breakers, bulkheads, fallback behavior, and overload response.

### Material risks

- Swallowed exceptions or false-success responses.
- Catch-all handling that converts programmer/data errors into retries.
- Retrying non-idempotent operations.
- Nested retries that multiply attempts.
- Missing timeout on network/database calls.
- Infinite or synchronized retry storms.
- Failure paths that leave state partially applied.
- Fallbacks serving stale/incorrect data without visibility.

Verify framework defaults; many clients have no timeout unless configured, while some job systems already provide bounded retries.

## 7. API contracts, validation, compatibility, and idempotency

### Review

- Request validation at the runtime boundary, not types alone.
- Cross-field and domain invariants after shape validation.
- Authentication and authorization ordering.
- Status codes, error envelopes, headers, pagination, caching, and content types.
- Null/optional/default semantics and serialization compatibility.
- Versioning, deprecation, generated clients, OpenAPI/protobuf/schema alignment.
- Request size, rate limits, and resource ownership.
- Idempotency keys, replay behavior, and duplicate side effects.

### Idempotency evidence

Confirm whether uniqueness is atomic at the database or side-effect boundary. A preflight lookup without a constraint/lock is not sufficient under concurrency. Verify that retries return the original outcome and that key scope includes tenant/operation identity.

### Avoid false positives

An unusual status code may be an established contract. Public fields may remain for backward compatibility. Validate API specifications, tests, clients, and release policy before recommending removal or change.

## 8. Database access, transactions, queries, and migrations

### Query review

- Queries inside loops and lazy-loaded relationships.
- Fetch joins/preloads/batches and actual SQL count.
- Unbounded list endpoints, missing pagination, and large in-memory filtering/sorting.
- Missing/unused indexes aligned with filter, join, uniqueness, and ordering patterns.
- Full-entity loads when projections suffice.
- Chatty writes, per-row commits, and lock duration.
- Pool sizing, connection leaks, statement/result closure, and read/write routing.

Do not label a loop N+1 without confirming lazy access or query execution. Prefer SQL logs, ORM statistics, query plans, or an integration test with query counting.

### Transaction review

- Transaction begins before all related writes and ends after the invariant is durable.
- External network calls do not unnecessarily hold database locks.
- Exception classes trigger expected rollback.
- Nested/propagated transactions match intent.
- Isolation and locking prevent lost updates, duplicate creation, or write skew.
- Optimistic-lock conflicts are surfaced or retried safely.
- Outbox/inbox or compensation exists where database and broker/external side effects must coordinate.

### Migration review

- Forward and rollback/mitigation strategy.
- Backward compatibility during rolling deployment.
- Table scans, locks, data rewrites, and long transactions.
- Defaults, nullability, constraints, and index creation behavior on populated tables.
- Backfill resumability, batching, observability, and rerun safety.
- Application reads/writes compatible with old and new schema during rollout.

Never apply a migration during review. Static validation or ephemeral-database tests require explicit safe setup.

## 9. Concurrency, jobs, and asynchronous processing

### Review

- Mutable singleton/global/shared state.
- Check-then-act sequences and uniqueness assumptions.
- Lock scope/order, atomic operations, optimistic locking, and compare-and-set.
- Thread/goroutine/task pool bounds and queue backpressure.
- Cancellation, shutdown, lease/visibility timeout, and orphan work.
- Message acknowledgment timing and at-most/at-least-once semantics.
- Job idempotency, deduplication, ordering, retries, poison messages, and dead-letter queues.
- Scheduler overlap, leader election, and multi-instance deployment.

### Evidence requirement

Write the concrete interleaving or delivery sequence. Example: request A and B both read “missing,” both perform an external charge, then both insert. Without an interleaving, a race claim is usually too vague.

## 10. Authentication, authorization, input, and secrets

### Review

- Authentication token/session verification, expiry, revocation, key rotation, and algorithm restrictions.
- Authorization at object/tenant/action level, including indirect references and background jobs.
- Default-deny behavior and bypass paths.
- Password/API-key storage, comparison, one-time reveal, and rotation.
- Input reaching SQL, shell, templates, paths, redirects, URLs, deserializers, or code execution.
- File uploads, decompression, archive traversal, MIME/content validation, and storage permissions.
- SSRF and egress restrictions.
- Secret sources, logging, errors, config files, test fixtures, and generated artifacts.
- CORS, CSRF, cookies, headers, rate limits, and brute-force controls where relevant.

Use scanners only to locate code for inspection. Report a security issue only after tracing the trust boundary and source-to-sink path and inspecting framework escaping, validation, authorization, configuration, and tests. Parameterized queries may invalidate a syntactic alert; dynamic concatenation may remain risky despite partial validation.

Do not print or reproduce secrets in reports. Redact evidence while preserving the affected path and data class.

## 11. Logging, observability, and auditability

### Review

- Structured logs with stable event names and appropriate levels.
- Correlation/trace/request/job IDs propagated across async and external boundaries.
- Error logs retaining cause and relevant non-sensitive context.
- Metrics for latency, throughput, saturation, retries, failures, queue age, pool usage, and business invariants.
- Tracing spans around database/external calls where the stack supports it.
- Audit events for privileged/security/financial state changes, including actor, target, outcome, reason, and correlation.
- PII, credentials, tokens, documents, and request bodies excluded or redacted.
- Cardinality controls for metric labels and log fields.

Logging every branch is not observability. Report missing telemetry when it prevents detecting, diagnosing, or auditing a material failure.

## 12. Dependencies, configuration, and environment handling

### Review

- Unused, duplicate, unlisted, conflicting, or wrongly scoped dependencies.
- Pinned/locked/reproducible builds and runtime compatibility.
- Outdated or vulnerable dependency versions whose affected functionality is reached by a traced production code path.
- Framework/BOM/platform alignment.
- Environment-variable parsing, required/optional distinction, defaults, units, and validation.
- Production-safe defaults for debug, auth, TLS, timeouts, pools, retries, queues, migrations, and observability.
- Configuration drift across local/test/staging/production.
- Secrets separated from ordinary config and never committed.

Do not recommend “upgrade everything.” Identify the reason, compatibility risk, affected code, and verification plan. Networked vulnerability/version lookups require approval and authoritative sources.

## 13. Tests and coverage

### Review test quality

- Assertions verify behavior and side effects, not only HTTP 200 or “does not throw.”
- Failure, rollback, authorization, validation, retry, timeout, and idempotency paths.
- Database constraints and transaction behavior tested with a real compatible database where necessary.
- Contract tests cover schemas/status codes/compatibility.
- Concurrent tests exercise races deterministically where possible.
- Job tests cover duplicate delivery, retry exhaustion, acknowledgment, and dead-letter behavior.
- Fixtures/builders remain realistic and avoid impossible states.
- Mocks do not bypass the behavior under test.
- Tests are isolated, deterministic, and do not depend on order, wall clock, random ports, or shared production-like state.

### Coverage interpretation

- Use branch coverage for decision-heavy code.
- Use mutation testing when already configured to expose weak assertions.
- Map uncovered code to risk; do not demand uniform percentages.
- Generated DTOs, framework glue, or trivial mappings may warrant less testing than money movement or authorization.
- A 100% covered method can still have wrong assertions or missing concurrency conditions.

## 14. Performance and scalability

### Review

- Algorithmic growth and data cardinality.
- Database query count, plans, indexes, row width, and result bounds.
- Serialization payload size and streaming behavior.
- Memory retention, caches, eviction, stampedes, and key cardinality.
- Thread/task/goroutine pools, event-loop blocking, backpressure, and connection pools.
- External-call fan-out, batching, parallelism bounds, retries, and timeouts.
- Locks and transactions on high-contention paths.
- Compression/encryption/hashing cost and abuse limits.

Require a plausible workload and evidence. A micro-optimization without measured or structural impact is not a finding. An obviously unbounded operation on attacker/user-controlled input can be reported without a benchmark if the growth path is clear.

## 15. Production readiness

Assess whether the service can be safely deployed and operated:

- Deterministic startup and fail-fast configuration validation.
- Readiness/liveness semantics that reflect dependencies correctly.
- Graceful shutdown, request draining, job lease handling, and resource closure.
- Migration ordering and rolling-deploy compatibility.
- Safe defaults, least privilege, and secret injection.
- Resource limits, pool sizing, timeouts, backpressure, and overload behavior.
- Health metrics, alerts, dashboards, runbooks, audit trails, and correlation.
- Rollback/feature-disable/repair path for risky changes.
- Backup/restore or reconciliation for stateful critical flows.

Do not claim “production ready” solely from passing tests. State residual risks and unavailable operational evidence.
