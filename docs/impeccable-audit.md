# Impeccable audit — Bhawana LMS

**Audit date** 2026-08-04 · **Remediation closed** 2026-08-05
**Environment** local dev (`localhost:5173` / `localhost:8080`, Supabase dev DB)
**Method** Impeccable `audit` + `critique` reference playbooks, bundled `detect.mjs`, live
browser inspection via Chrome DevTools, backend/API state seeding, and independent research
benchmarking.
**Evidence log** [`impeccable-audit-findings.md`](./impeccable-audit-findings.md) — per-finding
location, role, repro, observed behaviour and root cause for F-01…F-33.

**Business framing.** B2B2C lending — LSP tenants originate over the API, this platform
computes schedules, interest, disbursement, collection and DPD. The end borrower is a
consumer, so computed-financial legibility is treated as safety-critical, not cosmetic.

> **Status: closed.** Three remediation phases. Everything actionable is either fixed,
> decided, or listed in §10 with a reason. Section 9 records what a live browser pass proved
> against real data — including one money-truncation defect the test suite could not have caught.

---

## 1. Scores at audit time

### Impeccable audit health score

| # | Dimension | Score | Key finding |
|---|-----------|-------|-------------|
| 1 | Accessibility | 3/4 | Strong foundations undercut by three real gaps: density-toggle state at ~1.07:1 (F-05), no `h1` on permission-denied (F-26), form errors not announced (F-22) |
| 2 | Performance | 3/4 | No overflow, bounded table scroller, lazy routes. Two data-fetch idioms coexisted |
| 3 | Theming | 4/4 | Exceptional. Derived-not-eyeballed tokens, build-enforced contrast, first-class dark mode |
| 4 | Responsive design | 2/4 | Two independent definitions of "mobile" (F-25); worst case, zero navigation |
| 5 | Implementation integrity | 4/4 | `detect.mjs` found **0 genuine issues** across `frontend/src` |
| | **Total** | **16/20** | **Good** |

### Nielsen heuristics

| # | Heuristic | Score | Key issue |
|---|-----------|-------|-----------|
| 1 | Visibility of system status | 3 | Bare em-dash KPI (F-11), bare spinner on LSP detail (F-20) |
| 2 | Match system / real world | 3 | Raw enums leaked to partners (F-14, F-19) |
| 3 | User control and freedom | 3 | Forced `/change-password` had no exit (F-21) |
| 4 | Consistency and standards | 2 | Two row-action patterns, two schedule column sets, three temporal formats, two breakpoints |
| 5 | **Error prevention** | **1** | Illegal "Mark invalid" (F-16); unconfirmed "Disable" (F-30); silently discarded filters (F-01) |
| 6 | Recognition rather than recall | 3 | Status dropdown dropped badge vocabulary (F-07); breadcrumb said "Detail" (F-08) |
| 7 | Flexibility and efficiency | 2 | No bulk selection anywhere; LSP surface had no search (F-17) |
| 8 | Aesthetic and minimalist design | 2 | Dead foreclosure panel over ~55% of viewport (F-04) |
| 9 | Error recovery | 4 | Genuinely strong |
| 10 | Help and documentation | 2 | No contextual help, no explanation of computed figures |
| | **Total** | **25/40** | **Acceptable** |

**Cognitive load:** 4 of 8 failed — visual hierarchy, progressive disclosure, minimal choices
(`/audit` presented 9 stream toggles + 5 controls at one decision point), single focus.

**Design specificity verdict: authored, not category-interchangeable.** The instrument-panel
thesis is real and visible. *Where it slipped was not taste but discipline* — the product
states rules that the implementation then broke in specific, fixable places. That framing
drove the whole remediation.

---

## 2. What was inspected

**Roles** SYSTEM_ADMIN+OPS_USER (`ops.admin`), LSP_UI_WRITE (`audit.lspwrite`). Users also
provisioned for OPS_USER-only, PRODUCT_ADMIN, LSP_UI_READ.

**Routes (14/14 + 404)** `/login` · `/change-password` (forced) · `/home` ·
`/loan-applications` · `/loan-applications/:id` · `/borrowers` · `/alerts` · `/reports` ·
`/lsps` · `/products` · `/users` · `/api-clients` · `/audit` · `/my-loans` · `/my-loans/:id`.

**Detail tabs (6/6)** Overview · Schedule · Documents · Repayments · Activity · Webhooks.

**States** default · skeleton · spinner · empty · permission-denied (3 routes) · form
validation error · server-rejected action · in-flight submit · 404 · applied-filter ·
invalid-filter · dark theme · 768px + drawer.

