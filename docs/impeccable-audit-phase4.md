# Impeccable audit — phase 4

**Dates** 2026-08-06 → 2026-08-10 · **Predecessor** [`impeccable-audit.md`](./impeccable-audit.md)
**Environment** local dev (`localhost:5173` / `localhost:8080`, Supabase dev DB)
**Scope** §10 "Still open" of the phase-3 audit — the residue phase 3 explicitly left behind.
**Live evidence** `outputs/phase4-live-2026-08-06/`

Phase 3 closed on 2026-08-05 with seven items recorded as open. Phase 4 is those seven items,
plus five defects the work itself uncovered. Five sub-agent workstreams ran in parallel under a
lead orchestrator; the two live browser workstreams were orchestrator-owned because the browser
and the seeded database are shared resources.

> **Headline.** The money-critical disbursement UI had never been opened in a browser across two
> full audits. It has now been. The reason it was never reachable turned out to be a config
> default, not a UI problem — and the first live open immediately produced a defect that hid the
> confirm button of the most consequential dialog in the product.

---

## 1. The seven §10 items

| # | §10 item | Status | Evidence |
|---|---|---|---|
| 1 | Disbursement UI never exercised live | **Closed** | Both never-seen states seeded, held and audited live. Root cause found (§3). |
| 2 | Two `useEffect` data fetches remain | **Closed** | `DocumentsSection` + `TransitionConfirmDialog` on TanStack Query; a latent refetch bug removed with them. |
| 3 | `my-loans` fixture default-status coupling | **Closed** | Every call site audited; the two genuinely status-dependent tests now state their precondition. |
| 4 | Single-role live pass of `/my-loans` | **Closed** | Signed in as `audit.lspwrite`; list + detail scanned — **zero** raw enums across all 12 sections. |
| 5 | Viewports 1024 and 390 unmeasured | **Closed** | Both measured. No overflow, no clipping. 390 produced P4-01. |
| 6 | `axe(container)` blind to portalled content | **Closed** | Audited all 70 assertions; open-state coverage added where none existed. Found a shipped a11y bug. |
| 7 | Backend "is 1 days past due" | **Closed** | Fixed, plus three further defects in the same string found live (§3). |

### Item 2 — data-fetch idiom

`DocumentsSection` had two effects; the second depended on `docLabels`, a piece of *derived* data
held in state by the first, so the submitted-documents list was fetched twice on every mount. The
conversion removed that structurally rather than preserving it: labels are now derived, and the
upload path is a `useMutation` that invalidates the query instead of maintaining a parallel client
copy.

`TransitionConfirmDialog`'s hand-rolled request-identity + cancellation-flag hook became a
`useQuery` with `staleTime: 0`, `gcTime: 0`, `retry: false`, plus a cache flush on close. The
reasoning is recorded at the hook: this is the money figure an operator is about to confirm as
irreversible, so a single attempt must either produce a fresh number or a clear error — never a
silently retried request, and never a cached figure from a previous open.

**Files** `features/my-loans/components/DocumentsSection.tsx`, `features/my-loans/hooks/{useLspDocumentRequirements,useLspSubmittedDocuments,useUploadLspDocument}.ts`,
`components/app/lifecycle/TransitionConfirmDialog.tsx`, `components/app/lifecycle/useDisbursementPreview.ts` (extracted so the
component file stops exporting a non-component, which `react-refresh/only-export-components` correctly flagged).

### Item 6 — what the axe audit actually found

The §10 framing was that ~90 assertions were pointed at the wrong node. That turned out to be
**wrong, and the truth is worse.** Every one of the 70 `axe(container)` assertions was already
deliberately scoped to a *closed* state — so they were not lying. The real hole was that **no test
anywhere ever scanned a portal in its open state.** That is why F-34 (five unnamed popovers) had to
be found by hand under ~90 green assertions.

Open-state coverage was added for `DataTableViewOptions`, `DatePickerField`, `DataTablePagination`,
`UserMenu` (via `TopBar`), `CopyableId`, `TransitionDisabledTooltip`, `DocumentPreviewModal`,
`EscalateToAdminDialog`, and three components that had no test file at all — `EntityRowActions`,
`FilterBarShell`, `MultiSelectChip`. A shared `axeBaseElement()` helper in `src/test/utils.tsx`
disables only axe's page-level `region` rule, which `document.body` cannot satisfy in an isolated
render.

