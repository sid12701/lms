# Fresh-eyes implementation audit — 2026-07-15

## Executive result

This audit traced Specs S1–S20 in the Production Readiness Report, including the Claude Code
addendum findings NEW-01 through NEW-05/SCH-01, through the implementation log, deferred register,
migrations, code, callers, tests, and executable behavior.

The implemented technical slices are now in a materially stronger and production-grade state, but
the repository is **not approved for real-money production or partner pilot**. S5, S6, S13, S14,
S15, S16, S17, S18, and the S19 cutover residual are intentionally unimplemented or incomplete.
Several are explicit real-money, privacy, collections-system-of-record, or operational gates. No
business values or policy decisions were invented to close them.

The most serious fresh finding was in S3: an `UNKNOWN` disbursement intent could be reclaimed and
the payment request issued again. That duplicate-payout root cause is fixed. Other material
corrections made during this audit include atomic/fenced idempotency completion, removal of a
read-side database repair from disbursement preview, lockdown of the relationship dual-write API,
real operational gauges for ambiguous disbursements and pending idempotency records, a truthful
Playwright setup contract, generated loan-account status vocabulary, and removal of the final React
test warnings plus a controlled/uncontrolled selector defect. A fresh dependency audit also found
and removed a React Router open redirect and vulnerable build/test transitive packages.

## Audit method and limits

- Primary requirements: the report's §19.3–§19.5 specs and §19.6 implementation log, plus
  `docs/implementation-log.md` and `docs/deferred-implementation.md`.
- Code traversal: Graphify repository graph first, followed by direct inspection of entities,
  repositories, services, controllers, workers, migrations, frontend consumers, and tests.
- Runtime evidence: full backend suite and PostgreSQL/Testcontainers migration paths; frontend
  release gates and tests; unauthenticated live-browser login validation against the running stack.
- Attribution: the working tree contained a large uncommitted implementation. “Cursor approach”
  below means the implementation present when this fresh audit began, not a claim about individual
  commit authorship.
- Authenticated Playwright E2E could not be reproduced because no
  `E2E_ADMIN_EMAIL`/`E2E_ADMIN_PASSWORD` was available. The harness now skips with an explicit
  reason rather than fabricating credentials or failing nondeterministically.
- Axe checks under JSDOM still print its standard unimplemented canvas-context notice. The
  application does not use a canvas on those surfaces; accessibility assertions pass, and the
  message is distinct from the S1 React state-update warnings, which are zero.
- A local `npm ci` clean reinstall could not remove Tailwind's native Windows binary while an
  older user-owned Vite process was using it. That process was not stopped. A non-destructive
  install synchronized the lockfile; `npm ls`, installed-version checks, both audit modes, all
  gates, build and full tests then passed. A clean-checkout CI install was not reproduced here.

## Issue-by-issue findings

### S1 — Frontend release baseline (NEW-03)

**Expected.** Typecheck, lint, formatting, encoding, unit/component tests, and production build must
all pass, including the spec's explicit zero-`act(...)`-warning criterion.

**Cursor approach and verdict.** The release scripts and most code fixes were correct, but the
implementation log overstated closure: a full 124-file run passed 761 assertions while still
emitting React `act(...)` warnings. Verdict: **partial, corrected**.

**Fresh findings and changes.** Async report/loan accessibility checks and document upload
completion were not fully awaited. API-client Select tests left Radix/react-hook-form updates
pending, and the create selector changed from uncontrolled to controlled. Tests now await the
terminal state; the selector uses `value={field.value}` throughout; unit tests exercise Radix's
native form bridge without JSDOM portal timing. Invalid TypeScript deprecation config and formatting
drift had already been corrected in the working tree.

**Affected.** Frontend Vite/TypeScript gates; report and loan-application page tests;
`DocumentUploadRow`; API-client create/edit dialogs and tests.

**Verification.** Typecheck, ESLint with zero warnings, Prettier check, encoding check, production
build, full 124-file test run, and a 5-file/22-test warning regression shard. **Status: closed for
the implemented frontend baseline.**

### S2 — Test database safety (NEW-01 / TEST-BE-01)

**Expected.** Destructive integration cleanup must be impossible against an accidental real/shared
database unless an explicit operator override is present.

