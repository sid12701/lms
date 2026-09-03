---
title: "Final Report: Spring Boot Backend Plan Audit and Implementation"
description: "Independent audit of the backend quality plan against the current codebase, with bounded behavior-preserving implementation and fresh verification evidence."
trigger_phrases:
  - "backend audit final report"
  - "Spring Boot plan audit"
importance_tier: "important"
contextType: "implementation"
_memory:
  continuity:
    packet_pointer: "specs/002-spring-boot-backend-audit"
    last_updated_at: "2026-08-02T08:40:00Z"
    last_updated_by: "codex"
    recent_action: "Re-audited the plan with three Luna xhigh workstreams, implemented three bounded improvements, and reran the full offline suite"
    next_safe_action: "Review the packet-owned diff and open separate approval packets for any deferred functional or architecture finding"
    blockers: ["graphify is unavailable", "Docker/Testcontainers is unavailable in the current sandbox"]
    key_files: ["backend/src/main/java", "backend/src/test/java", "backend/pom.xml"]
    session_dedup:
      fingerprint: "sha256:0000000000000000000000000000000000000000000000000000000000000000"
      session_id: "codex-2026-08-02-backend-plan-audit"
      parent_session_id: "codex-2026-08-01-backend-audit"
    completion_pct: 100
    open_questions: ["Which deferred functional or architecture findings should receive separate approval packets?"]
    answered_questions: ["Three additional changes met the behavior-preservation bar"]
---
# Final Report: Spring Boot Backend Plan Audit and Implementation

## 1. Executive Summary

The original plan was directionally sound on scope control, framework-risk awareness, and the requirement to keep functional defects separate. It was not complete enough to close the task: its successful-build claim was historical rather than fresh, it did not audit every item in the requested verdict format, it did not record the required delegated workstreams, and it missed two concrete test-quality failures plus one misleading Spring transaction annotation pattern.

This re-audit used three independent GPT-5.6 Luna `xhigh` workstreams for architecture/security, services/persistence, and build/tests. Their claims were then checked against the source and fresh command results by the lead auditor. Two Luna implementation turns made disjoint, bounded edits, and the lead auditor reviewed the combined diff and ran the full offline suite.

Five total behavior-preserving improvements are accepted in the packet:

- Two earlier mechanical cleanups: private compile-time error-code constants in `GlobalExceptionHandler` and equivalent JUnit boolean predicates in `AuditExplorerControllerTest`.
- Removal of two ineffective package-private `@Transactional` annotations in `LoanDocumentService`; all callers are same-instance self-invocations, so Spring proxy advice never applied.
- Replacement of incomplete webhook-test teardown with the existing FK-safe `IntegrationTestDatabaseCleaner`.
- An explicit `-Dr2.probe.enabled=true` gate for the live R2 PUT/DELETE probe, so ordinary tests cannot perform external I/O merely because `.env` credentials exist.

No business rule, API contract, validation rule, persistence semantic, security rule, tenant boundary, transaction boundary, retry/timeout/scheduling behavior, error response, runtime configuration, or integration contract was intentionally changed.

**Final verdict: Ready for pull-request review.** This verdict applies to the bounded audit-owned changes, not to deployment readiness of the entire dirty worktree. Fresh PostgreSQL/MinIO/Testcontainers coverage remains unavailable because Docker cannot be accessed in this sandbox.

## 2. Plan Audit

