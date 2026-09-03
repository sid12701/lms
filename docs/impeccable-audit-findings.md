# Impeccable audit — findings evidence log

Per-finding location, role, repro, observed behaviour and root cause for F-01…F-33, plus the
running remediation log kept during phases 1–2. The consolidated report — scores, phases,
decisions, new findings F-34…F-36, the live browser pass and the final gates — is
[`impeccable-audit.md`](./impeccable-audit.md), which is the document to read first.

Environment: local dev, frontend `localhost:5173`, backend `localhost:8080`.
Viewport unless stated: 1440×900 @2dpr, light theme, role SYSTEM_ADMIN+OPS_USER (`ops.admin`).

> **Screenshots referenced below are not in the repository.** They were captured to
> `tmp/ux-audit/shots/` (9 MB, gitignored scratch) and were deliberately not committed. The
> written evidence in this file stands on its own; the images do not survive a fresh checkout.

---

## F-01 — Invalid URL filter state is silently discarded (no feedback)
**Location** `frontend/src/lib/url-state.ts` (`useUrlFilters`) · `/loan-applications`
**Role** OPS_USER, SYSTEM_ADMIN
**Repro** Navigate to
`/loan-applications?status=BOGUS_STATUS&pageSize=9999&page=-5&sortBy=nonsense`
**Observed** Page renders the default unfiltered list — `Showing 1–25 of 1,004`, no applied-filter
chip row, Status control reads "All statuses", no warning of any kind. The invalid query string
stays in the address bar, so re-sharing propagates it.
**Also** `?status=AWAITING_APPROVAL,DISBURSEMENT_RETRY` (the multi-value encoding the schema's own
docstring specifies) is dropped entirely — chip row says "1 filter", results include `Closed` rows.
A single value (`?status=AWAITING_APPROVAL`) works correctly (21 applications, correct chip).
**Root cause** `LoanApplicationListFilters.status` is typed `z.array(LoanStatus)` and documented as
`?status=X,Y,Z`, but the control and the ops endpoint are single-select
(`LoanApplicationsFilterBar.tsx:294` — "Single-select on purpose"). Parse failure drops the key
rather than surfacing it.
**Why it matters** DESIGN.md: "not knowing whether you are seeing the whole book or a slice is a
correctness problem, not a cosmetic one." An operator following a stale deep link is shown the
entire book while believing they see a filtered slice.
**Severity** High

## F-02 — Document checklist copy is lifecycle-blind
**Location** `frontend/src/components/app/documents/DocumentChecklistRow.tsx:51-60`
(same in `DocumentUploadRow.tsx:68`, `DocumentChecklistGroup.tsx:80`)
**Role** OPS_USER, SYSTEM_ADMIN (and LSP on `/my-loans`)
**Repro** Open `/loan-applications/bc4b20b6-59b7-4a71-8dae-7d9f07c84f40?tab=documents`
(status `UNDER_REPAYMENT`, ₹1,50,000 disbursed, 6 installments already Paid).
**Observed** All 8 required documents render "Pending · **Required for disbursement** · No file
uploaded yet", each with a warning-tinted badge. The badge is driven only by
`doc.requiredForDisbursement`; nothing consults the loan's lifecycle status. There is no upload
control on the ops surface and no "download all", so ops sees a compliance gap it cannot act on.
**Why it matters** B2B2C: the money is already with a consumer. The UI asserts a pre-disbursement
gate on a loan that is past it, gives no explanation (waived? bypassed? data gap?), and offers no
remediation. Eight warning chips per loan also produce alarm fatigue.
**Severity** High

## F-03 — "No actions available from this status" contradicted by adjacent actions
**Location** `/loan-applications/:id` header (`ActionBar`) + Foreclosure panel
**Repro** Any `UNDER_REPAYMENT` loan detail.
**Observed** The line "No actions available from this status." renders directly above a Foreclosure
panel containing an enabled date picker and an enabled "Request quote" button.
**Why it matters** The statement is false as written; an operator who believes it will not discover
foreclosure. Undermines trust in every other affordance statement on the page.
**Severity** Medium

## F-04 — Foreclosure panel occupies ~55% of the viewport on every tab, mostly disabled
**Location** `/loan-applications/:id`
**Observed** At 1440×900 the panel spans y≈360–745 above the tab strip and is rendered on all six
tabs. For a loan with no quote it shows a disabled "Settlement reference" input, a disabled "Note"
textarea and a disabled solid-primary "Execute quote" button. On the Schedule tab the first
installment row does not appear until y≈900, so only ~6 of 12 installments are visible.
**Why it matters** Directly contradicts the product's stated north star (information per square
inch) and the "no lever attached to nothing" principle. Foreclosure is an edge action; the schedule
is the daily one.
**Severity** Medium

## F-05 — Density toggle's selected state is ~1.07:1 against its container
**Location** `frontend/src/components/app/data/DensityToggle.tsx:38-65`
**Observed** Selected segment uses `variant="secondary"` → `oklch(0.967 0.001 286.375)` on a white
surface. Unselected is transparent. Font weight is 500 on both; border transparent on both. Measured
via computed style. ARIA is correct (`role="radio"` inside `role="radiogroup"`), so this is purely
a visual-state failure.
**Why it matters** WCAG 1.4.11 requires 3:1 for the visual information identifying control state.
DESIGN.md's own rule: applied state must read on three channels, not one.
**Severity** Medium (accessibility)

## F-06 — Primary sidebar does not fill the viewport height
**Location** `frontend/src/components/app/shell/Sidebar.tsx:35` (`h-full`) within
`AppShell.tsx:73` (`flex min-h-screen`)
**Observed** At 1440×900 the `<aside>` measures 659.5px tall against a 900px viewport and a 1072px
shell, leaving a band of bare page background below the sign-out footer while content continues to
its right. `height:100%` against an auto-height flex parent resolves to content height and defeats
the default `align-items: stretch`.
**Severity** Medium (visual)