**Cursor approach and verdict.** A database safety guard, Testcontainers marker, and explicit
external-database acknowledgement were introduced. The fresh audit narrowed permissive behavior so
only H2 or an injected ephemeral marker is automatic; remote execution requires
`LMS_IT_EXTERNAL_DB=true`. Verdict: **correct after hardening**.

**Blast radius.** Test bootstrap, integration profiles, Testcontainers support, cleanup utilities,
CI/operator invocation. Production runtime is not affected.

**Verification.** Five guard tests cover safe local/ephemeral execution and unsafe external
rejection. The full backend suite completed without using the running local application database.
**Status: closed.**

### S3 — Durable disbursement intent (MNY-01)

**Expected.** Persist a stable intent/reference before any external side effect; make the provider
call outside database transactions; never reissue a payment after an ambiguous/crash outcome;
support reconciliation and operator visibility.

**Cursor approach and verdict.** The initial intent workflow and later worker transaction split
were directionally right, but `UNKNOWN` remained claimable. A retry could call the provider again,
which recreated the P0 duplicate-payout risk. Verdict: **incorrect at root cause, replaced with the
smallest safe workflow**.

**Fresh changes.** Only `CREATED` is payment-claimable. Before the no-transaction provider call, a
PENDING request log and deterministic transaction reference are committed and the intent becomes
`REQUESTED`. A crash or ambiguous outcome retains that durable record. `UNKNOWN` is reconciled via
provider status and never through another payment request. Adapter identity is obtained from
`providerName()` rather than hardcoded mock data. V111's backfill/index semantics were aligned.
Unknown count and oldest-age Micrometer gauges were added.

**Blast radius.** Intent entity/state machine, request log, repositories, worker processor/service,
mock adapter interface, ops reference/preview path, V111 migration, recovery operations and metrics.
The provider call is confirmed to execute with no active Spring transaction.

**Verification.** Integration tests cover committed intent before call, crash after provider
acceptance, ambiguous outcome reconciliation, stable reference, and exactly one provider request.
Full backend suite and PostgreSQL migrations pass. **Status: closed for duplicate-request safety.**
Residual operational fact: the unavoidable DB/network uncertainty can strand an ambiguous request;
the system now fails safe and exposes it for reconciliation instead of risking duplicate money.

### S4 — Idempotency lease and crash recovery (IDEM-01)

**Expected.** Reclaim stale PENDING leases safely, fence stale attempts, atomically couple the
business mutation and cached response, and recover without repeating secrets or money movement.

**Cursor approach and verdict.** Lease/recovery primitives existed, but completion was not fully
fenced and serialization could fail after business state committed. Verdict: **partial, corrected**.

**Fresh changes.** Action, serialization, and idempotency completion now execute in one
admin-scoped `REQUIRES_NEW` transaction. Completion and cleanup require record id, attempt number,
and lease owner. A stale worker cannot overwrite its successor. Supported loan-create recovery
reconstructs the response from durable state. Unsupported legacy PENDING shapes fail closed with
`IDEMPOTENCY_RECOVERY_REQUIRED`; they are not blindly rerun. Pending count and oldest-age gauges
were added across LSP/admin repositories.

**Verification.** Race, serialization rollback, stale-attempt fencing, and crash-recovery tests are
green in the full backend suite. **Status: closed for new operations.** Existing unsupported legacy
PENDING records require manual reconciliation; this is deliberate fail-safe behavior.

### S5 — Approval-time beneficiary snapshot (DATA-01)

**Expected.** Freeze beneficiary details at approval and fail closed/audit if later profile changes
would redirect payout.

**Cursor approach and verdict.** Explicitly deferred; intent creation still reads live borrower bank
data. S12 labels it `LIVE_BORROWER`, which is truthful but not a substitute. Verdict:
**unimplemented by decision**.

**Blast radius reviewed.** Approval transition, loan-account creation, borrower bank updates,
disbursement preflight/intent creation, preview UI, migrations and audit. **Status: open real-money
gate.** Requires the approved snapshot/reaffirm behavior before live rails.

### S6 — Mutually exclusive mock/live modes (MOCK-01)

**Expected.** Explicit provider mode, conditional adapter/controller registration, and startup
refusal for mock rails in production/live profiles.

**Cursor approach and verdict.** Deferred. The mock adapter and mock-outcome endpoint remain
unconditional. Verdict: **unimplemented by decision**.

**Status: open P0 configuration gate before S17/live traffic.** The current tree is suitable only
for intentional mock/synthetic operation; no production profile safety claim is made.