It caught a real one immediately — see P4-05.

One case is documented as genuinely not coverable: `DocumentPreviewModal`'s PDF branch renders a
`blob:` iframe that axe-core cannot script into under jsdom. The image branch exercises the same
dialog chrome and is tested instead, with the reasoning left in the test.

---

## 2. The disbursement gap — root cause

Two audits recorded `APPROVED_PENDING_DISBURSAL` as having "0 rows" and concluded the state was
hard to reach. It was not. Measured live:

> a loan reached `APPROVED_PENDING_DISBURSAL` at `10:11:39.803Z` and was fully `DISBURSED` by
> `10:12:02.307Z` — **~23 seconds later**, actor `SYSTEM_DISBURSEMENT_WORKER`. Reproduced twice.

`app.disbursement.worker.enabled` defaults to `true` and `application-local.yml` does not override
it. `application-staging.yml` sets it to `false` explicitly. **Staging is manual-first by design;
local dev is auto-first by accident of the default.** Every loan that reached the state was
disbursed by automation before a human could look at it, so `DisbursementInitiateDialog` was
effectively unreachable under local dev's own configuration.

`scripts/seed_disbursement_pending.py` reaches the state through the genuine origination path — LSP
API intake, then all 8 required KYC documents, whose final upload synchronously triggers
`LoanAutoApprovalRuleEngine` and moves `INITIALIZED → AWAITING_APPROVAL → APPROVED_PENDING_DISBURSAL`
in one request. (`manual-status` refuses this target by design: `MANUAL_OVERRIDE_TARGET_BLOCKED` in
`LoanApplicationStatus`.) It also documents the `DISBURSEMENT_RETRY` recipe.

For the audit itself the backend was simply run with `APP_DISBURSEMENT_WORKER_ENABLED=false`,
matching staging. **No file was changed** — see §5 for the decision this leaves open.

---

## 3. New findings

### P4-01 — relative-only timestamps had no path to the instant on touch · **fixed**

Found at 390px. The mobile card layout reuses the table's cell renderer verbatim, so
`variant="relative"` — chosen because a full timestamp would dominate a *dense table column* —
leaked into a stacked card, where the absolute value was carried only on `title`. **Touch devices
have no hover, so `title` never appears.** That is precisely the case the F-13 policy rules out:
*never relative with no path to the instant.*

Fixed where the rule lives rather than at call sites: `AbsoluteRelativeTime` reads a
`TimeLayoutProvider` context, and `DataTableMobileCards` declares itself `dense={false}`. Every
table gets the correct behaviour at once, and the dense-column reading is unchanged.

Verified live: mobile cards now render `06 Aug 2026, 21:51 · 42 seconds ago`; the desktop table
still renders `1 minute ago` with the absolute on `title`.

### P4-02 — a tall dialog's actions fell below the fold · **fixed**

Found on the first-ever live open of `DisbursementInitiateDialog`. `DialogContent` caps its height
and scrolls **as a whole**, footer included. At a 697px viewport — an ordinary laptop — the dialog's
content was 725px, so **both `Cancel` and `Initiate disbursement · ₹2,51,175.00` were entirely off
screen**, with the closing helper text reading like the end of the dialog and nothing indicating
more existed.

This is a shared-primitive defect affecting any dialog taller than the viewport, not just this one.
`DialogFooter` is now pinned inside the scroll box. The sticky offset is `-bottom-4`, not `bottom-0`,
because a sticky offset is measured from the scrollport's padding edge — at `bottom-0` a 16px strip
of scrolled content showed through beneath the actions.

Verified: actions visible at 697px, no bleed, body still scrolls; a short dialog (299px,
non-scrolling) is byte-for-byte unaffected.

### P4-03 — three defects in one alert string · **fixed**

Captured from the live DOM on `/home`:

> `Loan SYN-R2-LSP-03-APP-13 is 1 days past due (bucket DPD_1_30, overdue ₹13787.72).`

1. **`1 days`** — the §10 item.
2. **`bucket DPD_1_30`** — a raw enum, in the *same card* whose header correctly read
   "Delinquency · 1–30 DPD". One vocabulary, two spellings, adjacent. Dropped as redundant with the
   header the frontend already humanises.
