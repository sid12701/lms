# Bhawana LMS — Production Readiness Report (2026-07-12)

**Assessment date:** 12 July 2026  
**Audience:** Management, engineering leadership, product, security, compliance, operations, QA, and prospective integration owners  
**System state assessed:** Current dirty working tree, including the large uncommitted July remediation set  
**Deployment context:** Management review and synthetic UAT; disbursement is mocked; the application is not approved for real-money rollout  
**Decision supported:** Whether the present system can progress to management demo, limited partner pilot, or real-money production, and what must be remediated at each gate  

---

## 1. Technical summary — strong foundation, no-go for real-money production

The LMS is a credible, substantially remediated fintech modular monolith with several genuinely strong controls: fixed-scale money types, database constraints, tenant RLS, cross-tenant integration tests, database-backed idempotency, installment locking/versioning, webhook/report `SKIP LOCKED` claims, strict partner JSON, structured API errors, product-term versioning, pagination, document signature validation, audit streams, and a large passing automated-test base.

It is nevertheless **not production-ready**. The present decision is:

| Deployment mode | Verdict | Required gate |
|---|---|---|
| Internal management review / synthetic UAT | **Conditional go** | Keep mock rails and synthetic data; repair the frontend build/lint baseline; document the current Home/Audit and login-environment limitations. |
| Limited partner pilot with no real disbursement | **No-go today** | Close silent intake stalls, partner servicing/reconciliation gaps, PAN policy, runtime defects, environment drift, and reproducible E2E execution. |
| Real-money production | **No-go** | Durable money-intent workflow, maker-checker, real receipt/allocation/reversal ledger, encrypted PII, reconciliation, capacity proof, partition/retention, DR, operational monitoring, and independent security/financial-control assurance. |

The critical conclusion is not that the system needs a rewrite. It does not. The fastest safe route is to retain the existing modular-monolith, relational, RLS, idempotency, outbox, and fixed-scale money foundations while replacing the unsafe real-rail transaction boundary and completing the missing operational controls.

### Production-readiness scorecard

| Dimension | Score | Assessment |
|---|---:|---|
| Fintech correctness and money safety | **7/20** | Good primitive controls, but provider intent, real receipt allocation, reversals, and maker-checker are launch blockers. |
| Security and compliance engineering | **9/20** | Strong tenant isolation and several audit controls; plaintext/duplicated PII, PAN exposure, token storage, malware scanning, and retention remain below fintech bar. |
| Database and data integrity | **13/20** | Relational core and constraints are strong; beneficiary snapshot (**S5 deferred 2026-07-15**), partitioning, retention, encryption, and disbursement reference uniqueness remain open or deferred. |
| API and partner operability | **11/20** | Strict contracts and error envelopes are strong; accepted-but-stuck intake, partner reconciliation, self-service, and servicing visibility remain incomplete. |
| UI/UX technical quality | **14/20** | Good product UI foundation; runtime failures, financial confirmation, action gating, release-gate failures, and incomplete conformance evidence prevent release. |
| Scalability and reliability | **8/20** | Multiple remediations landed, but current capacity is unproven and historical load evidence failed far below target. |
| Observability and operations | **9/20** | Correlation IDs, health and domain counters exist; end-to-end traces, SLOs, reconciliation alarms, queue-age dashboards, DR evidence, and proven alert delivery do not. |
| Code quality and maintainability | **12/20** | No circular/import-boundary failures and average maintainability is high; complexity, duplication, dead surface, manual contract mapping, and oversized boundary files remain. |
| Testing and delivery controls | **12/20** | Backend and unit suites are broad and green; frontend release gates fail, browser/API E2E are not reproducible in the current environment, and test orchestration is fragile. |
| **Overall** | **10.6/20** | **Capable prototype / pre-production platform; not a production system of record.** |

### Highest-risk release blockers

| Rank | Canonical ID | Severity | Category | Defect | Business consequence |
|---:|---|---|---|---|---|
| 1 | MNY-01 / F-S9 / F-Q5 / F-MNY-01 | ~~P0~~ **Closed (implemented 2026-07-13)** | Money flow | ~~Provider call can happen before durable intent commits.~~ Fixed by Spec **S3** (§19.3, implementation record §19.6 / `docs/implementation-log.md`): `disbursement_intent` committed before provider; bank call outside DB tx; `SKIP LOCKED` worker claims; unique `tran_ref_no`. Residual: crash-matrix tests, intent metrics, beneficiary snapshot (S5). | ~~Crash-after-provider-success can cause an unobserved payout and a duplicate retry.~~ Duplicate-initiation risk materially reduced when intent workflow is on. |
| 2 | MNY-02 / F-S10 / F-MNY-08 / LSP-F9 | P0 | Servicing ledger | Repayment accepts only one exact installment; no partial, lump-sum, suspense, bounce, or reversal ledger. | Actual borrower receipts cannot be represented faithfully. |
| 3 | SEC-01 / F-S1 / F-01 | P0 (partial) | Security / privacy | PII is plaintext and copied into JSON/audit/alert payloads; PAN is shown by default. ~~Access token in `localStorage`.~~ Token-at-rest closed by Spec **S11** (2026-07-15). | Large breach blast radius and weak purpose-bound PII access (PII/PAN remain). |
| 4 | CTRL-01 | P0 at real rails | Financial control | One SYSTEM_ADMIN can initiate disbursement; no maker-checker or amount limit; dialog omits amount/net fee. | Fraud or operator error can move uncapped money without independent approval. |
| 5 | SCALE-01 | P0 | Capacity | Current code has no post-remediation multi-instance peak/soak proof against the 150K/day peak target. | Production may fail under committed volume despite passing functional tests. |
| 6 | DATA-01 / F-S2 | ~~P1~~ **Deferred (2026-07-15)** | Data integrity | ~~Approved beneficiary details are not frozen; current global borrower bank data is used at payout.~~ Spec **S5** remains specified but is **not scheduled**: owner accepted residual risk under PAN dedupe + single active loan; see `docs/deferred-implementation.md`. | Residual: post-approval bank edit can still redirect an in-flight payout until S5 ships (before real-money rails). |
| 7 | API-01 / LSP-F2 | ~~P1~~ **Closed materially (validated 2026-07-12 evening)** | Partner API | ~~Create accepts payloads that progression gates later reject silently.~~ Runtime-verified: incomplete create now returns 422 `BORROWER_REQUIRED_FIELDS_MISSING` with per-field violations; see §19.1. Residual: DTO/OpenAPI contract documentation. | Silent-stall risk removed for the LSP path. |
| 8 | IDEM-01 | P1 | Reliability | Idempotency claims can remain `PENDING` after crash with no recovery lease/sweep. | A committed action can become permanently unreplayable/ambiguous. |
| 9 | CI-01 | ~~P1~~ **Closed (implemented 2026-07-13)** | Delivery | ~~Lint, format, and production build gates fail.~~ Fixed by Spec **S1** (§19.3, implementation record §19.6): lint/build/format green; `scripts/verify.mjs` reports all gates; `754` Vitest tests pass. Residual: legacy `act(...)` warnings in axe specs. Playwright harness remediated by Specs **S9/S10** (2026-07-15). | Clean frontend release artifact can be produced from the current tree. |
| 10 | OPS-01 | ~~P1~~ **Closed (validated 2026-07-12 evening)** | Operations | ~~Home and Audit Log failed in the last authenticated walkthrough.~~ Resolved by commit `f825e81` (Instant→Timestamp JDBC bind); Home and Audit verified working via API and real browser session; see §19.1. Residual: diagnostic error UI (UX-03) and regression guard. | Dashboard/compliance visibility restored. |
| 11 | ENV-01 | ~~P1~~ **Closed residuals (S10, 2026-07-15)** | Environment | ~~Bootstrap identity, password configuration, and running DB state disagree.~~ Login validated 2026-07-12; S10 removed stray `APP_SECURITY_BOOTSTRAP_LOGIN_PASSWORD`, added `bootstrap-sync` + Home/Audit canaries. Residual: no dedicated ephemeral UAT identity product. | Reproducible UAT login + heal path exist. |
| 13 | NEW-01 / TEST-BE-01↑ | ~~P0~~ **Closed (implemented 2026-07-13)** | Delivery / data safety | ~~**Reproduced 2026-07-12:** plain `mvn test` boots the `local` profile against the `.env` Supabase URL and `IntegrationTestDatabaseCleaner` deleted every `app_user`, borrower, loan, product and LSP row in the live database.~~ Fixed by Spec **S2** (§19.3, implementation record §19.6): default upload regression runs on Testcontainers; cleaner refuses non-ephemeral JDBC URLs; external-DB test is opt-in only. | Routine `mvn test` no longer writes to a shared/live database. |
| 12 | DATA-02 / F-Q8 | P1 | Data lifecycle | High-growth audit, webhook, report, token, and access streams lack complete retention/partition/archive automation. | Unbounded storage, degraded queries, and non-compliant retention. |

---

## 2. Scope, evidence, definitions, and confidence

### Evidence used

1. Current validation campaign: `outputs/validation-2026-07-12/VALIDATION_REPORT.md` and its raw logs/JSON.
2. Current production audit: `outputs/current-production-audit-2026-07-10/PRODUCTION-READINESS-AUDIT.md`.
3. LSP audit: `outputs/lsp-audit/2026-07-08/LSP-POV-AUDIT.md`.
4. UX audits and remediation plan under `outputs/ux-audit/`.
5. Database audit and remediation specification: `docs/database-audit-report-2026-07-03.md`, `docs/database-sql-review.md`, and `docs/db-audit-remediation-spec-2026-07-05.md`.
6. Scalability/performance evidence: `docs/scalability-audit-report-2026-06-14.md` and `docs/perf/PERFORMANCE_REPORT*.md`.
7. E2E readiness review: `docs/e2e-testing-readiness-review.md`.
8. Current graph index: `graphify-out/GRAPH_REPORT.md`, used as an index only because it is stale and structurally noisy.
9. Current Fallow JSON: 492 files, 3,399 functions, cleanup/duplication/complexity/security results.
10. Direct source checks of the cited backend/frontend files and current test configuration.

### Status vocabulary

| Status | Meaning |
|---|---|
| **Open — confirmed** | Current source, runtime, or 2026-07-12 automated evidence demonstrates the defect. |
| **Partial** | A material control landed but the root risk or production proof remains incomplete. |
| **Historical — revalidate** | Recorded by an older audit; later code changed materially and no current experiment proves whether the original defect still reproduces. It remains in this report to preserve history, not as a claim of current failure. |
| **Closed** | Later source/tests explicitly demonstrate remediation. Kept in the historical register so no chat finding disappears. |
| **Decision required** | Behavior is technically intentional or ambiguous but requires product, compliance, risk, or operating-model approval. |
| **Infrastructure blocked** | Test could not create an application verdict because the execution environment—not application behavior—stopped it. |

### Severity vocabulary

- **P0 — Blocking:** potential duplicate/lost money, material privacy exposure, tenant-wide outage, or inability to produce a release.
- **P1 — Major:** significant correctness, partner reliability, security, audit, operations, or scale risk before pilot/production.
- **P2 — Moderate:** meaningful maintainability, performance, operability, or UX defect with a workaround.
- **P3 — Minor/polish:** low-risk cleanup, consistency, or future-proofing.

### Important limitations

- The current backend and unit-test results are strong descriptive evidence, not proof of real-bank correctness, security certification, WCAG conformance, or production capacity.
- Browser E2E could not launch Chromium because the environment returned `spawn EPERM`; escalation was rejected by the execution environment's usage limit. The resulting 46 failed/9 not-run counts are **not product failures**.
- The supplemental Python API harness could not import `requests`; an isolated dependency install was rejected by the same usage limit. No edge-harness verdict is claimed.
- The June performance findings are preserved but marked historical where July remediations invalidate the old measurement. A new multi-instance test is required.
- The assessed worktree is dirty and contains extensive user changes. Results describe that exact state, not a tagged release commit.

---

## 3. Fintech correctness and money-flow safety

### MNY-01 — durable disbursement intent is missing before the provider side effect

**Status:** **Closed (implemented 2026-07-13)** — Spec S3; see §19.6 and `docs/implementation-log.md`  
**Severity:** P0 before any real rail (remediation landed; residual acceptance tests/metrics remain)  
**Affected flow:** SYSTEM_ADMIN disbursement → `LoanDisbursementCommandService` → `DisbursementIntentWorkflowService` (when enabled) → worker → provider adapter → request log

**Evidence (at assessment):** The service transaction began before the provider call; the adapter executed before account and request-log persistence. Provider/status polling also occurred within a transaction. `tran_ref_no` and `provider_request_id` lacked a reliable unique intent constraint, and historical worker behavior used broad scans rather than a complete leased-intent state machine.

**Remediation delivered:** `disbursement_intent` table (`V111`); Tx-A intent creation with unique `tran_ref_no`; worker claims via `FOR UPDATE SKIP LOCKED` + lease; provider calls outside DB transactions; Tx-B outcome persistence. Feature flag `app.disbursement.intent-workflow.enabled` (default on; legacy path when false).

**Residual:** Full crash-after-acceptance kill-point matrix; intent age/reconciliation dashboards (OBS-01D); beneficiary freeze at approval (DATA-01 / S5 — **deferred 2026-07-15**, see `docs/deferred-implementation.md`).

### MNY-02 — the repayment model cannot represent real receipts

**Status:** Open — confirmed. **Decision landed 2026-07-13:** the LMS **is** the system of record (D1); waterfall = charges → interest → principal, oldest first (D1a); overpayment policy deferred, surplus parks in suspense (D1b pending). Execution-ready spec: **S13 (§19.5)**. **Deferred from implementation 2026-07-15** — see `docs/deferred-implementation.md`.  
**Severity:** P0 for system-of-record use  
**Aliases:** LSP-F9, F-S10, F-MNY-08, F-MNY-10

**Evidence:** The posting API targets one installment and rejects any amount not exactly equal to its outstanding balance. The public path cannot reach the partially-paid domain state; payment statuses lack complete bounce/reversal semantics; no immutable compensating-entry workflow exists.

**Deferral rationale (owner, 2026-07-15):** Synthetic UAT / management review continues with exact full-EMI posting only; receipt ledger not scheduled for the current pass. Residual SoR gap accepted until resume.

**Impact:** Partial collections, overpayments, bunched EMIs, advance payments, round-offs, suspense, NACH bounce, reversal, chargeback, and bank settlement adjustments cannot be represented faithfully. Partners would need a shadow ledger, making the LMS unfit as the authoritative book.

**Recommendation (when resumed):** Separate cash receipt from installment allocation. Create an immutable receipt ledger; allocate oldest-due-first under locks; support partial/multi-installment/advance/suspense; model bounce and reversal as compensating entries; preserve allocation history; reconcile settlement references and totals.

**Acceptance evidence:** invariant tests prove `receipt = allocated + suspense`; reversal restores installment/account balances; concurrent receipts never over-allocate; duplicate bank references cannot create duplicate receipts.

### CTRL-01 — real-money authorization and confirmation controls are insufficient

**Status:** Open — confirmed. **Decision landed 2026-07-13 (D2):** STP within hard caps + human maker-checker for exceptions (above-threshold, retries, re-affirmed beneficiaries, all manual initiations; maker ≠ checker enforced at DB level). Threshold values are config pending risk sign-off. Execution-ready spec: **S14 (§19.5)**; confirmation display is S12.  
**Severity:** P0 at real rails  
**Evidence:** A single `SYSTEM_ADMIN` can initiate disbursement; there is no second independent approver, amount-based limit, segregation-of-duties rule, or exception approval. The confirmation UI shows beneficiary details but not principal, fee, net amount, payment mode, loan ID, maker, checker, or immutable intent ID. UI copy says “queued” while the current backend calls synchronously.

**Recommendation:** Implement maker-checker using different principals; role/amount limits; step-up authentication for high values; explicit exception reasons; immutable maker/checker/time/IP/device audit; show principal, fee, net transfer, beneficiary snapshot, payment mode, intent/reference, and resulting state before confirmation.

### MOCK-01 — mock adapter and mock-outcome endpoint are too easy to carry into production

**Status:** Open — confirmed technically; **deferred 2026-07-15** (not in current implementation pass)  
**Severity:** P1 configuration risk / P0 if accidentally deployed as real  
**Canonical deferral record:** `docs/deferred-implementation.md` (S6 / MOCK-01). Spec remains **S6 (§19.3)**.  
**Evidence:** The mock disbursement adapter is an unconditional service and mock-outcome endpoints are registered with the application. There is no production adapter/profile selector that makes mock and live modes mutually exclusive.

**Deferral rationale (owner, 2026-07-15):** Management-review / synthetic UAT still intentionally uses mock rails; exclusive `mock`/`icici` provider selection is deferred until closer to Spec S17 (real ICICI adapter). Residual configuration risk accepted until then.

**Recommendation (when resumed):** Require an explicit `app.disbursement.provider`; fail production startup if mock adapter/routes are active under `prod`/`staging-live`; expose adapter mode in health/config diagnostics; keep mock data clearly labeled; ensure live provider and mock outcome endpoints cannot coexist.

### DATA-01 — approved beneficiary data is mutable

**Status:** Open — confirmed technically; **deferred 2026-07-15** (not in current implementation pass)  
**Severity:** P1 (residual accepted for synthetic UAT / pre-real-rails under current product assumptions)  
**Aliases:** F-S2, global borrower bank-data coupling  
**Canonical deferral record:** `docs/deferred-implementation.md` (S5 / DATA-01). Spec remains **S5 (§19.3)**.

**Evidence:** Disbursement reads the current global borrower bank account and IFSC; the loan/account lacks an approval-time beneficiary snapshot. A later update by another LSP relationship can change the data used for payout.

**Deferral rationale (owner, 2026-07-15):** Practical likelihood judged low under PAN-based borrower deduplication and a single active loan per customer; cross-relationship bank edits redirecting an in-flight payout are not expected in the current operating model. Residual risk remains accepted until real-money rails or until those assumptions change.

**Recommendation (when resumed):** Snapshot beneficiary name, verified account, IFSC, bank, verification evidence, and hash at approval; require explicit re-affirmation when snapshot inputs change; compare snapshot to provider request at execution. (Encryption of snapshot fields stays gated on D4 / cloud KMS.)

### IDEM-01 — idempotency is cross-node safe but crash recovery is incomplete

**Status:** Partial  
**Severity:** P1  
**Positive control:** Claim-before-execute and database uniqueness/fingerprints fixed the original duplicate race.

**Remaining defect:** Claim is committed separately, the action runs, and completion occurs later. A crash after business commit but before idempotency completion leaves `PENDING`. Duplicates poll briefly and then return `IDEMPOTENCY_IN_PROGRESS`; there is no stale-claim lease/recovery sweep that reconstructs the response.

**Recommendation:** Use lease owner/expiry and deterministic recovery; bind result/resource identity to the business transaction; reconstruct completed responses from committed state; test crash-before-action, crash-after-commit, timeout, retry from another node, and payload mismatch.

### Remaining money-flow defects and decisions

| ID | Status | Severity | Defect / risk | End-to-end recommendation |
|---|---|---:|---|---|
| F-S9 | Open | P0 live | No definitive DB uniqueness on provider transaction/intention references. | Deterministic reference plus partial unique index after duplicate cleanup. |
| F-S10 | Open | P1 | No complete bounced/reversed payment command model. | Immutable reversal entries and allocation rollback under installment lock. |
| F-MNY-09 | Historical — revalidate | P2 | Retry budget historically derived from non-atomic count. | Atomic attempt counter on claimed intent; cap and escalate exhausted attempts. |
| F-MNY-10 | Open | P2 | No bulk repayment ingestion. | Idempotent batch/file API with row-level outcomes, validation, reconciliation, and resumability. |
| NEW-05 / SCH-01 | **Closed (implemented 2026-07-15)** | ~~P1~~ | ~~LSP-provided schedule: dates only checked strictly-increasing (no first-due window, cadence, or horizon cap) and interest never re-derived from the frozen product rate.~~ Spec **S20**: date window/cadence/horizon + product-rate interest reconciliation. | See §19.6 / `docs/implementation-log.md`. |
| F-RPT-01 | Partial | P1 | Dashboard/report figures need point-in-time semantics. | Finish snapshot adoption and render `dataAsOf`; reconcile snapshot totals. |
| LSP-F10 | Open | P1 | No partner-scoped daily disbursement/repayment/settlement reconciliation API. | Date-ranged, paginated LSP extract with opening/closing balances, immutable refs, and certification metadata. |
| LSP-F11 | Open | P1 | Partners cannot see disbursement attempts/failure reason or remediation state. | Tenant-scoped attempt timeline, safe reason codes, settlement facts, and next action. |
| BUS-01 | **Decided 2026-07-13 (D6)** | P1 | ~~Real provider operating model not finalized.~~ Target fixed: ICICI Composite Pay + composite-status, LMS-initiated per-loan payouts, daily book-to-bank recon (matches the mock seam). Commercial cutoffs/SLAs still need the bank contract, but engineering is unblocked. | Spec **S17 (§19.5)**; prerequisites S3 + S6. |

### Money controls that are already correct and should not be weakened

- `BigDecimal` with fixed-scale `NUMERIC(19,2)`; no floating-point money.
- Database non-negativity and allocation/installment total constraints.
- Database-backed idempotency key uniqueness and payload fingerprinting.
- Pessimistic installment lock plus optimistic `@Version` backstop.
- Terminal-state guards and tenant checks on money endpoints.
- Do not replace server-confirmed state with optimistic UI updates for money/lifecycle transitions.

---

## 4. Security, privacy, compliance, and access control

### SEC-01 — PII and access-token protection remain below fintech production bar

**Status:** Open — **partially remediated 2026-07-15.** Token storage (item 4) closed by Spec **S11**. **Decisions landed 2026-07-13:** PAN display policy (D3 → Spec S15); encryption-at-rest **deferred (D4)** until cloud/KMS is chosen. **Spec S15 implementation deferred 2026-07-15** (owner accepted current PAN display for this pass — see `docs/deferred-implementation.md`).  
**Severity:** P0 remaining for plaintext PII; PAN policy decided but not implemented; SPA access-token-at-rest closed.  
**Defects recorded:**

1. PAN, Aadhaar, bank account and other borrower fields remain plaintext at rest.
2. Sensitive values are copied into audit, alert, intake, idempotency, and disbursement JSON payloads.
3. Some LSP/ops responses return full PAN; the UI displays it by default while naming a field as masked.
4. ~~Access/session data persists in browser `localStorage`, including the access token.~~ **Closed (S11, 2026-07-15):** access JWT is memory-only; metadata may remain in `localStorage`.
5. PII reveal coverage is inconsistent; legacy and current reveal-audit infrastructure overlap.
6. Retention and crypto-shredding are difficult because PII is scattered across append-only payloads.

**Recommendation:** KMS-backed envelope encryption for Aadhaar/bank/secrets; deterministic HMAC lookup for PAN with encrypted display value; mask PAN by default; purpose-bound, audited, time-limited reveal; redact at write time; ~~migrate access token to memory~~ (**done — S11**); deploy strict CSP; maintain key rotation and break-glass procedures.

### SEC-02 — upload validation is improved, but malware/CDR/quarantine is absent

**Status:** Partial  
**Severity:** P1 before customer-document production  
**Closed portion:** Filename, size, MIME and PDF/JPEG/PNG signature validation exists.  
**Open portion:** No AV/CDR/quarantine pipeline; current validation reads complete files into memory; preview conversion and content-hash/retention controls are incomplete.

**Recommendation:** Stream to quarantine; calculate cryptographic hash; scan/CDR asynchronously; promote only clean objects; use isolated safe-preview conversion; apply per-document retention/legal hold; alert and audit rejected/quarantined objects.

### SEC-03 — frontend HTTP client permits credential-bearing absolute URL fetches

**Status:** **Closed (implemented 2026-07-15)** — Spec **S7**; see §19.6 / `docs/implementation-log.md`  
**Severity:** ~~P1~~ (was hardening; bearer-token blast radius)  
**Evidence (historical):** `src/lib/api/http-client.ts` accepted absolute `http(s)` URLs and attached authorization in the shared fetch path.

**Fix:** Resolve against `VITE_API_BASE_URL`; refuse credential-bearing cross-origin requests; `fetchExternal` for unauthenticated external fetches.

### Additional security/compliance defects