| Plan Item | Audit Decision | Reason | Implemented |
|---|---|---|---|
| Establish build, startup, test, and tooling baseline | Valid but needs refinement | The stored `787/0/0/1` result is from August 1. Fresh pre-change execution produced `725` tests, `17` errors, and `85` skips; the errors exposed real test-quality gaps. | Yes, evidence corrected |
| Use graphify as the repository map | Blocked by environment | `graphify-out/` is absent and `graphify` is not installed. `graphify update .` returns command-not-found. | No |
| Repository imports service-owned query contracts (F-01) | Valid, architecture-changing | The imports exist, but moving contracts or adding ports changes package ownership and dependency rules. | No; deferred |
| Near-giant production units (F-02) | Valid concern, not a defect | Files are large but below the 1,000-line production threshold; safe seams require characterization tests. | No; deferred |
| Duplicated LSP/admin idempotency workflows (F-03) | Valid but too risky | The branches differ in tenant scope, repositories, leases, recovery, and transaction handling. A generic extraction could change protected behavior. | No; deferred |
| Add static-analysis and coverage gates (F-04) | Valid, build-policy change | Maven/CI has no Checkstyle, SpotBugs, PMD, JaCoCo, or equivalent gate. Adding one changes dependencies and CI policy. | No; separate tooling proposal |
| Split giant test classes (F-05) | Valid concern, requires characterization | Splitting can change Spring contexts, fixture cleanup, tenant setup, and metric isolation. | No; deferred |
| Possible detached lazy association (S-01) | Requires characterization tests first | `BorrowerActiveLoanChecker` returns `LoanAccount` objects across an admin transaction and the rule engine dereferences lazy `loanApplication`. The exact cross-LSP branch is not freshly proven. | No; functional issue register |
| Consolidate Aadhaar masking lists (S-02) | Unsupported by current intake evidence | `incomingAadhar` is not part of the current intake payload path; existing helpers also have different edge-case semantics. | No; rejected |
| Normalize raw exception messages (S-03) | Functionality-changing | An allowlist would change HTTP error bodies and security behavior. No specific secret disclosure was proven. | No; deferred |
| Replace `String.intern()` repayment locking (S-04) | Too risky and workload-dependent | The database remains the correctness control; replacing JVM locking changes concurrency behavior and needs load/heap evidence. | No; deferred |
| Package-by-feature migration | Architecture-changing | Package moves can affect scanning, proxies, reflection, tests, and dependency direction. | No; report-only proposal retained |
| Error-code constants and boolean assertion cleanup | Already resolved and valid | Values, control flow, and test meaning are unchanged. | Yes; accepted from earlier pass |
| Ineffective self-invoked transaction annotations (M-01) | Valid | The two package-private helpers have no external callers; removing annotations describes actual Spring behavior without changing it. | Yes |
| Webhook test teardown (M-02) | Valid | Manual deletion omitted `loan_account`, causing order-dependent FK errors in the full H2 suite. The canonical cleaner already owns FK-safe order. | Yes |
| Live R2 probe auto-enables from `.env` (M-03) | Valid | Ordinary `mvn test` attempted remote PUT/DELETE when credentials existed. Explicit opt-in is the established test convention. | Yes |
| Web-package component injects repository (M-04) | Valid, architecture-changing | `LoanApplicationCreateIdempotencyReconstructor` is reachable and bypasses controller-only architecture guards, but moving it changes ownership and DTO boundaries. | No; deferred |
| PostgreSQL advisory-lock lifecycle (M-05) | Confirmed functional defect | Acquire and release use separate pooled `JdbcTemplate` calls, while session advisory locks require the same PostgreSQL connection. | No; out of code-quality implementation scope |
| Webhook claim fencing (M-06) | Probable functional/reliability issue | A fixed five-minute lease and status-only outcome checks allow stale completion if delivery outlives a reclaimed lease. | No; requires behavior design |
| Report row locks across external I/O (M-07) | Probable transaction/reliability issue | One transaction spans `FOR UPDATE SKIP LOCKED`, report generation, object storage, and email. | No; transaction redesign required |
| Production bootstrap/mock/default-profile risks (M-08) | Conditional deployment/configuration risks | The paths exist, but effective impact depends on deployment profile and adapter configuration. Changing them alters configuration/security/integration behavior. | No; separate hardening review |

## 3. Corrected Implementation Plan

### Required changes

| ID | Area | Validated Problem | Approved Change | Files/Packages | Dependencies | Risk | Parallelizable | Behaviour Guardrail |
|---|---|---|---|---|---|---|---|---|
| CQP-01 | Spring transaction clarity | Two package-private helpers carry proxy annotations that cannot apply to same-instance calls | Remove only those annotations | `LoanDocumentService.java` | Existing public callers and upload tests | Low | Yes | Do not add/move transaction boundaries or change visibility/control flow |
| CQP-02 | Test isolation | Webhook teardown omits dependent loan rows and fails after other H2 tests | Reuse canonical FK-safe cleaner | `WebhookOutboxSoftFourxxAndRedriveTest.java` | `IntegrationTestDatabaseCleaner` | Low | Yes | Preserve fixtures, assertions, webhook logic, and database schema |
| CQP-03 | Test external-I/O safety | Live R2 probe runs when repository `.env` credentials exist | Require explicit system-property opt-in in addition to the environment guard | `R2RegionAndStoreProbeTest.java` | JUnit condition annotations | Low | Yes | Preserve probe body and live behavior when explicitly enabled |