3. **`₹13787.72`** — no digit grouping, on a screen where every other figure had it.

All three shipped only because phase 3's F-12 fix promoted `OpsAlert.message` into the alert card —
a lesson worth keeping: **promoting an internal string to UI makes its contents copy, and nobody
re-read it.** Fixed via `Strings.pluralize` and a new `Money.formatIndianGrouping` (the JDK's `en-IN`
locale does *not* produce Indian grouping), then swept across every count-plus-noun in the alert
path — 9 sites in `AlertRuleEvaluationWorker`, `OpsAlertEmitters` and `BorrowerOnboardingService`.

Verified live against freshly generated alerts, side by side with a pre-fix one:

| | rendered |
|---|---|
| after | `is 2 days past due (overdue ₹13,787.72)` |
| after | `is 1 day past due (overdue ₹13,787.72)` |
| before | `is 1 days past due (bucket DPD_1_30, overdue ₹13787.72)` |

`LSP bound violation: MISSING_LOAN_ACCOUNT` — a raw enum in alert *titles* — was fixed on the
frontend instead, matching the existing convention where a title carries a stable machine code and
`alert-display.ts` humanises it. All 24 violation codes mapped, with an exhaustive test so a future
enum member added in Java fails loudly rather than degrading silently.

### P4-04 — raw enum and raw ISO instant in the stuck-disbursement alert · **fixed**

> `Application WS5-APD-B-PFT8IE has been in DISBURSEMENT_RETRY since 2026-08-06T16:25:22.311456Z.`

A raw enum *and* a microsecond ISO-8601 timestamp in operator copy. It had been unreachable for the
life of the product: the rule only fires against a `DISBURSEMENT_RETRY` loan, and no such row had
ever existed. Creating one exposed it within minutes.

Now reads `has been retrying for 6 hours`. Deliberately an elapsed duration, not a formatted
instant — the message is assembled server-side and any absolute rendering would bake in a timezone
the reader may not be in. "How long has this been stuck" is also the question the rule exists to
answer.

### P4-05 — `MultiSelectChip` had a broken listbox → option relationship · **fixed**

`<ul role="listbox"><li><Button role="option">` — the native `<li>`'s implicit `listitem` role sat
between the listbox and its options, breaking `aria-required-children`, `aria-required-parent` and
`listitem`. **A shipped defect**: the component is live in `AlertsFilterBar`, so `/alerts`' filter
had broken semantics for screen readers. Fixed once at the shared component with
`role="presentation"`.

Found by the new open-state axe coverage — the suite catching the exact class of defect that F-34
needed a human for.

---

## 4. Validation

| Check | Result |
|---|---|
| `tsc -b` | clean |
| `eslint . --max-warnings 0` | clean (one warning introduced mid-flight was resolved by extracting `useDisbursementPreview`) |
| `prettier --check .` | clean |
| Frontend `vitest run` | **161 files / 1050 tests passing** (from 158 / 1024 at the phase-3 close) |
| `check-bundle-boundaries` · `check-encoding` | pass |
| Backend, Docker-free targeted | 15/15 pass (`StringsTest`, `MoneyTest`, `AlertRuleEvaluationWorkerStuckDisbursementCopyTest`) |
| Backend, full suite | **could not run — see below** |

**The backend suite now requires Docker far more broadly than phase 3 recorded.** Phase 3 noted 86
Docker-gated tests that *skipped*. The current tree has a Testcontainers refactor under
`src/test/java/.../support/` (`SharedPostgresTestContainer`, `PostgresTestContextCustomizerFactory`)
and with Docker down **587 of 810 tests error** with `Could not find a valid Docker environment`
rather than skipping. Every error is that one cause; none is a test failure. This predates phase 4
and is unrelated to its changes, but it means *the backend suite cannot be green on a machine
without Docker*, which is a real change in the project's development contract and deserves a
decision.

**Live browser validation** (`outputs/phase4-live-2026-08-06/`): 1024 and 390 measured with no
horizontal overflow and no clipped money; `/my-loans` list and detail under `LSP_UI_WRITE` with zero
raw enums and exactly one `h1`; MetricHint opening on a real touch tap with correct copy and no
viewport collision — validating phase 3's deliberate popover-not-tooltip choice; the DPD chart
rendering the corrected `Current / 1–30 / 31–60 / 61–90 / 90+` buckets; and the disbursement dialog
before and after P4-02.