| ID | Status | Severity | Finding | Recommendation |
|---|---|---:|---|---|
| F-S1 / F-01 | Open | P0 | Plaintext PII and secrets at rest. | KMS-backed encryption/HMAC, rotation, masked writes, migration plan. |
| LSP-F3 / G1 | **Decided 2026-07-13 (D3); implementation deferred 2026-07-15** | P1 | PAN exposure/masking policy inconsistent; Aadhaar masked while PAN full. | **Approved policy:** LSP API always masked; internal detail surfaces full for SYSTEM_ADMIN/OPS with page-level access audit; lists masked. Spec **S15 (§19.5)** — not scheduled this pass; see `docs/deferred-implementation.md`. |
| LSP-F6 | Open | P1 | LSP cannot self-rotate compromised API secret or manage webhook/allowlist safely. | Constrained partner-security admin role, step-up auth, dual-secret rotation window, full audit. |
| F-SEC-03 | Open | P1 | Malware scanning absent. | Quarantine/AV/CDR workflow. |
| F-SEC-04 / F-ISO-01 | Historical — revalidate | P1 | Redis failure behavior for rate limiting was undefined/SPOF. | Explicit fail-open/fail-closed policy per endpoint, local fallback, telemetry and chaos test. |
| F-SEC-05 | Historical — revalidate | P2 | Client-IP trust/header selection and 60s allowlist cache can delay revocation. | Trusted-proxy configuration, normalized client IP, cache invalidation/versioning. |
| F-T4 | Open | P2 | Cross-tenant IDOR suite is not generated exhaustively for every table/endpoint. | Generated RBAC/RLS matrix and negative IDOR tests in CI. |
| F-T1/F-T2 | Open/partial | P2 | Some defence-in-depth RLS coverage is additive/incomplete. | Complete RLS policy inventory; test every tenant-owned table under partner role. |
| F17 | Open | P3 | `report_request` historically depended on application filtering rather than RLS. | Add RLS if partner/report access expands; verify current internal-only boundary. |
| SEC-LOG-01 | Open | P1 | PII can enter alerts/audit logs before read-time masking. | Central redaction API and structured sensitive-field classification enforced at write time. |
| SEC-AUD-01 | Open | P1 | No proven immutable/WORM audit export and anomaly detection. | Signed archive manifests, restricted write path, WORM retention, reveal/access anomaly alerts. |

### Security controls confirmed as strong

- Six-of-six tested cross-LSP isolation cases passed.
- Principal-derived tenant scope is fail-closed and backed by RLS/integration tests.
- API-client and LSP status/token-version checks support revocation.
- LSP API/UI IP allowlists exist.
- Strict JSON rejects unknown fields.
- Auth error envelopes avoid raw provider data.
- Refresh cookie is HttpOnly, Secure and SameSite Strict.
- Bank-detail reveal has an audit path.
- Document signature validation closes the earlier “trust declared MIME” defect.

---

## 5. Database, schema, query, and data-lifecycle assessment

### Database assessment: **13/20 — strong relational core, incomplete production lifecycle**

| Dimension | Score | Assessment |
|---|---:|---|
| Referential and money integrity | 4/4 | Near-complete FKs, restrictive deletes, fixed-scale money, high-value CHECK constraints. |
| Concurrency and idempotency | 3/4 | Payment locking and claims improved; disbursement intent and stale-idempotency recovery remain. |
| Tenant isolation | 4/4 | RLS architecture and cross-tenant tests are a major strength. |
| Scale and lifecycle | 1/4 | No complete partition/archive/retention implementation at forecast append rates. |
| PII/data governance | 1/4 | Plaintext and duplicated sensitive data remain the largest database compliance weakness. |

### Current open/partial database findings

| Canonical ID | Status | Sev. | Defect | Root cause / impact | Recommended fix |
|---|---|---:|---|---|---|
| F-S1 / F-01 | Open | P0 | PII stored plaintext and duplicated into JSON. | Search/dedupe convenience and broad serialization create breach/erasure risk. | Encrypted values + deterministic PAN hash; redact at write; key lifecycle. |
| F-S2 | Open (payout subset **deferred 2026-07-15** as S5) | P1 | Global borrower updates mutate all LSP views and payout source data. | Shared identity and mutable operational data are conflated. | Separate canonical vs relationship data (S19); freeze loan beneficiary snapshot (S5 — deferred, see `docs/deferred-implementation.md`). |
| F-S4 | Open | P2 | Lifecycle history dual-written to two near-identical tables. | Two truth sources, write amplification, repeated enum migration work. | Make one audit stream canonical; retire other writes, preserve read-only history. |
| F-S9 / F-Q5 | Open | P0 live | Provider references lack durable intent uniqueness/workflow. | Application status lock is not a bank-side dedupe boundary. | Deterministic intent, unique index, out-of-tx provider call, reconciliation. |
| F-S10 | Open | P1 | No reversal/bounce ledger. | Payment model assumes final exact settlement. | Immutable compensating transactions and allocation rollback. |
| F-S11 | Open | P2 | Webhook event types stored as CSV on LSP. | Denormalized subscription state caused vocabulary/matching bugs. | Normalize endpoint/subscription/event rows with uniqueness and status. |
| F-S12 | Open | P1 | Webhook signing secret protection/rotation incomplete. | Tenant row stores long-lived secret. | Encrypt with KMS; secret versions and overlap rotation; never display after creation. |
| F-S16 | Partial | P1 | Production password/default validation and rotation runbook incomplete. | Defaults improved, but startup assertion/operational rotation evidence remain. | Fail startup on unsafe values; secret manager; tested rotation runbook. |
| F-Q1/F-Q2 | Partial | P1 | Dashboard/alert aggregates moved toward snapshots, but UI timestamp and current performance proof are incomplete. | Full-book reads were replaced only partially and presentation lacks freshness. | Finish set-based snapshots; render `dataAsOf`; compare snapshot/live reconciliation. |
| F-Q8 | Partial — **schedule decided 2026-07-13 (D7)** | P1 | Only idempotency gets scheduled purge; refresh token/webhook/audit/report/access retention remains. | Default per-class schedule adopted (financial 8 y archive, audit 2 y hot, webhooks 180 d, tokens 30 d, idempotency 90 d), compliance-tunable. | Spec **S18 (§19.5)**: retention workers, monthly partitions, legal hold, purge manifests. |
| F-T1/F-T2 | Open/partial | P2 | Additive RLS policy hardening remains. | Some tables/surfaces still rely on application scope. | Complete policy inventory and generated tests. |
| #201 | Open | P1 | Production pool sizing, statement timeout and idle-in-transaction controls unproven. | Local/default datasource behavior is not a production capacity policy. | Explicit pool budget across replicas; DB timeouts; alert on saturation/long tx. |
| F-D1/F-D2/F-D5 | **Partial (S19 Slice A, 2026-07-15)** | P1 | Canonical PAN-unique borrower + relationship rows (D8). | Slice A: `borrower_lsp_relationship` (V113) dual-written; access collection retained for RLS. | Residual: drop `visibleLspIds`, normalizer + CHECKs, profile audit — see `docs/deferred-implementation.md` / Spec S19. |
| F-DB-02 | Open | P0 scale | No partitioning for high-growth audit/event tables. | Forecast 50–400M rows/year makes monolithic indexes/retention unsafe. | Time partitioning before high volume; archive/drop partitions with legal hold. |
| F-DB-07 | Open | P2 | Report-access LSP derivation used JSON/regex instead of typed indexed data. | Historical payload-first modeling makes queries brittle. | Use typed `lsp_id` column and remove regex extraction. |
| F-DB-09 | Open | P2 | Shared/Supabase pool ceiling may constrain scale. | Deployment connection budget not matched to API/worker replica count. | Pooler-mode validation, replica budget, load test, saturation alarms. |
| F-AUD-01/F-AUD-02 | Open/partial | P1 | Audit append volume and explorer unions need partition-aware bounds. | Many streams plus long retention create index/union cost. | Mandatory date/keyset guards, partitions, archive tier, pre-aggregates where justified. |

### Historical database findings explicitly closed by July remediation

| ID | Status | Defect that was recorded | Closure evidence |
|---|---|---|---|
| F-S7 | Closed | Concurrent different-key payments could silently lose installment updates. | V105, installment `@Version`, pessimistic lock, concurrency integration test. |
| F-S8 | Closed | Payment claim committed before allocation could leave orphan `RECEIVED` rows. | Claim and allocation moved into one transaction. |
| F-D3 | Closed | Integrity violations could surface as generic 500; request sizes uncapped. | `DataIntegrityViolationException` mapping and DTO `@Size` caps. |
| F-S14 | Closed | Four audit timestamps used non-time-zone types. | V106 migrated to `TIMESTAMPTZ`. |
| F-Q11 | Closed | Dead unbounded repository methods. | Methods removed. |
| F-S17 / F-05 | Closed | Eager money-graph associations forced unnecessary joins. | LAZY fetch remediation. |
| F-Q4 | Closed | MIS export hydrated the full portfolio in memory. | Keyset-batched export. |
| F-N1 | Closed | Product version bump lacked regression protection. | Version bump regression test. |
| F-Q6 | Closed/partial successor | LSP/admin idempotency executed before claim. | Claim-before-execute fixed race; stale-PENDING recovery remains IDEM-01. |
| F-Q7 | Closed | Audit explorer lacked date/keyset guardrails. | V107, cursor, 90-day cap. |
| F-Q9 | Closed | Principal/session validation repeatedly hit DB. | Auth principal cache and lookup deduplication. |
| F-Q10 / F-06 | Closed | Unbounded list defaults and borrower frontend over-fetch. | Backend cap 200 and frontend pagination. |
| F-S15 | Closed | Migration/schema drift lacked automated reconciliation. | Schema-diff tooling, CI, production reconciliation through V109. |
| F-03 | Closed | Missing FK on webhook outbox loan application. | V66 preflight and FK. |
| F-02/F-20 | Closed materially | Missing monetary/data CHECK constraints. | V65 and later constraint migrations. |
| F-08/F-13/F-14 | Closed materially | Redundant/dead/incorrect index definitions. | V61/V64 pruning/reconciliation. |
| F-09/F-10 | Closed materially | Payload width/type problems (`VARCHAR(4000)`, JSON-as-text). | Width and JSONB migrations plus object checks. |
| F-11 | Closed materially | Case-insensitive lookup bypassed normalized unique indexes. | Username/data canonicalization and query/index alignment. |
| F-12 | Closed materially | Derived delete could issue row-by-row work. | Bulk delete/query remediation. |

### Lower-severity database items retained from the SQL review

| ID | Status | Finding | Recommendation |
|---|---|---|---|
| F-S3 | **Partial (S19 Slice A)** | `visibleLspIds` ElementCollection still RLS-authoritative; relationship entity dual-written (V113). Residual: promote reads/drop collection — `docs/deferred-implementation.md`. |
| F-S5 | Open cleanup/P3 | Deprecated assignment columns/table and legacy `report_content` remain. | Retain for forensic window, then drop in planned cleanup migration. |
| F-S6 | Accepted/P3 | Audit actor stored as text without immutable subject ID on older streams. | Canonical actor format; new streams add nullable subject IDs while preserving text. |
| F-15 | Historical/P3 | Aadhaar/IFSC columns were oversized. | Keep tightened widths and validation aligned. |
| F-16 | Revalidate/P3 | Webhook-attempt sentinel defaults such as `UNKNOWN`/`UNSIGNED`. | Prove no live writer relies on sentinel values; remove unsafe defaults. |
| F-18 | Open/P3 | Single webhook endpoint/event set embedded in LSP. | Normalize when multi-endpoint or independent lifecycle is required. |
| F-19 | Historical/P3 | Refresh token username denormalization. | Later FK/XOR remediation largely supersedes; verify rename behavior. |
| F-21 | Accepted/P3 | Document-access audit types stored via delimited/auxiliary representation. | Normalize only if analytics needs individual-type querying at scale. |
| F-22 | Revalidate/P2 | Large-data safety of old borrower merge/backfill migrations. | Rehearse against production-size snapshot and record lock/duration/rollback. |

### Database changes that should not be made casually

- Do not replace the relational core with a document database.
- Do not remove RLS because application filters currently pass.
- Do not remove fixed-scale `NUMERIC`/`BigDecimal` money handling.
- Do not weaken restrictive loan-graph foreign keys or DB invariants.
- Do not collapse leased `SKIP LOCKED` report/webhook claims into unbounded JPA scans.
- Do not partition every small table; partition only high-growth append streams based on retention/query keys.

---

## 6. UI/UX assessment

### Impeccable technical audit score: **14/20 — Good, but not release-ready**

| Dimension | Score | Assessment |
|---|---:|---|
| Accessibility | **3/4** | Strong semantic/component tests, accessible row actions, labels, focus styles and existing axe coverage, but no current WCAG conformance evidence; browser verification was blocked. |
| Performance | **2/4** | Effect-driven state accumulation/reset, a 488-line high-complexity detail page, repeated client-side filtering/pagination, and a serial unit suite that needs ~12 minutes. |
| Responsive design | **3/4** | Generic tables can become mobile cards and reports reflow well; dense loan servicing tables, long detail/checklist flows, and wide desktop queues remain awkward. |
| Theming | **3/4** | Coherent semantic tokens, focus styles, density support and light/dark foundations; current contrast/theme behavior lacks fresh browser/visual certification. |
| Anti-patterns | **3/4** | Professional, task-oriented product UI with familiar controls; somewhat card-heavy, repeated eyebrow/card sections, and dense modal-driven workflows, but not an “AI slop” interface. |
| **Total** | **14/20** | **Good — address runtime, financial-control, performance and release-gate defects before release.** |

### Anti-pattern verdict

**Pass, with reservations.** The interface does not present as a generic AI-generated dashboard: it uses a restrained semantic vocabulary, real fintech states, tabular numerics, consistent component primitives, explicit loading/empty/error states, accessible tables, role-aware actions, and operational density. The main anti-pattern is structural repetition rather than visual gimmickry: long pages are assembled from many bordered cards, `PageHeader` eyebrow copy is repeated, and modal confirmation is used where an inline staged workflow would better communicate money state. No evidence supports a claim of gradient-text, decorative glassmorphism, bounce motion, or arbitrary novelty.

### Current UI/UX defects

| ID | Status | Sev. | Location/flow | User impact | Recommendation |
|---|---|---:|---|---|---|
| OPS-01A | **Closed — validated 2026-07-12 evening; S10 guards 2026-07-15** | ~~P1~~ | Home dashboard | ~~Authenticated operator sees “Couldn't load your dashboard”.~~ Root cause was the pgjdbc `Instant` bind fixed in `f825e81`; `/internal/home/overview` returns 200. Residual: UX-03 diagnostic context. | Instant-bind Testcontainers IT + `@canary` E2E (Spec S10). |
| OPS-01B | **Closed — validated 2026-07-12 evening; S10 guards 2026-07-15** | ~~P1~~ | Audit Log | ~~Last authenticated walkthrough failed.~~ Same root cause/fix; Audit renders in browser. Residual: UX-03. | Instant-bind Testcontainers IT + `@canary` E2E (Spec S10). |
| UX-01 | Open | P1 | Loan detail lifecycle actions | “Submit for approval” can remain enabled while visible gates say documents/schedule are incomplete. | Drive UI actions from backend `blockedReasons`; explain resulting state; block or rename incomplete submission explicitly. |
| UX-02 | **Closed (display slice — S12, 2026-07-15)** | ~~P0 live~~ | Disbursement confirmation | ~~Confirmation omits principal, fee, net transfer, mode, loan ID.~~ Preview + reference endpoints + dialog money summary; maker-checker still Spec S14. | Residual: `beneficiarySource=LIVE_BORROWER` until S5; checker queue is S14. |
| UX-03 | Open | P1 | Error states | Retry-only errors hide correlation ID and next support action. | Standard error component with safe message, code, correlation ID, event time, retry and support copy. |
| UX-04 | Open | P1 | Borrower overview | Full PAN displayed by default; masking policy inconsistent with Aadhaar. | Mask by default; purpose-bound audited reveal or explicitly approved role policy. |
| UX-05 | Open | P2 | Reports | No “data as of” / reconciliation certification. | Render snapshot timestamp, source window, generation status and reconciliation status. |
| UX-06 | Open | P2 | Loan queue / dense tables | Wide tables require horizontal scroll and hide later columns. | Prioritize columns, sticky identity/actions, responsive card/detail drawer, visible overflow affordance. |
| UX-07 | Open | P2 | Loan detail / documents | Very long card/checklist page increases scanning and action distance. | Progressive sections, sticky status/action summary, completion counts, collapse completed groups. |
| UX-08 | Open | P2 | Servicing | Schedule/payment tables use fixed `min-width` and raw dense rows on narrow screens. | Responsive priority fields/cards; preserve exact lookup in expandable detail. |
| UX-09 | Open | P2 | My-loans detail state | Synchronous state reset in render/effects and servicing state accumulation increase churn. | Key state by loan/account or use a query/cache state machine; derive rather than mirror state. |
| UX-10 | Open | P2 | Audit page state | Synchronous state accumulation inside an effect fails lint and can add rerenders. | Derive composed state or move reset/update to event/query boundary. |
| UX-11 | Open | P2 | Documents effect | `docLabels` dependency omitted. | Stabilize memo/source and declare the complete dependency set. |
| UX-12 | Open | P2 | Test/runtime warnings | Canvas, Router future-flag and React `act(...)` warnings obscure real regressions. | Deliberate canvas mock; opt into/test future flags; await user/state effects correctly. |
| UX-13 | Decision required | P2 | Product design system | No `PRODUCT.md` or `DESIGN.md`; intended product/design rules are implicit. | Run `/impeccable init`, then `/impeccable document`; codify personas, platform, tokens and interaction standards. |

### Historical UX/UI defect register — every previously recorded item

The July production audit reports that many of these were fixed. They remain listed to preserve the complete history. “Closed” means later source/audit evidence says the original defect was addressed; it does not imply a fresh cross-browser certification.

| Original ID | Current status | Original defect | Current interpretation / remaining action |
|---|---|---|---|
| A1 | Closed | Schedule/Repayments showed raw backend error with internal UUID for pre-approval loans. | Remediation landed; retain safe-message regression test and correlation ID only in support context. |
| A2 | Closed materially | Activity feed rendered raw enum constants. | Humanization was remediated; keep canonical backend code available only in audit detail if needed. |
| A3 | Partial / superseded by UX-01 | Disabled Approve looked enabled and gate reason was distant. | Styling/tooltip improved, but lifecycle action can still conflict with visible blockers. |
| B1 | Closed materially | Raw Zod default messages reached users. | Global/targeted error mapping landed; Fallow now flags the error-map function as complex/uncovered. Add focused tests. |
| C1 | Closed | Repo-wide mojibake/encoding corruption. | Encoding gate passes. Preserve `.editorconfig`/CI guard. |
| C2 | Closed | “Loan Service Provider” conflicted with canonical “Lending Service Provider.” | Terminology corrected; maintain glossary. |
| C3 | Closed | “BHAW LOAN ID” abbreviation was cryptic. | Copy remediated to clearer product terminology. |
| C4 | Closed | Internal term “placeholders” surfaced to users. | Copy remediated. |
| D1 | Closed/verify | Native date controls used US `mm/dd/yyyy` in an India product. | Locale remediation reported; verify actual browser rendering and screen-reader language. |
| D2 | Closed materially | Three inconsistent status-filter patterns; mixed native/custom selects in one bar. | Main filter/status work remediated; preserve context-appropriate tabs vs filters. |
| D3 | Closed | Reports required pasting raw LSP UUID. | Replaced by name-based selection. |
| D4 | Closed materially | Portfolio preview overflowed and empty state was misplaced. | Report mobile reflow judged healthy; remaining provenance and dense-table concerns are UX-05/06. |
| E1 | Closed materially | Notification and Help controls were dead. | Remediation reported; role-aware destination/help content should remain tested. |
| E2 | Closed materially | 404 dropped authenticated app shell. | Route fallback remediation landed. |
| E3 | Partial | Data tables were not mobile optimized. | Generic mobile cards now exist; dense servicing/custom tables still need targeted adaptation. |
| F1 | Closed materially | Dev role quick-fill/scaffolding exposed; unconfigured presets failed. | Login visual quality is good; environment/bootstrap drift now blocks UAT instead. |
| G1 | Open policy / SEC-01 | Aadhaar masked while PAN/mobile/bank presentation was inconsistent. | PAN/default masking and purpose-bound reveal remain unresolved. |
| G2 | Closed materially | Bootstrap user displayed “Created 57 years ago.” | Relative-date guard/seed cleanup reported fixed; preserve implausible-date fallback tests. |
| H1 | Decision/ops | Obvious demo/test data pervaded environment. | Acceptable in synthetic UAT only; production seed/cleanup guard must prohibit demo identities/documents. |
| LSP-F1 | Closed | KFS omitted from UI taxonomy, making UI-driven loans unable to complete. | July production audit confirms KFS visibility fixed. Keep enum-contract generation/test. |
| LSP-F4 | Open | Invalidation reasons are placeholder “Reason A/B/C/Others.” | Replace with risk-approved reason codes and descriptive labels; preserve immutable code in audit. |
| LSP-F5 | Partial | LSP UI omitted schedule, payments, delinquency, activity and disbursement detail already in API. | Some servicing detail is now rendered in `MyLoanDetailPage`; disbursement attempt/reconciliation visibility remains. |
| LSP-F7 | Open | LSP list UI lacks API-supported search/filter. | Add debounced query, status/product/source filters and URL-persisted state. |
| LSP-F8A | Revalidate | Forced-password-change succeeds but reload returns to login. | Verify intended token/session rotation and provide explicit “sign in again” success copy if by design. |
| LSP-F8B | Open/P3 | Terminal-state documents say “read-only for your account,” implying a permission issue. | Say “This loan is closed/terminal; documents can no longer be changed.” |

### Positive UI patterns to preserve

- Semantic product UI rather than decorative dashboard styling.
- Reusable `DataTable` supports controlled state, keyboard row actions, accessible names, density, skeletons, captions and mobile cards.
- Clear status badges and role-aware action gates.
- Existing axe/component test investments.
- Explicit empty/loading/error components rather than silent blank pages.
- Tabular numerics and consistent fintech formatting.
- Light/dark semantic token vocabulary and visible focus treatment.
- Reports’ mobile reflow and clear empty state.

### Impeccable remediation sequence

1. **P0 `/impeccable harden`** — disbursement confirmation, maker/checker visibility, PAN reveal, and lifecycle blocked reasons.
2. **P1 `/impeccable clarify`** — diagnostic errors, terminal/read-only copy, invalidation vocabulary, action-result language.
3. **P1 `/impeccable optimize`** — effect-driven state, detail-page complexity, repeated list filtering and warning-free tests.
4. **P2 `/impeccable adapt`** — dense servicing tables, queue overflow and long checklist behavior on narrow screens.
5. **P2 `/impeccable document`** — capture the existing product design system in `DESIGN.md` after `/impeccable init` establishes product context.
6. **Final `/impeccable polish`** — browser-based keyboard, zoom, light/dark, overflow, focus, loading and error QA.

---

## 7. API design, partner operations, and data flow

### API assessment: **11/20 — robust contracts, incomplete operating product**

| Dimension | Score | Assessment |
|---|---:|---|
| Validation and error model | 4/4 | Strict JSON, field violations, domain codes, correlation IDs and UUID/range checks are strong. |
| Tenant/role boundary | 4/4 | Tested isolation and server enforcement are strong. |
| Workflow truthfulness | 1/4 | Create/progression contracts disagree; UI and synchronous/queued semantics diverge. |
| Partner operability | 1/4 | Reconciliation, disbursement visibility, servicing breadth and security self-service remain incomplete. |
| Contract maintainability | 1/4 | Large nested controller contracts and handwritten frontend mappings drift from generated OpenAPI types. |

### API-01 — create accepts loans that cannot progress

**Status:** ~~Open — confirmed~~ **Closed materially — runtime-validated 2026-07-12 evening (see §19.1).** The current tree adds `BorrowerOnboardingRequirements` as a single source of truth shared by `LoanApplicationOnboardingService` (create) and `LoanAutoApprovalRuleEngine` (progression); an incomplete LSP create now returns 422 `BORROWER_REQUIRED_FIELDS_MISSING` with per-field violations (reproduced live). Residual work: document the contract in the partner OpenAPI, align DTO annotations for self-documentation, and decide admin-path parity.  
**Severity:** ~~P1~~ residual P3 documentation  
**Data flow:** LSP create returns success → documents can upload → auto-approval evaluates stricter fields → application remains `INITIALIZED` → no structured blocked reason returns to LSP.

**Mismatch:** Address/reference fields are optional at intake and income can be monthly or annual, while progression requires address/city/state/ZIP, positive monthly income and reference-person values.

**Recommendation:** Choose one explicit product contract:

- complete-at-create: make progression-required fields request-required and reject with field violations; or
- draft intake: return `requirements`, `blockedReasons`, `nextActions`, and a deliberately named incomplete status.

Never return a generic success that creates an indefinitely stuck resource.

### Partner/API defect register

