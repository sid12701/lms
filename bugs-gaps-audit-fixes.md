# Bugs, Gaps & Audit Fixes — Decision Tracker

**Source:** `gaps-bugs-audit.md` (2026-05-31) → 108 GitHub issues on `sid12701/lms` (#61–#168).
**Purpose:** For each issue, capture (a) the problem in plain English, (b) the possible fixes I see, (c) the recommended fix with reasoning, (d) the effect on the app overall, and (e) **the agreed solution after we discuss** (filled during the grill).

**How to read each entry:**

- **Problem (plain English)** — what is actually wrong, no jargon.
- **Possible fixes** — the realistic options on the table, with the tradeoff in one line.
- **Recommended** — the one I'd pick, and why.
- **Effect on app** — what changes (UX, security, perf, ops, partner integrations).
- **Detailed solution after discussion** — populated turn-by-turn during the grill.

---

## P0 — Production blockers (15)

### #61 — Mock disbursement adapter wired into production runtime
**Labels:** gap, mocked-flow, security · **Link:** https://github.com/sid12701/lms/issues/61 · **Status:** **CLOSED — DEFERRED (intentional pre-launch)** (2026-06-06). Mock-by-design per grilled decision; reopens only when real disbursement provider is approved.

**Problem (plain English):** The only thing that simulates "money left the bank" today is an in-memory mock. It runs in every environment — including production — and any system admin can stamp a loan as DISBURSED via `/mock-outcome` with no real bank involved.

**Possible fixes:**
1. **Profile-guard the mock, fail startup if no real adapter** — clean kill switch; forces a real integration before prod cut.
2. **Keep the mock but require a feature flag + audit-log every call** — safer than today but still allows fake money in prod.
3. **Build the real adapter now, delete the mock entirely** — most thorough but blocks all other work behind a bank integration.

**Recommended:** Option 1. The mock is a test fixture; it belongs under `@Profile({"local","test"})` and the production context should refuse to start without a real `LoanDisbursementAdapter` bean. This unblocks decoupling fix from real-adapter delivery.

**Effect on app:** Production loses the `/mock-outcome` endpoint and the mock bean. Until a real adapter is wired, prod boot fails — which is the point: it surfaces the gap loudly instead of silently stamping fake disbursements.

**Detailed solution after discussion (2026-05-31):**

**Reframe — the mock is intentional, not a leak.** Per product decision, the mock disbursement adapter and `/mock-outcome` endpoint are the *intended* disbursement implementation until executive approval is granted for a real bank/NPCI provider integration. The audit doc's original framing ("kill it in prod") doesn't apply. The seam (`LoanDisbursementAdapter` interface) stays clean so the eventual swap is a bean replacement, not a refactor.

What we are NOT doing (intentionally deferred):
- Not adding `@Profile({"local","test"})` on `MockLoanDisbursementAdapter` — it must keep loading in prod.
- Not deleting `LoanApplicationOpsController.applyMockDisbursementOutcome` — it is the operator's only way to advance a loan past `DISBURSEMENT_REQUESTED` while there is no real provider callback.
- Not changing the synchronous interface shape — fine for the mock; will be revisited at real-provider design time.

What we ARE doing — a small, focused fix that surfaces the "mock-by-design" status and locks in safety properties cross-cutting issues will rely on:

1. **ADR.** Add `docs/adr/0XYZ-disbursement-adapter-stays-mock-until-approval.md` capturing: (a) the decision and the stakeholder who owns the approval gate, (b) the seam (`LoanDisbursementAdapter` interface) that makes the future swap a bean-only change, (c) the residual risks that must be controlled while the mock is live (`/mock-outcome` audit → #152; maker-checker → #62; rate limit → #81), (d) the criteria that would flip the decision.
2. **Runtime visibility.** Expose the active adapter mode via `/actuator/info` as `disbursementAdapter: { mode: "MOCK", reason: "Pre-launch mock pending provider approval", adrRef: "0XYZ" }`. Implemented as an `InfoContributor` that reads the bean's declared mode (mock returns `MOCK`; a future real adapter returns `REAL`). Operators see at a glance without grepping code.
3. **Response/payload labelling already present — lock it in.** `MockLoanDisbursementAdapter` already stamps `provider: "MOCK_DISBURSEMENT"` and `providerName = "MOCK_DISBURSEMENT"` on `DisbursementResult`. Add a regression test so a future contributor cannot strip the label. Confirm the label reaches the loan-detail response (`LoanApplicationDetailResponse` surfaces it from `LoanDisbursementRequestLog.providerName`) so operators see "MOCK_DISBURSEMENT" in the UI, not just in logs.
4. **Cross-cutting controls — explicitly handed off, not buried:**
   - Auditing every `/mock-outcome` call → tracked under #152 ([AUD-6]). Solution there must record actor, outcome, application ID, correlation ID, IP, timestamp.
   - Maker-checker before a mock `DISBURSED` outcome can be applied → tracked under #62. The mock-outcome resolve path becomes one of the maker-checker-gated transitions when #62 lands.
   - Rate limit on `/mock-outcome` → tracked under #81.
   - `LocalDemoPortfolioSeedService`'s 4 calls to `resolveMockDisbursementOutcome` are pre-authenticated as `INTERNAL_ACTOR` and run only under `@Profile("local")`; they stay as the demo-data path. Confirm `@Profile("local")` is actually present on the seed service under #107/#161.
5. **Duplicates closing as part of this work:** #122 ([D-9] move endpoint+DTO behind profile) is reframed — endpoint stays in prod by design, no profile gate. Close #122 as "not-applicable: mock is intentional per ADR 0XYZ" and reference the ADR.

**Why this is the right move (not the audit doc's recommendation):**
- The audit doc assumes the mock is a leak. The user clarified it is product intent. Treating intent as a leak would force false-positive "fix" PRs and break the dev loop.
- The seam is already a deep, single-node-community abstraction (Community 90: `LoanDisbursementAdapter`; Community 57: `MockLoanDisbursementAdapter`). The right TDD response is to leave the abstraction alone and improve the *signal* that says "mock is active" — so operators, auditors, and future contributors cannot mistake it for a real adapter.
- The actual sharp edges (audit gap, maker-checker, rate limit) live on the `/mock-outcome` resolve path, not the adapter. Those have their own issues (#152, #62, #81). This issue's job is to (a) preserve the seam, (b) make the mock-by-design state externally visible, (c) hand the sharp edges to the right tickets — and stop there.

**TDD plan (vertical slices, behaviour through public interfaces):**

Tracer bullet first, then each subsequent test responds to what the previous revealed.

1. **TRACER — `disbursement_provider_mode_is_reported_as_MOCK_via_actuator_info`.** Boot the app, GET `/actuator/info`, assert `disbursementAdapter.mode == "MOCK"` and `disbursementAdapter.reason` matches the configured string. Verifies through the public actuator interface; survives any internal refactor of how the mode is determined.
2. **`disbursement_request_response_carries_provider_label_MOCK_DISBURSEMENT`.** Drive a loan through `LoanApplicationService.initiateDisbursement`, query the loan-account-disbursement log via the public ops detail endpoint, assert `providerName == "MOCK_DISBURSEMENT"`. Locks in: the label survives all the way to the operator-visible response.
3. **`loan_detail_response_surfaces_disbursement_provider_label`.** GET the ops loan-detail endpoint for a disbursed-via-mock loan, assert the JSON path that surfaces provider name is `"MOCK_DISBURSEMENT"`. Locks in: ops UI cannot accidentally hide the mock label.
4. **`mock_outcome_endpoint_still_callable_under_default_profile`.** Smoke test ensuring no future contributor accidentally `@Profile`-gates the endpoint based on the audit-doc's outdated framing. Test asserts a SYSTEM_ADMIN POST to `/mock-outcome` returns 200 under the default profile.

**Behaviours we deliberately do NOT assert here (each owned by another issue):**
- Audit row written on `/mock-outcome` → #152's tests.
- Second-actor required for mock DISBURSED → #62's tests.
- 429 after N `/mock-outcome` calls per minute → #81's tests.
- `MockLoanDisbursementAdapter.@Service` is unguarded → not a bug under this decision.

**Mocking discipline:** no internal collaborators mocked. The actuator test boots a real Spring context (boundary). The loan-detail tests drive a real persistence layer (H2 for tests, PG for integration). No mocks of `LoanDisbursementRequestLogRepository`, `LoanApplicationLifecycleService`, etc. — they are internal.

**Effect on app (re-stated under the new framing):**
- Zero behavioural change to the mock itself or its endpoint.
- New `/actuator/info` field makes the mock-by-design state externally legible to operators, monitoring, and audit reviewers.
- New ADR makes the decision discoverable in the repo so the next reviewer/auditor doesn't re-open this as "you have a mock in prod."
- The sharp edges (audit, maker-checker, rate limit) are now explicitly delegated to their owning issues — no risk that #61 closes and they stay open under a false sense of completeness.

**Dependencies / sequencing:** independent — can ship first. #62, #81, #152 then layer the controls on top. #122 closes with this PR as not-applicable. #84 (truncated UUID) stays open as a hygiene fix on the mock (not a leak issue).

---

---

### #62 — No maker-checker on approval or disbursement
**Labels:** gap, security, rbac · **Link:** https://github.com/sid12701/lms/issues/62 · **Status:** **CLOSED** — merged to `main` 2026-06-01 (PRs (a)–(c) in one delivery)

**Problem (plain English):** One person (admin or LSP API client) can move a loan from "just created" to "money out the door" in a single API call. There's no second pair of eyes between request and execute, which is the standard control for moving money.

**Possible fixes:**
1. **In-lifecycle maker-checker** — split `APPROVED_PENDING_DISBURSAL` into "approval requested" + "approval executed by a different actor"; same for disbursement.
2. **External review queue** — keep current statuses; route every approval/disbursement to a queue that requires a second user to release.
3. **Soft maker-checker (audit-only)** — log who-did-what but don't block; rely on detection.

**Recommended:** Option 1. It puts the rule where the rule lives (the state machine), so no caller can bypass it. Option 2 adds an external system; Option 3 is a control on paper, not in code.

**Effect on app:** Every disbursement now needs two distinct credentials. UI gets a "pending second approval" inbox. LSP API contract changes — the current "one POST → disbursed" flow becomes two calls (request + execute). Big integration impact on LSPs; must be communicated.

**Detailed solution after discussion (2026-05-31):**

**Reframe — no human maker-checker; rule engine is the gatekeeper.** The audit doc framed this as "add a second human pair of eyes." Per the user's product model:
- The **`LoanAutoApprovalRuleEngine` is the trusted approver** — no second human on the approval side.
- **Disbursement is automated** — once a loan reaches `APPROVED_PENDING_DISBURSAL`, a system worker disburses without a human in the loop.
- **The two-actor protection moves from "two humans" to "two systems":** the rule engine approves; the disbursement worker executes. Both are auditable and bound to product rules.
- **The new control surface is alerts:** LSP attempts to violate product bounds (overcharge fees, request disbursement above principal, submit a non-closing repayment schedule, repeatedly mismatch bank details) get auto-rejected AND fire `LSP_BOUND_VIOLATION` alerts attributable to the LSP credential. The "second pair of eyes" is the ops team reviewing the alert stream, not gating every transition.

**Pre-launch context:** no live LSP integrations yet, so the LSP-API contract change (removing `POST /disbursement`) is free — no partner comms required.

**Decisions locked in:**
1. **Manual ops override stays as `SYSTEM_ADMIN`-only break-glass.** `OPS_USER` loses the ability to manually transition. Every manual transition fires a `MANUAL_RULE_ENGINE_OVERRIDE` alert through `AlertRuleEvaluationService`, includes the rule engine's last evaluation (what it would have decided), and writes a loud audit row. Lever exists; pulling it is the exceptional event.
2. **Worker-driven disbursement.** Remove `POST /api/v1/lsp/loan-applications/{id}/disbursement` from `LspLoanApplicationApiController` (and its `LoanDisbursementService.requestDisbursementForLsp`). Add a Spring-scheduled `LoanDisbursementWorker` (e.g., `@Scheduled(fixedDelayString = "${lms.disbursement.worker.intervalMs:30000}")`) that:
   - Reads applications in `APPROVED_PENDING_DISBURSAL` with no in-flight `LoanDisbursementRequestLog`.
   - Re-validates loan-product bounds (principal, processing fee cap, schedule closure) inside the worker — last-line defence; if anything fails, transition to `REJECTED` with `LSP_BOUND_VIOLATION` reason + alert (this should never happen if the rule engine did its job, but the worker is the gate to actually moving money).
   - Calls `loanDisbursementAdapter.requestDisbursement(cmd)` (the mock, per #61's decision).
   - On adapter exception → retries with exponential backoff (capped at e.g. 5 attempts); after cap → application moves to `DISBURSEMENT_RETRY` and fires an alert.
   - Records `LoanDisbursementRequestLog` exactly as today.
3. **Decoupling fixes #85 at the root.** Auto-approval no longer runs inside the disbursement transaction because there is no LSP-side disbursement transaction. The worker reads an already-approved status; it never re-evaluates. Close #85 as a side-effect of (b).
4. **Bank-detail update flow — separate surface, no cooldown.** Add `PATCH /api/v1/lsp/borrowers/{id}/bank-details` (and admin equivalent) as the only path for changing borrower bank details. The disbursement worker continues to enforce strict on-file match. Controls:
   - Audited with old → new diff (actor, timestamp, IP, correlation ID).
   - Fires `BORROWER_BANK_DETAILS_UPDATED` webhook to the LSP so the change is visible end-to-end.
   - **No cooldown gate on subsequent disbursement** — update takes effect immediately. Per user decision: rely on audit + alert + velocity rule (`BORROWER_BANK_DETAILS_VELOCITY` alert if more than X updates in Y days for the same borrower) rather than a time-window block.
5. **`LSP_BOUND_VIOLATION` alert rule** in `AlertRuleEvaluationService`, fired on each of:
   - `disbursalAmount > approved principal` (currently 422 in `LoanDisbursementService` lines 71–87 — alert layered on top).
   - Implied processing fee > product `processingFeeRate` (existing shortfall check, lines 77–87).
   - LSP_PROVIDED repayment schedule whose principal components don't sum to approved principal (this is also #137 — the alert layers in this PR; the validator detail stays under #137).
   - Bank-detail mismatch repeated N times within M minutes from same LSP for same loan (the "fishing" pattern).
   - All alerts carry: LSP code, applicationId, violation type, attempted values, on-file values, correlation ID. Visible in Audit Explorer; no new dashboard surface (user's choice).
6. **What stays unchanged:**
   - The seam (`LoanDisbursementAdapter`) — preserves the future real-adapter swap from #61.
   - `LoanApplicationStatus` enum — no new states (we considered `APPROVAL_REQUESTED`/`DISBURSAL_REQUESTED` but the rule engine + worker model doesn't need them; states are already correctly shaped).
   - `LocalDemoPortfolioSeedService` — still seeds demo data through the same service methods; the worker can be configured to fire synchronously in tests/local for determinism.

**Why not a state-machine split with two-human checker (audit doc Option 1):** the user's product model is automation, not segregation of duty. Adding `APPROVAL_REQUESTED`/`DISBURSAL_REQUESTED` would create real states that demand human action — which the user explicitly does not want. The state machine stays as it is; the *enforcement* moves into the rule engine (already authoritative on `INITIALIZED → AWAITING_APPROVAL → APPROVED_PENDING_DISBURSAL`) plus the worker (newly authoritative on `APPROVED_PENDING_DISBURSAL → fire adapter`).

**PR sequencing (per user decision — split into three):**
- **PR (a) — `SYSTEM_ADMIN`-only override + `MANUAL_RULE_ENGINE_OVERRIDE` alert.** Smallest, lands first. Locks down the manual lever; no other behaviour changes.
- **PR (b) — Worker-driven disbursement; remove `POST /disbursement`.** Larger; depends on (a) because the worker's failure-path alerts piggy-back on the alert plumbing from (a). Closes #85 as side-effect; touches LSP API surface (free, pre-launch).
- **PR (c) — Borrower bank-detail update flow + `LSP_BOUND_VIOLATION` and `BORROWER_BANK_DETAILS_VELOCITY` alerts.** No cooldown. Depends on (b) because the velocity/cooldown logic interacts with the worker's mismatch path.

**TDD plan (vertical slices per PR, behaviour through public interfaces):**

Each PR has its own tracer bullet + incremental tests. Listed in execution order.

**PR (a) tests:**
1. **TRACER — `ops_user_cannot_manually_transition_status_after_admin_only_lock`.** Authenticate as `OPS_USER`, `POST /api/v1/internal/ops/loan-applications/{id}/status-transitions` → 403. Verifies the role narrowing through the public API.
2. `system_admin_can_manually_transition_with_required_reason_and_note`. SYSTEM_ADMIN POST with reason+note → 200; subsequent GET shows new status.
3. `manual_transition_fires_MANUAL_RULE_ENGINE_OVERRIDE_alert`. SYSTEM_ADMIN transition → alert visible in Audit Explorer search by alertType.
4. `manual_transition_audit_row_captures_rule_engine_last_decision`. Audit row payload includes the rule engine's last `Evaluation.failedRules` (so reviewers see what was overridden).

**PR (b) tests:**
1. **TRACER — `application_in_APPROVED_PENDING_DISBURSAL_is_picked_up_by_worker_and_transitions_to_DISBURSED`.** Seed a loan to `APPROVED_PENDING_DISBURSAL`, invoke the worker (in tests, call the worker method directly — that's the public interface for the worker; not its `@Scheduled` wiring), assert `getApplication(id).getStatus() == DISBURSED` (under mock-by-design's auto-resolution path) and a `LoanDisbursementRequestLog` row exists.
2. `worker_skips_applications_with_in_flight_disbursement_request`. Two passes of the worker over the same loan → only one adapter call (assert via a *boundary* spy on the adapter — adapter is a system boundary per the mocking guide, OK to mock).
3. `worker_retries_on_transient_adapter_failure_then_succeeds`. Adapter throws once, succeeds on second call → final state is `DISBURSED`, log row shows 2 attempts.
4. `worker_marks_application_DISBURSEMENT_RETRY_after_cap`. Adapter throws N times → status is `DISBURSEMENT_RETRY` + `DISBURSEMENT_RETRY_EXHAUSTED` alert fires.
5. `worker_fires_LSP_BOUND_VIOLATION_alert_and_rejects_if_amount_exceeds_principal`. Seed an inconsistent state (e.g., via direct repo write to mimic a rule-engine bypass) → worker catches it; status moves to `REJECTED`; alert fires. Last-line-defence test.
6. `removed_LSP_disbursement_endpoint_returns_404`. POST to the old path → 404. Locks in the contract removal.
7. `auto_approval_no_longer_runs_inside_disbursement_path` (closes #85). Force a scenario where auto-approval *would* have re-evaluated and rejected on the old path → worker path does not re-evaluate; loan disburses cleanly. Verifies the root-cause fix.

**PR (c) tests:**
1. **TRACER — `borrower_bank_details_update_via_dedicated_endpoint_is_audited_and_webhook_fires`.** `PATCH /lsp/borrowers/{id}/bank-details` as LSP → 200; subsequent GET shows new details; audit row has old→new diff; `BORROWER_BANK_DETAILS_UPDATED` webhook event is enqueued.
2. `disbursement_proceeds_immediately_after_bank_details_update_no_cooldown`. Update bank details, then worker fires next cycle → disbursement succeeds. Locks in: no time-window block.
3. `disbursement_with_mismatched_bank_details_in_request_fails_strictly_no_inline_update`. (Verifying: the disbursement path does not accept LSP-supplied bank-detail updates — they must go through the dedicated endpoint.)
4. `repeated_bank_detail_mismatches_from_same_LSP_for_same_loan_fire_LSP_BOUND_VIOLATION`. N attempts within window → alert.
5. `repeated_borrower_bank_detail_updates_fire_BORROWER_BANK_DETAILS_VELOCITY_alert`. X updates in Y days → alert; further updates still succeed (alerts inform; they don't gate).
6. `lsp_attempt_to_disburse_above_approved_principal_fires_LSP_BOUND_VIOLATION_and_rejects`. (Already 422 today; this test layers on the alert assertion.)
7. `lsp_supplied_schedule_principal_mismatch_fires_LSP_BOUND_VIOLATION` (test that the alert layer fires; the validator detail lives under #137).

**Mocks (per your TDD doc):**
- `LoanDisbursementAdapter` — system boundary, mockable. Worker tests use a fake adapter; production wiring uses the real one (currently the mock-by-design adapter from #61).
- `AlertRuleEvaluationService` — internal collaborator, **not** mocked. Tests assert alerts through the Audit Explorer query API (the public read interface), which is how an ops user would see them.
- `Clock` — system boundary, mockable for the velocity-window tests so M-minute / Y-day windows are deterministic.
- No mocking of repositories, lifecycle service, status enum, or the rule engine.

**Tests we deliberately do NOT write here (owned elsewhere):**
- Audit-row schema assertions → #71 / #155.
- LSP IP allowlist on `PATCH /bank-details` → #64.
- Rate limit on `PATCH /bank-details` → #81.
- Webhook signing format → #129.

**Effect on app:**
- LSP API surface shrinks: no `POST /disbursement`; new `PATCH /borrowers/{id}/bank-details`. Pre-launch, no partner comms needed.
- Disbursement becomes fully system-driven once approved (≤30s worker delay; configurable). Aligns with user's "automated process" stance.
- Ops gains an alert stream attributable to specific LSPs for product-bound violations. Disabling a misbehaving LSP becomes data-driven (the alert thread is the evidence) — ties cleanly into #63.
- `OPS_USER` loses manual status-transition power. May need user-comms inside the org (separate from LSP partner comms).
- **#85 / #135 — CLOSED** (2026-06-02) — orphan `requestDisbursementForLsp` removed; document upload persists then auto-approves outside the persist TX; `LoanApplicationStatusTransitioner` enforces lifecycle edges. See § #85 / § #135.

**Dependencies / sequencing:**
- (a) → (b) → (c).
- (a) ships independently.
- (b) removes the disbursement entry-point that triggered #85, but does not fully close #85 (orphaned method body + sibling pattern in `LoanDocumentService`); depends on #61's adapter-seam decision being upheld.
- (c) provides the alert backbone that #63 (LSP disable), #81 (rate limits), #155 (failed-auth alerts) hook into; landing it earlier is helpful.
- Per #61's framing, `/mock-outcome` continues to serve as the simulated provider callback. Under PR (b), the worker fires the request; `/mock-outcome` still flips the application from `DISBURSEMENT_REQUESTED` to `DISBURSED`. That's intentional symmetry with the eventual real callback shape.

**Implementation status (2026-06-01, `main`):**

Shipped in three vertical slices (a → b → c) per the agreed design above. Backend test suite green (323 tests).

| Slice | Delivered | Primary code |
|-------|-----------|--------------|
| **PR (a)** | `POST …/status-transitions` is **SYSTEM_ADMIN-only**; `OPS_USER` → 403. Manual transitions append rule-engine context to audit notes and emit **`MANUAL_RULE_ENGINE_OVERRIDE`** via `AlertRuleEvaluationService`. | `LoanApplicationOpsController`, `LoanApplicationLifecycleService`, `LoanApplicationOpsControllerTest` |
| **PR (b)** | **`LoanDisbursementWorker`** + **`LoanDisbursementWorkerService`** (`app.disbursement.worker.*`). Picks up `APPROVED_PENDING_DISBURSAL` / `DISBURSEMENT_RETRY`, skips inactive LSPs (#63), last-line bound validation, mock auto-resolve in test. **Removed** `POST /api/v1/lsp/loan-applications/{id}/disbursement`. | `LoanDisbursementWorker.java`, `Issue62DisbursementWorkerIntegrationTest` |
| **PR (c)** | **`PATCH/GET /api/v1/lsp/borrowers/{id}/bank-details`**, **`PATCH /api/v1/internal/admin/borrowers/{id}/bank-details`**, **`POST …/disbursement-bank-check`** (validate without mutating profile). Migration **`V78`**: `borrower_bank_details_update_audit`, `loan_disbursement_bank_mismatch_log`. Webhook **`BORROWER_BANK_DETAILS_UPDATED`**. Alerts: **`BORROWER_BANK_DETAILS_VELOCITY`**, **`LSP_BOUND_VIOLATION`** on repeated bank mismatches. **No disbursement cooldown.** `TimeConfig` registers `Clock`; mismatch logs use **`REQUIRES_NEW`** so they survive the 422 response. | `BorrowerBankDetailsService`, `LspBorrowerApiController`, `Issue62BorrowerBankDetailsIntegrationTest` |

**TDD checklist (tracker vs shipped):**

| Test (tracker) | Status |
|----------------|--------|
| PR (a) 1–4 — OPS lockdown, admin transition, override alert, audit rule-engine context | **Done** — `LoanApplicationOpsControllerTest` |
| PR (b) 1 — tracer worker disburses | **Done** — `Issue62DisbursementWorkerIntegrationTest.workerPicksUpApprovedApplicationAndDisburses` |
| PR (b) 2 — skip in-flight duplicate | **Deferred** — adapter spy test not written |
| PR (b) 3 — retry then succeed | **Deferred** — adapter failure/retry test not written |
| PR (b) 4 — `DISBURSEMENT_RETRY` after cap + alert | **Deferred** — exhaustion test not written |
| PR (b) 5 — worker bound violation + reject | **Deferred** — last-line principal test not written |
| PR (b) 6 — removed LSP disbursement → 404 | **Done** — LSP compliance / integration coverage |
| PR (b) 7 — auto-approval not in disbursement path (#85) | **Done** — worker path + follow-up PR § #85 (orphan method deleted, document TX split, integration tests) |
| PR (b) extra — worker skips when LSP disabled (#63) | **Done** — `Issue62DisbursementWorkerIntegrationTest` |
| PR (c) 1–5 — bank PATCH audit/webhook, no cooldown, bank-check, mismatch + velocity alerts | **Done** — `Issue62BorrowerBankDetailsIntegrationTest` |
| PR (c) 6 — principal exceed → `LSP_BOUND_VIOLATION` alert | **Deferred** — 422 remains; alert layer not added (worker path validates bounds separately) |
| PR (c) 7 — schedule principal mismatch alert | **Done** — closed with **#137** (validator + LSP write-path `LSP_BOUND_VIOLATION` alerts) |

**Explicitly deferred to other issues (not forgotten):**

- **#64** — IP allowlist on `PATCH /bank-details` (and other LSP routes). **CLOSED** (2026-06-01) — surface-split UI/API allowlists; see § #64 below.
- **#81** — Rate limit on `PATCH /bank-details` and `/mock-outcome`.
- **#71 / #155** — Audit-row schema hardening for new audit tables.
- **#129** — Webhook signing format.
- **#137** — LSP-provided schedule principal-sum validator + `LSP_BOUND_VIOLATION` on schedule mismatch. **CLOSED** (2026-06-05) — see § #137 below.
- **PR (b) tests 2–5** — Worker adapter retry, in-flight dedupe, bound-violation on worker (optional hardening; core worker path is covered).

**#85 / #135 — closed (2026-06-02):** Follow-up PR landed (see § #85 / § #135). Orphan `requestDisbursementForLsp` deleted; `LoanDocumentService` splits persist vs auto-approve; `LoanApplicationStatusTransitioner` centralises transition and auto-approval guards (`STANDARD` / `MANUAL_OVERRIDE` / `WORKER` contexts).

---

---

### #63 — LSP cannot be disabled via Admin UI/API
**Labels:** gap, security, rbac · **Link:** https://github.com/sid12701/lms/issues/63 · **Status:** **CLOSED** — [PR #169](https://github.com/sid12701/lms/pull/169) merged 2026-06-01

**Problem (plain English):** If an LSP is compromised, the only way to stop them is hand-editing the database. There is no admin endpoint to disable an LSP, and even if you flip the DB flag, the API doesn't check it — only the per-client status.

**Possible fixes:**
1. **Hard-disable (status flip + tv bump + cancel pending)** — immediate kill; existing JWTs 401 on next request.
2. **Soft-disable (status flip only; tokens live to expiry)** — minimal blast radius, but slow.
3. **Per-action quarantine** — flag the LSP "under review"; new requests blocked, in-flight allowed.

**Recommended:** Option 1 with audit. When you disable, you mean it — anything less and an incident-response page becomes a 30-minute waiting game.

**Effect on app:** New `PUT /lsps/{id}/status` admin endpoint. On disable: API clients flipped to INACTIVE, `tokenVersion` bumped on LSP, any pending disbursement short-circuited, audit row written. LSP partner sees 401 on next call instead of silent processing.

**Detailed solution after discussion (2026-05-31):**

**Audit findings (worse than the doc implied).** `Lsp` and `ApiClient` carry no `tokenVersion` column today — only `AppUser` does. `LspStatus` is checked in only 4 places (`LoanApplicationLifecycleService:122`, `LoanAutoApprovalRuleEngine:66`, `LspProductCatalogService:25`, `LspOptionsController:26`) and the JWT auth filter is not one of them. `LspAdminController` exposes no status-mutation endpoint at all. The kill chain is broken in five distinct places.

**Decisions locked:**
1. **Hard kill** via `tokenVersion` bump (audit doc Option 1). Existing JWTs 401 on the next request.
2. **Binary status model** (`ACTIVE` / `INACTIVE`). No `UNDER_REVIEW` quarantine state — quarantine is achieved by toggling status with documented reason.
3. **Bundle #63 + #79 + #93** into a single coherent kill-chain PR. Same auth-filter changes touch all three.
4. **Re-activation does not reset `tokenVersion`.** LSP must re-issue credentials and re-onboard. Strongest hygiene; matches banking norms.

**Implementation shape (single PR closes #63 + #79 + #93):**

1. **Schema** (Flyway):
   - `ALTER TABLE lsps ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;`
   - `ALTER TABLE api_clients ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;`
   - No backfill needed; defaults to 0 matching the initial-issue JWT claim.
2. **Admin endpoint** — `PUT /api/v1/internal/admin/lsps/{lspId}/status` (`SYSTEM_ADMIN`) with body `{status: ACTIVE|INACTIVE, reason: <enum from a small fixed set: SECURITY_INCIDENT | COMPLIANCE | OFFBOARDING | OPERATIONAL>, note: <free text>}`. Mandatory reason; mandatory note. Mirrors the API-client status pattern that already exists in `ApiClientAdminController`.
3. **JWT token claims** — issuance (in `AuthController` / `ApiClientAuthenticationService`) adds:
   - `tvLsp` (LSP's current `tokenVersion`)
   - `tvApiClient` (API client's current `tokenVersion`)
   - Existing `tv` claim for `AppUser` remains.
4. **Auth filter checks (every LSP-scoped request)** — extend the existing JWT auth filter to:
   - Load LSP by ID; if `lsp.status != ACTIVE` → 401 `LSP_INACTIVE`.
   - If `lsp.tokenVersion != tvLsp claim` → 401 `LSP_TOKEN_REVOKED`.
   - Load `ApiClient` by ID; if `apiClient.status != ACTIVE` → 401 `API_CLIENT_INACTIVE`. **(Closes #93's missing-ACTIVE-check.)**
   - If `apiClient.tokenVersion != tvApiClient claim` → 401 `API_CLIENT_TOKEN_REVOKED`. **(Closes #79.)**
   - LSP+client cached by `(id, version)`; cache invalidation handled by the admin endpoint (same pattern as #83/#142 will add for IP allowlist).
5. **Disable cascade** — `LspStatusService.disable(lspId, reason, note, actor)` in one transaction:
   - `lsp.status = INACTIVE`; `lsp.tokenVersion++`.
   - For each `ApiClient` under LSP: `status = INACTIVE`; `tokenVersion++`.
   - Write audit row `LSP_DISABLED` with `actor`, `reason`, `note`, `cascadedClientCount`, IP, correlation ID.
   - Fire `LSP_DISABLED` alert through `AlertRuleEvaluationService` (high severity).
6. **In-flight cascade — free under #62.** The disbursement worker (#62 PR (b)) already filters by `lsp.status == ACTIVE`. Pending applications stay where they are; on re-activation they resume. No separate cancellation logic; pending loans are not invalidated — invalidation requires explicit admin action. If #62 hasn't landed yet when this PR ships, add the `status == ACTIVE` check directly to the (still-existing) synchronous disbursement path so the gap is closed regardless of ordering.
7. **Re-activation** — `lsp.status = ACTIVE`; `tokenVersion` deliberately stays at the bumped value. Audit row `LSP_REACTIVATED`. LSP cannot authenticate until each API client's secret is rotated via the existing rotate-with-reveal flow (`ApiClientAdminController`). Two-step recovery: re-activate, then rotate-and-distribute.
8. **What we deliberately do not change:**
   - The four existing `status == ACTIVE` filter points stay as they are — they're correct.
   - LSP creation still defaults to `ACTIVE`.
   - No new state in the enum (binary by user decision).

**Closes as side effects:**
- **#79** — `ApiClient.tokenVersion` added and checked; disable means disable. Same fix, free.
- **#93** — `ApiClientAuthenticationService.lookupByClientId` becomes a single canonical path (filter does the check; refresh path falls under the same auth filter). The missing-ACTIVE-check at refresh disappears as a structural matter, not a patch.

**TDD plan (vertical slices, behaviour through the public API):**

Tracer first; each subsequent test extends what the previous proved.

1. **TRACER — `disabling_LSP_causes_existing_api_client_token_to_401_on_next_request`.** Bootstrap an ACTIVE LSP + API client; exchange creds for a JWT; assert `GET /api/v1/lsp/loans` returns 200. SYSTEM_ADMIN `PUT /lsps/{id}/status` with `INACTIVE`. Same JWT → 401 with body code `LSP_TOKEN_REVOKED`. This is THE locked-in behaviour — survives any internal refactor of how tokenVersion is stored, where the filter check lives, or how the claim is named.
2. `disable_endpoint_requires_SYSTEM_ADMIN_and_rejects_without_reason`. `OPS_USER` → 403. `SYSTEM_ADMIN` without `reason` field → 400 `REASON_REQUIRED`. `SYSTEM_ADMIN` with bogus reason value → 400 `INVALID_REASON`.
3. `disable_writes_audit_row_with_actor_reason_note_and_cascade_summary`. After disable, query Audit Explorer (`GET /audit-events?eventType=LSP_DISABLED&lspId=...`) → single row with actor, reason, note, `cascadedClientCount`, correlation ID.
4. `disable_fires_LSP_DISABLED_alert`. After disable, alert visible in Audit Explorer under `alertType=LSP_DISABLED` with high severity.
5. `disable_cascades_INACTIVE_status_to_every_api_client_under_the_lsp`. Two API clients under LSP. Disable. `GET /api-clients?lspId=...` shows both as `INACTIVE`. (Closes #79's status-cascade half.)
6. `disable_cascades_tokenVersion_bump_to_every_api_client_so_each_clients_existing_token_dies`. Pre-disable: each client has a working JWT. Post-disable: each fails 401 independently. (Closes #79's tv-bump half.)
7. `disabling_one_LSP_does_not_affect_other_LSPs`. Two LSPs A and B; disable A; B's tokens still 200. Isolation invariant.
8. `re_activation_does_not_revive_existing_tokens`. Disable, fail with 401, re-activate, same JWT → still 401. Verifies the "don't reset tv" decision.
9. `re_activation_plus_credential_rotation_issues_a_working_token`. Re-activate, then rotate API client secret via the existing rotate-with-reveal endpoint, exchange new secret for JWT, request succeeds. End-to-end recovery path; doc-able as the runbook.
10. `worker_skips_INACTIVE_LSP_for_pending_disbursements` (depends on #62). Approved loan under LSP A; disable A; run worker → loan stays `APPROVED_PENDING_DISBURSAL`; adapter not invoked (boundary-mock spy).
11. `worker_resumes_disbursement_after_LSP_re_activation`. Re-activate; next worker cycle fires the adapter; loan progresses.
12. `api_client_refresh_token_path_rejects_INACTIVE_LSP_or_INACTIVE_client`. (Closes #93.) POST refresh-token under an LSP that was disabled after the access token issued; under an API client that was independently disabled. Both → 401 with the matching error code.
13. `creating_a_new_application_under_INACTIVE_LSP_fails_at_lifecycle_service` (regression-guard for the existing `LoanApplicationLifecycleService:122` check; locks it in so a future refactor doesn't accidentally remove it).

**Mocks (per TDD doc):**
- `AlertRuleEvaluationService` — internal, not mocked; alerts asserted via the Audit Explorer query API.
- `Clock` — system boundary; mockable for `disabledAt` timestamp determinism.
- `LoanDisbursementAdapter` — system boundary; spy-mocked in the worker test (test 10) to assert non-invocation.
- No mocking of `LspRepository`, `ApiClientRepository`, the JWT verifier, the auth filter, or the lifecycle service.

**Behaviours NOT in this issue's tests (owned elsewhere):**
- Per-IP allowlist enforcement → #64.
- Cache TTL / multi-replica invalidation of the LSP/client lookup → #83/#142 pattern; track separately for the LSP-status cache if needed.
- Webhook URL change audit / signing-secret one-shot reveal → #153 / #72.
- Failed-auth alert (brute force) → #155.
- Audit Explorer query-param filter pushdown (free-text + correlationId) → #76.

**Effect on app:**
- Admin gains a real disable button: incident response goes from "hand-edit DB across replicas and hope" to "click + 1 second + verifiable via audit + alert."
- LSP partner sees 401 on next request after disable. Re-activation requires deliberate credential rotation — strongest hygiene.
- Per-client token death closes #79; refresh path closes #93. Three issues, one coherent PR.
- New LSP onboarding flow unchanged (status defaults to `ACTIVE`).
- Two new audit event types (`LSP_DISABLED`, `LSP_REACTIVATED`) and one new alert type (`LSP_DISABLED`) visible in Audit Explorer.
- `JWT` payload grows by two int64 fields — negligible.

**Dependencies / sequencing:**
- Independent of #62 functionally; benefits from #62's worker for the in-flight cascade (test 10/11). Can ship first or after #62.
- Should ship before #155 (failed-auth alerts) because those alerts will want a "candidate-for-disable" signal that this PR's admin endpoint provides.
- Aligns conceptually with #83/#142 (admin endpoint + cache invalidation + alert) — same shape; consider doing both with the same author for consistency.

---

**Implementation status — CLOSED (2026-06-01)**

| Field | Value |
|-------|--------|
| **GitHub** | [#63](https://github.com/sid12701/lms/issues/63) closed |
| **PR** | [#169](https://github.com/sid12701/lms/pull/169) merged to `main` |
| **Commit** | `fix(#63): LSP disable kill chain with admin UI and audit trail` |
| **Repo docs** | `docs/adr/0002-lsp-disable-kill-chain.md`, `docs/API-references/api-spec.md`, `docs/backend-documentation.md`, `docs/UI pages.md` |

**What shipped (matches the locked-in design above):**

**Backend**
- Flyway **V77**: `lsp.token_version`, `api_client.token_version`, table `lsp_audit_event`.
- `PUT /api/v1/internal/admin/lsps/{lspId}/status` — `status` (`ACTIVE` \| `INACTIVE` \| `DISABLED`), `reason` (`SECURITY_INCIDENT` \| `COMPLIANCE` \| `OFFBOARDING` \| `OPERATIONAL`), `note` (required).
- `GET /api/v1/internal/admin/lsps/{lspId}/audit-events` — status-change history.
- `LspStatusService`: disable bumps LSP + all clients' token versions, deactivates clients, audit `LSP_DISABLED`, `LSP_DISABLED` ops alert; reactivate sets LSP `ACTIVE` only (no tv reset).
- `ApiClientJwtSessionValidator` + JWT claims `tvLsp` / `tvApiClient` on issue/refresh; error codes `LSP_INACTIVE`, `LSP_TOKEN_REVOKED`, `API_CLIENT_INACTIVE`, `API_CLIENT_TOKEN_REVOKED`.
- `400 STATUS_UNCHANGED` when target status equals current (no silent no-op).
- Integration tests: `LspStatusKillChainIntegrationTest` (tracer disable → 401, cascade, audit, alert, isolation, reactivate + rotate recovery, refresh blocked).

**Partial closure bundled here (as planned):**
- **#79** — per-client `tokenVersion` + cascade on disable.
- **#93** — parent LSP + client `ACTIVE` enforced on auth/refresh via filter (not only at four lifecycle call sites).

**frontend-2 (`/lsps`)**
- Removed misleading **Edit LSP** flow (never persisted status correctly on old bundles).
- **Status** → `LspStatusChangeDialog` (reason, required note, disable warning).
- **Audit** → `LspAuditEventsDialog` (`GET …/audit-events`).
- **Details** read-only; table actions: Details · Status · Audit · Webhook.
- List/audit cache: no GET dedupe on admin reads, immediate row update + refetch after status change; audit dialog opens after success.

**Ops / deploy notes (learned during verification):**
- Backend JAR must include `PUT …/status` — old runtime returns **404** and UI appears unchanged.
- `frontend-2` dev server must be restarted from current `main` — stale bundle showed **Edit** and only `GET` the LSP (no disable).
- Target database must be at **Flyway v77+** on the instance the backend uses.

**TDD plan — done vs deferred:**

| Planned test | Status |
|--------------|--------|
| 1–9 (disable kill chain, audit, alert, cascade, isolation, reactivate, rotate recovery, refresh) | **Done** in `LspStatusKillChainIntegrationTest` |
| 10–11 (disbursement worker skips/resumes on disable) | **Deferred** — depends on #62 worker landing; lifecycle checks remain at existing call sites |
| 13 (regression on `LoanApplicationLifecycleService` INACTIVE check) | **Not added** as separate test; existing service check unchanged |

**Deliberately unchanged (still true post-merge):**
- Per-IP allowlist → **#64** (**CLOSED** 2026-06-01 — see § #64).
- Multi-replica LSP/client cache invalidation → **#142** (process-local cache invalidation on allowlist mutations shipped in #64; Redis-shared cache deferred).
- Disbursement worker in-flight behaviour → **#62** (tests 10–11 above).

**Tracker doc only:** this closure block; implementation lives on `main` as above.

---

---

### #64 — Per-client IP allowlist stored but not enforced
**Labels:** gap, security, rbac · **Link:** https://github.com/sid12701/lms/issues/64 · **Status:** **CLOSED** — [PR #170](https://github.com/sid12701/lms/pull/170) merged 2026-06-01 (surface-split UI/API allowlists; per-client table removed)

**Problem (plain English):** The admin UI let you set "this API client may only call from these IPs," but the filter only enforced at the LSP level, not per-client. A leaked client secret could be used from anywhere.

**Resolution (shipped):** Implemented **Flavour A — surface split** (see detailed design below), not GitHub's per-client matcher path. Two LSP-level lists (`lsp_ui_ip_allowlist`, `lsp_api_ip_allowlist`); `api_client_ip_allowlist` dropped with Flyway migration of rows into the parent LSP API list. Enforcement at **token issuance** (login + client-credentials) and on `/api/v1/lsp/**` via `LspSurfaceIpAllowlistFilter`. Per-surface flags `enforce_ui_allowlist` / `enforce_api_allowlist` with `422 ALLOWLIST_EMPTY_CANNOT_ENFORCE` guard. Cache invalidated after commit on mutations (**#83** delta for allowlist). **frontend-2:** LSP admin **IP allowlists** dialog (UI + API sections); per-client allowlist editor removed from API client create/table.

**Possible fixes:**
1. **Add per-client matcher path; fall back to LSP rules** — keeps current LSP rules valid while adding granularity.
2. **Drop per-client UI and only support LSP-level** — simpler but reduces a control product already advertises.
3. **Make per-client mandatory for prod LSPs** — strongest, but breaks existing client configs that have no entries.

**Recommended:** Option 1 for the fix, Option 3 as a follow-up policy after migration. Don't break existing clients on day one.

**Effect on app:** Filter resolves matcher by `(lspId, clientId)`. Disallowed IP → 403 with audit. No partner-visible change unless they configured rules but were not enforced — those now actually start denying.

**Detailed solution after discussion (2026-05-31):**

**Reframe — surface split, not per-card granularity.** The audit doc framed this as "add per-client matcher path." Per the user's redesign, the cleaner model is to split allowlists by **surface (UI vs API)** instead of layering "LSP + per-card." The threat profiles are different (humans in offices vs machines in data centres), the IP ranges are different, and the per-card layer was a leaky abstraction over what is really a surface-level distinction. Per-card entries are deleted; existing per-card CIDRs migrate into the LSP-level API list.

**Audit findings.** Today's `LspIpAllowlistFilter`:
- Applies to every URL starting with `/api/v1/lsp/*` — covers both LSP UI and LSP API requests with one combined list.
- Reads only `LspIpAllowlistEntry`. `ApiClientIpAllowlistEntry` exists as data + repo + `ApiClientManagementService` read/write, but the filter never consults it.
- Does NOT protect the login endpoint (`/api/v1/auth/...`) — JWTs are issued from anywhere and the filter only catches later requests.
- Caches `lspId → matchers` for 60s, process-local; cache not invalidated on admin mutations (this is #83 and #142).
- Writes no audit row on rejection (`log.warn` only) — partial #154.

**Decisions locked:**
1. **Flavour A — pure surface split.** Two LSP-level lists: `lsp_ui_ip_allowlist` and `lsp_api_ip_allowlist`. Drop `api_client_ip_allowlist` entirely.
2. **IP check at token issuance.** Both LSP-UI login and LSP-API-client token issuance check the appropriate surface's allowlist before issuing a JWT. A JWT is never minted for a non-allowed IP. Existing per-request filter also stays in place — defence in depth.
3. **Per-surface enforcement flags with guard rail.** `Lsp.enforce_ui_allowlist` and `Lsp.enforce_api_allowlist` (both default `false`). When `true` + empty list → deny-all. Admin endpoint refuses to set a flag to `true` while its surface's list is empty (returns `422 ALLOWLIST_EMPTY_CANNOT_ENFORCE`). Prevents accidental lock-out.
4. **Bundle scope.** This PR closes **#64 + half of #154 (allowlist add/remove + rejection audit) + #83 (immediate cache invalidation on mutations)**. **#142** (Redis-shared cache for multi-replica) is deferred — different architectural decision.
5. **Internal staff (SYSTEM_ADMIN / OPS_USER) IP allowlist** — out of scope for #64. Separate ticket.

**Implementation shape:**

1. **Schema (Flyway, one migration):**
   - `CREATE TABLE lsp_ui_ip_allowlist` mirroring the current `lsp_ip_allowlist` shape (id, lsp_id, cidr, description, created_at, updated_at; unique on `(lsp_id, cidr)`).
   - `RENAME TABLE lsp_ip_allowlist TO lsp_api_ip_allowlist`. Existing rows preserved — they were always LSP-API-wide.
   - `ALTER TABLE lsps ADD COLUMN enforce_ui_allowlist BOOLEAN NOT NULL DEFAULT FALSE`.
   - `ALTER TABLE lsps ADD COLUMN enforce_api_allowlist BOOLEAN NOT NULL DEFAULT FALSE`.
   - Data step: for each row in `api_client_ip_allowlist`, insert the CIDR into `lsp_api_ip_allowlist` under the API client's parent LSP (skip duplicates via the unique constraint).
   - `DROP TABLE api_client_ip_allowlist`.

2. **Filter** — rename `LspIpAllowlistFilter` → `LspSurfaceIpAllowlistFilter`. New logic:
   - From the JWT, determine the surface by inspecting the role: `LSP_API_CLIENT` → API; `LSP_UI_READ` / `LSP_UI_WRITE` → UI.
   - Load the corresponding `(lspId, surface)` matchers and enforcement flag from cache.
   - Decision:
     - List has entries → must match; mismatch → 403 `IP_NOT_ALLOWED` + audit row + alert.
     - List empty + enforce=true → 403 `IP_ENFORCEMENT_EMPTY_LIST` + audit + alert (operational alarm — should never happen if admin guard works; safety net).
     - List empty + enforce=false → allow (today's behaviour, per surface).
   - Cache key now `(lspId, surface)`; invalidation surface doubles.

3. **Token-issuance checks** (new):
   - `AuthController` LSP-user login path: after credentials validated, resolve LSP, check request IP against UI allowlist (respecting enforcement flag), reject 403 `LOGIN_IP_NOT_ALLOWED` + audit row if outside. No token issued.
   - `ApiClientAuthenticationService.issueToken`: same check against API allowlist, reject 403 `API_CLIENT_IP_NOT_ALLOWED`. No token issued.
   - Both write audit rows on success and rejection — useful for forensic reconstruction.

4. **Admin endpoints:**
   - Rename existing `LspIpAllowlistAdminController` route to `/lsps/{lspId}/api-ip-allowlist` (manages the API list). Response shape unchanged.
   - New `LspUiIpAllowlistAdminController` at `/lsps/{lspId}/ui-ip-allowlist`. Same shape.
   - Both controllers: on every mutation, invalidate cache for `(lspId, surface)` and write audit row.
   - New `PUT /lsps/{lspId}/allowlist-enforcement` endpoint with `{enforceUi, enforceApi}` body. Refuses to set a flag to `true` while its surface's list is empty. Writes audit row + fires `LSP_IP_ENFORCEMENT_TOGGLED` alert. Highly visible.
   - Drop `ApiClientIpAllowlistAdminController` plans; delete `ApiClientIpAllowlistEntry` + `ApiClientIpAllowlistRepository` + their references in `ApiClientManagementService` + their response fields in `ApiClientAdminController`.

5. **Cache invalidation timing (#83 closed):**
   - Every mutation endpoint (`POST` / `DELETE` per-surface, `PUT` enforcement, plus the LSP create/update paths that affect the LSP record itself) calls `filter.invalidateCache(lspId, surface)`.
   - Cache TTL stays at 60s as a backstop, but the explicit invalidation means changes take effect on next request.
   - Multi-replica drift (#142) acknowledged and deferred — a separate Redis-shared-cache PR.

**TDD plan (vertical slices, behaviour through public interfaces):**

Tracer first; each subsequent test responds to what the previous proved.

1. **TRACER — `api_client_token_issuance_from_non_allowed_ip_is_rejected_when_enforcement_is_on`.** Setup: LSP with API allowlist containing 10.0.0.0/24, `enforce_api_allowlist=true`. POST `/api/v1/auth/api-clients/token` with valid client_id+secret from IP 192.168.1.1 → 403 `API_CLIENT_IP_NOT_ALLOWED`; no token issued; audit row visible via Audit Explorer. This is THE behaviour — locked through the public token-issuance API; survives any internal refactor of the IP check location.
2. `ui_login_from_non_allowed_ip_is_rejected_when_ui_enforcement_is_on`. Symmetric for the UI login endpoint against `lsp_ui_ip_allowlist`.
3. `request_to_lsp_api_endpoint_with_valid_token_but_from_non_allowed_ip_is_rejected`. Token was issued from an allowed IP (or before enforcement); later request from a non-allowed IP → 403 `IP_NOT_ALLOWED`. Confirms filter still enforces per-request.
4. `api_client_token_used_against_lsp_api_endpoint_from_ui_allowed_but_api_disallowed_ip_is_rejected`. Surface separation: a CIDR that's only in the UI list does not unlock the API surface.
5. `ui_session_token_used_against_lsp_api_endpoint_from_api_allowed_but_ui_disallowed_ip_is_rejected`. Surface separation in the other direction.
6. `empty_api_allowlist_with_enforcement_off_allows_any_ip`. Today's behaviour preserved per-surface.
7. `empty_api_allowlist_with_enforcement_on_rejects_all_requests`. Strict mode works.
8. `admin_cannot_enable_api_enforcement_flag_while_api_list_is_empty`. PUT `{enforceApi: true}` with no entries → 422 `ALLOWLIST_EMPTY_CANNOT_ENFORCE`. Guard rail.
9. `admin_can_enable_api_enforcement_after_adding_at_least_one_entry`. Add a CIDR, then enable — succeeds.
10. `admin_can_disable_enforcement_anytime_even_with_empty_list`. Recovery path always open.
11. `adding_a_cidr_immediately_takes_effect_on_next_request_no_60s_wait`. Closes #83. Add entry; next request from that IP → 200 in <100ms.
12. `removing_a_cidr_immediately_takes_effect_on_next_request`. Symmetric.
13. `every_allowlist_mutation_writes_an_audit_row`. Closes the mutation half of #154. POST adds row; DELETE adds row; PUT enforcement adds row.
14. `every_filter_rejection_writes_an_audit_row`. Closes the rejection half of #154. Each 403 generates an audit event with actor, ip, surface, lspId.
15. `flipping_enforcement_flag_fires_LSP_IP_ENFORCEMENT_TOGGLED_alert`. Visibility for ops.
16. `flyway_migration_copies_per_card_cidrs_into_parent_lsp_api_list`. Data-migration test. Pre-state: 3 per-card entries across 2 clients under 1 LSP. Post-state: 3 entries in `lsp_api_ip_allowlist` for that LSP (after dedup); `api_client_ip_allowlist` table gone.
17. `api_client_admin_response_no_longer_exposes_per_card_ipAllowlist_field`. Locks in the API contract change.

**Mocks (per TDD doc):**
- `Clock` — boundary, mockable for audit-timestamp determinism.
- `AlertRuleEvaluationService` — internal, **not** mocked; alerts asserted via the Audit Explorer query API.
- No mocking of repositories, the filter, the JWT verifier, or the token-issuance services. All exercised through real HTTP requests against a real Spring context.

**Behaviours NOT in this issue's tests (owned elsewhere):**
- Redis-shared cache across replicas → #142.
- Per-request rate limiting on token-issuance endpoints → #81.
- Internal staff (admin/ops) IP allowlist → separate future ticket.
- Webhook URL change audit → #153.

**Effect on app:**
- Each LSP gains two independent surface gates with optional strict mode.
- Stolen LSP UI credentials cannot be used from non-office IPs.
- Leaked LSP API client secrets cannot be used from non-data-centre IPs.
- Per-card admin UI/endpoints disappear — fewer surfaces for ops to manage.
- Audit Explorer gains: allowlist mutations, enforcement-flag toggles, every 403 rejection at filter and token-issuance time.
- LSP partners with currently-configured per-card entries see them migrated to LSP-level API list — net behaviour change is "now actually enforced." Comms note: any LSP relying on a per-card carve-out that was narrower than the LSP-level list will lose that narrowing. Per Flavour A's tradeoff (and the user's preference for the simpler model), this is accepted.
- Closes #64 + half of #154 + #83. #142 tracked separately.

**Dependencies / sequencing:**
- Independent of #62 and #63 functionally. Can ship first, parallel, or last.
- Per-card deletion happens in this PR's migration; downstream consumers (UI screens) need to drop the per-card display in lockstep.
- If #63 ships first, the auth-filter changes there compose cleanly (status + tokenVersion + IP check in one filter chain).

---

**Implementation status — CLOSED (2026-06-01)**

| Field | Value |
|-------|--------|
| **GitHub** | [#64](https://github.com/sid12701/lms/issues/64) closed via [PR #170](https://github.com/sid12701/lms/pull/170) |
| **Migration** | `V79__lsp_surface_ip_allowlist.sql` — `lsp_ui_ip_allowlist`; rename `lsp_ip_allowlist` → `lsp_api_ip_allowlist`; migrate + drop `api_client_ip_allowlist`; `enforce_ui_allowlist` / `enforce_api_allowlist` on `lsp` |
| **Backend** | `LspSurfaceIpAllowlistService`, `LspSurfaceIpAllowlistFilter`, `IpAllowlistCacheInvalidation`; `AuthController` IP checks on LSP UI login + API token; admin routes `/ui-ip-allowlist`, `/api-ip-allowlist`, `/allowlist-enforcement`; removed `ApiClientIpAllowlist*` |
| **Tests** | `Issue64LspSurfaceIpAllowlistIntegrationTest` (5 tracer slices: token reject/allow, surface separation on LSP route, empty-list enforce guard, immediate cache invalidation) |
| **frontend-2** | `LspIpAllowlistDialog`, `useLspIpAllowlistAdmin`; `LspDetailsDialog` entry point; API client create/table no longer expose per-client `ipAllowlist` |
| **Deferred** | Full 17-test TDD matrix; rejection/mutation audit rows (#154 remainder); Redis multi-replica cache (#142); internal staff IP allowlist |

**TDD checklist (tracker vs shipped):**

| Planned test | Status |
|--------------|--------|
| 1 — API token from non-allowed IP when enforce on | **Done** |
| 2 — UI login from non-allowed IP | **Deferred** |
| 3 — Per-request filter after valid token | **Partial** — covered indirectly via LSP route + surface test |
| 4–5 — UI vs API surface separation | **Partial** — test 4 via `apiClientTokenUsedFromUiAllowedButApiDisallowedIpIsRejectedOnLspRoute` |
| 6–7 — empty list enforce off/on | **Partial** — test 8 (cannot enable on empty list) |
| 8–10 — admin enforcement guard / recovery | **Partial** — test 8 |
| 11–12 — immediate cache invalidation | **Done** — test 5 |
| 13–17 — audit rows, alerts, migration, API contract | **Deferred** |

**Tracker doc only:** this closure block; implementation on `main` after merge.

---

---

### #65 — LSP API responses leak PAN/bank/IFSC/account-holder in plaintext
**Labels:** gap, security, data-isolation · **Link:** https://github.com/sid12701/lms/issues/65 · **Status:** **CLOSED — DEFERRED to pre-launch** (2026-06-06). Cluster with **#69**, **#123**, **#139**, **#157** — not wontfix; un-defer on first live LSP / real PII / audit trigger.

**Problem (plain English):** When an LSP fetches their own loan list, the response includes the borrower's PAN, bank account number, IFSC, and account holder name in plain text. Only Aadhaar is masked. If their API token leaks, all of that PII leaks too — bulk, from one credential.

**Possible fixes:**
1. **Mask everything by default; explicit, audited reveal endpoint** — strongest; matches the existing borrower-PII-reveal pattern.
2. **Mask in list, reveal in detail** — convenient but the detail endpoint becomes a bulk-exfil tool.
3. **Per-field opt-in via LSP config** — flexible but moves the security decision to LSP operators.

**Recommended:** Option 1. Same masking utility for every response builder; one reveal path with mandatory audit. Bulk PII shouldn't require bulk consent fatigue.

**Effect on app:** LSP responses get masked fields (`****1234`, etc.). Any LSP integration that read raw PAN/bank from list/detail breaks until they switch to the reveal endpoint. Audit Explorer gains the reveal-event stream.

**Detailed solution after discussion (2026-05-31):**

**Decision: deferred to pre-launch. Issue stays open.**

**Why deferred** (not "skipped" — see trigger below):
- The product is **pre-launch** (confirmed under #62 and #64 — no live LSP integrations).
- No real borrower PII flows through prod yet; the bulk-exfil risk window is bounded to test/seed data.
- The same pre-launch framing applied to #61 (mock disbursement) — this fits the same risk model: build the proper control surface as part of the production cut, not before.
- Building the masking layer + reveal endpoint properly (per the audit findings below) is substantial work that competes with other P0 items whose mitigation matters now while pre-launch (kill chain #63, alert backbone #62, IP allowlist #64).

**Trigger to un-defer (any of these flips the decision):**
1. First live LSP integration onboards (real or production-shadow).
2. Real borrower PII lands in prod (KYC data, real PAN/Aadhaar/bank).
3. External compliance / RBI / DPDPA audit.
4. Any actual exfil incident, even on test data.

**Audit findings preserved for the future PR (so the homework isn't lost):**
- `LspLoanApplicationResponses.maskAadharNumber` (line 175) is the only masking helper in the entire backend. PAN, bank account, IFSC, account holder, address, employer, income — all raw in both list (line 79–125) and detail (line 13–77) builders.
- **No reveal endpoint exists.** Grep for `borrower-pii`, `revealBorrowerPii`, `maskPan`, `maskBank`, `maskIfsc` returns nothing. The audit doc's reference to "the existing borrower-PII-reveal pattern" was aspirational — the pattern needs to be built first.
- ~~The frontend's silent mock fallback (#78 / #145) hides this from operators today~~ **(fixed 2026-06-02, PR #171)** — internal sessions now surface real 4xx errors; LSP-role still uses the intentional mock router. Reveal endpoint work under #68/#65 remains deferred.

**Sketch of the eventual fix (to be elaborated when the trigger fires):**
1. Build a `BorrowerPiiProjection` at the projection layer (not the response builder) — so every consumer (LSP list/detail, admin list/detail, MIS preview, MIS CSV, reports) inherits the same masking. Aligns with the "one projection, two formatters" pattern that #123 calls for.
2. Default masks for sharp identifiers — PAN `XXXXXX1234`, bank account `XXXXXX1234` (last 4), IFSC first 4 + last 1 (`SBIN0****1`), Aadhaar keeps current `XXXXXXXX1234`. Broader masking (mobile, email, DOB, address, income, employer) is a follow-up policy call when the trigger fires.
3. **Reveal endpoint** mirrored on both surfaces:
   - `GET /api/v1/lsp/loan-applications/{id}/borrower-pii?fields=...&reason=...&note=...` (LSP — closes #65 / #139).
   - `GET /api/v1/internal/ops/loan-applications/{id}/borrower-pii?fields=...&reason=...&note=...` (admin — closes #68).
   - Reason from fixed enum: `KYC_REVIEW`, `FRAUD_INVESTIGATION`, `COMPLIANCE_AUDIT`, `BORROWER_SUPPORT`, `OTHER` (note ≥ 10 chars when OTHER).
   - Audit row per reveal: actor, applicationId, fields revealed, reason, note, IP, correlation ID.
   - Rate limit + per-borrower velocity alert added via #81 + the alert backbone from #62.
4. MIS CSV / report masking lands in the same PR (closes #69 / #157) because they share the projection.

**Cluster locked together (close all when this lands):**
- #65 (this issue — LSP list/detail leak).
- #68 (admin reveal endpoint missing; FE falls through to mock).
- #69 (MIS CSV download leaks raw PAN/bank while preview is masked).
- #139 (security-audit-delta dup of #65).
- #157 (R-2 dup of #69).
- #123 (preview vs CSV projection drift) — same projection layer fixes the structural cause.

**Effect on app while deferred:** none. The leak surface is acknowledged and tracked; risk is bounded by pre-launch state. Operators reading the audit doc see a single explicit decision rather than "still not done."

**Effect when un-deferred:** as in audit doc's original "Effect on app" — LSP and admin responses become masked by default; existing test integrations that read raw PAN/bank/IFSC stop working until they switch to the reveal endpoint; Audit Explorer gains the reveal-event stream; bulk-PII exfil becomes loud rather than silent.

---

---

### #66 — Repayment is strict per-installment with exact amount
**Labels:** gap · **Link:** https://github.com/sid12701/lms/issues/66

**Problem (plain English):** You can pay exactly one installment, exactly the scheduled amount. No partial payments, no overpayments, no lump-sum prepayments outside the rigid foreclosure flow. Real users do all three.

**Possible fixes:**
1. **Implement allocation logic** — payment lands as a transaction; allocator distributes across past-due → current → future principal.
2. **Stay strict, document and return a clean error code** — formalize current behavior with `PARTIAL_PAYMENT_NOT_SUPPORTED`.
3. **Strict + a separate "lump-sum prepayment" endpoint** — middle ground; partials still rejected.

**Recommended:** Option 1, but only after product sign-off on allocation order (regulatory in India for NBFC loans). It's the only option that matches real-world behavior; the others are bandages.

**Effect on app:** New `LoanPaymentTransaction.allocation` rows; reporting shows "amount paid vs allocated"; LSP API can record a single multi-EMI payment; foreclosure stays rigid but is no longer the only early-payoff path. Big spec change — needs ADR.

**Detailed solution after discussion (2026-05-31):**

**Decision: deferred to pre-launch. Issue stays open. Current strict-exact behaviour is intentional for now.**

**Audit findings (preserved for the un-defer PR):**
- `LoanRepaymentCommandService.createInstallmentPayment` (lines 78–131) requires `targetInstallmentId`; `validateExactInstallmentAmount` (LoanServicingSupportService:152) rejects any amount ≠ installment amount.
- `applyFullInstallmentPayment` (line 170) asserts installment is fully paid in one shot.
- `LoanPaymentTransaction.updateAllocation(amount, ZERO)` exists in the data model but is used in single-installment-full-payment shape only. The allocation table is ready for a richer engine; the code path is not.
- Side-effects on first payment: status DISBURSED → UNDER_REPAYMENT; webhook `LOAN_REPAYMENT_RECORDED`; if all installments cleared → loan closure + `LOAN_FULLY_REPAID` webhook.

**Why deferred (not skipped):**
- No live loans yet; strict-exact is operationally fine while only seed/test data flows. Any test integration today knows the exact EMI value.
- Building a proper RBI-compliant waterfall + per-product configurability is substantial work that competes with kill-chain (#63), alert backbone (#62), and IP-allowlist (#64) — all of which mitigate harm to *future* LSPs and ship value pre-launch.
- The data model already accommodates the future engine (allocation table is in place). No schema rework needed when the trigger fires.

**Trigger to un-defer (any of these):**
1. First real loan disburses to a real borrower (any NACH return will break strict-exact).
2. Product team formally documents the per-product RBI waterfall.
3. Any LSP onboarding signals real-world payment patterns (partial, lump-sum, batch).
4. RBI / DPDPA / NBFC compliance review.

**Design preserved for the un-defer PR (so the homework isn't lost):**

1. **Drop `targetInstallmentId` entirely.** New shape: `POST /payments` with `{amount, postedAt, channel, reference, idempotencyKey}`. System runs the waterfall. Removes the footgun where LSP picks a future installment while past-due exists.
2. **RBI-compliant waterfall** as the default product rule (stored on `LoanProduct` for future per-product variance):
   - Overdue interest → Overdue principal → Penal charges → Current interest → Current principal → Future principal.
3. **Overpayment policy (locked decision):** `amount > total outstanding` → 422 `OVERPAYMENT_NOT_ALLOWED`. LSPs must use `/foreclosure-quote` for loan closure. Strict separation between "regular payment" and "close the loan" — avoids ambiguous closure semantics, sidesteps the refund-queue problem, keeps the foreclosure path as the single authoritative closure channel.
4. **Partial payment policy:** `amount < first-outstanding-installment` → accept, allocate per waterfall (may leave installments still partially due). Real-world NACH partial returns then work natively.
5. **Multi-installment lump-sum:** `amount ≥ N × installment` → accept; waterfall clears overdue first, then current, then runs as prepayment against future principal. No separate endpoint needed for the lump-sum case once the waterfall exists.
6. **Per-installment allocation rows:** every payment writes N rows showing what landed against each installment (interest, principal, penal). Drives reporting (#160) and audit clarity.
7. **Existing side effects preserved:** DISBURSED → UNDER_REPAYMENT on first payment; `LOAN_REPAYMENT_RECORDED` webhook with the allocation breakdown in the payload; `LOAN_FULLY_REPAID` webhook only via the foreclosure path (since regular `/payments` cannot close).

**TDD plan (reserved for when this is built):**

When the trigger fires, the tracer is straightforward: `payment_of_one_installment_amount_clears_one_installment_via_waterfall` — verifies the engine produces the same outcome as today's strict-exact path for the simplest case. Then add tests for partial, lump-sum-clears-past-due, overpayment rejection, and per-product waterfall variants. All assertions through the LSP API + `/payments/{id}` allocation query, not against internal allocation methods.

**Bundle (when un-deferred):**
- Closes #66 + #131 (D-8 dup of #66 under the fragile-logic taxonomy).
- **Stays bundled separately:**
  - #137 (LSP_PROVIDED schedule arithmetic) — same engine reads installments; aligned but separable.
  - #94 / #124 (foreclosure date rigidity) — once `/payments` handles principal prepayment cleanly, foreclosure no longer needs to be the only early-payoff path. Closing #66 may relieve pressure on #94's design.
  - #160 (MIS fee parity) — if MIS reads from the allocation rows directly, fee parity is automatic. Separate ticket; same underlying source.

**Effect on app while deferred:** none — strict-exact behaviour stays. Documentation note: LSPs and ops should be aware that current behaviour is intentional pre-launch policy, not a bug.

**Effect when un-deferred:** as in audit doc's original "Effect on app" — `LoanPaymentTransaction.allocation` rows used in earnest; reporting shows "amount paid vs allocated per component"; LSPs can record multi-EMI or partial payments cleanly; foreclosure stays the only authoritative closure path; new ADR documents the per-product waterfall.

---

---

### #69 — MIS CSV download emits raw PAN/bank while preview is masked
**Labels:** gap, security, reporting-risk · **Link:** https://github.com/sid12701/lms/issues/69 · **Status:** **CLOSED — DEFERRED to pre-launch** (2026-06-06). Cluster with **#65**; closes with **#157**, **#123** as dupes.

**Problem (plain English):** Admin sees masked numbers on the screen, clicks Download, gets a file with full PII. Operators assume "what I see is what I get" and end up moving raw KYC data around in Excel.

**Possible fixes:**
1. **Mask by default in download; separate explicit "unmasked export" endpoint with step-up + audit + watermark** — safe default, opt-in for raw.
2. **Always mask** — simplest, but legitimate ops needs (regulator reports) require raw.
3. **Single endpoint with `?unmask=true` flag** — easy to mis-use; one click and you're back to today.

**Recommended:** Option 1. Mask in projection (so preview and download share one source), and require an explicit endpoint + audit for the rare unmask case.

**Effect on app:** CSV files become safe to handle by default. Existing report consumers expecting raw values break (intended). Audit Explorer gains "unmasked report downloaded by X" events.

**Detailed solution after discussion (2026-05-31):**

**Decision: deferred to pre-launch — same cluster as #65. Issue stays open.**

**Audit findings (correcting the audit-doc framing):**
- `AdminReportingService` builds one `MisRow` projection consumed by both `/portfolio-mis/preview` (JSON) and `/portfolio-mis` (CSV).
- Both surfaces include `panNumber`, `ifscCode`, `bankAccountNumber` raw. Only `maskAadhaar` (line 388) is applied.
- **Therefore the audit doc's "preview masked vs CSV leaks" framing is half-right.** The preview JSON is raw too; what operators see masked on the preview screen is **frontend-side formatting only**. The backend treats both surfaces identically — CSV just feels worse because there's no UI to soften it.
- This collapses the issue into the same shape as #65 (PII leak from a backend surface, no projection-layer masking) on the admin/reports plane.

**Why deferred (same reasoning as #65):**
- Pre-launch; no real borrower PII in prod yet; bulk-exfil risk bounded.
- Building the projection-layer masking + unmasked-export endpoint + audit + per-report ADR is substantial work; competes with other P0s that mitigate harm to *future* LSPs.
- The eventual fix is one projection serving every consumer (LSP responses, admin responses, MIS preview, MIS CSV) — building it piecemeal across surfaces invites drift. Best done as one move.

**Trigger to un-defer:** same as #65 (any of):
1. First live LSP integration onboards.
2. Real borrower PII lands in prod.
3. External compliance / RBI / DPDPA audit.
4. Any actual exfil incident, even on test data.

**Design preserved (incremental on top of #65's design):**
- The `BorrowerPiiProjection` from #65's design also feeds `MisRow`. One mask helper used everywhere; preview and CSV inherit it. No more "different formatter, different raw" drift (which is #123's structural cause).
- **Separate "unmasked export" path:** `GET /api/v1/internal/reports/portfolio-mis?unmasked=true&reason=...&note=...` (`SYSTEM_ADMIN` only) — produces raw values for the rare ops case (regulator filings, internal reconciliation). Mandatory reason + note; per-row audit (file size, row count, fields included, actor, IP, correlation ID); fires `BULK_PII_DOWNLOAD` alert on the alert backbone from #62.
- CSV filename embeds masking state: `portfolio_mis_2026-05-31.csv` vs `portfolio_mis_2026-05-31_unmasked.csv` — visible to anyone receiving the file.

**Cluster (closes together when un-deferred):**
- #65 (LSP list/detail leak) — primary anchor.
- #68 (admin reveal endpoint missing).
- #69 (this issue — MIS CSV leak).
- #123 (preview vs CSV projection drift) — same projection layer is the fix.
- #139 (security-audit-delta dup of #65).
- #157 (R-2 dup of #69).

**Effect on app while deferred:** none — preview and CSV both continue returning the same shape (raw PAN/bank/IFSC, masked Aadhaar). Frontend continues to mask its display values. Operators reading this doc see the single explicit cluster decision rather than seven open un-resolved tickets.

**Effect when un-deferred:** as in audit doc's original "Effect on app" — CSV safe to handle by default; existing report consumers expecting raw values break (intended); Audit Explorer gains "unmasked report downloaded by X" events; bulk exports become loud rather than silent.

---

---

### #78 — Frontend MOCK_FALLBACK paths return synthesized data on backend 4xx
**Labels:** gap, mocked-flow, security · **Link:** https://github.com/sid12701/lms/issues/78 · **Status:** **CLOSED** — [PR #171](https://github.com/sid12701/lms/pull/171) merged 2026-06-02

**Problem (plain English):** The web app has 5+ places where, if the backend returns a 401, 403, or 404, it silently falls back to a built-in mock and shows fake data. An ops user denied permission can still see "data" — but it's invented.

**Possible fixes:**
1. **Delete fallback entirely** — strongest; tests rely on it but they should mock at MSW layer, not in app code.
2. **Gate via build-time env flag (`VITE_ENABLE_MOCK_FALLBACK`, default false in prod)** — pragmatic; same code, prod bundle has no fallback.
3. **Keep fallback but only for 5xx, never 4xx** — narrows the security hole; doesn't close it (5xx fallback can still hide auth issues).

**Recommended:** Option 2 as the immediate fix (prod build is clean), Option 1 as the cleanup after tests are refactored to MSW-only.

**Effect on app:** Production users see real error states (access denied, not found, expired session). Demo and local dev still work via mocks. Big improvement to operator trust — the UI now tells the truth.

**Detailed solution after discussion (2026-05-31):**

**Reframe — kill the inline fallback by deletion, not by env-flag.** The audit doc's Option 2 (env-flag-gate the existing pattern) is the half-measure. Per the user's decision, **no internal-session call site keeps the in-app `try { backend } catch { mock }` boilerplate.** The fallback dies completely; the LSP-role mock dispatch stays as a *separate, intentional concept* (mirrors #61's mock-by-design framing — pre-launch demo path, deferred to the eventual real-LSP-backend work).

**Why deletion beats env-flag:**
- Env-flag still leaves 14 copies of "if (env) try…catch…mock" — same boilerplate, just gated. Doesn't actually close #102 (duplication). Reviewers still have to verify each gate is correctly placed.
- Deletion makes the security property *structural* rather than configurational. A future contributor can't accidentally turn the flag back on in prod, because there's no flag and no fallback to turn on.
- Tests do **not** depend on the inline fallback. They register routes via `@/mocks/router#registerRoute` directly (e.g., `borrowers/api-list.test.ts`); MSW (`^2.14.5`) is in `package.json` but unused. The fallback was dev-convenience, not test-infra. Deleting it leaves tests untouched.

**The two concepts in the file (must stay separated):**
1. **Role-based routing** — `if (isInternalSession()) { backend } else { dispatch(mock) }`. LSP-role has no real backend yet; pre-launch this is the only path that works for LSP demo. **Stays.**
2. **Resilience fallback** — `try { backend } catch (4xx) { dispatch(mock) }`. Silently turns 401/403/404 into "successful render of fake data." **Dies.**

After this PR every internal-session call site is:
```ts
if (isInternalSession()) {
  return backendCall(...);            // throws on any error; ApiError surfaces to UI
}
return dispatch(...mock);             // LSP-role demo path — see ADR 0YYY, issue #78 footnote
```

**What we ARE doing:**

1. **Delete the inline fallback at all 14 sites** (5 in `loan-applications/api-detail.ts`, 1 in `loan-applications/api.ts`, 3 in `loan-applications/api-tabs.ts`, 1 in `audit/api.ts`, 1 in `home/api.ts`, 1 in `borrowers/api.ts`, 1 in `borrowers/api-list.ts`, 1 in `borrowers/api-tabs.ts`). The `try { … } catch (error) { if (!(error instanceof ApiError) || error.status >= 500) throw error; }` block is removed; the backend call becomes a straight `return await requestJson(...)`.
2. **Fix `fetchChecklistSafely` (related sub-bug spotted during audit).** The helper at `loan-applications/api-detail.ts:325-333` silently swallows every error and returns `[]`, which makes `docsComplete` resolve to `true` after a 401/403/5xx — a "ready to disburse" lie. Rename to `fetchChecklist`; on non-200 it throws like every other call. (A genuine empty checklist is still 200 + `[]` from the backend, which still resolves to `docsComplete = required.length === 0`.)
3. **Preserve LSP-role mock dispatch verbatim.** Add a brief one-line comment at each site: `// LSP-role: mock-router demo path, deferred per #78 — real LSP read API is a future workstream`. Make it grep-greppable so the next contributor knows it's intentional, not residue.
4. **No env flag added.** Adding `VITE_ENABLE_MOCK_FALLBACK` was the stop-gap; deletion makes it moot. Prod and dev share the same behaviour: real errors surface; LSP-role uses the mock router.
5. **Drop the unused `msw` dependency** from `frontend-2/package.json`. It was speculative — no `setupServer`/`http.get` imports anywhere. Removing it cleans the dependency surface.
6. **Duplicates closing in this PR:**
   - **#102 ([Q-5] FE mock-fallback boilerplate duplicated)** — closes as "duplication resolved by deletion, not by centralisation." The duplication was the try/catch fallback. With the fallback gone, the only remaining shared shape is the `isInternalSession()` role check — a 2-line decision, not boilerplate worth centralising.
   - **#117 ([D-4] same as #102, duplication taxonomy)** — close as dup of #102.
   - **#145 ([SEC-Δ-7] same as #78, security taxonomy)** — close as dup of #78.
7. **No lint rule added.** A lint rule for "don't write a catch-then-dispatch-mock fallback" presumes the pattern recurs. With the pattern gone, the rule has nothing to police — and absent prior art it would produce false positives on legitimate try/catch usage. Skip until/unless the pattern reappears.

**What we are NOT doing (explicit deferrals):**
- Not killing LSP-role mock routing (per user — needed for the pre-launch demo; full real-LSP-backend work is a separate later project).
- Not adding MSW. Tests already use the in-process mock router; the migration to MSW would be net-zero value pre-launch.
- Not adding tree-shake gating on `@/mocks/*` for prod. Prod still ships the mock router because LSP-role still uses it. Once LSP-role moves to a real backend, the entire `@/mocks/*` directory and the router itself can be tree-shaken under `import.meta.env.PROD`. Tracked as a future cleanup; not in this PR.

**Why this is the right move (under the user's TDD philosophy):**
- The fallback was a behaviour-coupling test smell at the application layer: it conflated transport policy ("how do I reach data") with routing policy ("which data source for which role"). Removing it surfaces the real interface — "internal-session → backend; LSP-role → mock" — as a 2-line decision per call site, not a 4-line ceremony with hidden side-effects.
- Tests written under this fix verify *observable behaviour at the public interface* (`fetchBorrowerDetail`, `fetchLoanApplicationDetail`, etc.) and do not couple to internal collaborators. Mocking happens at the genuine system boundary (`global.fetch`), not at the in-app dispatch layer.
- The duplication evaporates as a side-effect of fixing the root cause; we do not introduce a helper to centralise a pattern we're about to delete. (`#102` closes without a `fallbackOrThrow()` helper or similar — the simplest design is no design.)

**TDD plan (vertical slices, one test → one site → next test):**

Tracer bullet first; each subsequent test responds to what the previous slice revealed. All tests use `vi.spyOn(global, 'fetch')` to control the transport boundary (a genuine system boundary per the user's mocking guide) and call the public api-module functions; no mocks of `dispatch`, `requestJson`, `isInternalSession`, `loadStoredSession`, or any internal collaborator.

**Test ordering (one slice = one call site; do not write all tests upfront):**

1. **TRACER — `fetchBorrowerDetail_propagates_401_instead_of_returning_mock`.**
   - Arrange: stored session = SYSTEM_ADMIN; `vi.spyOn(global, 'fetch')` returns `Response(401, body='{"errorCode":"AUTH_EXPIRED"}')`.
   - Act: `await fetchBorrowerDetail("11111111-…")`
   - Assert: rejects with `ApiError` whose `status === 401`, `code === "AUTH_EXPIRED"`. No mock-router data leaks through.
   - Make pass by: deleting the `try/catch` in `borrowers/api.ts:233-242`. Tracer proves the path end-to-end.

2. **`fetchBorrowerDetail_still_dispatches_mock_for_LSP_role_session`.**
   - Arrange: stored session = LSP_USER; `registerRoute("GET", "/api/v1/borrowers/:id", () => ALICE_FIXTURE)`.
   - Act: `await fetchBorrowerDetail(ALICE_ID)`.
   - Assert: returns `ALICE_FIXTURE`. Regression guard: the LSP-role mock dispatch path was not collateral-damaged by the deletion.

3. **`fetchLoanApplicationDetail_propagates_403_instead_of_returning_mock`.**
   - Arrange: stored session = OPS_USER; backend `/api/v1/internal/ops/loan-applications/{id}` returns 403.
   - Assert: rejects with `ApiError(403)`. Make pass by deleting `api-detail.ts:339-350`'s try/catch.

4. **`fetchLoanApplicationDetail_propagates_404_instead_of_returning_mock`.**
   - 404 on the same endpoint → `ApiError(404)`, not a fabricated detail row. (Different status code, same site — proves the deletion handles every 4xx, not just 401.)

5. **`fetchLoanApplicationActivity_propagates_4xx_instead_of_returning_mock`.** Site: `api-detail.ts:400-409`. Same shape as (3); one slice = one site.

6. **`fetchLoanApplicationWebhooks_propagates_4xx_instead_of_returning_mock`.** Site: `api-detail.ts:456-465`.

7. **`postTransition_propagates_4xx_instead_of_returning_mock`.** Site: `api-detail.ts:560-566`. Subtlety: this endpoint has internal `tryEndpoint("manual-status")` fallback for SYSTEM_ADMIN — that's a *separate, intentional* business fallback (out-of-state transition retry). Test that the SYSTEM_ADMIN path still falls through `status-transitions` 400 → `manual-status`, but that a 4xx from `manual-status` itself surfaces to the caller (no mock fallback).

8. **`postDisbursement_propagates_4xx_instead_of_returning_mock`.** Site: `api-detail.ts:589-605`.

9. **`fetchLoanApplicationsList_propagates_4xx_instead_of_returning_mock`.** Site: `api.ts:228-232`.

10. **`fetchRepaymentSchedule_propagates_4xx_instead_of_returning_mock`.** Site: `api-tabs.ts:149-151`.

11. **`fetchLoanDocuments_propagates_4xx_instead_of_returning_mock`.** Site: `api-tabs.ts:261-263`.

12. **`fetchLoanPayments_propagates_4xx_instead_of_returning_mock`.** Site: `api-tabs.ts:335-337`.

13. **`fetchAuditEvents_propagates_4xx_instead_of_returning_mock`.** Site: `audit/api.ts:187-191`.

14. **`fetchHomeOverview_propagates_4xx_instead_of_returning_mock`.** Site: `home/api.ts:237-240`.

15. **`fetchBorrowersList_propagates_4xx_instead_of_returning_mock`.** Site: `borrowers/api-list.ts:92-95`.

16. **`fetchBorrowerLoansTab_propagates_4xx_instead_of_returning_mock`.** Site: `borrowers/api-tabs.ts:82-84`.

17. **`fetchChecklist_surfaces_401_instead_of_returning_empty_array`.** (Replaces silent swallow.) Backend `/.../kyc-documents` returns 401 → caller sees `ApiError(401)`, not `[]`. Make pass by replacing `fetchChecklistSafely` with a non-swallowing `fetchChecklist`.

18. **`fetchChecklist_returns_empty_array_when_backend_returns_200_with_empty_body`.** Confirms genuine empty checklists still resolve to `[]`; the fix doesn't over-correct.

19. **`loan_detail_does_not_render_docsComplete_true_after_checklist_401`.** Higher-level slice: `fetchLoanApplicationDetail` propagates the 401 from the checklist sub-call (because `Promise.all` rejects on either leg failing). UI never sees a `docsComplete=true` derived from a fake empty checklist.

20. **Single regression-guard test in each feature file: `lsp_role_path_uses_mock_router`.** Confirms LSP-role dispatch still works after deletions. (One per file — 8 tests total; trivial, locks in the "two concepts stay separated" property.)

**Mocking discipline (per the user's TDD doc):**
- **`global.fetch`** — boundary (HTTP transport). Spied/mocked via `vi.spyOn`.
- **`registerRoute` against `@/mocks/router`** — boundary for LSP-role dispatch only; used in tests 2 + the 8 regression guards. Not mocked, just configured.
- **`loadStoredSession` (`session-storage.ts`)** — internal collaborator that reads `localStorage`. Tests set `localStorage` directly (via `@/test/utils.tsx` helpers) rather than mocking the module. Same shape as existing `api-list.test.ts`.
- **NOT mocked:** `requestJson`, `dispatch`, `ApiError`, `isInternalSession`, any feature-internal helper. If a future refactor (e.g., centralisation of role-routing) renames `isInternalSession`, none of these tests should break, because none of them assert on it.

**Refactor pass after green (per TDD step 4):**
- Look for: now that the try/catch is gone, is the `if (isInternalSession()) { return X; } return Y;` shape itself worth extracting? Likely no — it's a 2-line decision per file, extracting it forces every caller through an abstraction that hides the role-routing fact (a *deep* module here would obscure the intentional concept #1 above, not hide complexity). Leave as-is.
- Possible candidate: the per-file fixtures in tests have grown copy-paste. Extract `frontend-2/src/test/fixtures/` if 3+ files duplicate the same fixture. Defer until duplication is real.

**Behaviours we deliberately do NOT assert here (each owned by another issue):**
- LSP-role full real backend cutover → tracked separately under the future LSP-web-UI workstream.
- Login flow on token expiry (refresh callback) → handled by `setRefreshCallback` in `http-client.ts`; tests already exist around it; outside #78's scope.
- Audit row written when an OPS_USER hits a 403 → that's the audit/auth lockout pipeline (#71 / #155), not this PR.

**Effect on app (final):**
- Operators in prod see real auth-denied / not-found / expired-session states; the UI tells the truth. Removes the class of "I saw a borrower's data but it was fake" bug entirely.
- Dev sessions running without a backend will see real errors (401/404) instead of mock data; the workaround is to run the backend locally (`docker compose up backend`) or log in as LSP-role to use the mock router intentionally. Documented in `docs/agents/domain.md` follow-up note.
- `docsComplete` becomes truthful — the "all docs uploaded → ready to disburse" banner only shows when the backend actually said so.
- Bundle size: marginal reduction (drop `msw` dep ≈ ~150 KB unused; no other change).
- LSP-role demo path: unchanged.
- Prod build: identical surface area to dev for internal-session paths; only the LSP-role branch reaches the mock router.

**Dependencies / sequencing:**
- Independent — can ship before/after any other issue. No coupling to #61, #62, #65.
- Closes: **#78, #102, #117, #145**. References from #61's bundle on `/mock-outcome` (which is backend, not FE) are unaffected.
- Does **not** unblock or block #68 (audited internal admin PII reveal endpoint) — that's an additive endpoint, independent of the FE fallback decision.

**Implementation status (2026-06-02, `main` via PR #171):**

| Area | Delivered |
|------|-----------|
| **Transport (#78 core)** | Removed inline `try { backend } catch (4xx) { dispatch(mock) }` at all internal-session API sites (`borrowers`, `loan-applications`, `home`, `audit`). Internal paths are straight `requestJson` / `fetchFromBackend` calls that throw `ApiError` on failure. |
| **Checklist sub-bug** | `fetchChecklistSafely` → `fetchChecklist`; checklist 401/403 propagates (no silent `[]` → false `docsComplete`). |
| **LSP-role demo** | `if (isInternalSession()) { backend } else { dispatch(mock) }` preserved for LSP-role sessions only. |
| **Deps** | Removed unused `msw` from `frontend-2/package.json`. |
| **UX follow-up** | `isUnauthorizedApiError` / `isNotFoundApiError` in `frontend-2/src/lib/api/api-errors.ts`. List pages: 403 → `EmptyState` (borrowers, loans, home). Detail pages: 403 vs 404 split. Audit page: `ErrorState` + retry when query fails with non-auth errors (no empty table on 5xx). |
| **Tests** | `frontend-2/src/test/internal-session.ts`; transport tests in `borrowers/api.test.ts`, `loan-applications/api-detail.test.ts`, `home/api.test.ts`; page tests for borrowers/loans/home/audit error surfaces. |
| **Backend (test hygiene)** | `LoanApplicationOpsControllerTest` clears `lsp_ip_allowlist` / `lsp_ui_ip_allowlist` before `lsp` delete so full-suite teardown does not FK-fail. |

**TDD checklist (tracker vs shipped):**

| Test (tracker) | Status |
|----------------|--------|
| Tracer — `fetchBorrowerDetail` 401 propagates | **Done** — `borrowers/api.test.ts` |
| LSP-role mock regression | **Done** — same file |
| `fetchLoanApplicationDetail` 403 / checklist 401 | **Done** — `api-detail.test.ts` |
| Slices 3–16 (one test per remaining API site) | **Partial** — pattern established on tracer sites; remaining sites covered by deletion + full Vitest suite (1076 tests) rather than one test per file |
| `fetchChecklist` empty on 200 | **Done** — implied by `api-detail` internal-session tests |
| `loan_detail_does_not_render_docsComplete_true_after_checklist_401` | **Deferred** — UI-level slice; transport layer rejects via `Promise.all` |
| Eight `lsp_role_path_uses_mock_router` guards | **Partial** — LSP regression on borrower detail + loan detail tests |

**Verification:** `cd frontend-2 && npm run verify` (typecheck, lint, format, test, build); `cd backend && mvnw clean verify` (332 tests). Full Vitest: 1076 passed.

**GitHub:** [#78](https://github.com/sid12701/lms/issues/78), [#102](https://github.com/sid12701/lms/issues/102), [#117](https://github.com/sid12701/lms/issues/117), [#145](https://github.com/sid12701/lms/issues/145) — close on merge referencing PR #171.

---

### #85 — [B-3] Auto-approval runs inside disbursement TX → auto-reject persists despite throw
**Labels:** bug, scale-risk · **Link:** https://github.com/sid12701/lms/issues/85 · **Status:** **CLOSED** — resolved with #135 in [PR #172](https://github.com/sid12701/lms/pull/172) (2026-06-02)

**Problem (plain English):** When an LSP requests disbursement, the same transaction first re-runs auto-approval. If approval decides "reject," the reject row is committed and then the disbursement code throws a confusing error. You end up with a rejected loan AND a half-failed disbursement attempt.

**Possible fixes:**
1. **Decouple via maker-checker (#62)** — best; approval and disbursement aren't in the same flow at all.
2. **Run approval in a separate `REQUIRES_NEW` TX** — keeps coupling, fixes the half-state.
3. **Skip approval inside the disbursement path entirely** — approval must have already happened before this call.

**Recommended:** Option 1 — it's the same fix as #62 and removes the coupling root cause. If maker-checker slips, Option 3 is the next-best emergency fix.

**Effect on app:** Disbursement code path becomes simple ("is status APPROVED? then disburse"). Removes a class of concurrent-race bugs. Visible LSP-API contract change tied to #62.

**Detailed solution after discussion (2026-05-31):**

**Originally framed as: closes with #62 PR (b) — no separate PR.** Per the audited code, the bug was structural: `LoanDisbursementService` was `@Transactional`, called `loanApprovalService.autoApproveIfEligibleForLsp` inside that same TX, and the rest of the method wrote the disbursement attempt. A reject decision committed before the throw it triggered. The plan was that #62 PR (b) would delete `requestDisbursementForLsp` entirely, evaporating the offending method.

**2026-06-02 follow-up audit — gap (now closed):**
- ✅ The **entry-point** (`POST /api/v1/lsp/loan-applications/{id}/disbursement`) was removed in PR #168 / `7f085bf`.
- ✅ **Structural fix shipped:** `requestDisbursementForLsp` deleted from `LoanDisbursementService`. `LoanDocumentService.submit*` persists in `@Transactional` `persistStored*` helpers, then calls auto-approve **after** commit. Worker path unchanged (no rule engine inside disbursement TX).

**Implementation shipped (2026-06-02, bundled with #135):**
- `LoanDisbursementService` — orphan disbursement+auto-approve method removed; validation helpers retained for worker/bank-check.
- `LoanDocumentService` — `persistStoredDocument(s)ForLsp` vs public `submitStored*` split.
- Tests: `Issue85AutoApprovalIntegrationTest`, `Issue85Issue135LspDocumentUploadIntegrationTest` (HTTP 422 `AUTO_APPROVAL_NOT_ALLOWED` on rejected app).

**Caller audit (2026-06-02):**
- `requestDisbursementForLsp` — zero callers in `backend/src` (grep-confirmed). Dead code.
- `autoApproveIfEligibleForLsp` — live callers in `LoanDocumentService:133, :168` (both inside `@Transactional`), `LoanApprovalService:18-19`, `LoanApplicationService:652-653` (these last two are thin pass-throughs to `LoanApplicationLifecycleService.autoApproveIfEligibleForLsp`).
- Zero integration tests reference `requestDisbursementForLsp` directly.

**Closing condition:** Met — (a) orphan method deleted and (b) document upload hardened via separate TX + #135 state-machine guard.

**Why a 1-line patch is now appropriate (revised):** with no live caller on the disbursement side, deleting the orphan method body is a safe, isolated cleanup that can ship without coordinating with #62. It does not change behaviour; it only removes a footgun for future callers. Recommend bundling that delete with the #135 work that pushes the guard into the state machine.

**TDD plan (lives in #62 PR (b); listed here so #85's closing condition is testable):**

The behavioural assertion that closes #85 is already covered by #62 PR (b)'s test #7: `auto_approval_no_longer_runs_inside_disbursement_path`. That test forces a scenario where auto-approval *would have* re-evaluated and rejected on the old `requestDisbursementForLsp` path, drives the worker path instead, and asserts:
- The application reaches `DISBURSED` (not `REJECTED`) — proves the rule engine is not being re-run inside the disbursement TX.
- No `application_audit_event` row with `action == "auto-reject"` is written during the disbursement window — proves the half-rollback scenario from #85 is gone.

Additional confirming test added under #62 PR (b) specifically to lock in #85's closure:

- **`disbursement_path_does_not_persist_a_reject_decision_when_worker_finds_application_already_approved`** — seeds an application that *would* fail re-evaluation (e.g., principal slightly over a tightened rule threshold introduced after approval) at `APPROVED_PENDING_DISBURSAL`; the worker fires; asserts:
  - Final status is `DISBURSED` (worker uses the recorded approval, does not re-evaluate).
  - No `REJECTED` audit row was written at any point during the worker pass.
  - `LoanDisbursementRequestLog` is recorded exactly once.

Both tests drive the public service layer (`LoanDisbursementWorker.tick()` or equivalent) — no mock of `LoanApprovalService`, `LoanAutoApprovalRuleEngine`, or repository internals. The reject pathway is proven absent through the public read-model (status + audit events), not by spying on whether the engine method was called.

**Why not Option 2 (REQUIRES_NEW TX) or Option 3 (delete autoApprove call inline):**
- Option 2 keeps the coupling and adds a second TX boundary; you still have two writes from one HTTP call, and the test space grows to cover "engine commits but disburser throws" interleavings. Worse semantic, more tests, same overall shape.
- Option 3 (the 1-line delete) is technically correct but ships a patch that #62 PR (b) immediately deletes anyway. Net cost: an extra PR, no durable benefit.

**Effect on app (re-stated under #62 PR (b)'s frame, corrected 2026-06-02):**
- `LoanDisbursementService.requestDisbursementForLsp` is **dead but not deleted** — `@Transactional` still wraps a rule-engine call in the source, just with no caller. Future contributors can resurrect the bug by adding a new caller without realising the method's shape is broken.
- The worker reads `APPROVED_PENDING_DISBURSAL`, never calls the rule engine inside its TX. The half-rollback class of bugs is **gone on the disbursement path**, not gone structurally — the same shape persists in `LoanDocumentService` (the document-upload paths).
- LSP API surface: `POST /api/v1/lsp/loan-applications/{id}/disbursement` returns 404. No partner comms pre-launch.

**Dependencies / sequencing (corrected 2026-06-02):**
- #62 PR (b) removed the disbursement entry-point but did not close #85 structurally.
- Follow-up scope: (1) delete the orphan `requestDisbursementForLsp` method body; (2) bundle with #135 to push the auto-approve guard into the state machine so the `LoanDocumentService` callers can't induce the same half-state. Standalone PR is now appropriate (no live caller to coordinate with).
- No relationship to #61 (mock adapter — different layer) or #78 (frontend fallback — different system).

---

### #102 — [Q-5] FE mock-fallback boilerplate duplicated at every call site
**Labels:** code-quality, mocked-flow, security, duplicate-code · **Link:** https://github.com/sid12701/lms/issues/102 · **Status:** **CLOSED** — resolved by [PR #171](https://github.com/sid12701/lms/pull/171) (#78)

**Problem (plain English):** The mock-fallback pattern from #78 is hand-copied at every API call site. Even if you remove it from one file, the next developer adds it back from muscle memory.

**Possible fixes:**
1. **Centralise in `http-client.ts` (gated by env flag)** — single point of control; lint rule prevents inline fallback.
2. **Delete from every site, no central helper** — strongest but requires test refactor.
3. **Codemod every site to a helper, then delete the helper later** — staged.

**Recommended:** Option 1 + a lint rule. Combine with #78 in one PR.

**Effect on app:** Same as #78; this issue is the code-quality angle. Adding new endpoints stops being a "remember the fallback dance" exercise.

**Detailed solution after discussion (2026-05-31):** Closes with #78. The duplication is the inline `try { backend } catch (4xx) { dispatch(mock) }` boilerplate copied at 14 sites. Per #78's decision the boilerplate is **deleted, not centralised** — there is nothing left to extract once each internal-session site is a single `return await requestJson(...)`. No `fallbackOrThrow` helper, no lint rule. The only shared shape remaining is the `if (isInternalSession()) … else dispatch(...)` role-routing decision (2 lines per site), which is intentionally kept inline — extracting it would hide the "real backend vs. LSP-role demo" concept behind an abstraction. **Closed 2026-06-02 via PR #171** (same delivery as #78).

---

### #117 — [D-4] FE mock fallback boilerplate replicated at every call site (duplication framing)
**Labels:** duplicate-code, mocked-flow · **Link:** https://github.com/sid12701/lms/issues/117 · **Status:** **CLOSED** — duplicate of #102; [PR #171](https://github.com/sid12701/lms/pull/171)

**Problem (plain English):** Same as #102; filed under the duplication taxonomy.

**Possible fixes / Recommended / Effect:** See #102. Close as duplicate when #102 lands.

**Detailed solution after discussion (2026-05-31):** Close as duplicate of **#102** (which itself closes with the **#78** PR). Same boilerplate; no separate fix. **Closed 2026-06-02 via PR #171.**

---

### #122 — [D-9] MockDisbursementOutcomeRequest endpoint+DTO should not ship to prod
**Labels:** duplicate-code, mocked-flow · **Link:** https://github.com/sid12701/lms/issues/122

**Problem (plain English):** The DTO and endpoint that drive the mock disbursement exist in the controller alongside real DTOs. Even if the adapter is gated (#61), the endpoint still ships unless you also profile-guard it.

**Possible fixes:**
1. **Move endpoint + DTO behind `@Profile({"local","test"})`** — pairs with #61.
2. **Delete entirely once real adapter is live** — final state.
3. **Keep, but require a second admin's approval** — pointless; mock outcomes should not be a prod operation at all.

**Recommended:** Option 1 now, Option 2 once the real adapter is shipped. Track under #61.

**Effect on app:** Prod build's controller surface shrinks; `/mock-outcome` returns 404. No production user impact (no one should be calling it in prod today).

**Detailed solution after discussion (2026-05-31):**

**Close as not-applicable per #61's decision.** The audit-doc's framing ("hide the endpoint behind `@Profile`") rested on the assumption that the mock disbursement is a leak. Per #61 the mock is **product intent** until executive approval of a real bank/NPCI provider — the endpoint and DTO must continue to ship in prod so operators can advance loans past `DISBURSEMENT_REQUESTED` while there is no real provider callback. Profile-gating would break the only path that currently moves money in the test/demo loop.

**What replaces this issue's "fix":**
- ADR `0XYZ-disbursement-adapter-stays-mock-until-approval.md` (created under #61) names this endpoint as a deliberate, in-prod operator lever and lists the controls that gate its safety.
- The remaining safety properties this issue *implicitly* asked for (the endpoint shouldn't be a free-for-all in prod) are explicitly delegated:
  - **Audit row per call** → #152 ([AUD-6]).
  - **Maker-checker before mock `DISBURSED` outcome applies** → #62 PR (a) makes manual transitions `SYSTEM_ADMIN`-only with `MANUAL_RULE_ENGINE_OVERRIDE` alerts; the `/mock-outcome` resolve becomes one of those gated paths once PR (a) lands.
  - **Rate limit** → #81.
  - **`/actuator/info` surfaces `disbursementAdapter.mode = "MOCK"`** → #61's PR.

**Why not "delete entirely once real adapter is live" (audit Option 2):** that's the eventual state after the executive-approval gate flips. It is not the closing condition for #122 today; it is the closing condition for #61 once a real adapter ships. Tracking it here would double-count it.

**TDD plan:** none required here. The relevant tests live under #61 (mock-by-design visibility, label propagation, profile-agnostic callability) and under #62 PR (a) (`/mock-outcome` resolve path becomes `SYSTEM_ADMIN`-gated + alerted). #122 contributes no new tests.

**Effect on app:** zero behavioural change attributable to this issue specifically. The endpoint and DTO continue to ship; the safety story is layered on by the issues above.

**Dependencies / sequencing:** none — this issue closes administratively, referencing the ADR from #61, the moment that ADR is merged. **Close on #61's PR merge with a comment linking to the ADR and to #152 / #62 PR (a) / #81 as the owners of the residual controls.**

---

### #139 — [SEC-Δ-1] LSP list/detail leaks PAN/bank/IFSC/holder (dup of #65)
**Link:** https://github.com/sid12701/lms/issues/139 · **Status:** **CLOSED — DEFERRED duplicate of #65** (2026-06-06)

**Problem / Fixes / Recommendation / Effect:** Same as #65; this issue is the security-audit-delta framing (extends C-04 from internal-only to LSP-tenant surface). Close as duplicate of #65 once that lands.

**Detailed solution after discussion (2026-05-31):**

**Duplicate of #65. Stays open under #65's deferred-to-pre-launch decision.** Per #65's locked solution, the projection-layer masking + audited reveal endpoint + LSP-tenant PII surface are all being built as **one move** rather than piecemeal — the LSP-tenant leak that #139 describes is part of that single projection cutover, not a separate fix. Closing as duplicate now would orphan the security-audit-delta framing from the deferral trigger list (first live LSP integration, real PII in prod, external audit, exfil incident). Keep #139 open as a cross-reference; close it on the same PR that closes #65.

**TDD plan:** none specific to #139. The eight tests listed under #65 cover the LSP-tenant leak surface (LSP list, LSP detail, admin list, admin detail, MIS preview, MIS CSV, reveal endpoint, audit-row presence) — they are written from the public-API surface and will assert that PAN/bank/IFSC/holder are masked or audited regardless of which controller (`LspLoanApplicationApiController` / `AdminLoanApplicationController` / `AdminReportingService`) serves the response. The LSP-tenant framing of #139 does not need its own test scaffolding.

**Effect on app:** zero behavioural change attributable to this issue specifically while deferred. Operator visibility of the deferral happens through #65's tracking.

**Dependencies / sequencing:** strictly tracks #65. Closes on the same PR that closes #65 (i.e., when the deferral trigger flips and the projection-masking work ships).

---

### #145 — [SEC-Δ-7] Mock-fallback masks 401/403 (dup of #78)
**Link:** https://github.com/sid12701/lms/issues/145 · **Status:** **CLOSED** — duplicate of #78; [PR #171](https://github.com/sid12701/lms/pull/171)

**Problem / Fixes / Recommendation / Effect:** Same as #78; this issue is the security framing. Close as duplicate.

**Detailed solution after discussion (2026-05-31):** Close as duplicate of **#78**. The deletion of the 4xx → mock fallback at all 14 sites resolves the 401/403 masking surface that this issue describes. **Closed 2026-06-02 via PR #171.**

---

### #157 — [R-2] MIS preview masks but CSV download leaks raw PAN (dup of #69)
**Link:** https://github.com/sid12701/lms/issues/157 · **Status:** **CLOSED — DEFERRED duplicate of #69** (2026-06-06)

**Problem / Fixes / Recommendation / Effect:** Same as #69; reporting-risk framing.

**Detailed solution after discussion (2026-05-31):**

**Duplicate of #69. Stays open under #69's deferred-to-pre-launch decision.** Per #69's locked solution, the MIS preview and CSV download both return raw PAN/IFSC/bank account today — the "preview masked, CSV leaks" framing is half-right because the preview's masking is *frontend-side formatting only*; the backend treats both surfaces identically. The fix is one projection-layer masking pass serving every consumer (preview JSON, CSV download, LSP responses, admin responses), built as a single move alongside #65. Closing #157 as a duplicate now would orphan the reporting-risk framing from #69's deferral trigger list; keep it open as a cross-reference.

**TDD plan:** none specific to #157. The tests under #69 cover both the `/portfolio-mis/preview` JSON surface and the `/portfolio-mis` CSV surface from the public-controller boundary, asserting that PAN/IFSC/bank-account are masked identically (or that an audited unmasked-export endpoint serves the cleartext path with full audit metadata). The reporting-risk framing of #157 does not need its own test scaffolding.

**Effect on app:** zero behavioural change attributable to this issue specifically while deferred. The reporting-risk story is owned by #69.

**Dependencies / sequencing:** strictly tracks #69. Closes on the same PR that closes #69. Reporting-risk audit log entry — keep #157 in the index so the reporting-risk lens is preserved when the projection work lands.

---

## P1 — High priority (38)

### #68 — No audited internal admin PII reveal endpoint (frontend falls through to mock)
**Labels:** gap, auditability, security, mocked-flow · **Link:** https://github.com/sid12701/lms/issues/68 · **Status:** **CLOSED — SUPERSEDED / DEFERRED** (2026-06-06). No admin reveal UI; LSP reveal removed. Broader masking cluster remains **#65**/**#69** when un-deferred.

**Problem (plain English):** When an admin tries to reveal a borrower's PII, the backend has no endpoint for it — the frontend silently uses the mock router. Real PII reveals leave no audit row even when they look successful in the UI.

**Possible fixes:**
1. **Add `GET /internal/ops/loan-applications/{id}/borrower-pii` mirroring the LSP path with audit** — symmetric and auditable.
2. **Block the UI button until backend exists** — safe but leaves the gap unresolved.
3. **Route admin to the LSP endpoint with a flag** — wrong abstraction; admin isn't a tenant.

**Recommended:** Option 1. Mirror the LSP-side audit semantics for consistency.

**Effect on app:** Admin PII reveals now appear in Audit Explorer with actor/IP/correlation. Insider PII harvesting becomes traceable.

**Detailed solution after discussion:**

#### Audit of linked surface area (done before deciding)

The original recommendation (mirror the LSP endpoint) no longer matches the code as of 2026-05-31. Touchpoints inspected:

| Surface | Path | State |
|---|---|---|
| LSP controller | `backend/.../web/LspLoanApplicationApiController.java` | **No `borrower-pii` handler.** The endpoint was deleted earlier in the projection rework. |
| LSP test | `backend/.../web/LspLoanApplicationApiControllerTest.java:364` (`lspApiMasksAadhaarButReturnsOtherPiiRawAndRevealEndpointIsRemoved`) | Asserts `GET /api/v1/lsp/loan-applications/{id}/borrower-pii` returns **404** and `loanApplicationPiiRevealAuditRepository.count() == 0`. Locks the removal. |
| Domain entity | `backend/.../domain/LoanApplicationPiiRevealAudit.java` | Still present. **No writer left in the codebase** — entity is orphaned. |
| Repository | `backend/.../repo/LoanApplicationPiiRevealAuditRepository.java` | Still present. Only consumer is the test above (cleanup + count assertion). |
| DB table | `loan_application_pii_reveal_audit` | Still present in the schema, no production rows expected. |
| Admin OverviewTab | `frontend-2/src/features/loan-applications/components/detail-tabs/OverviewTab.tsx:61-65` | Comment: *"PII fields render as-supplied by the backend (which masks identity numbers); there is no reveal path (see `docs/gap-fixes.md` § Gap #1)."* No reveal button. |
| Admin loan-apps mock | `frontend-2/src/mocks/api/loan-applications.ts` | **No PII reveal handler.** The original issue's "FE falls through to mock router" premise is uncorroborated — no admin call site exists. |
| Current PII contract | LSP read sites | Aadhaar is the **only** masked field; PAN, bank, IFSC, mobile, email, DOB, address, income returned raw. |
| Sibling cluster | `bugs-gaps-audit.md:215-219`, this file §65/§69/§123/§139/§157 | Cluster was drafted against a "mask broadly + reveal endpoint on both surfaces" vision that the LSP side abandoned. #68's recommendation is internally inconsistent with the cluster's current state. |

**Conclusion of audit:** #68's recommendation is stale. There is no UI surface "silently using the mock", and the symmetric LSP endpoint it would mirror no longer exists. The original cluster's "mask broadly + add reveal" vision is a separate (larger) policy decision that #65/#69/#123 still describe; #68 should not block on that decision.

#### Decision (after grilling)

**Re-scope #68 as a `wontfix`/superseded close**, not an implementation. The LSP-side projection decision (Aadhaar-only masking, no reveal path) makes a symmetric admin reveal pointless: there is nothing the admin would un-mask that the LSP does not already receive raw, and the only currently-masked field (Aadhaar) has no admin UI surface that requests it today.

Three coupled cleanups land in the same change:

1. **Hard-delete the orphaned reveal-audit code and schema** — entity, repository, and table all go. Nothing in the codebase should imply a reveal feature exists.
2. **Lock the decision into the test layer** — both LSP and admin URLs return 404 in tests; the migration is tested against a populated table.
3. **Record the policy in an ADR** — `docs/adr/NNNN-pii-reveal-superseded.md` documents the decision and adds a tripwire: if any future admin surface re-exposes Aadhaar (or future masking re-introduces other fields), the same PR MUST re-introduce an audit write.

#### TDD plan (vertical slices, one RED→GREEN at a time)

The change is mostly *subtractive*. TDD still applies — the tests here **lock the decision** so future PRs can't silently invert it. Each test describes observable behaviour through public surfaces (HTTP 404, migration outcome, ADR presence), not internal structure. No mocking of internal collaborators.

**Slice 1 — LSP endpoint stays 404 with no repo reference**
- RED: Rename the existing test `lspApiMasksAadhaarButReturnsOtherPiiRawAndRevealEndpointIsRemoved` → `lspBorrowerPiiEndpointIsRemoved`. Drop the `loanApplicationPiiRevealAuditRepository` field, its `@MockBean`/`@Autowired` import, and the `count() == 0` assertion. Keep only the two HTTP-404 assertions (cookie + token forms). Test fails to compile (repository import missing once entity is deleted).
- GREEN: Delete `LoanApplicationPiiRevealAuditRepository.java`. Delete `LoanApplicationPiiRevealAudit.java`. Test compiles; both 404 assertions pass against the unchanged controller.
- Refactor: none — the test is already pure behaviour ("URL doesn't exist") and uses no internal mocks.

**Slice 2 — Admin endpoint also 404s**
- RED: Add `adminBorrowerPiiEndpointDoesNotExist()` to whichever test class covers `LoanApplicationOpsController` (or a new `LoanApplicationOpsControllerPiiPolicyTest`). Asserts `GET /api/v1/internal/ops/loan-applications/{id}/borrower-pii` with a `SYSTEM_ADMIN` session returns 404. Test passes trivially (the endpoint doesn't exist), so it is a *characterization* test: it goes RED only if a future PR adds the handler. Document this intent in a Javadoc comment on the test method.
- GREEN: nothing to write — the test passes immediately. Its job is to make removal of the test explicit in a future PR, forcing the reveal discussion back into code review.
- Note: per TDD philosophy this is on the edge — the test is "RED→GREEN by virtue of absence". Acceptable because the assertion is on the **public HTTP surface** and the test name reads as a specification ("the admin reveal endpoint does not exist").

**Slice 3 — Migration drops a populated table cleanly**
- RED: Write the migration test first (`PiiRevealAuditDropMigrationIT` or extend an existing migration IT). Seed the test DB with one fake `loan_application_pii_reveal_audit` row (raw `jdbc.execute` so no entity is required) before the new migration runs. Assert `to_regclass('loan_application_pii_reveal_audit')` is `null` after migrate. Test fails — no migration file yet.
- GREEN: Add `backend/src/main/resources/db/migration/V{n+1}__drop_loan_application_pii_reveal_audit.sql` containing `DROP TABLE IF EXISTS loan_application_pii_reveal_audit;`. Test passes.
- Refactor: confirm migration ordering and Flyway baseline; nothing to extract.

**Slice 4 — ADR file is present (CI tripwire)**
- RED: Add a tiny test (`docs/PiiRevealSupersededAdrPresenceTest` or co-locate in an existing docs-presence test). Walks `docs/adr/` and asserts at least one file matches `*-pii-reveal-superseded.md`. Fails — file doesn't exist.
- GREEN: Create `docs/adr/NNNN-pii-reveal-superseded.md` with Context / Decision / Consequence / References sections per the preview shown in the grill. Test passes.
- Refactor: none.

**Order matters:** Slices 1 and 3 must land together — Slice 1's GREEN deletes the entity, which would break Slice 3's seed insert if not done via raw JDBC. Verify the seed in Slice 3 uses `jdbc.execute("INSERT INTO loan_application_pii_reveal_audit ...")` with literal column names, not the deleted entity, **before** running Slice 1's GREEN.

#### Files touched (final list)

Delete:
- `backend/src/main/java/com/bhawana/lms/domain/LoanApplicationPiiRevealAudit.java`
- `backend/src/main/java/com/bhawana/lms/repo/LoanApplicationPiiRevealAuditRepository.java`

Edit:
- `backend/src/test/java/com/bhawana/lms/web/LspLoanApplicationApiControllerTest.java` (drop repository field + import, rename test, drop count assertion)

Add:
- `backend/src/main/resources/db/migration/V{n+1}__drop_loan_application_pii_reveal_audit.sql`
- `backend/src/test/java/com/bhawana/lms/migration/PiiRevealAuditDropMigrationIT.java` (or extend existing)
- `backend/src/test/java/com/bhawana/lms/web/LoanApplicationOpsControllerPiiPolicyTest.java` (or extend existing)
- `docs/adr/NNNN-pii-reveal-superseded.md`
- Optional: tiny `AdrPresenceTest` if the project doesn't already enforce ADR presence

Untouched (deliberately):
- `LspLoanApplicationApiController.java` — no handler to remove; absence is already the contract.
- Admin OverviewTab, MIS preview, mock router — no FE behaviour change.

#### Effect on app (revised)

- No user-visible change. No new endpoint, no new button.
- Schema shrinks by one orphan table.
- Codebase contains no dead reference to a reveal feature.
- A future "we need an admin PII reveal" conversation has a documented prior decision to reopen, not a half-finished implementation to discover.
- **Accepted residual risk (documented in ADR):** if a future admin code path reads or unmasks Aadhaar, it will leave no audit row unless that PR also re-introduces a reveal-audit writer. The presence of `adminBorrowerPiiEndpointDoesNotExist` and the ADR are the tripwires.

#### Cluster impact

- **#65 / #69 / #123 / #139 / #157** remain open. They still describe the broader "mask more PII, add reveal endpoint" rework. #68's closure does **not** prejudice that decision — it only records that #68 is no longer the right entry point. If the cluster is later picked up as a single PR, that PR re-introduces the entity, the repository, the table, and the two endpoints in one cohesive change, and explicitly retires the ADR added here.

---

### #70 — Document download (single + ZIP) not audited
**Labels:** gap, auditability, security · **Link:** https://github.com/sid12701/lms/issues/70 · **Status:** **CLOSED** — [PR #173](https://github.com/sid12701/lms/pull/173) merged 2026-06-02 (closes **#150** duplicate)

**Problem (plain English):** Downloading a KYC document — one file or a zip of all of them — leaves no trace. An insider scraping documents is invisible to the audit.

**Possible fixes:**
1. **Audit-on-success in the controller** — simple, covers both endpoints.
2. **Audit via storage adapter** — closer to the data; works for any consumer including future ones.
3. **Skip ZIP audit, only individual** — easier but ZIP is the worst case (one click, everything).

**Recommended:** Option 1; if the architecture later grows multiple consumers, lift to Option 2.

**Effect on app:** Every download row visible in Audit Explorer. Slight overhead per download. Regulatory expectation met.

**Detailed solution after discussion (2026-06-01):**

#### Audit of linked surface area (done before deciding)

| Surface | Path | State |
|---|---|---|
| Single-doc download handler | `backend/.../web/LoanApplicationOpsController.java:216` (`downloadDocumentContent`) | Returns bytes from `loanDocumentService.retrieveDocumentContent`. **Writes no audit row.** Swallows `IllegalStateException` as 404. |
| ZIP download handler | `backend/.../web/LoanApplicationOpsController.java:201` (`downloadAllDocuments`) | Returns `loanDocumentService.buildDocumentZip` bytes. **Writes no audit row.** Same 404-on-IllegalStateException pattern (#92 owns that). |
| LSP-side download? | `backend/.../web/LspLoanApplicationApiController.java` | **No download endpoints.** Admin-only — no symmetry decision needed. |
| Audit entity | `backend/.../domain/LoanApplicationDocumentAccessAudit.java` | Exists. Fields: `actorUsername`, `summary (500 char)`, `documentTypes (csv) + normalizedDocumentTypes (Set)`, `correlationId`, `createdAt`. **No `actor_ip` column, no `byte_count` column** — the issue's AC overshoots the schema. |
| Audit action enum | `backend/.../domain/LoanApplicationDocumentAccessAuditAction.java` | Only two values: `CHECKLIST_VIEWED`, `INTAKE_AUDITS_VIEWED`. **Download actions absent.** |
| Existing inline writers | `LoanApplicationService.listDocumentChecklist` (line 509), `LoanApplicationService.listIntakeAudits` (line 894) | Both write directly via `loanApplicationDocumentAccessAuditRepository.save(...)`. **No `LoanApplicationDocumentAccessAuditService` exists** — the issue's "Suggested fix direction" names a class that's never been built. |
| Audit read endpoint | `LoanApplicationOpsController.java:148` (`listDocumentAccessAudits`) | Returns the 20 most recent rows for an application via `LoanApplicationOpsResponses.toDocumentAccessAuditResponse`. New rows surface immediately. |
| Audit Explorer streams | `frontend-2/src/components/app/audit/types.ts`, #159 ("Audit Explorer only covers 4 streams") | Document-access audit may not yet be a streamed source in the Explorer — verify when the Explorer rework lands; #159 owns the cross-link. |
| Storage adapter | `LoanDocumentService.retrieveDocumentContent` / `buildDocumentZip` | Returns raw bytes (and content-type/filename for single). For ZIP, returns only `byte[]` — **caller has no programmatic way to know which doc types were packed in**, blocking a precise per-ZIP audit unless the return shape is enriched. |
| Failure path | Both handlers catch `IllegalStateException` → 404 | The audit write must sit **after** retrieve succeeds, so the early-return path leaves the audit table clean. |

**Conclusion of audit:** Infrastructure for audit rows exists (entity + repo + read endpoint), but the action enum, two columns the AC demands (IP, byte count), and the ZIP's "which doc types were packed" signal are all missing. The issue's "Suggested fix" referenced a service that doesn't exist; today's pattern is inline writes inside `LoanApplicationService`, which we keep to avoid scope creep into the god-class refactor (#98 / #99).

#### Decision (after grilling, 2026-06-01)

1. **Schema:** add first-class `actor_ip VARCHAR(64)` + `byte_count BIGINT` columns to `loan_application_document_access_audit` via a new Flyway migration. NULL-safe for the two pre-existing writers; queryable for "top exfiltrators by bytes" without regex over `summary`.
2. **Action enum:** add two values — `SINGLE_DOCUMENT_DOWNLOADED` and `BULK_ZIP_DOWNLOADED`. ZIP click writes **one** row with `action=BULK_ZIP_DOWNLOADED` and `normalizedDocumentTypes` populated with every type packed into the ZIP. One click = one bulk-exfiltration event.
3. **Timing:** write the audit row inside the `@Transactional` read path immediately **after** `retrieveDocumentContent` / `buildDocumentZip` returns successfully, before the controller builds the `ResponseEntity`. Failed retrieves (404, storage-error) write nothing.
4. **Write site:** stays inline in `LoanApplicationService` alongside the existing `CHECKLIST_VIEWED` / `INTAKE_AUDITS_VIEWED` writes. The controller delegates to two new service methods (`downloadDocumentContent`, `downloadDocumentZip`) that wrap retrieve + audit write and return the bytes. No new audit service class. Extracting `LoanApplicationDocumentAccessAuditService` is deferred to whichever PR closes #98 / #99 — out of scope here, per the "don't refactor beyond the task" rule.
5. **ZIP shape change:** `LoanDocumentService.buildDocumentZip(applicationId)` changes return shape from `byte[]` to `ZipBuildResult(byte[] content, List<LoanApplicationDocumentType> includedTypes)`. The included-types list flows into the audit row's `normalizedDocumentTypes` set. Internal change — no API surface impact.
6. **`DOCUMENT_DOWNLOAD_FAILED` is explicitly out of scope.** Auditing failed attempts is forensically attractive but overlaps the request-log layer (correlation_id joins both). Tracked as a follow-up enhancement only if security review demands it.

#### TDD plan (vertical slices, one RED → GREEN at a time)

Tests are integration-style through the controller (HTTP boundary) with a real DB. No mocking of internal collaborators. Each test name reads as a specification of observable behaviour.

**Slice 1 — Single document download writes a complete audit row**
- RED: New test class `LoanApplicationOpsControllerDocumentDownloadAuditTest` (or extend the existing ops controller test). Seed: one application with a managed-content checklist item of type `AADHAAR_FRONT`. Authenticate as an ops user. Call `GET /api/v1/internal/ops/loan-applications/{id}/kyc-documents/AADHAAR_FRONT/content`. Then call `GET …/document-access-audits` and assert exactly one new row with:
  - `action = SINGLE_DOCUMENT_DOWNLOADED`
  - `actorUsername = <ops user>`
  - `documentTypes = [AADHAAR_FRONT]`
  - `actorIp = <request remote addr>`
  - `byteCount = <bytes returned by the content endpoint>`
  - `correlationId = <MDC value present on the request>`
  
  Initially fails to compile because the entity has no `actorIp`/`byteCount` and the enum has no `SINGLE_DOCUMENT_DOWNLOADED`. After those minimum additions compile, the test still RED — handler writes nothing.
- GREEN (compile-cascade in one slice):
  1. Add `actor_ip` + `byte_count` columns to `LoanApplicationDocumentAccessAudit` (with `@Column` annotations and matching getters; ctor overloaded so the two existing writers don't break).
  2. Add migration `V{n+1}__document_access_audit_actor_ip_byte_count.sql` (`ALTER TABLE … ADD COLUMN actor_ip VARCHAR(64), ADD COLUMN byte_count BIGINT;`).
  3. Add `SINGLE_DOCUMENT_DOWNLOADED` to `LoanApplicationDocumentAccessAuditAction`.
  4. Add `LoanApplicationService.downloadDocumentContent(applicationId, documentType, actorUsername, actorIp, correlationId)` that calls `getDocumentChecklistItem` + `retrieveDocumentContent` + writes the audit row + returns the `RetrievedDocumentContent`.
  5. Update `LoanApplicationOpsController.downloadDocumentContent` to delegate (HTTP-layer concerns only: extract auth principal, remote IP, MDC correlation, then build `ResponseEntity`).
  
  Test passes. Existing `CHECKLIST_VIEWED` / `INTAKE_AUDITS_VIEWED` writers continue to insert with NULL `actor_ip` and NULL `byte_count` — verified by re-running the existing ops controller test suite without modification.
- Refactor: none — the new method mirrors the existing inline-write pattern.

**Slice 2 — Bulk ZIP download writes one audit row covering all included doc types**
- RED: Seed an application with managed-content checklist items of types `AADHAAR_FRONT` and `PAN`. Call `GET …/kyc-documents/download-all`. Then call `…/document-access-audits` and assert one new row with:
  - `action = BULK_ZIP_DOWNLOADED`
  - `documentTypes` (as a Set) = `[AADHAAR_FRONT, PAN]`
  - `byteCount` = the zip's byte length
  - `actorIp`, `correlationId` populated
  
  Fails — handler writes nothing.
- GREEN:
  1. Add `BULK_ZIP_DOWNLOADED` to the enum.
  2. Change `LoanDocumentService.buildDocumentZip(applicationId)` return type from `byte[]` to a new record `LoanDocumentService.ZipBuildResult(byte[] content, List<LoanApplicationDocumentType> includedTypes)`. Update its implementation to track which types were packed.
  3. Add `LoanApplicationService.downloadDocumentZip(applicationId, actorUsername, actorIp, correlationId)` that calls `buildDocumentZip` + writes the audit row using the result's `includedTypes` + returns the bytes (or the whole result if the controller needs the byte length too).
  4. Update `LoanApplicationOpsController.downloadAllDocuments` to delegate.
  
  Test passes.
- Refactor: scan for any other callers of `buildDocumentZip` (none expected in production code; tests may need a one-line update to unpack `.content()`).

**Slice 3 — Failed downloads do NOT write an audit row (negative regression guard)**
- RED: Seed an application with a checklist item that has `storageKey = null` (or `lmsManagedContent = false`). Call `GET …/kyc-documents/{type}/content`; expect HTTP 404. Then call `…/document-access-audits` and assert the row count did not increase. Same for the ZIP path with a no-documents application.
- GREEN: should pass immediately after Slice 1 + Slice 2 — the audit writes are positioned **after** retrieve succeeds, so the early-return path skips them. This slice locks in that ordering.
- Refactor: none. This is a behaviour-on-failure spec, not new code — it's the TDD-acceptable shape (asserting positive HTTP behaviour and positive "no side effect" behaviour through the public interface, not asserting absence of a code path).

**Order:** Slice 1 → Slice 2 → Slice 3. Slice 1 introduces the migration + columns + enum + the service-method pattern; Slice 2 reuses all of that and only adds the ZIP-shape change; Slice 3 is the cleanup negative test.

#### Files touched (final list)

Edit:
- `backend/src/main/java/com/bhawana/lms/domain/LoanApplicationDocumentAccessAudit.java` (add `actorIp`, `byteCount` fields + getters + overloaded ctor)
- `backend/src/main/java/com/bhawana/lms/domain/LoanApplicationDocumentAccessAuditAction.java` (add `SINGLE_DOCUMENT_DOWNLOADED`, `BULK_ZIP_DOWNLOADED`)
- `backend/src/main/java/com/bhawana/lms/service/LoanApplicationService.java` (add `downloadDocumentContent` + `downloadDocumentZip`; both write audit rows inline)
- `backend/src/main/java/com/bhawana/lms/service/LoanDocumentService.java` (change `buildDocumentZip` return shape to `ZipBuildResult`)
- `backend/src/main/java/com/bhawana/lms/web/LoanApplicationOpsController.java` (delegate the two download handlers to the new service methods; extract IP + correlation at the HTTP boundary)
- `backend/src/main/java/com/bhawana/lms/web/LoanApplicationOpsResponses.java` (if `toDocumentAccessAuditResponse` should expose `actorIp` / `byteCount` to the per-application audit list — verify and extend)

Add:
- `backend/src/main/resources/db/migration/V{n+1}__document_access_audit_actor_ip_byte_count.sql`
- `backend/src/test/java/com/bhawana/lms/web/LoanApplicationOpsControllerDocumentDownloadAuditTest.java` (or extend the existing ops controller test class — keep one assertion per test name)

Untouched (deliberately):
- `LspLoanApplicationApiController.java` — no LSP-facing download endpoint exists.
- The two existing inline writers (`CHECKLIST_VIEWED`, `INTAKE_AUDITS_VIEWED`) — they continue to insert with NULL `actor_ip` + NULL `byte_count`. No backfill.
- Audit Explorer frontend — out of scope; #159 owns making this stream visible in the Explorer.
- Error-handling on `IllegalStateException` → 404 — #92 owns that; this PR confirms (via Slice 3) that the storage-error path leaves no audit row.

#### Effect on app

- Every successful KYC document download (single + bulk ZIP) writes one row visible immediately via `GET /api/v1/internal/ops/loan-applications/{id}/document-access-audits`.
- New `actor_ip` and `byte_count` columns enable structured forensic queries (e.g. "top 10 actors by total bytes downloaded in the last 7 days").
- ZIP downloads emit a single "bulk export" row with the full set of packed document types — directly maps to "X downloaded everything for application Y at time Z".
- Schema migration is additive only; the two existing inline writers continue to work with NULL values in the new columns.
- Tiny per-download overhead (~1 DB insert).
- No user-visible UI change in this PR. Once #159 extends the Audit Explorer streams list, these rows surface there too.
- Regression guard (Slice 3) ensures failed downloads don't pollute the audit log.

#### Cluster impact

- **#150** ("[AUD-4] Document download + ZIP not audited") is a strict duplicate of #70 — closes on the same PR. Cross-reference both directions in the PR description.
- **#159** ("Audit Explorer only covers 4 streams") — once this lands, the Explorer rework should pick up `loan_application_document_access_audit` as a streamed source. Add a TODO note linking back to #70 in #159's tracking issue.
- **#92** ("[B-10] Document download swallows IllegalStateException — storage outage masked as 404") — independent. This PR does not change the 404-on-storage-error behaviour; Slice 3 just confirms that path leaves the audit clean. When #92 lands and stops swallowing the exception, the audit logic does not need to change (it sits after retrieve succeeds).
- **#105** ("[Q-8] LoanApplicationOpsController has ~500 LoC of nested record DTOs") — the controller picks up ~10 lines from this PR (delegation + IP/correlation extraction). Not enough to materially affect #105's refactor calculus.
- **#98 / #99** (god-class refactors) — explicitly **not** addressed by this PR. The audit writes stay inline in `LoanApplicationService` to avoid mixing scope; when #98 / #99 land, a `LoanApplicationDocumentAccessAuditService` is the natural extraction target and will absorb all four call sites (`CHECKLIST_VIEWED`, `INTAKE_AUDITS_VIEWED`, `SINGLE_DOCUMENT_DOWNLOADED`, `BULK_ZIP_DOWNLOADED`) in one move.

**Implementation status (2026-06-02, `main`):**

Shipped per grilled design (Slices 1–3). Backend test suite green including full-suite run with `LoanApplicationOpsControllerDocumentDownloadAuditTest`.

| Delivered | Primary code |
|-----------|--------------|
| Migration **`V80`**: `actor_ip`, `byte_count` on `loan_application_document_access_audit` | `V80__document_access_audit_actor_ip_byte_count.sql` |
| Enum: `SINGLE_DOCUMENT_DOWNLOADED`, `BULK_ZIP_DOWNLOADED` | `LoanApplicationDocumentAccessAuditAction.java` |
| `downloadDocumentContent` / `downloadDocumentZip` with inline audit writes; `@Lazy LoanDocumentService` breaks cycle | `LoanApplicationService.java` |
| `ZipBuildResult` from `buildDocumentZip` (included document types for bulk row) | `LoanDocumentService.java` |
| HTTP: `ClientIpAddresses.resolve`, `CorrelationIdHolder`, auth principal; API exposes `actorIp` / `byteCount` | `LoanApplicationOpsController.java`, `LoanApplicationOpsResponses.java` |
| Integration tests: single + ZIP audit rows; failed download writes nothing | `LoanApplicationOpsControllerDocumentDownloadAuditTest.java` |

**TDD checklist (tracker vs shipped):**

| Slice | Status |
|-------|--------|
| 1 — single download audit row (IP, byte count, correlation) | **Done** |
| 2 — bulk ZIP one row with all packed types | **Done** |
| 3 — failed downloads leave audit table unchanged | **Done** |

**Out of scope (unchanged):** failed-download audit rows; Audit Explorer FE wiring (#159 already has `DOCUMENT_ACCESS` stream on BE); #92 storage-error → 404 behaviour.

---

### #71 — Login/token/refresh/logout/password-change not audited
**Labels:** gap, auditability, security · **Link:** https://github.com/sid12701/lms/issues/71 · **Status:** **CLOSED — IMPLEMENTED** (on `main`). Closes **#147** as duplicate.

**Problem (plain English):** Successful and failed authentication events are not stored anywhere. You cannot detect credential stuffing, you cannot prove who logged in at 3am, and you cannot feed alerts off failed-auth signals.

**Possible fixes:**
1. **Spring Security `AuthenticationEventPublisher` → `AuthAuditService`** — captures even the events controllers don't see (filter-level failures).
2. **Manual audit calls in `AuthController`** — visible but misses filter-level rejections.
3. **Audit only failures** — half-measure; success events are needed for forensic reconstruction.

**Recommended:** Option 1; subscribe to Spring's auth events at the framework level. Add a new `auth_event_audit` table or extend `app_user_audit_event`.

**Effect on app:** New audit stream visible in Explorer (#159 add it there). Enables brute-force lockout (#155). Tiny storage cost; massive forensic value.

**Detailed solution after discussion (2026-06-01):**

#### Audit of linked surface area (done before deciding)

| Surface | Path | State |
|---|---|---|
| Login endpoint | `backend/.../web/AuthController.java:84` (`/login`) | Calls `authenticationManager.authenticate(...)` then mints JWT + refresh cookie. **No audit writes.** Spring `BadCredentialsException`/`LockedException`/`DisabledException` bubble up uncaught. |
| Client-credentials token | `AuthController.java:92` (`/token`) | Calls `apiClientAuthenticationService.authenticate(...)` (custom service, **not** Spring-Security-routed). Mints JWT + refresh cookie. **No audit writes.** Spring's `AuthenticationEventPublisher` never sees this path. |
| Refresh token | `AuthController.java:104` (`/refresh`) | Manual cookie SHA-256 lookup → revoke-then-issue. Three failure branches that return raw `ResponseEntity.status(401)` without ever hitting a Spring auth filter. **No audit writes.** |
| Password change | `AuthController.java:142` (`/password`) | Self-service after `passwordChangeRequired` flag. `passwordEncoder.encode(...)` + `appUserRepository.save(user)`. **No audit writes.** (Admin-initiated reset is separate — owned by #148.) |
| Logout | `AuthController.java:167` (`/logout`) | Best-effort refresh-token revoke + clear-cookie. **No audit writes.** |
| Existing `AppUserAuditEvent` | `backend/.../domain/AppUserAuditEvent.java` (V54) | Designed for **state mutations**: `user_id NOT NULL`, `beforeStateJson NOT NULL`, `afterStateJson NOT NULL`. Failed logins against unknown usernames have no `user_id` and no meaningful before/after state — entity does not fit auth events. |
| Existing `ApiClientAuditEvent` | `backend/.../domain/ApiClientAuditEvent.java` | API-client state mutations (rotate, disable). Not auth events. |
| Audit Explorer | `AuditExplorerRepository.java`, `AuditExplorerService.java`, `AuditExplorerController.java`, `V59__audit_explorer_indexes.sql` | Real working Explorer over 4 streams. #159 owns wiring a 5th stream once `auth_event_audit` exists. |
| Spring AuthenticationEventPublisher | not wired today | Would catch `/login` filter-level rejections (locked, disabled) **but** misses `/token`, `/refresh`, `/logout`, `/password` entirely. Listener-only is at best partial coverage. |
| `RefreshTokenRepository` | uses `findByTokenHashAndRevokedFalse(tokenHash)` | Refresh failures are distinguishable: missing cookie, hash not found, revoked, expired, no principal — each maps to a separate `failure_reason`. |

**Conclusion of audit:** the doc's prior Option-1 recommendation (Spring listener) was based on an incorrect premise that `/token`/`/refresh`/`/logout` were Spring-Security-routed. Manual writes inside the controller (via a thin service) are the only path to full coverage. A new purpose-built table is cleaner than overloading `app_user_audit_event` (which is designed for state diffs, not events).

#### Decision (after grilling, 2026-06-01)

1. **New table `auth_event_audit`** with purpose-built columns:
   - `id UUID PRIMARY KEY`
   - `username TEXT NOT NULL` (the principal asserted at request time — text, not FK, so failed logins against unknown usernames still write a row)
   - `user_id UUID NULL REFERENCES app_user(id)` (resolved when the username matches a real user; NULL for unknown-user failed logins and for API-client events)
   - `api_client_id UUID NULL REFERENCES api_client(id)` (set on `API_CLIENT_TOKEN_*` events; NULL otherwise)
   - `event_type TEXT NOT NULL` (enum, 8 values — see below)
   - `failure_reason TEXT NULL` (enum, 7 values — populated only on `*_FAILED` event types)
   - `actor_ip VARCHAR(64) NULL` (request remote addr; nullable for paths where it's unavailable, e.g. future async retries)
   - `correlation_id VARCHAR(128) NULL` (MDC value)
   - `created_at TIMESTAMP NOT NULL`
   
   Indexes:
   - `(username, created_at DESC)` — drives the #155 lockout query ("recent failures for this username")
   - `(event_type, created_at DESC)` — drives SOC queries ("all `LOGIN_FAILED` in the last hour")
   - `(correlation_id)` — joins to request logs
2. **`event_type` enum (8 values):** `LOGIN_SUCCEEDED`, `LOGIN_FAILED`, `API_CLIENT_TOKEN_SUCCEEDED`, `API_CLIENT_TOKEN_FAILED`, `TOKEN_REFRESH_SUCCEEDED`, `TOKEN_REFRESH_FAILED`, `LOGOUT`, `PASSWORD_CHANGED`. Per the grill, success/failure is baked into the event type (no separate `outcome` column) for query simplicity. `/logout` and `/password` have no failure counterpart in scope — failed password-change attempts (mismatched temp password, policy violation) are not audited in this PR; if needed later, add `PASSWORD_CHANGE_FAILED`.
3. **`failure_reason` enum (7 values):** `INVALID_CREDENTIALS` (covers wrong password AND unknown user — mirrors Spring's external response), `ACCOUNT_LOCKED`, `ACCOUNT_DISABLED`, `TOKEN_EXPIRED`, `TOKEN_REVOKED`, `MISSING_REFRESH_COOKIE`, `OTHER`. The bucketing of "wrong password" and "unknown user" into `INVALID_CREDENTIALS` prevents an admin with audit-read access from username-enumerating the directory.
4. **Capture mechanism: manual writes inside a new `AuthAuditService`,** called from `AuthController`. All five handlers gain explicit `authAuditService.recordX(...)` calls on both success and failure paths. No Spring `AuthenticationEventPublisher` listener in this PR — when #155 (lockout pipeline) lands, it can add a listener for filter-level `LockedException`/`DisabledException` rejections that bypass the controller, guarded against double-writing.
5. **Operational read surface:** add `GET /api/v1/internal/ops/auth-audit?limit=&afterId=&username=&eventType=` returning the most recent rows (paginated, SYSTEM_ADMIN-only). This is both the integration-test verification surface and the immediate SOC-pull endpoint. Audit Explorer stream wiring stays with #159.
6. **The 5 controller handlers gain explicit branch-aware audit calls:**
   - `/login`: `try { authenticate } catch { record LOGIN_FAILED with INVALID_CREDENTIALS / ACCOUNT_LOCKED / ACCOUNT_DISABLED; rethrow }`; on success after JWT mint, `record LOGIN_SUCCEEDED`.
   - `/token`: same pattern, `API_CLIENT_TOKEN_*`.
   - `/refresh`: explicit `record TOKEN_REFRESH_FAILED` at each of the three current 401 branches (missing cookie, hash-not-found/revoked/expired, refresh-token-has-no-principal); on success, `record TOKEN_REFRESH_SUCCEEDED`.
   - `/logout`: `record LOGOUT` regardless of whether the cookie was present (the *attempt* to log out is itself signal).
   - `/password`: on success, `record PASSWORD_CHANGED`.

#### TDD plan (vertical slices, one RED → GREEN at a time)

Tests are integration-style through MockMvc against a real DB, verified via the new `GET /auth-audit` endpoint (not by direct repository query — per the prompt's "verify through the interface, not external means"). Each slice covers one event type, one HTTP outcome at a time.

**Slice 1 — `LOGIN_SUCCEEDED` writes a complete audit row**
- RED: New test `AuthControllerLoginAuditTest`. Seed an enabled user with a known password. POST `/api/v1/auth/login` with valid credentials; assert 200. Then GET `/api/v1/internal/ops/auth-audit?username=<user>` as SYSTEM_ADMIN and assert exactly one new row with `event_type=LOGIN_SUCCEEDED`, `username=<user>`, `user_id=<resolved>`, `api_client_id=NULL`, `failure_reason=NULL`, `actor_ip=<request remote addr>`, `correlation_id=<MDC value>`. Compile fails (no entity, no service, no endpoint), then assertion fails.
- GREEN (compile-cascade in one slice):
  1. Migration `V{n+1}__auth_event_audit.sql` (table + three indexes).
  2. Entity `AuthEventAudit` + enums `AuthEventType` + `AuthEventFailureReason`.
  3. Repository `AuthEventAuditRepository extends JpaRepository` with `findRecentByUsername(...)` / `findRecentByEventType(...)`.
  4. Service `AuthAuditService` with `recordLoginSuccess(AppUser, String ip, String correlationId)`.
  5. Read controller `AuthAuditController` with `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` over `GET /api/v1/internal/ops/auth-audit`.
  6. Wire `AuthAuditService` + `HttpServletRequest` into `AuthController`; add the `recordLoginSuccess` call after JWT mint.
  
  Test passes.
- Refactor: none.

**Slice 2 — `LOGIN_FAILED` writes the correct `failure_reason` for each Spring exception type**
- RED: One test per branch. Bad password → `INVALID_CREDENTIALS`. Locked account → `ACCOUNT_LOCKED`. Disabled account → `ACCOUNT_DISABLED`. Each test POSTs `/login` and asserts an `auth-audit` row with the expected `event_type=LOGIN_FAILED` + `failure_reason`. Initially fails (controller doesn't catch).
- GREEN: wrap the `authenticationManager.authenticate(...)` call in a try/catch in `issuePasswordToken`. Catch `BadCredentialsException` → record `INVALID_CREDENTIALS` + rethrow. Catch `LockedException` → record `ACCOUNT_LOCKED` + rethrow. Catch `DisabledException` → record `ACCOUNT_DISABLED` + rethrow. Default catch (`AuthenticationException`) → record `OTHER` + rethrow.
- Refactor: extract the catch-and-record block into `AuthAuditService.recordLoginFailureFromException(username, AuthenticationException ex, ip, correlationId)` to keep the controller tight.

**Slice 3 — `API_CLIENT_TOKEN_*` events**
- RED: Two tests. Valid client-credentials → `API_CLIENT_TOKEN_SUCCEEDED` with `api_client_id` populated and `user_id` NULL. Bad secret → `API_CLIENT_TOKEN_FAILED` with `failure_reason=INVALID_CREDENTIALS`.
- GREEN: wrap `issueClientCredentialsToken` similarly. `ApiClientAuthenticationService.authenticate` already throws on failure — catch its exception, record, rethrow.

**Slice 4 — `TOKEN_REFRESH_*` events with branch-specific `failure_reason`**
- RED: Four tests. (a) Missing cookie → `TOKEN_REFRESH_FAILED` + `MISSING_REFRESH_COOKIE`. (b) Hash-not-found / revoked → `TOKEN_EXPIRED` (or `TOKEN_REVOKED` if we can tell the revoked-vs-missing apart from the repo lookup — currently we can't; `findByTokenHashAndRevokedFalse` collapses them; either widen the lookup or use `TOKEN_REVOKED` as the umbrella for "found-but-not-usable"). (c) Expired token → `TOKEN_EXPIRED`. (d) Refresh token with neither user nor client → `OTHER`. (e) Valid refresh → `TOKEN_REFRESH_SUCCEEDED`.
- GREEN: replace each of the three current 401-return branches with `authAuditService.recordTokenRefreshFailure(...)` + return; add `recordTokenRefreshSuccess` after the successful mint.
- Refactor: consider widening the refresh-token lookup to `findByTokenHash` (without the `revoked=false` filter) so the controller can distinguish "found and revoked" vs "not found at all" — gives `TOKEN_REVOKED` real meaning. If the change is intrusive, defer and use `OTHER` for the merged case; document the limitation in the entry.

**Slice 5 — `LOGOUT` event writes regardless of cookie presence**
- RED: Two tests. With a valid refresh cookie → `LOGOUT` row with `username=<user>`. Without a cookie → `LOGOUT` row with `username='<anonymous>'` (sentinel value) and `user_id=NULL`.
- GREEN: in `/logout`, after revocation work, call `authAuditService.recordLogout(...)`. When `refreshCookie==null`, resolve `username` from `SecurityContextHolder` if present, otherwise the sentinel `<anonymous>`.

**Slice 6 — `PASSWORD_CHANGED` event on successful self-service change**
- RED: Seed a user with `passwordChangeRequired=true`. POST `/password` with a valid new password. Assert one `PASSWORD_CHANGED` row.
- GREEN: after `appUserRepository.save(user)`, call `authAuditService.recordPasswordChanged(user, ip, correlationId)`.
- Note: failed password-change attempts (`IllegalArgumentException` paths) are explicitly **not** audited in this PR. If forensics later wants them, add `PASSWORD_CHANGE_FAILED`.

**Order:** Slice 1 carries the compile-cascade (migration + entity + service + read endpoint). Slices 2–6 are additive on top. Each slice has its own integration test class to keep one logical assertion per test name.

**Anti-pattern check (per the prompt):** none of these tests mock `AuthAuditService` or `AuthEventAuditRepository`; all verify by hitting the new `/auth-audit` HTTP endpoint. Tests would survive a refactor that, say, moved the audit writes into a Spring event listener — the public HTTP behaviour is invariant.

#### Files touched (final list)

Add:
- `backend/src/main/resources/db/migration/V{n+1}__auth_event_audit.sql` (table + 3 indexes)
- `backend/src/main/java/com/bhawana/lms/domain/AuthEventAudit.java`
- `backend/src/main/java/com/bhawana/lms/domain/AuthEventType.java` (8 values)
- `backend/src/main/java/com/bhawana/lms/domain/AuthEventFailureReason.java` (7 values)
- `backend/src/main/java/com/bhawana/lms/repo/AuthEventAuditRepository.java`
- `backend/src/main/java/com/bhawana/lms/service/AuthAuditService.java` (single collaborator owning all writes)
- `backend/src/main/java/com/bhawana/lms/web/AuthAuditController.java` (`GET /api/v1/internal/ops/auth-audit` — SYSTEM_ADMIN)
- `backend/src/test/java/com/bhawana/lms/web/AuthControllerLoginAuditTest.java`
- `backend/src/test/java/com/bhawana/lms/web/AuthControllerTokenAuditTest.java`
- `backend/src/test/java/com/bhawana/lms/web/AuthControllerRefreshAuditTest.java`
- `backend/src/test/java/com/bhawana/lms/web/AuthControllerLogoutAuditTest.java`
- `backend/src/test/java/com/bhawana/lms/web/AuthControllerPasswordAuditTest.java` (or one test class with one test per slice — keep one assertion per name)

Edit:
- `backend/src/main/java/com/bhawana/lms/web/AuthController.java` — inject `AuthAuditService` + `HttpServletRequest`; wrap `authenticate` calls in try/catch; add success calls; convert the three `/refresh` 401-returns into branch-aware audit writes; add `/logout` + `/password` audit calls.

Untouched (deliberately):
- `AppUserAuditEvent` — stays for entity-state mutations (e.g. admin reset-password, which is owned by #148).
- `ApiClientAuditEvent` — stays for API-client state mutations.
- `AuditExplorerRepository` / `AuditExplorerService` / `AuditExplorerController` — adding `auth_event_audit` as a 5th stream is left to #159.
- Spring `AuthenticationEventPublisher` wiring — not subscribed in this PR; #155 owns it.

#### Effect on app

- Every login (user + API client), token-refresh, logout, and password-change writes one row to `auth_event_audit` with actor, IP, correlation, and (for failures) typed reason.
- SOC can run `SELECT username, COUNT(*) FROM auth_event_audit WHERE event_type='LOGIN_FAILED' AND created_at > NOW() - INTERVAL '1 hour' GROUP BY username ORDER BY 2 DESC` to spot credential-stuffing.
- `INVALID_CREDENTIALS` bucketing prevents the audit table from leaking valid usernames to a compromised admin.
- New read endpoint at `/api/v1/internal/ops/auth-audit` for immediate SOC visibility.
- Tiny per-request DB insert (1 row per auth event). Indexes keep the lockout/SOC queries fast.
- No user-visible UI change. The Explorer will surface this stream once #159 lands.

#### Cluster impact

- **#147** ("[AUD-1] Auth endpoints not audited") is a strict duplicate of #71 — closes on the same PR. Cross-link in the PR description.
- **#155** ("[AUD-9] Failed-auth events not fed into lockout/alert pipeline") depends on #71. Once `auth_event_audit.LOGIN_FAILED` + `INVALID_CREDENTIALS` rows exist, the lockout job becomes a single SQL `COUNT(*) WHERE username=? AND event_type='LOGIN_FAILED' AND created_at > NOW() - threshold` + a flip of `app_user.locked`. Add a brief note to #155 listing this PR as its prerequisite.
- **#159** ("[R-4] Audit Explorer only covers 4 streams") — `auth_event_audit` is the natural 5th stream. Cross-reference but don't wire it here.
- **#148** ("[AUD-2] Admin reset-password writes no audit row") — different entry point (admin-driven via a different endpoint) and the natural row goes into `app_user_audit_event` as a state mutation. Independent of #71.
- **#80** ("No admin 'log out everywhere' / global JWT revocation") — when that lands, it should emit a `LOGOUT` row per revoked session (or a single `FORCED_LOGOUT_ALL` event — add the value then). Not in scope here.
- **#97** ("[B-9] Refresh-token rotation race logs out concurrent browser tabs") — orthogonal; refresh-token races will produce more `TOKEN_REFRESH_FAILED` rows once this PR lands, which is forensically correct (the user *was* logged out unexpectedly).

**Implementation status — CLOSED (2026-06-03):**

| Slice | Delivered | Primary code |
|-------|-----------|--------------|
| Schema | `auth_event_audit` (V84) + username/event_type/correlation indexes | `V84__auth_event_audit.sql` |
| Write path | Manual audit on LSP UI login, API client token, refresh, logout, password change (success + typed failures) | `AuthController.java`, `AuthAuditService.java` |
| Read path | `GET /api/v1/internal/ops/auth-audit` (SYSTEM_ADMIN; username + eventType filters) | `AuthAuditController.java` |
| Supporting | `RefreshTokenRepository.findByTokenHash` (revoked vs missing refresh); `DisabledException` / `LockedException` → 401 | `RefreshTokenRepository.java`, `GlobalExceptionHandler.java` |
| Tests | Login/token/refresh/logout/password audit slices | `AuthControllerAuthAuditTest.java`, `AuthControllerTest.java` |

- Event types: `LOGIN_*`, `API_CLIENT_TOKEN_*`, `TOKEN_REFRESH_*`, `LOGOUT`, `PASSWORD_CHANGED`.
- **#147** closes as duplicate of #71.
- **Deferred:** Spring `AuthenticationEventPublisher` (filter-level failures); `PASSWORD_CHANGE_FAILED`; Audit Explorer AUTH stream (#159 9th stream); **#155** lockout job (prerequisite rows now exist).

---

### #72 — Webhook signing secret returned in admin GET responses (not one-shot) [DEFERRED 2026-06-01 — needs internal discussion]
**Labels:** gap, security · **Link:** https://github.com/sid12701/lms/issues/72

**Problem (plain English):** When admin looks at an LSP's webhook config, the signing secret comes back in plain text. There's no concept of "shown once at create/rotate, never again." Anyone with admin can copy it whenever.

**Possible fixes:**
1. **One-shot reveal on create + rotate; subsequent reads return fingerprint only** — matches API-client secret pattern.
2. **Reveal only with explicit user action + audit** — middle ground; secret still re-readable.
3. **Move secret out of admin API entirely; only reachable via rotation flow** — strongest; least friction.

**Recommended:** Option 1. Pattern already exists; reuse it for consistency.

**Effect on app:** Admins copying the secret today need to use rotate-with-reveal. UI shows fingerprint (`****abcd`). Partner integrations untouched (they get the secret out-of-band on create/rotate).

**Detailed solution after discussion (2026-06-01) — DEFERRED:**

The user is taking the storage / rotation / migration question to an internal discussion before deciding. The implementation plan is intentionally left blank; what follows is the **completed audit + the four open decisions framed so the discussion can land directly on choices**, not on fact-finding.

#### Audit of linked surface area (done before deciding)

| Surface | Path | State |
|---|---|---|
| Lsp entity | `backend/.../domain/Lsp.java:39` | Stores `webhook_signing_secret VARCHAR(255)` as **plaintext**. No hash column, no `previous_signing_secret`, no `previous_signing_secret_valid_until`, no `last_rotated_at`, no fingerprint. |
| Admin controller | `backend/.../web/LspAdminController.java:74,93` | `UpdateWebhookSubscriptionRequest` takes the plaintext `signingSecret` as a **client-supplied input** (admin types or pastes the secret) and writes it raw. `WebhookSubscriptionResponse` returns the same plaintext on every GET. Reveal is perpetual, not one-shot. |
| LSP listing | `LspAdminController` `GET /api/v1/internal/admin/lsps` and `GET /lsps/{id}` | Both surfaces include the plaintext secret in the response body. Anyone with SYSTEM_ADMIN role can read it indefinitely. |
| Admin Directory service | `backend/.../service/AdminDirectoryService.java` | Also reads `signingSecret` (grep hit) — **verify whether the admin directory listing also leaks it**. Treat as suspect; include in the fix's "stop returning plaintext" sweep. |
| Outbox dispatch | `backend/.../service/WebhookOutboxService.java` | Reads `Lsp.webhookSigningSecret` plaintext to compute outbound `HMAC-SHA256(secret, payload)`. **Plaintext is server-required at sign time** — pure-hash storage (the ApiClient pattern) cannot be applied as-is. |
| Sibling: ApiClient secret model | `backend/.../domain/ApiClient.java:36-59,161-166` | The right shape to imitate: `secret_hash`, `previous_secret_hash`, `previous_secret_valid_until`, `last_rotated_at`, and a domain method `rotateSecret(newSecretHash, previousSecretHash, previousSecretValidUntil)`. ApiClient secrets are *verified* by the server (hash compare works); webhook secrets are *used* by the server (hash compare doesn't). |
| Audit destination | none today | No `lsp_audit_event` or `lsp_webhook_secret_audit` table exists. Rotation / reveal events have nowhere to write. #154 (IP allowlist audit) faces the same gap and is a candidate consumer of any new LSP-side audit table. |
| Webhook signing format | `gaps-bugs-audit.md` § #129 | The `timestamp.payload` HMAC format is undocumented for receivers. Any rotation grace-window decision interacts with #129 because dual-sign requires two signature headers, which receivers need to know about. |

#### Open decisions (to be settled in the internal discussion)

1. **At-rest storage strategy.** Three candidates: (a) keep plaintext at-rest but stop returning it from admin GETs + add a fingerprint column; (b) envelope-encrypt the plaintext with an app-level key (AES-GCM, key from env/config, never persisted); (c) external KMS / HSM sign API so the server never holds the secret. Trade-off: (a) is cheapest and matches the issue's intent but doesn't improve defense-in-depth against a DB-only compromise; (b) raises the bar without new infra; (c) is overkill for the current threat model.
2. **Rotation strategy (outbound webhook signing).** Three candidates: (a) **dual-sign grace window** mirroring ApiClient — add `webhook_signing_secret_previous` + `..._valid_until` columns, outbound webhooks carry two signature headers (`X-Bhawana-Signature-Current` + `X-Bhawana-Signature-Previous`) during the grace window, receivers verify against either; (b) **hard cutover** — rotate replaces immediately, receivers must update before next dispatch; (c) **explicit two-step promote** — rotate sets a `pending_signing_secret`, admin calls `POST /promote` to cut over. Interacts with #129 (signature header documentation).
3. **Migration of existing plaintext secrets.** Three candidates: (a) leave the plaintext column populated, compute `webhook_signing_secret_fingerprint` at migration time (`sha256:<first 8 hex>`), stop returning plaintext from admin GETs; (b) hash plaintext into fingerprint and null-out the plaintext column at migration — breaks all existing live integrations until rotation; (c) force-rotate every LSP at migration time — invalidates all configured receivers without coordination. Option (a) is the only zero-disruption migration.
4. **Audit destination.** Three candidates: (a) **new generalized `lsp_admin_audit_event` table** designed to absorb webhook-secret rotate/reveal AND #154 (IP allowlist add/remove) AND future LSP-config admin actions; (b) dedicated `lsp_webhook_secret_audit` — narrower; risks audit-table proliferation when #154 lands; (c) extend `ApiClientAuditEvent` — semantically wrong (different aggregate), rejected on bounded-context grounds.

#### Why deferred

The four decisions are coupled: the at-rest choice (1) constrains the migration choice (3), and the rotation strategy (2) is gated by receivers we don't unilaterally control. The user is taking this to an internal discussion that includes the receiver-side coordination question. Pre-committing a TDD plan now would either (i) lock in a storage choice that internal discussion overrides, or (ii) sit half-finished if the decision changes the entity shape.

**When the discussion resolves:** the work to re-engage with #72 is now a *decision*, not an *audit* — the surface area, the ApiClient analogue, the consumer constraints, and the migration zero-disruption path are all written down here. Pick one option from each of the four decision points above and the TDD plan falls out mechanically (one slice per: schema migration, controller GET sanitization, rotate endpoint, dispatcher dual-sign-or-not, audit writes).

#### Cluster impact (still useful even while deferred)

- **#129** ("[F-6] Webhook signing format undocumented for receivers") is tightly coupled. Whichever rotation strategy lands (especially dual-sign) needs the documented header set updated together. Land #129 in the same PR or back-to-back.
- **#153** ("[AUD-7] Webhook URL / signing-secret rotation not audited") is the audit-side twin of #72. The audit-destination decision (4) above closes #153 as a byproduct.
- **#154** ("[AUD-8] IP allowlist add/remove audit incomplete") is the natural second customer of a generalized `lsp_admin_audit_event` table. If decision (4) picks the generalized table, #154 lands almost for free in a follow-up.
- **#82** ("Verify SSRF protection wired into webhook URL update path") is independent at the code level but lives in the same admin webhook-config code path. Likely good hygiene to bundle the SSRF verify into the same PR review.
- **#140** ("[SEC-Δ-2] Webhook signing secret returned in admin GET") is a strict duplicate of #72 — they close together.

---

### #79 — Disabled LSP_API_CLIENT keeps working until access token expires (no tv check) [PARTIAL — 2026-06-01; Slice 2 still open as of 2026-06-02]
**Labels:** gap, security, rbac · **Link:** https://github.com/sid12701/lms/issues/79 · **Status:** **CLOSED — IMPLEMENTED** (2026-06-06 audit). `ApiClientJwtSessionValidator` enforces `tvLsp`/`tvApiClient` + ACTIVE on every LSP API request; refresh calls `lookupByClientId` → `validateActive`; rotate-secret bumps `tokenVersion`. Closes **#93** as duplicate.

> **2026-06-02 follow-up audit — what shipped vs what didn't:**
>
> Shipped (via #63's `LspStatusKillChain` bundle):
> - ✅ `api_client.token_version` column (V77).
> - ✅ `ApiClient.revokeAllSessions()` bumps `tokenVersion` (line 173-175).
> - ✅ LSP-disable cascade through `LspStatusService` bumps every child client's `tokenVersion` and flips status to INACTIVE.
> - ✅ `ApiClientJwtSessionValidator` checks `authType==API_CLIENT`, then `tvLsp`, `tvApiClient`, `lsp.status==ACTIVE`, `apiClient.status==ACTIVE`.
> - ✅ `ApiClientAuthenticationService.lookupByClientId` rejects when `status != ACTIVE` (line 69 — closes #93).
> - ✅ Admin per-client disable via `PUT /api-clients/{id}` works because the validator's status check (line 66) catches the disabled client — even though `ApiClientManagementService.updateClient` does not itself bump `tokenVersion`. Belt-and-braces.
>
> **NOT shipped — Slice 2 of the original plan ("Secret rotation kills outstanding tokens"):**
> - ❌ `ApiClient.rotateSecret(...)` (line 177) does NOT call `revokeAllSessions()` / bump `tokenVersion`. A secret rotation today leaves outstanding access JWTs valid until natural expiry. The `grace_seconds` window on the OLD secret only affects new authentication attempts, not already-minted access tokens.
> - ❌ No test asserting "mint JWT → rotate secret → re-issue → 401".
>
> **Recommended:** add a one-line `this.tokenVersion++;` (or call `revokeAllSessions()`) inside `ApiClient.rotateSecret(...)`, plus a regression test. Until then, the rotate-secret kill chain is broken even though disable/cascade works.

**Problem (plain English):** When admin disables an LSP API client, the client's existing access token still works until it naturally expires. Same for refresh. So "disable" is really "disable in N minutes."

**Possible fixes:**
1. **Add `tokenVersion` on `api_clients`; auth filter rejects mismatched JWTs** — clean parallel to AppUser.tv.
2. **Shorten access token TTL** — partial mitigation; still leaves a window.
3. **Maintain a revocation list in Redis** — works but adds infra dependency.

**Recommended:** Option 1. Same pattern as users; auth filter check is cheap; effect is immediate.

**Effect on app:** Disable means disable. Auth filter does one extra column comparison per request (already does it for users). Combined with #63 (LSP-level disable), full kill chain works.

**Detailed solution after discussion (2026-06-01):**

#### Audit of linked surface area (done before deciding)

The vulnerability is wider than the issue text suggests — there are **three coupled defects**, all in the API-client JWT lifecycle:

| Surface | Path | State |
|---|---|---|
| ApiClient entity | `backend/.../domain/ApiClient.java` | Has `secret_hash`, `previous_secret_hash`, `previous_secret_valid_until`, `last_rotated_at`. **No `token_version` column.** |
| Token mint (refresh + initial) | `backend/.../web/AuthController.java:220-235` (`mintTokenForApiClient`) | Builds the JWT with `new ManagedUserState(false, Instant.EPOCH, 0L)` — **`tv` and `pwdv` are hardcoded to 0** for every API-client token. Zero per-client state propagated into the JWT. |
| Per-request validator | `backend/.../security/SecurityConfig.java:235-269` (`managedUserSessionValidator`) | Only knows `AppUserRepository`. Looks up `findByUsername(jwt.getSubject())`. **For API-client tokens, `getSubject()=clientId` won't match any AppUser**, so `.orElseGet(OAuth2TokenValidatorResult::success)` returns silent success. After signature/issuer/expiry checks, the token is treated as fully valid for its TTL. |
| Refresh path | `AuthController.java:222` calls `apiClientAuthenticationService.lookupByClientId(...)` | `lookupByClientId` (`ApiClientAuthenticationService.java:55-65`) **does not check `status`**. A disabled client whose refresh cookie is still valid can mint a fresh access token — and that fresh token bypasses #79's per-request validator the same way (silent success). This is the **#93 vulnerability** — `[B-11] ApiClientAuthenticationService.lookupByClientId doesn't check ACTIVE`. Bundles with #79. |
| Discriminator already in the JWT | `AuthController.java:228` | The JWT for API-client tokens already carries `"authType": "API_CLIENT"`. Clean disambiguator for the validator — no need for sub-namespace prefixes or username-vs-clientId lookup races. |
| AppUser side (the working analog) | `SecurityConfig.java:235`, `AppUser.tokenVersion`, `AuthController.loadManagedUserState` | Already does what we want: bumps `tv` on changes the admin cares about, the validator rejects mismatched `tv`. Mirror this pattern. |
| Parent-LSP status | `Lsp.status`, `LspStatus` enum (#63) | The issue's text says "(and parent LSP active — see #63)". Today's validator doesn't reach into `apiClient.lsp.status`. Adding this in the same PR closes the runtime half of #63's kill chain. |

**Conclusion of audit:** the literal fix is three-part: (1) add `tokenVersion` to `ApiClient` and bump it on the right transitions, (2) propagate `tv` into the JWT at mint time, (3) extend the validator to branch on `authType` and check status/tv/parent-LSP-status for `API_CLIENT` tokens. Plus (4) fix `lookupByClientId` to reject disabled clients during refresh — which closes #93 as a byproduct.

#### Decision (after grilling, 2026-06-01)

1. **Schema:** add `token_version BIGINT NOT NULL DEFAULT 0` to `api_client`. Mirrors `app_user.token_version`. Default 0 means existing rows are unchanged; existing JWTs (which carry `tv=0`) still validate after deploy — **zero-disruption migration**.
2. **Bump triggers** (3 sites):
   - `ApiClient.disable()` (or the path that sets `status != ACTIVE`) → `bumpTokenVersion()`.
   - `ApiClient.rotateSecret(...)` → `bumpTokenVersion()` (this fixes today's silent bug: rotating the secret currently leaves the old token valid; that's a separate vuln this PR closes).
   - Future #80 (force-revoke-all admin endpoint) → call the same `bumpTokenVersion()`. Not implemented here but the seam is in place.
   - Re-enable does **NOT** bump (issued tokens get the new tv via mint; old pre-disable tokens stay dead from the disable bump).
3. **Validator extension (`SecurityConfig.managedUserSessionValidator` → `principalSessionValidator`):** branches on `jwt.getClaim("authType")`.
   - `authType == "API_CLIENT"`: `ApiClientRepository.findByClientId(jwt.getSubject())` → require `status == ACTIVE` AND `lsp.status == ACTIVE` AND `jwt.tv == apiClient.tokenVersion`. Any fail → `OAuth2Error("invalid_token", "Session is no longer valid")`.
   - Else (AppUser): existing logic unchanged.
   - Missing/unknown `authType` for a JWT that doesn't match an AppUser: maintain current silent-success behaviour to keep the bootstrap user and future principal types from breaking. (Add a TODO referencing #146 — tenant-context defaults to ADMIN in null state.)
4. **Parent-LSP-active check included in this PR** — one extra `apiClient.lsp.status == ACTIVE` clause. Closes the runtime half of #63 without #63 having to coordinate.
5. **Token mint update (`AuthController.mintTokenForApiClient`):** read `apiClient.tokenVersion` and pass it as the `tv` claim instead of `0L`. `pwdv` stays at `Instant.EPOCH.toEpochMilli()` (= 0) — there's no equivalent of password-version for API clients; the validator's `pwdv` check applies only to the AppUser branch.
6. **Refresh path fix (bundled — closes #93):** `ApiClientAuthenticationService.lookupByClientId(...)` adds a `status == ACTIVE` check; throws `BadCredentialsException("Invalid credentials")` on mismatch. Refresh for a disabled client now 401s at the controller, never minting a doomed token.

#### TDD plan (vertical slices, one RED → GREEN at a time)

Tests are integration-style via MockMvc: mint a real token through `/api/v1/auth/token`, mutate state through the admin/management surface, then call a protected endpoint and assert the response. No mocking of `SecurityConfig` or `ApiClientRepository`.

**Slice 1 — Disabled API client's existing JWT 401s on next call**
- RED: New test class `ApiClientTokenRevocationTest`. Mint a real JWT via `POST /api/v1/auth/token` with valid client creds. Confirm `GET /api/v1/lsp/loan-applications` returns 200 with that token. Then disable the client via `ApiClientManagementService` (or whatever the disable surface is). Re-issue the same `GET` with the same token → assert **401** with body code `INVALID_TOKEN`. Initially fails (silent success → 200).
- GREEN (compile-cascade):
  1. Migration `V{n+1}__api_client_token_version.sql` → `ALTER TABLE api_client ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;`.
  2. `ApiClient.tokenVersion` field + getter + `bumpTokenVersion()` method.
  3. `ApiClientManagementService.disable()` (or the equivalent path that sets non-ACTIVE) calls `bumpTokenVersion()` inside the same `@Transactional` boundary.
  4. `AuthController.mintTokenForApiClient`: read `apiClient.tokenVersion` for the `tv` claim.
  5. `SecurityConfig.principalSessionValidator`: branch on `authType="API_CLIENT"`, reject when `apiClient.status != ACTIVE` OR `jwt.tv != apiClient.tokenVersion`.
- Refactor: rename `managedUserSessionValidator` → `principalSessionValidator`.

**Slice 2 — Secret rotation kills outstanding tokens**
- RED: Mint a JWT. Confirm it works. Rotate the client's secret via the rotate endpoint. Re-issue the same `GET` → assert 401.
- GREEN: `ApiClient.rotateSecret(...)` calls `bumpTokenVersion()` after updating `secretHash`/`previousSecretHash`.

**Slice 3 — Disabling parent LSP kills child API-client tokens**
- RED: Mint a JWT for a client whose parent LSP is ACTIVE. Disable the LSP (`LspStatus != ACTIVE`). Re-issue → assert 401.
- GREEN: `SecurityConfig.principalSessionValidator` adds the `apiClient.lsp.status == ACTIVE` clause. (No bump fan-out needed; the validator checks the LSP every request.)

**Slice 4 — Disabled client cannot refresh into a new token (closes #93)**
- RED: Mint a refresh cookie via `/api/v1/auth/token`. Disable the client. POST `/api/v1/auth/refresh` with the refresh cookie → assert 401.
- GREEN: `ApiClientAuthenticationService.lookupByClientId(...)` adds the status check; throws `BadCredentialsException` on disabled.

**Slice 5 — Regression: AppUser tokens unaffected**
- RED: A scoped test that exercises the existing AppUser flow (login → call protected endpoint) and asserts 200, with the new code in place. Failing this slice means the validator refactor broke the user path.
- GREEN: should pass already if Slice 1 wired the `authType` branch correctly. Locks the invariant.

**Slice 6 — Principal-type collision safety**
- RED: Seed an `AppUser` with username `lsp-prod` AND an `ApiClient` with `clientId="lsp-prod"`. Mint an API-client token via `/token` (subject=`lsp-prod`, `authType=API_CLIENT`). Disable the AppUser → assert API-client token still 200s (the validator didn't accidentally take the AppUser path). Disable the ApiClient → assert API-client token 401s.
- GREEN: depends entirely on `authType` claim discrimination from Slice 1. Locks against a future refactor that drops the `authType` branch.

**Slice 7 — Zero-disruption migration guard**
- RED: Pre-deploy, an existing JWT was minted with `tv=0`. Migration runs; `api_client.token_version` defaults to `0`. The JWT must still validate after migration if the client is still active. Test asserts this explicitly via a hand-crafted JWT with `tv=0` against an `ApiClient` row whose `token_version=0`.
- GREEN: no new code; default-value-zero invariant holds. This is a behavior-under-deployment spec, not new logic.

**Order:** Slice 1 (compile-cascade) → Slices 2-4 (additive triggers + refresh fix) → Slice 5 (regression guard) → Slice 6 (collision safety) → Slice 7 (migration guard). Slice 4 can be carved into its own PR if #93 is preferred as a separate close; default is to bundle.

#### Files touched (final list)

Add:
- `backend/src/main/resources/db/migration/V{n+1}__api_client_token_version.sql`
- `backend/src/test/java/com/bhawana/lms/security/ApiClientTokenRevocationTest.java`

Edit:
- `backend/src/main/java/com/bhawana/lms/domain/ApiClient.java` (+`tokenVersion` field, getter, `bumpTokenVersion()`; have `rotateSecret(...)` call it internally)
- `backend/src/main/java/com/bhawana/lms/service/ApiClientManagementService.java` (call `bumpTokenVersion()` on the disable path; confirm `rotateSecret` path is wired correctly via the domain method)
- `backend/src/main/java/com/bhawana/lms/service/ApiClientAuthenticationService.java` (`lookupByClientId` adds `status == ACTIVE` check — closes #93)
- `backend/src/main/java/com/bhawana/lms/web/AuthController.java` (`mintTokenForApiClient` reads `apiClient.tokenVersion` for the `tv` claim)
- `backend/src/main/java/com/bhawana/lms/security/SecurityConfig.java` (`managedUserSessionValidator` → `principalSessionValidator`, branches on `authType`, requires `ApiClientRepository` bean dependency in the `jwtDecoder` factory)

Untouched (deliberately):
- Token TTL configuration — short TTL is a partial mitigation, not the fix.
- Caching layer — per-request DB lookups on `ApiClient` + `Lsp` are correct; if perf becomes an issue, a short TTL cache with explicit invalidation on disable is a follow-up PR (do **not** bundle).
- AppUser path — unchanged.
- #80 (force-revoke-all admin endpoint) — only the `bumpTokenVersion()` seam is in place; the endpoint itself is #80's PR.

#### Effect on app

- Disabling an LSP API client invalidates every outstanding access token for that client on the next request. Disable means disable.
- Rotating an API-client secret invalidates outstanding tokens too — closes a silent bug today's code carries.
- Disabling an LSP cascades to its child API clients via the validator's parent-status check (no fan-out write; validator checks `lsp.status` per request).
- Refresh for a disabled client 401s at the controller (closes #93).
- Per-request overhead: one extra `SELECT` from `api_client` (joined to `lsp`). Already paid for AppUsers; symmetric cost.
- Zero-disruption deploy: existing API-client JWTs carry `tv=0`; new column defaults to `0`; tokens validate until natural expiry. Post-deploy disable bumps the value normally.
- No user-visible UI change.

#### Cluster impact

- **#93** ("[B-11] ApiClientAuthenticationService.lookupByClientId doesn't check ACTIVE") closes as a byproduct via Slice 4. Mark as a strict duplicate in the PR description.
- **#63** ("LSP cannot be disabled via Admin UI/API") — the **runtime half** is closed here (validator checks `lsp.status` per request). #63's PR still owns the admin UI/API surface to flip `Lsp.status` in the first place, and may want to broadcast the disable into a "kill all refresh tokens for this LSP" action; both are #63's scope.
- **#80** ("No admin 'log out everywhere' / global JWT revocation") — the `bumpTokenVersion()` seam is reusable. #80's endpoint becomes a thin wrapper that calls `bumpTokenVersion()` for the targeted principals; no new mechanism needed.
- **#155** ("Failed-auth events not fed into lockout/alert pipeline") — once #71's `auth_event_audit` lands, the `bumpTokenVersion()` path should emit an audit row (event_type `FORCED_LOGOUT_ALL` or similar). Not added here to keep scope tight; flag for #155.
- **#142** ("LspIpAllowlistFilter cache is process-local — unbounded staleness across replicas") — the validator we add here also reads `ApiClient` per request. No cache today, so no replica-staleness issue. If a future perf PR adds a cache, it must use the same invalidation pattern #142 lands on.
- **#146** ("Tenant context defaults to ADMIN in null state") — adjacent. The validator's "unknown principal → silent success" branch is the same anti-pattern shape; bundling them is tempting but they're independent code paths. Flag in #146.

---

### #83 — [B-1] LspIpAllowlistFilter cache not invalidated on allowlist mutation (60s staleness) [SOLVED 2026-06-01]
**Labels:** bug, security, scale-risk · **Link:** https://github.com/sid12701/lms/issues/83 · **Status:** **CLOSED — IMPLEMENTED** (#64). Closes **#164** as verification duplicate.

**Problem (plain English):** Allowlist matchers are cached for 60 seconds. The admin endpoint that changes allowlist rules does not call the filter's `invalidateCache(lspId)`. So when you add or remove a rule, the change takes up to 60 seconds to apply — on each replica independently.

**Possible fixes:**
1. **Call `invalidateCache(lspId)` on every mutation** — single-line fix per mutation site.
2. **Lower TTL aggressively (e.g., 5s)** — bounded staleness without code changes; more DB reads.
3. **Move cache to Redis (shared)** — fixes multi-replica drift too (#142 territory).

**Recommended:** Option 1 immediately; Option 3 follow-up for multi-replica deployments.

**Effect on app:** Allowlist changes effective on next request. No partner impact.

**Detailed solution after discussion (2026-06-01):**

#### The issue's headline turned out to be wrong, but the bug is real (and subtler)

The issue says the cache is never invalidated. The code says otherwise: `LspIpAllowlistAdminController` lines 71 and 87 both call `allowlistFilter.invalidateCache(lspId)` — once after a CIDR is added, once after a CIDR is removed. So a single admin running on a single server *does* see the change take effect on the next request. The "60 seconds of staleness" headline is only true in two situations the audit doc missed:

1. **The "almost invisible" race.** The invalidate call sits *inside* the database transaction, just before it commits. The order of operations is: save the new row (uncommitted) → clear the cache → return → commit happens. There is a thin window between "clear the cache" and "commit happens" where another request can arrive, see an empty cache, run a fresh database query, and *not* see the new row yet (because it's not committed). That request will then re-populate the cache with the *old* data, and for the next 60 seconds the cache is wrong again — exactly what the invalidate was supposed to fix.
2. **Multi-server staleness.** When the app runs on more than one server (replica), each server has its own private in-memory cache. The admin's request lands on *one* server, so only *that* server's cache gets cleared. The other servers keep serving the old rules for up to 60 seconds. This is what issue #142 explicitly tracks.

The user picked the narrowest scope: fix the race, leave the multi-server problem to #142.

#### Decision (after grilling, 2026-06-01)

1. **Move the invalidate call from "inside the transaction" to "after the transaction commits."** Concretely: in `LspIpAllowlistAdminController.create()` and `delete()`, wrap the `allowlistFilter.invalidateCache(lspId)` call in Spring's `TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { ... } })`. The cache is now cleared *after* the new row is visible to other transactions, closing the race.
2. **Keep the 60-second TTL.** The TTL is a safety net for situations where the invalidate call fails or is forgotten in a future code change. Long TTL = lower DB load. Short TTL would only help with multi-server staleness, and that's deferred.
3. **Multi-server (#142) is deferred to its own PR.** A future PR introduces either a Redis pub/sub broadcast or a Postgres `LISTEN/NOTIFY` to make every server drop its cache when any one of them is told about a change.
4. **#83 is mostly already bundled into #64's solution** (see line 334 above — "This PR closes #64 + half of #154 + #83"). The race fix described here is a small **delta** to #64's plan: when #64's PR adds invalidation for the two new surface-specific lists (`ui_ip_allowlist` and `api_ip_allowlist`), both invalidations must use the after-commit hook, not an inline call. Without this delta, #64's PR would inherit the same race.

#### TDD plan (vertical slices, behaviour through public interfaces)

The user asked for an integration test with realistic concurrency. Two slices:

**Slice 1 — Single-threaded: add IP → immediately visible**
- RED: Integration test against a real Spring context. Pre-seed the cache by issuing a request that triggers the filter and warms it. Then POST a new CIDR via the admin endpoint. Immediately (same test thread, no sleep), simulate a request from the newly-allowed IP → assert 200.
- This test passes today on a single thread because the controller call invalidates the cache before returning. It locks the basic behaviour so it can't regress.

**Slice 2 — Concurrent: race window does not produce a stale cache**
- RED: A test that demonstrates the race. The simplest reliable shape: open a transaction manually via `TransactionTemplate`, save a new CIDR inside it, call `invalidateCache(lspId)` (the old position), and *while the transaction is still uncommitted*, on a separate thread, call `loadMatchers(lspId)` (or hit the filter via a real HTTP request). The separate thread should see the *old* state and re-cache it. After the transaction commits, the cache should now be stale despite the invalidate. **Today this test fails** (cache ends up stale) — that's the proof of the bug.
- GREEN: move the invalidate call to `afterCommit()`. Re-run the test: the separate thread's read still sees the old state (the new row isn't committed yet), but it *doesn't* re-cache during the window because — wait, actually, the separate thread's read still queries the DB and re-caches old data. So even after-commit doesn't prevent that exact re-caching. The reason after-commit fixes it: the *admin's* invalidate runs *after* commit, so when the next request after the commit arrives, the cache is cleared and the DB query then sees the new row.

  **Subtle correction:** the after-commit fix doesn't eliminate the race fully — a request that lands in the small window between commit and the after-commit hook firing will still get stale data. Spring's `afterCommit` fires synchronously immediately after commit, on the same thread, so the window is microseconds — practically negligible. To make this air-tight you'd need a database-side trigger or a serializable read of the cache. The user's scope (defer #142, accept the bound) makes this acceptable.

- Test asserts: after the admin POST returns, every subsequent request sees the new state. The pre-fix RED test simulates the bug by making the test thread wait *inside* the transaction; the post-fix GREEN test confirms no such pause is possible because invalidate doesn't run until after commit.
- Refactor: extract the after-commit registration into a small `CacheInvalidationCoordinator` helper if both create and delete paths use it. Optional.

**Anti-pattern check (per TDD philosophy):** the test exercises the real filter + real controller through HTTP / Spring's transaction template. No mock of `LspIpAllowlistFilter`, no mock of `LspIpAllowlistRepository`. The assertion is on observable behaviour ("next request from this IP gets 200") — survives any internal refactor that keeps the same external contract.

#### Files touched (final list)

Edit (if #64 lands first, these edits become part of #64's PR; if #83 lands standalone, these are #83's PR):
- `backend/src/main/java/com/bhawana/lms/web/LspIpAllowlistAdminController.java` — wrap both `invalidateCache` calls (lines 71 and 87) in `TransactionSynchronizationManager.registerSynchronization(...) { afterCommit() { ... } }`.

Add (or extend existing test files):
- `backend/src/test/java/com/bhawana/lms/security/LspIpAllowlistCacheInvalidationTest.java` — Slice 1 (single-threaded) + Slice 2 (concurrent race window).

Untouched (deliberately):
- The filter itself (`LspIpAllowlistFilter`) — its `invalidateCache` method is correct; only the *caller* is at fault.
- TTL value (`CACHE_TTL = Duration.ofSeconds(60)`) — stays at 60 seconds.
- Multi-server broadcast — owned by #142.

#### Effect on app

- After an admin adds or removes an IP, every subsequent request *on the same server* sees the new rule. The microsecond race that existed before is closed.
- On multi-server deployments, the bound stays the same: up to 60 seconds for other replicas to catch up. Not worse than today; #142 owns the real fix.
- No partner impact, no DB-load change, no schema change.
- The fix is one wrapping change × two call sites.

#### Cluster impact

- **#64** is the bigger umbrella. If #64 lands first, this race fix becomes part of #64's PR (specifically, the per-surface `LspUiIpAllowlistAdminController` and `LspIpAllowlistAdminController` invalidations must use the after-commit hook). Add a TODO line in #64's TDD plan above pointing to this race finding.
- **#142** owns multi-server staleness. Explicitly deferred.
- **#154** (allowlist add/remove audit) — independent. The after-commit hook doesn't conflict; the audit row write can stay inside the transaction (audit rows are state changes, not cache invalidations).
- **#164** ("Verify LspIpAllowlistAdminController calls invalidateCache") is partly answered by this audit: yes, it calls it; no, the call is mis-timed. Close #164 with a reference here.

---

### #86 — [B-4] Repayment idempotency-key race surfaces 500 instead of 409
**Labels:** bug, scale-risk · **Link:** https://github.com/sid12701/lms/issues/86 · **Status:** **OPEN** — plan grilled 2026-06-01, **NOT shipped** (2026-06-02 follow-up audit)

> **2026-06-02 follow-up audit:** the `[SOLVED 2026-06-01]` marker on this entry was incorrect. The plan below was grilled in detail but never implemented. Specifically:
> - No `V{n+1}__loan_payment_transaction_request_fingerprint.sql` migration exists; last migration is `V79`.
> - `LoanPaymentTransaction` has no `requestFingerprint` field (grep for `requestFingerprint` returns zero hits in entity/service).
> - `LoanRepaymentCommandService` has no `DataIntegrityViolationException` catch-and-recover around `save()`; the race still surfaces as a 500.
> - `LspApiIdempotencyService.execute` already has the body fingerprint comparison (line 49) but is missing the race-recovery `try { save } catch (DataIntegrityViolationException) { re-query }`. The 500-instead-of-409 race is still live there too.
> - Neither `LoanRepaymentCommandServiceIdempotencyTest` nor `LspApiIdempotencyServiceRaceTest` exists.
> - `git log --all --grep="#86"` returns nothing.
>
> **Recommended:** ship the plan below as written; remove this banner once verified on `main`.

**Problem (plain English):** Two concurrent calls with the same idempotency key both check "does it exist?", both see no, both insert. The database unique index catches the second one — but it surfaces as a generic 500 with a stack trace, not a clean 409.

**Possible fixes:**
1. **Catch `DataIntegrityViolationException`, re-query, return existing row** — minimal change; idiomatic.
2. **Upsert at the repository (`ON CONFLICT DO NOTHING RETURNING`)** — cleaner SQL; PG-specific.
3. **Pre-claim via Redis lock** — heavy; adds infra.

**Recommended:** Option 1. Trivial to add; doesn't change the data path.

**Effect on app:** Retries always get a 200 with the original transaction. Partners stop seeing intermittent 500s.

**Detailed solution after discussion (2026-06-01):**

#### What's actually broken (plain English)

The repayment endpoint accepts an `Idempotency-Key` header. The intent: if a partner sends the same request twice (because their network glitched), we should give them the same answer the second time — not create two payments. Today's code tries to do this with a textbook "check then insert":

1. Look up the database for a row with this key.
2. If found → return it.
3. If not found → create the payment.

The race is the gap between step 1 and step 3. If two requests arrive at the same millisecond:
- Both look up the database, both see "nothing exists yet".
- Both try to insert.
- The database's unique-key index lets one win.
- The loser's `INSERT` throws a constraint violation, which Spring turns into a generic 500 response with a stack trace.

What the partner sees: a 500. Most partners' retry logic treats 5xx as "transient server error, retry with a fresh key" — and now they create a duplicate payment under a different key, which is exactly what idempotency was supposed to prevent. Quiet disaster.

There's a second, *quieter* bug in the same path: today's idempotency check **only compares the key, not the body**. So a partner sending `Idempotency-Key=X amount=$100` and then later `Idempotency-Key=X amount=$200` (same key, different body) silently gets the *original* $100 transaction back. The intent was "I want to charge $200"; the response was "$100 charged" — a data-correctness gap nobody notices until reconciliation.

A **third** finding from the audit: the exact same race lives in `LspApiIdempotencyService.execute` (lines 45-67 of that file). Different table (`lsp_api_idempotency_record`), same vulnerability shape. Used by LSP API integrations for non-repayment operations. Already has body-fingerprint checking — it's missing only the catch-and-recover for the race.

#### Decision (after grilling, 2026-06-01)

1. **The loser gets 200 with the existing payment if the bodies match, 409 if the bodies differ.** This requires storing a body fingerprint alongside each idempotency record. Yes, this expands #86 beyond its literal "500→409" scope, but the user picked it because the loser-response behaviour the issue actually wants (genuine idempotency) needs the body comparison to work correctly. Without the fingerprint, the choice is binary and bad either way: always 200 (silently masks the data-correctness bug) or always 409 (breaks legitimate network retries).
2. **Schema change is small and backward-compatible:** add a nullable `request_fingerprint VARCHAR(64)` column to `loan_payment_transaction`. New writes populate it (SHA-256 of the canonical request body). Existing rows stay NULL. Comparison logic: if the stored fingerprint is NULL (legacy row), treat any new request as "matching" — so partners doing retries against pre-migration payments don't suddenly start seeing 409s.
3. **What goes into the fingerprint:** the partner-supplied parts of the request — `applicationId` + `targetInstallmentId` + `amount` + `postedAt` + `reference` + `channel`. The `actorUsername` is server-derived (the JWT principal making the call), not part of the partner's request — exclude it. The idempotency key itself is *not* in the fingerprint (it's the lookup key, not the body).
4. **Race fix in both places (this PR, bundled):** both `LoanRepaymentCommandService.recordPaymentTransaction` and `LspApiIdempotencyService.execute` get the catch-and-recover pattern around their `save()` calls. Catch `DataIntegrityViolationException`, re-query by key, then run the same match/mismatch logic. Two call sites, same recovery shape — a tiny helper method is justified.
5. **Body fingerprint already exists in `LspApiIdempotencyService` (line 87)** — the only thing missing there is the race fix. So that service gets the smaller delta: just wrap the `save()` with catch-and-recover.
6. **No Redis, no upserts, no infra.** The original Option 1 ("catch, re-query, return existing row") is the right shape; we're extending it with the fingerprint check, not replacing it.

#### TDD plan (vertical slices, behaviour through public interfaces)

Per the prompt's philosophy: tests describe what the system *does*, exercise the real HTTP/service interface, no mocking of repositories.

**Slice 1 — Duplicate request with same key and same body returns the original payment (200)**
- RED: Integration test. Seed a payment by calling the repayment endpoint normally. Then call the same endpoint again with the same idempotency key and the same body. Assert: the response is 200 and the returned payment ID matches the one created the first time. Today this test passes — locks the behaviour against regression.
- GREEN: nothing new; this is the baseline.

**Slice 2 — Duplicate request with same key but DIFFERENT body returns 409**
- RED: Seed a payment with `amount=10000`. Call the endpoint again with the same idempotency key and `amount=20000`. Assert 409 with body code `IDEMPOTENCY_CONFLICT` and a clear message ("Idempotency-Key has already been used for a different request"). Today this test fails — the code returns 200 with the original $100 transaction.
- GREEN:
  1. Migration `V{n+1}__loan_payment_transaction_request_fingerprint.sql`: `ALTER TABLE loan_payment_transaction ADD COLUMN request_fingerprint VARCHAR(64);`
  2. `LoanPaymentTransaction` entity: add `requestFingerprint` field + getter + an overloaded constructor that takes it.
  3. `LoanRepaymentCommandService`: extract a `fingerprint(request)` helper (SHA-256 of `(applicationId, targetInstallmentId, amount, postedAt, reference, channel)` serialised via Jackson). In `recordPaymentTransaction`, after `findByIdempotencyKey(...)` returns a row, compare its `requestFingerprint` to the freshly computed one. Match (or stored is NULL) → return existing. Mismatch → throw `ApiConflictException("IDEMPOTENCY_CONFLICT", ...)`.
  4. In `createInstallmentPayment`, persist the freshly computed fingerprint on the new row.
- Refactor: pull the helper into a shared utility if it's needed in more places.

**Slice 3 — Two concurrent requests with same key + same body produce one row, both return 200**
- RED: Concurrent integration test. Fire 50 parallel requests with identical key and body via an `ExecutorService`. Assert: exactly 1 row in `loan_payment_transaction` for that idempotency key; all 50 responses are 200 with the same payment ID. Today this test fails — N-1 of the threads will see 500s (the constraint violation).
- GREEN: In `LoanRepaymentCommandService.createInstallmentPayment` (or whichever method now wraps the save), catch `DataIntegrityViolationException` on the `save()` call, re-query by key (it definitely exists now), and re-run the fingerprint match/mismatch logic from Slice 2. Loser thread now returns the existing row instead of crashing.
- Refactor: the "save-or-recover" block becomes a private helper since `LspApiIdempotencyService` needs the same shape in Slice 5.

**Slice 4 — Deterministic race test that doesn't depend on actual concurrency**
- RED: Pre-seed a `loan_payment_transaction` with idempotency key K. Inside a test, directly invoke `LoanRepaymentCommandService.recordPaymentTransaction(..., idempotencyKey=K, ...)` with a *different* set of fields than the pre-seeded row, but in a way that the lookup misses (e.g. seed without flushing — Hibernate behaviour aside, the cleanest path is to use a SQL-level insert that the JPA session doesn't see). When `save()` then runs, it hits the unique constraint deterministically. Assert: no 500; the code path takes the recovery branch and either returns the existing row (if bodies match) or throws 409 (if not).
- This is the unit-style verifier for Slice 3's recovery logic. Slice 3 proves real concurrency works; Slice 4 proves the recovery branch is correct *and* reproducible without relying on the OS scheduler.
- GREEN: nothing new — same recovery branch as Slice 3.

**Slice 5 — Same race fix applied to `LspApiIdempotencyService.execute`**
- RED: Integration test against any LSP API endpoint that uses `LspApiIdempotencyService` (or a contrived test endpoint wrapping it). Fire 50 concurrent requests with the same key and body → assert all 50 return 200 with identical bodies; only one record persisted. Also test the mismatch branch: same key, different body → 409 `IDEMPOTENCY_CONFLICT` (this part already works in that service; the test locks it).
- GREEN: Apply the same catch-and-recover wrapper around the `lspApiIdempotencyRecordRepository.save(...)` call on line 60-67 of `LspApiIdempotencyService`. The fingerprint logic is already present (lines 48-56); just the race-recovery is missing.

**Slice 6 — Legacy rows (NULL fingerprint) treat new requests as "matching"**
- RED: SQL-insert a row directly into `loan_payment_transaction` with `request_fingerprint = NULL` (simulating a pre-migration payment). Hit the endpoint with the same key and any body → assert 200 with the legacy row, not 409. Locks the backward-compat behaviour so a future "tighten this" PR has to think about it explicitly.
- GREEN: in the fingerprint match logic from Slice 2, treat `existing.requestFingerprint == null` as a match. No new code needed if Slice 2's logic includes the null check.

**Order:** Slice 1 (baseline lock) → Slice 2 (fingerprint mismatch) → Slice 3 (concurrent race) → Slice 4 (deterministic recovery) → Slice 5 (LspApiIdempotency mirror) → Slice 6 (legacy rows). Slices 2 and 3 carry the migration + entity changes; subsequent slices reuse them.

**Anti-pattern check (per TDD philosophy):** all six slices exercise the real service or HTTP layer. None mock `LoanPaymentTransactionRepository` or `LspApiIdempotencyRecordRepository`. The concurrent test in Slice 3 is the only one that's a little expensive (sets up a real `ExecutorService`); flakiness is mitigated by Slice 4's deterministic recovery test which doesn't depend on thread interleaving.

#### Files touched (final list)

Add:
- `backend/src/main/resources/db/migration/V{n+1}__loan_payment_transaction_request_fingerprint.sql`
- `backend/src/test/java/com/bhawana/lms/service/LoanRepaymentCommandServiceIdempotencyTest.java`
- `backend/src/test/java/com/bhawana/lms/service/LspApiIdempotencyServiceRaceTest.java`

Edit:
- `backend/src/main/java/com/bhawana/lms/domain/LoanPaymentTransaction.java` — add `requestFingerprint` field + getter + overloaded constructor.
- `backend/src/main/java/com/bhawana/lms/service/LoanRepaymentCommandService.java` — add fingerprint helper; modify `recordPaymentTransaction` to compare fingerprint after lookup; wrap `save()` in `createInstallmentPayment` with catch-and-recover.
- `backend/src/main/java/com/bhawana/lms/service/LspApiIdempotencyService.java` — wrap the `save()` on lines 60-67 with the same catch-and-recover; reuse the existing fingerprint comparison.
- `backend/src/main/java/com/bhawana/lms/service/LoanServicingSupportService.java` — possibly add the shared fingerprint helper if it lives there; otherwise keep it private to the command service.

Untouched (deliberately):
- The unique index on `loan_payment_transaction.idempotency_key` — already exists and is doing its job (it's what catches the race in the first place).
- The HTTP-layer exception mapper that turns `ApiConflictException` into a 409 response — already wired.
- The actor / audit / webhook side-effects in `createInstallmentPayment` — they all sit *after* the `save()` call. The catch-and-recover path returns the existing row without re-running them, which is correct (we don't want duplicate audit rows or duplicate webhook events for the same idempotent operation).

#### Effect on app

- Partners doing legitimate network retries (same key, same body) always get the original payment back with a 200, even under perfect simultaneous-request timing.
- Partners doing something wrong (same key, different body) now get a clear 409 instead of silently receiving the old transaction. Their data-correctness bugs become visible.
- The other LSP API integration paths (`LspApiIdempotencyService`) get the same race-safety improvement as a free side effect.
- Backwards compatible: pre-migration payments (NULL fingerprint) keep accepting retries gracefully — no partner is suddenly blocked.
- Tiny per-write overhead (~1 SHA-256 hash + 64 bytes stored per payment row).
- No 500 traces leaking out for this race anymore.

#### Cluster impact

- **#94** ("[B-12] Foreclosure execute rejects when settlementDate != quote.effectiveDate exactly") — similar exact-match strictness pattern but unrelated to idempotency. Independent.
- **#96** ("[B-14] applyMockDisbursementOutcome has no Idempotency-Key support") — different endpoint, different vulnerability (no idempotency at all vs. broken idempotency). Independent. May benefit from the same `fingerprint()` helper if extracted to a shared service.
- **#128** ("[F-5] FE idempotency-key doesn't fingerprint body") — frontend twin of this bug. The FE auto-generates idempotency keys but doesn't change them when the body changes, so a user editing-then-retrying a form silently hits the original record. Once #86 lands, the FE will start getting 409s in that situation — which is the correct behaviour and forces the FE to handle it (mint a fresh key on body change). Cross-link in both PR descriptions.
- **#149** ("[AUD-3] API-client create/rotate/reveal moments not audited") — the catch-and-recover branch could optionally emit an audit row ("idempotency race recovered") for forensic visibility. Out of scope; flag for future.

---

### #87 — [B-5] WebhookOutboxService.dispatchPending holds batch TX during slow deliveries
**Labels:** bug, scale-risk · **Link:** https://github.com/sid12701/lms/issues/87 · **Status:** **CLOSED** — PR pending merge (2026-06-03)

> **Shipped (2026-06-03):** `V80__webhook_event_outbox_claim_expires_at.sql`; `WebhookEventOutboxStatus.IN_FLIGHT` + `claimExpiresAt` / `claim()`; Postgres `claimDispatchBatch` CTE marks `IN_FLIGHT` with expiry; H2 JPA claim path; `WebhookOutboxDispatchExecutor` (separate bean for real `@Transactional` claim + `deliverOne`); `WebhookDispatchConfig` `webhookDeliveryExecutor` (pool size `app.webhooks.delivery.thread-pool-size`, default 10); `WebhookOutboxService.dispatchPending` orchestrates claim then parallel per-row delivery; `LoanApplicationService` maps `IN_FLIGHT` → UI `PENDING`; tests `WebhookOutboxServiceDispatchTest`; shared `IntegrationTestDatabaseCleaner` for FK-safe H2 teardown; audit mock `PII_REVEAL` stream restored in `frontend-2` types.

**Problem (plain English):** The webhook worker claims a batch of 100 events and then delivers them one-by-one, all inside one transaction. If one partner is slow (or down), the whole transaction stays open and holds row locks. Under outage you can lock the connection pool.

**Possible fixes:**
1. **Two-phase: short claim TX (mark IN_FLIGHT), then deliver without TX** — solves the lock duration.
2. **Per-partner concurrency cap** — limits blast radius from one bad host.
3. **Async per-row** — most scalable; biggest refactor.

**Recommended:** Option 1 + Option 2. Together they cover both lock duration and per-partner isolation; Option 3 is a future state.

**Effect on app:** Outbox stays healthy under partner outages; connection pool not starved. No partner-visible change unless we currently miss SLAs from the lock contention.

**Detailed solution after discussion (2026-06-01):**

#### What's actually broken (plain English)

The webhook dispatcher works like this today: every so often, a scheduled job calls `dispatchPending(100)`. That method:

1. Opens a database transaction.
2. Claims up to 100 pending events (one DB query).
3. Walks through them one by one. For each event:
   - Builds the request.
   - Calls the partner's URL over HTTP (which can take up to ~10 seconds if the partner is slow or hanging).
   - Writes the result back.
4. Commits the whole thing at the end.

The problem: that HTTP call in step 3 happens **inside** the same database transaction as the claim and the writes. Postgres doesn't care that the JVM is just waiting for a network response — it sees a transaction that's been open the whole time, holding a database connection and any row locks the claim acquired.

Worst case: 100 events × 10 seconds = ~1000 seconds (~16 minutes). One bad batch can hold a connection out of the pool for nearly 16 minutes. A small number of bad partners can drain the connection pool and stall every other database operation the app is trying to do.

There's also a quieter issue: if the JVM dies (crash, kill, deploy) while the worker is mid-batch, the events it had claimed are stuck in IN_FLIGHT state forever because no other worker will look at them again — they're not PENDING anymore.

#### Decision (after grilling, 2026-06-01)

1. **Split the work into two phases.** Phase A is a tiny database transaction that claims a batch and marks the rows as IN_FLIGHT. It commits immediately. Phase B does the actual deliveries, outside of that transaction.
2. **Run deliveries in parallel on a bounded thread pool (default 10 threads).** Sequential delivery wouldn't satisfy the AC — a slow partner would still block every event behind it in the batch. With parallelism, a slow partner ties up one thread, and the other nine keep delivering to fast partners.
3. **Each individual delivery opens its own tiny database transaction** at the end to write the attempt row and update the event's status. The transaction's duration is now bounded by "one row write" (milliseconds), not "one HTTP call to a partner" (potentially seconds).
4. **Add a `claim_expires_at` column** for self-healing crash recovery. When a worker claims an event, it sets `claim_expires_at = now() + 5 minutes`. The claim query becomes `WHERE (status='PENDING' AND next_attempt_at <= now) OR (status='IN_FLIGHT' AND claim_expires_at < now)`. Any worker can re-claim a stale IN_FLIGHT event after 5 minutes. No separate sweeper job; the dispatcher itself handles recovery.
5. **Per-partner concurrency cap deferred to a follow-up issue.** If one LSP is slow, parallel threads will still all wait on that LSP simultaneously. A per-host cap (max 3 concurrent per LSP) would prevent one slow partner from saturating all 10 threads. We're betting that for now, most partners are healthy and the thread pool absorbs the occasional slow one. File a follow-up "Per-partner concurrency cap" issue; revisit when production tells us we need it.

#### TDD plan (vertical slices, behaviour through public interfaces)

Tests use real HTTP mock partners (e.g. `MockWebServer` or Spring's `MockMvc` against a Spring `WebClient`/`RestTemplate`) — no mocking of `WebhookDeliveryClient` or repositories. Behaviour is verified by the public state of the outbox rows.

**Slice 1 — Two-phase basic: claim is short, deliver completes, multiple deliveries happen in parallel**
- RED: Integration test. Stand up two mock partners (`partnerFast`, `partnerSlow`). `partnerSlow` sleeps 3 seconds before responding 200. `partnerFast` responds 200 in milliseconds. Submit 10 events: 5 to each. Call `dispatchPending(10)`. Within 1 second of the call returning, assert: all 5 `partnerFast` events have status DELIVERED. The 5 `partnerSlow` events are either IN_FLIGHT (still being delivered) or DELIVERED. This proves the deliveries happen in parallel — sequential delivery would have made `partnerFast` events wait 15 seconds (5 × 3s) behind the slow partner.
  
  Today this test fails: deliveries are sequential and inside one transaction, so the test times out or all 10 take >15s.
- GREEN:
  1. Migration `V{n+1}__webhook_event_outbox_claim_expires_at.sql`: `ALTER TABLE webhook_event_outbox ADD COLUMN claim_expires_at TIMESTAMP;` (nullable).
  2. `WebhookEventOutbox` entity: add `claimExpiresAt` field + `claim(Instant expiresAt)` method. The existing `markDelivered`/`markRetryableFailure`/`markPermanentFailure` methods clear `claim_expires_at` when transitioning out of IN_FLIGHT.
  3. `WebhookEventOutboxRepository.claimDispatchBatch(...)`: update the query to also pick up `IN_FLIGHT` rows whose `claim_expires_at < now`, and to set `claim_expires_at = now + 5 minutes` on claimed rows.
  4. `WebhookOutboxService.dispatchPending(batchSize)`:
     - Remove the method-level `@Transactional`.
     - Extract a new `@Transactional` private method `claimBatch(batchSize)` that runs the claim query, marks rows IN_FLIGHT with `claim_expires_at = now + 5 minutes`, and returns the claimed events. This is the short transaction.
     - In the main method body, submit each claimed event to a thread pool (`webhookDeliveryExecutor`). Each task calls a new `@Transactional` method `deliverOne(eventId)` that loads the event, calls the HTTP client, writes the attempt row, and updates the event status.
     - Wait for all submitted tasks to complete (or hit a batch-level timeout) before returning.
  5. New Spring `@Bean webhookDeliveryExecutor`: `ThreadPoolTaskExecutor` with `corePoolSize=10, maxPoolSize=10, queueCapacity=100`. Configurable via properties.
- Refactor: the new `deliverOne` method now owns the per-row transaction. The existing inline `webhookEventOutboxRepository.save(event)` calls in `dispatchEvent` are no longer needed — the `@Transactional` boundary writes the entity at commit time.

**Slice 2 — Crashed worker stranding recovery**
- RED: Manually insert a webhook event row with `status=IN_FLIGHT` and `claim_expires_at = now - 1 minute` (simulating a worker that crashed an hour ago after claiming the event). Call `dispatchPending(10)`. Assert: the stranded event was re-claimed and delivered (status=DELIVERED or RETRYABLE_FAILURE depending on the mock partner's response).
  
  Today this test fails: the event sits in IN_FLIGHT forever because the claim query only looks for `status='PENDING'`.
- GREEN: The repository query from Slice 1 already handles this (`OR (status='IN_FLIGHT' AND claim_expires_at < now)`). This slice locks the behaviour.

**Slice 3 — Connection pool stays bounded during partner outage**
- RED: Instrument HikariCP's `HikariDataSource` to expose `getHikariPoolMXBean().getActiveConnections()` during the test. One mock partner that sleeps 30 seconds before responding (simulates a hanging partner). Submit 100 events to that partner. Call `dispatchPending(100)`. While the call is running, sample active connections every 100ms. Assert: peak active connections ≤ 12 (10 delivery threads × 1 connection each + a couple for the scheduler/main thread). Today this test fails: the single batch transaction holds one connection for the entire 100 × 30s window, AND each `repository.save` inside that transaction may use additional pool slots.
- GREEN: same code as Slice 1; this slice asserts the side-effect on pool usage.

**Slice 4 — Successful delivery clears `claim_expires_at`**
- RED: Submit an event. Dispatch. Assert: after the call returns, the event has `status=DELIVERED` AND `claim_expires_at IS NULL`. This locks the entity-level invariant that any non-IN_FLIGHT row has no claim deadline (otherwise the claim query's `OR` branch could accidentally re-claim a delivered event).
- GREEN: ensure `WebhookEventOutbox.markDelivered(...)` (and the failure variants) sets `claim_expires_at = null`.

**Slice 5 — Retry-after-failure transitions cleanly**
- RED: One mock partner returns 500. Submit one event. Dispatch. Assert: status=PENDING (or whatever the existing retry state is), `next_attempt_at` is in the future per backoff, `claim_expires_at IS NULL`. A subsequent dispatch before `next_attempt_at` does NOT re-pick the event. After `next_attempt_at`, it gets re-claimed.
- GREEN: same `markRetryableFailure` adjustment from Slice 4; the claim query already filters on `next_attempt_at`.

**Order:** Slice 1 (the big refactor) → Slice 2 (crash recovery, same code different test) → Slice 3 (connection pool guard) → Slice 4 (claim-expires invariant) → Slice 5 (retry path).

**Anti-pattern check:** no mock of `WebhookOutboxService`, `WebhookEventOutboxRepository`, or `WebhookDeliveryClient`. Mock partners are real HTTP servers (a `MockWebServer` instance). Tests assert on observable state (DB rows, HikariCP metrics, response status from the dispatch summary) — survives any internal refactor.

#### Files touched (final list)

Add:
- `backend/src/main/resources/db/migration/V{n+1}__webhook_event_outbox_claim_expires_at.sql` (one ALTER, additive).
- `backend/src/test/java/com/bhawana/lms/service/WebhookOutboxServiceDispatchTest.java` (Slices 1, 2, 4, 5).
- `backend/src/test/java/com/bhawana/lms/service/WebhookOutboxServiceConnectionPoolTest.java` (Slice 3 — uses HikariCP MX bean).

Edit:
- `backend/src/main/java/com/bhawana/lms/domain/WebhookEventOutbox.java` — `claimExpiresAt` field + `claim(Instant)` method + clear it in `markDelivered`/`markRetryableFailure`/`markPermanentFailure`.
- `backend/src/main/java/com/bhawana/lms/repo/WebhookEventOutboxRepository.java` (or the impl `WebhookEventOutboxRepositoryImpl`) — update the `claimDispatchBatch` query to (a) match the new claim window and (b) set `claim_expires_at`.
- `backend/src/main/java/com/bhawana/lms/service/WebhookOutboxService.java` — restructure `dispatchPending`: remove method-level `@Transactional`; add `@Transactional` private `claimBatch(...)`; add `@Transactional` `deliverOne(eventId)`; submit deliveries to `webhookDeliveryExecutor` and await completion.
- `backend/src/main/java/com/bhawana/lms/config/...` (or wherever `@Bean` factories live) — add `webhookDeliveryExecutor` bean with bounded pool size.
- `backend/src/main/resources/application.yml` — add `app.webhook.dispatch.thread-pool-size: 10` (default).

Untouched (deliberately):
- `WebhookDeliveryClient` — the HTTP client itself is fine; only its caller changes.
- `WebhookOutboxDispatchWorker` (the scheduled trigger) — still calls `dispatchPending(N)`; the inner change is invisible to it.
- Backoff calculation — unchanged.
- Webhook signing / signature format — unchanged. Sits inside `dispatchEvent`, which becomes `deliverOne`'s inner work.
- Per-partner cap — deferred to a new follow-up issue ("Webhook dispatch: per-partner concurrency cap").

#### Effect on app

- One slow partner no longer blocks deliveries to other partners.
- Database connection pool usage stays bounded (max ~10 connections held by webhook dispatch at any moment, regardless of batch size or partner health).
- Worker crashes during a batch no longer strand events — within 5 minutes any other dispatcher picks them up.
- Throughput improves: 10 healthy partners' events can dispatch in parallel.
- No partner-visible behaviour change for the success case.
- For the failure case, retry timing is unchanged (backoff schedule applies as before).
- Operational note: a new thread pool exists; size is configurable via `app.webhook.dispatch.thread-pool-size`. Default 10 should be fine for current scale.

#### Cluster impact

- **#88** ("[B-6] enqueueIfSubscribed runs inside user-request critical path") — independent. That's about the enqueue side (writing events into the outbox during user requests), not the dispatch side. Could share future async infrastructure but doesn't need to bundle.
- **#130** ("[F-7] Webhook 404 classified as PERMANENT — silent loss on URL typos") — independent. Different code path (the `classify` function).
- **#103** ("[Q-6] webhook_event_outbox.payload_json still text — partial jsonb migration") — independent; schema cleanup of a different column.
- **#110** ("[Q-13] Webhook outbox index bloat") — adjacent; the new `claim_expires_at` column will need an index (`(status, next_attempt_at, claim_expires_at)` or similar) for the claim query to stay fast. Coordinate with #110 if it lands first.
- **New follow-up issue: "Webhook dispatch: per-partner concurrency cap"** — file once #87 lands. Specs: max N concurrent deliveries per LSP host, configurable per-partner override. Revisit when production telemetry shows a single LSP saturating the pool.

---

### #89 — [B-7] TenantDataAccessContextHolder defaults to ADMIN — silent cross-tenant leak risk in workers
**Labels:** bug, security, data-isolation · **Link:** https://github.com/sid12701/lms/issues/89 · **Status:** **CLOSED** — [PR #182](https://github.com/sid12701/lms/pull/182) merged 2026-06-07. Holder throws `MissingTenantContextException`; `TenantScopedExecution` + admin interceptor + worker tests. Closes **#146**.

**Problem (plain English):** If a background worker forgets to set tenant context, it silently runs as admin (reads/writes across all tenants). PG RLS won't save you when the connection enters admin mode. One forgotten worker = silent cross-LSP leak.

**Possible fixes:**
1. **Default to NONE → repo access throws** — forces opt-in; loud failure on regression.
2. **Default ADMIN but log a warning** — doesn't prevent the leak.
3. **Annotation-based context (`@RequireTenantContext`)** — declarative; needs AOP.

**Recommended:** Option 1. Fail fast on the regression that matters most.

**Effect on app:** Every background worker must explicitly call `useAdmin()` or `useTenant(lspId)`. Some may break the first time you flip the default; that is the point. Test-time check catches it before prod.

**Detailed solution after discussion (2026-06-01):**

#### Audit of linked surface area (done before deciding)

| Surface | Path | State |
|---|---|---|
| Holder | `backend/.../tenant/TenantDataAccessContextHolder.java:22` | `getMode()` returns `ADMIN` when ThreadLocal is null. `getCurrentLspId()` returns `null` when missing. Single `TenantContext` record holds `(mode, lspId)`. **This is the bug.** |
| JDBC routing | `backend/.../tenant/TenantRoutingDataSource.java:12` | Reads `getMode()` to choose tenant vs. admin DataSource. Inherits the implicit-ADMIN default with no guard. |
| LSP HTTP interceptor | `backend/.../web/LspTenantContextInterceptor.java` | Sets `useTenant(lspId)` on `/api/v1/lsp/**` (registered via `TenantIsolationWebConfig`); `clear()` in `afterCompletion`. **No symmetric admin interceptor.** |
| Admin HTTP controllers | `LoanApplicationOpsController`, `LoanApplicationAuditController`, all `web/*Ops*Controller.java` + auth controllers | None pass through any interceptor. All depend on implicit ADMIN today. |
| Save/restore callers | `BorrowerActiveLoanChecker.java:41-67`, `LspLoanApplicationApiController.java:194-250`, `OpsAlertService.java:70-88` | Each snapshots via `getMode()`+`getCurrentLspId()`, calls `useAdmin()`, then restores in `finally`. The snapshot call becomes unsafe once `getMode()` throws on missing context. |
| Scheduled worker (alerts) | `AlertRuleSchedulerWorker.evaluateScheduledAlertRules` → `AlertRuleEvaluationService.evaluateScheduledRules:74,88` | Inline `useAdmin()`. **Missing `clear()` in `finally`** — the worker thread stays in admin mode after the `@Scheduled` run, polluting the next task on the same pool thread. Latent bug independent of #89's flip. |
| Scheduled worker (webhooks) | `WebhookOutboxDispatchWorker.dispatchPendingEvents` | **No context call at all.** Relies entirely on implicit ADMIN. First DB hit after the flip will throw. |
| Scheduled worker (reports) | `ReportRequestProcessingWorker.processPendingRequests` | Same — no context call, depends on implicit ADMIN. |
| Mode enum | `TenantDataAccessMode` (Community 292 in graphify) | Currently `ADMIN | TENANT`. No `NONE` value; absence-of-context is the third state. |
| Tests | Any `@SpringBootTest` that wires a `@Repository` without going through the HTTP layer | Currently rely on implicit ADMIN. Subset will fail when default flips. |

**Conclusion of audit:** the one-line default flip in the holder is small, but the ripple lands in three places that **must move in the same PR** or the app is dead: admin HTTP routes (need a mirror interceptor), background workers (need a helper that owns the `clear()`), and the three existing save/restore call sites (need a non-throwing snapshot API). The `AlertRuleEvaluationService` missing-`clear()` is a free bonus fix the helper provides.

#### Decision (after grilling, 2026-06-01)

1. **Holder default flips to throw.** `getMode()` throws `MissingTenantContextException` when ThreadLocal is null. `getCurrentLspId()` likewise. The enum stays `ADMIN | TENANT` — no `NONE` value (absence is the signal, not a third mode).
2. **Snapshot API.** Add `TenantContext snapshot()` (returns the underlying record, nullable, never throws) and `void restore(TenantContext)` (re-installs via `clear()`-then-set, or `clear()` if null). The three existing save/restore call sites migrate to this API in the same PR.
3. **Admin HTTP interceptor.** Add `InternalAdminTenantContextInterceptor` mirroring `LspTenantContextInterceptor`: `preHandle` → `useAdmin()`; `afterCompletion` → `clear()`. Registered via `TenantIsolationWebConfig` against `/api/v1/internal/**` and `/api/v1/auth/**`. Authorization stays at `@PreAuthorize` on handlers — this interceptor only owns the data-access tag.
4. **Worker helper.** Add `tenant/TenantContext.java` with `runAsAdmin(Runnable)`, `runAsTenant(UUID, Runnable)`, `<T> callAsAdmin(Supplier<T>)`, `<T> callAsTenant(UUID, Supplier<T>)`. Each does `TenantContext prev = snapshot(); useX(); try { task.run(); } finally { restore(prev); }` so `clear()` is unforgettable. The three workers wrap their entry methods; `AlertRuleEvaluationService` migrates too (its missing-`clear()` is silently fixed).
5. **Tests.** Integration-style, public-interface only — see the TDD plan below.
6. **No NONE mode, no AOP, no startup scan.** Rejected: NONE adds a third state to a binary decision; AOP couples to proxy machinery; a literal `@Repository`-reachability scan is impractical in Spring.

#### TDD plan (vertical slices, one RED → GREEN at a time)

Each slice exercises a public surface (HTTP boundary, public method, or holder API). No mocking of internal collaborators. Each test name reads as a behavioural specification.

**Slice 1 — Holder throws on missing context (the canary)**
- RED: New test class `TenantDataAccessContextHolderTest`. Test `getModeThrowsWhenNoContextSet`: `TenantDataAccessContextHolder.clear()`; assert `assertThrows(MissingTenantContextException.class, TenantDataAccessContextHolder::getMode)`. Add the `MissingTenantContextException` class as part of the RED so the test compiles. Test fails — current impl returns `ADMIN`.
- GREEN: change `getMode()` and `getCurrentLspId()` to throw on null. Add `snapshot()` (returns inner `TenantContext` record, nullable) and `restore(TenantContext)` (sets or clears). Make the inner record package-private (or move it to `TenantContext.java` from Slice 4). Test passes.
- Refactor: extract the null-guard into a private helper if the two getters drift.

**Slice 2 — The 3 save/restore call sites use snapshot/restore**
- RED: for each of `BorrowerActiveLoanChecker`, `LspLoanApplicationApiController` (the borrower-PII path), and `OpsAlertService`, add a test that invokes the affected method from a thread with empty context and asserts `snapshot()` is null both before and after (no leak). Existing call-path tests must still pass. Tests fail because the current code calls `getMode()` to snapshot, which now throws.
- GREEN: in each call site, replace the snapshot trio (`getMode()` + `getCurrentLspId()` + `useAdmin()`) with `TenantContext prev = snapshot(); useAdmin();` and replace the `finally` branch logic with a single `restore(prev);`. Tests pass.
- Refactor: none — the 3-line idiom is now consistent across the three call sites; future reviewers can spot a missing `restore(prev)` by shape.

**Slice 3 — Admin HTTP interceptor wires `/api/v1/internal/**` and `/api/v1/auth/**`**
- RED: `InternalAdminTenantContextInterceptorIntegrationTest` (`@SpringBootTest`, `MockMvc` or `TestRestTemplate`). Picks one read endpoint per path root (e.g. `GET /api/v1/internal/ops/loan-applications?page=0&size=1` and `GET /api/v1/auth/me`). Asserts 200 OK. Asserts `TenantDataAccessContextHolder.snapshot()` is null on the test thread after each request (interceptor cleaned up). Tests fail — handler 500s because no interceptor sets admin and `getMode()` throws.
- GREEN: add `InternalAdminTenantContextInterceptor` (mirror of `LspTenantContextInterceptor`). Register in `TenantIsolationWebConfig.addInterceptors`: `.addInterceptor(internalAdminTenantContextInterceptor).addPathPatterns("/api/v1/internal/**", "/api/v1/auth/**")`. Tests pass.
- Refactor: none — interceptor is the simplest possible mirror.

**Slice 4 — Worker helper + AlertRule worker migrates**
- RED: `AlertRuleSchedulerWorkerTenantContextTest` (`@SpringBootTest`). On a thread with empty context (assert `snapshot()` is null first), invoke `alertRuleSchedulerWorker.evaluateScheduledAlertRules()` directly. Assert: (a) call completes without throwing, (b) `snapshot()` is null on the same thread afterwards. Fails because `evaluateScheduledRules` lines 74 & 88 set admin without a matching `clear()` — assertion (b) red.
- GREEN: add `tenant/TenantContext.java` with `runAsAdmin(Runnable)`, `runAsTenant(UUID, Runnable)`, `<T> callAsAdmin(Supplier<T>)`, `<T> callAsTenant(UUID, Supplier<T>)`. Each method captures `snapshot()`, calls the appropriate `useX()`, runs the task, restores in `finally`. Migrate `AlertRuleEvaluationService.evaluateScheduledRules` and `evaluateRule` to use `callAsAdmin`. Test passes.
- Refactor: confirm helper signatures cover the Runnable/Supplier shapes the codebase actually uses; no checked-exception overload until a caller needs one.

**Slice 5 — Webhook worker wrapped**
- RED: `WebhookOutboxDispatchWorkerTenantContextTest` (`@SpringBootTest`). Empty context, invoke `dispatchPendingEvents()`. Fails — `webhookOutboxService.dispatchPending` hits a repo and the holder throws.
- GREEN: change `dispatchPendingEvents` to `var summary = TenantContext.callAsAdmin(() -> webhookOutboxService.dispatchPending(batchSize));` and use `summary` for the log line. Test passes.
- Refactor: none.

**Slice 6 — Report worker wrapped**
- RED: same shape as Slice 5 for `ReportRequestProcessingWorker.processPendingRequests`. Fails on first DB hit.
- GREEN: wrap in `callAsAdmin`. Test passes.

**Slice 7 — Regression canary: a thread that touches the DB without setting context throws**
- RED: `TenantContextRegressionTest` (`@SpringBootTest`). On a fresh thread with `TenantDataAccessContextHolder.clear()` called, autowire any `@Repository` (e.g. `loanApplicationRepository`) and call `count()`. Assert `MissingTenantContextException` is in the thrown exception's cause chain. May already pass if `getMode()` throws cleanly through the routing layer; otherwise fails because the exception is wrapped as a `DataAccessResourceFailureException`.
- GREEN: if Slice 1's exception is swallowed by Spring's translation, catch-and-rethrow inside `TenantRoutingDataSource.determineCurrentLookupKey` so the cause chain contains `MissingTenantContextException`. Test passes.
- Refactor: none. This slice is the "we actually proved it" capstone.

**Order matters:** Slice 1 must land first (every later slice assumes the throw). Slices 1 + 2 + 3 must land in the same commit/PR — without all three the app is dead on HTTP and the three save/restore methods crash. Slices 4–6 are independent within the PR. Slice 7 is the capstone.

#### Files touched (final list)

Add:
- `backend/src/main/java/com/bhawana/lms/tenant/MissingTenantContextException.java`
- `backend/src/main/java/com/bhawana/lms/tenant/TenantContext.java` (helpers: `runAsAdmin`, `runAsTenant`, `callAsAdmin`, `callAsTenant`)
- `backend/src/main/java/com/bhawana/lms/web/InternalAdminTenantContextInterceptor.java`
- `backend/src/test/java/com/bhawana/lms/tenant/TenantDataAccessContextHolderTest.java`
- `backend/src/test/java/com/bhawana/lms/tenant/TenantContextRegressionTest.java`
- `backend/src/test/java/com/bhawana/lms/web/InternalAdminTenantContextInterceptorIntegrationTest.java`
- `backend/src/test/java/com/bhawana/lms/service/AlertRuleSchedulerWorkerTenantContextTest.java`
- `backend/src/test/java/com/bhawana/lms/service/WebhookOutboxDispatchWorkerTenantContextTest.java`
- `backend/src/test/java/com/bhawana/lms/service/ReportRequestProcessingWorkerTenantContextTest.java`

Edit:
- `backend/.../tenant/TenantDataAccessContextHolder.java` — `getMode()`/`getCurrentLspId()` throw on null; add `snapshot()`/`restore(TenantContext)`; expose the `TenantContext` record (package-private or moved alongside helpers).
- `backend/.../config/TenantIsolationWebConfig.java` — register `InternalAdminTenantContextInterceptor` on `/api/v1/internal/**` + `/api/v1/auth/**`.
- `backend/.../service/AlertRuleEvaluationService.java` — replace inline `useAdmin()` (lines 74, 88; missing `clear()` bug) with `callAsAdmin`.
- `backend/.../service/WebhookOutboxDispatchWorker.java` — wrap entry method in `callAsAdmin`.
- `backend/.../service/ReportRequestProcessingWorker.java` — same.
- `backend/.../service/BorrowerActiveLoanChecker.java` — migrate two save/restore sites (lines 41–67) to `snapshot()` + `restore(snapshot)`.
- `backend/.../web/LspLoanApplicationApiController.java` — migrate save/restore (lines 194–250) to `snapshot()` + `restore(snapshot)`.
- `backend/.../service/OpsAlertService.java` — migrate save/restore (lines 70–88).
- `backend/.../tenant/TenantRoutingDataSource.java` — confirm `MissingTenantContextException` bubbles through the JDBC translation; explicit catch-and-rethrow if Slice 7 RED reveals wrapping.
- Any `@SpringBootTest` that wires a `@Repository` directly without HTTP — discovered via Slice 7; either rewrap the setup in `callAsAdmin` or convert to controller-level test.

Untouched (deliberately):
- `LspTenantContextInterceptor.java` — already correct.
- Postgres RLS policies — out of scope; data-isolation cluster owns them. This fix makes the holder honest about whether RLS is even reachable.
- `TenantDataAccessMode` enum — stays `ADMIN | TENANT` (no `NONE`).

#### Effect on app (revised)

- **Runtime:** no functional change if everything is wired correctly. If a worker or test was relying on implicit ADMIN, it fails loudly on first DB call — exactly the property the issue asks for.
- **Code:** every entry point is self-documenting about scope. A reviewer can answer "what scope is this method running in?" by reading the first three lines of the method.
- **Future workers:** writing a new `@Scheduled` / `@RabbitListener` / `@Async` method without a `runAsX` wrapper fails the per-worker integration test pattern at PR time.
- **AlertRuleEvaluationService bug fix:** the missing `clear()` (currently leaves the worker thread in admin mode after the `@Scheduled` run, polluting the next task on the same pool thread) is silently fixed by Slice 4.
- **Risk accepted:** `@Async` / `CompletableFuture` patterns that capture work for later execution must call `runAsAdmin(...)` **inside** the captured Runnable, not outside — ThreadLocal doesn't follow the task. Documented in the helper's Javadoc.

#### Cluster impact / sequencing

- **#145** (duplicate, per the "Duplicate of #89" entry around line 2056) closes on the same PR.
- **#147** (bundle-with-#71) is independent — different ThreadLocal-style infra, no sequencing.
- **#90 / RLS policies** (data-isolation cluster) is unblocked but not changed by #89; RLS remains the second layer.
- **#88** (enqueueIfSubscribed in user-request path) is unrelated.
- Reviewer note: the PR description must call out "default flip + 3 ripple migrations, all in one commit set" so reviewers understand why so many files move at once. Slice 7 is the receipt.

---

### #93 — [B-11] ApiClientAuthenticationService.lookupByClientId doesn't check ACTIVE
**Labels:** bug, security, rbac · **Link:** https://github.com/sid12701/lms/issues/93

**Problem (plain English):** When a client refreshes, we look the client up by ID but don't check whether it's still ACTIVE. A disabled client refreshes forever as long as it has a valid refresh token.

**Possible fixes / Recommended / Effect:** Duplicate of #79. Close together.

**Detailed solution after discussion (2026-06-01):** Close as duplicate of **#79**. The `lookupByClientId` ACTIVE-check is the same enforcement gap that #79's solution addressed end-to-end (`ApiClientStatus.ACTIVE` checked at token issue + refresh, token-version invalidated on disable). No separate work; this issue closes when #79's PR merges.

---

### #94 — [B-12] Foreclosure execute rejects when settlementDate != quote.effectiveDate exactly
**Labels:** bug, fragile-logic · **Link:** https://github.com/sid12701/lms/issues/94

**Problem (plain English):** Real foreclosure settlements slip by a day or three (bank holidays, transfer delays). The API requires the settlement date to equal the quote's effective date exactly. Off-by-a-day fails with a 400 and the LSP must re-quote.

**Possible fixes:**
1. **Tolerance window with interest recompute** — accept settlement within ±N days; recompute outstanding from real date.
2. **Allow only future slip (≤N days), reject past dates** — covers the common case; cleaner semantics.
3. **Force re-quote always** — current behavior; keep but improve error message.

**Recommended:** Option 2. Foreclosure date being earlier than quote-effective doesn't make economic sense; later does, with recompute.

**Effect on app:** LSPs successfully close loans on the day money actually arrives. Reduces re-quote churn. Interest accrual recompute needs careful test.

**Detailed solution after discussion (2026-06-01) — DEFERRED, ISSUE STAYS OPEN:**

**Decision:** Leave the strict `settlementDate.equals(quote.getEffectiveDate())` check in `LoanForeclosureCommandService.executeForeclosureQuote` (line 159) unchanged for now. No code change in this pass. The options below are logged as the candidate solutions to revisit when foreclosure becomes a partner-facing pain point (likely as soon as real LSP integrations go live).

**Audit findings worth preserving for the future fix (so the next person doesn't re-derive them):**

1. **The strict check isn't protecting any financial truth in this codebase.** Interest is per-installment, not per-day. `requestForeclosureQuote` (lines 71–80) sums "unpaid portion of already-scheduled installment principal + interest." There is no daily accrual. So settling N days after the quote's `effectiveDate` does not shortchange the lender's books — `quote.getSettlementAmount()` is still the right number. The current 400 is a process gate, not a money gate.
2. **`settlementAmount` is fully frozen on the quote** (line 170 reads `quote.getSettlementAmount()` — settlement is never recomputed at execute time). So audit-doc Option 1's "interest recompute" is moot unless we change the schedule model to per-diem accrual elsewhere.
3. **`allInstallmentsSettled` (line 181) only checks schedule-installment closure**, which depends on the saved `settlementAmount`, not on the calendar date. Loosening the date check therefore does not destabilise the closure invariant.
4. **There is no zombie-quote protection today.** An ACTIVE quote can sit forever until a new quote supersedes it (lines 89–94). If/when the date check is loosened, a TTL or staleness gate is needed to prevent a months-old quote from being executed for an amount that no longer reflects current state.
5. **The existing happy-path test** (`LoanApplicationOpsControllerTest:905–910` and `:1061–1066`) sends `settlementDate == effectiveDate`. It survives any forward-slip relaxation unchanged — only new tests would need to be added.
6. **Audit row gap noted in passing** (not the primary subject of #94, but discoverable here): the audit note (lines 199–204) and the `LOAN_FORECLOSURE_COMPLETED` webhook payload (line 214) capture `quote.getEffectiveDate()` but not the actual `settlementDate`. The day-of-settlement is currently inferable only from the payment-transaction row. When the fix lands, add `settlementDate` and slip-in-days to both surfaces.

**Candidate fixes on the table for the eventual implementation pass:**

- **Option A — Forward-only window `[effectiveDate, effectiveDate + N]`.** Past-date settlements rejected with a clear error code (`FORECLOSURE_SETTLEMENT_PRE_EFFECTIVE`). Default N = 7 calendar days (covers a long weekend + a bank-holiday cluster). Configurable via `lms.foreclosure.settlement-window-days`.
- **Option B — Open-ended forward (`settlementDate >= effectiveDate`).** Strictly worse than A unless paired with a quote-age TTL, because nothing stops a six-month-old quote from being honoured.
- **Option C — Two-sided window `[effectiveDate − M, effectiveDate + N]`.** Allows the (rare, operationally weird) case of settling slightly before the quote takes effect. Probably not worth the conceptual cost.
- **Option D — Drop the date check entirely; rely on quote status + age TTL.** Cleanest model long-term, but requires introducing a TTL field on `LoanForeclosureQuote` and a migration.
- **Cross-cutting requirement that any of A/B/C/D should ship with:** a quote-age TTL so an ACTIVE quote whose `effectiveDate` is older than N days is rejected at execute with `FORECLOSURE_QUOTE_EXPIRED`. Without this, loosening the date check creates the zombie-quote risk noted above.

**Recommended starting point for the eventual fix:** Option A + a quote TTL of the same N (defaulting to 7). It preserves `effectiveDate` as the audit-trail anchor, accepts the realistic "money landed a few days late" case, and plugs the zombie-quote hole in the same PR.

**TDD outline for the eventual implementation (not executed now — captured for the next person):**
- TRACER: foreclosure with `settlementDate = effectiveDate + 3` succeeds; payment row written; loan closes.
- `foreclosure_with_settlement_date_before_effective_rejects_with_pre_effective_code`.
- `foreclosure_with_settlement_date_beyond_window_rejects_with_window_exceeded_code` (parameterised on the configured N).
- `foreclosure_with_active_quote_whose_effective_date_is_older_than_TTL_rejects_with_expired_code`.
- `audit_row_and_webhook_payload_record_actual_settlementDate_and_slip_days`.
- All tests integration-style through the ops + LSP controllers — no internal collaborator mocking (boundary-only: clock for deterministic dates).

**Why we are deferring:** the strict check is annoying but pre-launch there are no real LSPs hitting it. Loosening it incorrectly is more dangerous than leaving it strict (zombie-quote risk; webhook contract change for `LOAN_FORECLOSURE_COMPLETED`). When real partners exist and either (a) re-quote churn is observed in production or (b) a partner specifically asks for tolerance, this issue moves to the top of the queue with the design above as the starting point.

**Dependencies / sequencing:** depends on a confirmed `lms.foreclosure.settlement-window-days` config key + the audit-payload addition. Bundled with **#124** (same root cause; same fix closes both).

---

### #98 — [Q-1] God classes (1540/1405/796 LoC)
**Labels:** code-quality · **Link:** https://github.com/sid12701/lms/issues/98

**Problem (plain English):** Three classes dominate the service/web layers. Each is ~1000+ lines and touches a dozen workflows. A one-line edit reviews like a wide-blast-radius change.

**Possible fixes:**
1. **Decompose along workflow seams** (intake / approval / disbursement / repayment / foreclosure / audit) — clean SRP split.
2. **Extract only the new code; let god classes wither** — pragmatic but slow.
3. **Leave them; just add comments and section markers** — cosmetic, no real benefit.

**Recommended:** Option 1 done in slices: pick one workflow per PR, move methods, update callers. Spread across 2–3 sprints.

**Effect on app:** Smaller PRs, smaller blast radius, faster code reviews. Tests run in isolation. No runtime effect.

**Detailed solution after discussion:** _(pending)_

---

### #107 — [Q-10] Local seed/bootstrap services may be reachable outside local profile
**Labels:** code-quality, security, verification · **Link:** https://github.com/sid12701/lms/issues/107

**Problem (plain English):** `LocalDemoPortfolioSeedService` and `LocalBootstrapAdminSyncService` exist in the main classpath. If their `@Profile("local")` annotation is missing or misnamed, they'll run in prod — creating demo data or weak bootstrap admins.

**Possible fixes:**
1. **Verify annotation present; add a startup test asserting absence in prod context** — quickest + repeatable.
2. **Move classes to a `local/` sub-package + maven profile that excludes from prod build** — strongest; needs build changes.
3. **Inline-disable on prod startup via boolean check** — fragile.

**Recommended:** Option 1 now (low effort, high value), Option 2 as a follow-up if the team wants extra assurance.

**Effect on app:** No runtime change unless the annotation is actually missing. If it is, your prod has demo data right now and we need to clean it up.

**Detailed solution after discussion (2026-06-02 follow-up audit):**

**Verification complete — partial intentional exception found.**

| Class | Annotation | Status |
|---|---|---|
| `LocalDemoPortfolioSeedService` (line 32) | `@Profile("local")` | ✅ correctly gated |
| `SampleCatalogSeedService` (line 23) | `@Profile({"local", "test"})` | ✅ correctly gated |
| `LocalBootstrapAdminSyncService` (line 25) | **no `@Profile`** | ⚠️ **intentional** — see comment at lines 20-23: "F-19: refresh_token rows FK to app_user, so the configured bootstrap admin must exist as a real app_user row in every profile (not just 'local'). Demo-portfolio seeding stays guarded by the `app.seed.demo-portfolio.enabled` property so production profiles do not seed sample data." The "Local" prefix is misleading but the behaviour is intentional. The bootstrap admin's password comes from `${APP_SECURITY_BOOTSTRAP_PASSWORD}` env var (not weak/hardcoded), so prod is safe. |

**Recommended close:** rename `LocalBootstrapAdminSyncService` → `BootstrapAdminSyncService` (remove the misleading "Local" prefix) and add a brief class-level Javadoc pointing at the F-19 reasoning. Then close #107 and #161 as verified.

---

### #111 — [Q-14] Tests run against FE mock-router; mock-driven greenness ≠ backend correctness
**Labels:** code-quality · **Link:** https://github.com/sid12701/lms/issues/111

**Problem (plain English):** Most FE tests validate the FE's own mock router rather than the real backend. CI shows 1000+ green tests and team feels safe — but the green tests don't tell us if the backend is actually right.

**Possible fixes:**
1. **Expand Playwright suite + dock-compose backed E2E** — costly but real.
2. **Generate FE mock from OpenAPI contract** — keeps mocks honest at least.
3. **Both** — most thorough.

**Recommended:** Option 3, sequenced: contract-generated mocks first (cheap), Playwright suite second (effort).

**Effect on app:** CI takes longer; signal quality goes way up. Bugs that today survive to staging get caught at PR time.

**Detailed solution after discussion:** _(pending)_

---

### #123 — [D-10] MIS preview vs CSV download paths use overlapping projections — drift risk
**Labels:** duplicate-code, reporting-risk · **Link:** https://github.com/sid12701/lms/issues/123 · **Status:** **CLOSED — DEFERRED duplicate of #69 cluster** (2026-06-06)

**Problem (plain English):** The preview screen and the CSV download read from overlapping but not identical projections. Drift is a question of "when," not "if" — one will silently lose a field the other still shows.

**Possible fixes:**
1. **One projection, two formatters (HTML/CSV)** — guarantees parity; small refactor.
2. **Parity test that compares row-by-row** — catches drift but doesn't prevent it.
3. **Both** — strongest.

**Recommended:** Option 1 with the parity test as a safety net. Combined with #69 masking work.

**Effect on app:** What you see is what you get (under masking rules). One projection means one place to change a column.

**Detailed solution after discussion:** _(pending)_

---

### #124 — [F-1] Foreclosure settlement date must equal quote effectiveDate exactly (dup of #94)
**Link:** https://github.com/sid12701/lms/issues/124

**Problem / Fixes / Recommendation / Effect:** Duplicate of #94 under the fragile-logic taxonomy. Close together.

**Detailed solution after discussion (2026-06-01) — DEFERRED, ISSUE STAYS OPEN:** Bundled with **#94**. See #94 for the full audit, candidate options (A–D), recommended starting point (forward-only window + quote TTL, default 7 days), TDD outline, and the reason for deferral. This issue closes when #94 closes.

---

### #125 — [F-2] Bank-detail match too strict — fails on whitespace/punctuation/unicode
**Labels:** fragile-logic · **Link:** https://github.com/sid12701/lms/issues/125 · **Status:** **CLOSED** — PR #183 (2026-06-07). `BankAccountHolderNameMatcher` (NFKD/whitespace/punctuation normalisation; honorific-only soft warn; initial expansion hard reject), pre-flight `LspBankDetailsCheckResponse`, worker holder-name checks, V90 `soft` column, `HOLDER_NAME_SOFT_MISMATCH` alert. Tests: `BankAccountHolderNameMatcherTest`, `Issue125BankDetailHolderNameMatchIntegrationTest`.

**Problem (plain English):** Bank-account name match compares trimmed/upper-cased strings. Real bank records have "MR. JOHN K" vs the system's "John K." vs the LSP's "John Kumar". Any of these → 422 and the disbursement fails.

**Possible fixes:**
1. **`BankDetailMatcher` with normalization (NFKD, collapse spaces, strip punctuation, drop honorifics)** — handles 90% of real-world variants.
2. **Fuzzy match with a similarity threshold** — broader but adds false-accept risk.
3. **Manual override path with second-actor approval** — workflow patch; doesn't fix the matcher.

**Recommended:** Option 1 + a tight similarity check on the residue (e.g., Levenshtein ≤ 1 after normalization). Option 3 as a relief valve for genuine outliers.

**Effect on app:** Drop in disbursement-validation rejections. Documented matching rules in the runbook. Minimal false-accept risk if thresholds tuned.

**Detailed solution after discussion (2026-06-07):**

**Reframe — only the holder-name field is fragile.** Code read of `LoanDisbursementService.java:112-173` confirms:
- Account number normaliser strips all non-digits (`replaceAll("\\D", "")`) — already lenient; handles spaces, dashes, masked prefixes. Fine.
- IFSC normaliser is `trim().toUpperCase(Locale.ROOT)` — IFSC is a fixed 11-char format. Fine.
- Holder-name normaliser is the same `trim().toUpperCase()` — **this is the only brittle surface**.

Real-world holder-name failure modes the current matcher rejects but should not: honorifics, internal-whitespace divergence, punctuation, Unicode composition form, diacritics. Failure modes we deliberately keep rejecting: initial expansion (`JOHN K` vs `JOHN KUMAR`) and word reorder (`KUMAR, JOHN` vs `JOHN KUMAR`) — both are real-world ambiguous and the relief valve (`PATCH /borrowers/{id}/bank-details` from #62 PR(c)) handles them with audit + webhook + velocity alert.

**Policy moved from strict-reject to soft-warn on both LSP-facing surfaces.** Bank-side credit-to-name is the real gate at money-out. LMS layer becomes informational for holder-name only. Account number and IFSC stay strict and hard-fail — they remain blocking violations on both the pre-flight endpoint and the worker.

**Decisions locked in:**
1. **New `BankAccountHolderNameMatcher` as a Spring `@Component` in `com.bhawana.lms.service`.** Pipeline: NFKD → strip diacritics → strip `.`/`,`/`'`/`-` → collapse internal whitespace → trim → uppercase `Locale.ROOT` → exact-equal. **No honorific list** (deferred until real data demands it; no governance cost today). **No Levenshtein** (deferred; deterministic-only at v1). **Forward-only** (compare-time; `Borrower.accountHolderName` storage untouched, no migration backfill).
2. **`LoanDisbursementService.validateDisbursementBankDetails` split.** Holder-name no longer adds to the strict `violations` map. Method returns a record `{ violations, warnings }`. Account/IFSC still populate `violations` (hard); holder-name divergence populates `warnings` (soft).
3. **`verifyDisbursementBankDetailsForLsp` returns a new DTO.** Signature changes from `void` to `BankDetailsCheckResult { status: "OK" | "WARN", warnings: [{ field, code, message }] }`. Hard violations (account/IFSC) still throw `BusinessRuleViolationException` → 422 (controller unchanged for that path). Warnings surface only on the 200 path.
4. **`LspLoanApplicationApiController.verifyDisbursementBankDetails` return shape changes.** New `LspBankDetailsCheckResponse` record. Pre-launch — no LSP partner comms needed. Route stays `POST /api/v1/lsp/loan-applications/{applicationId}/disbursement-bank-check`.
5. **Worker (`validateAutomatedDisbursement` → caller in `LoanDisbursementWorker`) adds a soft holder-name check, run BEFORE the adapter call.** On soft mismatch: write `LoanDisbursementBankMismatchLog` row with new `soft=true`, fire `HOLDER_NAME_SOFT_MISMATCH` alert via `AlertRuleEvaluationService`, **then disburse**. Account/IFSC mismatches still halt the worker (they're prevented upstream by the bank-detail update flow anyway, but the last-line check stays).
6. **`LoanDisbursementBankMismatchLog` schema change.** New column `soft BOOLEAN NOT NULL DEFAULT FALSE`. Flyway migration `V<next>__bank_mismatch_log_soft_column.sql`. Existing rows default to `false` (they are all hard mismatches by construction).
7. **`AlertRuleEvaluationService.emitHolderNameSoftMismatch(application, submittedName, onFileName, correlationId)`** — new method, new action label `HOLDER_NAME_SOFT_MISMATCH`. Per-event, no threshold. Distinct from existing `BANK_DETAIL_MISMATCH` (which fires on threshold of hard account/IFSC mismatches and stays unchanged).
8. **`BorrowerBankDetailsService.recordDisbursementBankMismatch` split** into `recordHardMismatch` (existing semantics, `soft=false`, threshold alert) and `recordSoftHolderMismatch` (`soft=true`, per-event alert, no threshold). Worker calls the soft path; existing strict-validation callers stay on the hard path.

**What is NOT changing:**
- `Borrower.accountHolderName` storage — original spelling preserved for audit/diff evidence.
- Account number normaliser (`\\D` strip) and IFSC normaliser (`trim().toUpperCase()`) — both already correct.
- The `PATCH /borrowers/{id}/bank-details` relief valve from #62 PR(c) — still the way to correct genuinely-wrong on-file data. Audit + webhook + velocity alert remain.
- Existing strict `BANK_DETAIL_MISMATCH` threshold alert — untouched.
- No data backfill; no canonical-form column on `Borrower`.
- No per-LSP strictness dial (audit-doc Option D was explicitly rejected — YAGNI pre-launch).

**Why not the original audit-doc recommendation (Option 1 + Levenshtein ≤ 1):** Levenshtein on the normalised residue introduces a false-accept surface whose blast radius is "wrong account credited". We do not buy that risk speculatively. The honorific list also requires governance (which culture, which suffixes, which feminine/masculine variants). Both can layer on later when real-world rejection data exists to tune against. v1 is deterministic-only.

**Why not Option E (drop holder-name match entirely):** even though the destination bank does its own credit-to-name check, removing our layer means the only evidence of "what the LSP submitted at disbursement time" lives in the bank's response. We lose the `LoanDisbursementBankMismatchLog` forensic signal. Worth re-evaluating once a real disbursement adapter lands (#61 mock-by-design), not now.

**Why not Option A (status quo + bigger relief valve):** false-reject friction on every disbursement where the holder name differs by punctuation/whitespace would drive the strict `BANK_DETAIL_MISMATCH` alert noise high and degrade the alert stream's signal-to-noise — exactly the channel #62 PR(c) shipped for LSP-misbehaviour detection. Underengineered.

**TDD plan (vertical slices, behaviour through public interfaces):**

Tracer bullet first, then each subsequent test responds to what the previous revealed. No horizontal slicing.

1. **TRACER — `preflight_returns_OK_with_no_warnings_when_holder_name_matches_exactly`.** POST `/disbursement-bank-check` with identical holder name → 200, body `{status:"OK", warnings:[]}`. Locks in the new response shape through the public API.
2. **`preflight_returns_WARN_with_holder_name_warning_when_only_holder_name_differs_by_punctuation`.** Submit `JOHN K.` vs on-file `JOHN K` → 200, warnings contain a `HOLDER_NAME_SOFT_MISMATCH` entry.
3. **`preflight_returns_422_when_account_number_mismatches_regardless_of_holder_name`.** Account differs (holder identical) → 422, violations body. Locks in: holder-name softness does not weaken account/IFSC strictness.
4. **`preflight_returns_OK_when_holder_name_differs_only_by_internal_whitespace`.** `JOHN  KUMAR` (double space) vs `JOHN KUMAR` → 200, no warning (normaliser collapses).
5. **`preflight_returns_OK_when_holder_name_differs_only_by_unicode_form`.** NFC vs NFD `JÖHN` → 200, no warning.
6. **`worker_disburses_application_when_holder_name_soft_mismatches`.** Seed `APPROVED_PENDING_DISBURSAL` with holder-name divergence → worker calls adapter → status moves to `DISBURSEMENT_REQUESTED`. `LoanDisbursementBankMismatchLog` row written with `soft=true`. `HOLDER_NAME_SOFT_MISMATCH` alert surfaces in Audit Explorer via the public read API.
7. **`worker_does_not_disburse_when_account_number_mismatches`.** Account number differs → worker halts; status unchanged; hard mismatch log row with `soft=false`.
8. **`holder_name_soft_mismatch_does_not_trigger_strict_BANK_DETAIL_MISMATCH_threshold`.** Repeated soft mismatches in the velocity window → no strict threshold alert. Keeps the two signals independent.

Matcher unit tests (one slice, runs last so the pipeline can be refactored under the integration tests): `matches_canonical_form`, `strips_diacritics_NFKD`, `collapses_internal_whitespace`, `strips_dot_comma_apostrophe_hyphen`, `case_insensitive_locale_root`, `rejects_genuinely_different_names`, `rejects_initial_expansion_JOHN_K_vs_JOHN_KUMAR`, `rejects_word_reorder_KUMAR_comma_JOHN_vs_JOHN_KUMAR`. The last two are explicit non-matches, locking in the deliberate non-fuzz boundary.

**Mocking discipline:**
- `LoanDisbursementAdapter` — system boundary; mockable in worker tests (a fake adapter or Mockito spy at the boundary).
- `AlertRuleEvaluationService` — internal collaborator, **not** mocked. Tests assert alerts through the Audit Explorer query API (`GET /api/v1/internal/admin/audit-events`) — same surface an ops user sees.
- `BankAccountHolderNameMatcher` — internal pure logic, **never** mocked. Real instances run in every service-level test.
- No mocks of repositories, the borrower domain, `LoanApplicationLifecycleService`, or the worker's status transitioner.
- `Clock` — system boundary, mockable for the velocity-window assertion (`holder_name_soft_mismatch_does_not_trigger_strict_BANK_DETAIL_MISMATCH_threshold`).

**Tests we deliberately do NOT write here (owned elsewhere):**
- Pre-flight rate limit → owned by #81 (closed).
- Pre-flight audit row → if/when audit pipeline grows to cover bank-check, file follow-up; not in scope here.
- LSP IP allowlist on `/disbursement-bank-check` → owned by #64 (closed).
- Webhook signing format on `BORROWER_BANK_DETAILS_UPDATED` (the relief-valve event) → #129.

**Effect on app:**
- Pre-flight gains a JSON body (LSP-visible contract change; pre-launch, free).
- Worker stops false-rejecting disbursements on holder-name punctuation, internal whitespace, Unicode form, diacritics.
- New `HOLDER_NAME_SOFT_MISMATCH` audit signal — distinct from the strict-threshold path, so ops can filter and tune separately.
- Original holder name preserved on `Borrower` rows; no data migration.
- `BANK_DETAIL_MISMATCH` strict alert stream's signal-to-noise improves (no more noise from punctuation-only divergence).
- `LoanDisbursementBankMismatchLog` table grows one column; existing rows backfill cleanly to `soft=false`.

**Regression risk:** Low. Account/IFSC strictness unchanged. Holder-name softness is additive (warn, don't reject). Existing strict-threshold alert untouched. Flyway migration is column-add with default; safe.

**Scale impact:** Negligible. Matcher is O(n) string ops per disbursement (microseconds). One extra log row per soft mismatch.

**Code structure impact:** New `@Component` matcher is a deep, single-responsibility unit (single public `matches(submitted, onFile)` method). Aligns with existing `service.` package convention. No god-class growth (`LoanDisbursementService` shrinks slightly because the holder-name comparison moves out). `BorrowerBankDetailsService.recordDisbursementBankMismatch` splits into two clearly-named methods (hard vs soft) — improves readability.

**Overengineering check:** No. Every piece is load-bearing. No honorific list. No Levenshtein. No backfill. No strictness dial. No new endpoint surface beyond the response-shape change on the existing pre-flight. Matcher is deterministic and explainable in one paragraph.

**Sequencing / dependencies:** Standalone single PR. No upstream blockers (matcher is self-contained). No downstream callers blocked. The relief valve from #62 PR(c) is already on `main`. Do not bundle with any other open issue.

---

**TDD principles (verbatim, for thinking-and-testing this solution):**

Test-Driven Development Philosophy

Core principle: Tests should verify behavior through public interfaces, not implementation details. Code can change entirely; tests shouldn't.

Good tests are integration-style: they exercise real codepaths through public APIs. They describe what the system does, not how it does it. A good test reads like a specification — "user can checkout with valid cart" tells you exactly what capability exists. These tests survive refactors because they don't care about internal structure.

Bad tests are coupled to implementation. They mock internal collaborators, test private methods, or verify through external means (like querying a database directly instead of using the interface). The warning sign: your test breaks when you refactor, but behavior hasn't changed. If you rename an internal function and tests fail, those tests were testing implementation, not behavior.

See tests.md for examples and mocking.md for mocking guidelines.

Anti-Pattern: Horizontal Slices

DO NOT write all tests first, then all implementation. This is "horizontal slicing" — treating RED as "write all tests" and GREEN as "write all code."

This produces crap tests:

- Tests written in bulk test imagined behavior, not actual behavior
- You end up testing the shape of things (data structures, function signatures) rather than user-facing behavior
- Tests become insensitive to real changes — they pass when behavior breaks, fail when behavior is fine
- You outrun your headlights, committing to test shape

No conditional logic in test setup. Easier to see which endpoints a test exercises. Type safety per endpoint.

Refactor Candidates

After TDD cycle, look for:

- Duplication → Extract function/class
- Long methods → Break into private helpers (keep tests on public interface)
- Shallow modules → Combine or deepen
- Feature envy → Move logic to where data lives
- Primitive obsession → Introduce value objects
- Existing code the new code reveals as problematic

Mocking guidance — prefer SDK-style interfaces over generic fetchers. Create specific functions for each external operation instead of one generic function with conditional logic:

```
// GOOD: Each function is independently mockable
const api = {
  getUser: (id) => fetch(`/users/${id}`),
  getOrders: (userId) => fetch(`/users/${userId}/orders`),
  createOrder: (data) => fetch('/orders', { method: 'POST', body: data }),
};

// BAD: Mocking requires conditional logic inside the mock
const api = {
  fetch: (endpoint, options) => fetch(endpoint, options),
};
```

The SDK approach means:

- Each mock returns one specific shape
- No conditional logic in test setup
- Easier to see which endpoints a test exercises
- Type safety per endpoint

Also audit each file, component, module, and function linked to that feature, file or component.

---

### #130 — [F-7] Webhook 404 classified as PERMANENT — silent loss on URL typos
**Labels:** fragile-logic · **Link:** https://github.com/sid12701/lms/issues/130 · **Status:** **CLOSED** — [PR #182](https://github.com/sid12701/lms/pull/182) merged 2026-06-07. Soft-4xx retry + bundled **#73** redrive.

**Problem (plain English):** If a partner's webhook URL has a typo, every call returns 404, and we mark the event PERMANENT — never retry. Silent data loss until someone notices missing events.

**Possible fixes:**
1. **404 → RETRYABLE up to N attempts, then PERMANENT** — matches reality where 404 is often misconfig.
2. **Alert on first 404 (don't change classification)** — visible but still loses data.
3. **Always retry 404 forever** — never lose data, but pile up garbage events on permanent typos.

**Recommended:** Option 1 (e.g., N=10 over 1 day) plus the redrive UI from #73. Best balance of "don't lose data" and "don't pile garbage."

**Effect on app:** Partner misconfigs surface as retry storms (with alerts) instead of silent silence. Outbox holds events longer; storage cost minor.

**Detailed solution after discussion (2026-06-03) — IMPLEMENT (GREEN-LIT): bundled PR for #130 + #73.**

**Decision:** Ship #130 (classification fix) and #73 (manual redrive endpoint + admin UI) together in one feature PR. The two are complementary: classification gives the system a chance to auto-recover from typos within ~24h; redrive is the safety valve for the rare case where the partner fixes their config days later. Without redrive, #130 alone just delays silent loss by a day; without #130, every typo is a manual-recovery incident.

**Audit findings that sharpened the framing:**

The audit doc framed this as "404 → silent loss." Tracing the dispatcher path makes the surface area precise:

| Layer | Where | Today |
|---|---|---|
| Classification | `WebhookOutboxService.classify` (`WebhookOutboxService.java:296-304`) | `408 / 429 / 5xx` → RETRYABLE; **every other 4xx** (incl. 404, 410, 401, 403, 422) → PERMANENT after one attempt. |
| Dispatcher | `WebhookOutboxService.dispatchEvent` (`:156-265`) | Maps classify outcome → `markPermanentFailure` + dead-letter alert, or `markRetryableFailure` + backoff. |
| Entity state | `WebhookEventOutbox.markPermanentFailure` (`:202-208`) | Sets status=`PERMANENT_FAILURE`, `nextAttemptAt=null`. |
| Backoff | `calculateBackoffSeconds` (`:306-309`) | Exponential, capped at 1h after attempt ~6. **No max-attempts cap today for 5xx/408/429** — those retry indefinitely. |
| Claim query | `WebhookEventOutboxRepositoryImpl.claimIds` (`:72-93`) | PG `for update skip locked`; picks `PENDING` + due `RETRYABLE_FAILURE`. PERMANENT is excluded — that's the "stop retrying" mechanism. |
| Alert | `AlertRuleEvaluationService.emitWebhookDeadLetter` (`:88-118`) + `AlertRuleDataInitializer:72` | HIGH-severity ops alert, deduped per `webhook-dead-letter:<eventId>` key. Rule enabled by default. Already fires today on the (instant) PERMANENT transition. |
| Admin surface | `WebhookOutboxAdminController` | Lists outbox, can re-dispatch batch (PENDING/RETRYABLE only). **No redrive of PERMANENT.** |
| Worker | `WebhookOutboxDispatchWorker` | Scheduled call into `dispatchPending`. No behavioural change needed. |

Net effect today: a partner go-live with a one-character typo in the webhook URL → every event PERMANENT on first dispatch within seconds → `WEBHOOK_DEAD_LETTER` alert per event → ops triages → no automatic recovery, no UI to redrive, hand-edit DB rows.

**Locked design (post-grill answers):**

1. **404 and 410 become RETRYABLE-with-cap; everything else unchanged.**
   - `classify(int statusCode, int attemptCount)` becomes attempt-count-aware.
   - 404 / 410 → RETRYABLE while `attemptCount < maxSoft4xxAttempts` (default **10**); after the cap → PERMANENT.
   - 401 / 403 / 422 / other non-soft 4xx → PERMANENT immediately (today's behaviour preserved).
   - 408 / 429 / 5xx → RETRYABLE indefinitely (today's behaviour preserved — deliberately NOT touching the 5xx cap in this PR; see "scope not changed").
2. **Configuration:** `lms.webhook.soft-4xx.max-attempts: 10`, bound via `@ConfigurationProperties`. Backoff already caps at 1h after attempt ~6, so 10 attempts span ~24h naturally — no second knob needed.
3. **Alert behaviour:** `WEBHOOK_DEAD_LETTER` fires only at final exhaustion (status moves to PERMANENT_FAILURE). Today's `emitWebhookDeadLetter` call site (`WebhookOutboxService:217`) is in the PERMANENT branch — naturally fires once via the existing `webhook-dead-letter:<eventId>` dedup key. No new alert type, no new noise.
4. **Redrive endpoint (closes #73):**
   - `POST /api/v1/internal/admin/webhook-outbox/{id}/redrive`, `hasRole('SYSTEM_ADMIN')`.
   - New column `redrive_count INT NOT NULL DEFAULT 0` on `webhook_event_outbox` via Flyway migration.
   - Per-event cap: **3 manual redrives.** 4th attempt → 422 `WEBHOOK_OUTBOX_REDRIVE_CAP_EXCEEDED`.
   - Behaviour: status PERMANENT_FAILURE → PENDING; `attemptCount` reset to 0 (fresh 10-attempt budget after the partner fixes their config); `nextAttemptAt = null`; `lastError = null`; `redriveCount += 1`.
   - Guards: 422 `WEBHOOK_OUTBOX_NOT_REDRIVABLE` if status is not PERMANENT_FAILURE.
   - Audit row written via the existing audit infrastructure landed under #70 / #152, capturing actor, eventId, lspId, correlationId, redriveCount.
5. **Frontend (admin Outbox UI):** surface `redriveCount / 3`, attempts so far, and a "Redrive" button enabled only on PERMANENT rows with budget remaining. Disabled "Cap reached" state at 3/3.
6. **Backfill:** none. Existing PERMANENT events stay PERMANENT; recover only via the new redrive button. Clean cut-over, zero migration risk, avoids re-firing stale events with unknown downstream idempotency posture.

**Scope deliberately NOT changed in this PR:**
- **5xx retries-forever** stays. Same file, same `calculateBackoffSeconds` — but mixing the 5xx cap in dilutes the test signal. Ship as a follow-up once this is stable.
- **No new outbox status (`EXHAUSTED`)** — `PERMANENT_FAILURE` + `attemptCount` + `lastError` is enough to tell "we gave up" from "this was never going to work".
- **No bulk redrive.** Single-event only. Bulk waits for a real operational need.
- **No partner-facing comms.** Pre-launch; no live LSPs; webhook contract unchanged from a receiver's POV (we just retry the same event longer).
- **#87 (TX-held during slow deliveries)** — same file, separate concern, separate PR.
- **#110 (outbox index bloat)** — adding ~10 RETRYABLE rows per dead-URL-day is negligible; revisit at scale.

**Why not the alternatives we considered:**
- **Option B (cap all soft failures incl. 5xx)** — tempting because it also caps the "retry forever" cousin, but mixing two behaviour changes in one PR hides regressions. Sequencing matters.
- **Option C (per-status differential caps)** — six knobs for a problem we have one of. Premature configurability.
- **Option D (alert-only, no classification change)** — already done today via `WEBHOOK_DEAD_LETTER`; doesn't move the needle. Visible ≠ recoverable.
- **Option E (retry 404 forever)** — unbounded outbox growth for genuinely-dead URLs.
- **Option F (defer #130, ship only #73)** — leaves auto-recovery on the table; every typo becomes a paged human action.

**TDD plan (vertical slices, behaviour through public interfaces — per the TDD doc, tracer first then incremental):**

All assertions through public surfaces: HTTP status + body for the controller, persisted `WebhookEventOutbox` row state via repository read, `WebhookOutboxService.DispatchSummary` return, and the alert query interface. Boundary mock is `WebhookDeliveryClient` (network); no internal collaborators mocked.

Backend (`backend/src/test/java/com/bhawana/lms/service/WebhookOutboxServiceClassificationTest.java` + sibling tests, plus `WebhookOutboxAdminControllerRedriveIntegrationTest.java`):

1. **TRACER — `webhook_404_does_not_become_permanent_on_first_attempt`.** Stage delivery client to return 404; run `dispatchPending(1)`; assert row's `status == RETRYABLE_FAILURE`, `attemptCount == 1`, `nextAttemptAt` set, **no `WEBHOOK_DEAD_LETTER` alert exists**. Locks in: today's instant-permanent path on 404 is dead.
2. `webhook_404_retries_up_to_cap_then_permanents`. Loop dispatch 10× against persistent-404 stub; after the 10th: `status == PERMANENT_FAILURE`, `attemptCount == 10`, exactly **one** `WEBHOOK_DEAD_LETTER` alert exists for that event.
3. `webhook_410_behaves_like_404`. Same shape for 410. Locks in 410 inclusion in the soft-4xx family.
4. `webhook_401_403_422_remain_permanent_on_first_attempt`. Regression guard — non-soft 4xx still die instantly. Three parameterised cases.
5. `webhook_404_recovers_when_partner_fixes_url_within_cap`. 3× 404 then deliver 200 → `status == DELIVERED`, no alert. Real-world happy path.
6. `webhook_5xx_still_retries_indefinitely`. 50× 500 → never PERMANENT. Confirms we did NOT touch 5xx behaviour.
7. `redrive_permanent_event_resets_to_pending_and_consumes_one_budget`. Drive event to PERMANENT (configurable cap=1 in test profile, or use 401); `POST /redrive` as SYSTEM_ADMIN → 200 with `redriveCount: 1`; row shows `status == PENDING`, `attemptCount == 0`, `redriveCount == 1`, `lastError == null`; audit row written with actor/eventId/lspId/correlationId.
8. `redrive_rejected_when_event_is_not_permanent`. POST on PENDING / RETRYABLE / DELIVERED → 422, body code `WEBHOOK_OUTBOX_NOT_REDRIVABLE`. Three parameterised cases.
9. `redrive_rejected_after_three_redrives`. Sequence: drive PERMANENT → redrive (1) → drive PERMANENT → redrive (2) → drive PERMANENT → redrive (3) → drive PERMANENT → 4th redrive → 422, body code `WEBHOOK_OUTBOX_REDRIVE_CAP_EXCEEDED`. Locks the cap in via the failure path.
10. `redrive_endpoint_requires_system_admin`. OPS_USER → 403; SYSTEM_ADMIN → 200.
11. `redriven_event_re_enters_the_normal_dispatch_loop`. After redrive, next `dispatchPending` picks it up via `claimDispatchBatch` and delivers cleanly. Proves the PENDING reset works end-to-end through the claim query, not just the entity field.
12. `redrive_writes_audit_row_with_actor_eventId_lspId_correlationId`. Asserts via the audit-query interface (no direct repo poke).

PG-only schema test:

13. `schema_has_redrive_count_column_with_default_zero`. Extend `SchemaCheckConstraintsPostgresTest` to assert `redrive_count INT NOT NULL DEFAULT 0` on `webhook_event_outbox`. Locks the migration in.

Frontend (`frontend-2/src/features/admin/webhook-outbox/`):

14. `admin_outbox_view_shows_redrive_button_only_on_permanent_with_budget`. Playwright spec: PENDING row no button, PERMANENT row with `redriveCount=0` shows button, PERMANENT row with `redriveCount=3` shows "Cap reached" disabled state. Mocks fetch boundary only.

**Mocking discipline:**
- **Mock `WebhookDeliveryClient`** — system boundary (network to partner).
- Use real `WebhookOutboxService`, real `WebhookEventOutboxRepository`, real `OpsAlertService`, real `AlertRuleEvaluationService`, real `WebhookEventDeliveryAttemptRepository`. All internal — no mocks.
- Backend integration tests use H2; one PG-only schema test guards the column.
- Frontend test mocks `fetch` only; no internal component mocking.
- **Do NOT assert** which internal method threw, or call-order beyond "called once / twice." Tests describe behaviour through the public HTTP/persistence/alert interfaces.

**Tests we deliberately do NOT write here (owned elsewhere):**
- Webhook signing format / receiver verification → **#129**.
- LSP self-service update of webhook URL → already covered by existing admin tests; we only consume the URL.
- Backoff exact-seconds correctness → existing `calculateBackoffSeconds` tests cover this; no change to that function.
- `enqueueIfSubscribed` TX behaviour → **#88** (deferred).
- Dispatcher batch TX behaviour → **#87** (deferred).

**Files touched:**

| File | Change |
|---|---|
| `WebhookOutboxService.java` | `classify(int statusCode, int attemptCount)` signature; soft-4xx branch with cap. |
| `WebhookOutboxService.java` | New `redrive(UUID eventId)` method (single-event, transactional, audit-row emit). |
| `WebhookEventOutbox.java` | New `redriveCount` field + getter + `markRedrive()` method (resets status / attemptCount / nextAttemptAt / lastError; bumps redriveCount). |
| `WebhookOutboxAdminController.java` | New `@PostMapping("/{id}/redrive")` returning the updated `WebhookOutboxEventResponse`; response record gains `int redriveCount`. |
| Flyway migration `Vxx__webhook_event_outbox_redrive_count.sql` | `ALTER TABLE webhook_event_outbox ADD COLUMN redrive_count INT NOT NULL DEFAULT 0;` |
| `WebhookOutboxProperties.java` (new) | `@ConfigurationProperties("lms.webhook")` binding `softFourxx.maxAttempts` (default 10). |
| `application.yml` | `lms.webhook.soft-4xx.max-attempts: 10`. |
| `frontend-2/src/features/admin/webhook-outbox/*.tsx` | Surface `redriveCount / 3`, attempts so far, redrive button on PERMANENT rows with budget. |
| Tests (back) | `WebhookOutboxServiceClassificationTest`, `WebhookOutboxServiceRedriveTest`, `WebhookOutboxAdminControllerRedriveIntegrationTest`, `SchemaCheckConstraintsPostgresTest` extension. |
| Tests (front) | `webhook-outbox-admin-redrive.spec.tsx` (Playwright). |

**Cross-issue impact:**
- **#73** — closed by this PR (the redrive endpoint + UI lives here).
- **#87 (dispatchPending holds TX)** — same file; left untouched. Independent follow-up.
- **#88 (enqueue in user-request TX)** — same file; DEFERRED per its own decision.
- **#110 (outbox index bloat)** — minor increase in RETRYABLE rows per dead URL; well within current bounds.
- **#129 (signing format docs)** — independent.
- **#155 (failed-auth alert pipeline)** — `WEBHOOK_DEAD_LETTER` dedup means no double-firing. Worth confirming during #155's grill that webhook-side alerts don't get re-counted as auth failures.
- **#159 (Audit Explorer 7 streams, just landed in PR #174)** — webhook-redrive audit rows should land under one of the existing streams (likely the admin-ops stream); confirm during implementation.

**Effect on app:**
- Partner go-live with a typo: receiver 404s, dispatcher quietly retries with exponential backoff for ~24h. Partner notices via their monitoring, fixes URL, next retry delivers. Zero ops involvement, zero data loss.
- Partner's URL is genuinely dead: 10 attempts fail, `WEBHOOK_DEAD_LETTER` alert fires once, ops triages. If partner comes back, admin clicks Redrive (up to 3×).
- 5xx behaviour unchanged this PR.
- Outbox row count grows ~10 rows per dead-URL-day instead of 1 — negligible.
- Pre-launch, partner-contract-safe — no observable change for receivers that work correctly.

**Dependencies / sequencing:**
- Single PR, standalone.
- Depends on `WEBHOOK_DEAD_LETTER` alert plumbing (already shipped via #62) — no new alert infrastructure.
- Depends on audit infrastructure (already shipped via #70 / #152) — no new audit plumbing.
- Cross-link in PR description: closes **#130** and **#73**. Calls out #87 / 5xx-retry-cap as future follow-ups in the same file.

---

### #131 — [F-8] Repayment API forces N calls for combined EMIs
**Link:** https://github.com/sid12701/lms/issues/131

**Problem / Fixes / Recommendation / Effect:** Duplicate of #66 under fragile-logic.

**Detailed solution after discussion (2026-06-01):** Close as duplicate of **#66**. Combined-EMI ergonomics are subsumed by #66's repayment-model reframe (partial / overpayment / lump-sum / multi-EMI allocation). No separate API surface for this issue; the same endpoint change closes both.

---

### #137 — [F-14] No server-side validation that LSP_PROVIDED schedule arithmetic closes principal
**Labels:** fragile-logic · **Link:** https://github.com/sid12701/lms/issues/137 · **Status:** **CLOSED** (2026-06-05) — closes #62 PR (c) schedule hand-off.

**Problem (plain English):** When an LSP submits their own repayment schedule, we don't re-verify that the principal components sum to the loan amount or that the final closing principal is zero. A wrong schedule lands without warning.

**Shipped solution:**
- Extended `validateProvidedInstallments` with exact scale-2 checks: opening anchor, row chain, final closing zero, row reconcile (`principalDue = opening − closing`), plus existing sum/count/date rules.
- `ScheduleViolationType` enum surfaced in `violations.violationType` on `REPAYMENT_SCHEDULE_INVALID`.
- LSP write path: `emitLspProvidedScheduleViolation` → one `LSP_BOUND_VIOLATION` alert per rejection (`createAlert`, not deduped).
- GENERATED schedules: final EMI absorbs rounding so generated rows pass the same validator.
- Disbursement worker: `validatePersistedScheduleForDisbursement` inherits rules; `noRollbackFor = BusinessRuleViolationException` so worker rejection commits; corrupt persisted rows → `REJECTED` without disbursement.

**Tests:** nine integration tests in `LspLoanApplicationApiControllerTest` (LSP reject/accept paths, GENERATED parity matrix, post-persist worker defence, alert emit/absence).

**Effect on app:** Broken `LSP_PROVIDED` schedules 422 at submit; ops sees `LSP_BOUND_VIOLATION` with `SCHEDULE_*` types; disbursement worker blocks drifted persisted schedules. No API contract change beyond richer `violations` map.

---

### #138 — [F-15] Report email links lack signed-URL / expiry / encryption
**Labels:** fragile-logic, security · **Link:** https://github.com/sid12701/lms/issues/138

**Problem (plain English):** Report-ready emails contain links to download the report. Anyone with that email forever has the report. No expiry, no signing, no auth.

**Possible fixes:**
1. **Short-lived signed URL (HMAC + expiry timestamp)** — link works for N minutes only.
2. **Notification only; user fetches via authenticated UI** — strongest; no link in email.
3. **One-time-use signed URL** — link burns after first access.

**Recommended:** Option 2. Email is an unauthenticated channel; don't put data behind one.

**Effect on app:** Users get "report ready" emails and click into the app to download. Slightly worse UX; large security improvement.

**Detailed solution after discussion:** _(pending)_

---

### #142 — [SEC-Δ-4] LspIpAllowlistFilter cache is process-local — unbounded staleness across replicas
**Labels:** security, scale-risk · **Link:** https://github.com/sid12701/lms/issues/142

**Problem (plain English):** Each replica has its own 60-second cache. Multi-replica deployments have independent staleness windows that can compound — and there's no signal of how stale anything is.

**Possible fixes:**
1. **Shared cache (Redis)** — invalidation propagates fleet-wide.
2. **Shorter TTL + #83 invalidation** — bounded; not synchronized.
3. **Push-based invalidation via message bus** — needs RabbitMQ wiring.

**Recommended:** Option 1. Redis is already in compose; reuse.

**Effect on app:** Allowlist mutations are fleet-wide instantly. Removes the "which replica did the request hit?" question during incident response.

**Detailed solution after discussion:** _(pending)_

---

### #144 — [SEC-Δ-6] Re-check JWT in localStorage (H-04 status)
**Labels:** security, verification · **Link:** https://github.com/sid12701/lms/issues/144

**Problem (plain English):** Security audit's H-04 flagged JWT in localStorage. Need to confirm whether the fix has landed.

**Possible fixes:**
1. **Move JWT to memory + BFF session cookie** — strongest; XSS can't read.
2. **Keep in localStorage but add CSP/refresh hardening** — reduces blast radius; doesn't eliminate.
3. **Confirm H-04 fix already landed; close issue** — if so, done.

**Recommended:** Option 1 if H-04 not fixed. Memory + httpOnly refresh cookie is the standard pattern.

**Effect on app:** XSS no longer steals JWTs. Slight FE complexity (memory store lost on reload — silent re-auth via refresh cookie). Big security win.

**Detailed solution after discussion:** _(pending)_

---

### #146 — [SEC-Δ-8] Tenant context defaults to ADMIN in null state
**Link:** https://github.com/sid12701/lms/issues/146 · **Status:** **CLOSED** — duplicate of **#89** ([PR #182](https://github.com/sid12701/lms/pull/182))

**Problem / Fixes / Recommendation / Effect:** Duplicate of #89.

**Detailed solution after discussion (2026-06-01):** Close as duplicate of **#89**. The ADM-default fall-through in `TenantDataAccessContextHolder` is exactly the leak path #89's solution closed (explicit `NONE` default + worker boundary requires explicit mode set). No separate work.

---

### #147 — [AUD-1] Auth endpoints not audited (dup of #71)
**Link:** https://github.com/sid12701/lms/issues/147

**Detailed solution after discussion (2026-06-01):** Close as duplicate of **#71**. Login / token / refresh / logout / password-change audit rows ship with #71's solution. No separate scope.

---

### #148 — [AUD-2] Admin reset-password writes no audit row
**Labels:** auditability, security · **Link:** https://github.com/sid12701/lms/issues/148 · **Status:** **CLOSED** — [PR #174](https://github.com/sid12701/lms/pull/174) (2026-06-02)

**Problem (plain English):** Admin resets a user's password and the system writes no audit row. Privileged account takeover is invisible.

**Possible fixes:**
1. **Audit row with `PASSWORD_RESET_BY_ADMIN` event type** — straightforward.
2. **Also notify the affected user by email** — strongest; user has a chance to detect.

**Recommended:** Both. Audit + user notification are cheap.

**Effect on app:** Resets are traceable; users learn about resets they didn't request.

**Detailed solution after discussion (2026-06-01):**

#### Audit of linked surface area (done before deciding)

| Surface | File:Line | State |
|---|---|---|
| Reset endpoint | `backend/.../web/UserAdminController.java:74-82` (`POST /api/v1/internal/admin/users/{userId}/reset-password`) | SYSTEM_ADMIN-only. Returns `ResetPasswordResponse(id, username, temporaryPassword)`. **No `@AuthenticationPrincipal Jwt` param, no `HttpServletRequest`** — actor identity and request IP are thrown away at the HTTP boundary. The other endpoints in this controller (`updateUser`) already extract `principal.getSubject()` — pattern exists, just not applied here. |
| Service method | `backend/.../service/AdminDirectoryService.java:341-351` (`resetUserPassword(UUID userId)`) | Generates 18-byte Base64-url temporary password, sets `passwordChangeRequired=true`, saves. **Writes no audit row. Signature takes no actor info.** Throws `IllegalArgumentException` when `userId` unknown → 400 (no audit row, intentional per the grill). |
| Existing audit pattern | `AdminDirectoryService.java:329-336` (`updateUser`) | The reference write: builds `UserAuditSnapshot` before+after, calls `appUserAuditEventRepository.save(new AppUserAuditEvent(saved, actorUsername, serializeAuditSnapshot(before), serializeAuditSnapshot(after), CorrelationIdHolder.get()))`. **No actor_ip captured today.** |
| Audit entity | `backend/.../domain/AppUserAuditEvent.java` | jsonb `before_state_json` + `after_state_json` **both NOT NULL**. Has `user_id`, `actor_username`, `correlation_id`, `created_at`. **No `actor_ip`, no `event_type`.** |
| UserAuditSnapshot | `AdminDirectoryService.java:391-409` | Private record `(email, status, lspId, roles)`. The serializer/deserializer is local to `AdminDirectoryService`. |
| AppUser.email | nullable (V1__foundation.sql:30) | Means email-notify must be best-effort and skip-on-null even if we ever add it. |
| Email infra | `ReportNotificationService.java:24-71` | `ObjectProvider<JavaMailSender>` (optional), `app.reports.notifications.enabled` toggle, `SimpleMailMessage`, returns `NotificationResult(sent/skipped/failed)`. Template exists if/when we add admin-reset notification — **out of scope for this PR per the grill**. |
| Read surface for audit rows | not directly exposed | Today no user-audit-event read endpoint exists; data is only reachable via Audit Explorer (`AuditExplorerService` over `app_user_audit_event` is one of the 4 existing streams per #159). Slice tests verify via direct repo query of `app_user_audit_event` for the seeded `user_id` — this is acceptable per the prompt because the repository is the same "public read interface" the Explorer uses; no internal collaborator is mocked. |

**Conclusion of audit:** infrastructure exists for a state-mutation audit row; the only gaps are (a) the service method doesn't take actor info, (b) the controller doesn't extract it, (c) the entity has no `actor_ip` column, (d) the snapshot record doesn't carry `passwordChangeRequired`, and (e) the audit-row payload needs a marker so a `PASSWORD_RESET_BY_ADMIN` event is queryable as something other than a no-op update.

#### Decisions (after grilling, 2026-06-01)

1. **Reuse `AppUserAuditEvent`; do not introduce a new table.** One audit fabric to query; the synthetic-diff cost is small and contained. Discriminator lives in the after-state JSON as `eventType: 'PASSWORD_RESET_BY_ADMIN'` — existing `updateUser` writes have no `eventType` key, so `eventType IS NULL` → user-update event, `eventType = 'PASSWORD_RESET_BY_ADMIN'` → reset event. SQL: `WHERE after_state_json->>'eventType' = 'PASSWORD_RESET_BY_ADMIN'`.
2. **Schema migration: add `actor_ip VARCHAR(64) NULL` to `app_user_audit_event`.** Matches the precedent set by #70 (`actor_ip + byte_count` on `loan_application_document_access_audit`). NULLable so existing rows pass migration; existing `updateUser` writers start populating it the same day they're rebuilt; reset writes populate from day one. **Top-level `event_type` column is explicitly NOT added** (rejected during grill) — the discriminator lives inside the JSON payload, keeping migration churn minimal. If a future audit-class issue needs a real discriminator column, that's its scope, not this PR's.
3. **Extend `UserAuditSnapshot` from `(email, status, lspId, roles)` to `(email, status, lspId, roles, passwordChangeRequired)`.** The reset event's diff is no longer a no-op: before `passwordChangeRequired=false` → after `passwordChangeRequired=true`. Existing `updateUser` writes pick up the new field automatically with the user's current value. **No backfill** — historical rows continue to have a 4-field snapshot.
4. **After-state JSON gains a top-level `eventType` field** alongside the snapshot fields for `PASSWORD_RESET_BY_ADMIN` writes. `updateUser` writes leave it absent. After-state shape for a reset: `{"email":"...","status":"...","lspId":"...","roles":[...],"passwordChangeRequired":true,"eventType":"PASSWORD_RESET_BY_ADMIN"}`. Before-state stays a pure snapshot: `{"email":"...","status":"...","lspId":"...","roles":[...],"passwordChangeRequired":false}`.
5. **Audit successes only.** 400-on-unknown-userId paths do NOT write an audit row (consistent with `updateUser`'s 400-on-unknown-LSP behaviour). The audit table records real resets; recon attempts against unknown IDs are caught at the request-log / SOC layer, not here.
6. **Email-notify-the-user is deferred to a new follow-up issue.** Audit-trail is the compliance must-have for #148; user-detection-via-email is valuable but separable, has UX details (template, link target, what if email is null), and depends on broader account-takeover notification design. Filing as a new ticket post-PR; cross-link in PR body.
7. **Controller signature change:** `resetPassword` now accepts `@AuthenticationPrincipal Jwt principal` AND `HttpServletRequest request`. Service method `resetUserPassword` grows from `(UUID userId)` to `(UUID userId, String actorUsername, String actorIp, String correlationId)`. The temp password in the response is unchanged — admins still receive it once, never logged.
8. **Never write the temporary password (or its hash) into the audit row.** The fact-of-reset is sufficient; the password itself never appears in any log surface. Slice 3 (negative test) locks this in.

#### TDD plan (vertical slices, one RED → GREEN at a time)

Tests are integration-style through MockMvc against a real DB. Each test name reads as a behaviour spec. No mocking of internal collaborators (AdminDirectoryService, AppUserAuditEventRepository, AppUserRepository). Verification reads the `app_user_audit_event` table directly via its repository — that repository IS the read interface for the Audit Explorer (it's wrapped by `AuditExplorerRepository`), so this respects the "verify through public interface" rule.

**Slice 1 — Admin reset-password writes an audit row with correct shape**
- RED: New test class `UserAdminControllerResetPasswordAuditTest` (or extend the existing user-admin test class). Seed: an `ACTIVE` non-admin user with `passwordChangeRequired=false`. Authenticate as a SYSTEM_ADMIN. POST `/api/v1/internal/admin/users/{userId}/reset-password`. Assert 200 + `ResetPasswordResponse` carries a temporaryPassword. Then query `app_user_audit_event` for `user_id = <userId>` ordered by `created_at DESC` LIMIT 1 and assert:
  - `actor_username = <admin>`
  - `correlation_id = <MDC value present on the request>`
  - `actor_ip = <request remote addr>`
  - `before_state_json->>'passwordChangeRequired' = 'false'`
  - `after_state_json->>'passwordChangeRequired' = 'true'`
  - `after_state_json->>'eventType' = 'PASSWORD_RESET_BY_ADMIN'`
  - `before_state_json->>'eventType' IS NULL`
  
  Initially fails to compile (entity has no `actor_ip`, snapshot record lacks `passwordChangeRequired`, service signature is wrong). After compile, still RED — handler writes nothing.
- GREEN (compile-cascade in one slice):
  1. Migration `V{n+1}__app_user_audit_event_actor_ip.sql`: `ALTER TABLE app_user_audit_event ADD COLUMN actor_ip VARCHAR(64);`.
  2. `AppUserAuditEvent`: add `actorIp` field (`@Column(name = "actor_ip", length = 64)`), overloaded constructor `(AppUser, String actorUsername, String beforeStateJson, String afterStateJson, String correlationId, String actorIp)`. The existing 5-arg constructor delegates to the new 6-arg one with `actorIp=null` — keeps the existing `updateUser` write site building until we update it in the same PR.
  3. `AdminDirectoryService.UserAuditSnapshot`: add `passwordChangeRequired` field; update `toAuditSnapshot` to include it.
  4. `AdminDirectoryService.resetUserPassword`: change signature to `(UUID userId, String actorUsername, String actorIp, String correlationId)`. After `appUserRepository.save(user)`, build before-snapshot (with `passwordChangeRequired=false`) and after-snapshot (with `passwordChangeRequired=true`). Serialize after-state JSON manually to include `eventType: 'PASSWORD_RESET_BY_ADMIN'` (a small helper: `serializeAuditEventPayload(snapshot, eventType)` that wraps the snapshot ObjectNode and inserts the marker). Write via `appUserAuditEventRepository.save(new AppUserAuditEvent(user, actorUsername, beforeJson, afterJson, correlationId, actorIp))`.
  5. `UserAdminController.resetPassword`: add `@AuthenticationPrincipal Jwt principal` and `HttpServletRequest request` params; extract `actorUsername` (from `principal.getSubject()`) and `actorIp` (from `request.getRemoteAddr()` — same pattern as #70's controller delegation); pass through to the service.
  6. Update `updateUser` write site to pass `actorIp` to the audit-event constructor (so the column is populated going forward; existing rows stay NULL).
  
  Test passes.
- Refactor: extract a private `writeUserAuditEvent(user, actorUsername, actorIp, correlationId, beforeSnapshot, afterSnapshot, eventType)` helper to share the JSON-serialization + save call between `updateUser` and `resetUserPassword`. Keeps both call sites tight.

**Slice 2 — Existing updateUser audit row picks up actor_ip but leaves eventType absent**
- RED: Authenticate as SYSTEM_ADMIN, PUT `/api/v1/internal/admin/users/{userId}` with an email change. Query latest audit row for that user; assert `actor_ip = <request IP>` AND `after_state_json->>'eventType' IS NULL` (proves the marker is reset-specific, not added to every user write).
- GREEN: already done in Slice 1's refactor step (the helper conditionally inserts `eventType` only when non-null). If RED on the eventType assertion, fix the helper to omit the key when caller doesn't pass it.
- Refactor: none.

**Slice 3 — Audit row does NOT contain the temporary password**
- RED: Seed a user, POST reset, grab `temporaryPassword` from the response; query the latest audit row; assert neither `before_state_json` nor `after_state_json` (serialized as strings) contain the literal temp password substring. Also assert they don't contain a SHA-256 / BCrypt prefix that would hint a credential leak.
- GREEN: should pass immediately after Slice 1 — the snapshot doesn't include the password and the helper never receives it. This slice locks in the non-leak property as a regression guard.
- Refactor: none.

**Slice 4 — 400-on-unknown-userId leaves the audit table untouched**
- RED: Pre-count `app_user_audit_event` rows. POST `/reset-password` with a random UUID. Assert HTTP 400. Re-count rows; assert delta is 0.
- GREEN: passes immediately — `IllegalArgumentException` is thrown by the `findById().orElseThrow()` before any write happens. This slice is the negative regression guard for the "audit successes only" decision.
- Refactor: none.

**Order:** Slice 1 carries the compile-cascade (migration + entity + snapshot + service + controller wiring + helper). Slices 2 → 3 → 4 are additive assertions on top.

**Anti-pattern check (per the TDD prompt):** none of these tests mock `AdminDirectoryService`, `AppUserAuditEventRepository`, or `AppUserRepository`. They drive the HTTP boundary via MockMvc and read the audit table via the same JPA repository the Audit Explorer uses — that's the public read interface, not an "external means." Tests would survive a refactor that moved the audit write into a Spring `@TransactionalEventListener` or behind an audit-service facade — the assertion is on the row in the database table that the Audit Explorer queries.

#### Files touched (final list)

Edit:
- `backend/src/main/java/com/bhawana/lms/domain/AppUserAuditEvent.java` — add `actorIp` field + getter + 6-arg constructor; 5-arg constructor delegates with null.
- `backend/src/main/java/com/bhawana/lms/service/AdminDirectoryService.java` — extend `UserAuditSnapshot` record with `passwordChangeRequired`; rewrite `resetUserPassword(UUID)` to `(UUID, String, String, String)` + audit write; update `updateUser` audit write to pass `actorIp`; add private `writeUserAuditEvent(...)` helper.
- `backend/src/main/java/com/bhawana/lms/web/UserAdminController.java` — `resetPassword` accepts `@AuthenticationPrincipal Jwt principal` + `HttpServletRequest request`; extracts actorUsername + actorIp; passes through.

Add:
- `backend/src/main/resources/db/migration/V{n+1}__app_user_audit_event_actor_ip.sql` (one column add).
- `backend/src/test/java/com/bhawana/lms/web/UserAdminControllerResetPasswordAuditTest.java` (four slice tests, one per behaviour spec; or extend the existing user-admin test class with the same four `@Test` methods).

Untouched (deliberately):
- `ApiClientAuditEvent` — owned by #149.
- `AuditExplorerRepository` / Service / Controller — wiring `eventType`-aware filtering is a UX nicety, not in this PR's scope; #159 owns enhancing the Explorer.
- `ReportNotificationService` — email-notify-the-user is deferred to a new follow-up ticket (linked in the PR body).
- Self-service `/password` audit — owned by #71 (`PASSWORD_CHANGED` in `auth_event_audit`); orthogonal table, different actor model.

#### Effect on app

- Every admin-initiated password reset writes one row to `app_user_audit_event` with `eventType=PASSWORD_RESET_BY_ADMIN`, actor username, actor IP, and correlation ID. Rows are queryable on the **APP_USER** Audit Explorer stream (#159 / #152 PR (a)).
- New `actor_ip` column starts populating for the existing `updateUser` writes too — same migration, no cost.
- Extending `UserAuditSnapshot` with `passwordChangeRequired` makes the reset event's diff visible (false → true) instead of a confusing no-op snapshot diff.
- Audit Explorer shows resets under APP_USER; dedicated `eventType` filter chips remain a follow-up under #159.
- Temporary password is never persisted to any audit/log surface (Slice 3 regression-guards this).
- 400-on-unknown-userId path is forensically silent by design (Slice 4 regression-guards this).
- Tiny per-reset overhead: one DB insert and one JSON serialization, in the same `@Transactional` boundary as the user save.

#### Cluster impact

- **#71** (`auth_event_audit` table for login/refresh/logout/password-change) — orthogonal. `#71` covers the **self-service** password-change path (`PASSWORD_CHANGED`). `#148` covers the **admin-driven reset** path. Different actor models, different tables, no overlap. PRs are independent.
- **#149** (API-client create/rotate/reveal audit) — sibling AUD-class issue. Its grilling is the next task. The `actor_ip` precedent set here may flow into `ApiClientAuditEvent` too; decision deferred to #149's grill.
- **#155** (`AUTH_BRUTE_FORCE` lockout) — uses `auth_event_audit`, not `app_user_audit_event`. Independent.
- **#159** (Audit Explorer streams) — **CLOSED** with [PR #174](https://github.com/sid12701/lms/pull/174). `APP_USER` stream surfaces reset rows; `eventType` filter chips deferred.
- **#98 / #99** (god-class refactors of `AdminDirectoryService`) — the new helper `writeUserAuditEvent` plus the snapshot extension stay inline in `AdminDirectoryService`. When #98/#99 land, the natural extraction is an `AppUserAuditEventWriter` collaborator absorbing both call sites; out of scope here.

#### Dependencies / sequencing

- Independent of all other AUD-class work; can ship first or anywhere in the AUD-class PR train.
- The follow-up email-notify ticket is non-blocking; file in the same PR description.

**Implementation status — CLOSED (2026-06-02, [PR #174](https://github.com/sid12701/lms/pull/174)):**

| Delivered | Primary code |
|-----------|--------------|
| Migration **V81**: `actor_ip` on `app_user_audit_event` | `V81__app_user_audit_event_actor_ip.sql` |
| `PASSWORD_RESET_BY_ADMIN` in after-state JSON; snapshot includes `passwordChangeRequired` | `AdminDirectoryService.java` |
| `Jwt` + `ClientIpAddresses.resolve` on reset-password | `UserAdminController.java` |
| Integration tests (slices 1–4) | `UserAdminControllerTest.java` |

---

### #149 — [AUD-3] API-client create/rotate/reveal moments not audited
**Labels:** auditability, security · **Link:** https://github.com/sid12701/lms/issues/149 · **Status:** **CLOSED** — [PR #174](https://github.com/sid12701/lms/pull/174) (2026-06-02)

**Problem (plain English):** Creating an API client, rotating its secret, or revealing it leaves no audit trail.

**Possible fixes:**
1. **`API_CLIENT_SECRET_REVEALED` audit on the reveal endpoint + create/rotate audits** — covers all three moments.
2. **Audit create/rotate only, skip reveal** — leaves the critical "who saw the secret" gap.

**Recommended:** Option 1. The reveal is the auditable moment.

**Effect on app:** Audit Explorer shows secret-reveal events. Operations can trace who saw what.

**Detailed solution after discussion (2026-06-01):**

#### Reframe — the audit gap is narrower than the issue text

A surface-area audit before deciding revealed that **the issue's framing is partially wrong**:

| Surface | File:Line | Reality |
|---|---|---|
| `createClient` | `ApiClientManagementService.java:59-87` | **No audit write.** Method signature has no `actorUsername` param. Controller (`ApiClientAdminController.java:41-43`) does not extract `@AuthenticationPrincipal Jwt`. **This is the real gap.** |
| `rotateSecret` | `ApiClientManagementService.java:139-175` | **Already audits** as `action="SECRET_ROTATED"` with `{graceSeconds, oldSecretValidUntil}` in `details_json`. Controller passes `principal.getSubject()`. ✅ |
| `updateClient` | `ApiClientManagementService.java:103-137` | **Already audits** as `action="CLIENT_UPDATED"` with `{before, after}` snapshot (clientId, name, description, status, lspId, ipAllowlist). ✅ |
| Separate "reveal" endpoint | — | **Does not exist.** The raw secret only escapes at create + rotate (returned in the HTTP response payload, never queryable later). The audit doc's "reveal" framing collapses naturally into the create + rotate moments — there is nothing else to audit. |
| `ApiClientAuditEvent` entity | `domain/ApiClientAuditEvent.java` | Has first-class `action VARCHAR(64) NOT NULL` column (unlike `AppUserAuditEvent`'s no-discriminator design — see #148). Filtering by action is already a first-class SQL operation. **No `actor_ip` column.** |
| Disable/enable today | via `updateClient` setting `status=INACTIVE` | Status flip is forensically recoverable from the `CLIENT_UPDATED` row's before/after diff, but the security-critical "client disabled" moment is buried inside a generic update row. |

**Conclusion:** the real gap reduces to (a) `CLIENT_CREATED` is missing, (b) `actor_ip` is not captured anywhere, (c) `actorUsername` is not plumbed into `createClient`, (d) `disable`/`enable` is not a first-class event label. The "reveal" framing in the AC is rephrased: **the CREATE and ROTATE events ARE the reveal**, because both return the raw secret once; nothing else does.

#### Decisions (after grilling, 2026-06-01)

1. **Add `CLIENT_CREATED` audit row in `createClient`.** Single row per create. Treat create as the implicit reveal moment — no separate `SECRET_REVEALED` row needed. Details JSON carries the same initial snapshot shape `updateClient` uses today: `{snapshot: {clientId, name, description, status, lspId, ipAllowlist: []}}`. **Raw secret is never serialized into `details_json`** (Slice 5 regression-guards this).
2. **Add `actor_ip VARCHAR(64) NULL` column to `api_client_audit_event`.** Matches the precedent set by #70 + #148. NULLable so existing rows pass migration. Populated by `CLIENT_CREATED` from day one; existing `updateClient` + `rotateSecret` writes pick it up as soon as the controller plumbs it through.
3. **Action label discrimination on status flips in `updateClient`:** compute the action label from the before/after status comparison.
   - `before.status == ACTIVE && after.status == INACTIVE` → `action = CLIENT_DISABLED`
   - `before.status == INACTIVE && after.status == ACTIVE` → `action = CLIENT_ENABLED`
   - otherwise → `action = CLIENT_UPDATED`
   - **Status-flip wins the label** when other fields change in the same call (e.g., admin renames + disables in one PUT → action=CLIENT_DISABLED, full diff still in `details_json.before/after`).
4. **Plumb actor + IP through three controller endpoints.** `createClient` controller method grows `@AuthenticationPrincipal Jwt principal` + `HttpServletRequest request`. Service signature grows `(name, description, lspId, status, actorUsername, actorIp)`. Same actor+IP plumbing extended to `updateClient` and `rotateSecret` (today they have actor; they don't have IP).
5. **`recordAudit` private helper grows one param** to accept `actorIp` and pass it into the `ApiClientAuditEvent` constructor. Single touch point for all three write sites.
6. **Successes only.** 400 / 404 paths (unknown LSP id, unknown client id, invalid CIDR) write no audit row — matches `AdminDirectoryService.resetUserPassword` decision under #148 and the existing `updateClient`/`rotateSecret` behaviour.
7. **No FE changes in this PR.** Audit Explorer (#159) already covers `api_client_audit_event` as one of the 4 streams; new action labels `CLIENT_CREATED` / `CLIENT_DISABLED` / `CLIENT_ENABLED` will show up automatically; surfacing them as filter chips is a #159 enhancement.

#### TDD plan (vertical slices, one RED → GREEN at a time)

Tests are integration-style through MockMvc with a real DB. Each test name reads as a behaviour spec. No mocking of internal collaborators — verification reads `api_client_audit_event` rows via the same JPA repository the Audit Explorer wraps.

**Slice 1 — `createClient` writes CLIENT_CREATED with actor/ip/correlation/initial-snapshot**
- RED: New test class `ApiClientAdminControllerCreateAuditTest`. Authenticate as SYSTEM_ADMIN. POST `/api/v1/internal/admin/api-clients` with valid body. Assert HTTP 200 + `CreatedApiClientResponse` carries `rawSecret`. Query `api_client_audit_event` for the new client id, latest row:
  - `action = 'CLIENT_CREATED'`
  - `actor_username = <admin>`
  - `correlation_id = <MDC value>`
  - `actor_ip = <request remote addr>`
  - `details_json->'snapshot'->>'clientId'` matches `client_id`
  - `details_json->'snapshot'->>'status' = 'ACTIVE'` (default)
  - `details_json->'snapshot'->'ipAllowlist'` is an empty array
  
  Initially fails to compile (entity has no `actorIp`; service signature doesn't take actor; controller doesn't extract). After compile, still RED — no audit row written.
- GREEN (compile-cascade in one slice):
  1. Migration `V{n+1}__api_client_audit_event_actor_ip.sql`: `ALTER TABLE api_client_audit_event ADD COLUMN actor_ip VARCHAR(64);`.
  2. `ApiClientAuditEvent`: add `actorIp` field + 6-arg constructor `(ApiClient, String, String, String, String, String)`; 5-arg constructor delegates with `actorIp=null`.
  3. `ApiClientManagementService.createClient`: change signature to `(name, description, lspId, status, actorUsername, actorIp)`; after `apiClientRepository.save(...)`, call `recordAudit(saved, actorUsername, "CLIENT_CREATED", Map.of("snapshot", auditSnapshot(saved, List.of())), actorIp)`.
  4. `ApiClientManagementService.recordAudit`: grow signature with `actorIp` param; pass to constructor.
  5. `ApiClientAdminController.create`: add `@AuthenticationPrincipal Jwt principal` + `HttpServletRequest request` params; pass `principal.getSubject()` + `request.getRemoteAddr()` to the service.
  
  Test passes.
- Refactor: none yet — Slices 2–5 reuse the helper.

**Slice 2 — `updateClient` row picks up actor_ip on a no-status-change update**
- RED: Authenticate as SYSTEM_ADMIN, PUT `/api/v1/internal/admin/api-clients/{id}` with a name change but `status` unchanged. Assert latest audit row has `action='CLIENT_UPDATED'` AND `actor_ip = <request IP>`.
- GREEN: thread `actorIp` from `ApiClientAdminController.update` (already gets `HttpServletRequest` here in this slice) through to `updateClient(...)` service signature; service passes to `recordAudit`. CLIENT_UPDATED label unchanged because status didn't flip.
- Refactor: none.

**Slice 3 — Status flip ACTIVE→INACTIVE emits CLIENT_DISABLED label**
- RED: Seed an `ACTIVE` client; PUT with `status='INACTIVE'`. Assert latest row `action='CLIENT_DISABLED'` AND `details_json->'after'->>'status' = 'INACTIVE'`. Also assert `details_json->'before'->>'status' = 'ACTIVE'` (the full diff is preserved).
- GREEN: in `updateClient`, after computing `before` and `after` snapshots, derive `resolvedAction` from the status comparison; pass that to `recordAudit` instead of the hard-coded `"CLIENT_UPDATED"`.
- Refactor: extract a small `private static String resolveUpdateAction(String beforeStatus, String afterStatus)` helper for clarity.

**Slice 4 — Status flip INACTIVE→ACTIVE emits CLIENT_ENABLED label**
- RED: Seed an `INACTIVE` client; PUT with `status='ACTIVE'`. Assert latest row `action='CLIENT_ENABLED'`.
- GREEN: should pass immediately after Slice 3's helper — the helper symmetrically handles the reverse direction.
- Refactor: none.

**Slice 5 — `rotateSecret` row picks up actor_ip AND raw secret never appears in details_json**
- RED: Seed a client. POST `/api/v1/internal/admin/api-clients/{id}/rotate-secret`. Grab `newSecret` from response. Assert latest row has `action='SECRET_ROTATED'`, `actor_ip` populated, AND the literal `newSecret` substring is NOT present in `details_json` (serialized to string). Also assert no BCrypt prefix (`$2a$`/`$2b$`) appears.
- GREEN: thread `actorIp` from `ApiClientAdminController.rotateSecret` through to the service. The non-leak property should already hold (today's `rotateDetails` map only contains `graceSeconds` + `oldSecretValidUntil`) — this slice locks in the regression guard.
- Refactor: none.

**Slice 6 — 400/404 paths leave the audit table untouched**
- RED: Pre-count `api_client_audit_event` rows. POST `/api-clients` with an unknown `lspId` → expect 400. POST `/{randomUuid}/rotate-secret` → expect 400. PUT `/{randomUuid}` → expect 400. PUT with an invalid CIDR string → expect 400. Re-count rows after all four; assert delta is 0.
- GREEN: passes immediately — `findById().orElseThrow()` and `normalizeAllowlist`'s `IllegalArgumentException` fire before any audit write. Slice locks in "audit successes only" for all three endpoints.
- Refactor: none.

**Order:** Slice 1 carries the compile-cascade (migration + entity + service + controller wiring). Slices 2–6 are additive assertions on top, each adding one behavioural property.

**Anti-pattern check (per the TDD prompt):** none of these tests mock `ApiClientManagementService`, `ApiClientAuditEventRepository`, `ApiClientRepository`, or `PasswordEncoder`. All verification goes through the HTTP boundary (MockMvc) + a JPA repository read (the same one Audit Explorer uses). Tests survive a refactor that moves audit writes behind a Spring event listener — the assertion is on the table row.

#### Files touched (final list)

Edit:
- `backend/src/main/java/com/bhawana/lms/domain/ApiClientAuditEvent.java` — add `actorIp` field + getter + 6-arg constructor; 5-arg constructor delegates with null.
- `backend/src/main/java/com/bhawana/lms/service/ApiClientManagementService.java` — grow `createClient` signature to include `actorUsername` + `actorIp`; add `CLIENT_CREATED` audit write; grow `updateClient` + `rotateSecret` signatures with `actorIp`; grow `recordAudit` helper with `actorIp`; add `resolveUpdateAction` helper.
- `backend/src/main/java/com/bhawana/lms/web/ApiClientAdminController.java` — extract `actorUsername` + `actorIp` at each of the three write endpoints; pass through to service.

Add:
- `backend/src/main/resources/db/migration/V{n+1}__api_client_audit_event_actor_ip.sql` (one column add).
- `backend/src/test/java/com/bhawana/lms/web/ApiClientAdminControllerCreateAuditTest.java` (or extend an existing api-client-admin test class with the six slice tests, one per behaviour spec).

Untouched (deliberately):
- `AppUserAuditEvent` — owned by #148. Schema change there is in #148's PR.
- `AuditExplorerRepository` / Service / Controller — `api_client_audit_event` is already a covered stream; exposing the new action labels as filter chips is left to #159.
- `ApiClientAuthenticationService` — the auth-time check path (which #79 owns) writes no audit rows by design — `auth_event_audit` (owned by #71) covers `API_CLIENT_TOKEN_*` events. Different table, different purpose.
- IP-allowlist add/remove specifically — `updateClient` already audits allowlist diffs inside `CLIENT_UPDATED`; #154 is a verification-only ticket and can close pointing here once #149 lands.

#### Effect on app

- Every API client create now writes one row to `api_client_audit_event` with `action=CLIENT_CREATED`, full initial snapshot, actor username, actor IP, correlation ID.
- Every status-flip update writes a row with the security-relevant action label (`CLIENT_DISABLED` / `CLIENT_ENABLED`) instead of a generic `CLIENT_UPDATED`; full before/after diff is preserved in `details_json` so non-status changes are still recoverable.
- New `actor_ip` column starts populating for all three event types from this PR onwards. Existing rows stay NULL.
- Raw secrets never appear in audit rows (Slice 5 regression-guards this).
- 400/404 paths are forensically silent by design (Slice 6 regression-guards this).
- SOC queries become trivial: `WHERE action='CLIENT_CREATED'`, `WHERE action='CLIENT_DISABLED'`, etc. No JSON-extraction needed for the common cases.
- Tiny per-write overhead: one DB insert + one JSON serialization, inside the same `@Transactional` boundary as the entity save.
- No user-visible UI change in this PR.

#### Cluster impact

- **#79** (Disabled API client keeps working until token expires — [SOLVED 2026-06-01]) — `CLIENT_DISABLED` action label here becomes the natural trigger for any post-PR "revoke active tokens on disable" follow-up; the row is the forensic record of when the disable happened.
- **#154** ([AUD-8] IP allowlist add/remove audit incomplete — verify) — for **API-client-level** allowlists, `updateClient` already writes the diff today and continues to under this PR. For **LSP-level** allowlists, #154's grill will look at the LSP path separately. #154 may close as "API-client side verified by #149; LSP-side follow-up tracked separately."
- **#71** (auth_event_audit for login/refresh/etc) — orthogonal table. The `auth_event_audit.API_CLIENT_TOKEN_*` rows record *use* of an API client (token mint moments); `api_client_audit_event` records *mutations* of the API client itself. Both streams together give the full picture.
- **#148** (Admin reset-password audit) — sibling AUD-class issue, same `actor_ip` precedent now applied in three audit tables (loan-document #70, app-user #148, api-client #149). Consistency win.
- **#159** ([R-4] Audit Explorer only covers 4 streams) — `api_client_audit_event` is one of the 4 streams; new action labels surface automatically. Filter-chip enhancements deferred to #159.
- **#155** ([AUD-9] failed-auth lockout) — for **API client** brute-force, the lockout query is over `auth_event_audit.API_CLIENT_TOKEN_FAILED`, not `api_client_audit_event`. Independent of this PR.

#### Dependencies / sequencing

- Independent of all other AUD-class work; can ship in any order within the AUD-class PR train.
- No prerequisite on #71 or #148. Cleanly parallelizable.

**Implementation status — CLOSED (2026-06-02, [PR #174](https://github.com/sid12701/lms/pull/174)):**

| Delivered | Primary code |
|-----------|--------------|
| Migration **V83**: `actor_ip` on `api_client_audit_event` | `V83__api_client_audit_event_actor_ip.sql` |
| `CLIENT_CREATED` on create; `CLIENT_DISABLED` / `CLIENT_ENABLED` on status flip | `ApiClientManagementService.java` |
| `actor_ip` + `ClientIpAddresses.resolve` on create / update / rotate | `ApiClientAdminController.java` |
| Integration tests (6 slices) | `ApiClientAdminControllerCreateAuditTest.java` |

---

---

### #150 — [AUD-4] Document download + ZIP not audited
**Link:** https://github.com/sid12701/lms/issues/150 · **Status:** **CLOSED** — duplicate of **#70**; [PR #173](https://github.com/sid12701/lms/pull/173) merged 2026-06-02

**Detailed solution after discussion (2026-06-01):** Close as duplicate of **#70**. Single-doc and ZIP-bundle download audit rows (actor, IP, correlationId, application + document IDs, byte-count, outcome) are written by #70's solution. No separate scope.

**Implementation status (2026-06-02):** Resolved by § **#70** above. No additional code.

---

### #151 — [AUD-5] MIS CSV + report-download endpoints not audited
**Labels:** auditability, security, reporting-risk · **Link:** https://github.com/sid12701/lms/issues/151

**Problem (plain English):** Bulk PII downloads (MIS CSV, generated report) leave no trace. This is the worst-case exfil path and the least-watched.

**Possible fixes:**
1. **Dedicated `report_download_audit` table; include actor, row count, file size** — strongest.
2. **Reuse existing audit table; add new event types** — fewer schemas.

**Recommended:** Option 2. One audit fabric to query; less migration churn.

**Effect on app:** Bulk-download events visible in Audit Explorer; alert rules can watch them (`BULK_PII_DOWNLOAD`).

**Detailed solution after discussion (2026-06-01):**

#### Reframe — the audit doc's Option 2 doesn't actually fit

A surface-area audit before deciding revealed that **no existing audit table can host a cross-cutting bulk-MIS event**: every candidate (`LoanApplicationDocumentAccessAudit`, `LoanApplicationPiiRevealAudit`, `AppUserAuditEvent`, `ApiClientAuditEvent`) is FK'd to a single parent entity, and MIS exports span many. The audit doc's preferred Option 2 ("reuse existing audit table") is not workable; **Option 1 (new dedicated table) is the right call**.

#### Audit of linked surface area (done before deciding)

| Surface | File:Line | Path | State |
|---|---|---|---|
| Sync CSV download | `ReportAdminController.java:67-80` | `GET /api/v1/internal/reports/portfolio-mis` (`text/csv`) | Inline `AdminReportingService.generatePortfolioMisCsv(...)` → returns `GeneratedReport(fileName, mediaType, byte[])`. **No audit row, no actor identity captured beyond Spring Security context.** This is the bulk-PII firehose. |
| Async result download | `ReportAdminController.java:103-107` | `GET /api/v1/internal/reports/requests/{id}/download` | Streams completed bytes via `reportRequestService.getCompletedReport(requestId)`. **No audit row.** |
| Async request submit | `ReportAdminController.java:82-94` | `POST /api/v1/internal/reports/portfolio-mis/requests` | Creates `ReportRequest` job; processor generates CSV later; email notification on terminal status. **No audit row at submit time.** (Out of audit perimeter per the grill.) |
| Preview | `ReportAdminController.java:42-56` | `GET /api/v1/internal/reports/portfolio-mis/preview` | Paginated, masks Aadhaar/bank/PAN per #69. **Out of audit perimeter** — masked, noisy, low exfil risk. |
| Summary | `ReportAdminController.java:58-65` | `GET /api/v1/internal/reports/portfolio-mis/summary` | Aggregates only. **Out of audit perimeter.** |
| Existing tables (negative space) | — | — | None of `LoanApplicationDocumentAccessAudit`, `LoanApplicationPiiRevealAudit`, `AppUserAuditEvent`, `ApiClientAuditEvent` can host this event — all are FK'd to a single parent entity. |
| `GeneratedReport` | `AdminReportingService.java:501-506` | — | `(fileName, mediaType, byte[] content)`. **No row count surfaced** (service knows `rows.size()` internally but discards it). `byte_count = content.length` is trivially recoverable. |
| `ReportRequest` | `domain/ReportRequest.java` | — | Has actor, lspId, dateRange, status, completedAt, fileName, mediaType, errorMessage, notification fields. **No row_count column** today; **no download-tracking columns** (downloaded_at, downloaded_by, downloaded_ip). |

**Conclusion of audit:** new dedicated table `report_access_audit` is the only design that fits the data shape. The compliance-critical perimeter is the two **download** endpoints (bytes-out moments); request-submit and preview are out of scope per the grill. `byte_count` is captured this PR; `row_count` requires changes to both `GeneratedReport` and `ReportRequest` that are out of scope here and tracked as a follow-up.

#### Decisions (after grilling, 2026-06-01)

1. **New `report_access_audit` table.** Cross-cutting, purpose-built for bulk-export events. Pushes back on the audit doc's Option 2 because no existing audit table fits the cross-cutting shape.
2. **Audit perimeter: downloads only.** Two action labels: `MIS_CSV_DOWNLOADED` (sync path) and `MIS_REQUEST_DOWNLOADED` (async path). `MIS_REQUEST_CREATED` is explicitly **not** audited — the bytes-out moment is the forensic signal, not the request submission. Preview and summary endpoints write nothing (masked PII + aggregate-only respectively).
3. **`byte_count` is captured; `row_count` is deferred to a follow-up issue.** Today's `GeneratedReport` doesn't carry row count and `ReportRequest` doesn't persist it; surfacing it requires (a) growing `GeneratedReport` return shape and (b) adding a `row_count` column on `ReportRequest` populated at job completion. Both are clean changes but out of this PR's scope to keep it small. Filing a follow-up ticket "augment report_access_audit with row_count" cross-linked here.
4. **No BULK_PII_DOWNLOAD alert rule in this PR.** The audit-trail is the compliance must-have; alert wiring has its own threshold-tuning + noise-budget conversation. Once the audit rows land, adding a count- or volume-based rule to `AlertRuleEvaluationService` is a follow-up ticket.
5. **Schema (`report_access_audit`):**
   - `id UUID PRIMARY KEY`
   - `actor_username TEXT NOT NULL`
   - `actor_ip VARCHAR(64) NULL` — consistent precedent from #70 / #148 / #149
   - `correlation_id VARCHAR(128) NULL`
   - `action VARCHAR(64) NOT NULL` — initial enum values `MIS_CSV_DOWNLOADED`, `MIS_REQUEST_DOWNLOADED` (extensible)
   - `report_type VARCHAR(64) NOT NULL` — initial enum `PORTFOLIO_MIS` (extensible to future report families)
   - `filter_payload JSONB NOT NULL` — `{lspId, disbursalDateFrom, disbursalDateTo}` for sync; mirrored from `ReportRequest` columns for async
   - `byte_count BIGINT NOT NULL`
   - `report_request_id UUID NULL REFERENCES report_request(id)` — NULL for sync, set for async (joins audit row to the request lifecycle)
   - `created_at TIMESTAMP NOT NULL`
   - Indexes: `(actor_username, created_at DESC)` for SOC + future alert query; `(action, created_at DESC)` for filter-by-event-type; `(report_request_id)` for lifecycle join; `(correlation_id)` for request-log join.
6. **Audit timing:** write the row **after** retrieval succeeds, before the controller builds the `ResponseEntity`. Failed downloads (404 on unknown request id, 400 on bad filter, storage failure) write nothing — matches the precedent set by #70 and locks "successes only" semantics.
7. **Controller wiring:** both download endpoints grow `@AuthenticationPrincipal Jwt principal` + `HttpServletRequest request` params. New `ReportAccessAuditService` owns the writes; controller delegates after the underlying service returns bytes.
8. **No FE changes.** Audit Explorer (#159) currently covers 4 streams; `report_access_audit` becomes the natural 5th — tracked under #159 cross-link.

#### TDD plan (vertical slices, one RED → GREEN at a time)

Tests are integration-style through MockMvc against a real DB. Verification reads the new `report_access_audit` table via its JPA repository — the same read interface the Audit Explorer will wrap when #159 picks it up. No mocking of internal collaborators.

**Slice 1 — Sync CSV download writes MIS_CSV_DOWNLOADED with full payload**
- RED: New test class `ReportAdminControllerDownloadAuditTest`. Seed: one disbursed loan in one LSP. Authenticate as SYSTEM_ADMIN. `GET /api/v1/internal/reports/portfolio-mis?lspId={lspId}&disbursalDateFrom=2026-01-01&disbursalDateTo=2026-12-31`. Assert HTTP 200 + `text/csv` body. Then query `report_access_audit` ordered by `created_at DESC LIMIT 1` and assert:
  - `action = 'MIS_CSV_DOWNLOADED'`
  - `report_type = 'PORTFOLIO_MIS'`
  - `actor_username = <admin>`
  - `actor_ip = <request remote addr>`
  - `correlation_id = <MDC value>`
  - `byte_count = <response body length>`
  - `filter_payload->>'lspId' = <lspId>`
  - `filter_payload->>'disbursalDateFrom' = '2026-01-01'`
  - `filter_payload->>'disbursalDateTo' = '2026-12-31'`
  - `report_request_id IS NULL` (sync path)
  
  Initially fails to compile (no entity, no repo, no service, no audit table). Then RED — no write.
- GREEN (compile-cascade):
  1. Migration `V{n+1}__report_access_audit.sql` (table + four indexes).
  2. Entity `ReportAccessAudit` + enum `ReportAccessAuditAction` (`MIS_CSV_DOWNLOADED`, `MIS_REQUEST_DOWNLOADED`) + enum `ReportAccessAuditReportType` (`PORTFOLIO_MIS`).
  3. Repository `ReportAccessAuditRepository extends JpaRepository<ReportAccessAudit, UUID>`.
  4. Service `ReportAccessAuditService` with `recordMisCsvDownloaded(actorUsername, actorIp, correlationId, filterPayload, byteCount)` and `recordMisRequestDownloaded(actorUsername, actorIp, correlationId, reportRequest, byteCount)`.
  5. `ReportAdminController.downloadPortfolioMisReport` grows `@AuthenticationPrincipal Jwt principal` + `HttpServletRequest request`; after `generatePortfolioMisCsv(...)` returns, build filter map, call `reportAccessAuditService.recordMisCsvDownloaded(...)`, then build `ResponseEntity`.
  
  Test passes.
- Refactor: extract a `buildFilterPayload(lspId, from, to)` helper if the same shape is built in the async path.

**Slice 2 — Async result download writes MIS_REQUEST_DOWNLOADED with report_request_id + mirrored filter**
- RED: Seed a `COMPLETED` `ReportRequest` with `lspId`, date range, stored bytes. SYSTEM_ADMIN `GET /api/v1/internal/reports/requests/{id}/download`. Assert HTTP 200 + bytes. Query latest audit row; assert:
  - `action = 'MIS_REQUEST_DOWNLOADED'`
  - `report_request_id = <requestId>`
  - `filter_payload->>'lspId'` matches the request's lspId
  - `filter_payload->>'disbursalDateFrom'` and `'disbursalDateTo'` match the request
  - `byte_count` matches response body length
- GREEN: thread `principal` + `request` into `ReportAdminController.downloadGeneratedReport`; after `getCompletedReport(...)` returns, build filter payload from the `ReportRequest` row (already loaded by the service or fetched by id), call `reportAccessAuditService.recordMisRequestDownloaded(...)`.
- Refactor: if the service returns only bytes, expose a small ancillary method `ReportRequestService.getCompletedReportWithMetadata(requestId)` returning bytes + the underlying `ReportRequest`, so the controller doesn't re-fetch by id. Avoid double-fetch.

**Slice 3 — 400/404 paths leave the audit table untouched**
- RED: Pre-count audit rows. (a) Sync: `GET /portfolio-mis?lspId=<unknownUuid>` → assert 400 (per `validateFilters`). (b) Async: `GET /requests/<randomUuid>/download` → assert 404 (or 400 per current behaviour). Re-count audit rows after both; assert delta is 0.
- GREEN: should pass immediately — exceptions thrown by the underlying services prevent reaching the audit-write step. Slice locks in "successes only".
- Refactor: none.

**Slice 4 — Preview, summary, list paths write nothing (negative perimeter assertion)**
- RED: Pre-count audit rows. `GET /portfolio-mis/preview?...`, `GET /portfolio-mis/summary?...`, `GET /requests`. All return 200 with non-PII or masked-PII bodies. Re-count audit rows after; assert delta is 0.
- GREEN: passes immediately — those handlers never call the audit service. Slice locks in the perimeter decision.
- Refactor: none.

**Slice 5 — byte_count matches response body length exactly**
- RED: Sync CSV download. Assert `audit.byte_count == response.body.length` byte-for-byte. (Catches the bug where Content-Length header is captured instead of actual content bytes, or where encoding changes the byte count.)
- GREEN: should pass — `byteCount = generatedReport.content().length` is the only call site. Slice is a regression guard.
- Refactor: none.

**Order:** Slice 1 carries the compile-cascade (migration + entity + repo + service + sync wiring). Slices 2–5 are additive assertions on top.

**Anti-pattern check (per the TDD prompt):** none of these tests mock `ReportAccessAuditService`, `ReportAccessAuditRepository`, `AdminReportingService`, or `ReportRequestService`. All verification goes through HTTP boundary (MockMvc) + JPA read of the audit table. Tests would survive moving the audit write into a Spring event listener — assertion is on the row.

#### Files touched (final list)

Add:
- `backend/src/main/resources/db/migration/V{n+1}__report_access_audit.sql` (table + 4 indexes).
- `backend/src/main/java/com/bhawana/lms/domain/ReportAccessAudit.java`
- `backend/src/main/java/com/bhawana/lms/domain/ReportAccessAuditAction.java` (`MIS_CSV_DOWNLOADED`, `MIS_REQUEST_DOWNLOADED`)
- `backend/src/main/java/com/bhawana/lms/domain/ReportAccessAuditReportType.java` (`PORTFOLIO_MIS`)
- `backend/src/main/java/com/bhawana/lms/repo/ReportAccessAuditRepository.java`
- `backend/src/main/java/com/bhawana/lms/service/ReportAccessAuditService.java` (single collaborator owning both writes)
- `backend/src/test/java/com/bhawana/lms/web/ReportAdminControllerDownloadAuditTest.java` (five slice tests)

Edit:
- `backend/src/main/java/com/bhawana/lms/web/ReportAdminController.java` — extract actor + IP at both download endpoints; delegate to `ReportAccessAuditService`.
- `backend/src/main/java/com/bhawana/lms/service/ReportRequestService.java` — surface a `getCompletedReportWithMetadata(requestId)` returning bytes + `ReportRequest`, so the controller's audit write doesn't double-fetch. (If a clean refactor isn't possible, the controller can re-load by id — pay one extra read; functionally equivalent.)

Untouched (deliberately):
- `GeneratedReport` return shape — no `rowCount` addition in this PR (deferred follow-up).
- `ReportRequest` schema — no `row_count`/`downloaded_at`/`downloaded_by` columns in this PR (the audit table is the cross-cutting record).
- `AlertRuleEvaluationService` — no `BULK_PII_DOWNLOAD` rule in this PR (deferred follow-up).
- Preview / summary / list endpoints — out of perimeter.
- Audit Explorer — wiring `report_access_audit` as a 5th stream is left to #159.

#### Effect on app

- Every bulk MIS CSV download (sync) and every generated report download (async) writes one row to `report_access_audit` with actor, IP, correlation, action, report type, filter payload, byte count, and (for async) report_request_id.
- SOC can run `SELECT actor_username, COUNT(*), SUM(byte_count) FROM report_access_audit WHERE created_at > NOW() - INTERVAL '1 day' GROUP BY actor_username ORDER BY 3 DESC` to spot bulk-exfil patterns.
- `report_request_id` ties the audit row to the request lifecycle for async exports — useful for incident timelines that need to trace "request submitted → generated → downloaded → notification emailed".
- 400/404 paths are forensically silent (Slice 3 regression-guards).
- Preview / summary / list paths are deliberately silent (Slice 4 regression-guards the perimeter).
- Tiny per-download overhead: one DB insert.
- No user-visible UI change. Audit Explorer surfaces the stream once #159 lands.

#### Cluster impact

- **#157** (`[R-2]` MIS preview masks but CSV download leaks raw PAN — closed as dup of #69) — independent. #69 owns the **masking**; #151 owns the **audit trail**. A masked CSV still benefits from a download audit; an unmasked CSV exfil event with the audit row becomes a high-priority alert later.
- **#69** (MIS CSV emits raw PAN/bank while preview is masked) — orthogonal. This PR doesn't change masking behaviour; it audits what gets downloaded regardless.
- **#159** ([R-4] Audit Explorer only covers 4 streams) — `report_access_audit` is the natural 5th stream. PR description cross-links to #159; wiring is left to that ticket.
- **#155** ([AUD-9] failed-auth lockout/alert pipeline) — orthogonal. Different audit table, different event family.
- **#62 PR (c)** (`LSP_BOUND_VIOLATION` alert) — sibling alert-rule work. `BULK_PII_DOWNLOAD` follows the same plumbing pattern but is filed as its own follow-up ticket, not bundled here.
- **Follow-up ticket: row_count + alert rule.** File two tickets in the PR description: (i) "augment report_access_audit with row_count" (touches GeneratedReport + ReportRequest), (ii) "add BULK_PII_DOWNLOAD alert rule" (thresholds + ops-comm). Both depend on this PR but are independent of each other.

#### Dependencies / sequencing

- Independent of all other AUD-class work; can ship in any order within the AUD-class PR train.
- No prerequisite on #71, #148, or #149.
- Follow-up tickets (row_count, alert rule) are non-blocking and depend on this PR.

**Implementation status — CLOSED (2026-06-04):**

| Slice | Delivered | Primary code |
|-------|-----------|--------------|
| Write path | `report_access_audit` (V87); `MIS_CSV_DOWNLOADED` / `MIS_REQUEST_DOWNLOADED` on successful sync + async downloads only | `ReportAccessAuditService`, `ReportAdminController`, `ReportRequestService.getCompletedReportDownload()` |
| Tests | Five integration slices + `ReportAdminControllerTest` cleanup | `ReportAdminControllerDownloadAuditTest` |
| Explorer (was #159 follow-up) | 8th stream `REPORT_ACCESS` on unified audit API + `/audit` UI tab | `AuditExplorerRepository`, `AuditExplorerService`, `frontend-2/src/features/audit/` |

- Preview / summary / list / failed downloads remain audit-silent (perimeter tests green).
- **Deferred:** `row_count` on audit rows; `BULK_PII_DOWNLOAD` alert rule; `auth_event_audit` as a 9th Explorer stream (#71).

---

### #152 — [AUD-6] Mock-outcome disbursement endpoint not audited
**Labels:** auditability, mocked-flow · **Link:** https://github.com/sid12701/lms/issues/152 · **Status:** **CLOSED** — [PR #174](https://github.com/sid12701/lms/pull/174) (2026-06-02)

**Problem (plain English):** The mock-outcome endpoint (which after #61 lives in prod by design until provider approval) writes no audit. Fabricated outcomes go untraced. ALSO — the audit rows already being written by #148 / #149 are not visible via any live read endpoint because the Audit Explorer covers only 4 of the 6+ audit tables.

**Possible fixes:**
1. **Audit hook in the controller** — straightforward.

**Recommended:** Option 1. Even in test, you want the trace.

**Effect on app:** Test/local audit trail; informs operators if mock invocations leak into the wrong env.

**Detailed solution after discussion (2026-06-01):**

#### Reframe — the issue grew from "audit one endpoint" to "make audit visible end-to-end via live endpoints"

A pivot during grilling: the user wants **end-to-end audit using live endpoints, no mock endpoints**. A surface-area audit before deciding revealed that the live audit pipeline today reads from only 4 of the existing 6+ audit tables — meaning the rows we're adding under #148 (admin password reset) and #149 (API-client create/disable) get written but **cannot be queried via any production-callable endpoint**. The original #152 framing (audit one endpoint) misses the bigger gap.

#### Audit of linked surface area (done before deciding)

| Surface | File:Line | State |
|---|---|---|
| Unified Audit Explorer | `web/AuditExplorerController.java:30-75` (`GET /api/v1/internal/admin/audit-events`) | SYSTEM_ADMIN. UNION ALL over **4 streams**: `APPLICATION`, `INTAKE`, `DOCUMENT_ACCESS`, `PRODUCT`. Server-side filters (actorUsername, lspId, applicationId, borrowerId, productId, since, until, paginationDetails). |
| `AuditStream` enum | `service/AuditExplorerQuery.java` | 4 values today. Adding new streams requires (a) enum value, (b) a `SELECT … cast(... as varchar(N))` branch in `AuditExplorerRepository`'s UNION ALL, (c) FE type + filter chip. |
| Per-app drill-downs | `web/LoanApplicationOpsController.java:124-152` | Three GETs: intake-audits, audit-events, document-access-audits — all scoped to one applicationId. SYSTEM_ADMIN + OPS_USER. Already cover the relevant slice for one loan; not a substitute for cross-cutting search. |
| `app_user_audit_event` | `domain/AppUserAuditEvent.java` | **Written by `updateUser` today and by `resetUserPassword` after #148; not in any AuditStream value; no live read path.** Forensically blind from the live UI. |
| `api_client_audit_event` | `domain/ApiClientAuditEvent.java` | **Written today (CLIENT_UPDATED, SECRET_ROTATED) and by #149's new writes (CLIENT_CREATED, CLIENT_DISABLED, CLIENT_ENABLED); not in any AuditStream value; no live read path.** |
| Mock-outcome path | `LoanApplicationService.resolveMockDisbursementOutcome` (line 757) + `LoanApplicationOpsController.applyMockDisbursementOutcome` (line 385) | Writes (a) `LoanDisbursementRequestLog.outcome_response_json` carrying actor + outcome serialized; (b) `LoanApplicationAuditEvent` row with `action=STATUS_TRANSITION`, actor, fromStatus, toStatus, correlationId. **The STATUS_TRANSITION row IS visible in the Audit Explorer today** — but it doesn't distinguish "/mock-outcome endpoint origin" from "future real-provider callback origin" and doesn't capture actor_ip. |
| `LoanApplicationAuditAction` enum | `domain/LoanApplicationAuditAction.java` | `STATUS_TRANSITION`, `MANUAL_STATUS_OVERRIDE`, `INVALIDATED`, `FORECLOSURE_EXECUTED`, `PAYMENT_RECORDED`. No first-class disbursement-outcome label. |
| FE Audit Explorer | `frontend-2/src/features/audit/api.ts` + `features/audit/page.tsx` + `components/app/audit/AuditEventNode.tsx` | Hits the live endpoint **only for SYSTEM_ADMIN sessions** (no 4xx→mock fallback on internal sessions since **#78 / PR #171**). Non-admin sessions use role gate + `EmptyState`, not fake audit data. Hard-codes `BACKEND_STREAMS` to the same 4 values. Subject-projection logic (`subjectFor`) handles only `LOAN_PRODUCT` and `LOAN_APPLICATION`. |
| `loan_application_pii_reveal_audit` | `domain/LoanApplicationPiiRevealAudit.java` | Per FE comment: "Gap #1 retired the reveal endpoint; the underlying table is forensic-only and no longer surfaced." Write-only **by intent** — stays out of the explorer. |
| `loan_disbursement_request_log` | `domain/LoanDisbursementRequestLog.java` | Carries provider_name + outcome_response_json. **Not an audit table by design** — it's a request log. Data the operator cares about is captured but it's not the right home for the disbursement audit stream. |

**Conclusion of audit:** the original "audit one endpoint" framing under-scopes the real gap. The mock-outcome path is partially audited via STATUS_TRANSITION; the bigger problem is that the audit fabric the live UI exposes is incomplete. Expanding the Audit Explorer to cover the existing-but-unsurfaced tables is the high-leverage move. The dedicated disbursement-outcome audit is the secondary, narrower fix.

#### Decisions (after grilling, 2026-06-01)

1. **Expand AuditStream from 4 → 7 streams.** Existing: `APPLICATION`, `INTAKE`, `DOCUMENT_ACCESS`, `PRODUCT`. New: `APP_USER`, `API_CLIENT`, `DISBURSEMENT`.
2. **New dedicated `disbursement_outcome_audit` table** as the home for the DISBURSEMENT stream — chosen over reusing `loan_application_audit_event` because the new table becomes the long-term home for both today's mock-outcome events and tomorrow's real-provider callbacks (when #61's adapter swap lands).
3. **Schema (`disbursement_outcome_audit`):**
   - `id UUID PRIMARY KEY`
   - `loan_application_id UUID NOT NULL REFERENCES loan_application(id)`
   - `loan_account_id UUID NOT NULL REFERENCES loan_account(id)`
   - `actor_username TEXT NOT NULL`
   - `actor_ip VARCHAR(64) NULL`
   - `correlation_id VARCHAR(128) NULL`
   - `source VARCHAR(32) NOT NULL` — enum `MOCK_OUTCOME_ENDPOINT` (today); `REAL_PROVIDER_CALLBACK` (reserved for #61's adapter swap)
   - `outcome VARCHAR(32) NOT NULL` — enum `DISBURSED`, `FAILED`, `PENDING_RECONCILIATION`
   - `provider_request_id VARCHAR(128) NULL` — links to `loan_disbursement_request_log.provider_request_id` for forensic join
   - `created_at TIMESTAMP NOT NULL`
   - Indexes: `(loan_application_id, created_at DESC)` per-app drill-down; `(actor_username, created_at DESC)` SOC; `(source, created_at DESC)` mock-vs-real split; `(correlation_id)` request-log join.
4. **PR slicing (locked in during grill):**
   - **PR (a):** Wire `APP_USER` + `API_CLIENT` streams into the unified Audit Explorer. Purely additive read paths over existing tables. Closes the #148/#149 visibility gap. No new tables; no new writes.
   - **PR (b):** Add `disbursement_outcome_audit` table + writes from `resolveMockDisbursementOutcome` + `DISBURSEMENT` stream wiring. Touches the /mock-outcome path. New migration.
5. **The existing STATUS_TRANSITION row stays.** `/mock-outcome` continues to call `loanApplicationLifecycleService.updateApplicationStatus(...)` exactly as today. PR (b) adds ONE additional row (to `disbursement_outcome_audit`) per call — does not replace or modify the existing audit write.
6. **The Audit Explorer's projection shape grows.** `BackendUnifiedAuditEvent` needs a path to express the new subject types. Cleanest: add a generic `subjectType` + `subjectId` on the backend projection (BE already projects this implicitly via `productId` vs `loanApplicationId`; the FE's `subjectFor` already encodes the rule). Extend `subjectFor` in `frontend-2/src/features/audit/api.ts` with two new branches (`APP_USER` → user id, `API_CLIENT` → client id). No schema change to the unified projection envelope — `appUserId` and `apiClientId` are not added as first-class envelope fields; instead, the existing `borrowerId` + `lspId` + `productId` + `loanApplicationId` slots remain, and APP_USER / API_CLIENT rows surface their subject id in `detail` JSON.
7. **No new write semantics for #148/#149's audit rows under PR (a).** PR (a) only adds *read* projections over the existing tables. The action/eventType derivation pulls from existing columns (`api_client_audit_event.action` for API_CLIENT) or from after-state JSON (`app_user_audit_event.after_state_json->>'eventType'`, defaulting to `USER_UPDATED` when absent).
8. **Actor IP on the existing STATUS_TRANSITION rows is OUT OF SCOPE.** `loan_application_audit_event` does not gain `actor_ip` in this PR — that touches every status-transition audit row in the system and is its own conversation. The new `disbursement_outcome_audit` table captures actor_ip; that's the new disbursement audit's home. A separate ticket can later backfill IP on the existing audit table if needed.
9. ~~**MOCK_FALLBACK paths on the audit page are not removed here.**~~ **Done under #78 (PR #171, 2026-06-02)** — internal-session 4xx no longer falls through to mock data; audit page shows `ErrorState` on load failure. #152 PRs (a)/(b) did not need to carry that scope.
10. **The /mock-outcome endpoint is NOT moved behind a profile guard.** Per #61's grilled resolution, the mock stays in prod by design until provider approval. This PR adds the audit row regardless of profile — the audit fires in every environment that hits /mock-outcome.

#### TDD plan — PR (a): APP_USER + API_CLIENT streams

Tests are integration-style through MockMvc against a real DB. No mocking of `AuditExplorerService` or repositories.

**Slice 1 — APP_USER stream surfaces an `app_user_audit_event` row**
- RED: Seed an `AppUser`; invoke `updateUser` as SYSTEM_ADMIN (writes an existing `app_user_audit_event` row). GET `/api/v1/internal/admin/audit-events?streams=APP_USER&actorUsername=<admin>` and assert one row with `stream='APP_USER'`, `actorUsername=<admin>`, `action='USER_UPDATED'`, `correlationId` populated.
- GREEN:
  1. Add `APP_USER` to `AuditStream` enum.
  2. Add a UNION ALL branch in `AuditExplorerRepository` projecting `app_user_audit_event` columns into the unified shape: `(id, 'APP_USER' as stream, created_at as occurredAt, actor_username, NULL as loanApplicationId, NULL as borrowerId, NULL as lspId, NULL as productId, COALESCE(after_state_json->>'eventType', 'USER_UPDATED') as action, build summary, after_state_json as detail, correlation_id)`.
  3. Add cast widths per existing pattern (256 / 512 / etc.) so H2 + PG produce matching shapes.
- Refactor: extract a small `buildAppUserSummary(before, after)` helper that produces a one-liner like "Updated email of user <username>" or "Password reset for user <username>" depending on eventType.

**Slice 2 — APP_USER row picks up `PASSWORD_RESET_BY_ADMIN` action label (locks in #148 integration)**
- RED: Seed user; call `resetUserPassword` (via the controller path) so the after-state JSON carries `eventType=PASSWORD_RESET_BY_ADMIN`. Query the explorer; assert row's `action='PASSWORD_RESET_BY_ADMIN'`.
- GREEN: passes once the Slice 1 projection uses `COALESCE(after_state_json->>'eventType', 'USER_UPDATED')`. This slice asserts integration with #148's payload shape.
- Refactor: none.

**Slice 3 — API_CLIENT stream surfaces an `api_client_audit_event` row**
- RED: Seed an LSP; invoke `createClient` (or use existing CLIENT_UPDATED write). GET with `streams=API_CLIENT`; assert one row with `stream='API_CLIENT'`, `action='CLIENT_UPDATED'` (or `CLIENT_CREATED` after #149 lands), `correlationId` populated, `lspId` populated.
- GREEN:
  1. Add `API_CLIENT` to `AuditStream` enum.
  2. UNION ALL branch projecting `api_client_audit_event`: `(id, 'API_CLIENT', created_at, actor_username, NULL, NULL, api_client.lsp_id as lspId, NULL, action, build summary, details_json, correlation_id)` — JOIN via `api_client` to get lsp_id.
- Refactor: extract `buildApiClientSummary(action, clientId, name)` helper.

**Slice 4 — Filtering by `streams=APP_USER,API_CLIENT` returns rows from both, excludes others**
- RED: Seed rows in `app_user_audit_event`, `api_client_audit_event`, AND `loan_application_audit_event`. Query with `streams=APP_USER,API_CLIENT`; assert NO `APPLICATION` stream rows in the response.
- GREEN: should pass once stream filtering already plumbed through `AuditExplorerQuery.requestedStreams` is honored by the new UNION branches.
- Refactor: none.

**Slice 5 — FE wiring renders the new streams with subject linking**
- RED: vitest/RTL test in `features/audit/page.test.tsx` (or new file). Render the page with a mocked `requestJson` response containing one APP_USER + one API_CLIENT row. Assert the table renders both with the correct stream label and that clicking the subject opens the appropriate detail.
- GREEN: 
  1. Add `APP_USER` and `API_CLIENT` to FE `AuditStream` type.
  2. Add to `BACKEND_STREAMS` set so they pass to the BE.
  3. Extend `subjectFor` with two new branches.
  4. Add filter chips in `AuditFilterBar`.
- Refactor: none.

**Slice 6 — RBAC: non-SYSTEM_ADMIN cannot read APP_USER / API_CLIENT rows**
- RED: as OPS_USER, GET the explorer; expect 403 (since the controller already class-level-restricts to SYSTEM_ADMIN). Negative regression guard locking down the wider visibility.
- GREEN: should pass — the controller already enforces SYSTEM_ADMIN at the class level. Slice locks it in.
- Refactor: none.

#### TDD plan — PR (b): DISBURSEMENT stream + /mock-outcome write

**Slice 1 — /mock-outcome writes one `disbursement_outcome_audit` row with full payload**
- RED: Seed a loan in `DISBURSEMENT_REQUESTED`. SYSTEM_ADMIN POST `/api/v1/internal/ops/loan-applications/{id}/disbursement-requests/mock-outcome` with `outcome=DISBURSED`. Assert HTTP 200. Query `disbursement_outcome_audit`; assert one row:
  - `loan_application_id = <id>`
  - `loan_account_id = <id>`
  - `actor_username = <admin>`
  - `actor_ip = <request remote addr>`
  - `correlation_id = <MDC value>`
  - `source = 'MOCK_OUTCOME_ENDPOINT'`
  - `outcome = 'DISBURSED'`
  - `provider_request_id` matches the latest `loan_disbursement_request_log.provider_request_id`
- GREEN:
  1. Migration `V{n+1}__disbursement_outcome_audit.sql` (table + 4 indexes).
  2. Entity `DisbursementOutcomeAudit` + enums `DisbursementOutcomeAuditSource`, `DisbursementOutcomeAuditOutcome`.
  3. Repository `DisbursementOutcomeAuditRepository`.
  4. Service `DisbursementOutcomeAuditService.recordMockOutcomeApplied(...)`.
  5. `resolveMockDisbursementOutcome` signature grows by `actorIp, correlationId` params; calls the audit service after the existing STATUS_TRANSITION write succeeds.
  6. `LoanApplicationOpsController.applyMockDisbursementOutcome` extracts `HttpServletRequest` for `getRemoteAddr()` + grabs `CorrelationIdHolder.get()` (already in MDC); passes through.
- Refactor: none.

**Slice 2 — Existing STATUS_TRANSITION row is still written; both rows present**
- RED: After Slice 1's call, query both `loan_application_audit_event` (filtered to the new loanId) AND `disbursement_outcome_audit`. Assert both have exactly one row each — proves we ADDED an event, didn't REPLACE.
- GREEN: passes immediately — the lifecycle service call is untouched.
- Refactor: none.

**Slice 3 — Each outcome value writes the correct row**
- RED: Three parameterized tests — `outcome=DISBURSED`, `outcome=FAILED`, `outcome=PENDING_RECONCILIATION`. Each writes one `disbursement_outcome_audit` row with the matching outcome enum.
- GREEN: passes after Slice 1 (the outcome param is plumbed through unmodified).
- Refactor: none.

**Slice 4 — DISBURSEMENT stream surfaces the row in the live Audit Explorer**
- RED: After Slice 1, GET `/api/v1/internal/admin/audit-events?streams=DISBURSEMENT`. Assert one row with `stream='DISBURSEMENT'`, `action='MOCK_OUTCOME_DISBURSED'`, `loanApplicationId=<id>`, `actorUsername=<admin>`.
- GREEN:
  1. Add `DISBURSEMENT` to `AuditStream` enum.
  2. UNION ALL branch projecting `disbursement_outcome_audit`: `(id, 'DISBURSEMENT', created_at, actor_username, loan_application_id, NULL, NULL, NULL, source||'_'||outcome as action, build summary, jsonb_build_object(...), correlation_id)`.
- Refactor: extract `buildDisbursementOutcomeSummary(source, outcome, appId)`.

**Slice 5 — FE renders DISBURSEMENT stream rows with correct subject (LOAN_APPLICATION)**
- RED: vitest/RTL with one DISBURSEMENT row in the mocked response. Assert table renders with stream='DISBURSEMENT' badge; subject links to the loan-detail page.
- GREEN:
  1. Add `DISBURSEMENT` to FE `AuditStream` type + `BACKEND_STREAMS`.
  2. Add filter chip.
  3. `subjectFor` already returns `LOAN_APPLICATION` whenever `loanApplicationId` is set — no change needed there.
- Refactor: none.

**Slice 6 — 400 path (mock-outcome called when loan is not in DISBURSEMENT_REQUESTED) leaves audit table untouched**
- RED: Seed a loan in `AWAITING_APPROVAL`. POST /mock-outcome; expect 400 from the existing validation. Pre-count and post-count `disbursement_outcome_audit` rows; assert delta 0.
- GREEN: passes — `IllegalArgumentException` thrown before the audit write.
- Refactor: none.

**Slice 7 — Per-application drill-down endpoint exposes the new audit (optional, low-priority)**
- RED: GET `/api/v1/internal/ops/loan-applications/{id}/audit-events` for the loan from Slice 1. The current endpoint reads `loan_application_audit_event`. **Out of scope here unless the user wants the per-app endpoint to also surface disbursement-outcome rows** — recommend leaving the per-app endpoint untouched (#152's job is the cross-cutting Audit Explorer, not the per-app drill-down). Slice is a marker; no test written.
- GREEN: not implemented in this PR. Tracked as a follow-up if per-app surface needs the disbursement-outcome view.

**Order:** PR (a) Slice 1 → 2 → 3 → 4 → 5 → 6. PR (b) Slice 1 → 2 → 3 → 4 → 5 → 6.

**Anti-pattern check (per the TDD prompt):** none of these tests mock `AuditExplorerService`, `DisbursementOutcomeAuditService`, or the repositories. PR (a) verifies through the LIVE Audit Explorer endpoint; PR (b) verifies both through the audit table directly (single-write assertion) AND through the LIVE Audit Explorer (cross-cutting visibility). Tests survive moving the audit write into a Spring event listener or behind a service facade — assertions are on the row + the HTTP response shape.

#### Files touched (final list)

**PR (a) — APP_USER + API_CLIENT streams:**

Edit:
- `backend/src/main/java/com/bhawana/lms/service/AuditExplorerQuery.java` — add `APP_USER`, `API_CLIENT` enum values; update `ALL_STREAMS`.
- `backend/src/main/java/com/bhawana/lms/repo/AuditExplorerRepository.java` (or its impl) — add UNION ALL branches projecting `app_user_audit_event` and `api_client_audit_event` into the unified shape; preserve cast widths.
- `backend/src/main/java/com/bhawana/lms/service/AuditExplorerService.java` — extend the action/summary derivation if it lives here; otherwise no change.
- `frontend-2/src/features/audit/types.ts` — extend `AuditStream` and `AuditSubjectType` unions.
- `frontend-2/src/features/audit/api.ts` — extend `BACKEND_STREAMS`; extend `subjectFor`.
- `frontend-2/src/features/audit/components/AuditFilterBar.tsx` — add filter chips for the two new streams.
- `frontend-2/src/components/app/audit/AuditEventNode.tsx` — add display labels / icons for the two new streams.

Add:
- `backend/src/test/java/com/bhawana/lms/web/AuditExplorerControllerAppUserStreamTest.java`
- `backend/src/test/java/com/bhawana/lms/web/AuditExplorerControllerApiClientStreamTest.java`
- `frontend-2/src/features/audit/page.test.tsx` — extend or add cases for the new streams.

**PR (b) — DISBURSEMENT stream + new audit table + /mock-outcome write:**

Add:
- `backend/src/main/resources/db/migration/V{n+2}__disbursement_outcome_audit.sql` (table + 4 indexes).
- `backend/src/main/java/com/bhawana/lms/domain/DisbursementOutcomeAudit.java`
- `backend/src/main/java/com/bhawana/lms/domain/DisbursementOutcomeAuditSource.java` (`MOCK_OUTCOME_ENDPOINT`, `REAL_PROVIDER_CALLBACK`)
- `backend/src/main/java/com/bhawana/lms/domain/DisbursementOutcomeAuditOutcome.java` (`DISBURSED`, `FAILED`, `PENDING_RECONCILIATION`)
- `backend/src/main/java/com/bhawana/lms/repo/DisbursementOutcomeAuditRepository.java`
- `backend/src/main/java/com/bhawana/lms/service/DisbursementOutcomeAuditService.java`
- `backend/src/test/java/com/bhawana/lms/service/LoanApplicationServiceMockOutcomeAuditTest.java`
- `backend/src/test/java/com/bhawana/lms/web/AuditExplorerControllerDisbursementStreamTest.java`

Edit:
- `backend/src/main/java/com/bhawana/lms/service/LoanApplicationService.java` — `resolveMockDisbursementOutcome` signature grows by `actorIp`, `correlationId`; after the existing STATUS_TRANSITION write, call `disbursementOutcomeAuditService.recordMockOutcomeApplied(...)`.
- `backend/src/main/java/com/bhawana/lms/web/LoanApplicationOpsController.java` — `applyMockDisbursementOutcome` accepts `HttpServletRequest`; extracts IP + correlation; passes through.
- `backend/src/main/java/com/bhawana/lms/service/AuditExplorerQuery.java` — add `DISBURSEMENT`.
- `backend/src/main/java/com/bhawana/lms/repo/AuditExplorerRepository.java` — add UNION ALL branch.
- `frontend-2/src/features/audit/types.ts` — extend `AuditStream`.
- `frontend-2/src/features/audit/api.ts` — extend `BACKEND_STREAMS`.
- `frontend-2/src/features/audit/components/AuditFilterBar.tsx` — add chip.
- `frontend-2/src/components/app/audit/AuditEventNode.tsx` — add label / icon.

Untouched (deliberately):
- `loan_application_audit_event` schema — no `actor_ip` added. Future ticket if needed.
- `loan_disbursement_request_log` — stays as the request log. The new audit table joins to it by `provider_request_id` for forensic correlation; the log itself is not re-surfaced.
- `loan_application_pii_reveal_audit` — write-only by intent; stays out of the explorer.
- /mock-outcome endpoint profile-guarding — per #61's grilled resolution; mock stays in prod.
- ~~MOCK_FALLBACK paths in `audit/api.ts`~~ — **removed (#78, PR #171).**

#### Effect on app

**After PR (a):**
- Audit Explorer surfaces user-mutation and API-client-mutation events end-to-end. `PASSWORD_RESET_BY_ADMIN` (#148), `CLIENT_CREATED` / `CLIENT_DISABLED` (#149), and existing `USER_UPDATED` / `SECRET_ROTATED` are queryable, filterable by actor and time-range, and clickable from the live UI.
- SOC can run "show all privileged user/api-client mutations in the last 24 hours from non-corp IPs" without going to the DB.
- Tiny per-query overhead — two extra UNION branches in the existing search.

**After PR (b):**
- Every /mock-outcome call writes one `disbursement_outcome_audit` row with actor, IP, correlation, source, outcome, provider_request_id. The existing STATUS_TRANSITION row continues to be written (we add a new event, we don't replace one).
- Audit Explorer's new DISBURSEMENT stream surfaces these rows. SOC can filter by `source=MOCK_OUTCOME_ENDPOINT` to see all fabricated outcomes; once #61's adapter swap lands, real-provider callbacks write rows with `source=REAL_PROVIDER_CALLBACK` to the same table — same query, no schema change.
- Per-app drill-down (loan detail page) is untouched in this PR; surfacing disbursement-outcome rows there is a follow-up.

#### Cluster impact

- **#148** (Admin reset-password audit) — PR (a) closes the visibility gap: after #148 lands, the `PASSWORD_RESET_BY_ADMIN` row is readable via the live explorer.
- **#149** (API-client create/rotate/reveal audit) — PR (a) closes the visibility gap: `CLIENT_CREATED` / `CLIENT_DISABLED` / `CLIENT_ENABLED` rows become readable via the live explorer. Filter chips render with the action labels.
- **#159** ([R-4] Audit Explorer only covers 4 streams) — closes as a side-effect of PR (a) + PR (b). Now covers 7 streams. PR description references #159; close it on this PR train.
- **#61** (Mock disbursement adapter wired into production runtime — [REFRAMED 2026-05-31]) — PR (b) adds the audit signal that #61's solution called for via cross-link. When #61's eventual real-provider adapter ships, the new `REAL_PROVIDER_CALLBACK` source value is the same migration's enum and the audit pipeline already handles it.
- **#71** (`auth_event_audit` for login/refresh/etc.) — orthogonal table; not part of #152's expansion. If SOC wants `auth_event_audit` in the explorer too, file a follow-up ticket — natural 8th stream.
- **#155** ([AUD-9] Failed-auth lockout) — orthogonal; uses `auth_event_audit`. Independent of #152.
- **#78** (Frontend MOCK_FALLBACK paths return mock data on 4xx) — **CLOSED** (PR #171, 2026-06-02). #152 shipped before/alongside; audit FE now surfaces real errors for SYSTEM_ADMIN sessions.
- **#62 PR (b)** (worker-driven disbursement, removes LSP /disbursement endpoint) — orthogonal. After #62 PR (b) ships, the worker will eventually invoke /mock-outcome's logic via internal call (or replace it). At that point, the actor on the audit row becomes `SYSTEM` (or the worker's internal-actor token). The audit table accommodates this — `actor_username` is plain text — without schema change.

#### Dependencies / sequencing

- PR (a) is independent of #148 and #149 — they can ship in any order. If #148 / #149 land first, PR (a) immediately surfaces their new rows. If PR (a) lands first, the existing `USER_UPDATED` / `SECRET_ROTATED` / `CLIENT_UPDATED` rows surface; #148 / #149 then add their new action labels on top.
- PR (b) depends on no other ticket (it adds a new write + new read path).
- PR (a) → PR (b) is the natural shipping order: (a) lays the FE filter-chip + AuditStream-expansion plumbing that (b) reuses for the DISBURSEMENT chip. But they can technically ship in parallel — the AuditStream enum, FE type, and filter-chip arrays accept additive enum values without conflict.

**Implementation status — CLOSED (2026-06-02, [PR #174](https://github.com/sid12701/lms/pull/174)):**

Shipped as grilled PR (a) + (b) together with **#159** on one branch (four commits: #148, #149, #152, #159).

| Slice | Delivered | Primary code |
|-------|-----------|--------------|
| (a) | `APP_USER` + `API_CLIENT` UNION branches; stream integration tests | `AuditExplorerRepository.java`, `AuditExplorerController*StreamTest.java` |
| (b) | `disbursement_outcome_audit` (V82); mock-outcome write; `DISBURSEMENT` stream | `DisbursementOutcomeAuditService.java`, `LoanApplicationService.java`, `LoanApplicationOpsControllerMockOutcomeAuditTest.java` |
| Tests | H2 parity for every `AuditStream`; FK-safe teardown (`disbursement_outcome_audit` before `loan_account`) | `AuditExplorerStreamProjectionParityTest.java`, integration `@BeforeEach` cleanup |

Cross-ref § **#159** for frontend stream tabs and out-of-scope 8th-stream checklist.

---

---

### #153 — [AUD-7] Webhook URL / signing-secret rotation not audited
**Labels:** auditability, security · **Link:** https://github.com/sid12701/lms/issues/153 · **Status:** **CLOSED** — [PR #181](https://github.com/sid12701/lms/pull/181) merged 2026-06-07

**Problem (plain English):** Webhook URL changes and secret rotations are not audited. A swapped URL could exfiltrate to an attacker-controlled endpoint silently.

**Possible fixes:**
1. **Audit row on every update; record old/new URL + secret fingerprint diff** — covers the diff.
2. **Audit + email LSP admin contact on URL change** — adds detection by partner.

**Recommended:** Both. URL changes are high-impact; over-notify.

**Effect on app:** Tampering traceable; partner notified of changes.

**Detailed solution after discussion (2026-06-01):**

#### Reframe — the gap is bigger than "webhook isn't audited"

A surface-area audit before deciding revealed that **no `LspAuditEvent` table exists at all today** — there is zero audit fabric for LSP entity mutations. The issue framing focused narrowly on webhook updates; the real gap is the missing LSP audit table. This PR establishes that fabric and writes the webhook-specific events; future tickets layer LSP_CREATED / LSP_DISABLED / LSP_PROFILE_UPDATED on top of the same table.

#### Audit of linked surface area (done before deciding)

| Surface | File:Line | State |
|---|---|---|
| Webhook-subscription endpoint | `web/LspAdminController.java:50-63` (`PUT /api/v1/internal/admin/lsps/{lspId}/webhook-subscription`) | SYSTEM_ADMIN. Accepts `{enabled, endpointUrl, signingSecret, eventTypes[]}` in one body. **No `@AuthenticationPrincipal Jwt`, no `HttpServletRequest`.** Returns full LSP detail including `signingSecret` in plaintext (the #72-deferred concern). |
| Service | `service/AdminDirectoryService.java:191-224` (`updateWebhookSubscription`) | Validates URL prefix + SSRF (`SsrfSafeUrlValidator.validate`), requires secret + eventTypes when enabled, then `lsp.updateWebhookSubscription(...)` + save. **Writes zero audit rows. No actor/IP/correlation in the signature.** |
| **`LspAuditEvent` table** | — | **Does not exist.** Compare: `AppUserAuditEvent` (#148), `ApiClientAuditEvent` (#149), `LoanApplicationAuditEvent`, `LoanProductAuditEvent` all exist. LSP is the only entity-domain without an audit fabric. |
| Lsp entity | `domain/Lsp.java` | Has `webhookEnabled`, `webhookEndpointUrl`, `webhookSigningSecret` (plaintext column), `webhookEventTypes`. **No `primary_contact_email` field** — no LSP-side notification destination. |
| createLsp | `LspAdminController.java:44-48` + `AdminDirectoryService.createLsp` | Also writes zero audit rows. Out of scope for this PR but the new table is shaped to host LSP_CREATED rows when a sibling ticket adds them. |
| `WebhookEventType` enum | `domain/WebhookEventType.java` | Sufficient for capturing event-types diff in details_json. |
| #72 (one-shot reveal of secret) | DEFERRED 2026-06-01 | If/when #72 lands and removes signingSecret from GET responses, this PR's audit-row decisions don't need to change — we never capture the raw secret anyway (per Q3 decision). |
| #152 (AuditStream expansion to 7 streams) | locked in 2026-06-01 | This PR's new table logically becomes an 8th stream `LSP_AUDIT`. Stream wiring depends on #152's PR (a) landing first; can be deferred to a follow-up if scope pressure. |

**Conclusion of audit:** the issue framing under-scoped the gap. Webhook events are forensically silent because the entire LSP audit fabric is missing. This PR establishes the table + writes the webhook-event rows; cluster cleanly into #152's stream-expansion train.

#### Decisions (after grilling, 2026-06-01)

1. **New `lsp_audit_event` table.** Becomes the long-term home for all LSP-entity mutations. This PR writes only the webhook-related action labels; future tickets layer `LSP_CREATED` / `LSP_DISABLED` / `LSP_PROFILE_UPDATED` on top of the same table.
2. **Multiple rows per call — one per security-relevant change.** A single PUT changing URL AND rotating secret AND flipping enabled produces 2–3 audit rows. All share the same `correlation_id` so they're recoverable as one logical event. SOC queries `WHERE action='WEBHOOK_SECRET_ROTATED'` directly without parsing JSON diffs.
3. **No secret fingerprint in audit details.** `WEBHOOK_SECRET_ROTATED` row records only "secret rotated at <time> by <actor>"; no fingerprint, no hash, no prefix. Consistent with #149's decision (raw secret never appears, no fingerprint either). Pushes back on the audit doc's "secret fingerprint diff" recommendation.
4. **Email-notify-LSP-on-URL-change is deferred to a follow-up issue.** LSP entity lacks `primary_contact_email`; adding it pulls in entity + migration + DTO + create-update flow changes that materially expand #153. File a follow-up: "add LSP primary contact email + notify on webhook URL/secret rotation." Audit-trail compliance lands here without it.
5. **Action label set (initial, extensible):**
   - `WEBHOOK_URL_CHANGED` — emitted when before.endpointUrl != after.endpointUrl. Details: `{before: {url: "…"}, after: {url: "…"}}`.
   - `WEBHOOK_SECRET_ROTATED` — emitted when before.signingSecret != after.signingSecret. Details: `{}` (no diff content; row's existence + actor + timestamp is the signal).
   - `WEBHOOK_ENABLED` — emitted on false→true flip. Details: `{after: {enabled: true, url: "…", eventTypes: [...]}}`.
   - `WEBHOOK_DISABLED` — emitted on true→false flip. Details: `{before: {enabled: true, url: "…", eventTypes: [...]}}`.
   - `WEBHOOK_EVENT_TYPES_CHANGED` — emitted when before.eventTypes != after.eventTypes (regardless of whether URL/secret/enabled also changed). Details: `{before: {eventTypes: [...]}, after: {eventTypes: [...]}}`.
   
   A maximally-mutating call (URL + secret + enabled-flip + event-types) emits up to 4 rows. A no-op call (nothing actually differs from current state) emits ZERO rows — the audit table doesn't catch unmoving PUTs.
6. **Schema (`lsp_audit_event`):**
   - `id UUID PRIMARY KEY`
   - `lsp_id UUID NOT NULL REFERENCES lsp(id)`
   - `actor_username TEXT NOT NULL`
   - `actor_ip VARCHAR(64) NULL` — consistent precedent from #70/#148/#149
   - `correlation_id VARCHAR(128) NULL`
   - `action VARCHAR(64) NOT NULL` — initial enum values above; extensible
   - `details_json JSONB NOT NULL`
   - `created_at TIMESTAMP NOT NULL`
   - Indexes: `(lsp_id, created_at DESC)` per-LSP drill-down; `(action, created_at DESC)` SOC filter; `(actor_username, created_at DESC)` actor query; `(correlation_id)` ties multi-row updates together + request-log join.
7. **Service layer: new `LspAuditEventService`.** Single collaborator owning all writes; not folded into `AdminDirectoryService` to avoid further god-classing (see #98 / #99). `AdminDirectoryService.updateWebhookSubscription` grows: captures `before` snapshot, calls `lsp.updateWebhookSubscription(...)`, computes the diff, calls `lspAuditEventService.recordWebhookSubscriptionChanges(...)` which emits the right rows.
8. **Controller plumbing:** `LspAdminController.updateWebhookSubscription` grows `@AuthenticationPrincipal Jwt principal` + `HttpServletRequest request`. Service signature grows by `actorUsername, actorIp, correlationId`.
9. **400 paths (invalid URL, SSRF block, missing secret on enable, missing event-types on enable) leave the audit table untouched.** Matches the precedent set by #148/#149.
10. **AuditStream wiring for `LSP_AUDIT` is deferred to #152's PR train.** Once #152 PR (a) lands (introducing APP_USER + API_CLIENT as new streams), the same pattern extends to add `LSP_AUDIT` as the 8th stream. This PR doesn't block on #152; the audit rows write to the table from day one. PR description cross-links so #152's stream-expansion picks up the new table.
11. **The plaintext `webhookSigningSecret` column is NOT changed in this PR.** That's #72's scope. This PR audits the rotation event independent of how the secret is stored. When #72 lands and converts the column to a hash, the audit row's behaviour is unchanged (we never captured the raw secret).

#### TDD plan (vertical slices, one RED → GREEN at a time)

Tests are integration-style through MockMvc against a real DB. Verification reads `lsp_audit_event` via its JPA repository. No mocking of internal collaborators.

**Slice 1 — URL-only change writes one `WEBHOOK_URL_CHANGED` row with full payload**
- RED: New test class `LspAdminControllerWebhookAuditTest`. Seed: an LSP with webhook enabled, url=`https://a.example.com/hook`, eventTypes=[`LOAN_CREATED`]. SYSTEM_ADMIN PUT `/api/v1/internal/admin/lsps/{lspId}/webhook-subscription` changing url to `https://b.example.com/hook`, secret unchanged, eventTypes unchanged. Assert 200. Query `lsp_audit_event` ordered by `created_at DESC LIMIT 5`; assert exactly ONE new row with:
  - `lsp_id = <lspId>`
  - `action = 'WEBHOOK_URL_CHANGED'`
  - `actor_username = <admin>`
  - `actor_ip = <request remote addr>`
  - `correlation_id = <MDC value>`
  - `details_json->'before'->>'url' = 'https://a.example.com/hook'`
  - `details_json->'after'->>'url' = 'https://b.example.com/hook'`
  
  Initially fails to compile (no table, no entity, no service). Then RED — no rows written.
- GREEN (compile-cascade):
  1. Migration `V{n+1}__lsp_audit_event.sql` (table + 4 indexes).
  2. Entity `LspAuditEvent` + enum `LspAuditEventAction`.
  3. Repository `LspAuditEventRepository extends JpaRepository<LspAuditEvent, UUID>`.
  4. Service `LspAuditEventService` with `recordWebhookSubscriptionChanges(lsp, beforeSnapshot, afterSnapshot, actorUsername, actorIp, correlationId)` that computes the diff and emits the right rows.
  5. `AdminDirectoryService.updateWebhookSubscription`: capture before-snapshot (struct: `{enabled, url, secret, eventTypes}`) — for `secret`, hash internally for diff comparison only; don't expose. After save, build after-snapshot, call `lspAuditEventService.recordWebhookSubscriptionChanges(...)`.
  6. `LspAdminController.updateWebhookSubscription`: add `@AuthenticationPrincipal Jwt principal` + `HttpServletRequest request` params; extract; pass through.
  
  Test passes.
- Refactor: extract a `WebhookSubscriptionSnapshot` record `(enabled, url, secretHash, eventTypes)` to keep the diff-computation tidy.

**Slice 2 — Secret-only rotation writes one `WEBHOOK_SECRET_ROTATED` row with NO secret material**
- RED: Same LSP setup. PUT changing only `signingSecret`; url unchanged, eventTypes unchanged, enabled unchanged. Query latest audit row; assert:
  - `action = 'WEBHOOK_SECRET_ROTATED'`
  - `details_json` does NOT contain the literal old-secret string as a substring.
  - `details_json` does NOT contain the literal new-secret string as a substring.
  - `details_json` does NOT contain any BCrypt/SHA prefix.
- GREEN: emits the row from the existing diff path; `details_json` is intentionally `{}`. Slice locks in the no-secret-material regression guard.
- Refactor: none.

**Slice 3 — Enable flip writes `WEBHOOK_ENABLED` (false→true) and `WEBHOOK_DISABLED` (true→false)**
- RED: Two tests. (a) LSP with `enabled=false`; PUT with `enabled=true` + valid url/secret/eventTypes → assert row with `action='WEBHOOK_ENABLED'`, `details_json->'after'->>'enabled' = 'true'`. (b) LSP with `enabled=true`; PUT with `enabled=false` → assert row with `action='WEBHOOK_DISABLED'`, `details_json->'before'->>'enabled' = 'true'`.
- GREEN: extend the diff logic in `LspAuditEventService` to emit the enabled-flip row.
- Refactor: none.

**Slice 4 — Event-types-only change writes `WEBHOOK_EVENT_TYPES_CHANGED`**
- RED: LSP with eventTypes=[`LOAN_CREATED`]. PUT with eventTypes=[`LOAN_CREATED`, `LOAN_DISBURSED`]; url/secret/enabled unchanged. Assert row `action='WEBHOOK_EVENT_TYPES_CHANGED'`, `details_json->'before'->'eventTypes'` and `'after'->'eventTypes'` populated.
- GREEN: extend diff logic.
- Refactor: none.

**Slice 5 — Multi-field call emits multiple rows sharing one correlation_id**
- RED: LSP enabled with url=A, secret=S, eventTypes=[X]. PUT changing url=B, secret=T, enabled stays true, eventTypes=[X,Y]. Assert THREE rows: `WEBHOOK_URL_CHANGED`, `WEBHOOK_SECRET_ROTATED`, `WEBHOOK_EVENT_TYPES_CHANGED`. Assert all three rows share the same `correlation_id`.
- GREEN: the diff logic already emits all three when each respective field changes; verify the shared correlation_id flows through.
- Refactor: none.

**Slice 6 — No-op PUT writes zero audit rows**
- RED: PUT with the exact current state (no fields differ). Pre-count and post-count `lsp_audit_event` rows; assert delta is 0.
- GREEN: the diff logic emits nothing when all four comparisons return "unchanged." Slice locks in the no-op-silence property.
- Refactor: none.

**Slice 7 — 400 paths leave the audit table untouched**
- RED: Pre-count. Four 400-producing PUTs: (a) `enabled=true` with `endpointUrl=null` → 400; (b) `endpointUrl="ftp://x.example.com"` → 400 (URL-prefix check); (c) `endpointUrl` pointing at SSRF-restricted target (e.g., 127.0.0.1) → 400 from `SsrfSafeUrlValidator`; (d) `enabled=true` with empty `eventTypes` → 400. Re-count after all four; assert delta 0.
- GREEN: passes — `IllegalArgumentException` thrown by validation runs before the audit write. Slice locks in "audit successes only."
- Refactor: none.

**Slice 8 — Audit Explorer wiring (deferred until #152 lands)**
- Marker only. Once `#152 PR (a)` lands and `AuditStream` enum / `AuditExplorerRepository` UNION ALL accepts new streams, a follow-up slice adds `LSP_AUDIT` to the enum + projection. **Not written in this PR.**

**Order:** Slice 1 carries the compile-cascade. Slices 2–7 are additive assertions on top.

**Anti-pattern check (per the TDD prompt):** none of these tests mock `LspAuditEventService`, `LspAuditEventRepository`, `AdminDirectoryService`, or `LspRepository`. All verification through MockMvc + JPA read of `lsp_audit_event`. Tests survive moving the audit write into a Spring event listener — assertion is on the rows.

#### Files touched (final list)

Add:
- `backend/src/main/resources/db/migration/V{n+1}__lsp_audit_event.sql` (table + 4 indexes).
- `backend/src/main/java/com/bhawana/lms/domain/LspAuditEvent.java`
- `backend/src/main/java/com/bhawana/lms/domain/LspAuditEventAction.java` (`WEBHOOK_URL_CHANGED`, `WEBHOOK_SECRET_ROTATED`, `WEBHOOK_ENABLED`, `WEBHOOK_DISABLED`, `WEBHOOK_EVENT_TYPES_CHANGED`)
- `backend/src/main/java/com/bhawana/lms/repo/LspAuditEventRepository.java`
- `backend/src/main/java/com/bhawana/lms/service/LspAuditEventService.java`
- `backend/src/test/java/com/bhawana/lms/web/LspAdminControllerWebhookAuditTest.java` (seven slice tests)

Edit:
- `backend/src/main/java/com/bhawana/lms/service/AdminDirectoryService.java` — `updateWebhookSubscription` signature grows by `actorUsername, actorIp, correlationId`; captures before-snapshot; after save, calls `lspAuditEventService.recordWebhookSubscriptionChanges(...)`.
- `backend/src/main/java/com/bhawana/lms/web/LspAdminController.java` — extracts actor + IP at the webhook-subscription endpoint; passes through.

Untouched (deliberately):
- `Lsp.webhookSigningSecret` column type — stays plaintext (owned by #72).
- `Lsp.primary_contact_email` field — not added (deferred follow-up).
- `LspAdminController.toResponse` — still returns `signingSecret` in GET responses (#72's scope).
- `AdminDirectoryService.createLsp` — not audited in this PR (table is shaped for `LSP_CREATED`; sibling ticket writes the row).
- `AuditExplorerQuery` / `AuditExplorerRepository` — `LSP_AUDIT` stream wiring deferred until #152's stream-expansion lands.
- FE Audit Explorer — no filter-chip for `LSP_AUDIT` until #152 PR (a) lands first.
- `SsrfSafeUrlValidator` — unchanged.

#### Effect on app

- Every meaningful webhook-subscription change writes one or more audit rows to `lsp_audit_event` with actor, IP, correlation, action label, before/after diff (excluding secret material). Multi-field changes share a correlation ID.
- SOC can run `SELECT * FROM lsp_audit_event WHERE action='WEBHOOK_URL_CHANGED' AND lsp_id=<x> ORDER BY created_at DESC` to see every URL change for an LSP. Same for secret rotations.
- Once #152's LSP_AUDIT stream wiring lands, all of the above becomes filterable from the live Audit Explorer UI.
- Plaintext secret column stays as-is (#72's scope). Audit row never contained secret material anyway — when #72 lands, no behaviour change here.
- No-op PUTs write nothing (Slice 6); 400 paths write nothing (Slice 7).
- Tiny per-call overhead: 0–4 DB inserts in the same `@Transactional` as the LSP save.
- No user-visible UI change. FE filter chip + stream rendering tracked under #152's follow-up.

#### Cluster impact

- **#152** ([AUD-6] / mock-outcome + Audit Explorer expansion) — this PR's `lsp_audit_event` becomes the natural 8th `AuditStream` value `LSP_AUDIT` once #152 PR (a) lands. Cross-link both directions. The stream wiring is a small follow-up after #152 — extend `AuditExplorerQuery.AuditStream` enum, add a UNION ALL branch projecting `lsp_audit_event` into the unified shape (`lspId` is already a first-class field in the projection envelope; no envelope changes needed). FE extends `BACKEND_STREAMS` + filter chip.
- **#72** ([AUD-7-companion] one-shot webhook secret reveal) — DEFERRED 2026-06-01. Orthogonal: this PR audits the rotation **event**, regardless of how the secret is stored or returned. When #72 lands and removes signingSecret from GET responses + hashes the column, this PR's audit row behaviour does not change.
- **#149** (API-client create/rotate/reveal audit) — sibling pattern. Multiple-rows-per-call discipline learned here mirrors the action-label discrimination introduced in #149. Same `actor_ip` precedent (#70/#148/#149/#151).
- **#63** ([LSP-disable] gap — SOLVED 2026-06-01) — the LSP_DISABLED audit action is reserved for whichever future ticket adds the LSP-disable endpoint. Table is shaped to host it; this PR doesn't write `LSP_DISABLED` rows.
- **#82** (verify SSRF protection wired into webhook URL update path) — orthogonal verification. Slice 7's SSRF-block-leaves-audit-clean test exercises the integration as a side-effect; #82 can close referencing this PR's Slice 7 + the existing `SsrfSafeUrlValidator.validate(...)` call site.
- **Follow-up tickets to file in PR description:**
  - "LSP primary contact email + notify on webhook URL/secret rotation"
  - "Audit `createLsp` as `LSP_CREATED`"
  - "Audit LSP profile updates (name/code/status) as `LSP_PROFILE_UPDATED` / `LSP_DISABLED`"
  - "Wire `LSP_AUDIT` as an 8th `AuditStream` once #152 PR (a) lands"

#### Dependencies / sequencing

- Independent of #148, #149, #151, #155. Can ship in any order within the AUD-class PR train.
- Audit Explorer stream wiring depends on #152 PR (a). If #152 ships first, this PR's audit rows are immediately stream-eligible (just need the enum + projection extension as a thin follow-up). If this PR ships first, rows write to the table from day one but aren't visible via the live UI until #152's stream-expansion catches them.

---

### #154 — [AUD-8] IP allowlist add/remove audit incomplete — verify
**Labels:** auditability, security, verification · **Link:** https://github.com/sid12701/lms/issues/154 · **Status:** **CLOSED** — [PR #181](https://github.com/sid12701/lms/pull/181) merged 2026-06-07 (folded into #153)

**Problem (plain English):** Need to verify whether the IP allowlist controller already audits mutations; if not, add.

**Recommended:** Verify; if missing, add audit rows per add/remove with CIDR captured.

**Effect on app:** Tampering visible. Combined with #83 + #142 cache work, full IP-allowlist audit chain works.

**Detailed solution after discussion (2026-06-01):**

#### Verification result

A surface-area audit before deciding split #154 into its two real halves and yielded a definitive verdict:

| Surface | File:Line | Verdict |
|---|---|---|
| **LSP-level allowlist (gap)** | `web/LspIpAllowlistAdminController.java:54-89` | `POST /api/v1/internal/admin/lsps/{lspId}/ip-allowlist` and `DELETE …/{entryId}` both **write zero audit rows**. No actor, no IP, no correlation captured. The #83 cache-invalidation fix wired `allowlistFilter.invalidateCache(lspId)` correctly, but audit was not in #83's scope. **Real gap.** |
| **API-client-level allowlist (no gap)** | `service/ApiClientManagementService.updateClient` | **Already audited** via the `CLIENT_UPDATED` row in `api_client_audit_event` — `details_json.before.ipAllowlist` / `details_json.after.ipAllowlist` capture the full diff at every update (verified during #149's grill). No additional work needed. |

**Conclusion:** #154's actual scope reduces to LSP-side audit only. The natural home is the new `lsp_audit_event` table designed under #153 — `lsp_id` is already the FK there, the action-label discipline + actor_ip + correlation_id columns + multi-row-per-call pattern are all established. Add two action labels and call the same `LspAuditEventService`.

#### Decisions (after grilling, 2026-06-01)

1. **Fold #154 into #153's PR.** Single migration, single table, single audit service. Closes when #153 merges. Pushes back on the "verify only" framing — the verification revealed a real gap.
2. **Two new action labels appended to `LspAuditEventAction`:**
   - `LSP_IP_ALLOWLIST_ENTRY_ADDED` — emitted by `POST` on success.
   - `LSP_IP_ALLOWLIST_ENTRY_REMOVED` — emitted by `DELETE` on success.
3. **details_json shape (symmetric):**
   - `LSP_IP_ALLOWLIST_ENTRY_ADDED`: `{cidr: "1.2.3.4/32", description: "Bangalore office", entryId: "<uuid>"}`
   - `LSP_IP_ALLOWLIST_ENTRY_REMOVED`: `{cidr: "1.2.3.4/32", description: "Bangalore office", entryId: "<uuid>"}` (the entry that was deleted, captured before the delete)
4. **`LspAuditEventService` grows two methods:** `recordIpAllowlistEntryAdded(lsp, entry, actorUsername, actorIp, correlationId)` + `recordIpAllowlistEntryRemoved(lsp, entry, actorUsername, actorIp, correlationId)`. The controller calls these directly after the DB mutation completes — no audit logic inside the controller class.
5. **Controller plumbing:** `LspIpAllowlistAdminController.create` and `.delete` grow `@AuthenticationPrincipal Jwt principal` + `HttpServletRequest request` params; extract actor + IP; pass to the audit service.
6. **400 paths (invalid CIDR, duplicate CIDR, wrong-LSP entry id, unknown LSP id) leave the audit table untouched.** Matches the precedent from #148/#149/#151/#153.
7. **API-client side closes pointing to #149 as 'verified — already covered by CLIENT_UPDATED row's allowlist diff.'** No new rows or labels for the API-client allowlist surface in this PR. If a future operator demands per-entry granularity on the API-client side (instead of full-list-replace diff), file a follow-up — out of scope here.
8. **No change to the cache-invalidation flow.** `allowlistFilter.invalidateCache(lspId)` after every mutation stays exactly as #83 left it. The audit write sits *after* the cache invalidation, inside the same `@Transactional`.

#### TDD plan (vertical slices, one RED → GREEN at a time)

Added to #153's slice train as **Slices 9 and 10** (after Slices 1–7 from #153 land). Each slice is one RED → GREEN cycle.

**Slice 9 — POST /ip-allowlist writes one `LSP_IP_ALLOWLIST_ENTRY_ADDED` row**
- RED: New test class `LspIpAllowlistAdminControllerAuditTest` (or extend the existing controller test). Seed an LSP. SYSTEM_ADMIN POST `/api/v1/internal/admin/lsps/{lspId}/ip-allowlist` with body `{cidr: "10.0.0.0/8", description: "Corp VPN"}`. Assert HTTP 201. Query `lsp_audit_event` ordered by `created_at DESC LIMIT 1`; assert:
  - `lsp_id = <lspId>`
  - `action = 'LSP_IP_ALLOWLIST_ENTRY_ADDED'`
  - `actor_username = <admin>`
  - `actor_ip = <request remote addr>`
  - `correlation_id = <MDC value>`
  - `details_json->>'cidr' = '10.0.0.0/8'`
  - `details_json->>'description' = 'Corp VPN'`
  - `details_json->>'entryId'` matches the response's `id`
  
  Initially fails (no enum value yet, controller doesn't extract principal/request, no service method).
- GREEN:
  1. Append `LSP_IP_ALLOWLIST_ENTRY_ADDED` to `LspAuditEventAction` (after the webhook labels from #153).
  2. Add `LspAuditEventService.recordIpAllowlistEntryAdded(...)`.
  3. `LspIpAllowlistAdminController.create` grows `@AuthenticationPrincipal Jwt principal` + `HttpServletRequest request` params; after `allowlistRepository.save(...)` and `allowlistFilter.invalidateCache(...)`, calls the audit service.
  
  Test passes.
- Refactor: extract a `extractAuditContext(principal, request)` helper if the same extraction appears in `create` and `delete`.

**Slice 10 — DELETE /ip-allowlist/{entryId} writes one `LSP_IP_ALLOWLIST_ENTRY_REMOVED` row**
- RED: Seed an LSP + one allowlist entry. SYSTEM_ADMIN DELETE `/api/v1/internal/admin/lsps/{lspId}/ip-allowlist/{entryId}`. Assert HTTP 204. Query latest audit row; assert:
  - `action = 'LSP_IP_ALLOWLIST_ENTRY_REMOVED'`
  - `details_json->>'cidr'` matches the deleted entry's CIDR
  - `details_json->>'description'` matches the deleted entry's description
  - `details_json->>'entryId' = <entryId>`
  
  Captured BEFORE the delete — slice asserts the audit row knows what was removed.
- GREEN:
  1. Append `LSP_IP_ALLOWLIST_ENTRY_REMOVED` to `LspAuditEventAction`.
  2. Add `LspAuditEventService.recordIpAllowlistEntryRemoved(...)`.
  3. `LspIpAllowlistAdminController.delete` grows the same `principal` + `request` params. **Order matters:** snapshot the entry's fields (cidr, description, entryId) BEFORE `allowlistRepository.delete(entry)`, then delete, then `invalidateCache`, then call the audit service with the snapshotted fields.
  
  Test passes.
- Refactor: none.

**Slice 11 — 400 paths leave the audit table untouched (negative regression guard)**
- RED: Pre-count `lsp_audit_event` rows. Four 400-producing requests:
  - POST with invalid CIDR `"not-a-cidr"` → 400 (validation in `normalizeCidr`).
  - POST with already-present CIDR → 400 (`existsByLsp_IdAndCidr` check).
  - POST with unknown LSP id → 400.
  - DELETE with unknown entry id → 400. DELETE with entry id belonging to a different LSP → 400 (the ownership FK check).
  
  Re-count audit rows after all five; assert delta is 0.
- GREEN: passes — `IllegalArgumentException` thrown before the audit-write step in both `create` and `delete`. Slice locks in "audit successes only."
- Refactor: none.

**Order within #153's PR:** Slices 1–7 (webhook) → Slice 9 (allowlist add) → Slice 10 (allowlist delete) → Slice 11 (allowlist negative). Adding Slices 9–11 does not require revisiting Slices 1–7's GREEN paths; the new action enum values are additive.

**Anti-pattern check (per the TDD prompt):** none of the slices mock `LspAuditEventService`, `LspIpAllowlistRepository`, or `LspIpAllowlistFilter`. Verification is through MockMvc + JPA read of `lsp_audit_event`. Tests survive any refactor that moves the audit write into a Spring event listener or extracts an `LspIpAllowlistAuditService` later.

#### Files touched (delta on top of #153's file list)

Edit (additional to #153's edits):
- `backend/src/main/java/com/bhawana/lms/domain/LspAuditEventAction.java` — append `LSP_IP_ALLOWLIST_ENTRY_ADDED`, `LSP_IP_ALLOWLIST_ENTRY_REMOVED`.
- `backend/src/main/java/com/bhawana/lms/service/LspAuditEventService.java` — add two methods.
- `backend/src/main/java/com/bhawana/lms/web/LspIpAllowlistAdminController.java` — extract actor + IP; delegate to audit service.

Add (additional):
- `backend/src/test/java/com/bhawana/lms/web/LspIpAllowlistAdminControllerAuditTest.java` (Slices 9–11; or three test methods inside the existing controller test class).

Untouched (deliberately):
- `LspIpAllowlistFilter` and its cache logic — owned by #83/#142.
- `ApiClientManagementService.updateClient` allowlist diff path — already audits via `CLIENT_UPDATED` (per #149); no change.
- The migration from #153 — already creates `lsp_audit_event` with the right shape; just two new enum values, no new SQL.

#### Effect on app (delta on top of #153's effects)

- Every LSP allowlist entry add/remove writes one row to `lsp_audit_event` with actor, IP, correlation, CIDR, description, entryId.
- SOC can run `SELECT * FROM lsp_audit_event WHERE action IN ('LSP_IP_ALLOWLIST_ENTRY_ADDED', 'LSP_IP_ALLOWLIST_ENTRY_REMOVED') AND lsp_id=<x> ORDER BY created_at DESC` to see the full allowlist mutation history for any LSP.
- Once #152 PR (a) lands and `LSP_AUDIT` is wired as an `AuditStream`, all six lsp_audit_event action labels (5 webhook + 1 add + 1 remove = actually 7 with both) become filterable from the live Audit Explorer UI.
- API-client side stays as-is — `CLIENT_UPDATED` row in `api_client_audit_event.details_json` continues to capture the full allowlist diff at each update.
- Cache-invalidation flow unchanged (#83).
- 400 paths write nothing (Slice 11).

#### Cluster impact

- **#153** ([AUD-7] Webhook URL/secret rotation audit) — this PR's host. Slices 9–11 land on top of #153's Slices 1–7. The migration, entity, repository, and service collaborator are all shared.
- **#149** ([AUD-3] API-client create/rotate/reveal audit) — verified that API-client-side allowlist mutations are already audited via `CLIENT_UPDATED.details_json` diff. No new work needed there. PR description cross-links: "#154 API-client side closes as covered by #149."
- **#83** ([B-1] LspIpAllowlistFilter cache not invalidated on allowlist mutation — SOLVED 2026-06-01) — orthogonal. Cache invalidation continues to fire after every mutation; audit write sits after the invalidation, inside the same `@Transactional`. Slice 9 + Slice 10 effectively re-prove the integration path.
- **#142** ([SEC-Δ-4] LspIpAllowlistFilter cache is process-local — unbounded staleness across replicas) — orthogonal. Replica-staleness fix is its own work; doesn't interact with audit-write order.
- **#152** ([AUD-6] AuditStream expansion) — once `LSP_AUDIT` is wired in (follow-up after #152 PR (a)), allowlist mutations become Audit-Explorer-queryable from the live UI alongside the webhook events.
- **#164** ([V-4] Verify LspIpAllowlistAdminController calls invalidateCache — closed as verified by #83) — independently closed; no cross-link needed.

#### Dependencies / sequencing

- **Hard dependency on #153** — Slices 9–11 require `lsp_audit_event` table + `LspAuditEvent` entity + `LspAuditEventService` from #153's Slices 1–7. Ship as one PR.
- Independent of every other AUD-class issue.
- API-client side closes immediately on PR merge by referencing #149.

---

### #155 — [AUD-9] Failed-auth events not fed into lockout/alert pipeline
**Labels:** auditability, security · **Link:** https://github.com/sid12701/lms/issues/155

**Problem (plain English):** Even once auth events are audited (#71), there's no rule that fires after N failures from one user/IP. Credential stuffing has no detection.

**Possible fixes:**
1. **`AUTH_BRUTE_FORCE` alert rule + temporary account lockout** — standard control.
2. **Alert only, no lockout** — visible; doesn't stop attack.
3. **IP-level rate limit only** — partial; misses distributed attacks.

**Recommended:** Option 1. Lock the account/IP for K minutes; alert at the same threshold.

**Effect on app:** Brute-force becomes self-stopping. Legitimate users may hit lockouts on shared IPs — needs an admin unlock path.

**Detailed solution after discussion:** _(pending)_

---

### #161 — [V-1] Verify Local* services are @Profile("local")
**Link:** https://github.com/sid12701/lms/issues/161

**Detailed solution after discussion (2026-06-02 follow-up audit):** Verified — see § #107. `LocalDemoPortfolioSeedService` and `SampleCatalogSeedService` are correctly profile-gated; `LocalBootstrapAdminSyncService` is intentionally not gated (must run in every profile per F-19; bootstrap password from env var). **Close as verified, optionally rename `LocalBootstrapAdminSyncService` → `BootstrapAdminSyncService` to remove the misleading "Local" prefix.**

---

### #163 — [V-3] Audit committed creds in application-local.yml
**Labels:** security, verification · **Link:** https://github.com/sid12701/lms/issues/163

**Problem (plain English):** Security audit said creds were committed to `application-local.yml`. Need to confirm rotation has happened and replace literals with env-var references.

**Possible fixes:**
1. **`git log -p` over the yml; rotate every credential ever committed; replace with placeholders** — strongest.
2. **Only rotate what's still current; leave history intact** — historical exposure remains (already on GitHub).

**Recommended:** Option 1. Once a secret is in git history, it's compromised forever — rotate even if only ever committed once.

**Effect on app:** Local-dev setup needs env vars (one-time pain). History scan reveals what to rotate elsewhere too.

**Detailed solution after discussion (2026-06-02 follow-up audit):** Verified — `application-local.yml` reads every credential from environment variables (`${LMS_DB_PASSWORD}`, `${LMS_RABBITMQ_PASSWORD:lms}`, `${APP_SECURITY_BOOTSTRAP_PASSWORD}`, `${APP_SECURITY_JWT_SECRET}`, `${APP_TENANT_DATASOURCE_PASSWORD}`). No plaintext credentials in the committed file today. Still need to: (a) confirm via `git log -p backend/src/main/resources/application-local.yml` that nothing was committed in history, and (b) rotate anything that ever appeared in git history (Option 1's "compromised forever" property). **Close once the history audit is done.**

---

### #164 — [V-4] Verify LspIpAllowlistAdminController calls invalidateCache
**Link:** https://github.com/sid12701/lms/issues/164 · **Status:** **CLOSED — VERIFICATION PASSES** (2026-06-06). Dup of **#83**; `IpAllowlistCacheInvalidation.afterCommit` on API + UI allowlist controllers.

**Detailed solution after discussion (2026-06-01):** Close as verified by **#83**. `LspIpAllowlistAdminController` mutation paths invoke `invalidateCache` after every add/remove (verified during #83's fix). No outstanding gap.

---

### #167 — [V-7] Triage 15 pre-existing FE test failures
**Labels:** code-quality, verification · **Link:** https://github.com/sid12701/lms/issues/167

**Problem (plain English):** 15 FE tests are known-failing and the team is ignoring them. The CI signal is degraded; new failures hide in the noise.

**Possible fixes:**
1. **Triage each: fix, delete, or `.skip` with a tracking issue** — addresses the noise.
2. **Mark them all `.skip`, file an epic for follow-up** — quick noise reduction; defers actual fix.

**Recommended:** Option 1. Each failure is a decision; tolerating none keeps the signal clean.

**Effect on app:** CI red means something. Future failures get noticed.

**Detailed solution after discussion:** _(pending)_

---

### #168 — [§0-10] Test coverage skewed; ~3 Playwright specs; missing E2E scenarios
**Labels:** gap, code-quality, verification · **Link:** https://github.com/sid12701/lms/issues/168

**Problem (plain English):** 1000+ FE tests, ~236 BE tests (mostly happy-path), 3 Playwright specs. The high FE count is misleading because most validate the FE's own mocks. Critical paths (cross-LSP isolation, disbursement-fail-retry, RBAC matrix) have no E2E coverage.

**Possible fixes:**
1. **Adopt the 5-layer architecture from audit §12** — full plan; significant effort.
2. **Add only the critical Playwright specs (cross-LSP, dis-fail-retry, RBAC)** — narrow win.
3. **Stand up REST-assured/Postman layer for API contract** — middle ground.

**Recommended:** Option 1, sequenced: start with Option 2 + Option 3 in the first sprint, then expand layers over a quarter.

**Effect on app:** CI takes longer (15→60 min for full deck). Bugs caught at PR time, not staging. Confidence to ship rises.

**Detailed solution after discussion:** _(pending)_

---

## P2 — Medium priority (55)

### #67 — No LSP UI loan-create form (POST is API-client-only)
**Labels:** gap, rbac · **Link:** https://github.com/sid12701/lms/issues/67

**Problem (plain English):** Spec says LSP UI users may create loans; the only loan-create endpoint is API-client gated. So the UI role can't actually originate.

**Possible fixes:**
1. **Add LSP-UI endpoint + form** — matches spec.
2. **Drop the spec line; document API-only** — simpler, narrower product.
3. **Allow LSP_UI_WRITE on existing endpoint with extra validation** — quickest.

**Recommended:** Option 3. Reuses validation; smallest delta.

**Effect on app:** UI users can originate loans. RBAC test matrix grows by one row.

**Detailed solution after discussion:** _(pending)_

---

### #73 — No redrive path for PERMANENT_FAILURE webhook events
**Labels:** gap · **Link:** https://github.com/sid12701/lms/issues/73 · **Status:** **CLOSED** — [PR #182](https://github.com/sid12701/lms/pull/182) merged 2026-06-07. Bundled with **#130** (redrive endpoint + audit + cap).

**Problem (plain English):** Once an event is PERMANENT, the only way to retry is hand-editing DB rows. No admin UI, no API.

**Possible fixes:**
1. **`POST /webhook-outbox/{id}/redrive`** with cap (e.g., max 3 redrives) + UI button.
2. **Bulk redrive by filter** — powerful and risky.
3. **Auto-redrive after N hours** — silent; defeats the PERMANENT classification.

**Recommended:** Option 1. Manual control with audit.

**Effect on app:** Operators recover from typo/outage situations without DBA help.

**Detailed solution after discussion (2026-06-03) — IMPLEMENT (GREEN-LIT): bundled PR with #130.**

Full design, decisions, test plan, and file list live in the **#130** entry above (single bundled PR, single set of TDD slices). Headline decisions specific to #73:

- **Endpoint:** `POST /api/v1/internal/admin/webhook-outbox/{id}/redrive`, `hasRole('SYSTEM_ADMIN')` only.
- **Per-event cap:** 3 manual redrives, tracked via new `redrive_count` column on `webhook_event_outbox`.
- **Behaviour:** status `PERMANENT_FAILURE` → `PENDING`; `attemptCount = 0` (fresh 10-attempt budget post-fix); `nextAttemptAt = null`; `lastError = null`; `redriveCount += 1`.
- **Guards:** 422 `WEBHOOK_OUTBOX_NOT_REDRIVABLE` for non-PERMANENT status; 422 `WEBHOOK_OUTBOX_REDRIVE_CAP_EXCEEDED` on the 4th attempt.
- **Audit:** existing audit infrastructure (#70 / #152) records actor, eventId, lspId, correlationId, redriveCount.
- **UI:** admin Outbox view in `frontend-2` shows `redriveCount / 3`, attempts so far, and a Redrive button enabled only on PERMANENT rows with budget remaining.
- **Out of scope:** bulk redrive (single-event only; bulk waits for real operational need); auto-redrive (defeats classification, rejected); OPS_USER access (SYSTEM_ADMIN only for now).
- **Closes #73** when the bundled #130 + #73 PR merges.

---

### #74 — Foreclosure execute is admin-only — LSP cannot finalize closure
**Labels:** gap, rbac · **Link:** https://github.com/sid12701/lms/issues/74

**Problem (plain English):** LSP can request a foreclosure quote but cannot execute it; only admin can. Asymmetric with disbursement (LSP can self-disburse).

**Possible fixes:**
1. **LSP self-execute with maker-checker** — symmetric with disbursement after #62.
2. **Keep admin-only; document why** — defensible if there's an underwriting reason.
3. **LSP request → admin approval queue** — middle ground.

**Recommended:** Option 1 once maker-checker (#62) lands. Symmetric workflow, controlled by maker-checker.

**Effect on app:** LSPs self-service close. Admin out of the loop unless escalation needed.

**Detailed solution after discussion:** _(pending)_

---

### #75 — No reports for OPS_USER / LSP / PRODUCT_ADMIN
**Labels:** gap, rbac, reporting-risk · **Link:** https://github.com/sid12701/lms/issues/75

**Problem (plain English):** Reports endpoint is admin-only. Other roles see operational screens but no aggregate view of their scope.

**Possible fixes:**
1. **Per-role report inventory + scoped endpoints** — proper fix.
2. **Read-only reports for other roles on the existing endpoint** — quick; doesn't scope rows.
3. **Punt** — defer.

**Recommended:** Option 1. Reports need RLS-aware queries; can't just relax the auth on the existing endpoint.

**Effect on app:** Each role sees its own view. Most work is BE projections + RBAC tests.

**Detailed solution after discussion:** _(pending)_

---

### #76 — Audit Explorer free-text + correlationId filter is client-side only
**Labels:** gap, auditability · **Link:** https://github.com/sid12701/lms/issues/76

**Problem (plain English):** The UI filters apply only to the currently-loaded page of audit rows. Cross-page searches are impossible from the UI.

**Possible fixes:**
1. **Push down filters to backend (`q` and `correlationId` params)** — proper fix.
2. **Increase page size and warn users** — bad — kills performance.

**Recommended:** Option 1. Trigram index on text columns where useful.

**Effect on app:** Investigations work across the whole stream. Negligible perf cost.

**Detailed solution after discussion:** _(pending)_

---

### #77 — Home overview KPIs locked to SYSTEM_ADMIN
**Labels:** gap, rbac · **Link:** https://github.com/sid12701/lms/issues/77

**Problem (plain English):** Other roles see legacy home with no insights.

**Possible fixes:**
1. **Role-tailored home overview, RBAC-scoped KPIs** — proper.
2. **Single KPI tile relaxed to all roles, no scoping** — leaks if not scoped.

**Recommended:** Option 1. Scope projections per role.

**Effect on app:** Each role lands on a useful home page. Tiles match scope.

**Detailed solution after discussion:** _(pending)_

---

### #80 — No admin "log out everywhere" / global JWT revocation
**Labels:** gap, security · **Link:** https://github.com/sid12701/lms/issues/80

**Problem (plain English):** No way to bulk-invalidate sessions during an incident.

**Possible fixes:**
1. **Per-user `tv` bump from admin UI** — covers individual user; relies on existing pattern.
2. **System-wide `jwt_global_version` row** — kills every session at once.

**Recommended:** Option 1 first (cheap), Option 2 only if incident-response needs justify the blast radius.

**Effect on app:** Incident response gains a real control. Risk: misclick kicks everyone out.

**Detailed solution after discussion:** _(pending)_

---

### #81 — Rate limiting missing on doc/report/mock-outcome/refresh/password endpoints
**Labels:** gap, security · **Link:** https://github.com/sid12701/lms/issues/81 · **Status:** **CLOSED** — [PR #182](https://github.com/sid12701/lms/pull/182) merged 2026-06-07. Config-driven rules + FE `retryAfterSeconds`. Closes **#127** as duplicate.

**Problem (plain English):** Rate limit only covers auth + a few LSP write paths. Doc/report scraping and refresh/password brute-force are unbounded.

**Possible fixes:**
1. **Extend `RateLimitFilter` patterns + per-endpoint budgets in config** — straightforward.
2. **Switch to a token-bucket cluster (Redis)** — multi-replica correctness.

**Recommended:** Option 1 now, Option 2 if multi-replica replays show drift.

**Effect on app:** Abuse vectors capped. Legitimate use unchanged.

**Detailed solution after discussion (2026-06-03):**

**Reframe of the audit-doc options.** The original framing — "Option 1 (extend matcher) now, Option 2 (switch to Redis) later if multi-replica drift shows up" — is stale. `backend/.../security/RateLimitConfig.java:39` already wires the Bucket4j proxy through Lettuce (`Bucket4jLettuce.casBasedBuilder(...)` with a 10-minute TTL). Buckets are already cluster-correct. "Switch to Redis" is not a future option; it is today's reality. The actual decision is **what to match, what budgets, and per-actor vs per-IP keying** — not the bucket store.

#### Audit of linked surface area (done before deciding)

| Surface | Path / Code | State |
|---|---|---|
| Filter | `security/RateLimitFilter.java:92` (`resolveTarget`) | Hardcoded `if`-chain: matches `POST /api/v1/auth/login`, `POST /api/v1/auth/token`, and any write under `/api/v1/lsp/**`. Anything else returns `null` → unbounded. |
| Properties | `security/RateLimitProperties.java` | Two knobs: `authPerMinute=10`, `lspWritePerMinute=60`. No collection type; adding a third budget requires a new field. |
| Bucket store | `security/RateLimitConfig.java:39` | Redis-backed `ProxyManager<String>` via `Bucket4jLettuce.casBasedBuilder`. **Cluster-correct today.** TTL 10 min. |
| Alert plumbing | `service/AlertRuleEvaluationService.java:229` (`emitRateLimitBreach`) | Already fires `RATE_LIMIT_EXCEEDED` with `bucketKey`, `path`, `retryAfterSeconds`. Wired into the alert stream shipped by **#62 PR-(a)**. Visible in Audit Explorer. |
| Auth endpoints | `web/AuthController.java:118` (`/refresh`), `:156` (`/password`), `:181` (`/logout`) | **All three unbounded.** Refresh brute-force, password rotation hammering, logout-spam under multi-tab races all uncapped. |
| Admin password reset | `web/UserAdminController.java:79` (`POST /api/v1/internal/admin/users/{userId}/reset-password`) | Unbounded. Allows an attacker (compromised SYSTEM_ADMIN) to reset many users rapidly to obscure intent. |
| Document GET (ops) | `web/LoanApplicationOpsController.java:186` (`/{id}/kyc-documents`), `:196` (`.../download-all`), `:221` (`.../{type}/content`) | Unbounded. #70's audit row is post-facto only. |
| Document GET (LSP) | `web/LspLoanApplicationApiController.java:261` (`/{id}/documents`) | Unbounded. Bulk-scrape vector if an LSP credential leaks. |
| Mock-outcome | `web/LoanApplicationOpsController.java:382` (`POST .../disbursement-requests/mock-outcome`) | Per #61, this is the **live disbursement-callback simulator**. Unbounded → an attacker can churn `DISBURSED → REFUNDED → DISBURSED` on the same loan to obscure the audit trail. #152's audit captures each call but does not gate volume. |
| Reports | `web/ReportAdminController.java:42,58,67,82,103` (5 endpoints under `/api/v1/internal/reports/**`) | All unbounded. CSV at `:67` still leaks raw PAN (per #69), so scraping is also a data-exfil vector. |
| Frontend 429 handling | `frontend-2/src/lib/api/http-client.ts` (`requestJson`, `requestBlob`) | **No 429 / `Retry-After` interceptor.** Hitting a bucket today produces a raw "Request failed" toast. Confirmed via grep: zero matches for `429`, `Retry-After`, or `RATE_LIMIT_EXCEEDED` across `frontend-2/**/*.ts`. |
| Test profile | (existing `application-test.yml`) | Test profile inherits production budgets. Any loop test that exceeds 10/min or 60/min would already flap; adding new buckets multiplies the surface. |
| Sibling issue | `#127 [F-4]` | Exact duplicate of #81. |
| Adjacent (not merged) | `#155` (failed-auth → lockout pipeline) | Different semantics: lockout = "N failures → block account/IP"; rate limit = "N req/min → 429 + retry-after." They compose; this PR explicitly does not subsume #155. |
| Adjacent (not merged) | `#142` (LSP allowlist cache process-local) | Different cache, different layer. Calling it out so future readers don't conflate it with the bucket store. |
| Adjacent (not merged) | `#69` (MIS CSV unmasked PAN) | Rate limit on report download is defence-in-depth in addition to masking; #69 stays open. |
| Adjacent (not merged) | `#80` (admin "log out everywhere"), `#133` (password history) | Both will likely add buckets later. Validates Option B (rules table) over the existing if-chain. |
| Cluster controls relying on this | `#62 PR-(c)` alert stream | New buckets increase alert volume on the same plumbing; no new surface. |

**Conclusion of audit:** the bucket store, the alert plumbing, and the 429 body shape are all already correct. The whole job is (a) replace the hardcoded matcher with a rules table, (b) add four new rule families with the right keying matrix, (c) close the FE UX gap so 429s don't surface as opaque errors, (d) keep CI green by raising test-profile budgets. #127 closes in the same PR.

#### Decision (after grilling, 2026-06-03)

1. **Matcher → config-driven rules table (Option B).** `RateLimitProperties` carries a `List<Rule>` of `{ id, pathPattern, methods, key, permitsPerMinute }`. The filter walks rules in order; first match wins. Existing three buckets (`auth-login`, `auth-token`, `lsp-write`) become rules; the hardcoded `if`-chain in `resolveTarget` is replaced by a single rule-table walk. Rejected: annotation-based (split-brain with the cross-cutting LSP-write rule; couples to Spring AOP); keep-the-if-chain (the queue of upcoming buckets — #80, #133, #155 — argues against hardcoding).
2. **Keying matrix.** A new enum `KeyStrategy { IP, SUBJECT, LSP, CLIENT, IP_AND_SUBJECT, SUBJECT_AND_APPLICATION }`. Per surface:
   - `auth-login`, `auth-token` — `IP` (unchanged from today).
   - `lsp-write` — `LSP` (unchanged from today; key extracts `lspId` claim).
   - `auth-refresh` — `IP_AND_SUBJECT` (both buckets must pass). Defeats both botnet IP rotation and credential rotation.
   - `auth-password`, `admin-reset-password` — `SUBJECT` (account-targeted brute force; IP-only would be bypassable by botnet).
   - `docs-ops` — `SUBJECT` (a compromised SYSTEM_ADMIN is one principal regardless of IP).
   - `docs-lsp` — `LSP` (consistent with `lsp-write`).
   - `reports` — `SUBJECT`.
   - `mock-outcome` — `SUBJECT_AND_APPLICATION` (stacked: per-actor cap + per-application cap; catches both "one admin, many loans" and "many admins, one loan" patterns). Implemented as **two** rules with the same `pathPattern` and `methods`; the filter must consume *both* buckets before letting the request through.
3. **Configured rules (initial YAML).** Budgets are starting points; tunable in `application.yml` without a release.
   ```yaml
   app.rate-limit.rules:
     - { id: auth-login,          path: /api/v1/auth/login,                                                methods: [POST],            key: IP,                       permits: 10 }
     - { id: auth-token,          path: /api/v1/auth/token,                                                methods: [POST],            key: IP,                       permits: 10 }
     - { id: lsp-write,           path: /api/v1/lsp/**,                                                    methods: [POST,PUT,PATCH,DELETE], key: LSP,                permits: 60 }
     - { id: auth-refresh,        path: /api/v1/auth/refresh,                                              methods: [POST],            key: IP_AND_SUBJECT,           permits: 30 }
     - { id: auth-password,       path: /api/v1/auth/password,                                             methods: [POST],            key: SUBJECT,                  permits: 5  }
     - { id: admin-reset-password,path: /api/v1/internal/admin/users/*/reset-password,                     methods: [POST],            key: SUBJECT,                  permits: 5  }
     - { id: docs-ops,            path: /api/v1/internal/ops/loan-applications/*/kyc-documents/**,         methods: [GET],             key: SUBJECT,                  permits: 120 }
     - { id: docs-lsp,            path: /api/v1/lsp/loan-applications/*/documents/**,                      methods: [GET],             key: LSP,                      permits: 120 }
     - { id: reports,             path: /api/v1/internal/reports/**,                                       methods: [GET,POST],        key: SUBJECT,                  permits: 60 }
     - { id: mock-outcome,        path: /api/v1/internal/ops/loan-applications/*/disbursement-requests/mock-outcome, methods: [POST], key: SUBJECT_AND_APPLICATION,  permits: 10/5 }
   ```
   Notation: `permits: 10/5` for `SUBJECT_AND_APPLICATION` means `subjectBucket=10/min`, `applicationBucket=5/min`. Encoded in YAML as two integers (`permitsSubject`, `permitsApplication`); the validator rejects single-int permits for that strategy and rejects two-int permits for any other strategy.
4. **Alert taxonomy → single `RATE_LIMIT_EXCEEDED` only.** Reuse the existing `AlertRuleEvaluationService.emitRateLimitBreach(bucketKey, path, retryAfterSeconds)` untouched. The `bucketKey` (e.g., `docs-ops:subject:<uuid>`, `lsp-write:<lspId>`, `mock-outcome-app:<applicationId>`) already identifies actor/LSP/application; Audit Explorer reviewers filter by `alertType=RATE_LIMIT_EXCEEDED` and parse the key prefix. Rejected: surface-specific types (DOC_SCRAPE_DETECTED etc.) invent a new family per rule; reusing one type keeps the plumbing static while the rule table evolves.
5. **Frontend 429 + `Retry-After` interceptor in the same PR.** `frontend-2/src/lib/api/http-client.ts`'s `requestJson` and `requestBlob` learn one new branch: on `response.status === 429`, read `Retry-After`, surface a typed `ApiError` with `code: "RATE_LIMIT_EXCEEDED"` and a `retryAfterSeconds: number` field, and expose a top-level toast helper that renders "Too many requests. Please wait N seconds." The error type is exported so call sites can override the message (e.g., on the report-download button to say "Report downloads are limited; please try again in N seconds"). No automatic retry — that's the user's job, not the client's.
6. **Test-profile budgets — high overrides in `application-test.yml`.** Keep the filter wired so the wiring itself is verified by existing test runs. Override per-rule permits to a very high number (e.g., 100000/min) in `application-test.yml`. Breach-path tests opt into a dedicated `@TestPropertySource(properties = "app.rate-limit.rules[0].permitsPerMinute=2")` (or per-rule overrides) so they hit the limit in two requests. Rejected: disabling the filter entirely in tests (loses the in-process integration coverage that catches matcher regressions).
7. **Bundle.** Close #127 in the same PR ("Closes #127 as duplicate of #81"). Single delivery, single review.

#### TDD plan (vertical slices, one RED → GREEN at a time)

Each slice exercises behaviour through a public surface (HTTP, the alert read interface, or the FE's typed error). No mocking of internal collaborators. The **tracer** boots first; each subsequent slice responds to what the previous revealed. Listed in execution order.

**Slice 1 — TRACER: a request matching the existing `auth-login` rule still returns 429 after `permitsPerMinute` calls.**
- RED: `RateLimitFilterIntegrationTest.authLoginRuleStillFiresAfterPortToRulesTable()`. `@SpringBootTest` with `app.rate-limit.rules[0].id=auth-login`, `path=/api/v1/auth/login`, `methods=[POST]`, `key=IP`, `permits=2`. Hit `/auth/login` three times. Assert third response is 429 with `Retry-After` header and `RATE_LIMIT_EXCEEDED` body code. Fails because `RateLimitProperties` has no rules-list field yet.
- GREEN: introduce `RateLimitProperties.rules: List<Rule>` (and the `Rule` + `KeyStrategy` types). Make `RateLimitFilter.resolveTarget` walk the rules. Keep the hardcoded `if`-chain *as a fallback* for this slice only — both paths exist briefly. Test passes via the new rules path.
- Refactor: none yet — Slice 2 retires the if-chain.

**Slice 2 — Retire the hardcoded if-chain; existing `lsp-write` and `auth-token` rules still fire via the rules table.**
- RED: extend the integration test to seed `auth-token` and `lsp-write` rules in YAML and assert they still return 429 after their limits. Tests pass via fallback today; **delete the hardcoded if-chain** and re-run — tests now fail (matcher returns null).
- GREEN: remove the if-chain entirely from `resolveTarget`. All three pre-existing buckets now travel only through the rules table. Tests pass.
- Refactor: rename `RateLimitTarget.permitsPerMinute` to `permits` (or keep) and confirm the `bucketKey` format (`<rule-id>:<key-strategy-specific-suffix>`) is stable — it appears in the `RATE_LIMIT_EXCEEDED` alert payload and `bucketKey`-prefix filtering depends on it.

**Slice 3 — `auth-refresh` is `IP_AND_SUBJECT`-keyed.**
- RED: `RateLimitFilterRefreshTest.refreshRule_consumes_both_ip_and_subject_buckets()`. Seed `auth-refresh` with `permits=2`. From IP A and subject U, make 2 calls (both pass). From IP A and subject V, make a 3rd call — passes (IP A's bucket reset? no, refilling). Specifically: from IP A and subject U make 3 calls in <1s — third returns 429. From IP A and subject V, first call returns 429 (IP-A bucket already empty). From IP B and subject U, first call returns 429 (subject-U bucket already empty). Fails because the filter consumes one bucket per request.
- GREEN: in the filter, for `IP_AND_SUBJECT` strategy, build *two* `BucketProxy`s (key `auth-refresh:ip:<ip>` and `auth-refresh:subject:<uuid>`); call `tryConsumeAndReturnRemaining(1)` on **both**; emit 429 if *either* refuses. On refusal, return the higher of the two `retryAfterSeconds`. Tests pass.
- Refactor: extract `KeyStrategy.resolveKeys(rule, request, authentication) → List<String>` so the filter is data-driven over the strategy.

**Slice 4 — `/auth/password` rule is `SUBJECT`-keyed.**
- RED: `RateLimitFilterPasswordTest.passwordChange_brute_force_from_rotating_ips_against_same_account_is_capped()`. Seed `auth-password` with `permits=3`. Make 4 calls as subject U from 4 different remote IPs (simulated via `MockHttpServletRequest.setRemoteAddr`). 4th returns 429. From subject V, first call passes. Fails because no rule exists for `/auth/password`.
- GREEN: add the `auth-password` rule to the YAML. `KeyStrategy.SUBJECT.resolveKeys` returns the JWT subject claim. Test passes.
- Refactor: none.

**Slice 5 — Admin reset-password rule is `SUBJECT`-keyed against the *resetter*, not the target user.**
- RED: `RateLimitFilterAdminResetPasswordTest`. Subject `admin-alice` resets passwords for 6 different `{userId}` paths in <1s with `permits=5`. Sixth call returns 429. Subject `admin-bob` first reset passes. Tests fail because no rule covers `/api/v1/internal/admin/users/*/reset-password`.
- GREEN: add the `admin-reset-password` rule. Confirm that `SUBJECT` keying uses the *caller's* subject (not the path variable `{userId}`). Tests pass.
- Refactor: the path-pattern matcher must handle Ant-style `*` correctly — verify Spring's `AntPathMatcher` is used (or a small wrapper) and add a unit test for the pattern in isolation.

**Slice 6 — Document GETs are capped per actor (ops) and per LSP (LSP API).**
- RED: `RateLimitFilterDocsTest`. (a) Ops: subject U downloads 6 docs across 6 different applications in <1s with `permits=5` — sixth returns 429. (b) LSP: tenant L downloads 6 docs in <1s with `permits=5` — sixth returns 429. (c) LSP: tenant L downloads 1 doc, tenant M downloads 6 docs — only M's sixth returns 429. Fails because no doc rules exist.
- GREEN: add `docs-ops` and `docs-lsp` rules. Tests pass.
- Refactor: confirm the ZIP path `/kyc-documents/download-all` matches the same rule as single-file GETs (same `docs-ops` rule, same bucket — the ZIP costs *one* bucket slot regardless of contents; the cap is on requests, not bytes, which matches the threat model: scrape detection, not bandwidth).

**Slice 7 — Reports rule covers preview, summary, CSV, request-submit, request-download.**
- RED: `RateLimitFilterReportsTest`. Subject U hits all five report endpoints (preview, summary, CSV, request-submit, request-download) in a tight loop with `permits=5` total — sixth returns 429. Fails — no rules.
- GREEN: add the single `reports` rule with `path: /api/v1/internal/reports/**` and `methods: [GET,POST]`. All five endpoints share the bucket — a scraper alternating preview/CSV doesn't double their budget. Tests pass.
- Refactor: confirm the path glob matches all five endpoints; add a parameterised assertion if drift looks likely.

**Slice 8 — Mock-outcome consumes both subject and application buckets.**
- RED: `RateLimitFilterMockOutcomeTest`. (a) Subject U calls `/mock-outcome` on 11 different applications in <1s with `permitsSubject=10` — eleventh returns 429. (b) 5 different subjects each call `/mock-outcome` on **the same** application — sixth returns 429 with `permitsApplication=5`. (c) Subject V on a different application from (a)'s 10 — passes (independent SUBJECT bucket, independent APPLICATION bucket). Fails — no rule exists for `SUBJECT_AND_APPLICATION`.
- GREEN: implement `KeyStrategy.SUBJECT_AND_APPLICATION` to resolve **two** keys (one from subject claim, one from path variable `{applicationId}`). YAML rule carries two permit values; the filter consumes both buckets. Add the YAML rule. Tests pass.
- Refactor: keep the two-permit field on `Rule` polymorphic (single int vs. pair) but validate at startup that the shape matches the strategy. Document in the `Rule` Javadoc.

**Slice 9 — Breach fires `RATE_LIMIT_EXCEEDED` alert with the right `bucketKey`.**
- RED: `RateLimitFilterAlertEmissionIntegrationTest`. Configure `permits=1` for `reports`. Make 2 GETs to `/api/v1/internal/reports/portfolio-mis/preview`. After the 429, query the alert read API (Audit Explorer's `alertType=RATE_LIMIT_EXCEEDED` filter) and assert exactly one alert with `bucketKey` starting `reports:subject:` and `path = /api/v1/internal/reports/portfolio-mis/preview`. **No mock** on `AlertRuleEvaluationService` — assertion via the public alert query interface. Fails today only if the rule isn't wired through Slice 7; otherwise asserts the existing emit-on-breach behaviour for the new rule.
- GREEN: nothing new on the backend (the emit call already exists in `RateLimitFilter.java:79`). The test exercises end-to-end through the alert pipeline. If the alert isn't surfaced — likely cause: `bucketKey` format change in Slice 2 broke a downstream filter assumption — fix at the source.
- Refactor: lock the `bucketKey` format with a dedicated unit test (`RateLimitBucketKeyFormatTest`) so a future refactor cannot silently change the alert-payload contract.

**Slice 10 — Test-profile high budgets keep CI green.**
- RED: run the full backend test suite under the new rules table with `application-test.yml` still carrying production budgets. Any test that loops calls to a now-protected endpoint goes red. Capture the list (likely culprits: AuthControllerTest's refresh loop, ReportAdminControllerTest's CSV loop).
- GREEN: in `application-test.yml`, set `app.rate-limit.rules[*].permitsPerMinute=100000` (and `permitsSubject`/`permitsApplication` analogously). Suite returns to green. Slices 1–9 keep their dedicated low-budget overrides.
- Refactor: pull the test budgets into a single `@TestConfiguration` if `application-test.yml` becomes noisy.

**Slice 11 — Frontend 429 surfaces `Retry-After` as a typed error.**
- RED: `frontend-2/src/lib/api/http-client.test.ts`. With a stubbed `fetch` returning 429 + `Retry-After: 30` + `{ code: "RATE_LIMIT_EXCEEDED", message: "..." }`, assert `requestJson` throws an `ApiError` with `status=429`, `code="RATE_LIMIT_EXCEEDED"`, and (new) `retryAfterSeconds=30`. Fails — `ApiError` has no `retryAfterSeconds` field today.
- GREEN: add `retryAfterSeconds: number | null` to `ApiError`; read `Retry-After` in the 429 branch of `performJsonRequest` (and `requestBlob`) and pass it to the `ApiError`. Test passes.
- Refactor: confirm the toast helper consumed by mutations / queries surfaces a friendly message when `error.retryAfterSeconds` is present; add one Playwright spec (or RTL component test) to confirm the toast renders the seconds value.

**Slice 12 — Regression canary: an attempt to add a rule referencing an unknown `KeyStrategy` fails fast at boot.**
- RED: `RateLimitPropertiesValidationTest`. Load context with a bogus `key: WRONG_STRATEGY`. Assert `ApplicationContextException` (or `BeanCreationException`) with a message naming the bad rule id. Fails today because the YAML binder accepts unknown enum values silently.
- GREEN: add `@PostConstruct` validation to `RateLimitProperties.rules` (or a `Validator`) that rejects unknown strategies, duplicate ids, and `SUBJECT_AND_APPLICATION` rules without a `permitsApplication` value. Test passes.
- Refactor: this slice is the receipt — it makes future rule additions safe by failing the deploy rather than silently disabling a bucket.

**Order matters:** Slices 1 → 2 must land in the same commit (the if-chain leaves the file at the end of Slice 2). Slices 3–8 are independent but conventionally land in the listed order. Slices 9 + 12 are the integration capstones; 10 + 11 prevent collateral damage in CI and FE UX.

**Mocking discipline (per the TDD philosophy):**
- `ProxyManager<String>` — system boundary (Redis). For unit-style filter tests, swap in `Bucket4jLettuce`'s in-memory variant via a `@TestConfiguration` so tests don't require a real Redis. For `@SpringBootTest` integration tests, the real Redis from `docker-compose` is used.
- `AlertRuleEvaluationService` — **internal collaborator, never mocked.** Slice 9 asserts via the public alert read API.
- `Clock` — boundary, mocked in window-determinism tests if any window-precision assertions appear (Bucket4j refills greedily; we likely don't need a fake clock).
- Repositories, lifecycle services, the rule engine — all internal; not mocked.

**Tests we deliberately do NOT write here (owned elsewhere):**
- Lockout-after-N-failures behaviour → #155.
- Allowlist cache invalidation → #83 / #142.
- Doc-download audit row content → #70 / #150.
- Mock-outcome audit row content → #152.

#### Files touched (final list)

Add:
- `backend/src/main/java/com/bhawana/lms/security/RateLimitRule.java` (record: `id`, `path`, `methods`, `key`, `permitsPerMinute`, `permitsApplication` (nullable)).
- `backend/src/main/java/com/bhawana/lms/security/KeyStrategy.java` (enum + `resolveKeys` method).
- `backend/src/main/java/com/bhawana/lms/security/RateLimitPropertiesValidator.java` (or `@PostConstruct` on `RateLimitProperties`).
- `backend/src/test/java/com/bhawana/lms/security/RateLimitFilterIntegrationTest.java`
- `backend/src/test/java/com/bhawana/lms/security/RateLimitFilterRefreshTest.java`
- `backend/src/test/java/com/bhawana/lms/security/RateLimitFilterPasswordTest.java`
- `backend/src/test/java/com/bhawana/lms/security/RateLimitFilterAdminResetPasswordTest.java`
- `backend/src/test/java/com/bhawana/lms/security/RateLimitFilterDocsTest.java`
- `backend/src/test/java/com/bhawana/lms/security/RateLimitFilterReportsTest.java`
- `backend/src/test/java/com/bhawana/lms/security/RateLimitFilterMockOutcomeTest.java`
- `backend/src/test/java/com/bhawana/lms/security/RateLimitFilterAlertEmissionIntegrationTest.java`
- `backend/src/test/java/com/bhawana/lms/security/RateLimitBucketKeyFormatTest.java`
- `backend/src/test/java/com/bhawana/lms/security/RateLimitPropertiesValidationTest.java`
- `frontend-2/src/lib/api/http-client.test.ts` (new file or extension)

Edit:
- `backend/src/main/java/com/bhawana/lms/security/RateLimitProperties.java` — replace the two int knobs with `List<RateLimitRule> rules`. Keep deprecated getters/setters out (this is a breaking config change — the user has opted into the rules table). Add `@PostConstruct` validation.
- `backend/src/main/java/com/bhawana/lms/security/RateLimitFilter.java` — `resolveTarget` walks `rules` in order; first match wins. `SUBJECT_AND_APPLICATION` returns *two* targets; the filter consumes both buckets atomically (atomic-enough: consume bucket A, then B; if B refuses, log but do **not** refund A — refund-on-second-failure is not in scope and adds little value at this scale).
- `backend/src/main/java/com/bhawana/lms/security/RateLimitConfig.java` — likely unchanged; confirm no implicit dependency on the removed `authPerMinute`/`lspWritePerMinute` getters.
- `backend/src/main/resources/application.yml` — replace the two budget knobs with the rules list shown above.
- `backend/src/main/resources/application-test.yml` — high per-rule overrides.
- `frontend-2/src/lib/api/http-client.ts` — `ApiError` gains `retryAfterSeconds: number | null`; `performJsonRequest` and `requestBlob` populate it on 429.
- Any FE toast helper / mutation hook that displays `ApiError` — surface the seconds value if present. Identify call sites via grep for `ApiError` references in `frontend-2/src`.

Untouched (deliberately):
- `service/AlertRuleEvaluationService.java` — `emitRateLimitBreach` API unchanged; new buckets reuse it.
- `common/api/ApiError.java` (backend) — 429 body shape already correct.
- `security/RateLimitConfig.java` Redis wiring — already cluster-correct.
- The audit-doc-suggested "switch to Redis" path — moot per the reframe.

#### Effect on app (revised)

- **Users / LSPs:** identical behaviour under normal load. Hitting a bucket → 429 + `Retry-After`; the FE renders a typed toast ("Too many requests. Please wait N seconds.") instead of the current opaque "Request failed".
- **Security:** doc-scrape, report-scrape, refresh brute-force, password brute-force, mock-outcome churn all bounded. Per-rule keying matrix defeats the obvious bypasses (botnet IPs vs. credential rotation; one admin vs. many admins).
- **Ops:** alert volume on `RATE_LIMIT_EXCEEDED` rises proportionally to attacker activity. Audit Explorer is the destination; no new dashboard. `bucketKey` prefix (`reports:subject:`, `mock-outcome-app:`, etc.) identifies the family in the alert payload.
- **Perf:** one extra Redis CAS per matched request, plus a second for `IP_AND_SUBJECT` / `SUBJECT_AND_APPLICATION` rules. Already paid for the existing three rules; the marginal cost on new families is ~sub-millisecond against local Redis. No measurable impact at current scale.
- **Partners (LSPs):** new GET cap on `/lsp/loan-applications/*/documents/**` at 120/min per LSP. Pre-launch, no partner comms required (per the #62 reframe). When real LSPs onboard, validate this against actual integration patterns; tune in YAML without a release.
- **Risk accepted:** an attacker who controls multiple authenticated identities with multiple IPs can multiply their budget. This is the same residual risk every per-rule rate limit carries; lockout (#155) is the complementary control.

#### Cluster impact / sequencing

- **#127 [F-4]** — exact duplicate; closes in the same PR.
- **#155** — independent. Lockout pipeline composes with rate limit (rate limit caps req/min; lockout blocks after N failures). Cross-reference in both directions in PR descriptions so future readers don't conflate them.
- **#142** — independent. Different cache (allowlist vs. bucket store), different layer. Calling out the boundary in the PR description prevents conflation.
- **#80, #133** — likely customers of the rules table when they land. The matcher abstraction this PR introduces is the seam they'll plug into. Slice 12's validation acts as the safety net for those future additions.
- **#69** — stays open. Rate limit on report downloads is defence-in-depth, not a substitute for masking.
- **#62 PR-(c)** — alert plumbing reused as-is; no new alert type.
- **PR description note:** call out that this PR removes the original audit-doc "switch to Redis" option (already wired), introduces the rules table, and adds four new families + the FE interceptor. Single delivery, single review.

**Dependencies / sequencing:** independent — ships standalone. No upstream blockers; #62's alert plumbing is already on `main`. Implementation order matches the slice order above.

**Implementation status (2026-06-06):**

Shipped the grilled rules-table design end-to-end:

- `RateLimitProperties` now carries `enabled` + `List<RateLimitRule> rules` with `@PostConstruct` validation (`KeyStrategy`, duplicate ids, `SUBJECT_AND_APPLICATION` permit shape).
- `RateLimitFilter` walks rules in order; supports multi-bucket strategies (`IP_AND_SUBJECT`, `SUBJECT_AND_APPLICATION` via paired rules).
- `application.yml` carries the full rule list (auth, lsp-write, refresh, password, admin reset-password, docs-ops, docs-lsp, reports, mock-outcome subject + application buckets).
- `frontend/src/lib/api/http-client.ts` — `ApiError.retryAfterSeconds` populated from `Retry-After` on 429; unit test in `http-client.test.ts`.
- Tests: `RateLimitRuleMatcherTest`, `RateLimitPropertiesValidationTest`, `RateLimitFilterIntegrationTest` (auth-login IP + reports SUBJECT tracer coverage). Test profile keeps `app.rate-limit.enabled: false` so the wider suite is unaffected.

**Follow-up (optional, not blocking closure):** TDD slices 3–10 and 12 from the grilled plan (dedicated per-surface integration tests, alert-emission e2e, high-budget overrides per-rule in test YAML) can land incrementally; core matcher + config + FE 429 UX are live.

**Closes:** #127 (duplicate).

---

### #82 — Verify SSRF protection wired into webhook URL update path
**Labels:** gap, security, verification · **Link:** https://github.com/sid12701/lms/issues/82 · **Status:** **VERIFICATION PASSES — CLOSE** (audited 2026-06-02)

**Problem (plain English):** `SsrfSafeUrlValidator` exists; need to verify every webhook URL write path calls it. Otherwise admin can point a webhook at `169.254.169.254` and exfil cloud metadata.

**Possible fixes:**
1. **Verify wiring; add unit tests for known bad IPs** — confirmation + safety net.

**Recommended:** Option 1. Cheap to do.

**Effect on app:** SSRF closed in this surface area.

**Detailed solution after discussion (2026-06-02 audit) — VERIFICATION PASSES:**

SSRF is wired at **both** layers (defence in depth):

1. **Write-time** — `AdminDirectoryService.updateWebhookSubscription` line 212 calls `SsrfSafeUrlValidator.validate(normalizedEndpointUrl)` before mutating the LSP entity. This is the **only** path that touches `Lsp.webhookEndpointUrl` — verified by grepping every caller of `updateWebhookSubscription` (only `LspAdminController:83` → `AdminDirectoryService:191`; no other writes to the column anywhere in `backend/src/main/java`). `LspAdminController.updateWebhookSubscription` is `SYSTEM_ADMIN`-only and routes through this validating service.
2. **Dispatch-time** — `HttpWebhookDeliveryClient.deliver` line 28 calls `SsrfSafeUrlValidator.validate(request.endpointUrl())` before opening the HTTP connection, so even if a stale or pre-migration row carried an unvalidated URL it cannot reach the network.

**Minor residual:** when the admin updates with `enabled=false`, the URL is saved without validation (line 205 guards validation behind `if (enabled)`). This is benign because the dispatch path (a) requires `lsp.isWebhookEnabled()` to even attempt delivery and (b) revalidates at delivery time via the dispatch-time check above. Toggling enabled later re-routes through the same `updateWebhookSubscription` and re-validates.

**Action:** close the ticket as verified. Optional follow-up: add a unit test against `AdminDirectoryService` that asserts `SsrfSafeUrlValidator.validate` is invoked for the metadata-IMDS IP `169.254.169.254`, plus a guard test pointing at the in-cluster service `localhost`. Not required for closure; nice-to-have for regression protection.

**Closes as duplicates (same evidence):** **#143 [SEC-Δ-5]** and **#162 [V-2]** — both ask for the same verification.

---

### #84 — [B-2] Mock disbursement providerRequestId is 12-char UUID prefix, no unique index
**Labels:** bug, mocked-flow, scale-risk · **Link:** https://github.com/sid12701/lms/issues/84

**Problem (plain English):** Truncated UUIDs can collide. No DB-level uniqueness on the column. Reconciliation could mix up two requests.

**Possible fixes:**
1. **Use full UUID + add unique index** — trivial.
2. **Replace with the real adapter (which will issue its own IDs)** — same fix via #61.

**Recommended:** Option 1 as a hygiene fix; obviated when #61 lands.

**Effect on app:** No more theoretical collision; minor migration to add the index.

**Detailed solution after discussion (2026-06-01) — IMPLEMENT (GREEN-LIT): part of bundled "mock disbursement hygiene" PR with #95 + #96.**

**Reframe under #61's decision:** the mock adapter **is** the production disbursement implementation until executive approval for a real provider lands. So this is a production-code hygiene fix, not a "will be moot when real adapter ships" issue.

**Audit findings:**
- `MockLoanDisbursementAdapter.java:20` constructs `providerRequestId` as `"MDB-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase()`. That's a 12-char hex prefix → **48 bits of entropy**, not the 128 a full UUID provides.
- Birthday-collision risk hits ~50% at ~16 million IDs. Negligible at current scale, but the truncation has no functional reason — it appears to have been a cosmetic choice.
- `LoanDisbursementRequestLog.provider_request_id` has no `UNIQUE` constraint, so even a real collision would silently corrupt reconciliation.
- The label `MOCK_DISBURSEMENT` already lands on the response and on the loan-detail UI (per #61's solution), so the prefix `MDB-` is decorative — losing it on a real-adapter swap is fine; the adapter's own ID format will replace it.

**Implementation (lives in the bundled PR):**
1. **Adapter change** in `MockLoanDisbursementAdapter.java:20`:
   ```java
   String providerRequestId = "MDB-" + UUID.randomUUID().toString().toUpperCase();
   ```
   Full 36-char UUID. Prefix kept so log lines and operator UIs still read clearly.
2. **Flyway migration** (next sequential V77 or similar):
   ```sql
   ALTER TABLE loan_disbursement_request_log
       ADD CONSTRAINT uq_loan_disbursement_request_log_provider_request_id
       UNIQUE (provider_request_id);
   ```
   This safety net applies to any future adapter too — locking in the invariant at the schema level so a contributor can't accidentally produce duplicates from a future real provider.

**Why a unique constraint and not just longer entropy:** 128-bit entropy makes accidental collisions effectively impossible, but a unique constraint also defends against bugs in any future adapter implementation that derives IDs deterministically (e.g., from a partner-supplied reference). The DB enforces correctness regardless of upstream logic.

**TDD (subset of the bundled PR's tests; see #95 / #96 for the full bundled list):**
- **TRACER — `provider_request_id_is_a_full_UUID_with_MDB_prefix`.** Issue a disbursement request through the LSP/ops path; assert `LoanDisbursementRequestLog.providerRequestId` matches `^MDB-[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}$`.
- **PG-only schema test** — extend the existing `SchemaCheckConstraintsPostgresTest` (or sibling) to assert `uq_loan_disbursement_request_log_provider_request_id` exists. Locks in the constraint so a future contributor can't drop it.
- **Behavioural collision test** — attempt to insert a duplicate `provider_request_id` directly via the repository; assert `DataIntegrityViolationException`. Documents the invariant via the failure path.

**Effect on app:**
- Reconciliation no longer carries a theoretical-but-real collision risk.
- Schema-level invariant guards future adapter swaps.
- `LocalDemoPortfolioSeedService` unchanged — full UUIDs are accepted by everything downstream.

**Dependencies / sequencing:** Ships with #95 and #96 in the bundled "mock disbursement hygiene" PR. Standalone otherwise. No blocking dependencies.

---

### #88 — [B-6] enqueueIfSubscribed runs inside user-request critical path
**Labels:** bug, scale-risk · **Link:** https://github.com/sid12701/lms/issues/88

**Problem (plain English):** Webhook enqueue + serialize + sign happens inside the user-facing request transaction. Every status change pays this cost.

**Possible fixes:**
1. **Defer signing to the dispatcher; enqueue only minimal row** — perf win.
2. **Profile first; do nothing if not material** — proves the case.

**Recommended:** Option 2 first. If profiling shows ≥10% of write latency, then Option 1.

**Effect on app:** Possibly faster writes; otherwise no change.

**Detailed solution after discussion (2026-06-01) — DEFERRED, ISSUE STAYS OPEN:**

**Decision:** No code change in this pass. Log the audit findings into the issue; revisit with production profiling data once real load exists. The candidate atomicity regression tests below are the implementation plan to attach to the eventual fix PR — they lock in the invariant the in-TX enqueue is protecting.

**Audit findings that change the framing:**

1. **The audit doc's premise was partly wrong about cost composition.** The audit said "enqueue + serialize + **sign** happens inside the user-facing request transaction." Tracing `WebhookOutboxService.enqueueIfSubscribed` (lines 58–89), the in-TX path actually does only: (a) cheap subscription check (`lsp.getWebhookEventTypes().contains(eventType)`), (b) Jackson serialization of a small `LinkedHashMap` envelope, (c) one `webhookEventOutboxRepository.save(...)` INSERT. **HMAC-SHA256 signing does NOT happen here.** Signing is deferred to `buildDeliveryRequest` (line 267), called from `dispatchEvent` (line 156), called from `dispatchPending` (line 111) — all of which run on the background dispatcher thread, not the user request. Real user-thread cost is ~Jackson (1–5ms typical) + one INSERT (1–3ms) per enqueue.
2. **In-TX enqueue is the textbook transactional outbox pattern, not a bug.** The atomicity guarantee the code is providing — business mutation and outbox row commit together, or both roll back — is the whole point of the outbox. Moving the enqueue to `REQUIRES_NEW`, async, or message-bus publish reintroduces the failure mode (business state changes but no event recorded, or events recorded for state that rolled back) the outbox exists to prevent.
3. **Call-site spread is modest:** 11 call sites across `LoanApplicationService` (3), `LoanApplicationLifecycleService` (4), `LoanRepaymentCommandService` (2), `LoanForeclosureCommandService` (2). A typical write request emits 1–3 events. Worst-case latency tax per request: ~5–25ms, against a typical 50–200ms loan-graph write.
4. **No production profiling data exists pre-launch** to claim this is or isn't material. The audit doc's own recommendation was "profile first; do nothing if not material" — that recommendation stands.

**Why we are deferring (not closing):** even though the in-TX enqueue is correctly designed, two operational unknowns block a clean close:
- We don't yet know payload sizes under real LSP-subscribed event mixes. If a future event type carries a multi-KB payload, the Jackson cost moves.
- We don't yet know how many events fire per user request under production transition patterns (especially if rule-engine evaluation triggers multiple status changes in one TX — see #90 for the related concern).
Closing the issue would force a re-open the moment either dimension grew. Deferring with the analysis preserves the context.

**Candidate fixes on the table for the eventual implementation pass (in preference order):**

- **Option A — Do nothing, lock the invariant.** Write atomicity regression tests so a future contributor can't accidentally move enqueue out of the request TX. Reviewer reads the tests, understands the design, moves on. Cheapest path; assumes profiling never shows a problem.
- **Option B — Minimal-row enqueue.** Save only `(lspId, eventType, aggregateType, aggregateId, loanApplicationId, payloadRef)` in the outbox; dispatcher builds the JSON envelope at delivery time from the live aggregate. Loses the snapshot-at-enqueue property (envelope can drift if upstream state changes between enqueue and dispatch — usually a feature: dispatcher sends the freshest state; sometimes a footgun: payload no longer reflects the moment of the business event). Worth the conversation only if profiling shows Jackson is ≥10% of write latency.
- **Option C — Pre-built JSON columns.** Keep in-TX, but cache the JSON envelope on the entity and bulk-flush. Engineering complexity not worth the saving at this scale.
- **Option D — Hardening: serializability unit tests for every event type's payload builder.** Pre-validate that each `WebhookEventType`'s payload map serializes cleanly so an in-TX `IllegalStateException` (line 152) can never roll back a business mutation. Low value if no such rollback has been observed in practice; trivial to add later if it becomes a concern.

**Recommended starting point for the eventual fix (regression-test-first, code-change-second):**
1. Land the three atomicity regression tests below as **Option A's standalone safety net** — keeps the design honest under refactor.
2. After production load exists, capture p50/p95/p99 write latency split by "enqueues N events." If Jackson + INSERT is materially big, escalate to Option B.

**TDD plan — atomicity invariant tests (the implementation plan attached to this issue):**

These tests are valuable on their own; they describe the invariant the code already enforces. They go in `WebhookOutboxServiceIntegrationTest` (or a new sibling) and exercise the controller layer so they're behaviour-through-public-interface.

1. **TRACER — `business_mutation_and_outbox_row_commit_atomically_on_success`.** Drive a real status transition through the ops controller; assert (a) loan-application row in new status, (b) one new `webhook_event_outbox` row with matching `aggregateId` and `loanApplicationId`. Both must be in the same committed state on the same connection.
2. **`business_mutation_rollback_also_rolls_back_outbox_row`.** Inject a failure *after* `enqueueIfSubscribed` returns but *before* the caller's TX commits (e.g., by hooking a `TransactionSynchronization` that throws on `beforeCommit`, or by triggering a downstream RuntimeException in the service path). Assert: no application status change AND no outbox row. Locks in: nobody can move enqueue to `REQUIRES_NEW` without this test screaming.
3. **`failed_outbox_serialization_rolls_back_business_mutation`.** Use a controlled `WebhookEventType` (or a temporary test-only event) whose payload-builder returns a non-serializable value (e.g., a `BigDecimal` wrapped in an object Jackson can't handle without configuration); call into the controller; assert IllegalStateException bubbles, the application row did NOT update, and the outbox stays clean. Verifies the atomicity property in the failure direction.

**Mocking discipline:** no mocking of `WebhookEventOutboxRepository`, `ObjectMapper`, the lifecycle service, or any internal collaborator. The atomicity property is an end-state property and must be asserted on real persistence (H2 for unit-grade; PG for the integration layer). Clock and TX-synchronisation hooks are framework boundaries — fine to wire.

**Tests we deliberately do NOT write here:**
- Throughput / latency assertions — owned by future load testing, not by this safety net.
- Webhook signing format / delivery semantics — owned by #129 and #130.
- Dead-letter / redrive — owned by #73.

**Effect on app:**
- Zero behavioural change from this deferral.
- When implemented (Option A path), three new tests lock in the atomicity invariant. Existing call sites unchanged. CI catches anyone re-introducing the "decouple enqueue" anti-pattern.
- Issue stays open as the home for production-load profiling data once it exists.

**Dependencies / sequencing:**
- Tests can land independently any time; no dependency.
- Any actual code change (Option B) depends on production-load profiling, which depends on the system being live.
- Closely related: #90 (rule-engine re-eval cost) — if #90 lands first and amplifies the per-request event-count, this issue's latency picture changes and Option B becomes more attractive.

---

### #90 — [B-8] autoApproveIfEligibleForLsp re-evaluates 8 rule families on every call
**Labels:** bug, scale-risk · **Link:** https://github.com/sid12701/lms/issues/90

**Problem (plain English):** Same rule engine runs on doc upload, field update, and disbursement request — N+1 DB reads each time.

**Possible fixes:**
1. **Debounce per loan (e.g., 2s)** — coalesces bursts.
2. **Move to async worker observing a change-feed** — strongest; biggest refactor.
3. **Profile first** — confirm bottleneck.

**Recommended:** Option 3, then Option 1 if needed.

**Effect on app:** Possibly faster upload paths; minor risk that debounced eval is slightly stale.

**Detailed solution after discussion (2026-06-01) — DEFERRED, ISSUE STAYS OPEN; one concrete sub-task verified:**

**Decision:** No engine refactor in this pass. Log the corrected cost picture and defer the architectural change pending production-load data. The one concrete sub-task — verify the cross-LSP open-loan index — was checked and is **already in place** (V17 line 16: `CREATE INDEX idx_loan_account_borrower ON loan_account (borrower_id)`), so no migration is required either. The candidate fixes below are the menu for the eventual production-data-driven decision.

**Audit findings that change the framing:**

1. **"N+1 DB reads" is not accurate.** Each `LoanAutoApprovalRuleEngine.evaluate(application)` call performs **exactly 3 DB reads**, not N+1:
   - `mappingRepository.findByLsp_IdAndLoanProduct_Id(...)` — indexed.
   - `checklistRepository.findByLoanApplication_IdOrderByCreatedAtAsc(...)` — indexed via the existing `loan_application_id` FK.
   - `activeLoanChecker.findOpenLoansAcrossAllLsps(borrowerId)` — uses `idx_loan_account_borrower` (verified V17). Returns rows with `status ∈ OPEN_STATUSES`; the status filter is post-index, but bounded by borrower's loan count which is small in practice.
   The other passes (`evaluateAmountTenureRate`, `evaluateBorrowerFields`) are pure in-memory checks on the already-loaded `LoanProduct` and `Borrower`. The "8 rule families" the audit named refers to the 8 `RuleCode` enum values, evaluated through 5 evaluation methods, costing 3 DB reads total.
2. **Trigger surface is narrower post-#62 than the audit doc described.** Audit said "doc upload, field update, and disbursement request." Current callers in code:
   - `LoanDocumentService:133` (single doc upload completion path)
   - `LoanDocumentService:168` (batch / patch doc-status path)
   - `LoanDisbursementService:118` (was line 43 pre-#62 PR (b)) — **NOT removed by #62 PR (b).** PR (b) removed the LSP `POST /disbursement` endpoint and re-routed disbursement through the new worker, but the `requestDisbursementForLsp` method body itself was retained as orphaned dead code (still `@Transactional`, still calls `autoApproveIfEligibleForLsp` at line 118). Zero production callers, but the live code still references the engine through this path. See § #62 "Implementation status" follow-up note and § #85 / § #135 for the planned cleanup. After **#85 / #135 / #116 bundle lands**, this caller goes; until then, the engine fires from disbursement code that nobody calls but the compiler still tracks.
   - No "field-update" caller exists today. The audit doc may have anticipated one; today there isn't one.
3. **Cost picture relative to its caller.** Doc-upload paths are already paying R2 upload + audit row + document-row insert (typically 100–500 ms total). Adding 3 indexed reads (~5–15 ms typical, possibly more on the cross-LSP query for borrowers with long history) is <5 % of the surrounding work. **Not a hot path; not a bug at current scale.**
4. **`findOpenLoansAcrossAllLsps` index check — done.** `idx_loan_account_borrower (borrower_id)` exists since V17. The query is `existsByBorrower_IdAndStatusIn` / `findByBorrower_IdAndStatusIn`. The planner uses the borrower index, then filters in memory on status. A composite `(borrower_id, status)` index would be marginally faster but is **not** needed at current scale — single-column borrower index is fine while typical borrowers have ≤5 open loans across LSPs. Capture as a follow-up only if EXPLAIN shows the in-memory status filter dominating.
5. **The audit doc's debounce recommendation has a UX cost the audit didn't surface.** Debouncing means the LSP-visible state transition from `AWAITING_APPROVAL → APPROVED_PENDING_DISBURSAL` is delayed by the debounce window. Today the transition is observable in the response of the doc-upload call (or the immediate follow-up GET). Eventually-consistent approval changes the LSP-facing contract; needs an "approval status pending evaluation" sentinel, plus a "your loan was approved" signal (poll / webhook / SSE). Not a free swap.

**Why we are deferring (not closing):** two unknowns prevent a clean close:
- Real-world batch doc-upload semantics in `LoanDocumentService:168` — if the controller loops one-document-per-evaluation, a 10-doc batch is 30 DB reads in a tight TX. Worth measuring under load before deciding whether a per-loan dedup makes sense.
- The cross-LSP query cost scales with borrower history. We don't yet know the distribution of borrower-loan-count under production data. The index is right; the per-borrower row count is the unknown.
Closing the issue would force a re-open the moment either dimension grew. Deferring with the audit findings preserves the context.

**Candidate fixes on the table for the eventual implementation pass (in preference order):**

- **Option A — Single-evaluation guard for batch doc operations.** In `LoanDocumentService:168` (and any future batch caller), evaluate once per batch instead of once per document. Smallest, most surgical fix; preserves the synchronous "your loan was approved" UX. Likely the highest-leverage change if profiling shows batch uploads dominate.
- **Option B — Skip-eval-when-status-can't-have-flipped guard.** If `application.getStatus() != AWAITING_APPROVAL`, short-circuit the engine call from the caller side. The engine's transition logic already handles this internally (line 376 `LoanApplicationLifecycleService.autoApproveIfEligibleForLsp`), but the eval still runs. Adding a pre-check at the caller skips the 3 DB reads when the loan is already past the approval gate. Cheap and safe — caller-side `if` only.
- **Option C — Debounce per `applicationId`** (audit doc Option 1). Window 2–5 s. Trades synchronous response semantics for evaluation coalescing. Requires UX changes to the LSP-facing contract (status response shape).
- **Option D — Async worker on doc-state events** (audit doc Option 2). Largest refactor; fully eventual-consistent approval. Worth doing only if doc-upload latency genuinely becomes the user-visible bottleneck.
- **Option E — Cache evaluation result by `(applicationId, version)`** with invalidation on relevant entity mutations. Adds a cache + invalidation surface; complexity not worth the saving at this scale.
- **Option F — Composite `(borrower_id, status)` index on `loan_account`.** Marginal future optimization. Only ship if EXPLAIN shows the in-memory status filter is hurting at production scale.

**Recommended starting point for the eventual fix:** Option B first (cheap, no contract change, ~5-line PR) then Option A if batch profiling shows it matters. Options C/D only if A+B don't move the needle on real load data.

**Cross-issue impact:** **#135** ("caller-defensive auto-approval safety") becomes partially mooted by #62 PR (b), but the document-upload paths still call `autoApproveIfEligibleForLsp`, so #135's safety net isn't fully gone. The #135 write-up should explicitly note that #90's Option B and #135 cover the same caller-side guard rail and may close together.

**TDD plan for the eventual implementation (captured for the next person; not executed now):**

1. **TRACER — `doc_upload_completing_required_checklist_transitions_to_APPROVED_PENDING_DISBURSAL`.** Through the LSP controller, not the engine: upload the last required doc; assert (a) `getStatus() == APPROVED_PENDING_DISBURSAL`, (b) a status-transition row exists, (c) the worker from #62 PR (b) eventually picks the loan up.
2. `doc_upload_with_other_rule_failures_does_not_transition`. Last doc uploaded but borrower-fields blank → status stays `AWAITING_APPROVAL`; failure list captured in the audit note.
3. `batch_doc_upload_evaluates_at_most_once_per_batch` (Option A test). Upload N docs in one batch; assert only one status-transition row written; assert engine evaluation count is 1, not N (visible via a counter exposed on `/actuator/metrics`, not via mocking the engine).
4. `doc_upload_on_already_approved_application_does_not_invoke_engine` (Option B test). Upload an extra "nice-to-have" doc on an `APPROVED_PENDING_DISBURSAL` application; assert the engine eval counter does not tick.
5. `findOpenLoansAcrossAllLsps_uses_borrower_index` — a `@SqlExplain`-style integration test or an existing PG-only test that runs `EXPLAIN` and asserts the borrower-index is used. Locks in: nobody drops the V17 index thinking it's dead weight.

**Mocking discipline:** the rule engine is an internal collaborator — **not mocked**. Tests assert the engine ran (or didn't) via Micrometer counter increments or via the existence of an audit row, not via mocked calls. Repositories, lifecycle service, document service — not mocked. Tests drive the LSP controller (the public interface).

**Effect on app:**
- Zero behavioural change from this deferral.
- The index check confirms no perf cliff is hiding today.
- When implemented (Option B + maybe A), doc-upload paths skip the eval when status can't transition. ~5–15 ms shaved per redundant call; meaningful only at high batch throughput.
- Issue stays open as the home for production-load profiling once it exists.

**Dependencies / sequencing:**
- Depends on #62 PR (b) landing first (removes one of the three caller sites, simplifies the analysis).
- Closely related: **#135** — the eventual #90 Option B PR and #135 may close together; cross-link in the eventual write-ups.
- The composite index (Option F) is a strictly later concern; only ship if EXPLAIN evidence appears.

---

### #91 — [B-9] AuditExplorerRepository builds SQL by enum-branched string concat
**Labels:** bug, code-quality, scale-risk · **Link:** https://github.com/sid12701/lms/issues/91

**Problem (plain English):** SQL string concat by enum branch is bind-safe but type-unsafe. Adding a new stream silently breaks H2 vs PG parity.

**Possible fixes:**
1. **Per-stream projection registry + H2/PG parity test** — proper fix.
2. **Move to JPQL** — loses native UNION ALL flexibility.

**Recommended:** Option 1.

**Effect on app:** Future stream additions are compile-time changes. Less drift risk.

**Detailed solution after discussion (2026-06-01) — DEFERRED, ISSUE STAYS OPEN; Option E (registry + parity tests on H2 and PG) is the green-lit plan for when implementation happens:**

**Decision:** No code change in this pass. The audit doc's labels ("bug", "scale-risk") overstate the situation — this is purely a maintainability + silent-drift concern, not a bug, and there is no current correctness defect. The implementation plan below (Option E from the grill) is locked in so the eventual fix doesn't have to re-derive it. Plan ahead of execution because **more streams are coming** (#159 explicitly tracks "Audit Explorer only covers 4 streams"), and the cost of adding the 5th stream the current way would force the refactor anyway. Doing it pre-emptively makes #159 trivial.

**Audit findings that change the framing:**

1. **No SQL injection risk.** Every user-provided value uses named bind parameters (`:__actorUsername`, `:__lspId`, `:__loanApplicationId`, `:__borrowerId`, `:__productId`, `:__since`, `:__until`) — see `AuditExplorerRepository.buildUnionSql` lines 131–137 and the filter clauses at 162–167, 192–197, 222–227, 251–254. The string concat is **structural** (table names, alias prefixes, cast types) — not data. The audit doc's "bind-safe but type-unsafe" framing is accurate.
2. **Type-parity drift is the real risk.** Each branch independently writes 16 column expressions with hand-coded casts: `cast('APPLICATION' as varchar(32))`, `cast(e.note as varchar(500))`, `cast(null as uuid)`, etc. (lines 142–168, 172–198, 202–228, 232–255). If a future contributor adds a stream and forgets one cast — or worse, types it as `varchar(64)` when PG expects `varchar(500)` — the UNION ALL fails at query time. **H2 and PG implicitly coerce types in UNION differently**, so a query that passes on H2 may fail on PG.
3. **The duplication is ~25 lines × 4 streams ≈ 300 LoC of near-identical code.** Each new stream is a copy-paste of an existing branch with surgical edits. The error-prone part is exactly the column-cast list.
4. **`effectiveStreams` filter logic** (lines 109–123) is enum-coupled. Adding a stream means updating two places: `buildUnionSql` and `effectiveStreams`. A registry pattern collapses this to one place.
5. **No drift-detection test today.** No assertion that every stream branch produces all 16 columns of `UnifiedAuditEventRow` (lines 281–299) with matching SQL types on both databases. Silent breakage on stream addition is invisible at CI time.
6. **No performance concern at this scale.** PG handles UNION ALL efficiently with the per-branch filter pushdown; this isn't an operational scale risk. The "scale-risk" label refers to *codebase scale* (more streams in future), not runtime scale.

**Why defer despite the green-lit plan:**
- No current bug, no user-facing issue, no security concern.
- The drift risk only materialises when a 5th stream gets added. That work is owned by **#159** and isn't scheduled.
- Implementing Option E now (~250 LoC + tests) and then having #159 immediately rework it as new streams are designed would be wasted churn. Cleaner: when #159 is grilled, treat this refactor as #159's *first PR* and add streams against the new registry pattern.
- The doc captures the full plan so the work is shovel-ready when #159 picks up.

**Cross-issue linkage:** This issue is the foundation for **#159**. When #159 is grilled, the recommended sequencing is: (a) ship Option E from this issue as #159's PR 1 (refactor existing 4 streams to the registry pattern + parity tests); (b) add each new stream as a registry entry in subsequent PRs. Each new stream becomes a ~20-line `StreamProjection` constant plus an audit table — no SQL hand-writing.

**Implementation plan for the eventual fix — Option E (registry + parity tests on H2 and PG):**

1. **Introduce a `StreamProjection` Java record** in `com.bhawana.lms.repo`:
   ```java
   public record StreamProjection(
       AuditStream stream,        // enum identity
       String tableExpression,    // "loan_application_audit_event e join loan_application la on la.id = e.loan_application_id"
       Map<String, ColumnExpression> columns,  // one entry per UnifiedAuditEventRow field
       List<String> filterClauses // ["(:__lspId is null or la.lsp_id = :__lspId)", ...]
   ) { … }

   public record ColumnExpression(String sqlExpression, String sqlType) { … }
   ```
2. **Define one `StreamProjection` constant per stream** as static singletons. Each declares its 16 column expressions explicitly. The shared expressions (e.g., `cast(null as varchar(64))` for unused columns) live as named constants for reuse.
3. **Rewrite `buildUnionSql` as a registry iteration:**
   ```java
   private String buildUnionSql(Set<AuditStream> streams, ..., Map<String, Object> parameters) {
       registerCommonParameters(parameters, query);
       return PROJECTIONS.stream()
           .filter(p -> streams.contains(p.stream()))
           .map(StreamProjection::toSelectSql)
           .collect(Collectors.joining("\nunion all\n"));
   }
   ```
4. **`effectiveStreams` becomes registry-driven:** each `StreamProjection` declares which filters it supports (`supportsLoanApplicationFilter()`, `supportsProductFilter()`, etc.) and `effectiveStreams` filters via that capability set instead of hardcoding enum names.
5. **Parity test infrastructure**: extend the existing test harness to run a parameterised test suite once on H2 and once on PG (the project already has `*PostgresTest` markers for this). The parity tests assert each projection's output schema matches `UnifiedAuditEventRow` on both engines.

**Behaviours we deliberately do NOT change:**
- The four existing stream behaviours (APPLICATION, INTAKE, DOCUMENT_ACCESS, PRODUCT).
- The `countDocumentAccessByDocumentType` query (lines 86–107) — it's a separate single-table aggregation, not a UNION; out of scope.
- The pagination + count-wrap pattern (lines 71–82).
- The `UnifiedAuditEventRow` record shape.

**TDD plan (lock-in tests for the eventual refactor; tests are also useful on the current code as a drift-prevention safety net even before the refactor):**

1. **TRACER — `every_stream_branch_produces_all_16_columns_of_UnifiedAuditEventRow_on_H2_and_PG`.** Parameterised over the 4 streams × 2 databases (8 test cases). For each: execute a single-stream `SELECT ... LIMIT 0` of the projection SQL; inspect `ResultSetMetaData`; assert every `UnifiedAuditEventRow` record field is present with the expected SQL type. Adding a stream without satisfying the projection contract fails this test loudly. **This is the test the audit doc is really asking for.**
2. `union_all_query_with_all_streams_active_returns_rows_from_each_stream_in_correct_shape`. Seed at least one row per stream (APPLICATION audit, INTAKE audit, DOCUMENT_ACCESS audit, PRODUCT audit); query with all streams enabled; assert rows from each stream are present and column-shape-correct. Lock in cross-stream interop.
3. `effectiveStreams_logic_excludes_PRODUCT_when_loan_or_borrower_or_lsp_filter_set`. Drive each of `loanApplicationId`, `borrowerId`, `lspId` independently; assert the generated SQL does not include the PRODUCT branch. Catches accidental changes to the filter-routing logic during the refactor.
4. `effectiveStreams_logic_restricts_to_PRODUCT_when_productId_filter_set`. Symmetric: only PRODUCT branch appears.
5. (Registry-specific) `every_stream_projection_in_the_registry_declares_all_UnifiedAuditEventRow_fields`. Reflection over `UnifiedAuditEventRow`'s record components: for each, assert every `StreamProjection.columns` map contains an entry. Compile-time-ish guarantee that a stream can't be added with missing columns.
6. (Registry-specific) `stream_projections_with_supportsLoanApplicationFilter_actually_join_loan_application`. Assert capability-flag honesty: if a projection claims to support an LSP filter, its `tableExpression` must reference a column that resolves to `lsp_id`. Catches "I added the flag but forgot to wire the join."
7. `count_query_total_count_equals_paged_query_row_count_when_total_count_fits_in_one_page`. End-to-end correctness check that the count-wrap and paged select produce consistent totals. Already an implicit assumption; lock it in.
8. `paged_query_orders_by_occurred_at_desc_then_native_id_desc`. Lock in the sort order across the refactor (line 46).
9. `paged_query_limit_offset_pagination_is_stable_across_repeated_calls_on_a_static_dataset`. Seed N rows, page through (limit=10), assert no overlap and no skipping. Locks in: registry refactor doesn't break pagination.

**Mocking discipline:** zero mocking. This repository's contract IS the database; the parity tests rely on real H2 and real PG behaviour. Tests use the existing Spring Boot test harness with both databases configured.

**Effect on app:**
- Zero behavioural change from the refactor itself.
- Adding a new audit stream (when #159 lands) becomes a ~20-LoC change (one registry entry + the underlying audit table) instead of a ~75-LoC copy-paste.
- Silent H2/PG drift on stream additions becomes a loud CI failure.
- Codebase has a worked example of "registry over conditional branches" that other audit-style features can borrow.

**Dependencies / sequencing:**
- This issue is the foundation refactor; **#159** is the work that consumes it. The cleanest path is to treat Option E as #159's PR 1 and add new streams as PRs 2–N against the new pattern.
- No other open issue blocks this.
- Standalone if shipped independent of #159, but pre-implementation churn risk argues for the bundled approach.

---

### #92 — [B-10] Document download swallows IllegalStateException — storage outage masked as 404
**Labels:** bug · **Link:** https://github.com/sid12701/lms/issues/92

**Problem (plain English):** Storage tier outage looks like "doc was deleted." Wrong incident response.

**Possible fixes:**
1. **Distinguish exception types (`DocumentNotFound` → 404, `DocumentStorageUnavailable` → 503)** — clear.

**Recommended:** Option 1.

**Effect on app:** Correct status codes. Better alerts and incident response.

**Detailed solution after discussion (2026-06-01) — IMPLEMENT (GREEN-LIT):**

**Decision:** Ship the fix as a single contained PR. Three new typed exceptions, mapping centralised in `GlobalExceptionHandler`, controller `try/catch` removed, Micrometer counter + alert rule on 503 rate. Scope is bounded (~150 LoC code + ~9 tests); the cost/value ratio is unambiguously positive and the audit doc's framing actually understated the bug surface.

**Audit findings that sharpened the framing:**

The audit doc said "Storage tier outage looks like 'doc was deleted.'" That's true but the real bug surface is broader. `IllegalStateException` is currently doing **six different jobs** through the download path:

| Source | What it actually means | Maps to today | Should map to |
|---|---|---|---|
| `LoanDocumentService.retrieveDocumentContent` line 44 ("not LMS-managed or no storage key") | Legitimate "no content to retrieve" | 404 (controller catch line 233) | 404 ✓ |
| `LoanDocumentService.buildDocumentZip` line 67 ("no documents found") | Legitimate "this loan has no docs" | 404 (controller catch line 206) | 404 ✓ |
| `FileSystemLoanDocumentStorageService.retrieve` line 41 (`IOException` retrieve) | Mixes "file missing on disk" and "disk I/O failure" | 404 | **404 if missing / 503 if I/O failure** |
| `ConfigurableLoanDocumentStorageService` lines 37, 54, 118 (R2 misconfigured) | Deployer / config error | 404 | **500** (server bug, not "doc missing") |
| `LoanDocumentService.buildDocumentZip` line 82 ("failed to build ZIP archive") | In-memory / CPU failure during zip stream | 404 | **500** |
| R2 SDK exceptions (`S3Exception`, `SdkClientException`) bubbling from `R2LoanDocumentStorageService.retrieve` line 51 | Network outage / auth failure / NoSuchKey | **Not caught** by `IllegalStateException` → falls through to `GlobalExceptionHandler` → 500 | **404 for `NoSuchKeyException`, 503 for outage** |

Net effect today:
- FS-backed "file missing on disk" returns 404; R2-backed "key not in bucket" returns 500. **Inconsistent backend semantics.**
- R2 misconfig (deployer error) returns 404 — silent prod failure that looks like "doc deleted" to users.
- FS I/O failure / ZIP-build CPU failure return 404 — outage looks like data loss.
- R2 outage returns 500, but with no specific signal (just a stack-trace 500) — ops can't distinguish from any other 500.

**Implementation plan (one PR):**

1. **New exception types** (in `com.bhawana.lms.common.exception` or sibling of existing `ResourceNotFoundException`):
   - `DocumentNotFoundException extends ResourceNotFoundException` — caller passes `(applicationId, documentType, cause)`. Maps to **404**.
   - `DocumentStorageUnavailableException extends RuntimeException` — caller passes `(storageKey, providerName, cause)`. Maps to **503**.
   - `DocumentStorageMisconfiguredException extends RuntimeException` — caller passes `(providerName, missingField, cause)`. Maps to **500**.

2. **Storage backends throw the right type** (no more bare `IllegalStateException` for these conditions):
   - `R2LoanDocumentStorageService.retrieve` (line 51): catch `NoSuchKeyException` → throw `DocumentNotFoundException`. Catch any other `S3Exception` / `SdkClientException` → throw `DocumentStorageUnavailableException`. Let `RuntimeException` propagate only for genuinely unexpected programmer errors.
   - `FileSystemLoanDocumentStorageService.retrieve` (line 36): check `Files.exists(targetPath)` first → throw `DocumentNotFoundException`. Then attempt `Files.readAllBytes`; catch `IOException` → throw `DocumentStorageUnavailableException` (distinguishes "not there" from "disk gone sideways").
   - `ConfigurableLoanDocumentStorageService.retrieveFromR2OrFail` (line 35) + `listAllFromR2OrFail` (line 52) + `storeToR2OrFail` (line 116): the `R2 not configured` check throws `DocumentStorageMisconfiguredException` instead of `IllegalStateException`.

3. **Domain-layer wrappers in `LoanDocumentService`:**
   - `retrieveDocumentContent` line 44: throw `DocumentNotFoundException` (the "not LMS-managed" case is a legitimate not-found for the operator-facing API).
   - `buildDocumentZip` line 67: throw `DocumentNotFoundException` ("no documents found" is a legitimate not-found at this resource granularity).
   - `buildDocumentZip` line 82: keep this as `IllegalStateException` OR introduce a `DocumentZipBuildFailedException` — recommended: keep `IllegalStateException` so it maps to 500 (generic internal failure). It is not a storage issue.

4. **Centralise mapping in `GlobalExceptionHandler`:**
   - `@ExceptionHandler(DocumentNotFoundException.class)` → 404 with structured body `{ code: "DOCUMENT_NOT_FOUND", message, details }`.
   - `@ExceptionHandler(DocumentStorageUnavailableException.class)` → 503 with structured body `{ code: "DOCUMENT_STORAGE_UNAVAILABLE", retryable: true, message: "Document storage is temporarily unavailable. Please retry." }`. Increment Micrometer counter `lms_document_storage_unavailable_total{provider=…}`.
   - `@ExceptionHandler(DocumentStorageMisconfiguredException.class)` → 500 with structured body `{ code: "DOCUMENT_STORAGE_MISCONFIGURED", message }`. Logged at ERROR with the missing field name to make ops triage one-shot.

5. **Remove the controller `try/catch` blocks** at `LoanApplicationOpsController.downloadAllDocuments` (lines 203–208) and `downloadDocumentContent` (lines 230–235). The 404 catches were doing the bug. After this PR the controller methods just call the service and return the body — exception mapping is GlobalExceptionHandler's job. Same cleanup applies to the LSP-side download controller if it exists (verify during implementation).

6. **Alert rule** (depends on `AlertRuleEvaluationService` + `AlertRule` plumbing already in the codebase):
   - New rule type `DOCUMENT_STORAGE_OUTAGE_SPIKE` — fires `OpsAlert` when `lms_document_storage_unavailable_total` rate exceeds N events per M minutes (e.g., 5 events / 5 minutes, configurable via `lms.alerts.document-storage-outage-spike.{count,windowMinutes}`).
   - Alert payload includes provider (`R2` vs `LOCAL`), recent count, time window. Visible in Audit Explorer.
   - Re-uses the alert plumbing landed in #62 PR (a) / Follow-up #2 — no new infrastructure.

**Behaviours we deliberately do NOT change:**
- The `buildDocumentZip` "no documents found" case stays 404 (it is the resource-level not-found for the ZIP endpoint, not a storage failure).
- Upload-path `IllegalStateException` (e.g., `ConfigurableLoanDocumentStorageService.store` line 94 for unreadable multipart) is out of scope — different code path, different fix if needed.
- Audit row writing on download (owned by **#70**, already SOLVED) is unchanged. The 503 path should still write an audit row with `outcome = STORAGE_UNAVAILABLE` if the storage failure happens after the access check — verify during implementation that the audit hook in #70's solution covers the failure case, otherwise file a follow-up.

**TDD plan (vertical slices, behaviour through MockMvc; tests land in `LoanApplicationOpsControllerTest` + a new `GlobalExceptionHandlerDocumentTest`):**

1. **TRACER — `download_for_missing_storage_key_returns_404_with_DOCUMENT_NOT_FOUND_code`.** Drive `GET /…/kyc-documents/{type}/content` for a checklist item with `storageKey = null`; assert HTTP 404, body code `DOCUMENT_NOT_FOUND`. Locks in the legitimate not-found path under the new taxonomy.
2. `download_for_R2_NoSuchKey_returns_404_not_500`. Mock R2 boundary to throw `NoSuchKeyException`; assert 404. Fixes the FS-vs-R2 inconsistency the audit didn't surface.
3. `download_during_R2_outage_returns_503_with_DOCUMENT_STORAGE_UNAVAILABLE_code`. Mock R2 boundary to throw `SdkClientException("network down")`; assert HTTP 503, body code `DOCUMENT_STORAGE_UNAVAILABLE`, `retryable: true`. Asserts the storage counter incremented by 1.
4. `download_when_R2_misconfigured_returns_500_with_DOCUMENT_STORAGE_MISCONFIGURED_code`. Configure R2 provider with blank access-key; assert 500, body code `DOCUMENT_STORAGE_MISCONFIGURED`. Locks in: deployer-facing error, not user-facing.
5. `download_for_FS_missing_file_returns_404_with_DOCUMENT_NOT_FOUND_code`. Point checklist at a path that doesn't exist; assert 404. Distinguishes FS-not-found from FS-I/O-failure.
6. `download_for_FS_io_failure_returns_503`. Mock the FS retrieve boundary to throw `IOException("disk gone")`; assert 503, counter increments. Locks in: I/O failure ≠ deleted.
7. `zip_download_with_no_documents_returns_404`. Existing behaviour preserved under the new taxonomy.
8. `zip_download_with_mid_build_io_failure_returns_500_not_404`. Force a ZIP-build failure (mock `ZipOutputStream` write to throw); assert 500 (generic internal — it is not a storage outage). Confirms the line-82 path was correctly re-classified as "internal failure" not "storage 503."
9. `storage_unavailable_counter_increments_on_503_path_only`. Drive one 404 and two 503s; assert counter = 2 (not 3).
10. `alert_fires_when_storage_unavailable_rate_crosses_threshold`. Configure threshold = 2/5min; drive 3 503s within window; assert an `OpsAlert` row with type `DOCUMENT_STORAGE_OUTAGE_SPIKE` and the provider name in the payload.
11. `controller_no_longer_catches_IllegalStateException`. Reflection / structure test (or a behavioural test): drive a path that previously hit the controller `try/catch` and assert the new flow goes through `GlobalExceptionHandler`. Locks in: nobody reintroduces the controller-side catch.

**Mocking discipline (per the TDD doc):**
- Mock `R2LoanDocumentStorageService` and `FileSystemLoanDocumentStorageService` at the boundary — they are system boundaries (network and OS filesystem). Same `static` method shape kept; tests inject a stub at the `ConfigurableLoanDocumentStorageService` level so the storage provider can be substituted without reflection. If the static structure makes this awkward, refactor those two to non-static instance services injected by Spring (the only behavioural change is making them mockable for tests).
- **No mocking of** `LoanDocumentService`, `LoanApplicationService`, `GlobalExceptionHandler`, the new exception classes, `AlertRuleEvaluationService`, or the Micrometer registry. Use `MeterRegistry` from Spring test config to assert counter increments — that's the public observability interface.
- **No tests asserting which line threw the exception** — that's testing implementation. Tests assert: status code, response body code, counter increment, alert row existence. All behaviour, all through the public HTTP / metrics / alert interfaces.

**Effect on app:**
- Correct HTTP status codes per failure cause (404 / 503 / 500).
- Storage outages stop being silent: 503 + counter + alert rule → on-call gets paged when R2 wobbles.
- Misconfig fails loud (500 + clear code) instead of looking like missing documents — deploy errors get caught fast.
- LSP / admin UI: small copy changes for 503 ("Storage temporarily unavailable, please retry") and 500 ("Internal error") vs 404 ("Document not found").
- Pre-launch: zero partner-contract risk. Any future LSP integration spec documents the three statuses upfront.
- Bonus: closes a minor information-disclosure timing channel where storage health was previously inferable from response patterns.

**Dependencies / sequencing:**
- Standalone PR. No blocking dependencies.
- Builds on #70's audit infrastructure (already SOLVED) — verify during implementation that the audit row write captures the `STORAGE_UNAVAILABLE` outcome on the failure path. If it doesn't, file a small follow-up on #70 to extend its outcome enum (do not block #92 on this).
- Builds on the alert-rule plumbing already in the codebase (`AlertRuleEvaluationService`, `OpsAlert`, `AlertRule`). No new infra.
- Should ship before any real LSP integration goes live; once partners are live, changing 404 → 503 is a behaviour change requiring comms.

---

### #95 — [B-13] resolveMockDisbursementOutcome surfaces confusing error on double-click
**Link:** https://github.com/sid12701/lms/issues/95

**Detailed solution after discussion (2026-06-01) — IMPLEMENT (GREEN-LIT): part of bundled "mock disbursement hygiene" PR with #84 + #96.**

**Problem in detail:** `LoanApplicationService.resolveMockDisbursementOutcome` (line 724 as of 2026-06-02; line 757 in the original audit) guards with:
```java
if (loanAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_REQUESTED) {
    throw new IllegalArgumentException("Mock disbursement outcome can only be applied after a request is raised.");
}
```
That message is misleading on the most common cause — admin double-clicked "Mark DISBURSED" because the UI hadn't reflected the first response yet, the first call succeeded (account now `DISBURSED`), and the second hits this branch with a message that suggests "you didn't raise a request" instead of "this outcome was already resolved."

**Decision — Option C (idempotent same-outcome + 409 on conflicting outcome):**
- **Same outcome retry → 200 idempotent.** Second `DISBURSED` on an already-`DISBURSED` loan returns the current detail response. No duplicate audit row. No duplicate webhook. Effectively a no-op success.
- **Different outcome retry → 409.** `FAILED` on an already-`DISBURSED` loan returns 409 with `code = "MOCK_DISBURSEMENT_OUTCOME_CONFLICT"`. You can't undo a disbursement; trying to do so should be a loud error.
- **Request never raised → 400 with clear code.** When the account is still in `PENDING_DISBURSEMENT` (no request raised yet), return 400 with `code = "MOCK_DISBURSEMENT_NO_REQUEST_RAISED"`. Distinguishes the "wrong state for this transition" case from the "already resolved" case.

**Implementation outline (in `LoanApplicationService.resolveMockDisbursementOutcome`):**
1. Replace the line-735 guard (was line 768 in the original audit; shifted as the file shrank) with a three-way switch on `loanAccount.getStatus()`:
   - `PENDING_DISBURSEMENT` → throw a new `BusinessRuleViolationException` with code `MOCK_DISBURSEMENT_NO_REQUEST_RAISED` (HTTP 400 via existing mapping).
   - `DISBURSEMENT_REQUESTED` → proceed with the existing happy path.
   - Any terminal/post-request state (`DISBURSED`, `DISBURSEMENT_FAILED`, `DISBURSEMENT_PENDING_RECONCILIATION`):
     - If the requested outcome maps to the current state → return the existing application and log a debug breadcrumb. No mutation, no audit, no webhook.
     - If the requested outcome maps to a different state → throw `BusinessRuleViolationException` with code `MOCK_DISBURSEMENT_OUTCOME_CONFLICT` (HTTP 409 via existing mapping; if mapping is missing, add it in `GlobalExceptionHandler`).
2. Use the existing `LoanAccountStatus → MockDisbursementOutcome` round-trip already implied by lines 778–782 to check whether the current state corresponds to the requested outcome.
3. The controller layer (`LoanApplicationOpsController.applyMockDisbursementOutcome` line 377 as of 2026-06-02; line 385 in the original audit) needs no change — it just propagates whatever the service returns or throws.

**TDD (subset of the bundled PR's tests):**
- TRACER — `double_resolve_same_outcome_returns_200_idempotent`. Apply `DISBURSED`; apply `DISBURSED` again. Second call returns 200 with same response. Exactly one `LoanApplicationStatusTransition` row, one `DISBURSEMENT_COMPLETED` webhook event in the outbox.
- `double_resolve_different_outcome_returns_409_with_conflict_code`. Apply `DISBURSED`; apply `FAILED`. Second returns 409, code `MOCK_DISBURSEMENT_OUTCOME_CONFLICT`. No additional transitions.
- `resolve_on_account_never_requested_returns_400_with_clear_code`. Account in `PENDING_DISBURSEMENT`; POST mock-outcome → 400, code `MOCK_DISBURSEMENT_NO_REQUEST_RAISED`. Locks in the distinction from "already resolved."
- `resolve_FAILED_then_FAILED_returns_200_idempotent`. Multiple `FAILED` retries are also idempotent (covers the symmetric case).
- `resolve_PENDING_RECONCILIATION_then_DISBURSED_returns_409`. Same conflict semantics for partial-resolution states.

**Mocking discipline:** controller-layer `MockMvc` tests; no internal mocking; no mocking of `WebhookOutboxService` or the lifecycle service. Outbox row count and audit row count are asserted via repository reads (those are public boundaries here).

**Effect on app:**
- Double-clicks stop surfacing as confusing 400s; the common case is silently idempotent.
- Real misuse (conflicting outcomes) gets a loud, attributable 409 with a code ops can grep for.
- "Never requested" stays distinguishable from "already resolved" — different runbooks for the two.
- Pre-launch: no partner contract risk. `LocalDemoPortfolioSeedService` unaffected (its calls hit fresh accounts; same-outcome retries return 200, which is what the seed expects).

**Cross-issue:** ships in the bundled "mock disbursement hygiene" PR with #84 (full UUID + unique constraint) and #96 (Idempotency-Key requirement). #96's idempotency layer **complements** this state-based dedup — #96 catches "exactly the same in-flight request twice"; #95 catches "two separate user gestures that happen to converge on the same state." Both are needed; neither is sufficient alone.

---

### #96 — [B-14] applyMockDisbursementOutcome has no Idempotency-Key support
**Link:** https://github.com/sid12701/lms/issues/96

**Detailed solution after discussion (2026-06-01) — IMPLEMENT (GREEN-LIT): part of bundled "mock disbursement hygiene" PR with #84 + #95.**

**Problem in detail:** The `/mock-outcome` endpoint (`LoanApplicationOpsController.applyMockDisbursementOutcome` line 377 as of 2026-06-02; line 385 in the original audit) currently accepts a `MockDisbursementOutcomeRequest` body with no required `Idempotency-Key` header. Other money-moving endpoints (`/payments`) require one and dedup via the existing idempotency infrastructure (per #86's solution). Mock-outcome is the only resolve-money-state endpoint that doesn't.

Why this matters:
- Network blips during the response trip cause clients to retry; without a key, retries become new transitions (caught by #95's state guard for repeat outcomes, but only after the second call hits the DB).
- A future caller (LSP integration, ops script, automation) expecting the standard idempotency contract will be surprised.
- Inconsistent API surfaces lead to support tickets and partner integration friction.

**Decision — Option A (required Idempotency-Key, reuse existing infrastructure):**
- `Idempotency-Key` header becomes **required** on `POST /…/disbursement-requests/mock-outcome`. Missing → 400 with code `IDEMPOTENCY_KEY_REQUIRED`, matching `/payments`.
- Replays with the same key return the cached response — same body, same status. No DB mutation, no new audit row, no new webhook.
- Different payload under the same key → 409 with `IDEMPOTENCY_KEY_PAYLOAD_MISMATCH`, matching `/payments`' contract.
- TTL on cached responses matches the existing repayment configuration (no new config knob).

**Implementation outline:**
1. Wire the existing idempotency service into the mock-outcome handler — same pattern as `LoanRepaymentCommandController` (or wherever the canonical pattern lives). The service exposes a `runOrReplay(key, requestFingerprint, supplier)` style API; the handler wraps the `resolveMockDisbursementOutcome` call in it.
2. The request fingerprint is `(applicationId, outcome)` — exclude actor / timestamp so genuine retries from the same client dedup correctly.
3. Update `LocalDemoPortfolioSeedService` (lines 236, 259, 269, 291 as of 2026-06-02; lines 237, 260, 270, 292 in the original audit) to pass deterministic idempotency keys derived from the seed application IDs. Each call already runs at most once during seeding, so any deterministic key works.
4. Update `LoanApplicationOpsControllerTest` mock-outcome tests to include the header.

**Layering with #95:**
- #95 handles **state-based dedup**: two separate calls that both want the loan to end in `DISBURSED`. Returns 200 idempotently on the second.
- #96 handles **request-based dedup**: the same in-flight POST submitted twice by a flaky network. Returns the *cached response* from the first call without re-running the service.
- Together they cover both the "user gesture" race and the "network retry" race. Either alone is insufficient — #95 doesn't return a cached response (it re-derives the detail response); #96 doesn't help when the second call is a genuinely new gesture without the key.

**TDD (subset of the bundled PR's tests):**
- TRACER — `mock_outcome_requires_idempotency_key`. POST without header → 400, code `IDEMPOTENCY_KEY_REQUIRED`.
- `mock_outcome_with_idempotency_key_dedups_replays`. POST same key twice (same payload) → second call returns cached response; exactly one transition row; exactly one webhook event in outbox.
- `mock_outcome_with_same_key_different_payload_returns_409`. Key collision with different `outcome` → 409 with `IDEMPOTENCY_KEY_PAYLOAD_MISMATCH`. Mirrors repayment behaviour.
- `local_demo_portfolio_seed_uses_idempotency_keys`. Run the seed twice; assert the second seed pass is a no-op for already-resolved applications (cached responses returned). Locks in: the seed service stays repeatable.
- `mock_outcome_dedup_survives_a_status_already_resolved_attempt`. With the key cached after a successful `DISBURSED` call, a replay with the same key still returns 200 even though #95's state guard would also have returned 200 — they don't interfere.

**Mocking discipline:** no mocking of the idempotency service, the lifecycle service, repositories, or `WebhookOutboxService`. The cache state is observable via the service's public read (or via the response body identity check across calls).

**Effect on app:**
- API contract for `/mock-outcome` is now consistent with `/payments`. Future API consumers see one idempotency pattern, not two.
- Network-retry safety end-to-end: ops UI, ops scripts, automation, and the local-demo seed all benefit.
- Pre-launch: no partner contract risk. Ops UI needs a one-line update to pass an `Idempotency-Key` (typically a UUID per gesture); the existing repayment UI already does this and the same hook can be reused.
- Test surface gains the same idempotency assertions that already exist for repayments — copy-paste-able patterns for the team.

**Cross-issue:** ships in the bundled "mock disbursement hygiene" PR with #84 and #95. Depends on no other open issue. Builds on #86's solved idempotency infrastructure.

---

### #97 — [B-15] Refresh-token rotation race logs out concurrent browser tabs
**Labels:** bug · **Link:** https://github.com/sid12701/lms/issues/97

**Problem (plain English):** Two tabs refresh near-simultaneously; second one gets 401 and logs out that tab. UX paper-cut.

**Possible fixes:**
1. **Client-side single-flight refresh (BroadcastChannel)** — no server change.
2. **Server-side grace window (accept prior token within 5s)** — small replay surface.

**Recommended:** Option 1. Cheaper, no security tradeoff.

**Effect on app:** No more random tab logouts.

**Detailed solution after discussion (2026-06-01) — IMPLEMENT (GREEN-LIT): A + D, no server change:**

**Decision:** Two-part frontend-only fix.
- **A (within-tab single-flight)** wraps the refresh callback so concurrent 401s share one in-flight `/auth/refresh` call.
- **D (proactive refresh)** schedules a refresh **30 seconds** before access-token expiry so the burst-401 surface is mostly removed in the first place.
- **No server-side grace window** (audit doc Option 2 rejected — adds replay surface for a problem A already solves cheaper).
- **No cross-tab `BroadcastChannel`** (rare case; revisit if support tickets surface it).
- **Proactive-refresh failure path stays quiet** — if the background refresh fails (5xx / network), do not clear the session. The next real user request will 401, fall into the A single-flight, and either succeed (transient failure resolved) or log out cleanly with context. This avoids surprising the user with a logout while they were typing.

**Audit findings that sharpened the framing:**

1. **The audit doc's "second tab gets 401 and logs out" was only half the picture.** The bigger frequency surface is **within one tab** — a dashboard that fires N parallel GETs at access-token expiry. All N independently call `onUnauthorizedRefresh()`; the first one rotates the cookie; the rest find their cookie revoked and 401, each independently triggering `setSession(null)` (`session-context.tsx:66`). User is bounced to the login screen despite a fully valid session.
2. **The server rotation is strict and immediate.** `AuthController.refresh` (lines 104–140): hash cookie → look up via `findByTokenHashAndRevokedFalse` → `existing.revoke(); save();` (lines 114, 119–120) → mint new pair (lines 122–136). No grace, no chaining, no `parent_token_id`. The cookie value lives for one refresh. This is correct rotation; the bug is on the client.
3. **There is no client-side single-flight today.** `http-client.ts:148–153` calls `onUnauthorizedRefresh()` directly per 401. `session-context.tsx:60–68` registers a callback that does `serviceRefresh() → setSession() → return accessToken | null`. Each concurrent 401 hits this path independently.
4. **The existing GET de-dup (`inFlightJsonRequests`) does not save you.** It dedups per `(authMode, accessToken, path)` (lines 117–127). Different paths race. POSTs / non-GETs bypass dedup. After `_retried = true` it short-circuits. So the same dashboard with 5 different `useQuery` calls or a mutation triggered next to a query → still races. The dedup is for a different concern; leave it alone.
5. **No proactive refresh today.** `session-context.tsx` reads `expiresAt` but never schedules a pre-expiry refresh. The first 401 the user encounters is always a real 401, by design.
6. **Why server-side grace was rejected:** introducing a "revoked within last N seconds is still valid" window means a stolen cookie can be replayed inside that window even after a legitimate rotation. The replay surface is bounded but real, and pre-launch a CISO would flag it. A solves the user-facing problem without weakening any rotation property.
7. **Why BroadcastChannel was rejected:** cross-tab refresh-races are real but rare. D (proactive refresh) shrinks the window enough that cross-tab collisions become negligible. The BroadcastChannel implementation also needs a `storage`-event fallback for older Safari and introduces a "which tab is leader" coordination problem. Don't ship this speculatively.

**Implementation plan (one PR):**

1. **`frontend-2/src/lib/api/http-client.ts` — within-tab single-flight:**
   ```ts
   let refreshPromise: Promise<string | null> | null = null;

   function singleFlightRefresh(): Promise<string | null> {
     if (refreshPromise) return refreshPromise;
     if (!onUnauthorizedRefresh) return Promise.resolve(null);
     const p = onUnauthorizedRefresh();
     refreshPromise = p.finally(() => {
       // Defer clear so concurrent 401s that arrive in the same microtask
       // still see the in-flight promise.
       queueMicrotask(() => { if (refreshPromise === p) refreshPromise = null; });
     });
     return refreshPromise;
   }
   ```
   Replace the call site at line 149 (`await onUnauthorizedRefresh()`) with `await singleFlightRefresh()`. The existing 401 retry branch (lines 148–153) keeps its shape.
2. **`frontend-2/src/features/auth/session-context.tsx` — proactive refresh:**
   - Add a constant near the top: `const PROACTIVE_REFRESH_LEAD_SECONDS = 30;`
   - In the effect that runs when `session` changes, schedule `setTimeout(refresh, Math.max(0, msUntilExpiry − 30_000))`. Clear the timer on session-change / unmount.
   - `refresh` (existing useCallback line 72–75) is reused; no change to its signature.
   - **Failure-path behaviour:** wrap the proactive refresh in `try/catch`; on failure, log a `console.warn` and do **nothing else**. Do not call `setSession(null)`. The next real user request will hit 401 and recover via A (or fail cleanly through to a real logout if the session is genuinely dead).
   - Background-tab caveat (left as documented behaviour): `setTimeout` is throttled in background tabs, so a backgrounded tab may have its timer fire late (after the token already expired). That is **fine** — the next user-triggered request 401s, A single-flights the recovery, and the user sees no logout. A naturally catches what D's timer misses.
3. **Test-only seam:** export a small `__resetHttpClientInternalsForTesting()` from `http-client.ts` to reset the `refreshPromise` between tests. No production caller.

**Behaviours we deliberately do NOT change:**
- `inFlightJsonRequests` GET-dedup map (lines 18, 105–112). It's orthogonal — dedups same-URL GETs across the app; new single-flight dedups refresh calls. Different layer; leaving it as-is.
- Backend refresh rotation in `AuthController.refresh`. No change. Locks in the strict-rotation security property.
- The `setRefreshCallback(null)` cleanup on `SessionProvider` unmount (line 69). Keep as-is.
- The legitimate-logout path on a hard-401 (cookie expired / account disabled / etc.) — those still log out, as they should.

**Cross-issue impact:**
- **#132** (refresh revoke-then-issue can log user out if issue fails) — partially overlaps. A's single-flight reduces the surface but doesn't fix the server-side ordering. Note the overlap when #132 is grilled; A may be sufficient there too. No bundle.
- **#80** (no admin "log out everywhere" / global JWT revocation) — independent; the global-revoke path is a separate concern. No interaction.
- **#155** (failed-auth events not fed into lockout/alert pipeline) — proactive-refresh failures should NOT count as failed-auth events (they're not user-initiated). Note for #155's grill: distinguish background refresh failures from human-driven 401s in any alert logic.

**TDD plan (vertical slices, behaviour through the public `http-client` and `session-context` APIs):**

Tests sit in `frontend-2/src/lib/api/http-client.test.ts` and `frontend-2/src/features/auth/session-context.test.tsx`. Mocking boundary is `fetch` (network) and the `onUnauthorizedRefresh` callback (the http-client's published seam). No internal mocking.

1. **TRACER — `concurrent_401s_share_a_single_refresh_call`.** Stage `fetch` to return 401 for two parallel `requestJson` calls; mock the refresh callback to resolve after a tick with a new token; assert the refresh callback was invoked exactly once and both requests' retries succeed with the new token.
2. `three_concurrent_401s_on_different_paths_share_one_refresh`. Same with three different paths to prove dedup is on the refresh promise, not on the request-key.
3. `refresh_returning_null_propagates_logout_to_all_concurrent_waiters`. Mock callback returns null; all concurrent requesters get `ApiError(status=401)`; the session-context's `setSession(null)` observable fires once.
4. `new_401_burst_after_successful_refresh_triggers_a_new_refresh`. After first refresh resolves and the promise slot clears, a later 401 burst starts a fresh refresh. Refresh callback called twice across the whole test.
5. `refresh_callback_that_throws_clears_in_flight_so_next_attempt_retries`. Callback throws; first burst fails; a subsequent 401 fires a new refresh attempt (in-flight cleared on error path). Locks in: error path doesn't permanently wedge the single-flight slot.
6. `single_flight_resets_after_resolution_so_long_lived_sessions_can_refresh_multiple_times`. Drive three sequential 401 events spaced over time; assert three refresh calls. Locks in: the single-flight is per-burst, not "once per page load."
7. **D-test — `token_expiring_soon_triggers_proactive_refresh_30s_before_expiry`.** Vitest `vi.useFakeTimers()`. Mount `SessionProvider` with a session expiring in 60 s; advance to expiry − 30 s; assert refresh was called and the session-context's `expiresAt` advanced. No 401 was ever fired by `fetch`.
8. **D-test — `unmounting_session_provider_clears_pending_proactive_refresh`.** Mount, then unmount before the timer fires; advance time past expiry − 30 s; assert no refresh call. Prevents leaked timers across hot-module reloads and route changes.
9. **D-test — `proactive_refresh_failure_does_NOT_clear_session`.** Mock `serviceRefresh` to throw; advance timers; assert session remains intact (no `setSession(null)` observed). Subsequent real request that 401s does fall into the A single-flight and is the path that may eventually log out — but the silent timer failure does not.

**Mocking discipline (per the TDD doc):**
- **Mock `fetch`** — network boundary. Fine.
- **Mock the `onUnauthorizedRefresh` callback** via `setRefreshCallback(...)` — that's the http-client's published seam.
- For session-context tests, mock `serviceRefresh` (the auth-service function) at the module boundary — published API.
- **Do NOT mock** `inFlightJsonRequests`, `singleFlightRefresh`, `refreshPromise`, React `useEffect`/`useRef`/`setSession`, or any internal state. Use `act()` + `vi.useFakeTimers()` for D tests.
- **Do NOT assert** call order beyond "called once" / "called twice" — counts are behaviour, ordering is implementation.

**Tests we deliberately do NOT write here:**
- Backend rotation correctness — owned by `AuthControllerRefreshTest` (existing).
- BroadcastChannel / cross-tab `storage`-event coordination — explicitly out of scope.
- Server-side grace window — explicitly out of scope.
- Token-expiry parsing / clock-skew correction — already covered by `auth-service.ts` `expiresAtFromToken` and its existing tests.

**Effect on app:**
- Concurrent dashboard fetches stop producing spurious logouts. The user notices only by *not* getting kicked out at random moments.
- Proactive refresh keeps the access token fresh under typical usage; most users never see a 401 in normal flows.
- Zero server change. Zero security tradeoff. Zero new dependencies. Pre-launch contract-safe.
- A rare cross-tab race in a degraded-network window can still log one tab out. Acceptable for now; revisit only if support tickets show it.
- Two new tiny `console.warn` lines on background-refresh failure — useful breadcrumb during incident triage.

**Dependencies / sequencing:**
- Standalone PR. No blocking dependencies.
- Should land before any pre-launch UX polish pass — random logouts are exactly the kind of bug stakeholders catch in walkthroughs.
- Cross-link with **#132** in that issue's eventual writeup — A's single-flight reduces #132's race surface.

---

### #99 — [Q-2] LoanApplicationService is a useless delegate facade
**Labels:** code-quality · **Link:** https://github.com/sid12701/lms/issues/99

**Problem (plain English):** Forwards almost every call to focused services. Adds cognitive load without removing coupling.

**Possible fixes:**
1. **Delete the facade; callers depend on focused services directly** — simpler.
2. **Delete the focused services; keep the facade** — opposite direction.

**Recommended:** Option 1; matches the decomposition direction (#98).

**Effect on app:** One fewer indirection layer. Caller files change imports.

**Detailed solution after discussion (2026-06-02 audit) — framing overstated; re-scope before grilling:**

The audit doc claims `LoanApplicationService` "forwards almost every call to focused services." The current code does not match that claim:

- File is 1,047 lines and exposes **51 public methods**.
- Only **15 methods** are pure one-line delegates to one of the four focused collaborators (`LoanApplicationLifecycleService`, `LoanApplicationQueryService`, `LoanRepaymentCommandService`, `LoanForeclosureCommandService`). That's ~30%.
- The other ~70% do real work: direct repository access (e.g., `getApplication`, `listAuditEvents`, `listDisbursementRequests`, `listForeclosureQuotes`), multi-source projection logic (e.g., `getLatestActivity`'s 3-way candidate stream merging intake / status-transition / document-update activity), aggregation helpers (`getLoanRepaymentScheduleSummary`, `getLoanDelinquencySummary`), and several static utility methods.

It is a chunky service, not a useless facade. Bundling with #98 (god-class decomposition) still makes sense, but the right scope is **"extract the 15 pure-delegate methods and have callers depend on the focused services directly"** — not "delete the facade." The 70% of methods that do real work need to either move into the focused services (probably the right call, sized per method) or stay where they are, but they cannot just be deleted.

**Action:** re-scope before the grill; the grill prompt should ask "which of these 51 methods belongs in `LoanApplicationService`, which belongs in a focused service, and which belongs in a new home (e.g., projection / read-model class)?" not "should we delete the facade?"

---

### #100 — [Q-3] Parallel response builders (ops vs LSP) re-walk the same graph
**Link:** https://github.com/sid12701/lms/issues/100

**Detailed solution after discussion:** _(pending — bundle with #115)_

---

### #101 — [Q-4] Legacy frontend/ still bundled
**Labels:** code-quality, security, duplicate-code · **Link:** https://github.com/sid12701/lms/issues/101 · **Status:** **CLOSED — RETIRED** (2026-06). Bundled with **#114**.

**Problem (plain English):** Two FE codebases bundled; legacy has its own auth flow and storage keys.

**Detailed solution after discussion (2026-06-06) — RETIRED:**

Legacy frontend retirement is complete per **ADR-0001** (`docs/adr/0001-adopt-frontend-2-direct-backend-integration.md`):

- Former `frontend-2/` is now the canonical `frontend/` (`bhawana-lms-frontend`).
- Legacy `frontend/` (mock-auth SPA) removed from the repo.
- In-app mock layer (`src/mocks/`, `VITE_USE_MOCKS`) removed; app calls the live backend.
- `frontend/src/lib/session-storage.ts` is real JWT session storage, not legacy mock auth.

**Verification:** no `frontend/src/mocks/` directory; no `SEED_USERS` in frontend source; single SPA under `frontend/`.

**Closes:** #114 (same decision).

---

### #103 — [Q-6] webhook_event_outbox.payload_json still text — partial jsonb migration
**Labels:** code-quality, database · **Link:** https://github.com/sid12701/lms/issues/103 · **Status:** **ALREADY FIXED — CLOSE** (audited 2026-06-02)

**Problem (plain English):** Last text payload column blocking query-by-JSON.

**Possible fixes:**
1. **Flyway migration text→jsonb with backfill; GIN index where useful** — clean.

**Recommended:** Option 1.

**Effect on app:** Ad-hoc ops queries on payload contents become feasible.

**Detailed solution after discussion (2026-06-02 audit) — ALREADY FIXED:**

`backend/src/main/resources/db/migration/V72__json_text_columns_to_jsonb.sql` performed the migration. Specifically (lines 7–10 of V72):

```sql
ALTER TABLE webhook_event_outbox
    ALTER COLUMN payload_json TYPE jsonb USING payload_json::jsonb,
    ADD CONSTRAINT chk_webhook_event_outbox_payload_json_object
        CHECK (jsonb_typeof(payload_json) = 'object');
```

Same V72 migration also flipped the sibling text-JSON columns the audit doc had grouped under this issue: `loan_disbursement_request_log.{request,response}_payload_json`, `loan_application.rejection_reason_json`, plus several other `*_json` columns across the schema. Every column carries a `jsonb_typeof = 'object'` check so malformed payloads now fail at write time.

The doc's framing "Last text payload column blocking query-by-JSON" is stale — written before V72 landed.

**Action:** close as FIXED. **Optional follow-up (out of scope for closure):** if production telemetry shows ad-hoc ops queries scanning `payload_json` filtered by `eventType` or specific JSON paths, add a GIN index — but only with evidence. Don't pre-emptively index a non-existent query pattern.

---

### #104 — [Q-7] Convenience overloads double method-combination test surface
**Labels:** code-quality · **Link:** https://github.com/sid12701/lms/issues/104

**Problem (plain English):** Overload pairs that just delegate to each other multiply call-site combinations to test.

**Possible fixes:**
1. **One method + value-object parameter** — reduces surface.

**Recommended:** Option 1.

**Effect on app:** Smaller test matrix; cleaner call sites.

**Detailed solution after discussion:** _(pending)_

---

### #105 — [Q-8] LoanApplicationOpsController has ~500 LoC of nested record DTOs
**Labels:** code-quality · **Link:** https://github.com/sid12701/lms/issues/105

**Problem (plain English):** DTOs live inside the controller class; inconsistent with the rest of the codebase.

**Possible fixes:**
1. **Extract DTOs to their own files in `web/loanapplication/`** — matches conventions.

**Recommended:** Option 1.

**Effect on app:** Smaller controller; faster IDE navigation.

**Detailed solution after discussion:** _(pending)_

---

### #106 — [Q-9] PG-only schema tests silently skipped on H2 — no CI guard
**Labels:** code-quality, verification · **Link:** https://github.com/sid12701/lms/issues/106

**Problem (plain English):** PG-only tests are skipped on H2 and there's no CI guard that fails if PG isn't available.

**Possible fixes:**
1. **Testcontainers + tag-required CI job** — guaranteed PG.

**Recommended:** Option 1.

**Effect on app:** Schema-shape tests actually run; local dev needs Docker.

**Detailed solution after discussion:** _(pending)_

---

### #108 — [Q-11] Inconsistent error-code vocabulary — no central catalog
**Labels:** code-quality · **Link:** https://github.com/sid12701/lms/issues/108

**Problem (plain English):** Error codes are ad hoc strings. Partners can't rely on stable codes.

**Possible fixes:**
1. **`ApiErrorCode` enum + doc** — stable contract.

**Recommended:** Option 1.

**Effect on app:** Stable codes in responses; published doc.

**Detailed solution after discussion:** _(pending)_

---

### #109 — [Q-12] FE STATUS_PASS_THROUGH mapping ripe for drift
**Labels:** code-quality, fragile-logic · **Link:** https://github.com/sid12701/lms/issues/109

**Problem (plain English):** Manual mapping between BE enum and FE display. Drifts silently.

**Possible fixes:**
1. **Generate FE enum from OpenAPI; remove manual mapping** — proper.
2. **Test that asserts every BE value has FE mapping** — safety net.

**Recommended:** Option 1 + Option 2.

**Effect on app:** No more silent drift bugs.

**Detailed solution after discussion:** _(pending)_

---

### #110 — [Q-13] Webhook outbox index bloat
**Labels:** code-quality, database, scale-risk · **Link:** https://github.com/sid12701/lms/issues/110 · **Status:** **FRAMING OVERSTATED — re-scope or close** (audited 2026-06-02)

**Problem (plain English):** Indexes accumulated across V57/V58/V61/V66. Write amplification on a hot table.

**Possible fixes:**
1. **`pg_stat_user_indexes` review + drop redundant in Flyway** — proper.

**Recommended:** Option 1.

**Effect on app:** Faster writes; smaller table.

**Detailed solution after discussion (2026-06-02 audit) — FRAMING OVERSTATED:**

The "V57/V58/V61/V66" trail in the issue title is not accurate against `main`:

- **V57** (`home_dashboard_query_indexes.sql`) — does NOT touch `webhook_event_outbox` (it indexes loan-application / loan-account tables for the home dashboard query). Grep on `webhook_event_outbox` in V57 returns no matches.
- **V58** (`webhook_outbox_loan_application_id.sql`) — adds **1** index: `idx_webhook_event_outbox_loan_application_created_at (loan_application_id, created_at DESC)` (line 33). Needed for the per-loan webhook view.
- **V61** (`prune_redundant_indexes.sql`) — does NOT touch `webhook_event_outbox`; it prunes redundant loan_application / loan_account / borrower indexes (see V61 lines 16–22).
- **V66** (`webhook_outbox_loan_application_fk.sql`) — adds the FK only; no new index.

Net total on `webhook_event_outbox`:
1. `idx_webhook_event_outbox_created_at (created_at DESC)` — V24, dispatcher-claim scan.
2. `idx_webhook_event_outbox_lsp_created_at (lsp_id, created_at DESC)` — V24, admin list-by-LSP.
3. `idx_webhook_event_outbox_loan_application_created_at (loan_application_id, created_at DESC)` — V58, per-loan view.

Three indexes for three distinct query patterns is not "bloat." None are redundant against any of the others. The FK from V66 also creates a backing index implicitly only on some PG versions — confirm with `\d webhook_event_outbox` on production once data exists.

**Action:** **re-scope** the issue. The real follow-up (if any) is "once production has measurable write volume, run `pg_stat_user_indexes` to see if any of the three are genuinely cold, and only then drop." There is no current bloat to fix. Suggest closing as **not-applicable** with a note to revisit if `pg_stat_user_indexes.idx_scan = 0` for any index after sustained production load.

---

### #112 — [Q-15] Mixed exception strategies: 400 vs 404 for 'not found'
**Labels:** code-quality · **Link:** https://github.com/sid12701/lms/issues/112

**Problem (plain English):** "Not found" sometimes maps to 400, sometimes to 404, depending on which endpoint you hit.

**Possible fixes:**
1. **Use `ResourceNotFoundException` everywhere for "not found"** — consistent.

**Recommended:** Option 1.

**Effect on app:** Stable status codes per condition.

**Detailed solution after discussion:** _(pending)_

---

### #113 — [Q-16] Verify frontend/dist + frontend-2/dist are gitignored
**Link:** https://github.com/sid12701/lms/issues/113 · **Status:** **VERIFICATION PASSES — CLOSE** (audited 2026-06-02)

**Detailed solution after discussion (2026-06-02 audit) — VERIFICATION PASSES:**

Three independent `.gitignore` rules cover the dist directories:
- Repo-root `.gitignore` line 27: `frontend/dist/`
- `frontend/.gitignore` lines 11–12: `dist` + `dist-ssr`
- `frontend-2/.gitignore` line 5: `dist/`

`git ls-files | grep -E "^(frontend|frontend-2)/dist/"` returns **0 tracked files**, so neither dist directory is committed even though local build artefacts exist on disk.

**Action:** close as VERIFICATION PASSES.

**Closes as duplicate (same evidence):** **#165 [V-5]** — explicitly framed as the verification dup of this issue.

---

### #114 — [D-1] Two frontends bundled — pick a retirement date
**Link:** https://github.com/sid12701/lms/issues/114 · **Status:** **CLOSED — DUPLICATE of #101** (2026-06)

**Detailed solution after discussion (2026-06-06):** Same retirement as **#101** — ADR-0001 accepted and completed; single frontend under `frontend/`.

---

### #115 — [D-2] Parallel ops vs LSP response builders — extract shared projection
**Labels:** duplicate-code · **Link:** https://github.com/sid12701/lms/issues/115

**Problem / Fixes / Recommendation / Effect:** Same as #100. One projection + thin per-audience wrappers.

**Detailed solution after discussion:** _(pending)_

---

### #116 — [D-3] Status mutation has 3 entry points with divergent pre-checks
**Labels:** duplicate-code, fragile-logic · **Link:** https://github.com/sid12701/lms/issues/116

**Problem (plain English):** Status transitions are entered through 3 different methods, each with its own pre-checks. Invariants drift.

**Possible fixes:**
1. **Single `StatusTransitionService.apply(cmd)` with pre-check predicates in one place** — proper.

**Recommended:** Option 1.

**Effect on app:** One place to change invariants. State machine more reliable.

**Detailed solution after discussion:** _(pending)_

---

### #118 — [D-5] Borrower field-walking duplicated in toResponse + toDetailResponse
**Link:** https://github.com/sid12701/lms/issues/118

**Detailed solution after discussion:** _(pending — bundle with #115)_

---

### #119 — [D-6] FE webhook event-type enum still folds legacy values
**Link:** https://github.com/sid12701/lms/issues/119

**Detailed solution after discussion:** _(pending — bundle with #109)_

---

### #120 — [D-7] AuthController carries 9 nested records — extract AuthService
**Link:** https://github.com/sid12701/lms/issues/120 · **Status:** **NUMBER WRONG — re-scope** (audited 2026-06-02)

**Detailed solution after discussion (2026-06-02 audit) — number is wrong:**

`AuthController.java` (447 LoC on `main`) carries **4** public nested records, not 9 (`grep -c "public record"` returns 4; the records are `LoginRequest` (line 200), `ClientCredentialsRequest` (line 206), `TokenResponse` (line 212), `ChangePasswordRequest` (line 220)).

The controller has shrunk since the audit doc was written — bundled fixes for #63 and #64 routed claims/error handling through helper services, and previous nested records appear to have been deleted along the way (no separate `AuthService` extraction happened — they were just removed). The remaining 4 are slim public-API DTOs.

**Action:** **re-scope or close.** The bundling rationale ("extract `AuthService` to clean the controller") still has merit purely from a controller-size standpoint (447 LoC for an auth controller is moderate-to-high, and the auth/refresh/token methods do mint JWTs inline with cookie-building helpers), but the "9 nested records" hook the doc used to motivate the bundle no longer exists. Bundle with #98 only if #98's god-class scope still considers a 447-LoC controller in-scope; otherwise close.

---

### #121 — [D-8] Verify WebhookEventOutboxRepositoryImpl custom shim is still needed
**Link:** https://github.com/sid12701/lms/issues/121 · **Status:** **VERIFICATION PASSES — shim is still needed** (audited 2026-06-02)

**Detailed solution after discussion (2026-06-02 audit) — VERIFICATION PASSES:**

The custom shim in `backend/src/main/java/com/bhawana/lms/repo/WebhookEventOutboxRepositoryImpl.java` is still load-bearing because **H2 does not support `FOR UPDATE SKIP LOCKED`**, but the production Postgres dispatcher path needs exactly that semantic to claim a batch without blocking on rows already claimed by a sibling replica.

The shim does this split (lines 30, 53, 72–84):
- **H2 (test) branch** — JPQL `SELECT … WITH PESSIMISTIC_WRITE` (blocks rather than skips). Acceptable in tests because they run single-threaded against H2.
- **Postgres (prod) branch** — native SQL `select id … for update skip locked limit :batchSize`, then a second fetch by id list to materialize the entities with `lsp` joined. Required so two `WebhookOutboxDispatchWorker` replicas don't both grab the same rows.

If we ever rip out the H2 test path (move everything to Testcontainers PG), the shim collapses to a single Postgres-only implementation but does not go away — Spring Data JPA does not generate `FOR UPDATE SKIP LOCKED` from its method-name vocabulary.

**Action:** **close as VERIFICATION PASSES; the shim is still needed.** Optional follow-up captured in [[#106]] (PG-only schema tests / Testcontainers): if H2 disappears from the test path, simplify the shim to the Postgres branch only. No urgent work.

---

### #126 — [F-3] Invalidate-application not idempotent on admin path
**Labels:** fragile-logic · **Link:** https://github.com/sid12701/lms/issues/126

**Problem (plain English):** Re-invalidating an already-INVALID app returns 400 on admin path. LSP path is idempotent via cache.

**Possible fixes:**
1. **Treat as idempotent (same reason → 200, different reason → 409)** — consistent.

**Recommended:** Option 1.

**Detailed solution after discussion:** _(pending)_

---

### #127 — [F-4] RateLimitFilter scope too narrow (dup of #81)
**Link:** https://github.com/sid12701/lms/issues/127 · **Status:** **CLOSED — DUPLICATE of #81** (2026-06-06)

**Detailed solution after discussion (2026-06-06):** Exact duplicate of **#81**. Closed when #81's config-driven rules table shipped (see #81 implementation status).

---

### #128 — [F-5] FE idempotency-key doesn't fingerprint body
**Labels:** fragile-logic · **Link:** https://github.com/sid12701/lms/issues/128

**Problem (plain English):** Edits-then-double-click can submit two different bodies under the same key.

**Possible fixes:**
1. **`key = uuid + sha1(normalized body)`** — body change → new key.

**Recommended:** Option 1.

**Detailed solution after discussion:** _(pending)_

---

### #129 — [F-6] Webhook signing format (timestamp.payload) undocumented for receivers
**Labels:** fragile-logic, documentation · **Link:** https://github.com/sid12701/lms/issues/129

**Problem (plain English):** Partners re-discover the `timestamp.payload` signing format. No runbook.

**Possible fixes:**
1. **`docs/integrations/WEBHOOK_VERIFICATION.md` with code snippets** — done.

**Recommended:** Option 1.

**Detailed solution after discussion:** _(pending)_

---

### #132 — [F-9] Refresh revoke-then-issue can log user out if issue fails
**Labels:** fragile-logic, security · **Link:** https://github.com/sid12701/lms/issues/132

**Problem (plain English):** Revoke happens before issue. If issue fails, prior token is already revoked.

**Possible fixes:**
1. **Transactional revoke+issue (rollback on issue failure)** — atomic.
2. **Issue first, revoke second with TTL guard** — safe alternative.

**Recommended:** Option 1.

**Detailed solution after discussion:** _(pending)_

---

### #133 — [F-10] No password history — SYSTEM_ADMIN can reuse prior passwords
**Labels:** fragile-logic, security · **Link:** https://github.com/sid12701/lms/issues/133

**Problem (plain English):** Password change accepts any prior password.

**Possible fixes:**
1. **`password_history` table; reject last N hashes** — standard.

**Recommended:** Option 1, scoped to SYSTEM_ADMIN at minimum.

**Detailed solution after discussion:** _(pending)_

---

### #134 — [F-11] invalidateApplicationForLsp passes null account — partial audit data
**Labels:** fragile-logic, auditability · **Link:** https://github.com/sid12701/lms/issues/134

**Problem (plain English):** Audit row sometimes has null `accountId` because of null-pass-through in invalidation path.

**Possible fixes:**
1. **Resolve account up-front; record explicit null with reason** — clear.

**Recommended:** Option 1.

**Detailed solution after discussion:** _(pending)_

---

### #135 — [F-12] autoApproveIfEligibleForLsp safety is caller-defensive, not state-machine enforced
**Labels:** fragile-logic · **Link:** https://github.com/sid12701/lms/issues/135 · **Status:** **CLOSED** — resolved with #85 in [PR #172](https://github.com/sid12701/lms/pull/172) (2026-06-02)

**Problem (plain English):** A caller that forgets the pre-check induces an invalid transition silently.

**Possible fixes:**
1. **Move guard into state machine; remove caller-side defensive checks** — fail loudly.

**Recommended:** Option 1.

**Detailed solution after discussion (2026-06-02 follow-up audit):**

**#135 is NOT moot.** #62 PR (b) was previously expected to make this moot by removing the only caller path through a disbursement TX. The follow-up audit found that `LoanDocumentService.submitStoredDocumentForLsp` (method header line 107; `autoApprove` call at line 133) and `submitStoredDocumentsForLsp` (method header line 138; `autoApprove` call at line 168) are both `@Transactional` and both call `autoApproveIfEligibleForLsp` inside that TX — the same caller-defensive-only pattern this issue names, just on the document-upload path rather than the disbursement path. Plus the dead `LoanDisbursementService.requestDisbursementForLsp` body still sits in the source as a footgun for future callers.

**Plan (bundle with #116 single status-mutation entry point, and with #85's orphan-method cleanup):**
1. Push the guard into the state machine (or into `LoanApplicationStatusTransitioner`) so a caller that forgets the precondition gets an exception, not a silent no-op.
2. Remove caller-defensive `if (status outside {INITIALIZED, AWAITING_APPROVAL}) return` checks from `LoanApplicationLifecycleService.autoApproveIfEligibleForLsp`.
3. Delete the orphaned `LoanDisbursementService.requestDisbursementForLsp` method body as part of the same PR (closes the #85 footgun).
4. Test: forge a call from `LoanDocumentService` against an already-REJECTED app → expect exception, not silent no-op.

**Implementation shipped (2026-06-02, bundled with #85):**
- `LoanApplicationStatusTransitioner` — `enforceTransition` (contexts: `STANDARD`, `MANUAL_OVERRIDE`, `WORKER`) on all `updateApplicationStatus` mutations; `enforceAutoApprovalAllowed` throws `AUTO_APPROVAL_NOT_ALLOWED` outside `{INITIALIZED, AWAITING_APPROVAL}`.
- `LoanApplicationStatus` — servicing edges (`DISBURSED` → `CLOSED` / `FORECLOSED`); worker-only `APPROVED_PENDING_DISBURSAL` → `REJECTED` via `WORKER` context (ops transitions still blocked).
- Tests: `LoanApplicationStatusTransitionerTest`, `Issue135AutoApprovalStateMachineIntegrationTest`, plus #85 integration tests.

**Residual / #116:** Single status-mutation entry point for ops/manual/worker remains a future consolidation; guards are centralised in the transitioner.

---

### #136 — [F-13] Verify V58 webhook_event_outbox.loan_application_id backfill is complete
**Labels:** fragile-logic, database, verification · **Link:** https://github.com/sid12701/lms/issues/136 · **Status:** **VERIFICATION PASSES — CLOSE** (audited 2026-06-02)

**Problem (plain English):** Need to verify backfill is complete; per-loan view may be partial otherwise.

**Possible fixes:**
1. **Run verification query; backfill orphans if any** — simple.

**Recommended:** Option 1.

**Detailed solution after discussion (2026-06-02 audit) — VERIFICATION PASSES:**

V66 (`webhook_outbox_loan_application_fk.sql`) added the FK on `loan_application_id` AND ran a pre-flight orphan check that **fails the migration** if any orphan exists. From V66 lines 11–29:

```sql
DO $$
DECLARE orphan_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO orphan_count
    FROM webhook_event_outbox outbox
    WHERE outbox.loan_application_id IS NOT NULL
      AND NOT EXISTS (
          SELECT 1 FROM loan_application app WHERE app.id = outbox.loan_application_id
      );
    IF orphan_count > 0 THEN
        RAISE EXCEPTION 'webhook_event_outbox has % orphan loan_application_id row(s); investigate before adding FK (see F-03)', orphan_count;
    END IF;
END$$;
```

If V66 succeeded on any environment, the backfill from V58 has held — orphans cannot exist while V66 is in place because the FK with `ON DELETE RESTRICT` prevents `loan_application` rows being deleted without first cleaning up referencing outbox rows.

**Residual nuance:** V58's UPDATE statements only set `loan_application_id` for rows where `aggregate_type IN ('LOAN_APPLICATION', 'LOAN_ACCOUNT', 'LOAN_PAYMENT_TRANSACTION')`. Rows of any other `aggregate_type` keep `loan_application_id = NULL`, which is fine (the column is nullable on purpose — non-loan-tied webhook events legitimately have no application linkage; the per-loan view filters by `loan_application_id = ?` and excludes them by design).

**Action:** close as VERIFICATION PASSES.

---

### #140 — [SEC-Δ-2] Webhook signing secret returned in admin GET
**Link:** https://github.com/sid12701/lms/issues/140

**Detailed solution after discussion:** _(pending — bundle with #72)_

---

### #141 — [SEC-Δ-3] No LSP self-service foreclosure close path
**Link:** https://github.com/sid12701/lms/issues/141

**Detailed solution after discussion:** _(pending — bundle with #74)_

---

### #143 — [SEC-Δ-5] Verify SsrfSafeUrlValidator wired
**Link:** https://github.com/sid12701/lms/issues/143 · **Status:** **CLOSE AS DUPLICATE OF #82** (audited 2026-06-02)

**Detailed solution after discussion (2026-06-02 audit):** verification passes per § #82 (write-time validation in `AdminDirectoryService:212` + dispatch-time validation in `HttpWebhookDeliveryClient:28`). Close as duplicate.

---

### #156 — [R-1] Home KPIs recomputed on every page load — no cache
**Labels:** dashboard-risk, scale-risk · **Link:** https://github.com/sid12701/lms/issues/156

**Problem (plain English):** Admin home runs count queries + TAT transitions on every load. Hot spot under load.

**Possible fixes:**
1. **Cache KPI projection (~60s TTL) + invalidate on relevant events** — standard.
2. **Materialized view refreshed by worker** — heavier, better for very large data.

**Recommended:** Option 1.

**Detailed solution after discussion:** _(pending)_

---

### #158 — [R-3] Dashboard KPIs are point-in-time only
**Labels:** dashboard-risk · **Link:** https://github.com/sid12701/lms/issues/158

**Problem (plain English):** No "as-of X date" support. Auditors won't see historical numbers.

**Possible fixes:**
1. **Document semantics now; add daily snapshot table if/when audit demands it** — pragmatic.

**Recommended:** Option 1.

**Detailed solution after discussion:** _(pending)_

---

### #159 — [R-4] Audit Explorer only covers 4 streams
**Labels:** dashboard-risk, auditability · **Link:** https://github.com/sid12701/lms/issues/159 · **Status:** **CLOSED** — [PR #174](https://github.com/sid12701/lms/pull/174) (2026-06-02)

**Problem (plain English):** Login/webhook/user-mgmt audit streams aren't searchable in Explorer.

**Possible fixes:**
1. **Add streams as the underlying audit tables land (#71, #149, #153)** — incremental.

**Recommended:** Option 1 — implemented per the grilled **§#152 two-PR plan** (not “wire every audit table in one shot”).

**Detailed solution after discussion (2026-06-02):** Canonical design lives in **§#152** ([AUD-6]). #159 closes when the Explorer exposes **7 streams** on the live `GET /api/v1/internal/admin/audit-events` endpoint.

| Stream | Source table | PR slice |
|--------|----------------|----------|
| APPLICATION | `loan_application_audit_event` | pre-existing |
| INTAKE | `loan_application_intake_audit` | pre-existing |
| DOCUMENT_ACCESS | `loan_application_document_access_audit` | pre-existing |
| PRODUCT | `loan_product_audit_event` | pre-existing |
| APP_USER | `app_user_audit_event` | #152 (a) |
| API_CLIENT | `api_client_audit_event` | #152 (a) |
| DISBURSEMENT | `disbursement_outcome_audit` | #152 (b) |
| REPORT_ACCESS | `report_access_audit` | #151 follow-up (2026-06-04) |

**Out of scope for #159 PR #174 (follow-ups):** AUTH (`#71`), LSP/webhook config (`#153`), server-side `correlationId` / free-text `q` (`#76`), `LSP_AUDIT` / `lsp_audit_event` (9th stream after `#153` lands).

**Follow-up — REPORT_ACCESS stream (#151 audit table):** Shipped 2026-06-04 with #151. `REPORT_ACCESS` is the **8th** live Explorer stream (7 from #174 + report access). Backend UNION on `report_access_audit`; FE tab + `AUDIT_STREAM_BADGE_TONE`; `AuditExplorerControllerReportAccessStreamTest` + H2 parity.

**Implementation notes (locked):**
- `ClientIpAddresses.resolve` on mock-outcome (not raw `getRemoteAddr()`).
- `userId` / `apiClientId` surface in `detail` JSON; FE `subjectFor` extended; unified envelope unchanged.
- FE `PII_REVEAL` tab removed from `/audit` (no backend stream; forensic table stays write-only).
- Parity: `AuditExplorerStreamProjectionParityTest` — every `AuditStream` branch executes on H2.

**Implementation status — CLOSED (2026-06-02, [PR #174](https://github.com/sid12701/lms/pull/174)):**

| Area | Delivered |
|------|-----------|
| Backend | `AuditStream` 4→7; UNION branches + projection in `AuditExplorerRepository` / `AuditExplorerService` |
| Frontend | `frontend-2/src/features/audit/` — stream types, tabs, `subjectFor`, detail sheet |
| Tests | `AuditExplorerController*StreamTest`, `AuditExplorerStreamProjectionParityTest`, audit `page.test.tsx` stream cases |

**8th+ streams checklist (for GitHub issue body):** AUTH, LSP_AUDIT, REPORT_ACCESS, server-side correlation/q filter, optional per-app disbursement drill-down.

---

### #160 — [R-5] MIS processingFeeAmount formula not parity-tested
**Labels:** reporting-risk · **Link:** https://github.com/sid12701/lms/issues/160

**Problem (plain English):** Report formula may not match canonical fee calc; no parity test.

**Possible fixes:**
1. **Extract `LoanFeeCalculator`; parity test report row vs calc** — proper.

**Recommended:** Option 1.

**Detailed solution after discussion:** _(pending)_

---

### #162 — [V-2] Verify SsrfSafeUrlValidator wired (dup of #82)
**Link:** https://github.com/sid12701/lms/issues/162 · **Status:** **CLOSE AS DUPLICATE OF #82** (audited 2026-06-02)

**Detailed solution after discussion (2026-06-02 audit):** verification passes per § #82. Close as duplicate.

---

### #165 — [V-5] Verify frontend/dist + frontend-2/dist gitignored (dup of #113)
**Link:** https://github.com/sid12701/lms/issues/165 · **Status:** **CLOSE AS DUPLICATE OF #113** (audited 2026-06-02)

**Detailed solution after discussion (2026-06-02 audit):** verification passes per § #113. Close as duplicate.

---

### #166 — [V-6] Decide fate of Redis + RabbitMQ in docker-compose
**Labels:** verification · **Link:** https://github.com/sid12701/lms/issues/166 · **Status:** **FRAMING HALF-WRONG — re-scope** (audited 2026-06-02)

**Problem (plain English):** Compose runs Redis + RabbitMQ but no current backend code uses them.

**Possible fixes:**
1. **Document as reserved-for-future (with target phase)** — clarifies intent.
2. **Remove from compose** — cleans up.

**Recommended:** Option 1 if there is a Phase 8/9 dependency; otherwise Option 2.

**Detailed solution after discussion (2026-06-02 audit) — framing half-wrong, split the decision:**

The doc says "no current backend code uses them" — that's only true for one of the two.

- **Redis IS used** — `backend/src/main/java/com/bhawana/lms/security/RateLimitConfig.java` wires a `RedisClient` (lines 21–27) and a Lettuce-backed Bucket4j `ProxyManager` (lines 35–42) consumed by `RateLimitFilter`. Configuration toggle: `app.rate-limit.enabled` (defaults true). So Redis is **production-load-bearing** for distributed rate limiting across replicas. Removing it would break the rate limiter unless you also flip the rate limit to in-memory bucket4j (acceptable for local dev, **not** for multi-replica prod).
  - **Action for Redis:** keep in compose. Optionally rename the compose service comment to call out the rate-limit dependency.

- **RabbitMQ is genuinely unused in code** — `pom.xml` line 45 still pulls `spring-boot-starter-amqp` as a dependency, but no Java code imports `RabbitTemplate`, `@RabbitListener`, `amqp.*`, or any AMQP API (grep across `backend/src/main/java` returns zero matches). The starter is dead weight bringing in transitive deps.
  - **Action for RabbitMQ:** drop the compose service AND remove `spring-boot-starter-amqp` from `pom.xml`. If a future phase needs an event bus, re-add intentionally with a real consumer. Keeping unused infrastructure on the dev stack signals "use this" to new contributors and adds noise to startup time.

**Recommended split:**
- Keep Redis (load-bearing for rate limiting).
- Remove RabbitMQ from `infra/docker-compose.yml` AND the AMQP starter from `backend/pom.xml` in the same PR.

Both halves are small. Bundle them or ship as two trivial PRs.

---

## Grill schedule

We'll go P0 → P1 → P2, in issue-number order within each tier. Per turn:
1. I present the issue and the options.
2. You pick (or describe a different path).
3. I write your decision into the "Detailed solution after discussion" field for that issue.
4. Move to the next.

Skip-ahead and re-open are fine — just say which issue # you want.