Checked and found **not** to be defects, recorded so they are not re-investigated: the 384px
disbursement dialog width (it uses a two-column grid internally and nothing clips or wraps badly);
the density toggle at 390 (it does work — `p-4` → `p-3`; an early measurement targeted the wrong
element); and the `Columns` control at 390 (it does govern the card fields).

---

## 5. Still open

> Everything below that is a **decision rather than a task** is now tracked in
> [`deferred-decisions.md`](./deferred-decisions.md) (D1–D7), which is the live register. This
> section is kept as the phase-4 record.

- **The local-dev disbursement worker default.** `APP_DISBURSEMENT_WORKER_ENABLED=false` was used
  as a runtime override for this audit; no file was changed. Making local dev match staging would
  permanently fix the reachability of the disbursement UI, but it changes how every developer's
  machine behaves. **A decision, not a defect.**
- **No admin lever pauses an in-flight disbursement.** Once a `disbursement_intent` exists,
  `DisbursementIntentWorkflowService` executes it synchronously with no re-check of LSP status. This
  matches `CONTEXT.md`'s "in flight → hands-off" rule, but it is worth confirming the absence is
  intended given how much ops tooling elsewhere assumes a lever exists.
- **Document upload latency.** The 8-document batch took 36–42s server-side against R2 in three
  runs. A partner uploading one-by-one would be exposed longer.
- **Backend suite requires Docker** (above).
- **Cleanup from the audit run**: `scripts/seed_disbursement_pending.py` left two LSPs `INACTIVE`
  to hold state (`--release <lspId>`), and `audit.lspwrite`'s password was reset in order to do the
  single-role pass, since it had never been recorded anywhere.
- **Carried forward from phase 3, unchanged**: bulk selection / batch actions (§4.4) remains
  deferred, and heuristic 7 stays at 2/4 with a known cause.

---

## 6. Post-phase audit (Impeccable `audit`, 2026-08-10)

Run against the live stack after phase 4 landed, to verify the work rather than trust it. **17/20 —
Good.** Every phase-4 fix verified as landed and holding. The audit found six *new* issues, all in
code phase 4 touched or newly reached; none is a regression from phase 4.

| # | Dimension | Score | Key finding |
|---|---|---|---|
| 1 | Accessibility | 3/4 | Destructive button variant fails AA at **3.78:1** in light theme |
| 2 | Performance | 4/4 | Bundle boundaries enforced and passing; charts still dynamically loaded |
| 3 | Theming | 3/4 | Two competing red tokens; the build-enforced contrast test cannot see one of them |
| 4 | Responsive | 3/4 | 1024/390 clean; mobile cards silently drop columns the Columns control reports as visible |
| 5 | Implementation integrity | 4/4 | Detector: 1 finding, verified a **false positive** (matched prose in a security comment) |

### P1 — the destructive button variant fails WCAG 2.1 AA (1.4.3)