**Lifecycle states with live data** INITIALIZED 15 · AWAITING_APPROVAL 21 · REJECTED 111 ·
INVALID 37 · DISBURSED 23 · UNDER_REPAYMENT 195 · CLOSED 579 · FORECLOSED 23.

**Modals opened live — 17 of 26.** `DocumentPreviewModal` reviewed at source level.

### Coverage gaps at audit time — stated honestly

| Not covered | Why |
|---|---|
| `APPROVED_PENDING_DISBURSAL` and `DISBURSEMENT_RETRY` live screens | 0 rows. `manual-status` refuses the former; the standard flow needs 8 uploaded KYC docs. The latter is transient — the worker resolved seeded rows within minutes. **Consequence: the most money-critical UI in the product was not exercised live.** |
| 9 of 26 dialogs | The first five need lifecycle states that could not be created. |
| `/borrowers/:id`, `/dev/components` | Not reached |
| Viewports 1280, 1024, 390 | Only 1440 and 768 measured |
| Keyboard-only traversal, screen-reader pass, `prefers-reduced-motion` | Spot checks only |

Two of these were closed later: 1280 and 1366 were measured in the phase-3 live pass (§9) and
immediately produced F-36. The disbursement gap remains open (§10).

### Recorded as *not* defects

Seed `Activity` duplicates come from the API returning identical rows · `DISBURSEMENT_RETRY`
transience is worker behaviour · early browser-call timeouts were chrome-devtools MCP request
serialisation across concurrent agents, not application performance · synthetic seed data has
future-dated installments marked Paid.

---

## 3. Remediation phases

| Phase | Scope | Outcome |
|---|---|---|
| **1** | Group 1 — immediate usability defects | F-16, F-01, F-02, F-03, F-17, F-30, plus F-23 and F-33. Closed 6 of 6. |
| **2** | Groups 2–5 — consistency, accessibility, workflow, polish | F-25, F-18, F-13, F-07/14/19, F-27, F-28, F-24, F-05, F-26, F-22, F-21, F-31, F-32, F-04, F-12, F-08, 4.5, F-06, F-09/20, F-10, F-11, F-15. |
| **3** | Everything left | Deferred decisions, items never claimed, defects the earlier fixes introduced, defects in the tests, two new findings, and a live browser pass that produced a third. |

**Phase 3 found that the phase-2 checklist overstated several items** — it recorded what was
attempted rather than what was verified. Every claim was re-checked against the tree with
file:line evidence before new work started (§5).

---

## 4. Decisions taken

These are decisions, not open findings. They do not need revisiting unless the underlying
position changes.

| ID | Decision | Rationale |
|----|----------|-----------|
| **F-29** | **Keep PAN and Aadhaar visible** on the `/borrowers` list | Ratifies the provisional call. The asymmetry with `/loan-applications` (which masks borrower names) and the borrower detail page (which gates Aadhaar behind a PII-reveal audit stream) is now a **deliberate posture, not an oversight**. Revisit if the compliance position changes. |
| **4.4** | **Bulk selection / batch actions deferred** | The largest net-new feature in the audit — selection model, per-action permission gating, partial-failure reporting, an undo story. Its own piece of work, not an audit close-out. Heuristic 7 stays at 2/4 with a known cause. |
| **Heuristic 10** | **Explain computed figures only**; no keyboard-shortcut surface | The B2B2C legibility thesis is about figures an agent must defend to a borrower. A shortcut sheet serves power users — a different and lower need today. |
| **Avg approval TAT** | **Removed end to end** — tile, frontend contract, OpenAPI property, DTO, entity mapping, the `computeAvgApprovalTatHours` query, and the `avg_approval_tat_hours` column (migration `V114`) | Not needed. Removing it also collapsed the home KPI strip back to a single four-up row, which resolved F-36 (§9). Dropping the column discards its historical values; confirmed before applying. |
| **F-02 waiver semantics** | Label **"Not required"**, not "Waived"; counts as satisfied for completeness | "Waived" implies an approval action with an actor and timestamp, none of which the system records — the copy would claim more than the data supports. A document that does not apply to a loan cannot gate that loan's disbursement. |

---

## 5. Claims that did not survive verification

Re-checked in phase 3 against the working tree. Most held; these did not.