| ID | Status | Sev. | Defect | Recommendation |
|---|---|---:|---|---|
| API-01 / LSP-F2 | Open | P1 | Accepted payload can silently stall in `INITIALIZED`. | Align validation or expose structured requirements/blocked reasons. |
| LSP-F4 | Open | P2 | Placeholder invalidation reason vocabulary. | Risk-approved stable codes plus localized labels and free-text only for `OTHER`. |
| LSP-F5 | Partial | P1 | Servicing/disbursement detail not fully available to partner UI. | Map/render schedule, payment, delinquency, activity, attempt and reconciliation state. |
| LSP-F6 | Open | P1 | No constrained LSP security self-service. | Scoped partner-admin endpoints for dual-secret rotation, webhook and allowlist changes. |
| LSP-F7 | Open | P2 | LSP list UI ignores API search/filter capabilities. | Surface supported query parameters and preserve them in URL. |
| LSP-F10 | Open | P1 | No tenant-scoped reconciliation/MIS API. | Daily immutable extract and snapshot with source/freshness/certification metadata. |
| LSP-F11 | Open | P1 | Disbursement remains central-admin-driven and opaque to partner. | Partner-safe status/failure timeline; throughput/ownership plan for central action. |
| G-004 / UC-029 | Revalidate/current contract decision | P1 | OPS payment UI historically conflicted with SYSTEM_ADMIN-only API. | Align permission vocabulary, hide unauthorized controls, contract-test UI capability matrix. |
| G-005 / UC-021 | Revalidate | P2 | OPS status-transition UI/API semantics were ambiguous. | Document OPS escalation vs admin transition and enforce one capability source. |
| G-006 | Open tooling | P2 | Generated Postman collection lacks multipart bodies/assets. | Version safe fixture files and runnable multipart examples. |
| G-007 | Open tooling | P2 | Foreclosure missing from demo collection runner. | Add quote/execute/replay/invalid-state coverage. |
| G-008 | Decision/P3 | P3 | No borrower portal despite matrix references. | Mark API/admin-only or define borrower product scope; do not imply coverage. |
| G-009 | Decision/P3 | P3 | LSP product catalog is API-only. | Keep API-only if intentional; otherwise add scoped catalog UI. |
| API-DOC-01 | Open | P1 | No clearly published partner-only OpenAPI with operational semantics. | Publish versioned `/lsp/**` spec including auth, idempotency, pagination, rate, retention and error contracts. |
| API-COMPAT-01 | Open | P1 | Generated/manual contracts can drift without consumer compatibility gate. | OpenAPI diff, generated types, consumer-driven partner contract tests. |

### API controls confirmed as correct

- Structured error envelope: code, safe message, correlation ID, field violations.
- Unknown JSON fields rejected rather than ignored.
- UUID/format/range/product mapping and duplicate external ID handling.
- Pagination metadata is exposed and main frontend lists consume it.
- Cross-tenant reads/mutations fail; other-tenant rows are absent from lists.
- Create/payment idempotency replay and conflict semantics are database-backed.
- Terminal-state mutation guards are present.

---

## 8. Architecture, code quality, and maintainability

### Fallow evidence

| Measure | Result | Interpretation |
|---|---:|---|
| Files analyzed | 492 | Frontend/TypeScript scope only; Fallow does not analyze Java backend. |
| Functions analyzed | 3,399 | Broad static function inventory. |
| Cleanup findings | 184 | 1 unused file, 68 unused exports, 115 unused types. |
| Duplicated lines | 4,461 / 55,017 (**8.1084%**) | 19 clone groups, 60 instances, 100 files participating. |
| Complexity threshold findings | 42 | 6 critical, 11 high, 25 moderate. |
| Average maintainability | 90.4 | Healthy aggregate hides several concentrated boundary hot spots. |
| Circular dependencies | 0 | Strong signal; preserve. |
| Boundary violations | 0 | Strong signal under the analyzer's configured model. |
| Unresolved/unlisted imports | 0 | Dependency graph is structurally clean. |

### Complexity and boundary defects

| ID | Status | Sev. | Location | Evidence | Recommendation |
|---|---|---:|---|---|---|
| ARCH-01A | Open | P1 | `frontend/src/features/my-loans/detail-page.tsx` | `MyLoanDetailPage`: cyclomatic 48, cognitive 75, 488 LOC, CRAP 55.8. | Split data orchestration, page state machine and presentation sections; use query hooks and typed view model. |
| ARCH-01B | Open | P1 | `frontend/src/features/loan-applications/api-detail.ts` | `backendToDetail`: cyclomatic 44, 133 LOC, CRAP 462.2. | Generated contract plus small pure mappers per bounded sub-object; exhaustive status decoding. |
| ARCH-01C | Open | P1 | `frontend/src/features/borrowers/api.ts` | Borrower mapper cyclomatic 29, CRAP 210.7. | Normalize API schema and split mapping/compatibility/default logic. |
| ARCH-01D | Open | P1 | `frontend/src/components/app/data/DataTable.tsx` | Cyclomatic 35, cognitive 27, 240 LOC. | Decompose controlled-state adapters/table model/render modes; preserve generic contract tests. |
| ARCH-01E | Open | P2 | `frontend/src/lib/zod-error-map.ts` | Cyclomatic 18, cognitive 23, no coverage, CRAP 342. | Table-driven issue-code mapping and exhaustive tests. |
| ARCH-01F | Open | P2 | `ScheduleTab` / servicing surfaces | Cognitive complexity 34. | Split calculations/state/presentation; render normalized rows. |
| ARCH-01G | Open | P2 | `src/lib/api/http-client.ts` | `performFetch` cyclomatic 17 and security reachability. | Separate URL policy, authentication, retry/error decode and transport; same-origin tests. |
| ARCH-01H | Open | P1 | `LspLoanApplicationApiController.java` | ~945 lines and large nested contract ownership. | Split bounded resources/controllers and move contracts to dedicated package. |
| ARCH-01I | Open | P1 | `GlobalExceptionHandler.java` | ~700 lines, 31 conditionals. | Typed exception-to-error registry; group by domain; contract tests for every code/status. |
| ARCH-01J | Open | P2 | `AdminReportingService.java` | ~585 lines combining query/export/report concerns. | Separate read model, export writer, request workflow and storage delivery. |
| ARCH-01K | Open | P2 | `AdminApiIdempotencyService.java` | ~400 lines with mechanical/format churn. | Normalize formatting, extract shared lease/result logic, preserve admin-specific policy. |
| ARCH-01L | Open | P1 | `frontend/src/features/my-loans/api.ts` | ~696 lines of handwritten contract mapping. | Adopt generated OpenAPI types and domain-specific mapper modules. |
| ARCH-01M | Open | P1 | Backend/FE account status | FE models `ACTIVE`; backend has disbursement/reconciliation/invalid states and is cast into inaccurate union. | One generated status vocabulary with exhaustive parsing; delete obsolete abstraction. |
| ARCH-01N | Open | P2 | Response dependency direction | `LspLoanApplicationResponses` depends on controller-nested response types. | Dedicated API contract package independent of controller class. |

### Duplication and cleanup defects

| ID | Status | Sev. | Finding | Recommendation |
|---|---|---:|---|---|
| CQ-01 | Open | P2 | 8.1084% duplicated lines; client filtering/pagination repeated across alerts, API clients and users. | Shared server-list query/state abstraction only where semantics match; avoid a universal god hook. |
| CQ-02 | Open | P2 | Test fixture/setup clones appear across multiple suites. | Typed builders/factories with explicit overrides; keep scenario intent local. |
| CQ-03 | Open | P2 | Filter-schema clones suggest vocabulary drift risk. | Canonical query schema and generated/central status options. |
| CQ-04 | Open | P2 | 68 unused exports and 115 unused types expand public surface. | Remove after reference verification; make package exports intentional and CI-enforced. |
| CQ-05 | Open | P3 | `src/test/internal-session.ts` is unused. | Delete if no dynamic/tooling consumer; add explicit reference if intentionally loaded. |
| CQ-06 | Open | P2 | 100 files participate in clone groups. | Prioritize production contract/mapping clones over harmless test readability duplication. |
| CQ-07 | Partial | P2 | Graphify repository map is stale (2026-06-12) and noisy. | Install/pin Graphify; refresh after code changes; tune extractor around generic `of`/`toString` god nodes. |

### Architectural direction

Retain the modular monolith. The current pressure points are boundary ownership and transaction design, not deployable-service count. Recommended boundaries:

1. **Origination command/read split:** intake and lifecycle commands separate from list/detail projections.
2. **Servicing ledger:** receipts, allocations, reversals, suspense and reconciliation as a cohesive domain.
3. **Disbursement intent:** durable state machine with provider adapter outside database transactions.
4. **Partner contract:** dedicated versioned request/response package generated into frontend types.
5. **Reporting read model:** snapshots/batches independent from live transaction aggregates.
6. **Operations plane:** queue age, reconciliation exceptions, retries and runbook actions as explicit resources.

### Architecture controls to preserve

- No current circular dependency or configured boundary violation.
- Former 1,172/1,455-line facade/god service was decomposed.
- No `@Lazy` injection remains.
- Architecture tests exist.
- Typed money/errors and lifecycle helpers improved.
- A modular monolith remains proportionate; premature microservices would add distributed transaction and operational risk.

---

## 9. Scalability, performance, reliability, and historical finding reconciliation

### Current capacity verdict

**No current experiment demonstrates production capacity.** The June benchmark—approximately 1.5 RPS, 28.6% errors, and ~32-second dashboard p95 in the recorded run—was a valid failure signal for that code state. July introduced KPI snapshots, batched MIS, auth caching, query caps and concurrency remediations, so the old numbers are no longer a fair measurement of current code. They also cannot be discarded: until a replacement multi-instance peak/soak test passes, the committed 150K-loans/day peak remains an open P0 gate.

### Required capacity acceptance test

- Month-9 production-like dataset and tenant skew.
- At least two API instances and two independently scalable worker instances.
- Two-hour all-LSP peak plus spike, soak and one-whale-tenant scenarios.
- Error rate below 0.5% excluding intentional 4xx.
- Origination create p95 below 2 seconds; dashboard p95 below 3 seconds.
- No connection-timeout 5xx or long-lived idle transactions.
- No cross-tenant p99 degradation outside the agreed noisy-neighbor budget.
- Webhook/report oldest-item age stays within SLA and drains after fault recovery.
- Zero duplicate/lost payment/disbursement actions under retries and injected crashes.
- Provider/storage/Redis/DB latency and partial failure injected deliberately.

### Complete June scalability catalog, with current interpretation

Every historical finding is retained below. “Historical — revalidate” means July code changed the path or no fresh measurement exists; it is not silently treated as fixed.

#### API throughput and latency

| ID | Original sev. | Current status | Recorded defect | Current action |
|---|---:|---|---|---|
| F-API-01 | P0 | Open gate / revalidate | Connection-pool starvation dominated tail latency. | Explicit connection budget, statement/idle timeouts, pool metrics; rerun multi-instance load. |
| F-API-02 | P0 | **Decided 2026-07-13 (D5)** | 60/min LSP write limit incompatible with high-volume origination bursts. | Per-LSP configurable rate plans + idempotent bulk-create endpoint with per-row outcomes. Spec **S16 (§19.5)** (also covers F-API-05 read quotas). |
| F-API-03 | P1 | Closed materially | DB session validation occurred on every LSP call. | Auth principal cache landed; load-test hit/miss, revocation latency and failure behavior. |
| F-API-04 | P1 | Historical — revalidate | Loan create p95 was ~7 seconds. | Profile current create path, DB/provider/storage contribution, require p95 <2s. |
| F-API-05 | P1 | Open | No read-rate limits on LSP GET endpoints. | Cost-aware read quotas, pagination caps, cache where safe, fair-use telemetry. |
| F-API-06 | P2 | Open/verify | Document upload could hold a DB connection while object-storage I/O occurs. | Stream outside DB transaction; short metadata transactions; slow-storage fault test. |
| F-API-07 | P2 | Decision/revalidate | Auth-token limit 10/min per IP can throttle shared NAT users/integrations. | Principal/client-aware limit, trusted proxy/IP handling, documented retry and lockout interaction. |
| F-API-08 | P3 | Open noise risk | Ops alert emitted for every 429. | Aggregate/rate-limit alerts; metrics for volume; page only sustained abnormal rate. |

#### Database and query scalability

| ID | Original sev. | Current status | Recorded defect | Current action |
|---|---:|---|---|---|
| F-DB-01 | P0 | Open | No proven `statement_timeout` / `idle_in_transaction_session_timeout`. | Configure per environment/role; test cancellation and transaction cleanup. |
| F-DB-02 | P0 | Open | No partitioning on high-growth tables. | Partition audit/events before high volume; archive/drop by retention/legal hold. |
| F-DB-03 | P1 | Partial | Dashboard and MIS full-portfolio queries. | KPI snapshots/batched MIS landed partly; render freshness and benchmark current plans. |
| F-DB-04 | P1 | Historical/open on disbursement | Disbursement worker `findByStatus` was unbounded. | Bounded leased `SKIP LOCKED` intent claims with indexes and oldest-age metrics. |
| F-DB-05 | P1 | Revalidate | RLS child-table policy overhead at payment volume. | EXPLAIN ANALYZE with tenant context on month-9 data; tune policy/index joins, never remove RLS casually. |
| F-DB-06 | P2 | Closed materially | Audit explorer lacked partition-friendly mandatory date guard. | 90-day/keyset guard landed; align future partition key and archived-query path. |
| F-DB-07 | P2 | Open/partial | Report-access LSP filter relied on JSONB regex. | Use typed indexed `lsp_id`; remove payload parsing. |
| F-DB-08 | P2 | Partial | Idempotency table grew without bound. | 90-day purge landed; monitor purge lag/locks and handle other append streams. |
| F-DB-09 | P2 | Open gate | Supabase/session pooler ceiling may be ~15 connections. | Verify real plan/mode, budget across replicas/workers, saturation load and alerting. |

#### Multi-tenant fairness and noisy-neighbor control

| ID | Original sev. | Current status | Recorded defect | Current action |
|---|---:|---|---|---|
| F-TEN-01 | P2 | Open | Isolation is sound but CPU, DB, thread and queue pools are shared. | Per-LSP metrics, fair queues, bulkheads and admission control. |
| F-TEN-02 | P1 | Open | No per-LSP resource quotas across reads/writes/webhooks. | Tiered quotas and burst budgets derived from contracts/capacity. |
| F-TEN-03 | P1 | Open | Shared webhook dispatch pool had no per-LSP cap. | Tenant-fair claim/scheduler, per-LSP concurrency and dead-letter isolation. |
| F-TEN-04 | P2 | Partial/revalidate | Alert evaluation scanned full portfolio on every pod. | Set-based snapshot worker under distributed lock; prove single execution and bounded work. |
| F-TEN-05 | P3 | Revalidate | LSP write limit could be skipped if JWT lacked `lspId`. | Fail closed for partner roles; negative token-claim tests. |

#### Failure isolation and dependency resilience

| ID | Original sev. | Current status | Recorded defect | Current action |
|---|---:|---|---|---|
| F-ISO-01 | P0 | Open/revalidate | Redis unavailable behavior for rate limiting was undefined. | Explicit endpoint policy; fallback/bypass alarms; chaos test. |
| F-ISO-02 | P1 | Open | No circuit breaker on external dependencies. | Timeouts, bounded retries, circuit/bulkhead per provider; distinguish unknown money outcome. |
| F-ISO-03 | P1 | Open/partial | All schedulers could run on every pod. | Distributed locks or dedicated workers; idempotent jobs; leader-loss tests. |
| F-ISO-04 | P2 | Revalidate | Report worker held one transaction for a full batch. | Per-item/short claim transactions, lease and resumable generation. |

#### Disbursement and repayment consistency

| ID | Original sev. | Current status | Recorded defect | Current action |
|---|---:|---|---|---|
| F-MNY-01 | P0 | **Closed (2026-07-13)** | Provider call inside DB transaction. | S3: durable intent; out-of-tx provider call; see `docs/implementation-log.md`. |
| F-MNY-02 | P0 | **Partial (2026-07-13)** | Disbursement worker used a single mega-transaction. | Intent workflow: leased bounded claims + short txs; legacy path when flag off. |
| F-MNY-03 | P0 | **Partial (2026-07-13)** | No safe multi-instance disbursement work claiming. | `SKIP LOCKED`/lease on `disbursement_intent` when workflow enabled. |
| F-MNY-04 | P1 | Closed | Payment claim in `REQUIRES_NEW` was separate from allocation. | Same-transaction claim/allocation remediation landed. |
| F-MNY-05 | P1 | Closed | No installment row lock for concurrent different-key payments. | Pessimistic lock + version + concurrency test landed. |
| F-MNY-06 | P1 | Closed/partial successor | LSP idempotency executed before claim. | Claim-first fixed; stale-PENDING recovery remains. |
| F-MNY-07 | P1 | Closed materially | Loan-create idempotency optional/natural key not replay-friendly. | Current LSP create idempotency is DB-backed; preserve mandatory/documented behavior. |
| F-MNY-08 | P2 | Open | No payment bounce/reversal at scale. | Receipt/reversal ledger and compensating allocation. |
| F-MNY-09 | P2 | Revalidate | Retry budget used non-atomic count. | Atomic attempt state on intent; exhaustion workflow. |
| F-MNY-10 | P2 | Open | Bulk repayment ingestion absent. | Resumable idempotent batch API/file pipeline and reconciliation. |

#### Reporting accuracy and throughput

| ID | Original sev. | Current status | Recorded defect | Current action |
|---|---:|---|---|---|
| F-RPT-01 | P0 | Partial | Dashboard used live aggregates rather than point-in-time snapshots. | KPI snapshots exist; complete frontend freshness, reconciliation and load proof. |
| F-RPT-02 | P1 | Closed materially | MIS CSV hydrated entire portfolio in memory. | Keyset-batched export landed; benchmark storage/network path. |
| F-RPT-03 | P1 | Partial/revalidate | Async report race and no retry lease. | `SKIP LOCKED` claims landed; verify lease expiry, retry budget and crash recovery. |
| F-RPT-04 | P2 | Open | Synchronous report download proxies bytes through JVM. | Signed short-lived object URL or streaming gateway; access audit preserved. |
| F-RPT-05 | P2 | Revalidate | PAR30 summary used correlated `EXISTS` per account. | EXPLAIN on month-9 dataset; snapshot/preaggregate if still expensive. |
| F-RPT-06 | P3 | Open/product | No scheduled nightly per-LSP MIS enqueue. | Add only if operating model requires; tenant-scoped schedule, retry and certification. |

#### Audit logging

| ID | Original sev. | Current status | Recorded defect | Current action |
|---|---:|---|---|---|
| F-AUD-01 | P1 | Open | High append rate without partition/retention. | Time partitions, archive manifests, legal hold, purge evidence. |
| F-AUD-02 | P1 | Partial | Eight-stream union lacked mandatory date bound. | Guard landed; partition-aware plans and archive query remain. |
| F-AUD-03 | P2 | Open/verify | Explorer did not include every audit stream. | Canonical stream inventory and coverage test; avoid leaking PII. |
| F-AUD-04 | P2 | Open/decision | Audit writes are synchronous in request path. | Keep transactionally critical audit synchronous; outbox non-critical enrichment with reliability proof. |

#### Background workers and queues

| ID | Original sev. | Current status | Recorded defect | Current action |
|---|---:|---|---|---|
| F-WKR-01 | P1 | Open/revalidate | Fixed-delay semantics amplify backlog. | Queue-age-driven scaling, bounded batch, next-run cadence independent of job duration. |
| F-WKR-02 | P2 | Open/architecture decision | RabbitMQ exists in infra but domain workers do not use it. | Do not adopt reflexively; choose DB queue vs broker per throughput/replay/ordering need. |
| F-WKR-03 | P2 | Open | No complete domain queue-depth/oldest-age metrics. | Instrument intent, report, webhook, alert and idempotency queues. |

#### Security under scale

| ID | Original sev. | Current status | Recorded defect | Current action |
|---|---:|---|---|---|
| F-SEC-01 | P1 | Closed materially | No API-client credential lockout. | Lockout landed; monitor false positives, admin unlock and attack telemetry. |
| F-SEC-02 | P2 | Revalidate | CORS hardcoded to localhost. | Environment allowlist; reject wildcard+credentials; preflight tests. |
| F-SEC-03 | P2 | Open | Upload malware scanning absent. | Quarantine/AV/CDR pipeline. |
| F-SEC-04 | P2 | Open/revalidate | Redis was a rate-limit SPOF. | Explicit degradation policy and resilience tests. |
| F-SEC-05 | P3 | Open/revalidate | Allowlist cache/revocation delay and wrong-header IP risk. | Trusted proxy chain, normalized IP, versioned invalidation. |

#### Uptime, observability, incident response and recovery

| ID | Original sev. | Current status | Recorded defect | Current action |
|---|---:|---|---|---|
| F-OPS-01 | P0 | Partial | Prometheus/metrics endpoint/deployment not proven. | Secure scrape endpoint, deployed collector, retained dashboards and alert tests. |
| F-OPS-02 | P0 | Open/unproven | Alerts were stored but not proven delivered. | Pager/email/chat notifier with retry, dedupe, escalation and synthetic delivery test. |
| F-OPS-03 | P1 | Open/partial | No fully standardized structured JSON logging/MDC. | JSON schema, correlation/trace/tenant IDs, PII redaction, ingestion contract. |
| F-OPS-04 | P1 | Open | No complete runbooks or verified alert paths. | Money, DB saturation, queue backlog, provider unknown, RLS and DR runbooks with drills. |
| F-OPS-05 | P2 | Open | Health probe lacks dependency granularity/readiness semantics. | Separate liveness/readiness; DB/Redis/storage/provider degradation detail without secrets. |
| F-OPS-06 | P2 | Open | No synthetic portfolio or multi-instance failure test bed. | Deterministic synthetic tenants, workers, canaries and fault injection in staging. |

---

## 10. Testing, quality gates, and every recorded test defect

### Full campaign result

| Surface | Exact command / method | Result | Duration / scale | Production interpretation |
|---|---|---|---:|---|
| Fallow static | `npx fallow --format json --quiet --explain` | PASS, valid `kind=combined`, schema 7 | 492 files / 3,399 functions | Static inventory valid for JS/TS; not a Java analyzer. |
| Fallow security | `npx fallow security --format json --quiet` | PASS, valid `kind=security` | 1 candidate | Candidate needs contextual review; not proof of exploit. |
| Backend | `backend\mvnw.cmd test` with JDK 21 | **PASS** | 738 tests; 0 failures; 0 errors; 1 skipped; 6m55s | Strong broad suite; one environment-coupled test remains hazardous. |
| Frontend typecheck | `npm run typecheck` | **PASS** | 1.346s | `tsc --noEmit` alone does not prove project-reference build. |
| Frontend lint | `npm run lint` | **FAIL** | 3 errors, 1 warning; 30.449s | Release gate red. |
| Frontend format | `npm run format:check` | **FAIL** | 10 files; 13.937s | Integration baseline inconsistent. |
| Encoding | `npm run check:encoding` | **PASS** | 1.402s | Previous mojibake recurrence is guarded in this run. |
| Frontend unit/coverage | `npm run test:cov` | **PASS** | 121 files, 748/748 tests; 11m54s | Assertions green; coverage/noise remain concerns. |
| Frontend production build | `npm run build` | **FAIL** | 3 TypeScript errors; 8.512s | No releasable frontend artifact. |
| Playwright | `npm run e2e` | **INFRASTRUCTURE BLOCKED** | 55 tests discovered; Chromium `spawn EPERM` | 46 failed/9 not run are not app verdicts. |
| Python edge/API harness | fixture preflight | **INFRASTRUCTURE BLOCKED** | `requests` unavailable | Harness result absent; dependency setup itself is defective. |

### Backend suite details and defects

- **Result:** 134 Surefire XML reports aggregate to 738 tests: 737 executed successfully and 1 skipped.
- **Runtime:** 6m55s, proving a five-minute outer timeout is invalid for this repository.
- **Slowest (2026-07-12 campaign):** `DocumentUploadLocalProfileIntegrationTest` at 44.137s (removed 2026-07-13 by S2; replaced by `DocumentUploadPostgresIntegrationTest` on Testcontainers). Remaining slow classes: `LmsApplicationTests` 22.68s; `TenantIsolationPostgresIntegrationTest` 15.843s; `LspLoanApplicationApiControllerTest` 14.161s; `SyntheticPortfolioSeedServiceTest` 13.296s.
- **Noise:** Mockito self-attachment/JDK future warnings only; no backend assertion failures.

