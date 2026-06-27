# Bhawana LMS — UX / UI Audit

**Date:** 2026-06-20
**Branch:** fix/tracker-residuals
**Method:** Live walkthrough of the running app (Vite `:5173` + Spring Boot `:8080`, signed in as `ops.admin` / SYSTEM_ADMIN) using Chrome DevTools, cross-referenced against frontend and backend source. Screenshots in `outputs/ux-audit/shots/`.
**Scope covered:** Login, Home, Loan applications (list + detail: Overview/Schedule/Documents/Repayments/Activity), Borrowers, Alerts, Reports, LSPs (+ New LSP form), Products, Users, API clients, Audit log, 404, dark mode, mobile (390px). Backend `GlobalExceptionHandler` and the shared FE form/format/feedback layers.

> **Note — do not change yet.** This is an audit only. The remediation plan at the end is for review.

---

## Overall assessment

The product is **substantially more polished than a typical pre-production build**. It has a proper feedback system (`EmptyState` / `ErrorState` / `PermissionDeniedState` with correlation-ID copy, retry, and role context), skeleton loaders on every async surface, a clean dark mode, responsive nav (hamburger + stacked cards on mobile), accessibility scaffolding (skip-to-content, `role="alert"`, `aria-live` regions, labelled icon buttons), Indian-locale currency formatting (`₹1,50,000`), and a genuinely good backend error handler (typed error codes, a safe catch-all that never leaks stack traces or SQL).

The issues that remain are mostly **surface polish and "last-mile" finishing** rather than structural. But several of them are the kind a customer notices in the first five minutes and reads as "unfinished" — corrupted characters in headings, raw developer error text with internal UUIDs, raw Zod validation strings, dead toolbar buttons, and a date that says "57 years ago." Those are the priority.

---

## Findings (grouped by area)

Severity scale: **High** = looks broken / unreliable / unsuitable for prod · **Medium** = noticeably unpolished or confusing · **Low** = polish / consistency.

---

### A. Loan application detail

#### A1 — [High] Schedule & Repayments tabs show a raw backend error (with internal UUID) for pre-approval loans
- **Where:** `/loan-applications/:id?tab=schedule` and `?tab=repayments`, for any application not yet approved (e.g. `AWAITING_APPROVAL`). Shot: `05b-schedule-error.png`.
- **What happens:** The tab renders a red, `aria-live="assertive"` alert:
  *"Couldn't load repayment schedule — Loan account is not available for application id: e2663945-f3a2-48de-9be6-ae70cfbec2c1"* with a "Try again" button. Identical on Repayments.