| Claim | Reality |
|---|---|
| F-26 "every route exposes exactly one `h1`" | `/my-loans/:id` not-found rendered **two** — `PageHeader` plus `PermissionDeniedState`. **The F-26 fix introduced it.** Two 403 states had **zero**. |
| F-22 "all forms announce and focus" | `EscalateToAdminDialog` is a raw `useState` form that never routed through `FormShell`, so it did neither. |
| F-31 "required markers added" | True for the six dialogs named; still inconsistent across the ~11 other forms. |
| F-13 "one datetime component, everywhere" | True at the repro location. Eight other surfaces still rendered relative-only, several with no path to the absolute value at all. |
| F-07/14/19 "enums routed through label maps" | True where the audit looked. The **partner-facing** `/my-loans/:id` still leaked `LoanAccountStatus`, `ClosureReason` and disbursement status raw. |
| F-12 "alerts have information scent" | Cosmetic. The card humanised the *title* but carried no borrower, amount or DPD — the data existed in `OpsAlert.message` and was never added to the summary DTO. |
| F-27, F-25, F-18, F-04, F-08, F-28, 4.5 | **Held up**, verified end-to-end including through the backend. |

The `/audit` cognitive-load failure was never claimed as fixed, and was still true.

**Lesson worth keeping:** a fix verified only at its reported repro location tends to be a
fix only at that location. F-13, F-31 and F-07/14/19 all failed the same way.

---

## 6. New findings

Not in the original audit; all three are the same shape as the defects it named — a rule the
product states, then does not enforce.

### F-34 — every popover panel was an unnamed `role="dialog"`

Radix renders `PopoverContent` with `role="dialog"`, which requires its own accessible name;
labelling a listbox or group *inside* it does not satisfy `aria-dialog-name`. All five call
sites did exactly that.

**Why ~90 axe assertions missed it:** they scan the render `container`, and portalled content
renders outside it. `axe(container)` is structurally blind to every popover, dialog and
tooltip in the suite.

**Fix:** enforced in the type, not by patching call sites — `PopoverContent` now *requires*
`aria-label` or `aria-labelledby`, so the next popover cannot ship unnamed.

### F-35 — the home DPD chart contradicted the backend's bucket boundaries

The chart labelled its buckets `0-30 / 30-60 / 60-90`; the backend
(`LoanDelinquencySupport.resolveDelinquencyBucket`) buckets on 1–30, 31–60, 61–90, 90+. Every
boundary day appeared to fall in two buckets at once, on a delinquency chart, in a lending
product.

**Root cause** is the audit's own F-07/14/19 pattern: three separate label maps for one
vocabulary, and the chart's had drifted. **Fix:** one source of truth in
`lib/delinquency-display.ts`, carrying both a full and a compact label so an axis cannot fork
its own copy again.

### F-36 — money silently truncated on the home KPI row

Found in the live pass; see §9.

---

## 7. What shipped

| Area | Outcome |
|---|---|
| **F-34** | `PopoverContent` requires an accessible name in its type; all five call sites named. |
| **F-35** | One DPD vocabulary; badge and chart both consume it; boundaries match the backend. |
| **Heuristic 10** | New `MetricHint` — an info control beside a derived figure opening a plain-language definition. **Deliberately a popover, not a tooltip:** Radix tooltips never open on touch and the partner surface is used on phones. Wired to the three schedule totals and the home money KPIs, with copy checked against the actual derivation (`computeScheduleTotals`, `PortfolioKpiSnapshotComputationService`, `LoanDelinquencySupport`) rather than inferred from the label. `ForeclosureSummaryCard` deliberately untouched — it already itemises principal + interest + charges = payoff, so the breakdown *is* the explanation. |
| **F-26** | `/my-loans/:id` double-`h1` fixed; both 403 states now expose exactly one. |
| **F-22** | `EscalateToAdminDialog` migrated to `FormShell` — announces via live region *and* moves focus to the first invalid control. |
| **F-31** | Sweep completed across every remaining form. `TransitionConfirmDialog` marks `reason` **conditionally** (`required={requiresReason}`) — the same field is mandatory for a rejection and optional for an approval, so a hardcoded marker would be wrong half the time. |
| **F-13** | `AbsoluteRelativeTime` gains a `relative` variant — visible relative reading, absolute carried on `dateTime` + `title`. Policy: inline absolute · relative on evidence surfaces (ledgers, activity, audit log); relative-with-absolute-carried in dense table columns; **never relative with no path to the instant**. |
| **F-07/14/19** | `/my-loans/:id` no longer leaks `LoanAccountStatus`, `ClosureReason` or disbursement status; unmapped values degrade to `Unknown (raw)`, never to the backend's spelling. |
| **F-12** | `OpenAlertSummary` carries `message` (blank→null on the backend, so the card omits the line rather than rendering an empty one). Backend + OpenAPI + generated types regenerated. |
| **`/audit`** | Ten-button strip → one shared single-select. Also ends the ARIA lie: it was `role="tablist"`/`role="tab"` with no tabpanel, no `aria-controls` and no arrow-key traversal. |
| **Motion** | `useDelayedFlag` (300ms, opt-in at page-level gates only — deliberately *not* inside the shared skeletons, which would have forced fake timers on every future test); filter-chip entrance; tab-panel fade with `key` so the entrance actually replays. All off under `prefers-reduced-motion`. |
| **Data-fetch idiom** | `useLspOptions` / `useProductOptions` shared TanStack queries replace three hand-rolled `useEffect` fetches of the same list. |
| **`totalDisbursedMtd`** | Renamed to `totalDisbursed` — it held a lifetime figure, and the stale name was a live MTD/lifetime bug waiting to happen. |