| ID | Sev. | Test defect | Impact | Recommendation |
|---|---:|---|---|---|
| TEST-BE-01 | ~~P0~~ **Closed (implemented 2026-07-13)** | ~~`DocumentUploadLocalProfileIntegrationTest` used `@ActiveProfiles("local")`, read repository `.env`, and wiped the Supabase URL via `IntegrationTestDatabaseCleaner`.~~ Replaced by three-layer S2 fix (§19.6): Testcontainers default, JDBC URL guard, opt-in `external-db` tag. Full `mvnw test` green with `.env` present; Supabase untouched. | ~~Routine `mvn test` could wipe the live shared database.~~ | **Done** — Spec S2. |
| TEST-BE-02 | P2 | Wrapper requires a valid `JAVA_HOME` but campaign setup was not self-diagnosing. | Fresh agents/CI can fail before tests. | Toolchain/preflight script prints JDK path/version and supported Java range. |
| TEST-BE-03 | P2 | Mockito self-attach warning will become incompatible with future JDK behavior. | Future upgrades may break mocking unexpectedly. | Configure Mockito agent per current guidance and test JDK upgrade in CI. |
| TEST-BE-04 | P2 | One skipped test is not dispositioned in the summary. | Unknown coverage gap can become permanent. | Record exact skipped case, reason, owner and expiry in CI output. |
| TEST-BE-05 | P1 | Fresh Flyway tests do not replace migration-from-production-snapshot testing. | Backfill/lock/drift defects can pass on empty schema. | Versioned anonymized snapshot upgrades with timing, rollback and invariant checks. |

### Frontend lint defects — exact current failures

| ID | Location | Rule/symptom | Risk | Fix |
|---|---|---|---|---|
| LINT-01 | `frontend/src/features/audit/components/AuditTable.tsx:123` | Unused `filters`. | Build/lint failure and stale component contract. | Remove parameter or implement its intended use; align call sites/tests. |
| LINT-02 | `frontend/src/features/audit/page.tsx:149` | Synchronous state update/accumulation inside effect. | Extra render cycles and state synchronization defects. | Derive state or update at query/event boundary. |
| LINT-03 | `frontend/src/features/my-loans/detail-page.tsx:78` | Synchronous state reset inside effect/render lifecycle. | Churn, stale transition risk and lint failure. | Key/query state by `id`, reducer/state machine, or derived reset. |
| LINT-04 | `frontend/src/features/my-loans/components/DocumentsSection.tsx:214` | Missing `docLabels` effect dependency; warning fails due `--max-warnings 0`. | Stale labels/effect behavior after dependency changes. | Stabilize source and include complete dependencies. |

### Production-build defects — exact current failures

| ID | Location | Defect | Root cause | Fix |
|---|---|---|---|---|
| BUILD-01 | `AuditTable.tsx` | Unused `filters` compilation error. | Contract changed without cleanup. | Same fix as LINT-01. |
| BUILD-02 | `frontend/src/features/my-loans/detail-page.test.tsx` | Fixture lacks required `lastActivity`. | Domain/API contract tightened without fixture builder update. | Typed fixture builder with explicit nullable/default field; contract-generation test. |
| BUILD-03 | `frontend/src/features/users/api.test.ts` | `CreateUserInput` fixture lacks required `lspId`. | Tenant-scoping contract changed without test update. | Require tenant-aware builder/input; add role-specific validation tests. |

### Formatting defects — all 10 files

The 2026-07-12 Prettier gate rejected:

1. `frontend/src/components/app/lifecycle/TransitionConfirmDialog.tsx`
2. `frontend/src/components/app/repayment/RepaymentPostDialog.tsx`
3. `frontend/src/features/audit/components/AuditTable.tsx`
4. `frontend/src/features/audit/page.tsx`
5. `frontend/src/features/loan-applications/api.ts`
6. `frontend/src/features/my-loans/components/DocumentsSection.test.tsx`
7. `frontend/src/features/my-loans/components/DocumentsSection.tsx`
8. `frontend/src/features/my-loans/detail-page.test.tsx`
9. `frontend/src/features/my-loans/detail-page.tsx`
10. `frontend/src/lib/api/generated/schema.ts`

Generated code should either be deterministically formatted during generation or excluded only with a documented reason; it must not make the normal release gate nondeterministic.

### Frontend unit-test and coverage defects

**Passing evidence:** 121/121 test files and 748/748 tests passed.  
**Coverage:** 55.79% statements/lines, 76.32% branches, 64.57% functions.

| ID | Sev. | Defect | Evidence / impact | Recommendation |
|---|---:|---|---|---|
| TEST-FE-01 | P1 | Statement/line coverage is only 55.79%. | Critical mapper/state/money-confirmation branches can remain unexecuted. | Risk-weighted thresholds for money, auth, tenant, mapping and lifecycle modules; do not chase vanity global percentage. |
| TEST-FE-02 | P2 | 69 canvas `getContext` warnings. | Real browser/canvas regressions are buried in expected noise. | Intentional canvas mock/polyfill or isolate visualization tests. |
| TEST-FE-03 | P2 | 47 React Router future warnings. | Upgrade behavior changes are deferred and logs are noisy. | Enable future flags in a branch, fix changes, assert navigation behavior. |
| TEST-FE-04 | P1 | 20 React `act(...)` warnings. | Tests may finish before user-visible state settles, allowing false green. | Await interactions/effects; use `findBy`/`waitFor`; eliminate every warning. |
| TEST-FE-05 | P2 | Serial Vitest coverage takes ~12 minutes. | Feedback latency and external runner timeout risk. | Profile slow files; safe sharding/parallelism; preserve deterministic isolation. |
| TEST-FE-06 | P1 | High-CRAP functions lack proportionate focused coverage. | Complex mapping/error/state paths are most likely to regress. | Table/property tests for mappers, error map, URL policy and lifecycle state. |

### Test orchestration, CI and E2E defects

| ID | Sev. | Defect | Impact | Recommendation |
|---|---:|---|---|---|
| CI-01 | P1 | `npm run verify` is fail-fast. | Lint hides formatting, unit and build results; one red gate obscures full picture. | Separate independent CI jobs or collect all stages and fail at end. |
| CI-02 | P1 | Frontend release baseline fails lint, format and build. | No trustworthy release artifact. | Fix current defects; require clean `verify` on every merge. |
| CI-03 | P2 | External cancellation left orphan Node/cmd workers. | Later suites stalled at zero tests and consumed resources. | Runner owns a job/process tree and kills descendants on cancellation. |
| CI-04 | P2 | Five-minute external timeout was shorter than valid suite runtime. | False timeout/failure and orphan processes. | Measured CI budget ≥15 minutes for FE coverage and ≥10 for backend, with per-test deadlock guards. |
| E2E-01 | **Materially closed (S9, 2026-07-15)** | ~~Stale "System roles" specs / hardcoded passwords / Phase 8 throw without app id.~~ Env-only login helpers; smoke asserts Email/Password; `globalSetup` seeds `E2E-*` fixtures; Phase 8 skips with reason when unseeded; pinned `requirements-e2e.txt`; `docs/e2e.md`. Residual: full suite CI on browser worker still optional. | Specs no longer target removed UI; Phase 8 is self-skipping. | Keep `npm run e2e:canary` on deploy checklist. |
| E2E-02 | P1 | Phase 8 throws when `E2E_APPLICATION_ID` is missing, causing nine tests not to run. | One configuration gap suppresses the rest of a project. | Preflight or conditional skip with explicit reason; separate environment-specific project. |
| E2E-03 | P1 | Python harness has no checked-in pinned requirements/lock. | Fresh environment fails at `import requests`. | Add `requirements-e2e.txt`/locked project and deterministic install command. |
| E2E-04 | P1 | E2E credentials/config are not reproducibly aligned with running bootstrap identity. | Authenticated flow cannot be repeated safely. | Seed ephemeral test identity via supported API/fixture; never hardcode/shared production-like secret. |
| E2E-05 | P2 | Lifecycle spec depends on seed data. | Test order/environment coupling. | Create isolated fixture via API and clean it deterministically. |
| E2E-06 | P2 | Worker/time-dependent cases need explicit triggers or bounded waits. | Flaky long sleeps and nondeterminism. | Admin test hooks or clock/worker control in test profile; await observable state. |
| E2E-07 | P2 | Webhook tests require reachable subscriber; rate tests require Redis/flag. | Important failure paths silently skip in default runs. | Compose dedicated E2E profile with local subscriber and Redis; report selected capabilities. |
| E2E-08 | P2 | R2 is the local default without credentials in readiness review. | Document upload E2E fails for configuration, not product behavior. | Force filesystem/Testcontainer-compatible storage in local E2E profile. |
| E2E-09 | P2 | Postman matrix has empty manual steps/mis-tags/overlaps and incomplete multipart/foreclosure coverage. | Coverage reporting is misleading. | Canonical generated case inventory with owner, preconditions, automation link and result. |
| E2E-10 | P2 | Graph/test runners do not expose one authoritative “all suites” command with capability report. | Audits require bespoke orchestration. | Repository validation entry point runs independent gates, reports skips/blockers and preserves logs. |

### Missing high-value test classes

1. Disbursement kill-point tests at intent commit, provider accept, outcome commit and reconciliation.
2. Provider contract tests for signed replay, duplicate reference, timeout/unknown, late success and permanent rejection.
3. Receipt/allocation property tests, bounce/reversal and concurrent multi-key posting.
4. Generated RBAC/RLS/IDOR matrix for every tenant resource and endpoint.
5. Migration tests from production-like schema/data, including lock duration and rollback.
6. Consumer-driven partner contracts and backward-compatible OpenAPI diff.
7. Multi-instance peak/soak/spike/failure injection.
8. Browser keyboard, focus trap, zoom, responsive, contrast and screen-reader announcement tests.
9. Restore/PITR and reconciliation drill as a release artifact.

### Machine-readable and raw test evidence

- [Fallow combined JSON](D:/Desktop-New/Folders/LMS/outputs/validation-2026-07-12/fallow-combined.json) — complete combined cleanup, duplication, health and security-candidate payload.
- [Fallow security JSON](D:/Desktop-New/Folders/LMS/outputs/validation-2026-07-12/fallow-security.json) — dedicated security result.
- [Backend full log](D:/Desktop-New/Folders/LMS/outputs/validation-2026-07-12/backend-full-test.stdout.log) — complete Maven/Surefire output.
- [Backend stderr log](D:/Desktop-New/Folders/LMS/outputs/validation-2026-07-12/backend-full-test.stderr.log) — Mockito/JDK warnings.
- [Frontend campaign results](D:/Desktop-New/Folders/LMS/outputs/validation-2026-07-12/frontend-campaign-results.json) — exact stage commands, exit codes and durations.
- [Frontend unit/coverage log](D:/Desktop-New/Folders/LMS/outputs/validation-2026-07-12/frontend-vitest-coverage.stdout.log) — all 121 files, 748 tests and coverage table.
- [Frontend unit/coverage stderr](D:/Desktop-New/Folders/LMS/outputs/validation-2026-07-12/frontend-vitest-coverage.stderr.log) — canvas, Router and `act(...)` warnings.
- [Frontend lint log](D:/Desktop-New/Folders/LMS/outputs/validation-2026-07-12/frontend-lint.stdout.log) — exact ESLint findings.
- [Frontend formatting log](D:/Desktop-New/Folders/LMS/outputs/validation-2026-07-12/frontend-format-check.stderr.log) — exact rejected files.
- [Frontend build log](D:/Desktop-New/Folders/LMS/outputs/validation-2026-07-12/frontend-build.stdout.log) — exact TypeScript build failures.
- [Repeatable frontend runner](D:/Desktop-New/Folders/LMS/outputs/validation-2026-07-12/run-frontend-campaign.ps1) — independent-gate campaign runner.
- [Validation report](D:/Desktop-New/Folders/LMS/outputs/validation-2026-07-12/VALIDATION_REPORT.md) — concise campaign synthesis and limitations.

---

## 11. Observability, production operations, recovery, and environment safety

### Observability assessment: **9/20 — useful primitives, insufficient operating proof**

**Present and worth preserving:** correlation IDs; actuator health; database health indicator; structured-ish domain logs; webhook outcome counters, retry/backoff, dead-letter and redrive; selected exception/alert counters; ops alerts; report/webhook leased work patterns.

### Open operational defects

| ID | Sev. | Defect | Operational consequence | Required control |
|---|---:|---|---|---|
| OBS-01A | P1 | Prometheus scrape/deployment and retained dashboards are unproven. | Metrics code may exist without an operational monitoring path. | Deploy secured scrape, recording rules, dashboards and synthetic alert tests. |
| OBS-01B | P1 | No end-to-end OpenTelemetry trace across API → DB → worker → provider/storage/webhook. | Slow/failed workflows require log archaeology. | Trace/parent propagation with sensitive attributes excluded. |
| OBS-01C | P1 | No complete SLO/error-budget definition. | Teams cannot decide when reliability work blocks feature rollout. | SLOs for auth, intake, dashboard, money intents, webhook/report age and partner API. |
| OBS-01D | P0 live | No disbursement intent/reconciliation dashboard or alarm. | Unknown payouts can remain invisible. | Intent age, unknown outcome, provider/local mismatch and duplicate-reference alerts. |
| OBS-01E | P1 | No receipt/suspense/reversal reconciliation totals. | Book/cash divergence can accumulate. | Daily balance controls and exception queue with sign-off. |
| OBS-01F | P1 | No stale idempotency `PENDING` age dashboard/recovery. | Committed actions can remain wedged. | Lease-age metric, recovery worker and alert threshold. |
| OBS-01G | P1 | Queue depth/oldest age incomplete for reports, webhooks, alerts and workers. | Backlogs are detected by complaints rather than telemetry. | Per-domain depth, oldest age, throughput, retry/exhaustion and tenant skew. |
| OBS-01H | P1 | Alert delivery path not proven. | Stored critical alerts may never reach on-call staff. | Synthetic page, retry/dedupe/escalation metrics and periodic end-to-end test. |
| OBS-01I | P1 | Production JSON log/MDC schema and PII redaction incomplete. | Searches are inconsistent and logs may leak sensitive data. | Versioned log schema, trace/correlation/tenant/actor IDs, centralized redaction. |
| OBS-01J | P2 | Health endpoints do not communicate dependency-specific readiness/degradation. | Traffic may reach an instance unable to serve critical work. | Separate liveness/readiness; bounded dependency checks; safe degraded-state detail. |
| OPS-01 | P1 | Home/Audit runtime failures hide diagnostics. | Operators lose core visibility and support evidence. | Root-cause, correlation-aware error UI and synthetic protected-route canary. |
| ENV-01 | **Closed residuals (S10, 2026-07-15)** | ~~Bootstrap drift / dual password vars / startup-only sync.~~ Login path validated 2026-07-12; S10 removed `APP_SECURITY_BOOTSTRAP_LOGIN_PASSWORD`, added `POST /internal/system/bootstrap-sync`, Home/Audit Instant bind Testcontainers + `@canary` E2E. Residual: no dedicated ephemeral UAT identity product yet. | Reviewers can log in; bootstrap heal without restart. | Keep canary on deploy checklist. |
| OPS-DR-01 | P0 live | Multi-AZ/PITR/RPO/RTO restore evidence is absent. | A major incident can lose availability/data without proven recovery. | Recovery design, encrypted backups, quarterly restore and measured RPO/RTO. |
| OPS-RUN-01 | P1 | Critical money/provider/DB/queue/security runbooks are incomplete/unverified. | Incident response depends on individual memory. | Named owner, detection, triage, safe action, rollback, escalation and evidence checklist. |

### Minimum production dashboards

1. Partner/API SLO: request rate, 4xx/5xx, p50/p95/p99, tenant skew, rate-limit and pool saturation.
2. Disbursement control: intents by state/age, provider outcomes, unknowns, retries, reconciliation mismatches, maker/checker SLA.
3. Collections ledger: receipts, allocation, suspense, bounced/reversed, bank settlement difference.
4. Worker health: queue depth, oldest age, claims, lease expiry, retries, dead letters, tenant fairness.
5. Database: pool active/wait, transaction age, statement timeout, slow query, lock wait, replication/PITR health, partition growth.
6. Security/privacy: auth failures/lockouts, secret rotation age, PII reveal volume/anomalies, allowlist denials, upload quarantine.
7. Reports/audits: snapshot freshness, generation failures, archive/purge progress, audit ingestion and export verification.

---

## 12. Strange, duplicated, suspicious, or unnecessary states and flows

| ID | Status | Observation | Why suspicious | Recommendation |
|---|---|---|---|---|
| ODD-01 | **Closed (S8, 2026-07-15)** | ~~Frontend `LoanAccountStatus` included `ACTIVE` and omitted backend states.~~ Zod + badges aligned to eight backend literals; no `ACTIVE`. | ~~Casts conceal contract drift.~~ | Residual: keep OpenAPI regen in CI when enum changes. |
| ODD-02 | Open | Lifecycle history dual-writes transition and audit-event tables. | Two truth sources and repeated migration/write amplification. | One canonical append stream; old table read-only for forensic continuity. |
| ODD-03 | Open | Deprecated assignment columns/table and legacy report content remain. | Dead schema increases cognitive and migration burden. | Time-box forensic retention; planned cleanup migration. |
| ODD-04 | Open | Legacy `loan_application_pii_reveal_audit` coexists with borrower reveal audit. | Overlapping/dead compliance infrastructure confuses coverage. | Canonical reveal event model; migrate readers and retain immutable history. |
| ODD-05 | Open | Webhook subscriptions encoded partly as CSV on LSP. | Event vocabulary bugs and no independent endpoint lifecycle. | Normalized subscriptions/events. |
| ODD-06 | Open | Mock adapter is unconditional and mock outcomes live beside real workflow endpoints. | Environment misconfiguration can create false financial state. | Mutually exclusive profiles and startup validation. |
| ODD-07 | **Materially closed when intent workflow on (S3)** | ~~Dialog "queued" vs synchronous adapter call.~~ Intent workflow (default on) queues provider work; S12 money preview landed. Residual: legacy inline path when flag off. | Prefer intent-on deployments; do not reintroduce sync-in-tx provider calls. |
| ODD-08 | Open | API accepts incomplete intake but auto-approval silently leaves it `INITIALIZED`. | “Success” is not progress and there is no diagnosable transition. | Explicit draft/requirements state. |
| ODD-09 | Open | Payment domain contains partial concepts but public path requires exact full settlement. | Dormant abstractions imply incomplete/contradictory model. | Build coherent receipt-allocation domain or remove misleading unreachable concepts until planned. |
| ODD-10 | Open | `AdminApiIdempotencyService` has unusually high blank/mechanical churn. | Signals generated/manual conflict or unfinished cleanup. | Normalize and extract shared workflow carefully, with crash tests. |
| ODD-11 | Open | 68 unused exports, 115 unused types and one unused test file. | Public surface and stale contracts obscure ownership. | Evidence-driven deletion and intentional export maps. |
| ODD-12 | Open | Graphify generic nodes (`of`, `toString`, `String`) act as noisy god nodes. | Repository map can mislead architecture conclusions. | Refresh and tune extractor; never use inferred generic edges as proof. |
| ODD-13 | Open | Test harness has hardcoded/default credentials and non-pinned dependencies. | Automation becomes environment-specific and unsafe. | Ephemeral credential/fixture generation and locked dependencies. |
| ODD-14 | Open | A nominal backend test can infer permission to use Supabase from `.env`. | Test execution scope expands silently. | Explicit opt-in and disposable infrastructure. |

---

## 13. Safeguards and capabilities that must be added

### Before formal management/demo build

- Clean frontend lint, format and production build.
- Reproducible UAT bootstrap identity without shared/hardcoded secrets.
- Root-cause Home/Audit failures and expose correlation-aware recovery.
- Structured `blockedReasons` contract and truthful lifecycle actions.
- PAN masking/purpose decision and immediate JSON/log redaction.
- Financial confirmation with principal/fee/net/beneficiary/reference.
- One authoritative validation command that preserves every gate result.

### Before limited non-money partner pilot

- Partner-only versioned OpenAPI and compatibility gate.
- Daily LSP reconciliation/MIS extract and servicing/disbursement visibility.
- Scoped LSP security self-service and dual-secret rotation.
- Stale idempotency recovery.
- Complete token/webhook/report/audit/document retention policy and jobs.
- Production pool/timeouts, metrics, JSON logs, dashboards and verified alerts.
- Multi-instance staging, E2E profile and current capacity run.
- Beneficiary snapshot/re-approval.
- Real invalidation vocabulary and partner-operable failure reasons.

### Before real-money integration or launch

- Durable disbursement intent/provider/outcome/reconciliation state machine.
- Maker-checker, limits, segregation of duties, step-up authentication and exception approvals.
- Receipt/allocation/suspense/bounce/reversal ledger.
- Bank settlement and daily book-to-bank reconciliation with sign-off.
- KMS-backed PII/secrets encryption, rotation and audited reveal.
- Malware/CDR/quarantine and safe document preview.
- Partition/archive/legal-hold/restore implementation.
- Provider security: mTLS/signing/encryption, replay protection, certified contract, key rotation, timeout/unknown semantics, circuit and bulkhead.
- Multi-AZ/PITR and measured RPO/RTO restore drill.
- Independent penetration test, threat model, privacy/compliance review and financial-control walkthrough.

---

## 14. Prioritized remediation roadmap

### Phase 0 — restore a trustworthy release baseline (0–2 weeks)

| Priority | Work | Owner profile | Exit evidence |
|---:|---|---|---|
| 1 | Fix lint/build/format defects and warning noise. | Frontend | `verify`, build and all 748 tests pass with no `act` warnings. |
| 2 | Fix/reproduce Home and Audit; add diagnostic error context. | Backend + frontend + QA | Seeded Playwright smoke and API regression; correlation ID visible. |
| 3 | Reconcile bootstrap identity/config and create ephemeral UAT seeding. | Platform/security | One documented clean-environment login; no secret in repo. |
| 4 | Align intake/progression and lifecycle `blockedReasons`. | Product + API + frontend | Contract tests and UI actions match backend eligibility. |
| 5 | Mask PAN by default and redact sensitive alert/audit writes. | Security + backend + frontend | PII response/log tests; audited reveal decision. |
| 6 | Correct disbursement confirmation and mock-mode startup guards. | Product + backend + frontend | Amount/fee/net/beneficiary shown; production refuses mock mode. |

### Phase 1 — safe partner pilot foundation (2–8 weeks)

| Priority | Work | Exit evidence |
|---:|---|---|
| 1 | Beneficiary snapshot and change re-approval. | DB/API/UI tests prove post-approval profile change cannot redirect payout. |
| 2 | Idempotency lease/recovery. | Kill-after-business-commit recovers deterministic response from another node. |
| 3 | Partner reconciliation, servicing and disbursement status surface. | Tenant-isolated daily extract reconciles seeded ledger; UI/API next action visible. |
| 4 | Partner security self-service. | Dual-secret rotation and webhook/allowlist changes pass RBAC/audit tests. |
| 5 | Retention and token/webhook/report/audit cleanup. | Scheduled jobs, legal hold, purge manifest and restore test. |
| 6 | Production observability and runbooks. | Dashboards, synthetic alert delivery and on-call game day. |
| 7 | Multi-instance E2E/load environment. | Browser/API matrix and current scale baseline published from tagged commit. |

### Phase 2 — real-rail engineering program (8–20+ weeks)

| Priority | Work | Exit evidence |
|---:|---|---|
| 1 | Durable disbursement intent and reconciliation. | Provider sandbox certification and crash/fault matrix with zero duplicate movement. |
| 2 | Maker-checker and financial limits. | Independent-principal enforcement, exception/limit tests, immutable audit. |
| 3 | Receipt/allocation/reversal ledger. | Property/invariant tests and bank-reconciliation proof. |
| 4 | Encryption/key lifecycle and document security. | Rotation drill, encrypted backup proof, AV/CDR quarantine evidence. |
| 5 | Partition/archive/DR. | Month-9 performance, partition maintenance, restore and RPO/RTO drill. |
| 6 | Production capacity/failure gate. | Two-hour multi-instance acceptance test meets every SLO and backlog target. |
| 7 | Independent assurance. | Pen test, threat model, privacy assessment, financial-control sign-off and launch review. |

---

## 15. Release gates and decision checklist

### Management-review gate

- [ ] Frontend lint, format, unit and build all green.
- [ ] Home and Audit Log demonstrably usable in a fresh seeded environment.
- [ ] Mock mode visibly labeled and production-prohibited.
- [ ] No real/customer PII in demo data.
- [ ] Known limitations and synthetic nature disclosed.

### Partner-pilot gate

- [ ] Intake never silently stalls; blockers/next actions are machine-readable.
- [ ] PAN/PII purpose and masking approved.
- [ ] Partner can reconcile its book and see servicing/disbursement outcomes.
- [ ] Partner can rotate secrets safely or has a tested operational SLA.
- [ ] Browser/API E2E and tenant/RBAC matrix pass on a tagged build.
- [ ] Current load and failure evidence meets pilot volume/SLO.
- [ ] Alert delivery, backups and restore are tested.