### Recommended safe improvements

No additional production refactor cleared the equivalence bar. The two earlier mechanical cleanups remain accepted, but all newly suggested structural changes were rejected or deferred.

### Rejected or deferred items

Repository DTO relocation, package migration, controller/test splitting, idempotency abstraction, exception normalization, repayment-lock replacement, advisory-lock correction, webhook fencing, report transaction splitting, bootstrap removal, mock-adapter profile changes, default-profile changes, and new static-analysis dependencies all require separate approval or characterization.

## 4. Subagent Execution

The requested “5.6 Luna at Extra High” configuration was fulfilled using Codex tasks with model `gpt-5.6-luna` and `xhigh` reasoning. Worktree creation initially failed because the snapshot copier treated the untracked `formance/` directory as a file; read-only audit tasks were therefore relaunched directly in the saved project. Implementation tasks edited disjoint files sequentially from their completed audit tasks.

| Subagent | Assigned Area | Parallel/Sequential | Files Owned | Result | Revisions |
|---|---|---|---|---|---|
| Luna architecture/security task | Spring wiring, controllers, tenant isolation, security, package boundaries | Parallel audit | Read-only | Completed; plan items classified and missed architecture/configuration risks traced | Lead rejected behavior-changing proposals |
| Luna services/persistence task | Transactions, JPA, locks, idempotency, workers, integrations | Parallel audit | Read-only | Completed; advisory-lock defect and probable webhook/report risks traced | Lead kept all functional fixes deferred |
| Luna build/tests task | Fresh baseline, startup, Maven tests, CI/tooling, test isolation | Parallel audit | Read-only | Completed; historical baseline corrected and two safe test fixes identified | Lead independently reran suite |
| Luna service implementation turn | Ineffective transaction annotations | Sequential implementation | `LoanDocumentService.java` | Accepted; exactly two annotations removed | No revision required |
| Luna test implementation turn | Webhook cleanup and R2 probe opt-in | Sequential implementation | Two named test classes | Accepted; focused tests passed | No revision required |

No task created a commit. This preserved the user’s heavily dirty worktree and allowed the lead auditor to review an isolated path-restricted diff.

## 5. Implementation Results

### CQP-01 — Remove misleading transaction annotations

- **Original problem:** `persistStoredDocumentForLsp` and `persistStoredDocumentsForLsp` were package-private and annotated `@Transactional`, but all calls originate inside the same `LoanDocumentService` instance.
- **Implemented improvement:** removed only the two annotations.
- **Quality benefit:** the code no longer advertises transaction advice that Spring cannot apply through self-invocation.
- **Behavior evidence:** caller search found only `submitStoredDocumentForLsp` → singular helper, `submitStoredDocumentsForLsp` → batch helper, and batch helper → singular helper. No visibility, control-flow, persistence, storage, or proxy boundary changed.
- **Verification:** compile passed; `LspLoanDocumentUploadIdempotencyIntegrationTest` passed 3/3.

### CQP-02 — Make webhook tests order-independent

- **Original problem:** class-local teardown deleted webhook and LSP rows but not `loan_account`, so shared H2 state could violate the LSP foreign key.
- **Implemented improvement:** injected and called the existing `IntegrationTestDatabaseCleaner`; removed only the now-unused delivery-attempt repository field/import.
- **Quality benefit:** one canonical FK-safe teardown order replaces incomplete duplicated cleanup.
- **Behavior evidence:** production code and every webhook assertion/fixture remain unchanged.
- **Verification:** the class passed 16/16 alone and within the fresh full suite.

### CQP-03 — Gate the live R2 probe explicitly