---

## 8. Test-suite defects

The suite was healthy in the ways that usually rot — **zero** `.skip`/`.only`/`.todo`
anywhere, every `axe()` correctly awaited against real rendered output. The defects were
narrower:

- **Assertions that cannot fail.** `expect(baseElement).toBeTruthy()` (`baseElement` is
  `document.body`); a "no horizontal overflow" check scanning for inline `style="width:NNNNpx"`
  in a codebase that styles exclusively with Tailwind classes, under jsdom, which has no
  layout engine.
- **Fixture default-status coupling** — `makeMyLoanDetail()` defaults to `UNDER_REPAYMENT`
  and five tests depend on that default invisibly.
- **Wall-clock flakiness** — a real `setTimeout(25)` racing RTL's polling window.
- **Coverage gaps** on modules the remediation itself added.

Two traps worth recording, because both caught agents mid-flight:

1. Adding `<FormItem required>` changes the label's `textContent` to include the `*`, breaking
   `getByLabelText("Exact String")`. The house fix is an anchored regex.
2. Once a form routes through `FormShell`, each validation message renders **twice** — inline
   and in the live-region summary — so a bare `getByText` throws "found multiple elements".
   The right repair is to assert through the control's `aria-describedby`, which pins the
   WCAG 3.3.1 requirement instead of merely finding the string somewhere on the page.

---

## 9. Live browser pass

Run against the real stack — Vite on `:5173`, Spring Boot on `:8080`, the Supabase dev DB,
signed in as `ops.admin` (SYSTEM_ADMIN + OPS_USER). Rate limiting was disabled for the run via
an env override because Docker/Redis was not up; nothing else was changed.

**Verified against real data:** F-35 chart boundaries · F-34 popover names on two separate
popovers · heuristic-10 hints opening with the right copy · F-12 alerts carrying loan ref, DPD
and overdue amount · F-13 across tables, the audit log and the snapshot line · F-08
`document.title` and breadcrumb identity with exactly one `h1` · F-02 past-tense document copy
on a servicing loan · F-04 foreclosure collapsed to a 68px disclosure · F-03 no contradictory
"no actions" line · F-30 disable confirmation naming the user with focus defaulting to Cancel ·
F-32 "Close dialog" · F-05 density toggle on three channels (border ≈3.7:1 in dark) · 4.5
schedule totals. Console clean apart from a known React Router v7 future-flag warning.

**Arithmetic checked against real rows, not just rendered:** on an `UNDER_REPAYMENT` loan with
today = 05 Aug and the earliest due date 14 Aug, "Outstanding as of today" correctly read
₹0.00, and "Total outstanding" ₹82,726.28 reconciled exactly to the six unpaid installments.

### F-36 — money silently truncated on the home KPI row

`₹12,30,00,000` needs ~168px; at `xl` the five-up grid gave each tile ~142px, and the tile is
`overflow: hidden`. **The last digits of a crore figure were being silently cut off at 1280 and
1366** — the two commonest laptop widths, and both listed as *uncovered* by the original audit,
which measured only 1440 and 768.

**Resolved by removing the fifth tile** (Avg approval TAT, §4). With four tiles the strip sits
on `KpiStrip`'s default four-up grid and stays a single row from `xl` up, with room to spare.
Verified clip-free and single-row at 1280/1366/1440/1600/1920.