## F-07 — Status filter options drop the status vocabulary
**Location** `/loan-applications` status combobox
**Observed** The dropdown lists the 10 lifecycle states as plain text. Everywhere else in the
product a status is colour + icon + label (`StatusBadge`, an explicit invariant in DESIGN.md).
**Why it matters** The operator cannot visually match a dropdown entry to the badge they are
scanning for in the table; recognition becomes recall.
**Severity** Low

## F-08 — Detail breadcrumb and page identity read "Detail"
**Location** `frontend/src/components/app/shell/breadcrumb-labels.ts`
**Observed** `/loan-applications/:id` breadcrumb renders `Home > Loan applications > Detail`; the
route announcer says "Detail page"; `<title>` stays "LMS" on every route.
**Why it matters** With several loans open in tabs, nothing distinguishes them. Ops work is
multi-tab by nature.
**Severity** Medium

## F-09 — Detail-page skeleton does not map to the content it replaces
**Location** `/loan-applications/:id` loading state
**Observed** Three short bars, two small blocks, then one ~270px full-width grey block. The loaded
page is a header + action bar + panel + tab strip + table. The oversized block reads as a broken
panel rather than as loading.
**Severity** Low

## F-10 — Home: chart axis label collides with tick labels
**Location** `frontend/src/features/home/components/DpdBucketChart.tsx`
**Observed** The rotated "Loans" y-axis label overlaps the "400" tick label on the "Loans by DPD
bucket" chart at 1440 width.
**Severity** Low

## F-11 — Home: `AVG APPROVAL TAT` renders a bare em-dash
**Observed** KPI tile shows "—" with no supporting text, while the neighbouring "OVERDUE LOANS"
tile carries a secondary line. Nothing says whether the metric is unavailable, not yet computed,
or genuinely zero.
**Severity** Low

## F-12 — Open alerts list has no information scent
**Location** `/home` "Open alerts" panel
**Observed** Five consecutive entries read "Delinquency bucket DPD_1_30 · HIGH", distinguished only
by an 8-character id fragment and a relative timestamp. No borrower, no amount, no DPD value.
**Why it matters** Triage requires opening each one to learn anything.
**Severity** Medium

---

## Data/environment notes (NOT product defects)

- `DISBURSEMENT_RETRY` is transient: two applications moved into it were resolved to `REJECTED` by
  the disbursement worker within minutes (`LoanApplicationStatusTransitioner` WORKER context allows
  `DISBURSEMENT_RETRY → REJECTED`). Live capture of this state needs the worker paused.
- `APPROVED_PENDING_DISBURSAL` has 0 rows and is deliberately gated: `manual-status` refuses it
  ("Use the standard approval flow instead"), and the standard flow requires complete KYC
  (`KYC_COMPLETION_REQUIRED`).
- Synthetic seed data has future-dated installments marked Paid; not a UI defect.
- Early browser-call timeouts were chrome-devtools MCP request serialisation across concurrent
  agents, **not** an application performance problem. Do not report as such.

## F-13 — Repayments ledger uses relative-only timestamps; Activity uses absolute+relative
**Location** `/loan-applications/:id?tab=repayments` vs `?tab=activity`
**Observed** Repayments "Posted at" column renders "in 12 days", "in 11 days"… Activity renders
"13 Jul 2026, 00:38 · 22 days ago" (the correct pattern). Schedule uses absolute ("14 Aug 2026").
Three tabs on one record, three temporal conventions.
**Why it matters** B2B2C: a payment ledger is reconciliation and dispute evidence. An LSP agent
answering a borrower cannot cite "in 12 days". Also, "Posted at" rendering a *future* time is
never valid for a posted payment and is passed through without comment.
**Severity** Medium

## F-14 — Raw backend enum leaks into the repayments ledger
**Location** `/loan-applications/:id?tab=repayments`, Status column
**Observed** Renders `POSTED` in raw uppercase. Every other status in the product is humanised
("Under repayment", "Paid", "Initialized"). DESIGN.md reserves uppercase for the 11px eyebrow only.
**Severity** Low

## F-15 — Audit timeline renders an empty "Reference id" with an enabled copy button
**Location** `frontend/src/components/app/audit/AuditEventNode.tsx`
**Observed** Every node renders the label "Reference id" followed by an empty value, plus an
enabled icon button `aria-label="Copy reference id"`. Copying yields nothing.
**Note** ARIA on the button is correct; the defect is the unconditional label + dead affordance.
**Severity** Low

## Verified NOT defects (checked, do not report)
- Repeated `Initialized → Under repayment` entries in the Activity tab come from the API/seed data
  (`/status-transitions` returns 4 identical rows). Not a rendering bug.
- Activity copy buttons are icon-only but carry `aria-label="Copy reference id"` — accessible.
- Table skeletons use `animate-pulse` and match column widths — good practice, not a defect.

---
# LSP partner surface (`/my-loans`) — role LSP_UI_WRITE