- **Original problem:** `.env` credential discovery was sufficient to enable an external PUT/DELETE during ordinary tests.
- **Implemented improvement:** added `@EnabledIfSystemProperty(named = "r2.probe.enabled", matches = "true")` while preserving the existing environment-presence condition.
- **Quality benefit:** default tests are deterministic and offline-safe; deliberate live probing remains available.
- **Behavior evidence:** the probe endpoint, credentials, key creation, PUT, and DELETE are unchanged when explicitly enabled.
- **Verification:** the R2 class ran 2 tests with 1 pass and 1 intentional skip by default. The external probe was not executed.

### Combined diff

The newly accepted diff is three files, 7 insertions, and 11 deletions. A CRLF-aware `git diff --check` passes. No dependency, migration, runtime configuration, API, or generated file was changed.

## 6. Rejected or Deferred Findings

### Confirmed or probable functional issues — discussion only

| ID | Evidence | Disposition |
|---|---|---|
| D-01 Advisory-lock connection affinity | `PostgresAdvisoryLockSupport.tryAcquire` and `release` issue separate pooled JDBC calls; workers hold the logical lock across other work | Confirmed functional concurrency defect. Add a two-connection PostgreSQL characterization test, then fix in a separate packet. |
| D-02 Webhook lease fencing | Five-minute lease, reclaimable `IN_FLIGHT` rows, and status-only outcome recording | Probable at-least-once delivery race. Define claim token/fencing semantics first. |
| D-03 Report transaction spans I/O | `processPendingRequests` is transactional and holds claimed rows while storing and notifying | Probable contention/rollback side-effect risk. Requires transaction and idempotency redesign. |
| D-04 Detached lazy loan association | Cross-LSP admin read returns lazy-linked `LoanAccount`; rule engine later dereferences `loanApplication` | Add the exact PostgreSQL cross-LSP HTTP test before changing fetch behavior. |
| D-05 Raw exception response messages | Several handlers pass exception messages into `ApiError` | Build an error-contract allowlist and tests before changing HTTP behavior. |
| D-06 Interned repayment keys | `LoanRepaymentCommandService` synchronizes on validated UUID-v4 key strings | Require load/heap evidence and a separately approved concurrency design. |
| D-07 Real-adapter terminal outcomes | Loan-state application currently depends on the mock resolution path | Add a real-adapter contract test before enabling a non-mock provider. |

### Conditional security/configuration risks — discussion only

- The bootstrap fallback and startup sync are active application paths; deployment impact depends on effective non-local credentials and policy.
- `MockLoanDisbursementAdapter` is an unprofiled Spring service, and mock auto-resolution defaults are enabled in base configuration; a real deployment must prove adapter selection explicitly.
- `spring.profiles.default` is `local`; deployments should supply and verify an explicit non-local profile, but changing the default is configuration behavior outside this pass.
- Rate-limit coverage is not universal across all administrative mutations; changing coverage is a security/HTTP behavior decision.

### Rejected observations

- The Aadhaar-list difference does not prove a current intake leak; `incomingAadhar` belongs to another context and helper semantics differ.
- Current API-client token validation checks LSP/client status and token versions; older revocation findings are stale for this tree.
- No production code was classified dead merely because static references were absent.
- No unambiguous production N+1 issue was proven from the observed Hibernate warning alone.

## 7. Validation Evidence