### S7 — Same-origin frontend credentials (SEC-03)

**Expected.** Never attach bearer/cookie credentials to a cross-origin URL; provide an explicitly
credentialless external-fetch path.

**Cursor approach and verdict.** Base-URL resolution and cross-origin refusal were appropriate.
Verdict: **correct, with stronger regression coverage**.

**Fresh validation.** Tests now prove caller-supplied `Authorization`, `Idempotency-Key`, and
`credentials: include` are stripped/overridden by `fetchExternal`, while safe headers survive.
Search found no alternate token-bearing fetch path. **Status: closed.**

### S8 — Generated loan-account status vocabulary (ODD-01)

**Expected.** Frontend status values must derive from the backend/OpenAPI contract, not a manually
duplicated enum.

**Cursor approach and verdict.** Values were manually aligned but still drift-prone. Verdict:
**partial, corrected**.

**Fresh changes.** Backend schema exposes the enum; the frontend generation pipeline derives
`LOAN_ACCOUNT_STATUSES` from OpenAPI and formats both generated files. The PowerShell contract
generator now evaluates Maven/npm exit codes explicitly: it propagates real command failures
without treating harmless native stderr warnings as PowerShell failures or printing false success.
Exhaustive status presentation remains typed.

**Verification.** Contract generation, typecheck, tests and build pass. **Status: closed.**

### S9 — Reproducible E2E harness (NEW-02)

**Expected.** Environment-only credentials, deterministic API fixture setup/teardown, no stale UI
assumptions, and explicit skip behavior when operator prerequisites are absent.

**Cursor approach and verdict.** The Playwright fixture harness was largely sound, but global setup
returned `{env}` as though Playwright propagated it to workers; Playwright ignores that return
shape. Verdict: **partial, corrected**.

**Fresh changes.** Global setup returns `void`; the fixture file is the documented worker handoff.
`.auth` remains ignored; missing credentials/application fixture yields an explicit skip reason.
The login screen was exercised live and rendered the current Email/Password flow with no app error,
storage leak, or overflow.

**Status: harness code closed; authenticated E2E execution unverified in this environment** because
credentials were not supplied. This is a validation limitation, not a claim that E2E passed.

### S10 — Home/Audit/login guards and bootstrap sync (OPS-01 / ENV-01)

**Expected.** Guard restored surfaces and allow safe, audited bootstrap-account resynchronization
without restart or privilege leakage.

**Cursor approach and verdict.** Canary and sync features existed, but the fresh audit completed
the durability/security boundary. Verdict: **correct after hardening**.

**Fresh changes.** Bootstrap sync runs in an admin-scoped transaction, writes a durable
`AppUserAuditEvent`, captures actor/correlation context, avoids duplicate lookup, and remains
SYSTEM_ADMIN-only. The PostgreSQL test restores both bootstrap and audit state; OPS is forbidden.

**Verification.** Controller/security/PostgreSQL integration tests and the full backend suite pass.
Live unauthenticated login validation passes. **Status: closed.**

### S11 — Access token out of localStorage (SEC-01 item 4)

**Expected.** Persist non-secret session metadata only; keep the access token in memory; remove
legacy persisted tokens and refresh through the HttpOnly-cookie path.

**Cursor approach and verdict.** Minimal and architecturally consistent. Verdict: **correct**.

**Verification.** Storage tests prove new and legacy data contain no token; repository search found
no secondary browser persistence. Live unauthenticated local/session storage was empty.
**Status: closed.** Residual XSS risk still warrants normal frontend CSP/dependency hygiene, but the
specified persistent bearer-token exposure is removed.

### S12 — Truthful disbursement money preview (UX-02)

**Expected.** Confirmation must show principal, fees, net payout, payment mode, and beneficiary
source without changing state during a GET.

**Cursor approach and verdict.** Backend/frontend preview was useful, but the read service called a
status writer to create/repair a missing loan account. Verdict: **partial and behaviorally unsafe,
corrected**.

**Fresh changes.** Preview is now read-only and fails `LOAN_ACCOUNT_MISSING` rather than mutating
database state. The dialog disables confirm during load/error and displays the shared amount math,
beneficiary and `LIVE_BORROWER` source. S5 remains intentionally separate.