## F-16 — "Mark invalid" is offered on loans where the transition is illegal
**Location** `frontend/src/features/my-loans/components/MarkInvalidDialog.tsx` + `/my-loans/:id`
**Role** LSP_UI_WRITE (partner-facing)
**Repro** Sign in as `audit.lspwrite`, open `/my-loans/ef442df3-d55b-4e43-b709-bc785817b8c6`
(status `UNDER_REPAYMENT`), click "Mark invalid", choose a reason, submit.
**Observed** The button is enabled; the dialog opens; a reason can be selected; submit enables and
shows "Submitting…"; the backend then rejects with the inline `role="alert"` message
"Loan applications that have entered servicing cannot be marked invalid." Status is unchanged.
**Root cause** `LoanApplicationStatus.canTransitionTo` (`LoanApplicationStatus.java:44-45`) allows
`UNDER_REPAYMENT → CLOSED | FORECLOSED` only. `INVALID` is unreachable from servicing. The UI does
not consult the transition table before offering the action.
**Why it matters** Violates the product's first principle verbatim: "If an action is unsafe at the
current point… it must not be presented as available — and the reason must be legible, not merely
enforced." Five steps of wasted work, on a partner-facing surface, ending in a destructive-intent
commitment that silently does nothing. A `TransitionDisabledTooltip` component already exists in
the design system for exactly this.
**Positive** Error *recovery* is good: dialog stays open, input preserved, plain-language message.
**Severity** High

## F-17 — LSP surface has no search, filter, or sort — only "Rows per page"
**Location** `/my-loans`
**Observed** The only form control on the page is the rows-per-page select. 250 loans across 10
pages of 25. Page 1 is entirely `Invalid` and `Rejected` rows. The internal `/loan-applications`
list has 7 filters plus a date range.
**Why it matters** B2B2C: LSP staff are who the borrower phones. Answering "what is my loan status"
requires locating one loan among 250 by paging blind. This is the largest workflow gap found.
**Severity** High

## F-18 — Internal ops detail omits the interest rate that the partner surface shows
**Location** `/loan-applications/:id` Overview "Loan terms" vs `/my-loans/:id` "Loan terms"
**Observed** LSP view shows `INTEREST RATE 18.5%`; internal ops view shows requested amount, tenure,
product, source channel, created, updated — no rate. The two repayment-schedule tables also differ:
internal is `# · Due date · Principal · Interest · Installment · Outstanding · Status`; LSP is
`# · Due date · Amount · Paid · Outstanding · Status · DPD`. Same loan, two schedules, different
columns, and only the partner sees DPD per installment.
**Why it matters** Internal staff supervising an escalation see less than the partner they are
supporting. Divergent column sets for one domain object is a design-system failure.
**Severity** High

## F-19 — Raw backend enums are shown to partners
**Location** `/my-loans/:id` "Recent activity"
**Observed** `TYPE STATUS_TRANSITION`, `SUMMARY Moved from INITIALIZED to UNDER_REPAYMENT`.
The internal Activity tab humanises the same data to "Initialized → Under repayment".
**Why it matters** The external, partner-facing surface presents rawer text than the internal one.
**Severity** Medium

## F-20 — LSP detail loads with a bare spinner; internal detail uses skeletons
**Location** `/my-loans/:id` loading state
**Observed** Full-page "◌ Loading loan details…" under an `h1` of just "Loan". The internal detail
renders a skeleton. Two loading idioms for the same kind of screen.
**Why it matters** Research consensus: skeletons for content-rich predictable layouts at 1–10s;
spinners for short atomic actions. This page has a highly predictable 11-section layout.
**Severity** Low

## F-21 — Change-password screen has no show-password toggle (login does)
**Location** `/change-password`
**Observed** Two password fields, no reveal control, while `/login` provides "Show password".
Forces blind double entry of a 12+ character password. Also no strength meter, no visible required
marker, and no way to sign out / return to `/login` from the forced screen.
**Positive** `autocomplete="new-password"` and `aria-describedby` are correct on both fields;
"Passwords do not match" is specific and correctly attached.
**Severity** Medium

## F-22 — Form errors are not announced on submit
**Location** `/change-password` (pattern is shared via `components/ui/form.tsx`)
**Observed** On submit, invalid fields get `aria-invalid="true"` and a linked inline message, but
there is no `role="alert"` / live region and focus is not moved to the first invalid field.
**Note** `MarkInvalidDialog` *does* use `role="alert"` for its server error — so the pattern exists
in the codebase and is applied inconsistently.
**Severity** Medium (accessibility)

## F-23 — Redundant nested header block on `/my-loans`
**Observed** `PageHeader` "Loan applications / Loans and applications for your lending partner."
followed immediately by a bordered card "Loan applications / Review applications and accounts you
originated." with a chevron implying navigation, sitting directly above the table it describes.
**Why it matters** Duplicate title, second description, and a container that adds no information —
the "excessive cards / nested containers" pattern.
**Severity** Low

## F-24 — Route, product vocabulary, and UI label disagree
**Observed** Route `/my-loans`; PRODUCT.md calls it "My loans"; sidebar and `h1` both say "Loan
applications" — the same label the internal ops list uses for a different screen.
**Why it matters** Bhawana staff supporting a partner cannot say "go to Loan applications"
unambiguously. CONTEXT.md treats naming as load-bearing.
**Severity** Medium

---
# Cross-cutting

## F-25 — Two independent definitions of "mobile" in one system
**Location** `AppShell.tsx:19-39` (`window.innerWidth` + `resize` listener, LG=1024) vs
`DataTable.tsx:452` (`useMediaQuery("(max-width: 767px)")`)
**Observed** The shell drops the sidebar and switches to the drawer below **1024px**. The data table
switches to `DataTableMobileCards` below **768px**. Between 768–1023px you get the "mobile" shell
with a horizontally scrolling desktop grid — while DESIGN.md states "mobile falls back to
`DataTableMobileCards` rather than a horizontally scrolling grid."
**Compounding risk** The shell derives its tier from JS state updated only by a `resize` listener,
while the hamburger that rescues it is gated by a CSS media query (`TopBar.tsx:85` `lg:hidden`). If
the two disagree, the sidebar is absent *and* the hamburger is hidden — leaving no navigation at
all. This was observed once in a tab whose viewport changed without a `resize` event: mobile cards
rendered at 1440px with zero `<aside>` and no visible menu trigger.
**Fix direction** One source of truth — drive both from `matchMedia` listeners, and never CSS-gate
the only fallback navigation trigger.
**Severity** High (architectural; the observed total-nav-loss is the worst case)