### Real-money launch gate

- [ ] Durable intent and reconciliation certified with provider.
- [ ] Maker-checker, limits and segregation of duties enforced.
- [ ] Arbitrary receipts, allocation, suspense, bounce and reversal are supported.
- [ ] Book-to-bank reconciliation is automated and signed off daily.
- [ ] PII/secrets encrypted with tested key rotation and reveal audit.
- [ ] Partitions/retention/legal hold/archive/restore are operational.
- [ ] Multi-instance peak/soak/fault testing meets SLO/error/backlog targets.
- [ ] DR RPO/RTO drill passes.
- [ ] Independent security, privacy/compliance and financial-control reviews sign off.

---

## 16. Items that are already correct and should not be “fixed” into regressions

1. Keep `BigDecimal` and fixed-scale database numerics.
2. Keep DB money invariants, uniqueness/fingerprints and restrictive loan-graph FKs.
3. Keep RLS as defence in depth; do not rely solely on controller/service filters.
4. Keep installment pessimistic locking and entity versions.
5. Keep strict JSON, structured errors and correlation IDs.
6. Keep tenant-scoped list/read/mutation checks and negative isolation tests.
7. Keep webhook/report leased `SKIP LOCKED` claims.
8. Keep product terms/version frozen at origination.
9. Keep document signature validation while adding AV/CDR.
10. Keep server-authoritative lifecycle/money state; do not add optimistic financial transitions.
11. Keep the modular monolith unless measurable organizational/scale boundaries justify services.
12. Keep safe partner errors; never expose raw provider response or secrets for “debuggability.”

---

## 17. Further questions requiring explicit business or governance decisions

*Status update 2026-07-13: questions 1–8 were answered in the owner review recorded in §19.4; remaining open sub-items are noted inline.*

1. ~~Is the LMS intended to become the accounting/system-of-record ledger?~~ **Answered (D1): the LMS is the system of record.**
2. ~~Who owns canonical borrower identity across LSP relationships?~~ **Answered (D8): canonical PAN-unique borrower + per-LSP relationship rows + loan-level snapshots.**
3. ~~Which roles may see PAN…~~ **Answered for PAN (D3): role-based full display on internal detail surfaces, LSP API masked.** Encryption/retention of the same fields: deferred with D4. Aadhaar/bank/mobile policies unchanged (already masked).
4. ~~Maker-checker thresholds/roles?~~ **Model answered (D2): STP-within-caps + exception checker.** *Open sub-item: the actual threshold/budget values need risk sign-off (config knobs in S14).*
5. ~~Payment allocation waterfall?~~ **Answered (D1a): charges → interest → principal, oldest first.** *Open sub-item (D1b): overpayment/advance policy — surplus parks in suspense until decided.*
6. ~~Provider outcome finality?~~ **Engineering model answered (D6): Composite Pay + status-check; timeout = UNKNOWN, never FAILED (S3/S17).** *Open sub-item: bank-contract cutoffs and commercial SLAs.*
7. ~~Partner rate/bulk SLAs?~~ **Answered (D5): per-LSP rate plans + bulk endpoint (S16).** Contractual numbers per partner still come from each contract.
8. ~~Retention schedule?~~ **Answered (D7): default schedule adopted (S18), compliance can retune per class.**
9. What RPO/RTO, peak volume, tenant skew and seasonal burst must launch certification prove?
10. Is LSP security self-service required at pilot or can a tested admin SLA temporarily substitute?
11. Is mobile a supported operations persona or only a responsive convenience surface?
12. Which historic audit streams must remain queryable hot versus archived forensic-only?

---

## 18. Methodology, robustness, and provenance

### Method

- Used the repository graph as an initial index, then verified material claims in current source and current audit/test artifacts.
- Reconciled duplicate IDs across production, database, scalability, LSP, UX, E2E and Fallow findings into canonical root causes.
- Preserved older findings in historical tables rather than silently deleting them.
- Distinguished current assertion/runtime evidence from design risk, business decisions and infrastructure-blocked tests.
- Did not modify application source or claim fixes.

### Robustness checks

- Backend test aggregation was taken from fresh Surefire XML after natural Maven completion.
- Frontend gates ran independently so fail-fast lint did not hide tests/build.
- Fallow JSON `kind`/schema were parsed and verified.
- The security candidate was traced to browser URL/token behavior and classified as a hardening issue, not asserted as exploitable SSRF.
- Old performance data was not presented as current capacity after major remediation.
- Playwright `spawn EPERM` was classified as infrastructure failure, not 46 product defects.
- Dirty-worktree status is disclosed; no comparison to a tagged baseline is implied.

### Final conclusion

The Bhawana LMS is **good enough to continue engineering and controlled synthetic management review**, but **not ready for a limited partner pilot today and unequivocally not ready for real-money production**. The core is worth preserving. The decisive work is durable money workflow design, real servicing ledger behavior, privacy protection, partner operability, capacity proof, production operations and a clean repeatable release pipeline—not a wholesale rewrite.

---

## 19. Independent validation addendum — 2026-07-12 (evening)

Every material claim in this report was independently re-validated on the same dirty working tree (HEAD `f825e81`, both frontend and backend running live). Method: re-execution of every frontend gate; a fresh full backend Maven run; a fresh full frontend Vitest coverage run; direct source audits of every cited money/security/idempotency/architecture path; live authenticated API probes; and a real Chromium browser session against the running app. Validation artifacts live beside the original campaign evidence in `outputs/validation-2026-07-12/` (`revalidation-backend-test.log`, `revalidation-frontend-testcov.log`, `create1.json`, `create2.json`). Synthetic runtime fixtures created during validation are labeled `VAL-0712` (LSP `VAL-0712`, product `VAL-P-0712`, API client `validation-0712-client`, applications `VAL-STALL-1`/`VAL-FULL-1`).

### 19.1 Verdict register

| Claim / ID | Verdict | Evidence |
|---|---|---|
| MNY-01 provider call inside DB transaction | **Accurate at assessment; remediated 2026-07-13** | At assessment: provider inside `@Transactional` before persistence. **Now:** Spec S3 — `disbursement_intent` + out-of-tx provider when `app.disbursement.intent-workflow.enabled=true` (default). Legacy path when flag off. See `docs/implementation-log.md`. |
| MNY-02 exact-installment-only repayment | **Accurate** | `LoanServicingSupportService` throws `PAYMENT_AMOUNT_MISMATCH` unless amount equals installment outstanding exactly; `PARTIALLY_PAID` exists in the domain enum but is unreachable from the public path (ODD-09 also accurate). |
| CTRL-01 single-admin disbursement, no maker-checker | **Accurate** | `POST /{applicationId}/disbursement-requests` is `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` with no second-principal, limit, or step-up concept anywhere in the codebase. `TransitionConfirmDialog` renders no principal/fee/net figures. |
| MOCK-01 unconditional mock adapter + mock endpoints | **Accurate** | `MockLoanDisbursementAdapter` is a plain `@Service` (no profile/conditional); `POST …/disbursement-requests/mock-outcome` is registered unconditionally on the ops controller. |
| DATA-01 mutable beneficiary at payout | **Accurate; deferred 2026-07-15** | Disbursement command still reads live borrower bank fields at initiation; no approval-time snapshot. Spec S5 remains valid but is not scheduled — see `docs/deferred-implementation.md`. |
| IDEM-01 stale-`PENDING` after crash | **Accurate (Partial)** | Claim-before-execute and `releasePendingLspApiIdempotencyRecord` on in-process exception are present; a crash between business commit and `completeLspApiIdempotencyRecord` leaves `PENDING` forever — duplicates poll ~briefly then throw `IDEMPOTENCY_IN_PROGRESS`; the only scheduled job is age-based purge, not recovery. |
| F-S9 no unique intent/reference constraint | **Accurate** | `tran_ref_no` (V98) and `provider_request_id` (V19) carry no unique index; `tranRefNo` is a random UUID substring. |
| SEC-01 PAN raw / Aadhaar+bank masked; localStorage token; PII in JSON payloads | **Partially remediated** | **Token (item 4):** closed by Spec **S11** (2026-07-15) — access JWT memory-only; metadata may remain in `localStorage`. **PAN / plaintext PII / request-log JSON:** still open (D3→S15, D4 deferred). Historical: live LSP API returned raw PAN; `borrowerPanMasked` label mismatch (NEW-04). |
| SEC-02 no AV/quarantine; full in-memory read | **Accurate** | `DocumentUploadPolicy` calls `inputStream.readAllBytes()`; zero malware/CDR/quarantine references in the backend. |
| SEC-03 absolute-URL credential-bearing fetch | **OUTDATED — remediated 2026-07-15 (S7)** | Same-origin gate + `fetchExternal`; see §19.6. |
| CI-01/CI-02, LINT-01..04 | **Accurate — reproduced exactly** | `npm run lint`: 3 errors, 1 warning at the exact cited files/lines. |
| BUILD-01..03 | **Accurate — reproduced exactly** | `npm run build`: the same 3 TypeScript errors (`AuditTable.tsx` TS6133, `detail-page.test.tsx` missing `lastActivity`, `users/api.test.ts` missing `lspId`). |
| Formatting — 10 files | **Accurate — reproduced exactly** | `npm run format:check` rejected the identical 10 files. |
| Typecheck PASS | **Accurate** | `tsc --noEmit` clean while `tsc -b` fails — the report's nuance is correct. |
| Backend 738 tests / 0 fail / 1 skipped | **Accurate — reproduced** | Fresh run: BUILD SUCCESS; Surefire aggregate 134 reports, 738 tests, 0 failures, 0 errors, 1 skipped. |
| Frontend 748/748 pass, 55.79/76.32/64.57 coverage, 69 canvas / 47 Router / 20 `act` warnings | **Accurate for its run** | Original campaign logs verified (counts match exactly). Fresh re-run under heavy machine load produced **2 timeout failures (746/748)** — not a product regression but a new flakiness data point (NEW-03) reinforcing TEST-FE-05. |
| Fallow metrics (492 files, 3,399 fns, 184 cleanup, 8.1084 % dupes, 42 complexity 6/11/25, 90.4 maintainability, 0 circular/boundary) | **Accurate — every number matches the JSON** | Parsed `fallow-combined.json` directly. |
| ARCH-01H/I/J/K/L file sizes | **Accurate** | `LspLoanApplicationApiController` 945 lines, `GlobalExceptionHandler` 700, `AdminReportingService` 585, `AdminApiIdempotencyService` 400, `my-loans/api.ts` 696 — all exact. |
| ODD-01 / ARCH-01M status-union drift | **OUTDATED — remediated 2026-07-15 (S8)** | Frontend `LOAN_ACCOUNT_STATUSES` matches backend eight literals; `ACTIVE` removed. |
| ODD-02/F-S4 dual-write lifecycle streams | **Accurate** | `LoanApplicationStatusWriter` writes both `LoanApplicationStatusTransition` and `LoanApplicationAuditEvent`. |
| ODD-04 overlapping reveal-audit infrastructure | **Accurate** | `loan_application_pii_reveal_audit` and borrower reveal audit repositories coexist. |
| ODD-05/F-S11 webhook CSV on LSP; F-S12 plaintext signing secret | **Accurate** | `Lsp.webhookEventTypes` is a `VARCHAR(500)` CSV column; `webhook_signing_secret` is a plaintext 255-char column. |
| ODD-07 "queued" copy vs synchronous call | **OUTDATED when intent workflow enabled (S3); UX-02 closed (S12)** | Default `intent-workflow.enabled=true` queues provider via worker. Money summary is on the confirmation path (S12). Legacy inline path remains behind the feature flag (tests). |
| F-MNY-02 mega-transaction | **Accurate as historical** | Worker now processes per-application transactions via `LoanDisbursementWorkerProcessor`; unbounded `findByStatus` scans and no lease/claim remain (F-DB-04/F-MNY-03 accurately still open). |
| F-ISO-03 schedulers on every pod | **Accurate** | Plain `@Scheduled` on disbursement/status-check workers; no distributed lock. |
| F-Q8 only idempotency purge exists | **Accurate** | `IdempotencyRecordRetentionWorker` is the only retention worker. |
| ODD-13/E2E-04 hardcoded test credentials | **Accurate** | `e2e/helpers` falls back to `ChangeMe123!` / `DemoPass123!`. |
| **API-01 / LSP-F2 silent intake stall** | **OUTDATED — remediated in the assessed tree** | Runtime-proven: incomplete LSP create returns **422 `BORROWER_REQUIRED_FIELDS_MISSING`** with per-field violations (addressLine1/City/State/Zip, referencePersonName/Number); `BorrowerOnboardingRequirements` is shared by create validation and the auto-approval engine, eliminating the create/progression divergence. The complete-payload path creates successfully. Residual: partner OpenAPI documentation and DTO annotation parity only. |
| **OPS-01A/B Home + Audit failures** | **OUTDATED — resolved by `f825e81`** | `/internal/home/overview` and `/internal/admin/audit-events` return 200 with data; a real Chromium session logged in, rendered Home and Audit Log with **zero console errors**. |
| **ENV-01 bootstrap/login drift** | **Mostly OUTDATED** | Bootstrap login works with the configured `.env` credentials (API + browser). Residual issues are cleanup-grade (stray unused env var; startup-only sync; no ephemeral UAT identity). NEW-01 wipe hazard **closed by S2 (2026-07-13)**. |
| E2E-01 `spawn EPERM` | **Accurate for the prior sandbox; not environmental fact** | Chromium launches normally here; see NEW-02 for the deeper problem (spec drift). |
| TEST-BE-01 environment-coupled test | **OUTDATED — remediated 2026-07-13 (S2)** | See §19.6. Default `mvn test` no longer boots `local` profile against `.env`; `IntegrationTestDatabaseTargetGuard` refuses non-ephemeral JDBC URLs; upload regression runs on Testcontainers. |
| Positive controls (§3/§4/§7/§16) | **Spot-verified accurate** | Strict JSON `@StrictJson` on partner DTOs, structured error envelope with correlation IDs (observed on every error response), Aadhaar/bank masking, claim-before-execute idempotency, pessimistic `findByIdForUpdate` disbursement lock, per-item worker transactions, webhook outbox with retry/permanent-failure classification (observed live in test logs), HttpOnly refresh-cookie architecture (per `http-client.ts` docs and auth flow). |

### 19.2 New findings surfaced by this validation

| ID | Sev. | Finding | Detail |
|---|---:|---|---|
| **NEW-01** | ~~P0~~ **Closed (implemented 2026-07-13)** | ~~**Plain `mvn test` destroys the database that `.env` points at.**~~ `DocumentUploadLocalProfileIntegrationTest` **deleted**; replaced by `DocumentUploadPostgresIntegrationTest` (Testcontainers, `test` profile) plus opt-in `DocumentUploadExternalDbIntegrationTest` (`@Tag("external-db")`, `LMS_IT_EXTERNAL_DB=true`). `IntegrationTestDatabaseTargetGuard` blocks cleaner on non-ephemeral JDBC URLs. See §19.6. |
| **NEW-02** | ~~P1~~ **Closed (S9, 2026-07-15)** | ~~Browser E2E specs stale against shipped login.~~ Env-only credentials; smoke asserts Email/Password; fixtures via globalSetup; see §19.6. |
| **NEW-03** | P2 | **Frontend unit suite is timing-flaky under load.** | Re-run under CPU contention produced 2 timeout failures (5 s default `testTimeout`) in `reports/page.test.tsx` and one other spec; same code passed 748/748 in the quiet campaign run. Reinforces TEST-FE-04/05. Remediation folded into Spec S1. |
| **NEW-04** | P1 (labeling) | **`borrowerPanMasked` is a false label.** | `my-loans/api.ts:262` maps raw backend `panNumber` into a field named `borrowerPanMasked`, with a comment asserting backend masking that does not exist. Whatever PAN policy is chosen (Decision D3), the field name/comment must stop asserting a control that is absent. |
| **NEW-05** / SCH-01 | ~~P1~~ **Closed (implemented 2026-07-15; residuals closed)** | Spec **S20**: first-due window, anchored monthly cadence, horizon, interest vs frozen product rate; product-accepted defaults; partner contract in `docs/partner-schedule-validation.md`; kill-switch removed. |

### 19.3 Execution-ready implementation specifications (technical, non-breaking)

Specs below are for findings that are purely technical and do not require business input. Findings needing product/compliance decisions (maker-checker thresholds, PAN visibility policy, receipt/allocation waterfall, partner rate plans, retention schedule) are deliberately **not** specified here; see §17 and the decision summary delivered with this validation.

---

#### Spec S1 — Restore the frontend release baseline (CI-01/02, LINT-01..04, BUILD-01..03, formatting, NEW-03)

**Root cause / current behavior.** Four in-flight workstreams landed without their gate cleanup: `AuditTable` grew a `filters` prop nobody consumes (LINT-01/BUILD-01); `audit/page.tsx` and `my-loans/detail-page.tsx` accumulate/reset state synchronously inside effects (LINT-02/03, UX-09/10); `DocumentsSection` omits `docLabels` from an effect dependency list (LINT-04/UX-11); two test fixtures were not updated when `MyLoanDetail.lastActivity` and `CreateUserInput.lspId` became required (BUILD-02/03); 10 files (including generated `schema.ts`) are Prettier-dirty; `npm run verify` is fail-fast so lint hides everything behind it.

**Solution.**
1. `AuditTable.tsx`: remove the `filters` prop from the component signature and all call sites (it duplicates state already owned by `page.tsx`); if the intended feature was server-driven filter rendering, that belongs to the query layer, not the table.
2. `audit/page.tsx`: replace the cursor-append effect with derived pagination state — keep `accumulatedRows` in a `useReducer` keyed on `(filtersKey, cursor)` events fired from the query's `onSuccess`/select path, or accumulate in the query cache itself (`keepPreviousData` + explicit page merge in `select`). No `setState` inside `useEffect`.
3. `my-loans/detail-page.tsx`: key servicing state by loan account — either `<ServicingPanel key={loanAccountId}>` so React resets state naturally, or move schedule/payments into query hooks (`useQuery(['servicing', loanAccountId])`) and delete the mirrored `useState`/reset effect entirely (preferred; also reduces the ARCH-01A complexity).
4. `DocumentsSection.tsx`: memoize `docLabels` at its source (`useMemo` on the stable input) and add it to the dependency array.
5. Fixtures: add `lastActivity: null` (and make the fixture a typed builder `makeMyLoanDetail(overrides)`), add `lspId` to the user-create fixture via a `makeCreateUserInput` builder — builders live beside the feature's `test-utils`.
6. Run `prettier --write` on the 9 hand-written files; for `src/lib/api/generated/schema.ts`, make the generator pipe output through Prettier (or add the generated path to `.prettierignore` with a comment explaining determinism), so regeneration can never dirty the gate again.
7. `verify` script: run `lint`, `format:check`, `typecheck`, `test`, `build` as independent steps that all execute and report a combined failure at the end (simple shell aggregation or `npm-run-all --continue-on-error` equivalent).
8. NEW-03: raise Vitest `testTimeout` to 15 000 ms globally (assertion-driven tests are unaffected; only genuine hangs pay it) and fix the 20 `act(...)` warnings by replacing timing-sensitive assertions with `findBy*`/`waitFor`.

**Files.** `frontend/src/features/audit/components/AuditTable.tsx`, `frontend/src/features/audit/page.tsx`, `frontend/src/features/my-loans/detail-page.tsx` (+`.test.tsx`), `frontend/src/features/my-loans/components/DocumentsSection.tsx`, `frontend/src/features/users/api.test.ts`, `frontend/package.json`, `vitest.config.ts`, generator script for `schema.ts`, the 10 Prettier-dirty files.

**Impacts.** None on API/DB/infrastructure. UI behavior identical except fewer redundant renders.

**Migration/compat.** None — internal refactor.

**Validation & rollout.** `npm run verify` green locally and in CI; all 748+ tests pass with zero `act` warnings; `npm run build` produces an artifact. Rollout is an ordinary PR; rollback is revert.

**Acceptance criteria.** Lint 0 errors/0 warnings at `--max-warnings 0`; `format:check` clean including after a fresh `schema.ts` regeneration; `tsc -b` clean; 100 % test pass twice consecutively on a loaded machine; `verify` reports every gate even when one fails.

---

#### Spec S2 — Make the backend test suite incapable of destroying a real database (NEW-01 / TEST-BE-01)

**Status:** **Closed — implemented 2026-07-13.** Implementation record: §19.6.

**Root cause / prior behavior (historical).** `DocumentUploadLocalProfileIntegrationTest` activated `@ActiveProfiles("local")`, which imported `optional:file:../.env[.properties]`, and self-enabled via `@EnabledIf` when `LMS_DB_URL` contained "supabase". `IntegrationTestDatabaseCleaner.cleanIntegrationTestData()` then bulk-deleted ~40 tables in that database. The guard was inverted: the presence of a *live* database URL was treated as permission to destroy it. `@BeforeEach` cleanup ran even when the test method was disabled.

**Solution (three independent layers — all implemented).**
1. **Opt-in, not auto-on:** `DocumentUploadExternalDbIntegrationTest` uses `@EnabledIfEnvironmentVariable(named = "LMS_IT_EXTERNAL_DB", matches = "true")` and `@Tag("external-db")`. Default Surefire run excludes `external-db`; `-Pexternal-it` profile runs only that group.
2. **Cleaner refuses non-ephemeral targets:** `IntegrationTestDatabaseTargetGuard.assertEphemeralTarget(DataSource)` runs at the top of `cleanIntegrationTestData()` and throws unless the JDBC URL is `jdbc:h2:…`, `localhost`/`127.0.0.1` (Testcontainers), or `LMS_IT_EXTERNAL_DB=true` is set (logs a warning naming the remote host). No `system_flag` migration — URL allowlist only.
3. **Default execution on Testcontainers:** `DocumentUploadPostgresIntegrationTest` extends `PostgresDataJpaTestSupport`, uses `@ActiveProfiles("test")` with `DynamicPropertySource` overriding the datasource to ephemeral PostgreSQL. Multipart upload regression runs on every default `mvn test`.

**Files changed (exact).**

| Action | Path |
|---|---|
| **Added** | `backend/src/test/java/com/bhawana/lms/support/IntegrationTestDatabaseTargetGuard.java` |
| **Added** | `backend/src/test/java/com/bhawana/lms/support/IntegrationTestDatabaseTargetGuardTest.java` |
| **Modified** | `backend/src/test/java/com/bhawana/lms/support/IntegrationTestDatabaseCleaner.java` — inject `DataSource`; call guard before any DELETE |
| **Added** | `backend/src/test/java/com/bhawana/lms/web/DocumentUploadPostgresIntegrationTest.java` — default Testcontainers upload regression |
| **Added** | `backend/src/test/java/com/bhawana/lms/web/DocumentUploadExternalDbIntegrationTest.java` — opt-in external-DB variant (`local` profile) |
| **Added** | `backend/src/test/java/com/bhawana/lms/web/DocumentUploadTestSupport.java` — shared seed/helpers for upload tests |
| **Deleted** | `backend/src/test/java/com/bhawana/lms/web/DocumentUploadLocalProfileIntegrationTest.java` |
| **Modified** | `backend/pom.xml` — `<excludedGroups>external-db</excludedGroups>` on Surefire; `external-it` Maven profile |

**Impacts.** No production code change; no API/UI impact; no DB migration. CI keeps upload coverage via Testcontainers (Docker required, same as existing Postgres integration tests).

**Validation evidence (2026-07-13).** `IntegrationTestDatabaseTargetGuardTest` (4 cases): Supabase URL rejected without opt-in; H2 and localhost allowed; remote allowed when `LMS_IT_EXTERNAL_DB=true`. `DocumentUploadPostgresIntegrationTest` passes on Testcontainers. Full `.\mvnw.cmd test` BUILD SUCCESS with repo `.env` present; `DocumentUploadExternalDbIntegrationTest` excluded from default run.

**Acceptance criteria (met).** `mvn test` with `.env` present completes with zero writes to Supabase; upload regression executes on Testcontainers in the default run; explicit opt-in (`-Pexternal-it` + `LMS_IT_EXTERNAL_DB=true`) remains available with a logged warning on remote targets.

---

#### Spec S3 — Durable disbursement intent with out-of-transaction provider call (MNY-01, F-S9, F-MNY-03, partially F-DB-04)

**Root cause / current behavior.** `initiateDisbursement` opens a transaction (pessimistic lock on the application), calls the provider adapter *inside* it, then persists the account status and request log. A crash after provider acceptance but before commit leaves no durable record; retry generates a fresh random `tranRefNo` and can pay twice. `tran_ref_no`/`provider_request_id` have no uniqueness; the worker claims work via unbounded `findByStatus` with no lease, so multiple instances rely solely on the row lock + status check inside the same fragile transaction.