- **Why it's bad:** This is an **expected** state (no loan account exists until approval — the Overview tab itself shows "Schedule missing" as a normal gate), but it's presented as a technical failure, leaks the internal application UUID, and offers a "Try again" that can never succeed. It reads as a bug.
- **Root cause:** `ScheduleTab.tsx` has good empty/awaiting states, but they only trigger for `APPROVED_PENDING_DISBURSAL`. For earlier statuses the schedule query *errors*, so it falls through to the generic `ErrorState` and echoes `query.error.message` (the backend's raw `ResourceNotFoundException` message, which embeds the UUID — see `GlobalExceptionHandler` passing `exception.getMessage()` through).
- **Fix:** Treat "no loan account yet" as an empty state for all pre-disbursement statuses (e.g. "No repayment schedule yet — generated once the loan is approved"). Either branch on status before the error branch in `ScheduleTab`/`RepaymentsTab`, or have the backend return `200` with an empty schedule for pre-account applications. Never surface a raw UUID in body copy.

#### A2 — [Low] Activity feed shows raw enum constants while the Audit log humanizes them
- **Where:** loan detail → Activity tab. Shot: `06-activity-tab.png`.
- **What:** Events render as `INITIALIZED → AWAITING_APPROVAL`, `STATUS_TRANSITION`, plus a truncated raw UUID. The standalone **Audit log** humanizes the *same* events ("Document access", "Viewed 8 KYC document placeholders") — so the app already knows how to do this.
- **Fix:** Map status/event enums to human labels in the Activity timeline as the audit page does.

#### A3 — [Low] Disabled "Approve" looks enabled, and the reason it's blocked is far from the button
- **Where:** loan detail action bar. Shots: `03b-loan-detail-loaded.png`, `05b-schedule-error.png`.
- **What:** The disabled **Approve** button is still bright green (reads as clickable). The reasons it's disabled ("Docs incomplete", "Schedule missing") live in the "Lifecycle gates" card at the bottom of the page and the right rail — not adjacent to the button.
- **Fix:** Strengthen the disabled visual state, and attach a tooltip on the disabled Approve summarizing the blocking gates (a `TransitionDisabledTooltip` component already exists — wire it to the disabled Approve).

---

### B. Forms & validation

#### B1 — [High] Raw Zod default messages are shown to end users
- **Where:** New LSP dialog (and any form using the shared resolver). Shot: `11-lsp-form-validation.png`.
- **What:** Submitting empty fields shows *"String must contain at least 2 character(s)"* (Code) and *"String must contain at least 1 character(s)"* (Display name), under a generic *"There are errors in this form"* summary.
- **Why it's bad:** "String must contain at least N character(s)" is developer/library language. It's unprofessional and unclear ("Display name is required" is what a user needs).
- **Fix:** Give each Zod field a human message (`z.string().min(2, "Enter a code (2–16 characters)")`) or install a global Zod error map. The field hint text ("Uppercase letters, digits…") is good and should be the validation message too.

> The backend's validation responses are fine — `GlobalExceptionHandler` returns typed `VALIDATION_FAILED` with per-field messages and humanizes enum/type-mismatch errors. This is purely the FE Zod layer.

---

### C. Copy, characters & terminology

#### C1 — [High] Repo-wide character-encoding corruption (mojibake) in UI copy
- **Where:** Visible on **Users** subtitle ("Internal and LSP users **â€"** manage roles…" — shot `13-users.png`) and **API clients** subtitle ("…under Administration **â†'** LSPs" — shot `14-api-clients.png`). Also baked into rendered strings in `ReportRequestsTable.tsx` (date-range `${from} â†' ${to}`, empty-value `"â€""`, button label `"Openingâ€¦"`).
- **Scale:** **58 occurrences across 12 `.tsx` files** (api-clients, users, reports + ReportRequestsTable, products create/edit/mapping dialogs, disbursement dialog, repayment dialog, audit), plus more in `.ts`. Em-dash (—), right-arrow (→), ellipsis (…) and section-sign (§) are all corrupted into `â€"`, `â†'`, `â€¦`, `Â§`.
- **Why it's bad:** Garbled characters in page headings/labels are one of the most obvious "this is broken/unfinished" signals.
- **Fix:** Re-save the affected files as UTF-8 (the corruption is the classic UTF-8-decoded-as-Latin-1 pattern) or sweep-replace the corrupted sequences with the correct glyphs. Add a CI/lint guard (and editorconfig `charset = utf-8`) to prevent regressions.

#### C2 — [Medium] "Loan Service Provider" vs canonical "Lending Service Provider"
- **Where:** LSPs page subtitle, LSPs empty-state, New LSP dialog description (`lsps/page.tsx`, `LspCreateDialog.tsx`).
- **What:** Copy says "Loan Service Provider"; `CONTEXT.md` defines the term canonically as **"Lending Service Provider"**.
- **Fix:** Align all three strings to "Lending Service Provider".

#### C3 — [Low] Cryptic abbreviation "BHAW LOAN ID" / "Bhaw Loan ID"
- **Where:** Loan applications table column + filter placeholder. Shot: `02b-loan-applications-loaded.png`.
- **Fix:** "Bhawana loan ID" (or full word + tooltip).

#### C4 — [Low] Internal term "placeholders" surfaced to users
- **Where:** Audit log event copy: "Viewed 8 KYC document **placeholders**." Shot: `15b-audit-loaded.png`.
- **Fix:** "Viewed 8 KYC documents".

---

### D. Controls, consistency & localization

#### D1 — [Medium] Date inputs are native browser pickers in US `mm/dd/yyyy` format
- **Where:** Loan applications (disbursal range), Reports (disbursed from/to), Audit (from/to). Shots: `02b`, `09-reports.png`, `15-audit.png`.
- **Why it's bad:** This is an INR / India product; `mm/dd/yyyy` is the wrong locale and is ambiguous to Indian users (who expect `dd/mm/yyyy`). Native pickers also don't match the design system's styling.
- **Fix:** Adopt one styled date-picker component with `en-IN` formatting across all date filters.

#### D2 — [Medium] Status filtering uses three different UI patterns; one filter bar mixes native and custom selects
- **Where:** Status filter is a **segmented control** on Products, a **dropdown** on LSPs/Users, and **tabs** on Alerts/API clients. On the **Loan applications** filter bar, "All statuses" is a custom dropdown while the adjacent "All LSPs"/"All products" are native `<select>` elements — visibly different (chevron vs double-arrow, different padding/typography). Shots: `02b`, `08b`, `10-lsps.png`, `12-products.png`.
- **Fix:** Standardize on the design-system `Select`/segmented pattern; replace remaining native `<select>`s so adjacent controls match.

#### D3 — [Medium] Reports LSP filter requires pasting a raw UUID
- **Where:** Reports → LSP filter placeholder: "All LSPs (paste a UUID to scope)". Shot: `09-reports.png`.
- **Why it's bad:** Asking a user to find and paste a tenant UUID is hostile; every other screen offers an LSP **name** dropdown.
- **Fix:** Replace with an LSP name typeahead/dropdown (reuse the existing `lsp-options` source).

#### D4 — [Medium] Reports "Portfolio preview" table overflows horizontally; empty state mispositioned
- **Where:** Reports → Portfolio preview (17+ columns: Loan ID, Borrower, Account, LSP code, LSP, Product code, Product, Amount, Status, Applied, Disbursed, DPD, EMI, Overdue, Bucket, External ID, Year…). Shot: `09-reports.png`.
- **What:** The table runs off the right edge of the viewport; the "No rows" empty state is pushed to the far right (following the wide layout) instead of centered.
- **Fix:** Constrain in a horizontal-scroll container with a centered empty state; consider a column-visibility toggle / prioritized default columns.

---

### E. Top bar, navigation & shells

#### E1 — [Medium] Dead controls: the Notifications bell and Help button do nothing
- **Where:** Every authenticated page (TopBar). Confirmed clicking each produces no panel, navigation, or feedback. `TopBar.tsx` (lines ~96–115) renders both buttons with **no `onClick`**.
- **Why it's bad:** Prominent, interactive-looking controls that silently do nothing read as unfinished.
- **Fix:** Implement them, or remove until ready (or wire Help to docs and the bell to the alerts surface). At minimum give honest feedback.

#### E2 — [Low] 404 page drops the entire app shell
- **Where:** any unknown route while authenticated. Shot: `17-404.png`.
- **What:** "Page not found" renders on a bare white page — no sidebar, no top bar — with only "Back to home" as an escape. Copy itself is good.
- **Fix:** Render not-found inside the authenticated layout so navigation persists.

#### E3 — [Low] Data tables aren't mobile-optimized
- **Where:** All list tables at phone width. Shot: `20-mobile-table.png`.
- **What:** Tables horizontally scroll showing ~3 columns; important columns (Status, Amount) are off-screen. The Help button is also dropped on mobile.
- **Fix:** Responsive card layout or freeze/prioritize key columns at small widths.

---

### F. Auth / login

#### F1 — [Medium] Dev scaffolding exposed on the login screen; role quick-fill is broken when unconfigured
- **Where:** `/login`. Shot: `16-login.png`.
- **What:** A "Sign in by role" panel lists all five role types with one-click credential-fill and instructions to "Configure usernames and passwords in `.env.local`." In this environment **every** role button fails identically with a toast: *"No user exists for this role."*
- **Why it's bad:** This is a developer affordance. On a production build it exposes the role taxonomy + config internals and presents five buttons that all fail.
- **Fix:** Gate the entire panel behind `import.meta.env.DEV` so it never ships in production bundles.

---

### G. PII & data presentation

#### G1 — [Medium] Inconsistent PII masking (Aadhaar masked; PAN & mobile shown in full)
- **Where:** Borrowers directory list and loan-detail Borrower panel. Shots: `07-borrowers.png`, `03b-loan-detail-loaded.png`.
- **What:** Aadhaar is masked (`XXXXXXXX5027`) but **PAN** (`ABCDE5027F`) and full **mobile** render unmasked in a list view. `lib/format.ts` states "masking is the only presentation of PII" and ships a `maskAccount()` helper, yet PAN/mobile (and, per the tracked bug, bank account) aren't masked.
- **Fix:** Decide and apply one masking policy consistently. If ops genuinely needs full PII, make reveal explicit and audited (a "PII reveal" audit stream already exists).

#### G2 — [Medium] "Created 57 years ago" — implausible relative date for the bootstrap user
- **Where:** Users table, `ops.admin` row. Shot: `13-users.png`.
- **Root cause:** `formatRelative()` (`lib/format.ts`) calls `formatDistanceToNowStrict` with no guard; a seed/bootstrap user whose `createdAt` is epoch/zero renders as "57 years ago" (≈ 2026 − 1970).
- **Fix:** Guard the formatter for epoch/invalid/implausible timestamps (fall back to an absolute date or "—"), and fix the seed `createdAt`.

---

### H. Environment / data hygiene (informational)

#### H1 — [Info] Pervasive obvious test data
- Everything is seeded with "Doc Borrower", "Doc Product", "Doc Upload Test", a 619-byte `pan.pdf`, etc. Fine for dev, but confirm the production seeding/cleanup story so a customer demo never shows this.

---

## What's already good (don't regress these)
- `EmptyState` / `ErrorState` / `PermissionDeniedState` — correlation-ID copy, retry, role context, `role="alert"`.
- Skeleton loaders on every async surface; `aria-live` status regions for screen readers.
- Dark mode (proper surfaces/contrast); responsive nav (hamburger, stacked cards).
- Backend `GlobalExceptionHandler` — typed error codes, humanized enum/type errors, safe catch-all ("An unexpected error occurred"), no stack/SQL leakage.
- INR formatting in `en-IN` (Indian digit grouping).
- Audit log — humanized events, copyable correlation IDs, deep links, rich filtering.
- Documents checklist — clear per-document status and required-for-disbursement grouping.

---

## Ranked remediation plan

| # | Finding | Severity | Effort | Why this rank |
|---|---------|----------|--------|---------------|
| 1 | **C1** Mojibake in UI copy (58 hits, incl. visible headings) | High | Low | Most visible "broken" signal; mechanical fix |
| 2 | **A1** Raw backend error + UUID on Schedule/Repayments | High | Low–Med | Looks like a bug on a core workflow; leaks internal ID |
| 3 | **B1** Raw Zod validation messages | High | Low | Every form; reads as unfinished |
| 4 | **E1** Dead Notifications/Help buttons | Medium | Low | Prominent dead controls; implement or hide |
| 5 | **F1** Dev login scaffolding in prod build | Medium | Low | Unprofessional + leaks role taxonomy; gate on DEV |
| 6 | **G2** "57 years ago" date | Medium | Low | Reads as a data bug; one formatter guard |
| 7 | **D1** US `mm/dd/yyyy` date inputs | Medium | Med | Wrong locale for INR/India product |
| 8 | **D3** Reports LSP filter = paste-a-UUID | Medium | Med | Hostile filter; reuse existing LSP options |
| 9 | **G1** PII masking inconsistency (PAN/mobile) | Medium | Med | Compliance + consistency; needs a policy decision |
| 10 | **C2** "Loan/Lending Service Provider" terminology | Medium | Low | 3 strings; align to CONTEXT.md |
| 11 | **D4** Reports portfolio-preview overflow + empty state | Medium | Med | Layout polish on a key report |
| 12 | **D2** Inconsistent status-filter / select controls | Medium | Med | Design-system consistency pass |
| 13 | **A3** Disabled-Approve affordance + tooltip | Low | Low | Wire existing tooltip; dim disabled state |
| 14 | **A2** Activity feed raw enums | Low | Low | Reuse audit-log humanization |
| 15 | **E2** 404 drops app shell | Low | Low | Keep nav on not-found |
| 16 | **E3** Tables not mobile-optimized | Low | Med | Card layout / column priority |
| 17 | **C3 / C4** "BHAW LOAN ID", "placeholders" copy | Low | Low | Wording |
| 18 | **H1** Test-data hygiene | Info | — | Confirm prod seeding story |

**Suggested first sweep (1–2 days, all Low effort, high perceived impact):** C1, B1, E1, F1, G2, C2, A3, A2, C3/C4 — these collectively remove almost every "this looks unfinished" signal. Then tackle A1 (workflow correctness), D1/D3/G1 (locale, filters, PII policy), and the consistency/layout items.