**Verification.** A red-first unit test proves preview cannot repair state; integration and dialog
tests pass. **Status: closed for display/read semantics.**

### S13 — Receipt/allocation/suspense/reversal ledger (MNY-02)

**Expected.** Durable receipt, allocation, suspense, reversal and account-reopen semantics for real
collections.

**Cursor approach and verdict.** Deferred. Exact full-installment posting remains; partial/bunched,
advance, bounce and reversal flows are not representable. Verdict: **unimplemented by decision**.

**Status: open P0 gate before this LMS is a collections system of record.** No synthetic schema or
allocation policy was invented.

### S14 — STP caps and maker-checker (CTRL-01)

**Expected.** Amount/budget/velocity controls and independent checker authorization where required.

**Cursor approach and verdict.** Deferred. A single SYSTEM_ADMIN/worker can initiate without signed
thresholds or a second principal. Verdict: **unimplemented by decision**.

**Status: open P0 real-money authorization gate.** Thresholds require risk/product approval.

### S15 — PAN display policy (NEW-04 / SEC-01 item 3)

**Expected.** Mask partner/list surfaces; permit audited full PAN only on approved internal detail
surfaces using the approved format.

**Cursor approach and verdict.** Deferred. Raw PAN remains on several LSP/admin serializers and the
frontend field name falsely implies masking. Verdict: **unimplemented by decision**.

**Status: open privacy/partner-pilot gate.** The existing generic masking helper does not implement
the approved display matrix, so applying it indiscriminately would be incorrect.

### S16 — Per-LSP rates and idempotent bulk intake (F-API-02)

**Expected.** Partner-specific read/write/burst/bulk plans and an idempotent bulk create contract.

**Cursor approach and verdict.** Deferred. The current per-LSP key uses one global write tier; most
reads and all bulk behavior remain outside the spec. Verdict: **unimplemented by decision**.

**Status: open before high-volume/differentiated partner onboarding; not a blocker for low-volume
synthetic UAT.**

### S17 — Real ICICI Composite Pay adapter

**Expected.** A real, mutually exclusive adapter using bank-approved fields, authentication,
status/reconciliation semantics and production isolation.

**Cursor approach and verdict.** Approved in principle but not implemented because bank details and
S6/S14 prerequisites are absent. Verdict: **correctly not fabricated**.

**Status: open real-rail gate requiring external bank/operational input.** Implementing a guessed
payload or status model would create financial risk.

### S18 — Retention lifecycle and partitioning (DATA-02)

**Expected.** Classed retention, legal holds, manifests, object deletion, archive, and scalable
partition lifecycle.

**Cursor approach and verdict.** Deferred except existing idempotency retention. Verdict:
**unimplemented by decision**.

**Status: open before compliance audit/pilot scale/real-money capacity sign-off.** The fresh audit
did not add speculative partition migrations to low-volume synthetic UAT.

### S19 — Canonical borrower/LSP relationships (D8)

**Expected.** Relationship rows become the canonical tenant-visibility model and eventually retire
the ElementCollection/access table, with normalization and database constraints.

**Cursor approach and verdict.** Slice A added V113, backfill, RLS, dual-write, metadata and parity
checks while intentionally retaining legacy read authority. A public service upsert still allowed
callers to bypass the legacy dual-write path. Verdict: **partial slice, corrected within its stated
boundary**.

**Fresh changes.** Generic `upsertRelationship` is private; all visibility grants use the single
dual-write method. A reflection regression test prevents re-exposure. Migration/backfill and RLS
were inspected against callers and tenant tests.

**Status: Slice A closed; overall S19 remains partial.** Cutover/drop, normalizer/CHECK constraints,
generic update audit, and money isolation remain deferred. Dual-write divergence is an operational
risk until cutover.

### S20 — Partner schedule date/interest validation (NEW-05 / SCH-01)

**Expected.** Reject implausible first dates/cadence/horizons and interest inconsistent with the
frozen product rate; apply at partner ingestion and pre-disbursement.

**Cursor approach and verdict.** Central validator, product-accepted bounds, anchored cadence, and
row/total interest checks are used on both paths. The temporary kill switch was removed. Verdict:
**correct and appropriately scoped**.

**Verification.** Validator unit tests, LSP integration fixtures, disbursement revalidation and the
full backend suite pass. The documented partner 422 tightening is intentional. **Status: closed.**

### Fresh finding AUD-01 — Frontend dependency advisories