**Solution.** Introduce a `disbursement_intent` state machine and split the flow into three short transactions:
1. **Tx-A (intent):** insert `disbursement_intent` (id, loan_account_id FK, deterministic `tran_ref_no`, amount, payment_mode, beneficiary fields *from the S5 snapshot once it exists*, state `CREATED`, lease_owner NULL, lease_expires_at NULL, attempt_count 0, created_by, correlation_id) and set the account to `DISBURSEMENT_REQUESTED`. Commit. Deterministic reference: derive `tranRefNo` from the intent id (e.g. `"ICI" + base32(intentId).substring(0,13)`) so a retried intent reuses the same provider reference. Migration adds `UNIQUE (tran_ref_no)` on the intent table and a partial unique index `ON disbursement_intent(loan_account_id) WHERE state NOT IN ('SUCCEEDED','FAILED','CANCELLED')` guaranteeing at most one live intent per account.
2. **No-Tx (provider):** load claimable intents with `SELECT … FOR UPDATE SKIP LOCKED WHERE state IN ('CREATED','UNKNOWN') AND (lease_expires_at IS NULL OR lease_expires_at < now()) LIMIT :batch`, stamp lease_owner/expiry in a short claim transaction, **release the connection**, then call the adapter with no transaction held. Timeout/ambiguous responses set in-memory outcome `UNKNOWN` — never `FAILED`.
3. **Tx-B (outcome):** persist the provider verdict on the intent (`REQUESTED`→`SUCCEEDED`/`FAILED_*`/`UNKNOWN` + act code, RRN, payload), write the existing `LoanDisbursementRequestLog` row for continuity, and apply `DisbursementOutcomeApplier` exactly as today. `UNKNOWN` intents are re-claimed by the existing status-check worker (which becomes "reconcile unknown intents via `/composite-status`"), with `attempt_count` incremented atomically (`UPDATE … SET attempt_count = attempt_count + 1` — closes F-MNY-09) and parking to `DISBURSEMENT_PENDING_RECONCILIATION` at the poll cap as today.

`LoanDisbursementCommandService.initiateDisbursement` (admin endpoint) becomes: Tx-A synchronously, then either return "requested" immediately (worker executes step 2/3) or execute steps 2/3 inline *without* a surrounding transaction — recommend the worker path so admin HTTP threads never block on the provider, and fix the dialog copy to genuinely mean "queued" (resolves ODD-07 truthfully).

**Files.** New `domain/DisbursementIntent.java`, `repo/DisbursementIntentRepository.java`, `V###__disbursement_intent.sql`; rework `service/LoanDisbursementCommandService.java`, `service/LoanDisbursementWorkerService.java`/`Processor`, `DisbursementOutcomeApplier` unchanged in contract; `MockLoanDisbursementAdapter` untouched (same seam).

**Impacts.** DB: one new table + indexes (small, hot). API: response of the admin initiate endpoint keeps its shape (application detail) — no partner-visible change while disbursement stays admin-driven. UI: dialog copy already says queued; add intent reference to the confirmation/result (feeds UX-02). Mock-only today, so no real-money risk during the change — this is precisely why it should land **before** the ICICI adapter.

**Migration/compat.** Forward-only table; legacy `loan_disbursement_request_log` rows stay untouched (log remains the immutable attempt journal; intent is the state machine). Existing in-flight `DISBURSEMENT_REQUESTED` accounts at deploy time: a one-shot backfill creates `UNKNOWN` intents from the latest request log per account.

**Validation & testing.** Kill-point integration tests (Testcontainers): crash after Tx-A (intent survives, worker completes, exactly one provider call — assert via adapter spy counter); crash after provider-accept before Tx-B (reconciliation resolves via status query; zero second `requestDisbursement`); duplicate concurrent initiate (second blocks/no-ops on the partial unique index); two workers racing (SKIP LOCKED yields disjoint claims); lease expiry reclaim. Property: for any interleaving, `count(provider calls with same tranRefNo) ≥ 1` and `count(distinct tranRefNo per account lifetime while non-terminal) = 1`.

**Rollout/monitoring/rollback.** Ship behind `app.disbursement.intent-workflow.enabled` (default on in dev/UAT, verify, then remove flag). Monitor: intents by state, oldest `UNKNOWN` age, attempt exhaustion (OBS-01D groundwork). Rollback: disable flag → old inline path (retain for one release only).

**Acceptance criteria.** All kill-point tests green; DB rejects a second live intent per account; no code path holds a DB connection across `requestDisbursement`/`checkStatus`; status-check worker drains injected `UNKNOWN` backlog; dashboard metric for unknown-intent age exists.

---

#### Spec S4 — Idempotency lease and crash recovery (IDEM-01, F-MNY-06 successor)

**Root cause / current behavior.** LSP/admin idempotency claims commit in their own transaction (`PENDING_RESPONSE_*` sentinels), the business action runs in a separate admin-scoped transaction, and completion is written afterwards. A crash between business commit and completion leaves the record `PENDING` forever; concurrent duplicates poll ~2 s then return `IDEMPOTENCY_IN_PROGRESS` indefinitely. In-process failures are handled (release on exception); only process death is not.

**Solution.**
1. Add `lease_owner VARCHAR`, `lease_expires_at TIMESTAMPTZ`, `attempt INTEGER DEFAULT 1` to `lsp_api_idempotency_record` and the admin twin. Claim stamps owner (pod id) + expiry (2× worst-case action time, e.g. 60 s).
2. On replay of a `PENDING` record whose lease has expired: **re-claim** it (`UPDATE … SET lease_owner=:me, lease_expires_at=:new, attempt=attempt+1 WHERE id=:id AND lease_expires_at < now()` — atomic, one winner) and run **recovery** instead of blind re-execution: each idempotent operation registers a `ResultReconstructor` that checks committed business state for the fingerprinted request (e.g. loan application by `(lspId, lspLoanId)`, payment by `(applicationId, idempotencyKey)` natural key that already exists on `loan_payment_transaction`) and, if found, serializes the response from it and completes the record; if absent, executes the action normally.
3. Keep the short in-flight poll for fresh (unexpired) leases; replace the terminal `IDEMPOTENCY_IN_PROGRESS` with it only when the lease is still live.

**Files.** `V###__idempotency_lease.sql`; `service/IdempotencyClaimService.java`, `LspApiIdempotencyService.java`, `AdminApiIdempotencyService.java` (extract the shared lease/recovery core into one collaborator — also addresses ARCH-01K/ODD-10); reconstructors colocated with `LoanApplicationOnboardingService` (create) and `LoanRepaymentCommandService` (payment).

**Impacts.** DB: three columns + index on `(lease_expires_at) WHERE response_status = PENDING`. API: duplicate callers see deterministic replay instead of permanent 409 after a crash — strictly better, non-breaking. No UI impact.

**Migration/compat.** Columns nullable/defaulted; existing `PENDING` rows get `lease_expires_at = now()` in the migration so they become immediately recoverable.

**Validation & testing.** Integration tests: kill-after-business-commit (recovery reconstructs the same response body from another "node"); kill-before-action (re-execution, exactly one resource); expired-lease race between two recoverers (single winner via atomic update); fingerprint mismatch still 409; live-lease duplicate still polls then 409 with `Retry-After`.

**Rollout/monitoring/rollback.** Ordinary PR. Add `idempotency_pending_age_seconds` gauge + alert (OBS-01F). Rollback: revert code; columns are inert.

**Acceptance criteria.** No test scenario yields a permanently wedged key; recovered responses byte-equal originals for the same fingerprint; stale-`PENDING` age stays below the alert threshold in a chaos run.

---

#### Spec S5 — Approval-time beneficiary snapshot (DATA-01 / F-S2 subset)

**Status:** **Deferred — 2026-07-15.** Spec remains execution-ready; not scheduled for the current pass. Rationale and resume criteria: `docs/deferred-implementation.md`. Remains a real-money launch gate unless assumptions change.

**Root cause / current behavior.** Payout uses `borrower.bank_account_number/ifsc` read at disbursement time from the shared borrower row; any later update (any LSP relationship, ops correction) silently redirects an in-flight approved loan.

**Solution.** On the transition into `APPROVED_PENDING_DISBURSAL` (single choke point: `LoanApplicationStatusWriter`), copy `account_holder_name`, `bank_account_number`, `ifsc_code`, `bank_name`, plus `source_verified_at` and a SHA-256 `snapshot_hash`, into new columns on `loan_account` (or a `loan_beneficiary_snapshot` table 1:1 with the account — prefer columns; it is one beneficiary per account by design). Disbursement (S3 Tx-A) reads **only** the snapshot; `DisbursementPreflightValidator` compares snapshot to live borrower and, on divergence, blocks with `BENEFICIARY_DETAILS_CHANGED` (422, machine-readable) requiring an explicit admin "re-affirm beneficiary" action that refreshes the snapshot with full audit (who/when/old-hash/new-hash). *Default is block-and-re-affirm; if compliance later wants dual-approval on re-affirm, that plugs into the maker-checker decision (D2) without schema change.*

**Files.** `V###__loan_account_beneficiary_snapshot.sql`; `domain/LoanAccount.java`, `service/LoanApplicationStatusWriter.java` (populate at approval), `service/LoanDisbursementCommandService.java`/S3 intent creation (consume snapshot), `service/DisbursementPreflightValidator.java` (divergence check), ops endpoint + small UI affordance for re-affirm, `BorrowerBankDetailsUpdateAudit` already exists for the audit trail.

**Impacts.** DB: 6 nullable columns. API: new 422 code on initiate when diverged (documented); new re-affirm endpoint (`POST /{applicationId}/beneficiary-reaffirmation`, SYSTEM_ADMIN). UI: disbursement confirmation shows snapshot values (feeds UX-02); banner when diverged.

**Migration/compat.** Backfill existing `PENDING_DISBURSEMENT/DISBURSEMENT_*` accounts from current borrower values in the migration (documented as "best available at migration time"). Terminal accounts left NULL.

**Validation & testing.** Integration: approve → mutate borrower bank account via LSP B path → initiate blocks with `BENEFICIARY_DETAILS_CHANGED`; re-affirm → initiate proceeds with new values; unchanged borrower → snapshot equals live and proceeds; provider command uses snapshot fields (adapter spy assertion).

**Rollout/monitoring/rollback.** Ordinary PR before real rails. Metric: divergence blocks per week. Rollback: revert; columns inert.

**Acceptance criteria.** Post-approval borrower edits can no longer change the values sent to the provider without an audited re-affirmation; every provider command's beneficiary equals the snapshot at intent-creation time.

---

#### Spec S6 — Mutually exclusive mock/live disbursement modes (MOCK-01, ODD-06)

**Status:** **Deferred — 2026-07-15.** Spec remains execution-ready; not scheduled for the current pass. Rationale and resume criteria: `docs/deferred-implementation.md`. Resume before or with Spec S17 (real ICICI adapter); remains a non-mock / real-money launch gate.

**Root cause / current behavior.** `MockLoanDisbursementAdapter` is an unconditional `@Service` and the `mock-outcome` endpoint is always registered; nothing prevents a production deployment from running the mock rail.

**Solution.** Introduce `app.disbursement.provider = mock | icici` (no default in `application.yml`; `local`/test profiles set `mock`). `@ConditionalOnProperty` selects exactly one `LoanDisbursementAdapter` bean; the mock-outcome endpoint and `MockDisbursementOutcome` request handling move behind the same condition (404 when live). Add a startup `SmartInitializingSingleton` guard: if the active profile set contains `prod`/`staging-live` and provider is `mock`, throw and refuse to start. Expose the active provider in the existing actuator `info`/health detail (name only, no secrets).

**Files.** `service/MockLoanDisbursementAdapter.java`, `web/LoanApplicationOpsController.java` (mock-outcome mapping → separate `@ConditionalOnProperty` controller), new `config/DisbursementProviderGuard.java`, `application*.yml`, one test per mode.

**Impacts.** None functional in current environments (they all set `mock`). Live adapter slot-in later requires only the property.

**Migration/compat.** Set `app.disbursement.provider=mock` in `.env`/local config as part of the PR so local boots keep working.

**Validation & testing.** Context-run tests: `mock` mode registers mock adapter + endpoint; `icici` mode (with a stub bean) hides the endpoint (404 assertion); `prod`+`mock` context fails to start with a clear message.

**Acceptance criteria.** It is impossible to boot a prod-profiled instance with the mock rail; the running mode is observable; mock endpoints are absent from live deployments' route table.

---

#### Spec S7 — Same-origin credential policy in the frontend HTTP client (SEC-03, ARCH-01G slice)

**Root cause / current behavior.** `buildUrl` returns absolute `http(s)` inputs verbatim and `performFetch` attaches the bearer token to whatever URL results, so any future caller (or injected value reaching a `path` parameter) exfiltrates the token.

**Solution.** In `performFetch`, resolve `new URL(buildUrl(path), API_BASE_URL)` and require `url.origin === new URL(API_BASE_URL).origin` before attaching `Authorization`/`Idempotency-Key`/`credentials:"include"`; otherwise throw `new Error("Refusing cross-origin authenticated request")`. Provide an explicit named export `fetchExternal(url, init)` (no credentials, no cookie include) for any legitimate future external fetch. Split `performFetch` into `resolveUrl` → `applyAuth` → `execute` (starts the ARCH-01G decomposition without behavior change).

**Files.** `frontend/src/lib/api/http-client.ts` + new `http-client.test.ts` cases.

**Impacts.** None — no current caller passes absolute URLs (verified). Breaking only for hostile/buggy future code, which is the point.

**Validation & testing.** Unit tests: `https://attacker.example/x` throws before any fetch (mock fetch asserts zero calls); same-origin absolute URL passes; relative path unchanged; unauthenticated requests to external origins via `fetchExternal` carry no auth header and `credentials:"omit"`.

**Acceptance criteria.** No code path can attach the access token or session cookie to a non-`VITE_API_BASE_URL` origin; tests enforce it.

---

#### Spec S8 — One generated loan-account status vocabulary (ODD-01 / ARCH-01M)

**Root cause / current behavior.** `frontend/src/schemas/loan-account.ts` hand-declares `["PENDING_DISBURSEMENT","ACTIVE","CLOSED","FORECLOSED"]`; the backend enum has 8 states and no `ACTIVE`. Casts hide the drift; badges/actions can misrender real states like `DISBURSEMENT_FAILED`.

**Solution.** The repo already generates `src/lib/api/generated/schema.ts` from OpenAPI. Export the `LoanAccountStatus` union from the generated types; replace the hand-written Zod enum with `z.enum(GENERATED_LOAN_ACCOUNT_STATUSES)` derived from one generated constant; delete `ACTIVE`. Update `statusBadgeMeta.ts` to be `Record<LoanAccountStatus, …>` so the compiler forces a badge entry for every real state (the existing StatusBadge test already asserts exhaustiveness — it will drive the fix). Audit usages for `ACTIVE` comparisons and map them to the real semantic (`DISBURSED`/`UNDER_REPAYMENT` at the application level).

**Files.** `frontend/src/schemas/loan-account.ts`, `frontend/src/components/app/status/statusBadgeMeta.ts` (+ test), any `ACTIVE` comparison sites, OpenAPI generation script.

**Impacts.** UI-only; strictly more truthful rendering. No API/DB change.

**Validation & testing.** Existing exhaustive badge test; new schema test parsing every backend enum literal; grep-gate (`ACTIVE` absent from loan-account status code); typecheck as the real enforcement.

**Acceptance criteria.** Frontend compiles against the generated union; every backend `LoanAccountStatus` literal round-trips through the Zod schema; no cast between account-status types remains.

---

#### Spec S9 — Reproducible E2E harness (E2E-02/03/04/05, NEW-02, ODD-13)

**Root cause / current behavior.** Browser specs assert UI removed by the F1 login redesign (`System roles` quick-fill); credentials fall back to hardcoded guesses that match no environment; Phase 8 throws when `E2E_APPLICATION_ID` is unset (9 tests never run); the Python harness has no pinned requirements.