`#e7000b` on `#f5e0e4` measures **3.78:1** against a 4.5:1 requirement, at 12px normal weight.
Confirmed live by axe on the `APPROVED_PENDING_DISBURSAL` screen (2 nodes: "Mark disbursement
retry", "Mark invalid").

**Root cause — the same shape as a defect this project already fixed once.** There are two reds:
the project's own tuned `--color-danger: #b23a48` in `tokens.css`, and the shadcn preset's
`--destructive: oklch(0.577 0.245 27.325)` in `globals.css`. `token-contrast.test.ts` parses
**`tokens.css` only**, and its `FOREGROUND_TOKENS` list covers the nine project tokens —
`destructive` is not among them and is not in the file it reads. So the test is *structurally*
blind to it.

The test's own header says each token "must clear AA against its theme's surface **and against its
own 10% tint (the `bg-x/10 text-x` badge pattern)**". The destructive button is
`bg-destructive/10 text-destructive` — the exact pattern the test exists to protect, using the one
token it cannot see. This is the `--primary-tinted` finding from the 2026-08-01 UI audit repeating
in a second token: *a preset background value used as foreground text.*

**Scope is app-wide**, not disbursement-specific: `ConfirmDestructiveDialog`, `RevokeSessionsDialog`,
`RotateSecretDialog`, `DisbursementGateBanner`, `ForeclosureSummaryCard`, plus every lifecycle
action with `intent: "destructive"` — i.e. the highest-consequence confirmations in the product.

**Recommendation** Mirror the `--color-primary-tinted` fix: introduce a tuned foreground red for
tinted destructive surfaces, and extend the contrast test to read `globals.css` preset tokens so it
can never again be blind to one.

**Fixed (2026-08-10).** Both the button and badge destructive variants now use `danger`, the
project's own red — already gated by this suite on the surface *and* on its own 10% tint, in both
themes. The blind spot is closed by a new guard in `token-contrast.test.ts` that fails if any
component pairs a `bg-destructive/N` tint with `text-destructive`; it was mutation-tested by
restoring the old class string, which fails exactly that one test. `text-destructive` on a *solid*
surface (form messages, alerts) measures 4.77:1 and is left alone — the defect was the tint, not
the token. Verified live: axe reports **0 violations** on the disbursement detail, `/users` and
`/alerts` in **both** themes.

### P2 findings

- **"REPAYMENT · On track" on a loan that has never been disbursed.** Shown in "At a glance" on
  both `APPROVED_PENDING_DISBURSAL` and `DISBURSEMENT_RETRY` — including on the stuck-disbursement
  screen itself, where it reads as reassurance. No money has been lent, so there is no repayment to
  be on track. Contradicts Product Principle 5 ("make drift visible… rather than silently
  normalised into something that looks complete"). The outstanding *figures* are defensible — the
  metric hint discloses "including installments that are not due yet", and "as of today" correctly
  reads ₹0.00. What needs a product decision is the verdict label pre-disbursement.
- **"Mark disbursement retry" is offered unconditionally on `APPROVED_PENDING_DISBURSAL`.**
  `lib/lifecycle.ts:248-254` declares the transition with **no preconditions**, so an operator can
  move a loan into a state whose whole meaning is "an attempt failed" when no attempt has ever been
  made. Product Principle 1 is explicit: affordances follow reconcilability. This is the F-16 shape.
  It may be a deliberate ops escape hatch — worth confirming rather than assuming.
- **Rejected domain vocabulary in action labels.** `CONTEXT.md:25-27` lists **"retry"** as a term to
  *avoid* in favour of **"disbursement attempt"**; `PRODUCT.md` repeats the table. Both
  `"Mark disbursement retry"` and `"Retry disbursement"` use it. (The *status* label "Disbursement
  retry" is fine — `DISBURSEMENT_RETRY` is the canonical lifecycle enum.)
- ~~**Dark-theme primary button 4.49:1**~~ **· fixed 2026-08-10.** `#f0f9ff` on `#1678b4` measured
  4.499:1 against a 4.50 requirement. Root cause was arithmetic, not taste: the token's own comment
  already claimed *"keeping white text on it at 4.79:1"*, but 4.79 is what **pure** white gives —
  the foreground had been left at the preset's near-white `oklch(0.977 …)`. Setting dark
  `--primary-foreground` to `oklch(1 0 0)` delivers the ratio the comment documented.
- ~~**Mobile cards drop columns the Columns control reports as visible**~~ **· fixed 2026-08-10.**
  `DataTableViewOptions` now narrows its list at mobile widths to columns the card can actually
  render (`meta.mobileCard`), falling back to the full list when no column opts in, since the card's
  own fallback then renders a slice of them. At 390 the control now lists exactly the six fields the
  card shows; "Tenure" and the three identifier columns no longer appear as toggles that do nothing.

### Verified as *not* defects

The detector's single finding — `broken-image` in `DocumentPreviewModal.tsx:7` — is a **false
positive**: line 7 is prose inside the security comment describing why an `<img>` is *not* pointed
at the API URL. The real `<img>` (line 152) has `src={objectUrl}` and a proper `alt`.

### What the audit confirms is genuinely good

Bundle discipline is enforced and passing. Status is never communicated by colour alone. Row and
dialog accessible names are specific ("Reset password for audit.lspwrite", "Open loan for …").
`DisbursementInitiateDialog`'s copy is exemplary for money-critical UI — it states irreversibility
in plain language, masks the account number, discloses that bank details are live rather than
frozen, and puts the amount **on the confirm button itself**. Phase 4's own fixes were made at the
shared component and type level rather than at call sites, which is why they hold.