**Expected.** The shipped browser dependency graph and build/test toolchain should contain no known
audited vulnerabilities, and build-only CLIs should not be classified as production dependencies.

**Initial state and verdict.** `npm audit --omit=dev` reported five advisories, including the direct
React Router 6.30.3 protocol-relative open redirect. `shadcn` was incorrectly a production
dependency, pulling Hono/Babel tooling into the production audit graph. The all-dependency audit
also exposed vulnerable Vite/Vitest versions. Verdict: **incorrect dependency hygiene, corrected**.

**Changes.** Patched React Router to 6.30.4; moved `shadcn` to dev dependencies; upgraded Vite,
Vitest, coverage and UI packages as a compatible set; applied compatible transitive fixes. No
runtime API or UI behavior was intentionally changed. The nested frontend's Husky prepare script
now runs from the repository root and no longer hides a permanent “.git can't be found” failure.

**Verification.** Full and `--omit=dev` audits both report zero vulnerabilities; dependency tree is
valid; typecheck, lint, formatting, encoding, targeted router/dialog/page/upload tests and the
production build pass on the secure versions; `npm run prepare` installs the expected hooks path.
**Status: closed.**

## Claude Code addendum reconciliation

| Finding         | Canonical spec | Final result                                                           |
| --------------- | -------------- | ---------------------------------------------------------------------- |
| NEW-01          | S2             | Closed; explicit safe-database marker/override behavior tested.        |
| NEW-02          | S9             | Harness corrected; authenticated run awaits operator credentials.      |
| NEW-03          | S1             | Gates green and warning regressions corrected.                         |
| NEW-04          | S15            | Still deferred; privacy/partner gate remains open.                     |
| NEW-05 / SCH-01 | S20            | Closed; ingestion and pre-disbursement validation share one validator. |

## Key file/component trace

This is the principal trace, not an exhaustive list of every test fixture or generated artifact.

| Spec   | Principal implementation / evidence                                                                                                  |
| ------ | ------------------------------------------------------------------------------------------------------------------------------------ |
| S1     | `frontend/package.json`, `vite.config.ts`, affected page/dialog/upload tests, `ApiClientCreateDialog.tsx`                            |
| S2     | `IntegrationTestDatabaseTargetGuard.java`, Testcontainers support/listener classes, backend test profiles/README                     |
| S3     | `DisbursementIntentWorkflowService.java`, worker processor/service, intent repositories/entities, V111, workflow/metrics tests       |
| S4     | `IdempotencyExecutionCoordinator.java`, claim/recovery services, LSP/admin idempotency services/repositories, V112, race/crash tests |
| S5     | Borrower bank fields, approval/loan-account creation, disbursement command/intent and preview paths (deferred)                       |
| S6     | Adapter registration, mock adapter, ops mock-outcome route, worker mock auto-resolve configuration (deferred)                        |
| S7     | `frontend/src/lib/api/http-client.ts` and `http-client.test.ts`                                                                      |
| S8     | backend `LoanAccountStatus`, OpenAPI snapshot, status generator, `generated/loan-account-status.ts`, schema consumers                |
| S9     | `frontend/e2e/global-setup.ts`, teardown, fixture helpers, Playwright config, `docs/e2e.md`                                          |
| S10    | `LocalBootstrapAdminSyncService.java`, system controller, app-user audit entity/repository, PostgreSQL/controller tests              |
| S11    | `session-storage.ts`, session types/provider and storage tests                                                                       |
| S12    | `DisbursementPreviewService.java`, ops controller/DTO, preview summary/dialog/API and integration/unit tests                         |
| S13    | repayment command/installment/payment model and UI repayment flow (deferred ledger)                                                  |
| S14    | disbursement initiate/worker authorization surfaces (deferred control model)                                                         |
| S15    | LSP/admin borrower serializers, PAN masking/reveal audit services and frontend borrower/my-loans mappings (deferred policy)          |
| S16    | LSP rate-limit filter/config/admin model and partner create API (deferred bulk/plan model)                                           |
| S17    | disbursement adapter interface and provider configuration boundary (real adapter absent)                                             |
| S18    | idempotency retention worker plus audit/auth/webhook/report storage streams (broader lifecycle deferred)                             |
| S19    | V113, relationship entity/repository/service, legacy access writer, RLS, borrower API/UI and tenant/unit tests                       |
| S20    | `ScheduleValidationProperties.java`, `LoanRepaymentScheduleService.java`, violation types/config, partner contract and tests         |
| AUD-01 | `frontend/package.json`, `package-lock.json`, npm audit evidence, router entry tests and Vite/Vitest build/test gates                |

