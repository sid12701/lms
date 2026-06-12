# Code Quality Review — Improvement Tracker

**Source:** Thermo-nuclear code quality review (2026-06-11), full-codebase pass: every file >300 lines in `backend/src/main/java` and `frontend/src` was read or structurally audited; every claim below carries a `file:line` citation verified against the working tree on this date.
**Scope:** Maintainability, abstraction quality, duplication, spaghetti growth, type/boundary cleanliness. **Not** in scope: feature gaps and scalability work (tracked in `bugs-gaps-audit-fixes.md` and `scalability-execution-tracker.md`) — overlaps are cross-referenced, not duplicated.
**Volume context:** India small-ticket model — ~1M loans/yr planned. Findings touching financial math or partner API contracts are graded against that volume.

**How to read each entry:**

- **Problem (plain English)** — what is structurally wrong, no jargon.
- **Evidence** — exact files/lines verified during this review.
- **Why** — the engineering principle at stake and what triggered the flag.
- **Effect on codebase & app** — what it costs today and what breaks/rots if untouched.
- **Detailed solution** — the recommended restructuring, sized honestly, including the "code judo" move where one exists.

---

## At a glance

| ID | Title | Layer | Priority | Effort |
|----|-------|-------|----------|--------|
| [B1](#b1) | Diverged duplicate EMI/schedule generators | Backend | **P0** | S |
| [B2](#b2) | `LoanApplicationService` is a 1,172-line pass-through facade | Backend | P1 | M |
| [B3](#b3) | `LoanApplicationLifecycleService` god class (1,455 lines, ≥5 responsibilities) | Backend | P1 | L |
| [B4](#b4) | Error contract bypassed: 143 `IllegalArgumentException` vs 9 typed throws | Backend | **P0** | M |
| [B5](#b5) | 37-arg command / 29-arg Borrower constructors / null-walls | Backend | P1 | M |
| [B6](#b6) | Detail-response assembly duplicated 6× with ~9 queries per render | Backend | P1 | M |
| [B7](#b7) | 4 circular dependencies papered over with `@Lazy` | Backend | P1 | M |
| [B8](#b8) | Hand-rolled JSON building + inconsistent serialization error handling | Backend | P2 | S |
| [B9](#b9) | `AdminDirectoryService` junk drawer (LSPs + users + borrowers + portfolio) | Backend | P2 | M |
| [B10](#b10) | `AuthController` contains the token service (633 lines) | Backend | P2 | M |
| [B11](#b11) | Partner API reuses internal ops DTO classes | Backend | P1 | S |
| [B12](#b12) | Inline tenant-context escalation in controller | Backend | P2 | S |
| [B13](#b13) | Canonical helpers copy-pasted 8× (`normalizeOptional`, `scaleCurrency`, …) | Backend | P2 | S |
| [B14](#b14) | Dead parameters and misleading authorization signature | Backend | P3 | S |
| [B15](#b15) | Business date = `LocalDate.now(systemDefault())` | Backend | P2 | S |
| [F1](#f1) | Frontend ships a fictional 23-status state machine (backend has 10) | Frontend | **P0** | L |
| [F2](#f2) | Per-feature hand-rolled backend types + mappers (~3,500 lines of api files) | Frontend | P1 | L |
| [F3](#f3) | `requestBlob` lacks the 401-refresh retry `requestJson` has | Frontend | P1 | S |
| [F4](#f4) | `my-loans/detail-page.tsx` 741-line multi-component file | Frontend | P2 | S |
| [F5](#f5) | `DataTable.tsx` internal render duplication | Frontend | P3 | S |
| [C1](#c1) | Repo root littered with one-off artifacts | Cross | P2 | S |
| [C2](#c2) | Machine reports (graphify, fallow) are stale and partially hallucinated | Cross | P2 | S |

Priorities: **P0** = correctness or contract risk now; P1 = structural debt that compounds with every feature; P2 = quality-of-life with clear payoff; P3 = nice-to-have.

---

## Progress log (2026-06-11 review session)

**GitHub issues:** No dedicated issues map 1:1 to tracker IDs `B1`–`C2`. Related open issues: #98 (god classes → B2/B3), #99 (facade → B2), #100 (parallel response builders → B6), #109 (status drift → F1), #112 (mixed 400/404 → B4), #120 (AuthController → B10).

| ID | Review | Outcome |
|----|--------|---------|
| **B1** | Sound; P0 correctness | **Implemented** — single generator in `LoanRepaymentScheduleService.generateIfAbsent`; duplicate removed from lifecycle |
| **B2** | Sound but L-effort; do after B6 | **✅ Done (2026-06-12)** — facade deleted; `LoanDisbursementCommandService`, `LoanApplicationServicingReadService`, `LoanDelinquencySupport`; seed + document-download tests on focused services |
| **B3** | Sound but L-effort | **Partial (step 2)** — `BorrowerOnboardingService`, `LoanApplicationDocumentChecklistService`, `LoanWebhookPayloads`; lifecycle thinned (~450 lines removed); step 1 predicates retained |
| **B4** | Sound; full 143-site sweep is M-effort | **Partial (2026-06-12)** — service sweep + integration tests aligned to typed 404/409/422; remaining IAEs intentional for input validation; Postman/e2e refresh deferred to F2 pass (#112) |
| **B5** | Sound; sequence with B3 | **✅ Done (2026-06-11)** — `BorrowerProfile` record + `BorrowerProfileMappers`; slim `LoanApplicationOnboardingCommand` (9 fields); `Borrower` uses profile constructors/merge; intake audit via `intakeAuditEntries()` |
| **B6** | Sound; M-effort | **✅ Done (2026-06-12)** — `LoanApplicationDetailAssembler` + `LoanApplicationDetailView`; ops/LSP controllers + `LspLoanApiController`; pure `toDetailResponse(LoanApplicationDetailView)` |
| **B7** | Sound; M-effort | **✅ Done (2026-06-12)** — `OpsAlertEmitters` + `AlertRuleEvaluationWorker`; alerts-cycle `@Lazy` removed; `LazyInjectionArchitectureTest` whitelists only lifecycle↔schedule until B3 step 3 |
| **B8** | Sound; depends on B7 step 1 | **✅ Done (2026-06-12)** — `AlertContextJson` + `ObjectMapper` in `OpsAlertEmitters` and `AlertRuleEvaluationWorker`; `escapeJson` removed |
| **B9** | Sound; mechanical M | **✅ Done (2026-06-12)** — `AdminDirectoryService` → `LspDirectoryService`, `UserAdminService`, `BorrowerDirectoryService` |
| **B10** | Sound; M-effort | **✅ Done (2026-06-12)** — `AuthTokenService` + `RefreshCookieFactory`; `AuthController` thinned |
| **B11** | Sound; partner contract isolation | **Implemented** — LSP DTOs; excludes `storageKey`/`fileChecksum` |
| **B12** | Sound; complementary to `AuthenticationTenantScopeFilter` | **✅ Done (2026-06-11)** — `runAsAdmin(Supplier)`; manual snapshot/restore removed from `LspLoanApplicationApiController.doCreateApplication`; escalation moves to `BorrowerOnboardingService` with B3 |
| **B13** | Sound; mechanical S | **✅ Done (2026-06-11)** — `common/util/Strings`, `common/money/Money`; copies replaced in lifecycle, servicing, query, schedule, admin directory, LSP controller |
| **B14** | Sound; quick wins | **Implemented** — `@PreAuthorize` on status transition; dead param removed; identity wrapper inlined |
| **B15** | Sound; S-effort | **✅ Done (2026-06-11)** — `BusinessCalendar` + `Clock` fixed to `Asia/Kolkata`; IST midnight boundary tests |
| **F1** | Sound; L-effort P0 | **Implemented** — canonical 10-status module; TRANSITIONS ⊆ backend matrix; removed legacy mappers; `UNKNOWN:*` badges |
| **F2** | Sound; L-effort (OpenAPI pipeline) | **Deferred** |
| **F3** | Sound | **Implemented** — shared `performFetch` with 401 refresh for blobs |
| **F4** | Sound; mechanical S | **✅ Done (2026-06-11)** — `MarkInvalidDialog`, `MaskedBorrowerCard`, `DocumentsSection` extracted; `detail-page.tsx` ~270 lines |
| **F5** | Clone group **no longer present** in current `DataTable.tsx` | **No change** — already resolved or fallow stale |
| **C1** | Sound housekeeping | **Implemented** — untracked scratch removed; `.gitignore` extended; `graphify-out/` and tracked Postman collection untouched |
| **C2** | Sound | **Blocked in agent env** — Siddhant: run `graphify update .` locally after each code batch (CLI not on agent PATH) |

---

## Report-claim validation

The review was asked to verify the machine-generated reports before trusting them. Verdicts:

### graphify (`graphify-out/GRAPH_REPORT.md`, dated 2026-05-31)

| Claim | Verdict |
|-------|---------|
| God node `LoanApplicationLifecycleService` (64 edges) | **Valid.** 1,455 lines, ≥5 tangled responsibilities — see B3. |
| God node `LoanApplicationService` (59 edges) | **Resolved (B2, 2026-06-12).** Facade deleted; regenerate graph (`graphify update .`) to drop from god-node report. |
| God nodes `of()` (273 edges), `toString()` (182 edges) | **Noise.** Generic Java method names aggregated across unrelated classes; not abstractions. Ignore. |
| God node `requestJson()` (115 edges) | **Valid.** It is the single frontend HTTP transport (`frontend/src/lib/api/http-client.ts:116`) — appropriately central, and in good shape (one defect, see F3). |
| God node `dispatch()` (69 edges) | **Stale.** No `dispatch` function exists anywhere in `frontend/src` today; it lived in the deleted mocks layer / `frontend-2`. |
| "Surprising Connections" (5 backend→frontend `calls` edges, e.g. `LoanApplicationOpsController.from()` → `extractApprovalBlocker()` in a `.ts` file) | **Invalid — all five.** Java does not call TypeScript. All are `INFERRED` edges joining same-named functions across languages. Treat every INFERRED cross-language edge as untrusted. |
| References to `frontend-2\src\...` | **Stale.** `frontend-2/` no longer exists. |
| 496 communities for navigation | **Low value.** Main communities have cohesion 0.01–0.06 and dozens are empty; the report is not currently a useful navigation layer. Regenerate after the mocks/`frontend-2` removal, or drop the wiki ambition. |

### fallow (`fallow-*-after.json` at repo root)

| Claim | Verdict |
|-------|---------|
| Critical complexity: `seedDashboardFixtures` (cyclomatic 48), `buildPreviewRow` (CRAP 503) in `src/mocks/**` | **Stale — files deleted.** The whole `frontend/src/mocks` layer no longer exists. 55 of 446 paths in `fallow-health-after.json` and 6 of 96 in `fallow-dead-code-after.json` point at deleted files. |
| Clone group inside `src/components/app/data/DataTable.tsx` (lines ~285–298) | **Valid.** Still present — see F5. |

**Action:** re-run both tools against the current tree before using them in any future review; the stale JSONs at the repo root should be deleted (C1).

---

# Backend

## <a id="b1"></a>B1 — Diverged duplicate EMI/schedule generators · **P0** · ✅ Done (2026-06-11)

**Session outcome:** `LoanRepaymentScheduleService.generateIfAbsent` is the sole ops-approval entry point; lifecycle duplicate (`Math.pow` EMI) deleted. Regression test: `LoanRepaymentScheduleServiceTest`.

**Problem (plain English):** The repayment schedule for a loan is generated by two different copies of the same algorithm, and the copies no longer agree. Which copy runs depends on which code path created the schedule.

**Evidence:**
- Copy 1: `LoanApplicationLifecycleService.generateRepaymentSchedule` + `calculateMonthlyEmi` (`backend/.../service/LoanApplicationLifecycleService.java:1107-1150, 1435-1445`). EMI compounding uses **`Math.pow` on `double`** (line 1440–1441).
- Copy 2: `LoanRepaymentScheduleService.buildGeneratedInstallments` + `calculateMonthlyEmi` (`backend/.../service/LoanRepaymentScheduleService.java:129-169, 381-391`). EMI compounding uses **`BigDecimal.pow` with `MathContext.DECIMAL64`** (line 387).
- The loop bodies are otherwise line-for-line identical, except copy 2 carries an extra final-installment residual-fold branch (lines 149–152) that copy 1 lacks (and which is dead code given `principalDue = openingPrincipal` two lines earlier — drift in both directions).
- Both paths are **live**: ops approval (`transitionStatus` → `ensureLoanAccountForApprovedApplication` → copy 1) and the LSP `PUT /{id}/repayment-schedule` GENERATED mode (→ copy 2).

**Why:** Copy-pasted financial math is the highest-severity duplication category. Floating-point compounding (`Math.pow`) and `BigDecimal` compounding round differently; at ₹-and-paise scale across 1M loans/yr the copies can produce schedules that differ by a paisa per installment — enough to fail reconciliation, confuse `validateProvidedInstallments`, and make "regenerate the schedule" non-idempotent across paths.

**Effect on codebase & app:** Today: a loan approved through ops gets a `Math.pow` schedule; if the LSP later replaces it in GENERATED mode it gets a `BigDecimal` schedule for the same terms. Any future fix to EMI rounding must be made twice and will, on current evidence, be made once. This also blocks the ICICI integration work (the schedule is the contract the bank repays against).

**Detailed solution:**
1. Delete `generateRepaymentSchedule` and `calculateMonthlyEmi` from `LoanApplicationLifecycleService` entirely.
2. `ensureLoanAccountForApprovedApplication` calls `loanRepaymentScheduleService.generateIfAbsent(loanAccount)` — a new public method wrapping the existing `buildGeneratedInstallments` + the existing "skip if installments exist" guard.
3. Remove the dead residual-fold branch (`LoanRepaymentScheduleService.java:149-152`) or keep it with a test proving it's reachable (it isn't — `closingPrincipal` is identically zero after `principalDue = openingPrincipal`).
4. Add a regression test: same principal/rate/tenure through both entry points must produce byte-identical schedules; add one property-style case (e.g. ₹99,999 @ 23.99% × 18 months) where `Math.pow` vs `BigDecimal.pow` historically differ, pinning the BigDecimal result.
5. This naturally resolves the awkward fact that schedule generation lives in the lifecycle god class at all (supports B3).

---

## <a id="b2"></a>B2 — `LoanApplicationService`: a 1,172-line identity facade · P1 · ✅ Done (2026-06-12)

**Session outcome (A1+A4):** LSP controllers call focused services directly (query/lifecycle/servicing/repayment/document/foreclosure). `recordPaymentTransactionWithRecovery` moved to `LoanRepaymentCommandService`.

**Session outcome (2026-06-12, step 1):** `LoanDisbursementCommandService` owns initiate/mock-outcome; `LoanApplicationServicingReadService` owns per-application reads + document-access audit; `LoanDelinquencySupport` + top-level view records replace inner facade types. `LoanApplicationOpsController` injects servicing-read, disbursement-command, repayment-command directly. Facade shrunk to pure delegation (~500 lines, 8 deps, no `@Lazy`). `LoanDocumentService` no longer depends on facade.

**Session outcome (2026-06-12, step 2 — end state):** `LoanApplicationService` deleted (zero backend references). `LocalDemoPortfolioSeedService` injects lifecycle, query, servicing-read, disbursement-command, repayment-command, foreclosure-command. Document-download integration tests inject `LoanApplicationServicingReadService` / lifecycle directly. Closes tracker mapping to GitHub #99 (facade) and the B2 slice of #98 (god classes).

**Problem (plain English):** The class was correctly decomposed at some point — `LoanApplicationQueryService`, `LoanApplicationLifecycleService`, `LoanRepaymentCommandService`, `LoanForeclosureCommandService`, `LoanDocumentService`, `LoanAutoApprovalGateService` all exist — but the old god class was kept alive as a forwarding layer so callers didn't have to change. Roughly half its methods are one-line delegations.

**Evidence:** `backend/.../service/LoanApplicationService.java` — pure pass-throughs at lines 131–256 (5 list methods → query service), 602–721 (create/transition/override/invalidate/checklist/auto-approve → lifecycle service), 923–981 (4 foreclosure methods → foreclosure service), 1100–1102. It also re-declares the same private helpers its delegates already have (lines 997–1020, 1058–1060; see B13). 22 constructor dependencies (lines 83–129).

**Why:** This is the canonical "thin wrapper that adds indirection without buying clarity." Every new loan feature pays a three-file tax (real service + facade method + controller), the facade's 22 dependencies make it the hardest class in the codebase to construct in tests, and its existence invites exactly the drift it already shows: real logic (`initiateDisbursement`, `resolveMockDisbursementOutcome`, delinquency math at lines 1022–1088) has accreted *into* the facade because it was the path of least resistance.

**Effect on codebase & app:** No user-visible behavior; pure drag. Invisible to the app, expensive to every contributor and to the upcoming scalability work (#203–#206 all touch disbursement/payment flows that currently route through this class).

**Detailed solution (code judo — delete the layer):**
1. Move the three genuine behaviors out: `initiateDisbursement` + `resolveMockDisbursementOutcome` (+ their serializers) → a new `LoanDisbursementCommandService` (~200 lines, sits beside the existing `LoanDisbursementService`); `getLatestActivity` + the delinquency/summary calculators (lines 311–362, 383–401, 1022–1088) → `LoanApplicationQueryService` (they are reads); `recordPaymentTransaction`'s optimistic-lock retry wrapper → `LoanRepaymentCommandService` itself (the retry belongs next to the thing it retries).
2. Repoint the ~6 controllers/services that inject `LoanApplicationService` at the focused services. Mechanical: each call site already names the operation.
3. Delete `LoanApplicationService`. Target end state: 0 lines.
4. Do this *after* B6, which removes most of the controller-side call fan-out and makes the repointing smaller.

---

## <a id="b3"></a>B3 — `LoanApplicationLifecycleService` god class · P1

**Problem (plain English):** One `@Service` owns loan intake, borrower identity resolution and dedupe alerts, the status state machine, manual overrides, auto-approval, document checklist seeding/validation, EMI schedule generation, webhook payload construction, and a pile of string/number normalizers. Five-plus modules in a trench coat.

**Evidence:** `backend/.../service/LoanApplicationLifecycleService.java` (1,455 lines, 17 constructor deps incl. one `@Lazy`):
- Intake + borrower resolution: lines 119–192, 821–1036 (includes the 28-arg `mergeLatestProfile` call wall at 851–880 and a second 28-arg `new Borrower(...)` at 895–925).
- Transition core: 194–295, 525–615 — **three** telescoping `updateApplicationStatus` overloads (6, 7, 8 params).
- Document checklist: 329–523, 1046–1092.
- Schedule generation: 1107–1150 (B1).
- Webhook payload builders: 655–729, 1199–1227 — six near-identical `LinkedHashMap` builders.
- Status-set conditionals duplicated: the "entered servicing" status enumeration appears in `manuallyOverrideStatus` (253–268) and again in `invalidateApplication` (746–751).

**Why:** Every lifecycle bug fix forces a reader through 1,455 lines of unrelated concerns. The duplicated status-set conditionals are the classic missing-enum-predicate smell — the next person adding a status *will* miss one of the two lists. The telescoping overloads exist only because the call signature grew one nullable at a time.

**Effect on codebase & app:** This is the file the graphify report correctly identifies as the #1 god node. It is where the borrower-identity and one-open-loan rules live (launch-critical for the volume model), and it is currently the riskiest file in the repo to modify.

**Detailed solution:**
1. **Enum predicates first (smallest, highest leverage):** add `LoanApplicationStatus.hasEnteredServicing()` and `isManualOverrideTarget()`; replace both conditional walls. The same move applies to `LoanAccountStatus` (checked at `invalidateApplication:754-756` and `LoanApplicationService.initiateDisbursement:750-752`).
2. **Collapse the overloads:** one `updateApplicationStatus(LoanApplication, TransitionCommand)` where `TransitionCommand` is a record with a builder/`withDefaults()` — deletes two overloads and the null-padding at every call site.
3. **Extract `BorrowerOnboardingService`** (~330 lines): `resolveBorrowerForOnboarding`, identity-conflict/active-loan-duplicate raising + their two context serializers, the PAN/mobile/Aadhaar normalizers. It has exactly three collaborators (`BorrowerRepository`, `OpsAlertService`, `BorrowerActiveLoanChecker`).
4. **Extract `LoanWebhookPayloads`** (pure static factory, ~140 lines): all six `build*Payload` methods. They take entities, return `Map` — zero dependencies.
5. **Move checklist logic into the existing `LoanDocumentService` orbit** or a `DocumentChecklistService`: seeding, `ensureDocumentChecklist`, the three "all required documents" predicates, `validateKycCompletionBeforeApproval`. Note `hasAllRequiredLmsManagedDocuments`'s `requireForApprovalOnly` param is documented dead (lines 494–506) — delete the parameter while moving it (B14).
6. Schedule generation already leaves via B1. End state: a ~450-line transition/lifecycle core with single responsibility.

---

## <a id="b4"></a>B4 — Error contract bypassed: everything is a 400 · **P0** · 🔄 In progress (service sweep done)

**Session outcome:** … A5 … **service sweep (2026-06-11)**: `ProductConfigurationService`, `LspStatusService`, `WebhookOutboxService`, `LoanDisbursementService` (`DISBURSEMENT_ALREADY_REQUESTED` 409); idempotency fingerprint mismatch → 409 (`IDEMPOTENCY_CONFLICT`); partner unknown/cross-tenant → 404.

**Session outcome (2026-06-12):** Six integration tests updated for typed error contract (404/409 vs blanket 400/IAE); `DocumentUploadLocalProfileIntegrationTest` uses `test` profile to avoid local Redis gate. Full `mvnw test` green on branch. **Next:** Postman/E2E assert refresh with F2; lifecycle input IAEs remain intentional where validation-only.

**Problem (plain English):** The codebase has a proper typed error system — `ResourceNotFoundException` → 404, `ApiConflictException` → 409, `BusinessRuleViolationException` → 422 with error codes — but the services almost never use it. They throw `IllegalArgumentException` for *everything*: unknown IDs, ownership mismatches, illegal state transitions, bad input. The global handler maps IAE to a blanket `400 INVALID_REQUEST`.

**Evidence:**
- Counts (verified by grep): **143** `throw new IllegalArgumentException` in `backend/src/main/java` vs **8** `BusinessRuleViolationException` and **1** `ResourceNotFoundException`.
- `GlobalExceptionHandler.java:207-210`: `IllegalArgumentException` → `400 INVALID_REQUEST`, message passed through verbatim.
- Concrete miscodings: "Unknown loan application id" (`LoanApplicationService.java:259-262`) returns **400, not 404**; same for LSP-ownership mismatch (`:265-271` — also a tenant-isolation information-shape concern); "already in status X" conflicts (`LoanApplicationLifecycleService.java:208-210`) return **400, not 409**.
- Inconsistency *within one class*: `listWebhookEventsForApplication` correctly throws `ResourceNotFoundException` (`LoanApplicationService.java:413-416`) while every sibling method throws IAE for the identical situation; `getDocumentChecklistItem` throws JPA's `EntityNotFoundException` (`:475`) — a third pattern.

**Why:** The API contract is currently carried by free-text exception message strings. Partners (LSP API clients) cannot programmatically distinguish "doesn't exist" / "not yours" / "wrong state" / "bad input" — they all arrive as `400 INVALID_REQUEST` with prose. Frontend evidence that this already hurts: `readResponseError` in `http-client.ts:58-85` guesses across five message keys and five code keys because the error envelope can't be trusted.

**Effect on codebase & app:** Partner integrations bind to message strings (they have nothing else), which freezes every message as de-facto API surface. Retry logic can't distinguish retryable conflicts from permanent rejections. The ops UI shows raw backend prose. Fixing this *after* LSPs integrate is a breaking change; fixing it now is cheap.

**Detailed solution:**
1. Define the rule once in `docs` (API standards doc already exists): not-found → `ResourceNotFoundException`; cross-tenant access → also `ResourceNotFoundException` (don't leak existence); state conflicts ("already in status", "disbursement already requested") → `ApiConflictException` with a stable code; business-rule failures → `BusinessRuleViolationException` with code; only true input validation stays IAE/Bean Validation.
2. Sweep the 143 sites mechanically, service by service (lifecycle, application, repayment, foreclosure, document, admin). Most are one-line swaps; each gets a stable `errorCode`.
3. Keep the IAE→400 handler as the safety net but add a WARN log + counter (`lms.api.untyped_error`) so new untyped throws are visible and the count ratchets down.
4. Update the Postman/E2E assertions that currently expect 400 where 404/409 becomes correct — do this in the same PRs (cross-ref: scalability tracker #207 "Conflicts → 409" already covers a subset; fold that issue into this sweep rather than running both).
5. Frontend follow-up: collapse `readResponseError`'s guess-chains to the single envelope `{ code, message, fieldErrors }` that `ApiError.of` actually emits (`GlobalExceptionHandler.java:345-360`).

---

## <a id="b5"></a>B5 — 37-argument command, 29-argument constructors, null walls · P1

**Problem (plain English):** Borrower profile data is shuttled around as gigantic positional parameter lists. Creating a loan application from the ops UI passes a 37-argument constructor where ~20 arguments are the literal `null`; the `Borrower` entity has five constructors, two of them 29 parameters long, plus a 28-parameter `mergeLatestProfile`.

**Evidence:**
- `LoanApplicationOpsController.java:243-281` — `new LoanApplicationOnboardingCommand(...)` with 37 positionals, nulls at positions 3, 10–13, 16, 18–19, 22–23, 25–29, 31–37.
- `LspLoanApplicationApiController.java:200-238` — the same constructor, fully populated, 37 positionals.
- `Borrower.java:128, 162, 167-228, 230-294, 296-340` — five constructors; the 10-arg convenience one forwards **19 inline nulls** (lines 308–339). `mergeLatestProfile` at line 603. `LoanApplicationLifecycleService` consumes these at lines 851–880 and 895–925.
- The same field list is re-typed a *fourth* time as a hand-built map in `serializePayload` (`LoanApplicationLifecycleService.java:1152-1197`, 40 `payload.put` lines).

**Why:** Positional argument lists this long are a correctness hazard, not a style issue: two adjacent `String` parameters swapped (e.g. `employmentCity`/`employmentState`, or `referencePersonName`/`accountHolderName`) compile cleanly and corrupt borrower PII silently. The 37-null wall in the ops controller is unreviewable — nobody can verify which null is which field.

**Effect on codebase & app:** Every borrower-field addition touches 6+ sites in lockstep (command record, two controllers, two Borrower constructors, mergeLatestProfile, serializePayload, response mappers). Field-swap bugs would surface as wrong PII on real borrowers — a compliance problem, not just a bug.

**Detailed solution:**
1. Introduce one `BorrowerProfile` record (the ~24 profile fields: name, contact, identity, address, employment, income, bank, references) used by: `LoanApplicationOnboardingCommand` (shrinks to ~8 args: lspId, product selection, externalLoanId, sourceChannel, amount, rate, tenure, profile), the `Borrower` constructor (one canonical ctor taking `BorrowerProfile`; delete the other four — the 10-arg convenience one is only used by tests/seeds, repoint them), and `mergeLatestProfile(BorrowerProfile)`.
2. Controllers build the profile with named accessors from their request records — `BorrowerProfile.from(request)` static factories per controller, so partial sources (ops form has fewer fields) construct explicitly named partial profiles instead of null walls.
3. Replace `serializePayload`'s 40-line map with `objectMapper.valueToTree(profile)` merged with the application envelope — the record *is* the schema.
4. Sequence with B3 step 3 (borrower onboarding extraction) — same files, one PR series.

---

## <a id="b6"></a>B6 — Detail-response assembly duplicated 6×, ~9 queries per detail render · P1 · ✅ Done (2026-06-12)

**Session outcome:** `LoanApplicationDetailAssembler.getDetail` (single read-only txn, one installment fetch); `LoanApplicationDetailView`; ops + LSP controllers + `LspLoanApiController` use one-line `toDetailResponse(detail)`; `LspLoanApplicationResponses` no longer queries mid-map.

**Problem (plain English):** Building the loan-application detail response means calling five separate service methods and stitching them together. That stitch block is copy-pasted five times in the ops controller, and a sixth variant hides inside a "mapper" that takes the service as a parameter and runs queries mid-mapping.

**Evidence:**
- `LoanApplicationOpsController.java` — identical 6-line block at 109–115, 301–307, 324–330, 362–368, 386–392 (`toDetailResponse(application, getLatestActivity, getLoanAccount, getScheduleSummary, getDelinquencySummary)`).
- Each block: `getApplication` re-fetches inside `getLatestActivity` (`LoanApplicationService.java:313`), `getLoanAccount` re-validates the application (`:377-380`), and schedule + delinquency summaries each re-fetch the account *and* the full installment list (`:383-401`) — the installment list is loaded **twice** per render. Net: ~9 repository round-trips, each in its own read-only transaction, for one GET.
- Sixth variant: `LspLoanApplicationResponses.toDetailResponse(application, loanApplicationService)` (`LspLoanApplicationResponses.java:13-77`) — a static mapper making 4 service calls (lines 68–74). A mapper that queries is neither a mapper nor a service.

**Why:** Duplication plus non-atomicity: five sequential transactions can observe different states mid-write (the transition endpoints assemble the detail *after* mutating, so a concurrent payment can make `lastActivity` disagree with `status` in the same response). And the repeated block is precisely the kind of thing that drifts — one endpoint will eventually gain a field the others lack.

**Effect on codebase & app:** Ops detail page latency carries 9 round-trips where 3 suffice; at the planned dashboard refresh patterns this is measurable. Codebase-wise, response assembly is the most-edited surface (every new tab/summary touches it) and currently the most duplicated.

**Detailed solution:**
1. Create `LoanApplicationDetailAssembler` (or extend `LoanApplicationQueryService`) with one `@Transactional(readOnly = true) getDetail(UUID applicationId)` returning a `LoanApplicationDetailView` record (application + account + latest activity + schedule summary + delinquency summary). Internally: one `findDetailedById`, one account fetch, **one** installment-list fetch feeding both summaries, the three latest-activity lookups.
2. Controller endpoints become `return LoanApplicationOpsResponses.toDetailResponse(assembler.getDetail(id));` — one line, five deletions.
3. Make `LspLoanApplicationResponses.toDetailResponse` a pure function of `LoanApplicationDetailView`; delete the service parameter.
4. Fold the webhook-projection N+1 (`LoanApplicationService.toWebhookEventProjection:422-440` queries the latest delivery attempt per event) into the same pass with a single `findLatestAttemptsByOutboxEventIds` batch query.

---

## <a id="b7"></a>B7 — Circular dependencies behind `@Lazy` · P1 · ✅ Done (2026-06-12)

**Session outcome:** `OpsAlertEmitters` (ad-hoc emission) + `AlertRuleEvaluationWorker` (scheduled `evaluate*` + `listRules`); alerts-cycle `@Lazy` removed. Facade↔document `@Lazy` removed with B2 facade deletion. `LazyInjectionArchitectureTest` blocks new `@Lazy` constructor injection; only `LoanApplicationLifecycleService` ↔ schedule remains until B3 step 3.

**Problem (plain English):** Four injection sites use `@Lazy` to break dependency cycles instead of fixing the shape that created them.

**Evidence (grep-verified, post B2):** `LoanApplicationLifecycleService` (`@Lazy LoanRepaymentScheduleService`) only. Alerts-cycle and facade↔document `@Lazy` sites removed.

**Why:** The lifecycle↔alerts cycle exists because `AlertRuleEvaluationService` is two things in one class (see its structure: scheduled `evaluate*` rules at lines 350–646 *and* ad-hoc `emit*` helpers at 110–344 that domain services call). Domain services need the emitters; the evaluator needs domain repositories; fusing them creates the cycle. `@Lazy` hides it and leaves a runtime-proxy landmine (lazy beans fail at first use, not at startup).

**Effect on codebase & app:** Startup-order fragility, confusing test wiring, and an architecture in which "who calls whom" can't be answered from the class graph. The alerts cycle also blocks cleanly extracting the lifecycle pieces in B3.

**Detailed solution:**
1. Split `AlertRuleEvaluationService` (688 lines) into `OpsAlertEmitters` (the 9 `emit*` methods — depends only on `OpsAlertService` + `ObjectMapper`) and `AlertRuleEvaluationWorker` (the scheduled `evaluate*` rules + rule bookkeeping). Domain services inject `OpsAlertEmitters`; the cycle and the `@Lazy` disappear.
2. ~~`LoanApplicationService`'s `@Lazy LoanDocumentService`~~ — **done (B2):** facade deleted; `LoanDocumentService` calls focused services directly.
3. Audit the remaining two the same way; for `WebhookOutboxDispatchExecutor` the usual fix is extracting the shared lower-level dependency rather than lazy-injecting the peer.
4. Add ArchUnit (already feasible — tests use JUnit 5) rule: no `@Lazy` on constructor parameters; cycles fail the build.

---

## <a id="b8"></a>B8 — Hand-rolled JSON + inconsistent serialization failure policy · P2 · ✅ Done (2026-06-12)

**Session outcome:** `common/util/AlertContextJson` (log-and-null on failure); `OpsAlertEmitters` + `AlertRuleEvaluationWorker` use `ObjectMapper`; `escapeJson` deleted; lifecycle duplicate/borrower-conflict contexts aligned.

**Problem (plain English):** One service builds alert-context JSON by string concatenation with a homemade escaper that only handles backslash and quote; everything else uses Jackson. Separately, the same concern (serialize alert context) swallows failures in one place and throws in another.

**Evidence:**
- `AlertRuleEvaluationService.java:202-251` (`StringBuilder` JSON with `escapeJson`) and `:679-684` — `escapeJson` misses control characters, so a newline in an LSP code or detail value produces **invalid JSON** stored in the alert context column.
- Same pattern again at `:252+` (`emitHolderNameSoftMismatch` string-concatenates JSON).
- Policy inconsistency: `serializeActiveLoanDuplicateContext` returns `null` on `JsonProcessingException` (`LoanApplicationLifecycleService.java:1031-1035`) while `serializeBorrowerConflictContext` throws `IllegalStateException` (`:1375-1379`) — adjacent methods, same concern, opposite behavior.

**Detailed solution:** Replace all string-built JSON with `objectMapper.writeValueAsString(Map.of(...))` (the class already has access to it via the split in B7 step 1). Pick one failure policy for *alert context* serialization — log-and-degrade (context = `null`) is right here because an alert without context beats a failed business transaction — and apply it in one shared helper (`OpsAlertEmitters.serializeContext`). Delete `escapeJson`.

---

## <a id="b9"></a>B9 — `AdminDirectoryService` junk drawer · P2

**Problem (plain English):** One 827-line "directory" service owns LSP CRUD + webhook subscriptions, user administration (create/update/password-reset/session-revocation/audit snapshots), borrower search/detail, and portfolio/delinquency aggregation.

**Evidence:** `backend/.../service/AdminDirectoryService.java` — LSPs at 125–247, users at 248–573 (incl. audit-event serialization 491–563 and `generateTemporaryPassword` 574), borrowers at 615–751, plus 8 inline view-records (753–827).

**Why:** Three admin domains evolve at different speeds (user management changes with auth work; borrower views change with PII policy; LSP directory changes with partner onboarding). One class means every change risks the others, and the test file for it must seed all three worlds.

**Detailed solution:** Mechanical three-way split along the existing line groupings: `LspDirectoryService`, `UserAdminService` (takes the audit snapshot/serialize helpers with it), `BorrowerDirectoryService` (takes `computeDelinquencyAggregate`, which should also reuse the canonical DPD logic from `LoanApplicationService.calculateDaysPastDue` rather than its own — verify while moving). Controllers already call disjoint method subsets, so call-site changes are import-level. No behavior change.

---

## <a id="b10"></a>B10 — `AuthController` contains the token service · P2

**Problem (plain English):** Token minting, refresh-token generation/hashing/storage, cookie construction, role loading, and managed-user state — the entire auth engine — lives inside the controller class instead of a service.

**Evidence:** `backend/.../web/AuthController.java` (633 lines): `mintTokenForAppUser/ForApiClient/mintTokenResponse` ×3 overloads (326–407), `issuePasswordToken`/`issueClientCredentialsToken` (408–489), refresh-token generation + `sha256Hex` crypto (531–582), cookie building (555–572), claims loading (591–613). The `refresh` endpoint method spans lines 130–217 (~88 lines).

**Why:** Controllers are the HTTP adapter layer; the brute-force lockout work (#155, just merged) and the JWT-rotation Phase-4 item both have to modify token issuance, and today that means editing a controller with eight private helper families. Crypto in controllers also evades the service-level test seams.

**Detailed solution:** Extract `AuthTokenService` (mint/refresh/revoke families, refresh-token store interaction, sha256, claims) and `RefreshCookieFactory` (cookie shape). Controller keeps request validation + HTTP mapping, ends near ~200 lines. Decompose `refresh()` into named steps (`resolveRefreshToken`, `rotateAndMint`, `failureResponse`). Pure move — lock in with the existing `AuthControllerTest` before/after.

---

## <a id="b11"></a>B11 — Partner API reuses internal ops DTO classes · P1 · ✅ Done (2026-06-11)

**Session outcome:** `LspDocumentChecklistDetailResponse` + `LspRepaymentScheduleInstallmentResponse`; mappers in `LspLoanApplicationResponses`; `LspLoanApiController` schedule list repointed.

**Problem (plain English):** The external LSP-facing API returns response records *declared inside the internal ops controller*. Changing an internal ops response silently changes the partner contract.

**Evidence:** `LspLoanApplicationApiController.java:280, 301, 323, 362` — return types `LoanApplicationOpsController.LoanApplicationDocumentChecklistResponse` and `LoanApplicationOpsController.LoanRepaymentScheduleInstallmentResponse`. Meanwhile the same file *does* define its own `LspLoanDelinquencySummaryResponse`/`LspLoanRepaymentScheduleSummaryResponse` (659–673) that are field-identical to the ops versions — i.e., the codebase already believes the contracts should be separate, but applied it inconsistently.

**Why:** The internal ops API can evolve freely; the partner API is a versioned external contract (LSPs integrate against it; ADR 0003 makes it the *only* origination path). Sharing classes couples their change cadence in the dangerous direction. The checklist response is the worst case: it exposes internal fields (`storageKey`, `fileChecksum`, `lmsManagedContent` — `LoanApplicationOpsController.java:719-741`) to external partners.

**Detailed solution:** Declare `LspDocumentChecklistDetailResponse` and `LspRepaymentScheduleInstallmentResponse` in the LSP controller (or a `web/lsp/dto` package) with exactly the fields partners need — almost certainly *excluding* `storageKey`/`fileChecksum`. Map in `LspLoanApplicationResponses`. Wire-compatible if the field set is kept identical initially; then prune internal fields as a deliberate, versioned change. Add an ArchUnit rule: nothing in the LSP API surface may reference `LoanApplicationOpsController.*` types.

---

## <a id="b12"></a>B12 — Inline tenant-context escalation in controller · P2 · ✅ Done (2026-06-11)

**Session outcome:** `TenantDataAccessContextHolder.runAsAdmin(Supplier)` delegates to existing `TenantScopedExecution`; `doCreateApplication` uses it. Escalation moves into `BorrowerOnboardingService` when B3 lands.

**Problem (plain English):** The LSP create-application endpoint manually snapshots the tenant data-access context, escalates to admin, and restores it in a `finally` — security-critical ceremony written by hand at one call site.

**Evidence (resolved):** was `LspLoanApplicationApiController.java:195-243` manual `snapshot()/useAdmin()/restore()`; now `doCreateApplication` wraps `loanApplicationLifecycleService.createApplication` in `TenantDataAccessContextHolder.runAsAdmin(() -> …)` (~lines 214-258).

**Why:** The next endpoint that needs cross-tenant reads (borrower dedupe requires it — that's why this one does) will copy this block; the first copy that forgets `finally { restore }` leaks admin scope into the request thread pool. This is precisely the class of bug behind the #89 production 401 regression (tenant filter/context ordering). Note: a new `security/AdminTenantDataAccessFilter.java` is sitting untracked in the working tree alongside `SecurityConfig` edits — this finding may already be mid-fix; align rather than duplicate.

**Detailed solution:** Move the escalation into the service operation that *requires* it (`BorrowerOnboardingService` from B3 — borrower resolution is the part that must see across tenants), or provide `TenantDataAccessContextHolder.runAsAdmin(Supplier<T>)` so the snapshot/restore pairing is unforgeable. Controllers should never touch the tenant holder. Add a test that asserts the context is restored even when the service throws.

---

## <a id="b13"></a>B13 — Canonical helpers copy-pasted across the service layer · P2

**Problem (plain English):** The same four-line private statics are pasted everywhere instead of living in one utility.

**Evidence (grep-verified counts):** `normalizeOptional` ×8 (`LspLoanApplicationApiController`, `LoanApplicationDocumentChecklist`, `Borrower`, `AdminDirectoryService`, `LoanApplicationQueryService`, `LoanApplicationLifecycleService`, `LoanApplicationService`, `LoanServicingSupportService`); `scaleCurrency` ×6; `normalizeActorUsername` ×2+; `requireCurrency` ×2.

**Why:** Mostly identical today, but `scaleCurrency` is money-handling — six copies of a rounding rule is how rounding bugs are born (see B1 for the proof this codebase already diverges duplicated math).

**Detailed solution:** `common/util/Strings.normalizeOptional/normalizeActor` and `common/money/Money.scale/requirePositive` (or a `Money` wrapper if appetite exists — the minimal version is two static utility classes). Replace the 18 copies. Pure mechanical PR, zero behavior change, big drift insurance.

---

## <a id="b14"></a>B14 — Dead parameters, misleading signatures · P3 · ✅ Done (2026-06-11)

**Evidence:**
- `LoanApplicationOpsController.authorizeStatusTransition(actorRoles, currentStatus, targetStatus)` (`:333-342`) ignores `currentStatus` and `targetStatus`; body is "is SYSTEM_ADMIN". Replace with `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` on the endpoint and delete the method + `extractRoles` (whose `ROLE_PASSWORD_CHANGE_REQUIRED` filtering is an auth concern leaked into a loan controller).
- `hasAllRequiredLmsManagedDocuments(UUID, boolean requireForApprovalOnly)` — parameter documented as dead (`LoanApplicationLifecycleService.java:494-506`), threaded through the facade too (`LoanApplicationService.java:1100`). Delete the parameter.
- `resolveDocumentActivityDetail(item)` (`LoanApplicationService.java:1096-1098`) is an identity wrapper for `item.getNote()` — inline it.

**Why/effect:** Each one lies to the reader about what the code considers. Five-minute fixes; fold into adjacent PRs.

---

## <a id="b15"></a>B15 — Business date depends on server timezone · P2

**Problem (plain English):** "Today" for delinquency math is the JVM's default timezone.

**Evidence:** `LoanApplicationService.currentBusinessDate()` (`:1070-1072`) = `LocalDate.now(ZoneId.systemDefault())`; feeds `calculateDaysPastDue` and the DPD buckets (`:1022-1088`). Schedule first-due-date uses UTC (`LoanRepaymentScheduleService.java:135`) — a second, different convention.

**Why:** DPD is a regulatory number for an India-domiciled portfolio. A server in UTC flips bucket transitions 5.5 hours late; a misconfigured container flips them arbitrarily. Two conventions in one money path is one too many.

**Detailed solution:** Define `BusinessCalendar` (or a `Clock` bean fixed to `Asia/Kolkata`) injected wherever `currentBusinessDate`/`LocalDate.now` appears in domain code; grep shows the static helper is the main entry. Tests get a fixed clock, which also de-flakes the DPD tests around midnight. Coordinate with the alert-rule evaluator (it computes its own `Instant.now()` windows).

---

# Frontend

## <a id="f1"></a>F1 — Fictional 23-status state machine, falsely documented as shared with the backend · **P0**

**Problem (plain English):** The frontend defines its own loan lifecycle with 23 statuses and a 40+-rule transition table, while the backend's real enum has 10. Thirteen statuses (`INITIATED`, `KYC_PENDING`, `DOCS_PENDING`, `UNDER_REVIEW`, `APPROVED`, `DISBURSEMENT_IN_PROGRESS`, `PARTIALLY_PAID`, `DELINQUENT`, `FORECLOSURE_REQUESTED`, `FORECLOSURE_APPROVED`, `FULLY_REPAID`, `CANCELLED`, `INVALIDATED`) can never be emitted by the backend. The module's own docs claim the opposite of reality.

**Evidence:**
- `frontend/src/lib/lifecycle.ts:1-19` claims `canTransition()` is "consulted by … the backend (enforces server-side validation)" and `:511-514` claims "UI gating + server enforcement are byte-identical." The backend is Java (`LoanApplicationStatusTransitioner`); nothing is shared. `components/app/lifecycle/actions.ts:4-6` repeats the false claim.
- Backend enum: 10 values (`backend/.../domain/LoanApplicationStatus.java:15-24`). Frontend `STATUS_META`: 23 (`lifecycle.ts:46-117`). The `TRANSITIONS` table (`:275-501`) is built almost entirely on the 13 fictional ones.
- Live consumers of the fiction: `LoanApplicationsFilterBar.tsx:28-33` builds the status filter from **all 23** `STATUS_META` keys — the ops UI offers 13 filter options that match nothing, and because `mapFrontendStatusToBackend` folds several onto one backend value (e.g. `APPROVED` → `APPROVED_PENDING_DISBURSAL`, `INVALIDATED` → `INVALID`), users see *duplicate filters with different labels returning identical result sets*. `StatusBadge`, `ApplicationsByStatusCard`, and the lifecycle `ActionBar`/`gates` also consume it.
- The translation layer this forces: `mapBackendStatus`/`mapFrontendStatusToBackend` (`features/loan-applications/api.ts:42-90`) imported by 6 files; `mapBackendStatus` silently folds any *unknown* backend status to `"INITIALIZED"` (default branch) — real status drift would render as "Initialized" instead of failing loudly.
- The code already knows: the mapper's own comment calls the 10 "canonical" and says the legacy branches "can be removed once the DB is fully migrated forward."

**Why:** Two sources of truth for the most important domain concept in the product, with the *false* one wired into filters, badges, dashboards, and action gating. Every UI feature pays the translation tax, and the gating table can disagree with the backend transitioner in both directions: buttons that 400 when clicked, and legal transitions the UI never offers.

**Effect on codebase & app:** User-visible today (phantom/duplicate filter options); silently corrupting tomorrow (unknown status → "Initialized"). It also inflates every api file (see F2) because each must map statuses at the boundary in both directions.

**Detailed solution (code judo — delete the second state machine):**
1. Redefine `LoanStatus` as the canonical 10 backend values. Shrink `STATUS_META` to 10 entries (labels/intents/groups survive — they're display metadata, which *is* the frontend's legitimate job).
2. Delete the 13 fictional statuses, the transition rules referencing them, and `INVALIDATABLE_FROM`/`CANCELLABLE_FROM` loops (`lifecycle.ts:256-273, 482-501`). What remains of `TRANSITIONS` is the ~10-rule table mirroring the backend transitioner (ops transitions, manual override targets, invalidation) — small enough to keep in sync by eye, but add a contract test anyway: expose the backend's allowed-transition matrix (a tiny `GET /api/v1/internal/ops/meta/transitions` or a generated JSON committed by the backend build) and assert the frontend table equals it.
3. Delete `mapBackendStatus`/`mapFrontendStatusToBackend` (`api.ts:42-90`) and all 6 import sites; statuses pass through untranslated. Replace the silent `default: return "INITIALIZED"` philosophy with a loud unknown-status badge ("Unknown (RAW_VALUE)") so drift is visible.
4. Fix the docstrings: the gate is *advisory UX*; the backend is the enforcer. Delete the "byte-identical" claims.
5. Filter bar and `ApplicationsByStatusCard` shrink to the 10 real options automatically.
6. Sequencing: do this *before* B4's status-code sweep lands UI-side error handling changes, so the UI work happens once.

---

## <a id="f2"></a>F2 — Hand-rolled per-feature backend types and mappers · P1

**Problem (plain English):** Every feature folder re-declares the backend's response shapes by hand and writes its own mapping/coercion layer — ~3,560 lines of `api*.ts` across 14 feature folders, with near-duplicate interfaces and the same primitive coercers re-implemented.

**Evidence:**
- `features/loan-applications/api-detail.ts` (487 lines) hand-declares `BackendLoanAccountSummary`/`BackendLoanApplicationDetail` (`:46-103`); `features/my-loans/api.ts` (455 lines) declares the field-identical `MyLoanLoanAccountSummary` (`:18-40`) and its own `BackendLspDetail`. Both define their own `toNumber`-style coercion; every backend numeric arrives typed `number | string | null` and is defensively re-parsed per feature.
- 14 `api*.ts` files totaling 3,563 lines (measured), the four largest being loan-applications (487+307+172) and my-loans (455).
- The backend serializes most numbers as JSON numbers via Jackson `BigDecimal` — the `number | string | null` unions are defensive guesses, the same "untrusted envelope" pattern as `readResponseError` (B4 item 5).

**Why:** The backend response records (`LoanApplicationOpsController.java:447-782` and friends) are *already* the precise schema, maintained by the compiler. Re-typing them by hand in TypeScript guarantees drift in the only direction that can't be caught at build time, and the per-feature duplication means the loan-account summary shape now lives in at least four places across the stack.

**Effect on codebase & app:** Every backend response change requires a human to notice and mirror it per feature; misses surface as `undefined` rendering or silently-zeroed money (`toNumber` returns 0 on parse failure — money displayed as ₹0 is worse than crashing).

**Detailed solution:**
1. Add `springdoc-openapi` to the backend (one dependency; records + Bean Validation annotations make the generated spec accurate) and generate TS types in the frontend build (`openapi-typescript`, types-only, no runtime client — keep `requestJson`).
2. Feature api files keep their thin functions (`requestJson<paths["/api/v1/..."]["get"]["responses"]["200"]>`), delete their hand-declared `Backend*` interfaces and coercion unions. Money fields become `number` per the spec; if a field genuinely serializes as a string, the spec says so and one shared `money.ts` coercer handles it — not 14 copies.
3. Where richer runtime validation is wanted, the existing `src/schemas/*` zod layer is the place — derive from generated types (`z.ZodType<generated.X>`) so zod and the spec can't diverge.
4. Migrate one feature (loan-applications detail) first to prove the loop; expected reduction ~40% of the api-layer line count and a CI-enforced contract with the backend.

---

## <a id="f3"></a>F3 — `requestBlob` lacks the 401-refresh retry; duplicated transport code · P1 · ✅ Done (2026-06-11)

**Session outcome:** `performFetch` + `throwIfNotOk` shared core; test in `http-client.test.ts`.

**Problem (plain English):** JSON requests transparently refresh an expired token and retry; file downloads don't. A user idle past token expiry can browse (JSON refreshes) but every document download fails with 401 until something else triggers a refresh.

**Evidence:** `frontend/src/lib/api/http-client.ts` — `performJsonRequest` has the refresh-retry (`:166-171`); `requestBlob` (`:192-225`) duplicates URL building, auth header, error parsing, but has **no** 401 branch.

**Detailed solution:** Extract the shared core (`performFetch`: build URL/headers, fetch, 401-refresh-retry, error parsing) and make `requestJson`/`requestBlob` thin wrappers differing only in body handling. Deletes ~30 duplicated lines and fixes the behavioral gap in one move. Add a unit test: 401-then-success for blob path. (Also fold in B4 item 5: collapse `readResponseError`'s five-key guess chains to the single real envelope.)

---

## <a id="f4"></a>F4 — `my-loans/detail-page.tsx`: four components in one 741-line file · P2

**Evidence:** `frontend/src/features/my-loans/detail-page.tsx` — `MarkInvalidDialog` (89–225), `MaskedBorrowerCard` (226–258), `DocumentRow`/`DocumentsSection` (297–500), `MyLoanDetailPage` (501–740).

**Why:** The page is internally well-decomposed — the components just live in one file, pushing it toward the 1k-line ceiling and making the dialog/documents sections untestable in isolation by path convention (sibling features keep components in `components/`).

**Detailed solution:** Mechanical extraction to `features/my-loans/components/{MarkInvalidDialog,MaskedBorrowerCard,DocumentsSection}.tsx`, matching the convention every other feature already follows (`features/users/components/`, `features/lsps/components/`). Page file lands ~280 lines. Zero behavior change.

---

## <a id="f5"></a>F5 — `DataTable.tsx` internal render duplication · P3 · ⏭️ No action (clone absent in current tree)

**Evidence:** fallow clone group (validated still present): `src/components/app/data/DataTable.tsx` lines ~285–298 — the cell-rendering block (`meta.numeric` → `TABULAR_ATTR` → `TableCell` className composition) appears twice (regular vs. some variant rows).

**Detailed solution:** Extract `renderCells(row, cellPad)` (or a `<DataTableCells>` helper) used by both branches. Small, but this is the shared table used by every list screen — duplication here multiplies into every visual tweak.

---

# Cross-cutting

## <a id="c1"></a>C1 — Repo root littered with one-off artifacts · P2

**Problem (plain English):** The repository root contains dozens of scratch outputs: 10 `fallow-*.json`, 6 `*issues*.json/txt`, `__pycache__/`, `tmp-login.json`, `blank.pdf`, 6 `*.log` files, `.issue-list-temp.json`, `e2e-test-matrix.xlsx`, plus ~20 `postman/_mcp_*`/`postman/_collection_only.json` build intermediates (verified via `git status`).

**Why:** Root files are the first thing every human and agent reads to orient; 40+ scratch artifacts bury the dozen that matter (`README`, trackers, `pom.xml`, `docs/`). Several are stale enough to mislead (C2). A `scratch/` directory already exists for exactly this.

**Detailed solution:** Delete the stale report JSONs and temp files; move anything still wanted into `scratch/` or `test-artifacts/`; add `.gitignore` entries (`*.log`, `__pycache__/`, `fallow-*.json`, `tmp-*`, `postman/_*`). One housekeeping PR. Keep: the three tracker `.md`s, audit docs, `e2e-test-matrix.xlsx` if it's a deliverable (move under `docs/` if so).

## <a id="c2"></a>C2 — Stale machine reports steer future work wrong · P2

**Problem:** As validated above, `graphify-out/` predates the mocks/`frontend-2` removal (stale god node, impossible cross-language edges, 55 dead paths in fallow health). The project CLAUDE.md instructs agents to consult the graph *before* answering architecture questions — currently that means consulting fiction first.

**Detailed solution:** Re-run `graphify update .` and the fallow suite against the current tree; commit regenerated outputs (or stop committing them and generate on demand). Add a one-line caveat to CLAUDE.md: INFERRED cross-language edges are untrusted. Until regenerated, prefer direct code reads.

---

# Suggested sequencing

1. **Week 1 (correctness):** B1 (one schedule generator) → B4 sweep started (lifecycle + application services first) → F3 (blob refresh).
2. **Week 2–3 (structure that unblocks everything else):** B7 step 1 (alert emitter split) → B3 (lifecycle decomposition, with B5's `BorrowerProfile` and B13's utilities riding along) → B6 (detail assembler) → B2 (delete the facade last, when it's nearly empty).
3. **Week 3–4 (contracts):** B11 (partner DTOs) + F1 (collapse the status machine) + F2 pilot (OpenAPI types for one feature).
4. **Anytime fillers:** B8, B9, B10, B12, B14, B15, F4, F5, C1, C2.

Most items are behavior-preserving refactors; the exceptions that change observable behavior are B4 (HTTP status codes — coordinate with Postman/E2E and partners), B11 (partner response field pruning — staged), and F1 (filter options shrink to real statuses — strictly a fix).