| Stage | Command/Check | Result |
|---|---|---|
| Historical stored evidence | August 1 Surefire reports | `787` tests, `0` failures, `0` errors, `1` skip; historical only |
| Fresh pre-change package | `./mvnw -o -DskipTests verify` | PASS |
| Fresh startup | `LmsApplicationTests` with cached Byte Buddy agent | PASS; Spring context started |
| Fresh pre-change full tests | Offline Maven test with cached agent | `725` tests, `0` failures, `17` errors, `85` skips |
| Pre-change error isolation | Webhook class and R2 probe analysis | 16 order-dependent H2 setup errors; 1 R2 `UnknownHostException` |
| Focused annotation regression | `LspLoanDocumentUploadIdempotencyIntegrationTest` | PASS: 3/3 |
| Focused webhook regression | `WebhookOutboxSoftFourxxAndRedriveTest` | PASS: 16/16 |
| Focused R2 regression | `R2RegionAndStoreProbeTest` without opt-in | PASS: 1 executed, 1 intentionally skipped |
| Fresh post-change full tests | `./mvnw -o -q -DargLine=-javaagent:... test` | PASS: `725` tests, `0` failures, `0` errors, `86` skips |
| Docker/Testcontainers | Docker socket probe | BLOCKED: socket access denied; 84 container-backed tests skipped |
| OpenAPI export | Default test run | 1 intentional skip because `openapi.export=true` was not supplied |
| Static analysis/coverage | Maven/CI inspection | No configured quality gate or coverage report |
| Graph update | `graphify update .` | BLOCKED: executable unavailable |
| Backend detector | `detect_backend_stack.py` | BLOCKED by detector `TypeError: 'str' object is not callable` |
| Combined diff | Lead review plus CRLF-aware whitespace check | PASS |
| Strict spec validation | Installed spec-kit validator | PARTIAL/FAILED: packet structure, placeholders, sections, priorities, evidence, frontmatter, links, and ToC policy pass; 7 shared-toolchain errors and 4 warnings remain because installed validator modules/runtime are incomplete and legacy custom anchors/metadata are advisory |

The default Mockito self-attach failure on this macOS JVM was bypassed only on the command line with the already-cached Byte Buddy agent. No project configuration was changed. The full-suite pass proves the available H2/unit/MockMvc paths; it does not substitute for the skipped PostgreSQL, MinIO, Flyway-container, or concurrency tests.

## 8. Final Assessment and Verdict

| Dimension | Assessment |
|---|---|
| Readability | Improved: misleading transaction metadata and duplicated teardown were removed |
| Maintainability | Improved locally; major package and giant-file concerns remain intentionally deferred |
| Structure/responsibility | Unchanged at runtime; repository/service and web/repository boundary drift remains documented |
| Duplication/abstraction | One duplicated test-cleanup sequence was replaced by the canonical cleaner; risky idempotency duplication was not abstracted |
| Testability | Improved: full offline suite is deterministic and external R2 I/O is opt-in |
| Java/Spring alignment | Improved: annotations now match actual proxy behavior and JUnit conditions express live-test intent |
| Workaround risk | Low: no warning suppression, hard-coded workaround, or production branch was added |
| Over-engineering risk | Low: three small edits reuse existing mechanisms |
| Regression risk | Low for audit-owned changes; residual infrastructure risk remains because Docker-backed tests were skipped |

**Final verdict: Ready for pull-request review.**

The pull-request description should explicitly limit its claim to these bounded code-quality/test-quality changes. It must not imply that deferred concurrency, security, integration, or transaction issues are fixed, or that deployment readiness has been proven.

## Appendix A: Current Maintainability Findings

- Repository adapters import service-owned query/support types. Preserve this as an architecture finding until a separately approved feature-package migration.
- `IdempotencyExecutionCoordinator` duplicates LSP/admin workflows but has tenant, lease, repository, and recovery differences that make generic extraction unsafe.
- `LspLoanApplicationApiController`, `SyntheticPortfolioSeedService`, `GlobalExceptionHandler`, `LoanApplicationOpsController`, reporting, repayment scheduling, and several integration-test classes are near-giant change surfaces. Split only at characterized behavior seams.
- `LoanApplicationCreateIdempotencyReconstructor` is a web-package component that directly injects a repository; current controller-only architecture tests do not cover it.
- Maven and backend CI lack an agreed static-analysis and coverage gate.

## Appendix B: Package-Structure Proposal

The prior report-only recommendation remains valid: evolve gradually toward a hybrid feature-oriented modular monolith with explicit shared/platform/security/tenant areas and feature-local API, application, domain, and persistence packages. Do not perform a mass move.

Migration guardrails remain:

1. Add dependency tests and a complete consumer inventory first.
2. Move one read-only feature before command workflows.
3. Preserve controller mappings, serialization, transaction ownership, tenant scope, and provider boundaries.
4. Run focused and full tests after each move.
5. Keep package movement separate from behavior changes, dependency upgrades, and migrations.
6. Roll back one feature move at a time.