## Cross-cutting blast-radius conclusions

- **Transactions/concurrency:** provider I/O is outside transactions; intent creation/request log is
  committed first; idempotency completion is atomic and lease-fenced; tenant-concurrency tests pass.
- **Database/migrations:** V111/V112/V113 semantics were reviewed; all 109 migrations validate and
  apply from an empty PostgreSQL 17 schema. No migration was added for a deferred policy.
- **Security:** same-origin credentials, memory-only bearer tokens, SYSTEM_ADMIN bootstrap sync,
  RLS relationship coverage and fail-closed legacy idempotency were verified. PAN policy and mock
  provider isolation remain explicitly open.
- **Performance:** new gauges execute small indexed/low-cardinality aggregate queries at scrape time.
  Existing large controllers/handlers remain maintainability risks, but no unrelated structural
  rewrite was justified by these specs.
- **Observability/operations:** ambiguous disbursement and pending-idempotency count/age are now
  measurable. Provider reconciliation/runbook ownership remains required for live operation.
- **Backward compatibility:** implemented corrections preserve API shapes except the explicitly
  documented S20 422 tightening. Fail-closed responses affect only unsafe/missing states or legacy
  pending records that could not be safely replayed.

## Executable validation record

- Backend: Maven/Surefire **778 tests, 146 suites, 0 failures, 0 errors, 1 intentional skip**;
  `BUILD SUCCESS` in 12:15. The skip is the opt-in OpenAPI export test when its property is absent.
- PostgreSQL: **109 migrations successfully validated and applied** from empty schema on PostgreSQL
  17.10 during integration testing.
- Backend package/static compile: Maven `verify -DskipTests` produced the repackaged application
  artifact successfully.
- Frontend: typecheck, ESLint (`--max-warnings 0`), Prettier check, encoding check, API/status
  generation and production build passed. Two complete Vitest runs passed **124 files / 761 tests**;
  the definitive secure-toolchain run used Vitest 4.1.10 and had zero `act(...)` or controlled-state
  warnings. The secure warning/router regression shard passed **6 files / 23 tests**.
- Contract regeneration: opt-in OpenAPI export passed **3 tests**, regenerated the OpenAPI/schema
  and status artifacts, and the post-generation typecheck/format/status tests passed.
- Dependency security: React Router 6.30.4, Vite 7.3.x and Vitest 4.1.10 validated; both full and
  production-only `npm audit` report **0 vulnerabilities**.
- Live browser: `/login` rendered the real current form, no application error or horizontal
  overflow, and no persisted session/token in local/session storage. Only React Router's known
  future-flag warning appeared.
- Changed-code static analysis: no introduced dead-code finding or dependency cycle. Fallow still
  reports 27 pre-existing unused export/type candidates, 27 complexity findings (maximum
  cyclomatic 44), and 16 clone groups. Security-sink candidates were manually checked: the HTTP
  client enforces same-origin credentials or strips credentials for external fetches; generator
  and E2E paths/URLs are repository constants or explicit operator configuration, not untrusted
  runtime input.
- E2E: not run authenticated because required credentials were absent; harness skip path verified
  by code and tests.

## Final release decision and required input

**Technical remediation decision:** implemented issues S1–S4, S7–S12, S19 Slice A, and S20 are
accepted after the corrections above.

**Overall production decision:** **NO-GO for real-money production and partner pilot.** At minimum,
the owner must schedule/decide:

1. S5 beneficiary freeze, S6 provider isolation, S14 financial authorization, and S17 bank adapter
   before any real disbursement rail.
2. S13 before authoritative real-receipt/collections use.
3. S15 before partner pilot or compliance sign-off involving PAN.
4. S16 before high-volume/differentiated partner SLAs.
5. S18 before retention/compliance and real-money capacity sign-off.
6. S19 cutover timing after zero-divergence UAT evidence.
7. Supply non-production E2E credentials to complete the authenticated Playwright proof.

No further purely technical correction discovered by this audit is knowingly left unimplemented.
The remaining items require the already-recorded product, risk, compliance, external-bank, or
operational decisions.