**Solution.**
1. Rewrite `e2e/helpers` login to drive the real form (email/password labels — validated selectors from this session's live login) reading **only** `E2E_ADMIN_EMAIL`/`E2E_ADMIN_PASSWORD` (no fallback secrets; fail fast with a clear message when unset). Update `smoke.spec.ts` and role-based helpers to the shipped login page.
2. Replace seed-dependent fixtures with API-created ones: a `globalSetup` that logs in as admin, creates an `E2E-`-prefixed LSP/product/client/application via the public APIs (the exact sequence proven in this validation), exports ids via test fixtures, and a `globalTeardown` that invalidates/marks them. Phase 8 uses the created application id; if creation fails, `test.skip` with the reason string, never a throw that kills the project.
3. Check in `scripts/indep-e2e/requirements-e2e.txt` (pin `requests`, exact versions) and a one-line bootstrap (`py -m pip install -r requirements-e2e.txt`).
4. Document the one supported invocation in `docs/` (`E2E_ADMIN_PASSWORD=… npm run e2e`) and wire it into CI on a browser-capable worker.

**Files.** `frontend/e2e/helpers/*.ts`, `frontend/e2e/*.spec.ts`, `frontend/playwright.config.ts` (globalSetup/teardown), `scripts/indep-e2e/requirements-e2e.txt`, docs.

**Impacts.** Test-only. Creates clearly-labeled synthetic tenants in whatever environment it points at (acceptable for dev/UAT; production runs are out of scope by policy).

**Validation & testing.** Full `npm run e2e` green twice consecutively against the local stack from a fresh clone + documented env vars; deliberately unset password produces one clear preflight failure, not 55 cascading ones.

**Acceptance criteria.** Zero hardcoded credentials in the repo; zero specs referencing removed UI; 0 not-run-due-to-missing-config tests (they either run or self-skip with printed reason); Python harness installs deterministically.

---

#### Spec S10 — Regression guards for the restored Home/Audit/login surfaces (OPS-01A/B, ENV-01 residuals)

**Root cause / current behavior.** Home, Audit and bootstrap login now work, but the defects recurred twice historically (raw-JDBC `Instant` binds; DB state drift) and nothing guards them. `.env` carries an unused `APP_SECURITY_BOOTSTRAP_LOGIN_PASSWORD`; bootstrap sync runs only at startup.

**Solution.**
1. Backend regression tests on PostgreSQL (Testcontainers, not H2 — the whole failure class was H2-masked): call `HomeDashboardService.overview` and the audit explorer query with real `Instant` parameters against pg; assert 200-path.
2. Playwright protected-route canary (part of the S9 suite): login → `/home` renders KPI cards → `/audit` renders rows — tagged `@canary`, runnable standalone (`npm run e2e -- --grep @canary`) as the post-deploy smoke.
3. Delete `APP_SECURITY_BOOTSTRAP_LOGIN_PASSWORD` from `.env`/docs; document `APP_SECURITY_BOOTSTRAP_PASSWORD` as the single input.
4. Add an admin-only `POST /internal/system/bootstrap-sync` (SYSTEM_ADMIN, audited) that re-runs the existing `LocalBootstrapAdminSyncService` logic on demand, so a wiped/drifted environment can be healed without a restart (this validation had to boot a throwaway instance to do it).

**Files.** New backend test class; `e2e/canary.spec.ts`; `.env`, `docs/`; `web/SystemController.java` + service wiring for the sync endpoint (reuse the runner's logic; no duplication).

**Impacts.** One new admin endpoint (internal, audited, idempotent). No schema change.

**Validation & testing.** pg-backed tests red on a reverted `f825e81` (proves they guard the actual bug); canary green against the live stack; sync endpoint restores a deliberately deleted bootstrap user in a Testcontainers test.

**Acceptance criteria.** Reverting the `Instant` fix breaks CI; the canary is part of the deploy checklist; a wiped `app_user` table is recoverable via one authenticated call.

---

#### Spec S11 — Access token out of `localStorage` (SEC-01 item 4)

**Root cause / current behavior.** `session-storage.ts` persists the entire session object — access token included — in `localStorage`; any XSS gains a durable, exfiltratable token. The refresh token is already an HttpOnly/Secure/SameSite-Strict cookie, and `http-client.ts` already supports a refresh callback — the hard part is done.

**Solution.** Keep the session *metadata* (username, roles, expiry) in `localStorage` for UX continuity, but hold the access token **only in module memory** (`sessionCache.accessToken` without the storage write — strip it in `saveStoredSession`, never rehydrate it). On page load, treat the token as absent and let the existing 401→refresh path (HttpOnly cookie) mint a new access token transparently; `loadStoredSession` keeps rendering the shell optimistically from metadata while refresh completes. Logout clears cookie via existing endpoint. This is the standard memory+refresh-cookie pattern; no BFF required now (a BFF remains a later option, not a prerequisite).

**Files.** `frontend/src/lib/api/session-storage.ts`, `features/auth/auth-service.ts` (bootstrap-refresh on app start), `App` auth provider; tests in `session-storage`/auth flows.

**Impacts.** Behavior: a hard reload now performs one silent refresh round-trip before the first authenticated call (tens of ms). Multi-tab: each tab refreshes independently — the backend already supports concurrent refresh per cookie (verify; if single-use rotation is enforced, serialize via a `BroadcastChannel` lock — include in testing). No backend change expected.

**Migration/compat.** First deploy: existing stored sessions have a persisted token — `loadStoredSession` must discard the `accessToken` property and delete it from storage (one-line sanitizer). No user-visible logout.

**Validation & testing.** Unit: `saveStoredSession` never writes `accessToken` to storage (assert on storage mock); reload flow acquires a token via refresh (mocked 401→refresh→retry already has tests to extend). Manual/Playwright: login → hard reload → authenticated page loads without re-login; XSS simulation (`localStorage` dump) contains no token.

**Rollout/monitoring/rollback.** Ordinary PR; watch refresh-endpoint rate (expect +1 per page load). Rollback: revert.

**Acceptance criteria.** No access token at rest in any browser storage; reload keeps the user signed in; refresh volume within expected bounds.

---

#### Spec S12 — Truthful money summary in the disbursement confirmation (UX-02 display slice only)

**Root cause / current behavior.** The confirmation dialog shows beneficiary details but not principal, processing fee, net transfer, payment mode, or reference; the operator authorizes a money movement without seeing the amounts. (Maker-checker itself is Decision D2 and **not** part of this spec.)

**Solution.** Backend already computes fee/net (`netDisbursalAmount`, ADR 0004). Expose a read-only preflight: `GET /{applicationId}/disbursement-preview` (SYSTEM_ADMIN) returning principal, processingFee, netDisbursalAmount, paymentMode (IMPS/NEFT by threshold), beneficiary snapshot fields (masked account, holder name, bank/IFSC), loanId/externalLoanId — assembled from existing services, no new state. The dialog fetches it on open and renders an immutable summary table; the confirm button stays disabled until the preview loads; the post-confirm toast/result shows the intent/tran reference (from S3 when available, request-log reference until then).

**Files.** `web/LoanApplicationOpsController.java` (+ response record in the ops responses class), `service/DisbursementPreflightValidator`/small assembler; `frontend` dialog component used by `DetailHeader` disbursement path (+ tests).

**Impacts.** One new read-only endpoint; no schema change; no partner impact.

**Validation & testing.** Backend test: preview figures equal the amounts the command service would use (shared calculation, not duplicated — extract the fee/net computation used by both). FE test: dialog renders all fields; confirm disabled during load/error.

**Acceptance criteria.** An operator cannot reach the confirm action without principal/fee/net/beneficiary on screen; the displayed net equals the amount sent to the adapter in the same flow (single source of calculation).

### 19.4 Business decisions — resolved 2026-07-13 (owner review)

The product owner resolved the open decisions on 2026-07-13. Decided items now have execution-ready specs in §19.5; two sub-items remain deliberately pending.

| # | Finding(s) | Decision (2026-07-13) | Spec |
|---|---|---|---|
| D1 | MNY-02 / F-S10 / F-MNY-08 | **LMS is the authoritative system of record** (§17 Q1 answered). Build the full receipt/allocation/suspense/reversal ledger. | **S13** |
| D1a | Allocation waterfall | **Charges → interest → principal, oldest-due installment first** (standard India NBFC order; charges bucket present but zero until charge-bearing products exist). | S13 |
| D1b | Overpayment/advance policy | **PENDING — deliberately deferred.** Until decided, S13 parks any surplus in suspense (never auto-applies, never rejects); moving suspense→auto-advance later is purely additive. | S13 (interim rule) |
| D2 | CTRL-01 maker-checker | **STP within limits + human checker for exceptions:** worker straight-through-disburses in-policy loans under hard caps; human checker queue for above-threshold amounts, post-failure retries, beneficiary re-affirmations, and all manual initiations (maker ≠ checker always). Threshold values are config knobs pending risk sign-off. | **S14** |
| D3 | SEC-01(3)/UX-04/G1/NEW-04 PAN policy | **Role-based full display:** SYSTEM_ADMIN/OPS see full PAN on *detail* surfaces (lists stay masked per the earlier G1 decision), with page-level access audit; **LSP API always masked**. Documented as approved policy. | **S15** |
| D4 | SEC-01(1) encryption scope | **PENDING — wait for cloud/KMS target.** No PII encryption work until the infrastructure platform is chosen so keys live in a real KMS from day one. **Explicitly recorded as an accepted open risk through UAT**; plaintext-PII findings (F-S1/F-01) stay open and remain a real-money launch gate. | — (blocked on infra decision) |
| D5 | F-API-02/F-TEN-02/F-API-05 partner quotas | **Per-LSP rate plans + idempotent bulk-create endpoint** (default tier stays 60/min). | **S16** |
| D6 | BUS-01 provider model | **ICICI Composite Pay + composite-status, LMS-initiated per-loan payouts** with daily book-to-bank reconciliation — exactly the seam the mock and Spec S3 already model. Commercial cutoffs/SLAs still need the bank contract, but engineering is unblocked. | **S17** |
| D7 | F-Q8/DATA-02/F-DB-02 retention | **Adopt default schedule now, refine with compliance:** financial 8 y post-closure (archive, not delete), audit/auth 2 y hot + archive, webhook deliveries 180 d, refresh tokens 30 d past expiry, idempotency 90 d (live). All windows per-class config. | **S18** |
| D8 | F-D1/D2/D5, F-S2 borrower identity | **Canonical borrower + per-LSP relationship rows + loan-level snapshots:** one PAN-unique canonical identity (dedupe/exposure view preserved); LSP-scoped data moves to relationship rows; S5 snapshots keep money operations immune to profile edits. **Partial 2026-07-15:** Slice A shipped (`borrower_lsp_relationship` + dual-write); residual drop of access collection / normalizer still open. | **S19** |

### 19.5 Implementation specifications from the 2026-07-13 decisions

---

#### Spec S13 — Receipt/allocation/suspense/reversal ledger (MNY-02, F-S10, F-MNY-08; decisions D1/D1a, interim D1b)

**Status:** **Deferred — 2026-07-15.** Spec remains execution-ready (D1/D1a decided; D1b interim suspense); not scheduled for the current pass. Rationale and resume criteria: `docs/deferred-implementation.md`. Remains a collections system-of-record / real-receipt launch gate.

**Root cause / current behavior.** The posting API accepts only an amount exactly equal to one installment's outstanding (`PAYMENT_AMOUNT_MISMATCH` otherwise); `PARTIALLY_PAID` exists in the domain but is unreachable; there is no receipt entity, no suspense, no bounce/reversal representation. Real collections (partial, bunched, advance, NACH returns) cannot be booked, so the LMS cannot be the system of record it is now decided to be.

**Design principle.** Separate **cash** (what arrived) from **application** (what it paid down). Money facts are immutable; corrections are compensating entries, never updates.

**Data model** (one migration, forward-only):
- `payment_receipt` — id, loan_account_id (nullable only for future unmatched-credit ingestion; required for API-created receipts), lsp_id, amount `NUMERIC(19,2) CHECK (amount > 0)`, value_date, channel (existing `LoanPaymentChannel`), bank_reference, payer_name/note, state (`RECEIVED`→`ALLOCATED` | `PARTIALLY_ALLOCATED` | `REVERSED`), suspense_amount `NUMERIC(19,2) DEFAULT 0 CHECK (suspense_amount >= 0)`, idempotency_key, created_by, correlation_id, created_at. Uniques: `(loan_account_id, idempotency_key)`; partial unique `(channel, bank_reference) WHERE bank_reference IS NOT NULL AND state <> 'REVERSED'` (duplicate bank credits cannot double-book).
- `receipt_allocation` — id, receipt_id FK, installment_id FK, component (`CHARGES`|`INTEREST`|`PRINCIPAL`), amount (positive for allocation, negative for reversal entries), entry_type (`ALLOCATION`|`REVERSAL`), sequence, created_at. Append-only; no UPDATE/DELETE path in code.
- `receipt_reversal` — id, receipt_id FK, reason_code (`NACH_BOUNCE`|`BANK_RECALL`|`OPS_CORRECTION`|`CHARGEBACK`), reason_note, reversed_by, created_at. One active reversal per receipt (unique on receipt_id).

**Allocation engine.** A pure, side-effect-free `ReceiptAllocator.allocate(receiptAmount, dueInstallments) → List<AllocationLine> + surplus`. Waterfall per D1a: for each installment oldest-due-first, pay charges (zero today), then remaining `interestDue − paidInterest`, then `principalDue − paidPrincipal`. The existing per-installment fields (`paidPrincipal`, `paidInterest`, `outstandingAmount`, `PARTIALLY_PAID` status via `applyPayment`) are the projection the allocator writes through, under the existing pessimistic installment lock + `@Version`. **Interim D1b rule:** surplus after all due components → `suspense_amount` on the receipt; nothing auto-applies and nothing is rejected. Suspense is surfaced in servicing reads and daily reconciliation totals. When D1b is decided, an `applySuspense` command consumes it — additive change only.

**Command surface.**
- `POST /internal/ops/loan-applications/{id}/receipts` (SYSTEM_ADMIN): amount, valueDate, channel, bankReference?, targetInstallmentId? (optional pin for the legacy flow), Idempotency-Key mandatory. Runs: validate eligibility (reuse `validateRepaymentEligibility`) → lock installments → allocate → persist receipt+allocations → update installment projections → recompute account/application status (existing `allInstallmentsSettled` path) → webhook (`PAYMENT_RECEIVED` payload gains an additive `allocations[]` + `suspenseAmount`).
- `POST /internal/ops/receipts/{receiptId}/reversal` (SYSTEM_ADMIN): reason code/note. Locks affected installments, writes negative `REVERSAL` allocation rows mirroring the originals, restores `paidPrincipal/paidInterest/outstandingAmount`, recomputes installment status (PAID→PARTIALLY_PAID/PENDING as arithmetic dictates), reopens account/application if it had closed (`CLOSED→UNDER_REPAYMENT`, audited), marks receipt `REVERSED`, emits webhook (`PAYMENT_REVERSED` — new event type, additive).
- **Back-compat:** the existing exact-match endpoint remains, reimplemented as a thin wrapper that creates a receipt pinned to the target installment; its `PAYMENT_AMOUNT_MISMATCH` rule is dropped only when the caller sends the new `allocationMode=WATERFALL` flag — default behavior is unchanged until the frontend/partners migrate (two-release deprecation).

**Migration/backfill.** Existing `loan_payment_transaction` rows are backfilled as `payment_receipt` + one `receipt_allocation` each (component split derived from the installment's paid fields; where ambiguous, `PRINCIPAL`-labeled single line with a `backfilled=true` marker). `loan_payment_transaction` keeps receiving a compatibility row per receipt for one release (dual-write), then becomes read-only history.

**Files.** New domain/repo/service (`PaymentReceipt`, `ReceiptAllocation`, `ReceiptReversal`, `ReceiptAllocator`, `ReceiptCommandService`); `V###__payment_receipt_ledger.sql`; rework `LoanRepaymentCommandService` into the wrapper; `LoanServicingSupportService` gains reversal recompute; ops controller + responses; FE servicing tabs read allocations/suspense (additive columns); webhook payloads/`WebhookEventType.PAYMENT_REVERSED` + V### enum migration mirroring the V99 pattern.

**Invariant tests (gate for merge).** Property tests: `receipt.amount = Σ allocations(entry_type=ALLOCATION) − Σ |REVERSAL| + suspense_amount` for every generated sequence; installment `outstanding ≥ 0` and `paid* ≤ due*` always; reversal of the last receipt restores the exact pre-receipt projection (byte-equal fields); concurrent different-key receipts on one loan never over-allocate (existing lock test pattern extended); duplicate `(channel, bank_reference)` rejected with 409.

**Rollout/monitoring/rollback.** Feature-flag the new endpoints (`app.ledger.receipts.enabled`, default on in dev/UAT). Daily job publishes `Σ receipts − Σ reversals − Σ allocated − Σ suspense` per tenant (must be 0) into ops alerts (foundation for OBS-01E). Rollback: disable flag (wrapper endpoint keeps working); ledger tables are additive so no down-migration needed.

**Acceptance criteria.** All invariant tests green; a partial receipt, a bunched 3-EMI receipt, an advance receipt (→suspense), a NACH bounce, and an ops correction each round-trip through API → allocations → servicing view → webhook with correct balances; exact-match legacy flow regression-green; daily balance control reports zero drift on seeded portfolio.

---

#### Spec S14 — Disbursement authorization: STP caps + exception maker-checker (CTRL-01; decision D2)

**Root cause / current behavior.** One SYSTEM_ADMIN (or the unattended worker) can move any approved amount with no second principal, limit, or velocity control; the confirmation shows no amounts (S12 covers display).

**Solution.** Builds on the S3 intent machine — S3 is a prerequisite.
1. **Policy config** (`app.disbursement.authorization.*`, all hot-reloadable via `@ConfigurationProperties`): `stp-max-amount` (per-loan ceiling for unattended disbursement; **placeholder default ₹1,00,000 — value requires risk sign-off, tracked as config not code**), `per-lsp-daily-budget`, `per-lsp-hourly-velocity`, `global-daily-budget`.
2. **Intent routing:** at intent creation (S3 Tx-A) the policy engine stamps `authorization_mode`: `AUTO` when worker-created, in-policy, first attempt, snapshot unchanged; `PENDING_APPROVAL` when any of: amount > `stp-max-amount`; intent is a retry after a failed/unknown attempt; beneficiary was re-affirmed since approval (S5); initiation is manual. Budget/velocity caps are enforced at creation for **both** modes (hard stop, `DISBURSEMENT_BUDGET_EXCEEDED` 422) — defence in depth even if routing logic regresses.
3. **Approval records:** `disbursement_approval` (intent_id unique, maker_username, checker_username, decision `APPROVED|REJECTED`, reason, decided_at, checker_ip, correlation). Service rule + DB `CHECK (maker_username <> checker_username)`. Maker = intent creator (worker intents needing approval get maker `SYSTEM_DISBURSEMENT_WORKER`; any SYSTEM_ADMIN may check). Checker role is SYSTEM_ADMIN initially; a dedicated `DISBURSEMENT_APPROVER` role can be introduced later without schema change.
4. **Queue surface:** `GET /internal/ops/disbursement-approvals?state=PENDING` (paginated, oldest first, S12 money summary embedded) + `POST /internal/ops/disbursement-approvals/{intentId}` with decision/reason. Worker executes only `AUTO` or `APPROVED` intents; `REJECTED` intents transition the account to `DISBURSEMENT_FAILED` with reason (existing state, audited).
5. **UI:** approval queue page (reuses DataTable + S12 summary component); the manual initiate dialog becomes "submit for approval" when the policy routes to `PENDING_APPROVAL`, with truthful copy.

**Files.** `V###__disbursement_approval.sql`; policy engine class beside S3's intent service; ops controller/responses; worker gate condition; FE queue page + dialog wording; audit via existing disbursement outcome audit stream (add `authorization_mode`, approval reference).

**Impacts.** No partner-visible change (disbursement remains admin/worker-driven). Ops workflow gains one queue. Mock adapter unaffected.

**Migration/compat.** Existing in-flight intents at deploy default to `AUTO` (they were created under the old policy); flag-guard the routing (`app.disbursement.authorization.enabled`) so demo environments can run permissive until UAT of the queue completes.

**Validation & testing.** Same-principal check rejected (service + DB level); above-threshold worker intent lands in queue and is never auto-executed (worker loop test); budget exhaustion blocks creation concurrently (two threads, one budget slot — atomic `UPDATE … WHERE spent + :amt <= budget` accounting row per LSP/day); approval → execution → immutable audit chain assertions; rejection path reaches `DISBURSEMENT_FAILED` with reason.

**Rollout/monitoring/rollback.** Enable in UAT first; dashboard: pending-approval count/age, STP vs checked ratio, budget consumption (feeds OBS-01D). Rollback: disable flag → all intents `AUTO` (pre-decision behavior), table inert.

**Acceptance criteria.** No single principal can both create and approve the same above-threshold intent (proven at DB level); unattended path cannot exceed configured caps under concurrency; every executed intent carries either `AUTO`+in-policy proof or a maker≠checker approval row.

---

#### Spec S15 — PAN policy implementation: masked partner API, role-based full display on internal detail surfaces (SEC-01 item 3, UX-04, G1, NEW-04; decision D3)

**Status:** **Deferred — 2026-07-15.** Spec remains execution-ready (D3 decided); not scheduled for the current pass. Owner accepted current PAN display behaviour for management-review / synthetic UAT. Rationale and resume criteria: `docs/deferred-implementation.md`. Remains a partner-pilot / compliance gate when masked LSP + list surfaces are required.

**Root cause / current behavior.** LSP API and admin surfaces return raw PAN; Aadhaar/bank are masked; the frontend maps raw PAN into a field named `borrowerPanMasked` with a comment asserting non-existent backend masking.

**Approved policy (D3, 2026-07-13).** SYSTEM_ADMIN/OPS see full PAN on **detail** surfaces (borrower 360, application detail) — lists remain masked per the earlier G1 decision; every full-PAN detail response is access-audited at page level; the **LSP API never returns full PAN**.

**Solution.**
1. New `PanMasking.mask(pan)` in `common/pii` beside `AadhaarMasking`: `AB•••••34F` → keep first 2 and last 3 chars, mask 5 (enough for human matching, useless for identity theft). Unit-tested for null/short/invalid inputs.
2. **LSP responses** (`LspLoanApplicationResponses`, borrower endpoints): wrap PAN with `PanMasking.mask` exactly where `AadhaarMasking.mask` is applied today (lines 39/102 pattern). Contract-breaking for partners **by approved policy**; documented in the partner OpenAPI/changelog as a compliance change (no version bump — masked value remains a string of the same field name).
3. **Internal admin:** detail endpoints (`BorrowerAdminController` detail, `LoanApplicationOpsResponses` detail) keep full PAN for SYSTEM_ADMIN/OPS; **list/search endpoints switch to masked** (borrower list response + ops list). Detail responses containing full PAN write one access-audit row per request into the existing borrower PII reveal audit stream with `purpose=INLINE_DETAIL_VIEW` (reuses `borrowerPiiRevealAudit` infra — no new table; volume is bounded by human detail-page views).
4. **Frontend (NEW-04):** rename `borrowerPanMasked`→`borrowerPan` in `my-loans/api.ts` and delete the false comment (LSP UI now genuinely receives masked values from the server, so the LSP-side display needs no change); admin borrower detail continues rendering the full value it receives; borrower list renders masked (server-driven, delete any client-side full-PAN rendering in lists).
5. **Docs:** one paragraph in `CONTEXT.md`/partner docs recording the approved role/surface matrix.

**Files.** `common/pii/PanMasking.java` (+test), `LspLoanApplicationResponses.java`, `LspBorrowerApiController` responses, `BorrowerAdminController` (list masked, detail audited), `LoanApplicationOpsResponses.java` (list vs detail split), FE `my-loans/api.ts`, `borrowers/api*.ts` list types, docs.

**Impacts.** Partner-visible field content change (full→masked PAN) — approved. MIS CSV: **unchanged** (intentionally raw per the earlier bank-details decision) unless compliance says otherwise later. No DB change.

**Validation & testing.** Contract tests: every `/lsp/**` response containing `panNumber` matches the mask regex (add to the existing controller test matrix); admin list masked / detail full per role; one audit row per full-PAN detail response (repository assertion); FE typecheck catches all renamed-field usages.

**Rollout/monitoring/rollback.** Single PR; notify the (currently synthetic) partner channel of the contract change. Monitor reveal-audit volume for anomalies (feeds SEC-AUD-01 later). Rollback: revert (no data change).

**Acceptance criteria.** `grep`-level guarantee: no `/lsp/**` serializer references `getPan()` unmasked; borrower/application lists never carry a full PAN; every full-PAN response is attributable to a user in the audit stream; `borrowerPanMasked` no longer exists in the frontend.

---

#### Spec S16 — Per-LSP rate plans and idempotent bulk intake (F-API-02, F-API-05, F-TEN-02 partial, F-MNY-10 partial; decision D5)

**Root cause / current behavior.** One global 60/min LSP write limit; no per-partner plan, no read quotas, no bulk path. A batch partner must drip 10 K applications over ~3 h through 429s.

**Solution.**
1. **Rate plans:** new columns on `lsp` (or `lsp_rate_plan` 1:1 table — prefer columns; it is one plan per LSP): `write_rpm INT NOT NULL DEFAULT 60`, `read_rpm INT NOT NULL DEFAULT 600`, `burst_multiplier NUMERIC(3,1) DEFAULT 1.5`, `bulk_rows_per_min INT DEFAULT 0` (0 = bulk disabled). Admin CRUD on the existing LSP admin controller (+audit rows via the LSP audit stream); FE field group on the LSP detail page.
2. **Limiter integration:** the existing Redis limiter keys by `lspId`; it now resolves the plan via the auth principal cache (invalidated on plan update — same mechanism as status/token-version revocation, ≤60 s propagation). Fail-closed for partner roles when `lspId` is absent from the token (closes F-TEN-05 in passing). 429 responses keep `Retry-After`.
3. **Bulk endpoint:** `POST /api/v1/lsp/loan-applications/bulk` — `@StrictJson` body `{applications: [ …up to 500 rows of the existing create request… ]}`, mandatory `Idempotency-Key` (batch-scoped). Each row executes through the **existing single-create service** in its own transaction (all current validation incl. `BORROWER_REQUIRED_FIELDS_MISSING` applies per row); response `207`-style: `[{index, status: CREATED|FAILED, applicationId?, error?: {code, violations}}]`. Batch idempotency stores the per-row outcome array via the existing LSP idempotency record (S4 lease/recovery applies); replay returns the stored array; a payload-fingerprint mismatch is the existing 409. Rate accounting: a bulk call consumes `rows` from `bulk_rows_per_min` and 1 from `write_rpm`.
4. **Read quotas (F-API-05):** apply `read_rpm` to `/lsp/**` GETs through the same limiter (previously unlimited).

**Files.** `V###__lsp_rate_plan.sql`; `Lsp.java`; rate-limit config/service; `LspAdminController` + responses + FE; new bulk method on `LspLoanApplicationApiController` (or a dedicated `LspBulkIntakeController` to respect the ARCH-01H size concern) + service orchestrator; partner OpenAPI additions.

**Impacts.** Additive API; existing partners unaffected (defaults preserve today's behavior; bulk off until enabled per LSP). Redis remains the limiter backend — its failure policy is still F-ISO-01 (unchanged scope).

**Migration/compat.** Column defaults reproduce current behavior exactly.

**Validation & testing.** Plan-change propagation ≤60 s (integration test with cache invalidation); per-LSP isolation under concurrent load (LSP A at limit does not affect LSP B — two-tenant limiter test); bulk: 500-row batch with mixed valid/invalid rows yields per-row outcomes, valid rows persist, invalid rows do not, replay byte-equal, crash-mid-batch recovery via S4 completes or reconstructs; reads throttle at `read_rpm`.

**Rollout/monitoring/rollback.** Ship with bulk disabled everywhere; enable per partner. Metrics: 429 rate per LSP, plan utilization, bulk rows/outcome mix (feeds F-TEN-01 telemetry). Rollback: defaults restore global behavior; bulk flag off.

**Acceptance criteria.** A 10 K-application batch completes in minutes via bulk with full per-row accounting and zero duplicate loans under retry; no partner can exceed its plan; read endpoints are no longer unmetered.

---

#### Spec S17 — Real ICICI Composite Pay adapter (BUS-01 → decided D6; F-ISO-02 for this dependency)

**Root cause / current behavior.** Only `MockLoanDisbursementAdapter` exists. Decision D6 fixes the target: per-loan Composite Pay payouts, composite-status polling, daily book-to-bank reconciliation — the exact seam the mock models.

**Solution.**
1. **`IciciCompositePayAdapter implements LoanDisbursementAdapter`**, activated by `app.disbursement.provider=icici` (S6 exclusivity). Same command/result records — no caller changes.
2. **Transport/security:** dedicated `RestClient` with connect/read timeouts (5 s/30 s), mTLS keystore + API credentials from environment/secret manager (upgraded to KMS when D4 resolves — config indirection only), request/response encryption+signing per ICICI's API spec (isolated in a `IciciCryptoCodec` so certification changes don't touch flow logic). Secrets never logged; payload logging redacts account numbers via the existing masking utils.
3. **Outcome mapping:** bank ActCode/status → `DisbursementDisposition` + `DisbursementDeclineKind` in one table-driven mapper (mirrors `MockIciciDisbursementScenario` vocabulary; TD→retryable, BD→terminal). **Any timeout, 5xx, or unparseable response maps to `UNKNOWN` — never `FAILED`** — feeding S3's reconciliation loop; the pay call is never re-issued for the same `tranRefNo` until a composite-status query says the bank never saw it.
4. **Resilience (F-ISO-02):** resilience4j circuit breaker + bulkhead around pay and status calls (separate breakers); breaker-open → intents stay `CREATED`/`UNKNOWN` and back off (no state corruption, alarms on breaker state).
5. **Bank simulator contract tests:** WireMock simulator replaying the mock's scenario matrix plus: late-success-after-timeout, duplicate-`tranRefNo` replay (bank-side dedupe assertion), malformed/half-encrypted response, TLS failure. The same suite doubles as the sandbox certification checklist; certification evidence is a launch-gate artifact.
6. **Reconciliation:** daily statement/MIS ingestion job matching bank debits (RRN/tranRefNo) against `SUCCEEDED` intents; mismatches (bank-paid-but-not-succeeded, succeeded-but-no-debit) land in an exception queue with ops alerts (realizes OBS-01D/E for disbursement).

**Files.** `service/icici/` package (adapter, codec, mapper, properties), `V###` none (S3 owns state), simulator tests under `src/test`, recon job + exception queue table `V###__disbursement_recon_exception.sql`, ops surface for exceptions.

**Impacts.** None until the property flips per environment. Requires S3 (intent machine) and S6 (mode exclusivity) as prerequisites; S14 recommended before live traffic.

**Migration/compat.** None; mock remains the default everywhere until sandbox certification passes.

**Validation & testing.** Full fault-injection matrix on the simulator with zero duplicate payouts across kill/retry interleavings (extends S3's kill-point suite through a real HTTP boundary); breaker behavior under simulator blackout; recon detects seeded mismatches.

**Rollout/monitoring/rollback.** Sandbox → certification checklist → UAT with bank test credentials → limited live. Dashboards: provider latency/error/breaker, unknown-intent age, recon exception count. Rollback: flip provider property to `mock` in non-live, or halt the worker (intents queue safely) in live.

**Acceptance criteria.** Sandbox certification matrix green and archived; zero duplicate transfers in the fault matrix; every ambiguous outcome resolves through status/recon within the poll budget; secrets absent from logs and heap dumps of test runs.

---

#### Spec S18 — Retention lifecycle and partitioning (F-Q8, DATA-02, F-DB-02, F-AUD-01; decision D7)

**Root cause / current behavior.** Only idempotency records have scheduled purge; audit/auth/webhook/token/report streams grow unboundedly; no partitioning on append-heavy tables at a forecast of 50–400 M rows/year.

**Approved default schedule (D7 — per-class config, compliance can retune numbers without re-engineering):**

| Class | Tables (representative) | Window | Action |
|---|---|---|---|
| Financial records | loans, accounts, receipts/payments, disbursement intents/logs, product versions | 8 y post-closure | Archive (never hard-delete); legal-hold flag honored |
| Audit/auth streams | `loan_application_audit_event`, `auth_event_audit`, reveal/access audits | 2 y hot | Move to archive partitions/manifest, then detach |
| Webhook deliveries | `webhook_event_delivery_attempt`, outbox terminal rows | 180 d | Purge with manifest |
| Refresh tokens | `refresh_token` | 30 d past expiry/revocation | Purge |
| Report artifacts/requests | `report_request`, stored objects | 1 y | Purge + object-storage delete |
| Idempotency | both idempotency tables | 90 d | Existing worker (unchanged) |

**Solution.**
1. **`DataRetentionWorker`** (mirrors `IdempotencyRecordRetentionWorker` pattern): per-class `@ConfigurationProperties` (window, batch size, schedule, enabled, legalHold override), keyset-batched deletes/moves off-peak, per-run `retention_purge_manifest` row (class, window bounds, row count, min/max ids, duration) — the audit-of-the-purge itself is financial-class.
2. **Partitioning:** monthly `PARTITION BY RANGE (occurred_at/created_at)` on the three fastest-growing streams first (`loan_application_audit_event`, `auth_event_audit`, `webhook_event_delivery_attempt`) via a Flyway migration that creates the partitioned parent, attaches the existing table as the initial partition (or copies under maintenance window if attach is infeasible), plus a scheduled `PartitionMaintenanceWorker` creating next-month/dropping-expired partitions per retention class. The audit explorer's existing mandatory 90-day/keyset bound (V107) makes queries partition-prunable already — verify with `EXPLAIN` in tests.
3. **Legal hold:** `legal_hold` marker table (aggregate type/id, placed_by, reason); retention workers skip held aggregates; hold placement/release is SYSTEM_ADMIN + audited.
4. **Archive:** for audit classes, "archive" = detached partition dumped to object storage (CSV/Parquet + SHA-256 manifest) via the existing storage service before drop; a documented `psql`-restorable format. (WORM/immutability upgrades stay with SEC-AUD-01.)

**Files.** Retention worker + properties + manifest table migration; partition migrations per table; partition maintenance worker; legal-hold table + admin endpoint; docs table above lands in `CONTEXT.md`.

**Impacts.** DB DDL on hot tables — the partition-attach migration needs a maintenance window rehearsal (F-22 discipline: rehearse on a production-size snapshot, record lock time). No API changes. Storage costs shift from Postgres to object storage.

**Migration/compat.** Partition conversion is the risky step: rehearse, measure, schedule. Retention workers ship disabled-by-default; enable class-by-class after manifest review in UAT.

**Validation & testing.** Worker tests per class (boundary rows kept/purged, legal-hold skip, manifest accuracy); `EXPLAIN` assertions prove pruning on explorer queries; archive round-trip test (dump → restore → row-count/hash match); rehearsal artifact for the attach migration.

**Rollout/monitoring/rollback.** Enable purge classes in UAT first; metrics: purge lag per class, oldest unpurged row age, partition count/size (feeds the §11 database dashboard). Rollback: disable workers (growth resumes, nothing breaks); partitioned tables stay partitioned (no rollback needed once attached).

**Acceptance criteria.** Every class in the table above has an enabled, manifest-producing job in UAT; audit explorer plans show partition pruning; a held aggregate survives purge; archived partition restores byte-consistent.

---

#### Spec S19 — Canonical borrower + per-LSP relationship rows (F-D1/D2/D5, F-S2 remainder, F-S3 retirement; decision D8)

**Status:** **Partial — Slice A implemented 2026-07-15** (relationship table + dual-write + grant API lockdown). Residual (drop `borrower_lsp_access`, normalizer/CHECK, profile audit) deferred — see `docs/deferred-implementation.md` and `docs/implementation-log.md`.

**Root cause / current behavior.** One global borrower row (PAN-matched) serves every LSP relationship; any LSP's update mutates shared operational data globally; LSP visibility is an `@ElementCollection` of ids (`visibleLspIds`) with no room for relationship metadata; normalization rules are ad-hoc.

**Solution.**
1. **Keep `borrower` canonical** (PAN-unique — the dedupe/exposure view is the point of D8). Document it as the identity record: PAN, Aadhaar, name, DOB — plus *current* contact/financial data understood as "latest known", never as money-operational input (S5 snapshots own that).
2. **New `borrower_lsp_relationship`:** borrower_id FK, lsp_id FK, `UNIQUE(borrower_id, lsp_id)`, first_sourced_at, last_touched_at, source_channel, consent placeholder columns (nullable until a consent feature exists). **Slice A (2026-07-15 / V113):** table + backfill + dual-write on grant; RLS retained on `borrower_lsp_access`. **Residual:** switch visibility reads (`BorrowerDirectoryService`, LSP scoping, RLS EXISTS) to the relationship table; drop the element collection (`V114+`), retiring F-S3.
3. **Update semantics:** LSP-originated profile updates keep writing to the canonical row (current behavior) but every field-level change records the acting LSP in the existing borrower update audit (extend `borrowerBankDetailsUpdateAudit` pattern to a generic `borrower_profile_update_audit` with old/new values for non-PII-sensitive fields and hashes for sensitive ones). Money isolation is delivered by S5 (approved loans snapshot beneficiary data), so a cross-LSP edit can update the shared profile without being able to redirect any in-flight payout — the two specs together fully close F-S2.
4. **Normalization policy (F-D1/D2/D5 closure):** single `BorrowerFieldNormalizer` applied at every intake boundary (LSP create, admin edit, bulk rows): PAN uppercase-trimmed, mobile digits-only, IFSC uppercase, email lowercase, city/state trimmed. Matching DB `CHECK` constraints (`pan = upper(pan)` etc.) added after a one-shot data canonicalization migration (rehearsed per F-22). DTO validation messages already exist; the normalizer guarantees stored form.
5. **Exposure view:** borrower-360 gains a per-LSP relationship strip (which LSPs, since when, loan counts per relationship) — read-only UI addition from the new table.

**Files.** `V###__borrower_lsp_relationship.sql` (+backfill), `V###__drop_visible_lsp_ids.sql` (one release later), `V###__borrower_normalization.sql`; `Borrower.java`, `BorrowerDirectoryService`, LSP borrower scoping queries, `BorrowerFieldNormalizer` (+wire into onboarding service, admin controller, S16 bulk), profile update audit extension, borrower-360 FE strip.

**Impacts.** No partner-visible contract change (scoping semantics identical, now table-driven). DB: two small tables + constraints. RLS: add tenant policy on `borrower_lsp_relationship` (it is tenant-owned) — extends the F-T1/F-T2 inventory, with the standard cross-tenant negative test.

**Migration/compat.** Backfill is derive-only (no data loss); the element collection drop waits one release behind a dual-read verification metric (log divergence between old/new visibility answers — must be zero before drop).

**Validation & testing.** Visibility parity test (old vs new source agree on the full seeded portfolio); cross-tenant isolation test on the new table under partner role; normalization property tests (idempotent, matches DB CHECKs); S5+S19 combined scenario: LSP B updates a shared borrower's bank account → LSP A's approved loan still pays the snapshot, audit attributes the change to LSP B.

**Rollout/monitoring/rollback.** Two-release sequence (add+dual-read → drop). Rollback within release 1: revert reads to the element collection (still populated). After release 2: restore from relationship table (lossless superset).

**Acceptance criteria.** All borrower visibility flows read the relationship table; zero divergence during dual-read; every profile change attributes an actor+LSP; PAN/mobile/IFSC stored canonically with DB-enforced form; F-S3's metadata need is satisfied by the relationship row.

---

#### Spec S20 — Date and interest validation for partner-provided repayment schedules (NEW-05 / SCH-01)

**Status:** **Closed — implemented 2026-07-15; residuals closed same day.** Record: `docs/implementation-log.md`, `docs/partner-schedule-validation.md`, §19.6.

**Root cause / prior behavior (historical).** `LoanRepaymentScheduleService.validateProvidedInstallments` enforced principal integrity rigorously but left calendar and interest unguarded: due dates needed only be strictly increasing; per-row interest was never compared to the frozen `LoanProductVersion.interestRate`.

**Solution (as delivered 2026-07-15).** Extended the existing validator with date and interest discipline under `app.schedule.validation.*`. Defaults are **product-accepted** (not provisional). Date/interest checks are **always on** (rollout kill-switch removed after validation). Partner contract change documented in `docs/partner-schedule-validation.md`.

1. **Date discipline** (products are monthly-EMI by construction — `tenureMonths`, and the generator emits `firstDueDate + i months`):
   - **First-due window:** `approvalDate + first-due-min-days ≤ dueDate[0] ≤ approvalDate + first-due-max-days` (defaults 1 and 60; `approvalDate` = `loanAccount.approvedAt` UTC date, same anchor the generator uses). Kills past-dated and far-future starts.
   - **Anchored cadence:** for every row `i`, `dueDate[i]` must lie within `dueDate[0].plusMonths(i) ± cadence-tolerance-days` (default 7). Anchoring to the *first* due date rather than the previous row prevents tolerance from accumulating into drift; tolerance absorbs legitimate month-end/holiday adjustments partners make.
   - **Horizon cap (belt-and-braces):** `dueDate[last] ≤ approvalDate + tenureMonths months + horizon-grace-days` (default 75). Redundant with cadence but survives any future relaxation of the cadence rule.
   - New `ScheduleViolationType` entries: `SCHEDULE_FIRST_DUE_OUT_OF_WINDOW`, `SCHEDULE_CADENCE_VIOLATION`, `SCHEDULE_HORIZON_EXCEEDED` (priority-ordered after the existing principal violations — principal errors remain the primary violation when both occur).

2. **Interest discipline** (reconcile against the frozen product version, tolerate cross-engine rounding):
   - **Row-level:** expected interest per row = `openingPrincipal × monthlyRate` where `monthlyRate = productVersion.interestRate / 1200` at scale 10 — the generator's own formula (`buildGeneratedInstallments` line 169). Accept `|interestDue − expected| ≤ max(interest-row-tolerance-abs, expected × interest-row-tolerance-pct)` (defaults ₹10.00 and 2 %), so annuity-vs-reducing-balance rounding differences between partner engines pass but zero/inflated interest fails.
   - **Total-level:** `|Σ interestDue − Σ generatedInterest| ≤ max(interest-total-tolerance-abs, generatedTotal × interest-total-tolerance-pct)` (defaults ₹100.00 and 1 %), where the generated totals come from running the existing generator for the same account — no duplicated math, extract the interest-projection loop into a shared helper used by both the generator and the validator.
   - Zero-interest rows pass only when the product rate is zero (falls out of the row-level bound automatically; add an explicit test).
   - New violation types: `SCHEDULE_INTEREST_ROW_MISMATCH` (field `installments[i].interestDue`, message includes expected value), `SCHEDULE_INTEREST_TOTAL_MISMATCH`.

3. **Both validation call sites inherit the checks automatically** because they share `validateProvidedInstallments`: LSP submission returns 422 `REPAYMENT_SCHEDULE_INVALID` with the per-field violations; the disbursement-time revalidation surfaces `DISBURSEMENT_VALIDATION_FAILED` if a legacy stored schedule violates the new rules — which is the desired behavior (a bad schedule must not reach payout).

**Files.** `service/LoanRepaymentScheduleService.java` (validator + shared interest-projection helper), `ScheduleViolationType` enum + priority switch, new `ScheduleValidationProperties`, `application.yml` defaults, partner OpenAPI/docs (document the new 422 violation fields and bounds), tests.

**Impacts.** Partner-facing contract tightening on `PUT …/repayment-schedule`: schedules that previously validated may now 422 — approved tightening, documented in the partner changelog with the exact bounds. No DB change; no UI change (admin schedule views already render whatever is stored). Generated (non-partner) schedules are unaffected by construction but are covered by a self-consistency test.

**Migration / backward compatibility.** No stored-data migration. Pre-existing stored-but-not-yet-disbursed schedules get checked at disbursement time. Partner schedules with flat/arbitrary interest or irregular calendars receive `422` — intentional and documented.

**Rollout/monitoring/rollback.** Ordinary PR; partner channel notified via `docs/partner-schedule-validation.md`. Metric: schedule rejections by violation type per LSP. Rollback: revert the release (no kill-switch retained).

**Validation & testing.**
- Table-driven violation tests: past first due date; first due 61 days out; 12 rows across 100 years (the motivating case — must fail with `SCHEDULE_CADENCE_VIOLATION`); one row drifted +8 days (fail) / +6 days (pass); zero interest on a 14.5 % product (fail row-level); interest inflated 3 % per row (fail); partner rounding ±₹5/row (pass); zero-rate product with zero interest (pass).
- Self-consistency property: for every seeded product/principal/tenure combination, the output of `buildGeneratedInstallments` passes the full validator unchanged.
- Regression: all existing schedule tests (count/chain/reconcile/lock, Issue-guard tests) stay green; disbursement-time revalidation test with a legacy-invalid stored schedule surfaces `DISBURSEMENT_VALIDATION_FAILED` before any adapter call.
- Contract test asserting the 422 envelope carries the new violation fields and codes.

**Acceptance criteria.** A 12-installment schedule spanning more than tenure + grace is rejected at submission *and* at disbursement; a schedule whose interest deviates from the frozen product rate beyond tolerance is rejected with the expected value named in the violation; generator output always self-validates; all bounds are runtime-configurable and documented in the partner OpenAPI.

---

## 19.6 Implementation log — remediation delivered in-repo

This section records exact fixes landed after the 2026-07-12 assessment. Each entry maps to a Spec in §19.3/§19.5.

### S2 / NEW-01 / TEST-BE-01 — backend test suite database safety (2026-07-13)

**Problem.** `DocumentUploadLocalProfileIntegrationTest` booted `@ActiveProfiles("local")` (importing repo-root `.env`), ran `IntegrationTestDatabaseCleaner.cleanIntegrationTestData()` in `@BeforeEach`, and wiped ~40 tables on the live Supabase database pointed to by `LMS_DB_URL`.

**Fix (three layers).**

1. **Opt-in external DB** — `DocumentUploadExternalDbIntegrationTest` is tagged `@Tag("external-db")`, gated by `LMS_IT_EXTERNAL_DB=true`, excluded from default Surefire (`<excludedGroups>external-db</excludedGroups>` in `backend/pom.xml`); run explicitly with `mvnw test -Pexternal-it` and the env var set.
2. **JDBC URL guard** — new `IntegrationTestDatabaseTargetGuard` called at the start of `IntegrationTestDatabaseCleaner.cleanIntegrationTestData()`; allows only `jdbc:h2:…`, `localhost`/`127.0.0.1`, or remote URLs when `LMS_IT_EXTERNAL_DB=true` (with SLF4J warning naming the host).
3. **Testcontainers default** — `DocumentUploadPostgresIntegrationTest` replaces the local-profile test; extends `PostgresDataJpaTestSupport`, `@ActiveProfiles("test")`, multipart upload regression on ephemeral PostgreSQL every default `mvn test`.

**Files changed.**

| Action | Path |
|---|---|
| Added | `backend/src/test/java/com/bhawana/lms/support/IntegrationTestDatabaseTargetGuard.java` |
| Added | `backend/src/test/java/com/bhawana/lms/support/IntegrationTestDatabaseTargetGuardTest.java` |
| Modified | `backend/src/test/java/com/bhawana/lms/support/IntegrationTestDatabaseCleaner.java` |
| Added | `backend/src/test/java/com/bhawana/lms/web/DocumentUploadPostgresIntegrationTest.java` |
| Added | `backend/src/test/java/com/bhawana/lms/web/DocumentUploadExternalDbIntegrationTest.java` |
| Added | `backend/src/test/java/com/bhawana/lms/web/DocumentUploadTestSupport.java` |
| Deleted | `backend/src/test/java/com/bhawana/lms/web/DocumentUploadLocalProfileIntegrationTest.java` |
| Modified | `backend/pom.xml` |

**Tests added.** `IntegrationTestDatabaseTargetGuardTest` (4 cases). `DocumentUploadPostgresIntegrationTest` (1 integration case).

**Validation.** `.\mvnw.cmd test` BUILD SUCCESS with `.env` present; Supabase not contacted by default suite; `DocumentUploadExternalDbIntegrationTest` not executed in default run.

**Residual risk.** Conscious opt-in (`LMS_IT_EXTERNAL_DB=true`) can still wipe a remote database — by design, with warning. Layer 2 guard must not be reverted.

### S3 / MNY-01 / F-MNY-01 — Durable disbursement intent with out-of-transaction provider call (2026-07-13)

**Problem.** `LoanDisbursementCommandService.initiateDisbursement` invoked `loanDisbursementAdapter.requestDisbursement` inside `@Transactional` before persisting account status and `loan_disbursement_request_log`. Crash after bank acceptance could leave no durable record and allow a duplicate payout on retry. Status polling also held DB connections during provider calls.

**Fix.** Three-phase intent workflow behind `app.disbursement.intent-workflow.enabled` (default `true`; test profile `false`):

1. **Tx-A** — insert `disbursement_intent` (`CREATED`), mark account `DISBURSEMENT_REQUESTED`, deterministic `tran_ref_no` (`ICI` + 13 hex from intent UUID).
2. **No transaction** — `LoanDisbursementWorker` claims intents (`FOR UPDATE SKIP LOCKED` + lease), calls provider.
3. **Tx-B** — persist `loan_disbursement_request_log`, update intent state, enqueue webhook; auto-resolve terminal outcomes.

**Files changed.**

| Action | Path |
|---|---|
| Added | `backend/src/main/resources/db/migration/V111__disbursement_intent.sql` |
| Added | `backend/src/main/java/com/bhawana/lms/domain/DisbursementIntent.java`, `DisbursementIntentState.java` |
| Added | `backend/src/main/java/com/bhawana/lms/repo/DisbursementIntentRepository*.java` |
| Added | `backend/src/main/java/com/bhawana/lms/service/DisbursementIntentWorkflowService.java`, `DisbursementIntentWorkflowProperties.java`, `DisbursementIntentReference.java` |
| Modified | `LoanDisbursementCommandService.java`, `LoanDisbursementWorkerProcessor.java`, `LoanDisbursementWorkerService.java` |
| Modified | `application.yml`, `application-test.yml`, `IntegrationTestDatabaseCleaner.java` |
| Added | `DisbursementIntentReferenceTest.java`, `DisbursementIntentWorkflowIntegrationTest.java` |

**Tests added.** `DisbursementIntentReferenceTest`; `DisbursementIntentWorkflowIntegrationTest` (intent before provider, single provider call, no duplicate live intent). `MockIciciDisbursementLifecycleIntegrationTest` regression green.

**Validation.** `.\mvnw.cmd test` BUILD SUCCESS (683 tests).

**Residual risk.** Async admin disbursement (~30s worker tick); beneficiary from live borrower row (**S5 deferred 2026-07-15** — `docs/deferred-implementation.md`); no full crash-matrix suite; no intent-age metrics; legacy inline path behind feature flag.

**Canonical doc.** `docs/implementation-log.md`.

### S5 / DATA-01 — Approval-time beneficiary snapshot — **deferred 2026-07-15**

**Not implemented.** Owner deferred Spec S5 for the current pass (PAN dedupe + single active loan → low practical likelihood). Defect remains accurate in source; residual accepted until real-money rails or operating assumptions change. Full record: `docs/deferred-implementation.md`. Also mirrored in `docs/implementation-log.md` and the DATA-01 / Spec S5 sections above.

### S7–S12 group — frontend-heavy pilot hardening (2026-07-15)

Canonical narrative: `docs/implementation-log.md` (S7–S12). Residual close-out same evening.

#### S7 — Same-origin credential policy (SEC-03)

**Problem.** Authenticated `http-client` accepted absolute URLs and attached bearer + cookies.

**Fix.** Resolve against `VITE_API_BASE_URL`; refuse cross-origin credential-bearing requests; `fetchExternal` (`credentials: "omit"`, no auth).

**Validation.** Unit tests: attacker origin throws with zero `fetch` calls.

#### S11 — Access token out of localStorage (SEC-01 item 4)

**Problem.** Full session including `accessToken` persisted in `localStorage`.

**Fix.** Persist `{ user, expiresAt }` only; token in module memory; sanitize legacy stored tokens; reload refreshes via HttpOnly cookie.

**Validation.** `session-storage.test.ts` asserts storage never contains the token.

#### S8 — Loan-account status vocabulary (ODD-01)

**Problem.** Frontend Zod enum had fictitious `ACTIVE` and only four states.

**Fix.** `LOAN_ACCOUNT_STATUSES` aligned to backend eight literals; exhaustive badge meta; detail `LoanAccountStatus.parse`.

#### S12 — Disbursement money preview (UX-02 display)

**Problem.** Confirm dialog omitted principal / fee / net / payment mode.

**Fix.** Shared `DisbursementAmounts` + payment-mode selector; `GET …/disbursement-preview`; dialog loads preview before confirm; `GET …/disbursement-reference` for live intent `tranRefNo`; `beneficiarySource=LIVE_BORROWER` until S5.

**Validation.** `DisbursementPreviewIntegrationTest`; intent-workflow reference case; FE dialog tests.

#### S9 — Reproducible E2E harness (NEW-02 / E2E-*)

**Problem.** Specs targeted removed “System roles” UI; hardcoded passwords; Phase 8 threw without application id.

**Fix.** Env-only login; smoke asserts Email/Password; Playwright `globalSetup`/`globalTeardown` seeds `E2E-*` fixtures; Phase 8 env → fixture → skip; pinned `scripts/indep-e2e/requirements-e2e.txt`; `docs/e2e.md`.

#### S10 — Home/Audit canaries + bootstrap-sync (OPS-01 / ENV-01 residuals)

**Problem.** Instant JDBC binds and bootstrap drift recurred with no guard; stray `APP_SECURITY_BOOTSTRAP_LOGIN_PASSWORD`.

**Fix.** `HomeAndAuditInstantBindPostgresTest` (Testcontainers) — 2/2 green with Docker; `e2e/canary.spec.ts` + `npm run e2e:canary` — passed locally; `POST /api/v1/internal/system/bootstrap-sync`; single password env `APP_SECURITY_BOOTSTRAP_PASSWORD`.

#### Residual close-out (2026-07-15 evening)

| Residual | Closure |
|---|---|
| Phase 8 manual application id | `globalSetup` seeds fixtures |
| Instant bind unproven | Postgres Testcontainers tests green |
| Intent `tranRefNo` lag | `disbursement-reference` endpoint |
| Live bank in preview | Labeled `LIVE_BORROWER`; S5 still deferred |
| OpenAPI drift | Regenerated `openapi/openapi.json` + `schema.ts` |
| Canary not run | `e2e:canary` passed |

**Still open after this group.** S5 (deferred); S6 mock/live exclusion (**deferred 2026-07-15**); S13 receipt ledger (**deferred 2026-07-15**); S14+ maker-checker and later specs; plaintext PII encryption (D4); PAN policy implementation (S15, **deferred 2026-07-15**).

### S6 / MOCK-01 — Mutually exclusive mock/live disbursement modes — **deferred 2026-07-15**

**Not implemented.** Owner deferred Spec S6 for the current pass (mock rails remain intentional for management-review / synthetic UAT). Defect remains accurate in source; residual accepted until non-mock / real-money deployment or Spec S17. Full record: `docs/deferred-implementation.md`. Also mirrored in `docs/implementation-log.md` and the MOCK-01 / Spec S6 sections above.

### S13 / MNY-02 — Receipt / allocation / suspense / reversal ledger — **deferred 2026-07-15**

**Not implemented.** Owner deferred Spec S13 for the current pass (exact full-EMI posting remains sufficient for synthetic UAT / management review). Defect remains accurate in source; residual SoR gap accepted until real receipt ingestion. Full record: `docs/deferred-implementation.md`. Also mirrored in `docs/implementation-log.md` and the MNY-02 / Spec S13 sections above.

### S15 / SEC-01(3) — PAN masking policy — **deferred 2026-07-15**

**Not implemented.** Owner deferred Spec S15 for the current pass (current PAN display behaviour accepted). Defect remains accurate in source; residual partner/list full-PAN exposure accepted until partner pilot / compliance requires D3. Full record: `docs/deferred-implementation.md`. Also mirrored in `docs/implementation-log.md` and the Spec S15 section above.

### S20 / NEW-05 / SCH-01 — Partner schedule date and interest validation (2026-07-15)

**Problem.** Partner `LSP_PROVIDED` schedules accepted past/absurd calendars and arbitrary interest as long as principal arithmetic balanced.

**Fix.** Extended the shared validator with first-due window, anchored monthly cadence, horizon cap, and interest reconciliation to the frozen product rate / platform generator. Bounds under `app.schedule.validation.*` (product-accepted defaults).

**Residuals closed.** Partner 422 contract documented (`docs/partner-schedule-validation.md`); defaults locked; kill-switch removed (checks always on).

**Validation.** `LoanRepaymentScheduleServiceTest` + LSP schedule IT methods green.

### S19 / D8 — Borrower↔LSP relationship Slice A (2026-07-15)

**Problem.** Visibility was only `borrower_lsp_access` ElementCollection ids — no sourcing timestamps, channel, or consent placeholders; public `grantVisibilityTo` could update the list without a relationship row.

**Fix (Slice A).**
1. `V113__borrower_lsp_relationship.sql` — table + backfill from access + RLS.
2. Dual-write on grant via `BorrowerLspRelationshipService.grantVisibility` (onboarding + synthetic seed).
3. Admin detail / FE Profile strip expose `firstSourcedAt` / `lastTouchedAt` / `sourceChannel`.
4. Public `Borrower.grantVisibilityTo` removed; package-private mutation via `BorrowerLegacyAccessWriter`.

**Not in Slice A.** Drop of `visibleLspIds` / access table; field normalizer + DB CHECKs; profile-update audit; money freeze (S5).

**Validation.** `BorrowerLspRelationshipServiceTest`; `BorrowerAdminControllerTest`; tenant isolation relationship counts under partner role.

**Canonical docs.** `docs/implementation-log.md`, `docs/deferred-implementation.md` (S19 residual).