Recorded for the future: had the tile stayed, the answer was to hold five-up back to `2xl` and
let the row wrap 3+2. Font-stepping alone would not have survived a longer figure, and a
wrapped row costs less than a money figure that quietly loses digits.

Two smaller live findings, both fixed: the metric-hint panel sat flush against the viewport
edge in the last KPI column (`collisionPadding`), and the audit log's "When" column was
relative-only with a raw ISO string in its `title` — now a real absolute · relative reading,
because on an audit log the instant *is* the evidence.

**Not a defect, worth recording:** `/home` showed ₹12,30,00,000 and `/reports` ₹12,69,00,000.
Same metric definition; `/home` reads a precomputed snapshot that was 12 minutes stale. This is
exactly what the "Portfolio snapshot as of…" provenance line exists to disclose, and it did.

---

## 10. Still open

> **Superseded by phase 4 (2026-08-06 → 2026-08-10).** Every item below has since been closed —
> see [`impeccable-audit-phase4.md`](./impeccable-audit-phase4.md), which also records five further
> defects the work uncovered, including the reason the disbursement UI had never been reachable and
> a shared-dialog defect that hid the confirm button on the money-critical dialog. The list is kept
> here as written for the record.

- **The money-critical disbursement UI has never been exercised live.** `DisbursementInitiateDialog`,
  `DisbursementGateBanner` and the disbursement preview need an `APPROVED_PENDING_DISBURSAL`
  loan, which requires 8 uploaded KYC documents to reach. This was the original audit's largest
  coverage gap and it is unchanged.
- **Two `useEffect` data fetches remain** — `my-loans/DocumentsSection` and
  `TransitionConfirmDialog`'s disbursement preview. The dialog is money-critical UI, which is
  both why converting it is worth doing and why it deserves its own change.
- **`my-loans` fixture default-status coupling** — still not made explicit at the call sites.
- **Single-role live pass.** `/my-loans` was not re-verified as `audit.lspwrite`, so the
  partner-facing enum fixes are covered by tests but not by a browser.
- **Viewports 1024 and 390 unmeasured.** 1280 and 1366 are now covered — and immediately
  produced F-36 — so the remaining two are worth a pass on the same basis.
- **`axe(container)` is blind to portalled content** across most of the ~90 existing axe
  assertions. F-34 was found by hand, not by the suite. Auditing which should move to
  `baseElement` deserves its own pass. Note `baseElement` pulls in axe's page-level `region`
  rule, which an isolated component render cannot satisfy — disable that rule there.
- **Backend copy defect (backend-owned):** alert messages read "is 1 days past due" —
  `AlertRuleEvaluationWorker` does not singularise.

---

## 11. Verification

Gates run from `frontend/` and `backend/` respectively. Final state:

| Check | Result |
|---|---|
| `tsc -b` | clean |
| `eslint . --max-warnings 0` | clean |
| `prettier --check .` | clean |
| Frontend `vitest run` | **158 files / 1024 tests passing** (from 150 / 939 at phase-3 baseline) |
| Backend `mvnw test` | **727 tests, 0 failures** (86 skipped — Testcontainers/Postgres, Docker-gated) |
| `check-bundle-boundaries` · `check-encoding` | pass |
| Migration `V114` | applied to the dev DB; snapshot worker wrote successfully afterwards |

**Caveat on the migration:** the 86 skipped backend tests are the Testcontainers ones, so the
suite could not validate `V114` in an environment without Docker. It was validated by a real
boot — Flyway applied it, then `PortfolioKpiSnapshotWorker` performed a genuine INSERT against
the altered table.

**Two operational notes for whoever runs this next.** Run `vitest` from `frontend/`, never the
repo root — from the root it sweeps up the Playwright specs and produces a bogus run. And do
not run the frontend and backend suites concurrently; CPU contention pushed one run from 160s
to 983s and produced a spurious 15s timeout.

---

## 12. What is genuinely good

The token system is the strongest part of this codebase — derived rather than eyeballed,
documented with its own failure history, and enforced by a contrast suite that breaks the
build. Dark mode is a first-class citizen. `DocumentPreviewModal` is exemplary
security-and-UX engineering. Table row accessible names are better than most shipping
products. The domain vocabulary in `CONTEXT.md` is real and largely honoured. The Impeccable
detector found **zero** genuine anti-patterns across the entire frontend — there is no AI-slop
problem here.

The gap was never craft. It was that the product writes down excellent rules and then, in a
handful of specific places, does not enforce them. Most of this remediation was enforcement —
and where possible, enforcement moved into the type system or a shared module so the rule
cannot quietly drift again.