## F-26 — Permission-denied page renders no `<h1>`
**Location** `frontend/src/components/app/feedback/PermissionDeniedState.tsx`
**Repro** Sign in as `audit.lspwrite`, navigate to `/users`, `/audit`, or `/loan-applications`.
**Observed** `document.querySelectorAll('h1')` is empty. Every other route has an `h1`.
**Positive** The copy is genuinely good: "Internal workspace only — Signed in as LSP user. This area
is limited to System admin or Ops user or Product admin. Contact your administrator if you need
access." plus two recovery actions.
**Aggravated by F-24** An LSP blocked from `/loan-applications` is offered "Go to loan applications"
as the recovery — the same label as the page that just refused them.
**Severity** Medium (accessibility)

## F-27 — Row actions use two different patterns across the four admin tables
**Observed** `/products` uses a single `EntityRowActions` menu ("Actions for <code>").
`/lsps` renders four inline buttons per row (Details · Status · Audit · Webhook);
`/users` renders four (Edit · Reset password · Revoke sessions · Disable);
`/api-clients` renders inline (Edit · Rotate secret).
At 14 LSP rows that is 56 buttons competing in one column.
**Why it matters** Same object class, same table component, two interaction vocabularies. The menu
pattern already exists and is used — the others have not adopted it.
**Severity** Medium (design-system consistency)

## F-28 — `MTD disbursed` is formatted and valued differently on two screens
**Observed** `/home` KPI reads `₹12,30,00,000`; `/reports` KPI reads `₹12.7Cr`. Different notation
(full lakh/crore grouping vs abbreviated) and, on their face, different values.
**Needs verification** whether the two metrics use the same window/definition; if they do, one is
wrong, and if they don't, neither label says so.
**Severity** Medium (needs backend confirmation)

## F-29 — Borrower directory exposes PAN and Aadhaar as list columns
**Location** `/borrowers`
**Observed** Default columns include `PAN` and `Aadhaar` in a browsable, searchable directory across
every LSP. The loan-applications list deliberately masks borrower names (BR-7, `borrowerNameMasked`)
and the detail page gates Aadhaar behind a masking notice and a PII-reveal audit stream.
**Why it matters** Inconsistent PII posture: the strictest treatment is on the detail page, the
loosest on the widest-reach list. Worth an explicit product decision rather than a default.
**Severity** Medium (privacy — needs product confirmation)

## Positives worth preserving
- `DocumentPreviewModal` is exemplary: authenticated blob fetch, revoked object URLs, sandboxed
  iframe, MIME allowlist, and distinct unsupported / error / loading states with reduced-motion
  handling and real `alt` text.
- Table row accessible names are excellent: "Open application for <borrower>, <status>, <amount>,
  LSP ref <ref>".
- Pagination on the internal lists is complete and states the total ("Showing 1–25 of 1,004").
- Dark theme is well executed — navy-tinted surface ladder holds up across shell, filter bar and
  table; the density-toggle contrast failure (F-05) is light-mode only.
- Mobile drawer works correctly: exactly one `aside` with the "Primary navigation" landmark, focus
  moves inside on open.
- Error *recovery* in `MarkInvalidDialog` preserves input and gives a plain-language `role="alert"`.
- The Impeccable detector found 0 genuine issues across `frontend/src` (its single hit was a false
  positive matching `<img>` inside a documentation comment).

---
# Dialogs / modals (17 of 26 opened live)

## F-30 — "Disable" deactivates a user account on a single click, with no confirmation
**Location** `frontend/src/features/users/page.tsx:255-262` (`handleToggleStatus`)
**Role** SYSTEM_ADMIN
**Repro** `/users` → click "Disable" on any row.
**Observed** `update.mutate({ status: "DISABLED" })` fires immediately. No dialog, no confirmation,
no undo. Verified live: clicking it set `audit.lspread` to `INACTIVE` (restored to `ACTIVE` via the
admin API afterwards).
**Why it matters** This is the most consequential row action on the page — it locks a person out —
and it is the *only* one with no confirmation. On the same row, the **less** destructive
"Revoke sessions" opens a confirm dialog with a reason field, and "Reset password" confirms too.
The escalation ordering is inverted. `ConfirmDestructiveDialog` already exists in the design system
and is not used here. The LSP equivalent ("Change LSP status") is handled correctly, with a
consequence-explaining description and a disabled-until-changed Save.
**Severity** High

## F-31 — Required-field marking is inconsistent across dialogs
**Observed** `ProductCreateDialog` is the only dialog that marks required fields
("Code* (required)", "Name* (required)", 8 fields in total). `UserCreateDialog`, `LspCreateDialog`,
`ApiClientCreateDialog`, `CreateReportDialog` and `ChangePasswordPage` mark none, and no field
carries the native `required` attribute — validation is Zod-only and appears after submit.
**Severity** Low

## F-32 — Two controls share the accessible name "Close" in several dialogs
**Observed** `LspDetailsDialog` and `LspAuditEventsDialog` expose a footer "Close" and the dialog's
own "Close" affordance; both resolve to the same accessible name within one dialog.
**Severity** Low

## Dialogs verified GOOD (do not report as defects)
All 17 dialogs opened carry `role="dialog"`, `aria-labelledby`, and move focus inside on open.
Notably strong destructive-action copy:
- `RotateSecretDialog` — "Rotating invalidates the current secret immediately. Any integration still
  using it will fail." plus a required reason.
- `LspStatusChangeDialog` — "Disabling revokes all outstanding API tokens and deactivates API
  clients"; Save disabled until a change is made.
- `RevokeSessionsDialog` — "This will sign <user> out of every device."
- `UserCreateDialog` — "A temporary password is generated and shown exactly once."
- `AcknowledgeAlertDialog` — names the specific alert being acknowledged.

## Probe artifacts — NOT defects (verified)
- An earlier probe reported "Acknowledge opens no dialog". It had matched the **"Acknowledged"
  status-filter tab**, not the row action. The real row action opens `AcknowledgeAlertDialog`
  correctly. No alert was mutated.

## Dialogs still NOT opened live (9)
`DisbursementInitiateDialog`, `RepaymentPostDialog`, `ForeclosureRequestDialog`,
`TransitionConfirmDialog`, `EscalateToAdminDialog`, `ConfirmDestructiveDialog`,
`ProductEditDialog`, `ProductMappingDialog`, `LspIpAllowlistDialog`.
The first five need lifecycle states that could not be created (`APPROVED_PENDING_DISBURSAL`
requires 8 uploaded KYC docs; `DISBURSEMENT_RETRY` is worker-transient). **This leaves the
money-critical disbursement UI unverified — it should be the first target of a follow-up pass.**

## F-33 — Delinquency escalation is invisible to assistive tech (found during remediation)
**Location** `frontend/src/components/app/status/statusBadgeMeta.ts:41-45`
(`getUnderRepaymentBadgeTone`) + `StatusBadge`
**Observed** When a loan under repayment is delinquent, the badge intent flips `success → danger`
and the icon changes, but `label` stays "Under repayment". Verified on
`/my-loans/ef442df3-…` (bucket `DPD_1_30`, ₹13,788 overdue, 25 days past due): the badge renders
red with `color: rgb(178,58,72)`, and its accessible name is exactly "Under repayment" —
no `aria-label`, no `title`, no delinquency wording.
**Why it matters** DESIGN.md's "Never Colour Alone Rule" requires colour **plus icon plus text**,
and names `StatusBadge` as the component that enforces it. Here colour and icon carry the
delinquency; the text does not. A screen-reader user hears "Under repayment" for both a healthy
loan and one 25 days past due. In B2B2C terms an LSP agent using a screen reader cannot tell a
current borrower from an overdue one.
**Suggested fix** Have `resolveStatusMeta` return a delinquency-aware label (e.g. "Under repayment
· 25d past due") so the text channel carries the same information as the colour.
**Severity** High (accessibility + financial legibility)

## Correction to an earlier inference
An intermediate observation that the same status rendered green on the internal detail and red on
the partner detail was **wrong** — they were two different loans, one delinquent and one not
("On track / Repayments are on schedule"). The ops detail does pass `delinquency`
(`loan-applications/detail-page.tsx:271-274`). There is no internal/partner colour inconsistency.

---
# Remediation log

## ✅ F-16 fixed — invalidation now gated on the transition table
`features/my-loans/detail-page.tsx`
- Added `resolveMarkInvalidDisabledReason`, which asks the existing
  `canTransition(role, status, "INVALID", {})` gate. Invalidation rules carry no preconditions, so
  no context assembly was needed and no new gate logic was written.
- Split the previously conflated `canMutateLoan` flag: document upload stays open for the life of a
  non-terminal loan; invalidation closes at disbursement.
- Terminal loans hide the action (unchanged behaviour). Servicing loans (`DISBURSED`,
  `UNDER_REPAYMENT`) show it disabled with the reason, rather than a permanently dead control on a
  closed record.
- A disabled button is not focusable, so the tooltip alone was unreachable by keyboard and screen
  reader. The reason is mirrored into an `sr-only` element wired via `aria-describedby`.
- Tests: two existing tests were **asserting the bug** (fixture defaults to `UNDER_REPAYMENT`);
  moved to `AWAITING_APPROVAL`. Added coverage for disabled+reason on `DISBURSED`/`UNDER_REPAYMENT`,
  hidden on `CLOSED`, and still enabled pre-disbursal.
- Verified live: button disabled, `aria-describedby="mark-invalid-disabled-reason"`, reason reads
  "Loans that have entered servicing can't be marked invalid."

## ✅ F-30 fixed — disabling a user is now confirmed
`features/users/page.tsx`
- `handleToggleStatus` routes disable through the existing `ConfirmDestructiveDialog`; re-enabling
  stays immediate because it is restorative, not destructive.
- Description names the user and states the consequence, matching the standard already set by
  `LspStatusChangeDialog`. No typed-confirmation token — disproportionate for an internal admin
  action, and reserved for high-impact operations like loan invalidation.
- Server errors render in the dialog rather than dismissing it.
- Tests: the existing test asserted the single-click mutation and was updated; added a test that
  re-enable still applies without a confirm step.
- Verified live: dialog titled "Disable user", description
  "audit.prodadmin will be signed out and blocked from signing in again…", focus defaults to Cancel.

**Gate after both fixes:** `tsc -b` clean · `eslint . --max-warnings 0` clean · `prettier --check`
clean · full suite **873 tests / 138 files passing**.

## Still open in Group 1
1.2 (F-01 silent URL filter loss) · 1.3 (F-02 lifecycle-blind document checklist) ·
1.4 (F-03 contradictory "No actions available") · 1.5 (F-17 no search on `/my-loans`).

## ✅ F-03 fixed — the bar no longer claims "no actions" while a panel offers one
`components/app/lifecycle/ActionBar.tsx` + `features/loan-applications/components/DetailHeader.tsx`
- Root cause: `UNDER_REPAYMENT → CLOSED` is system-only (dropped by `actionsFor`) and
  `→ FORECLOSED` was hidden via `hiddenTargetStatuses` because `ForeclosureQuotePanel` owns it.
  The bar was left empty and asserted the status had no actions — directly above the panel.
- `ActionBar` now distinguishes "genuinely no transitions leave this status" from "every action was
  relocated to a dedicated workflow", and stays silent in the second case.
- `DetailHeader` now relocates `FORECLOSED` **only when it actually renders the panel**
  (`showsForeclosurePanel`), so a non-admin no longer has an action hidden that nothing else offers.
- Tests: the existing test asserted the contradiction; replaced with one asserting silence, plus a
  test that a genuinely-empty status still reports itself.
- Verified live on an `UNDER_REPAYMENT` loan: no "No actions available" text, foreclosure panel and
  "Request quote" both present.

## ✅ F-01 fixed — rejected URL filters are now named instead of dropped silently
`lib/url-state.ts` · `components/app/data/FilterBarShell.tsx` ·
`features/loan-applications/components/LoanApplicationsFilterBar.tsx` · `…/types.ts`
- `parseFilters` now returns `{ values, ignoredKeys }`; `useUrlFilters` exposes `ignoredKeys` as a
  third tuple element, so the seven existing two-element call sites are unaffected.
- New shared `IgnoredFilterNotice` + a `notice` slot on `FilterBarShell`, so the other eight filter
  bars can adopt the same treatment without inventing their own.
- Copy names filters by their on-screen label ("Status", "Rows per page"), not the schema key, and
  states the consequence: "You are seeing every result that matches the remaining filters."
  `role="status"`, not `alert` — worth announcing, not worth interrupting.
- The URL self-heals on the next interaction (serialization runs from parsed values) rather than
  being rewritten under the user on load.
- **Also corrected a false docstring**: `LoanApplicationListFilters.status` claimed the encoding was
  `?status=X,Y,Z`. `serializeFilters` writes repeated params (`?status=X&status=Y`); comma-joined
  values never parsed. The docstring now says so and explains the single-select reality.
- Tests: new `lib/url-state.test.tsx` (5 cases) covering valid parse, repeated-param arrays,
  invalid params being named, comma-joined arrays rejected rather than half-applied, and absent
  params not being flagged.
- Verified live in both themes at the original failing URL.

## Still open in Group 1
1.3 (F-02 lifecycle-blind document checklist) · 1.5 (F-17 no search on `/my-loans`).

## ✅ F-17 fixed — the LSP surface can now find a loan
`features/my-loans/{page,types}.tsx` · `features/my-loans/components/MyLoansFilterBar.tsx` ·
`features/my-loans/api.ts`
- **The backend already supported it.** `LspLoanApplicationApiController` accepts `q`, `status`,
  `productId` and `sourceChannel`; the frontend simply never sent them. Verified by API before
  building: `q=1001154` → 1 of 250, `status=UNDER_REPAYMENT` → 50 of 250. So this is genuine
  server-side filtering, not a client-side filter over one page.
- New `MyLoansFilterBar` built from the shared `FilterBarShell` primitives, so the partner surface
  gains the same search / status / applied-chips / result-count / clear-all vocabulary as the
  internal list, plus the F-01 ignored-filter notice for free.
- Filters and pagination are URL-bound via `useUrlFilters`, so "this borrower's loan" is now a
  shareable link — the actual support workflow.
- **`status` is modelled as a single value, not an array** — matching what the endpoint accepts.
  This is the F-01 lesson applied: the array-shaped schema on `/loan-applications` is exactly what
  let schema, control and endpoint drift apart.
- Empty state now distinguishes an over-filtered list ("No loans match these filters") from an
  empty book ("No loans yet").
- **Also closes F-23**: the redundant `LspLinkCardGrid` card — a bordered panel repeating the page
  title and linking to the page you were already on — is replaced by the filter bar, which earns
  the same space.

## ✅ Design-system extract (F-27 groundwork)
`components/app/data/FilterBarShell.tsx`
- Promoted `SingleSelect` out of `LoanApplicationsFilterBar` into the shared module as
  `FilterBarSingleSelect`, together with the `__all__` sentinel that six filter bars were each
  re-implementing. `/loan-applications` now imports it; behaviour unchanged (113 tests green).
- Extracted on the *second* real consumer rather than speculatively, and the other five bars are
  left to adopt incrementally — no big-bang refactor of files this change does not otherwise touch.
- Fixed a copy defect found while verifying: `FilterAppliedChips` rendered "1 loans" / "1
  applications". It now singularises, with a `resultNounSingular` escape hatch for irregular nouns.

## Group 1 status: 5 of 6 complete
Remaining: **1.3 (F-02)** lifecycle-blind document checklist — needs a product/backend call on
whether the checklist gets a lifecycle-aware field or the frontend derives it from status.

## ⚠️ Side effect to decide: `LspLinkCardGrid` is now orphaned
Replacing the redundant card on `/my-loans` (F-23) removed its last non-test consumer.
`grep` confirms no production file imports `features/home/components/LspLinkCardGrid.tsx` any more,
and `features/home/page.test.tsx:31` still mocks it for a component `/home` does not render.
Not deleted unilaterally — it is a product surface, not obviously garbage. Either remove it with
its test and the stale mock, or re-home it on `/home` if the LSP link grid was meant to live there.

## Gate after Group 1 (5 of 6 fixes)
`tsc -b` clean · `eslint . --max-warnings 0` clean · `prettier --check` clean ·
**139 test files / 882 tests passing.**

One-off during verification: `UsersTable.test.tsx > lockout badge` failed a single full run, then
passed in isolation, with the whole `users` folder, across three consecutive repeats, and in the
clean re-run. `UsersTable.tsx` and its test are at HEAD — untouched by this work and not among the
pre-existing working-tree changes. Treated as parallel-execution flakiness, not a regression; worth
watching if it recurs.

A second apparent failure was operator error, not code: the shell cwd had drifted to the repo root,
so `vitest` swept up the Playwright `e2e/*.spec.ts` files. Invalid run, discarded.

## ✅ LspLinkCardGrid removed (orphan follow-up)
Deleted `features/home/components/LspLinkCardGrid.{tsx,test.tsx}` and the stale mock plus a now
vacuous assertion in `features/home/page.test.tsx`.
**Why delete rather than re-home:** `HomePage` redirects every non-`SYSTEM_ADMIN` role away
(`home/page.tsx:51` → `defaultLandingFor`), so LSP users never reach `/home` and the grid could
never have served as their landing content. Its single card linked to `/my-loans` with exactly the
title and description that *was* the redundant F-23 card — the component's entire content was the
redundancy already removed.

## ✅ F-02 fixed — the document checklist is lifecycle-aware
`components/app/documents/disbursement-gate.ts` (new) · `DocumentChecklistGroup.tsx` ·
`DocumentChecklistRow.tsx` · `DocumentUploadRow.tsx` · `detail-tabs/DocumentsTab.tsx` ·
`loan-applications/detail-page.tsx`
- Frontend-derived, no API change: `isDisbursementGatePassed(status)` treats `DISBURSED`,
  `UNDER_REPAYMENT`, `CLOSED` and `FORECLOSED` as past the gate, mirroring
  `LoanApplicationStatus.canTransitionTo` (nothing returns to a pre-disbursal status).
- Past the gate the section heading, badge and empty copy shift to past tense — "Required before
  disbursement" / "Not provided before disbursement." — and the badge drops its warning tint for a
  neutral one. The claim is now true, and it no longer asserts a blocker nobody can act on.
- `undefined` status deliberately falls back to the pre-disbursement wording: describing a real gate
  is the safe default.
- Verified live on the `UNDER_REPAYMENT` loan that produced the finding: eight warning-tinted
  "Required for disbursement" chips are now eight neutral "Required before disbursement" chips,
  and no false gate claim remains anywhere in the panel.
- Tests: `disbursement-gate.test.ts` pins all ten lifecycle statuses plus the undefined default.

**Deliberately left for a product decision:** this fixes the *tense*, not the underlying question of
why required documents are missing on disbursed loans. Distinguishing "waived" from "never
collected" needs backend data that does not exist today — see F-02's original note.

## Group 1: complete (6 of 6)

## Final gate (Group 1 complete)
`tsc -b` clean · `eslint . --max-warnings 0` clean · `prettier --check` clean ·
**139 test files / 888 tests passing.**

---
# Close-out of the three carried-forward gaps

## ✅ F-33 fixed — delinquency is now carried by text, not colour alone
`components/app/status/statusBadgeMeta.ts`
- `resolveStatusMeta` now returns a delinquency-aware label for `UNDER_REPAYMENT`:
  "Under repayment · 25d past due", falling back to "· N overdue" when DPD is absent but the loan
  is still delinquent, and to the plain label when healthy.
- The tone flip (`success → danger`) was colour; the accessible name was identical for a current
  loan and one 25 days past due. Now colour, icon and text all carry it — the invariant DESIGN.md
  names `StatusBadge` as enforcing.
- Applies everywhere at once: the badge is shared by the ops list, ops detail, borrower loans tab
  and the LSP surface.
- Tests: new cases in `statusBadgeMeta.test.ts` for DPD, overdue-count fallback, and healthy.

## ✅ "Tests locking in defects" — characterised precisely, and it is narrower than it looked
The systemic guard **already exists**: `components/app/lifecycle/actions.test.ts:9` asserts every UI
action's `(from, to)` pair exists in `TRANSITIONS`, making "the UI offers a transition the gate does
not know about" impossible by construction for anything routed through `ActionBar`.
F-16 slipped past it because `/my-loans` **bypassed the catalogue entirely** — it hand-rolled a
button instead of going through `actionsFor` / `canTransition`. So the guard was sound; the surface
opted out of it. F-16's fix routes it back through the gate.
Sampling the other tests that use post-disbursement statuses (`loan-applications/detail-page.test`,
`borrowers/.../LoansTab.test`, `actions.test`) found none asserting affordances on those statuses —
they use the status only as inert fixture data. The four cases hit during remediation shared one
root cause: a shared fixture with a default status, reused by tests that do not care about status
and then relied on by tests that do.
**Recommended follow-up (not done):** make status explicit at the call site in fixtures whose tests
depend on it, rather than inheriting a default.

## ⚠️ F-02 deeper question — ANSWERED, and my earlier statement was wrong
I previously said distinguishing "waived" from "never collected" needs data that does not exist.
**It exists.** `LoanApplicationDocumentChecklistStatus` (backend) is
`{PENDING, SUBMITTED, NOT_REQUIRED}` — `NOT_REQUIRED` *is* the waiver signal.
The frontend discards it, deliberately and documented:
`features/loan-applications/api-tabs.ts:161-175` — *"NOT_REQUIRED surfaces as PENDING in the UI"*,
because `schemas/document.ts` models status as a two-state enum `["PENDING","UPLOADED"]`.

**Consequences, both real:**
1. A document the backend has marked *not required for this loan* renders as
   "Pending · Required before disbursement · Not provided before disbursement." The UI reports a
   compliance gap where the business has recorded a deliberate exemption, and ops chases it.
2. `areRequiredDocumentsComplete` (`api-detail.ts:212-220`) treats `NOT_REQUIRED` as not-complete
   (`isUploadedBackendChecklistStatus("NOT_REQUIRED") === false`), so a loan with a waived required
   document can **never** show "Docs complete". This is a plausible cause of the "Docs incomplete"
   chip seen on disbursed, fully-serviced loans during the audit.

**Not implemented** — this needs a third document state through the schema, adapter, checklist row,
group and the docs-complete predicate, plus a product decision on how a waiver should read
("Not required" vs "Waived") and whether it should count toward completeness. That is a deliberate
change, not a copy tweak, and it should not be started with the remaining session budget.
**Severity** High — it makes the platform overstate compliance gaps on real loans.

## ✅ F-02 deeper question FIXED — `NOT_REQUIRED` is carried through instead of folded away
`schemas/document.ts` · `schemas/loan-application.ts` · `features/loan-applications/api-tabs.ts` ·
`features/loan-applications/api-detail.ts` · `components/app/documents/{documentStatusMeta,
DocumentStatusPill,DocumentChecklistGroup,DocumentChecklistRow}.tsx`

**Decisions taken (and why):**
- **Label "Not required", not "Waived".** It matches the backend enum name and states a fact.
  "Waived" implies an approval action with an actor and a timestamp, none of which the system
  records — the copy would claim more than the data supports.
- **It counts as satisfied for completeness.** A document that does not apply to a loan cannot gate
  that loan's disbursement. `areRequiredDocumentsComplete` now uses a new
  `isSatisfiedBackendChecklistStatus` rather than `isUploadedBackendChecklistStatus`.

**Why two predicates rather than widening one:** "has a file" and "no longer blocking" are genuinely
different questions. `isUploadedBackendChecklistStatus` keeps its narrow meaning (and its existing
test asserting `NOT_REQUIRED` is *not* uploaded stays correct); the new predicate answers the gate
question. Widening the original would have made its name a lie.

**Behaviour now:**
- `safeDocumentStatus` carries `NOT_REQUIRED` through instead of returning `PENDING`.
- The pill reads "Not required" in a neutral tone (new `neutral` tone added to
  `DocumentStatusPillTone`), not amber "Pending".
- The row drops the "Required for disbursement" badge entirely for an exempt document — it is not
  an outstanding requirement — and reads "Not required for this loan — no document is expected."
- `DocumentChecklistGroup` routes it to the read-only row, never the upload affordance.
- A loan carrying a waived required document can now reach "Docs complete".

Tests: `schemas/document.test.ts` covers the satisfied-vs-uploaded distinction in both directions.
Live regression check on a real `UNDER_REPAYMENT` loan (all 8 rows genuinely `PENDING`) confirms the
existing path is unchanged: 8 rows, past-tense heading, neutral badges, no errors.

## Final gate (all carried-forward gaps closed)
`tsc -b` clean · `eslint . --max-warnings 0` clean · `prettier --check` clean ·
**140 test files / 894 tests passing.**

## 🔴 F-02 follow-up — grouping bug found by rendering the full state matrix
**Found by** a direct question about whether every (status × required) combination renders
correctly. Reasoning said yes; rendering the matrix said no.

**The bug:** `DocumentChecklistGroup` split sections on the raw `requiredForDisbursement` flag. A
document type that gates disbursement *in general* but is marked `NOT_REQUIRED` for *this* loan —
which is precisely what a waiver is — therefore rendered **inside the "Required for disbursement"
section while its own pill read "Not required"**. The heading contradicted the row, and it hit
exactly the case the `NOT_REQUIRED` work had just introduced.

**Fix:** group by *effective* gating (`requiredForDisbursement && status !== "NOT_REQUIRED"`), and
relabel the second section from "Optional" to "Not required for this loan", accurate for both
genuinely-optional documents and waived ones. Its empty state now reads "Supporting documents,
and any requirement waived for this loan, appear here."

**Verified matrix** (`DocumentChecklistMatrix.test.tsx`, 5 cases):

| Status | Required flag | Section | Reads as |
|---|---|---|---|
| UPLOADED | true | Required for disbursement | file meta + requirement badge |
| PENDING | true | Required for disbursement | "Pending" + "No file uploaded yet." |
| UPLOADED | false | Not required for this loan | file meta, no requirement badge |
| PENDING | false | Not required for this loan | no requirement badge |
| NOT_REQUIRED | true (waived) | Not required for this loan | "Not required" + "Not required for this loan — no document is expected.", no badge |

**Lesson worth keeping:** the earlier `NOT_REQUIRED` change was verified by unit tests of the
*predicate* and a live regression check of the *unchanged* path. Neither exercised the new
combination end-to-end. A state matrix rendered through the real component caught in one pass what
targeted tests missed.

### Heading wording — second iteration
The first relabel used "Not required for **disbursement**", which *contains* "Required for
disbursement" as a substring. A test caught it as an ambiguous match, but the real problem is on
screen: the two section headings render uppercase and differ only by a leading "NOT", so they scan
almost identically in a column an operator skims. Changed to **"Not required for this loan"** —
distinct at a glance, no substring collision, and it matches the row copy ("Not required for this
loan — no document is expected."). Assertions tightened to exact-match regexes so a future
substring overlap fails loudly rather than matching two elements.

Three pre-existing tests asserted the old "Optional" heading and its empty state; updated. These
were **not** tests locking in a defect — they correctly asserted copy that was then deliberately
changed. Worth distinguishing from the F-16/F-30/F-03 cases, which asserted wrong behaviour.

## Final gate (document state matrix closed)
`tsc -b` clean · `eslint . --max-warnings 0` clean · `prettier --check` clean ·
**141 test files / 899 tests passing.**
