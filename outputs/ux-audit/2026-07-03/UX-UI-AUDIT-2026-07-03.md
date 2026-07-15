# Bhawana LMS — Production UI/UX Audit (2026-07-03)

**Method:** Live walkthrough of the running app (Vite `:5173` + Spring Boot `:8080`) via Chrome DevTools, as `ops.admin` (SYSTEM_ADMIN), with seeded data covering every loan lifecycle state (`scripts/indep-e2e/seed_ux_audit.py`: 22 INITIALIZED + AWAITING_APPROVAL, DISBURSED, DISBURSEMENT_RETRY, UNDER_REPAYMENT, CLOSED, INVALID). Cross-referenced against source for every confirmed finding. Screenshots in `outputs/ux-audit/2026-07-03/`.

**Baseline:** the 2026-06-20 audit (`outputs/ux-audit/UX-UI-AUDIT.md`) + issue tracker. Prior fixes re-verified live and holding: humanized Zod messages (B1), schedule/repayments friendly empty states (A1), humanized activity feed (A2), muted disabled Approve (A3), dd/MM/yyyy DatePickerField on filters (D1), LSP-name report filter (D3), 404-in-shell (E2), mobile card layout on loan applications (supersedes part of E3), bank-account masking (BORROW-01), no mojibake (C1). Settled-by-decision items (PAN/mobile unmasked in lists, no OPS dashboard, LSP origination API-only) were not re-litigated.

Severity: **Critical** = a core flow is impossible / data materially wrong · **High** = data loss, wrong figures, broken action · **Medium** = confusing or incorrect but has workaround · **Low** = polish/consistency.

---

## Findings

### F1 — [Critical] LSP portal users cannot sign in at all
- **Type:** Functional / backend. **Screens:** login → entire LSP workspace (`/my-loans`).
- **Current:** Login POST succeeds (200), then `GET /api/v1/internal/system/context` returns **500** for any principal with an `lspId` claim; the login page dead-ends with "An unexpected error occurred". Reproduced with a freshly created `LSP_UI_WRITE` user. This also explains why the 2026-06-21 tracker could not verify any LSP-surface item ("LSP role login unavailable") — the path has been broken for weeks.
- **Root cause:** `SystemContextService.resolveUserId` (`backend/.../service/SystemContextService.java:20`) is `@Transactional`; the JPA transaction binds a connection **before** the inner `TenantScopedExecution.callAsAdmin` flips the thread-local, and `AuthenticationTenantScopeFilter` has already routed the request to the TENANT datasource — so the `app_user` lookup runs on the tenant-role connection and fails on permissions. Same bug class as the LSP-validation 500s fixed earlier via `AdminScopedTransactionExecutor`.
- **Fix:** use `AdminScopedTransactionExecutor.call(...)` (scope switch before tx acquisition), drop `@Transactional`/`callAsAdmin`. Regression test: `/internal/system/context` with an LSP-scoped JWT returns 200.
- **Blast radius:** every LSP UI user; unblocks LSP-flow verification.
- **Tests:** backend integration test (new), then live LSP sign-in + change-password + /my-loans walkthrough.

### F2 — [High] "Reject" on a loan application is impossible from the UI, and the failure is invisible
- **Type:** Functional + API integration. **Screen:** loan-application detail action bar (`DetailHeader` → `TransitionConfirmDialog`).
- **Current:** The Reject dialog collects only a free-text note and POSTs `{targetStatus: "REJECTED", note}`. Backend (`LoanApplicationLifecycleService.validateTransitionReasonCode`) requires a `reasonCode` for REJECTED → **422 REASON_CODE_REQUIRED every time**. The error renders in the page-level action-bar alert **behind the modal overlay**; inside the dialog there is no feedback and the text field resets. (Mark invalid, which shares the dialog, succeeds — only REJECTED requires a code.)
- **Fix:** in `TransitionConfirmDialog`, when the transition requires a reason code, render a required "Reason" select over `LoanApplicationStatusReasonCode` (6 values, humanized) and send `reasonCode`; surface the mutation error inside the dialog.
- **Blast radius:** core ops credit-decision flow.
- **Tests:** dialog unit test (reason-code required + error-in-dialog), live reject of a seeded AWAITING_APPROVAL loan.

### F3 — [High] List pagination drops records beyond page 1 (loan applications, borrowers)
- **Type:** API integration. **Screens:** `/loan-applications`, `/borrowers`.
- **Current:** Backend lists now return a **bare JSON array + `X-Total-Count`/`X-Limit`/`X-Offset` headers** (post-envelope-removal). `features/loan-applications/api.ts:97` still expects `payload.totalCount` in the body and falls back to `items.length`: with 28 applications the table says "Showing 1–25 of 25", 1/1 pages, next disabled — **rows 26-28 are unreachable**. `features/borrowers/api-list.ts:50` has the same `total: items.length`, plus it only sends `offset/limit` when a page param exists, so page 1 renders **all** rows while the footer claims "1–25 of 28", and page 2's footer degrades to the nonsense "Showing 26–3 of 3".
- **Reference implementation:** `features/my-loans/api.ts:72` already does this correctly (`requestJsonWithHeaders` + `readPaginationHeaders`). Audit uses a body envelope and is also correct.
- **Fix:** adopt the header-based pattern in both APIs; always send pagination params.
- **Blast radius:** any tenant beyond 25 applications/borrowers — i.e. production immediately.
- **Tests:** api unit tests with mocked headers; live check "of 28"/2 pages on both lists.

### F4 — [High] Compact ₹ formatting misstates amounts by up to ~50%
- **Type:** Functional (financial display). **Screens:** Reports KPI row + portfolio preview (AMOUNT, EMI), any `formatINR(..., { compact: true })` caller.
- **Current:** `lib/format.ts:21` defaults `maximumFractionDigits: 0` in compact mode, so ₹1,50,000 renders as **"₹2L"** and the ₹13,503 EMI as "₹14K" on the MIS preview — observed live. Round-half-lakh errors on a finance screen.
- **Fix:** compact mode defaults to 1 fraction digit (₹1.5L, ₹13.5K); explicit `decimals` still wins.
- **Tests:** format unit tests (150000 → "₹1.5L"), live reports re-check.

### F5 — [High] Multi-select status filter silently applies only the first selection
- **Type:** API integration / correctness. **Screen:** `/loan-applications` filter bar.
- **Current:** The "All statuses" chip is multi-selectable and the URL records both (`?status=DISBURSED&status=UNDER_REPAYMENT`), but `backendQueryFromFilters` sends only `filters.status[0]` because the backend accepts a single `status` param — verified live: UNDER_REPAYMENT rows silently missing from results.
- **Fix:** make the loan-applications status filter single-select (matches the backend contract; the LSPs/Users pages already use single Select). Alternative (backend multi-status) deferred as a contract change.
- **Tests:** filter-bar unit test, live re-check.

### F6 — [High] Tall dialogs overflow the viewport with no scroll; footer buttons unreachable (user-reported; reproduced)
- **Type:** UI / layout. **Screens:** New product dialog (worst), any dialog that grows (validation errors, long content) on ≤768px-tall windows.
- **Current:** `components/ui/dialog.tsx` `DialogContent` is centered `fixed` with **no max-height and `overflow: visible`**; Radix locks body scroll. Measured: with validation errors the product dialog is 889px tall in a 674px viewport — clipped at top (−107px) *and* bottom; Cancel/Create sit off-screen and cannot be clicked or scrolled to.
- **Fix:** `max-h-[calc(100dvh-2rem)] overflow-y-auto` on the shared `DialogContent` — one change, every dialog inherits.
- **Tests:** live re-check of product dialog with errors at 674px viewport.

### F7 — [Medium] Source channel always displays "UI"
- **Type:** API integration. **Screen:** loan detail → Overview → "Source channel".
- **Current:** backend sends `sourceChannel: "ONBOARDING_API"`; `api-detail.ts safeChannel()` only recognizes exact "API"/"WEBHOOK" and defaults everything else to "UI". Since origination is API-only, **every** application shows the wrong channel.
- **Fix:** map `ONBOARDING_API` (and API-suffixed values) → "API".
- **Tests:** api-detail unit test, live re-check.

### F8 — [Medium] LSPs table "API clients" column is hardcoded to 0
- **Type:** API integration / fabricated data. **Screen:** `/lsps`.
- **Current:** `features/lsps/api.ts:101` fills `apiClientCount: 0` because the backend directory response has no such field; the table renders it as real data (both live LSPs have 1 client each, column shows 0).
- **Fix:** show the field the backend actually returns — relabel column to "Users" bound to `userCount` (already served). Backend `apiClientCount` aggregation deferred (contract change).
- **Tests:** LspsTable test update, live re-check.

### F9 — [Medium] No self-service "Change password"; login dead-ends on context failure
- **Type:** UX / recovery. **Screens:** account menu (only item: "Sign out"); login page.
- **Current:** `/change-password` exists but is reachable only via the forced flow — and the backend `UserAdminService.completeRequiredPasswordChange` explicitly rejects a change when `mustChangePassword` is false, so a menu link would be a dead affordance. When `system/context` fails after a successful login, the user gets a generic "An unexpected error occurred" with no path forward (observed with F1).
- **Fixed this pass:** the login context-failure now reports "Signed in, but your workspace couldn't be loaded…" (`auth-service.ts`).
- **Deferred:** self-service change-password needs a backend endpoint that verifies the current password first; then add the `UserMenu` entry.

### F10 — [Medium] Borrower "Loans" tab shows unlabeled loan-account status that contradicts application status
- **Type:** UX / data consistency. **Screen:** borrower detail → Loans.
- **Current:** column "STATUS" shows the loan-*account* status ("Disbursed") while the same loan shows "Under repayment" everywhere else.
- **Fix:** map to the application status the rest of the app uses (or label "Account status"). Chosen: application status for consistency.

### F11 — [Low] Native US-format date/datetime inputs remain inside dialogs/panels
- **Type:** Consistency / locale. **Screens:** foreclosure "Quote effective date" (`type=date`, mm/dd/yyyy), repayment "Posted at" (`datetime-local`, mm/dd/yyyy + 12h).
- **Fix now:** foreclosure date → shared `DatePickerField`. Deferred: a datetime variant for "Posted at" (time-of-day matters; needs a design-system datetime component).

### F12 — [Low] Sortable column headers lose their name for screen readers
- **Type:** Accessibility. **Screens:** every sortable table.
- **Current:** `DataTableColumnHeader.tsx:62` sets `aria-label="Not sorted. Activate to sort ascending."`, overriding the visible title — a screen-reader user hears three identical unnamed buttons on `/loan-applications`.
- **Fix:** accessible name = column title; sort state appended as sr-only text.

### F13 — [Low] Nested interactive controls in Products rows
- **Type:** Accessibility / invalid HTML. **Screen:** `/products` — each row is a `<button>` that *contains* the "Actions for X" `<button>`.
- **Fix:** restructure so the row-open control does not wrap the actions menu button.

### F14 — [Low] Row-action buttons lack row context in accessible names
- **Type:** Accessibility. **Screens:** `/lsps` (Details/Status/Audit/Webhook ×N), `/users` (Edit/Reset password/Revoke sessions; only "Disable {user}" is contextualized), `/api-clients` (Edit/Rotate secret).
- **Fix:** include the row identifier in each action's `aria-label`.

### F15 — [Low] Filter search inputs missing `id`/`name`
- **Type:** Accessibility / autofill (Chrome DevTools issue, count 3). **Screen:** loan-applications FilterBar searchboxes.
- **Fix:** add stable ids derived from the label.

### F16 — [Low] Copy nits
- "Per **BR-13**, partial payments…" — internal rule ID surfaced in the repayment dialog.
- IP-allowlist dialog: UI (human-login) section reuses API copy — "The **client** will be reachable from any source IP…"; "Enforce" checkbox is disabled with no explanation (needs "add at least one entry first" hint).
- Activity feed renders an empty "Reference id" label when the event has no correlation id.
- Raw enums in secondary surfaces: repayments status "POSTED", audit events "AWAITING_APPROVAL (from INITIALIZED)", actor "SYSTEM_DISBURSEMENT_WORKER", borrower "GENDER: MALE".

### F17 — [Low] Route-loading fallback fails landmark/heading axe checks
- **Type:** Accessibility (transient). Suspense fallback (`<span class="sr-only">Loading page</span>`) renders outside `main`, with no h1 — axe: `landmark-one-main`, `page-has-heading-one`, `region`.
- **Fix:** wrap `RouteFallback` in a `main` landmark.

### F18 — [Info] Risky affordance: admin can disable / revoke sessions on their own account
- "Disable ops.admin" is enabled on the admin's own row (sole SYSTEM_ADMIN). Not clicked during audit. FE guard added (disable own-row destructive actions); backend enforcement recommended separately.

### F19 — [Info] Duplicate list fetches
- Two identical `GET /internal/ops/loan-applications?...` fired on filter change (reqids 772/774); one wasted round-trip. Dedupe key mismatch suspected; not fixed this pass.

### F20 — [High, deferred] Admin-created users' temporary passwords never force a change
- **Type:** Security / backend, found during LSP-flow verification.
- **Current:** The New-user dialog promises "only the 'must change password' flag remains on the user" — but `UserAdminService.createUser` stores the frontend-minted temporary password as a normal credential with `passwordChangeRequired = false` (only `resetUserPassword` sets the flag, line 252). Verified live: `uxaudit.lsp` logged in with the temp password and was never prompted to change it.
- **Fix (deferred — behavioral change):** `createUser` should mark the initial password change-required (`AppUser.requirePasswordChange`). Deferred because every API-created user (integration scripts, tests) would then hit the 428 forced-change flow on first login — correct for production, but it needs its own reviewed change + test/script updates.

### Environment notes
- Doc-upload rate limiting (429 RATE_LIMIT_EXCEEDED) engaged correctly during seeding; disbursement mock FAILED outcome behaves as a **retryable** technical decline (worker re-disbursed ~30s later) — a permanently-failed disbursement UI state needs the BD-reject IFSC marker to test.
- "Sovereign Ledger" sidebar tagline and breadcrumb "Home > Home" left as branding/product decisions.

---

## What's already good (do not regress)
Feedback system (`EmptyState`/`ErrorState`/`PermissionDeniedState` with correlation IDs), skeletons + aria-live on every async surface, dark/light themes, mobile card layouts + hamburger nav, en-IN currency in full-precision contexts, one-time temp-password reveal flow, schedule table's next-due-only "Record payment" affordance, exact-amount readonly repayment posting (BR-13), audit log filtering/deep links, humanized lifecycle activity feed, friendly pre-approval empty states, IP-allowlist CIDR validation with examples, in-flight button states ("Creating…") preventing duplicate submits, idempotency keys on all writes.

---

# Production UI design checklist

**Data integrity**
- [ ] Every paginated list reads `X-Total-Count`/`X-Limit`/`X-Offset` via `readPaginationHeaders` (pattern: `features/my-loans/api.ts`); never derive totals from `items.length`.
- [ ] Never render a field the API doesn't return; no hardcoded fallbacks presented as data.
- [ ] Compact currency keeps ≥1 fraction digit (`₹1.5L`); full precision (`₹1,50,000`) in ledgers/schedules.
- [ ] Enum mappings are exhaustive with a visible fallback (show raw value over silently wrong default).
- [ ] A filter control never offers more capability than the API honors.

**Dialogs & forms**
- [ ] `DialogContent` caps at viewport height and scrolls internally; footer actions always reachable.
- [ ] Mutation errors render inside the open dialog, not behind the overlay.
- [ ] Every backend-required field is collected by the form that triggers it (reason codes!).
- [ ] Buttons disable + show progress label during submission (in place — keep).
- [ ] All date inputs use the shared `DatePickerField` (dd/MM/yyyy display, ISO emit).

**Accessibility**
- [ ] Interactive controls never nest (`button` in `button`).
- [ ] Repeated row actions carry row context in their accessible names.
- [ ] Sortable headers keep the column name as accessible name; sort state is supplementary.
- [ ] Form fields have `id`/`name`; loading fallbacks live inside a `main` landmark.

**Recovery & self-service**
- [ ] Sign-in failure modes have specific messages and a retry path.
- [ ] Change-password reachable from the account menu, not only via forced flows.
- [ ] Destructive self-actions (disable own account) are guarded.

**Copy**
- [ ] No internal codes (BR-13), raw enums, or API-audience copy on human surfaces.
- [ ] Disabled controls state why they're disabled (hint or tooltip).

---

## Remediation record (what was actually changed)

**Backend**
- `service/SystemContextService.java` — F1: `AdminScopedTransactionExecutor.call` replaces `@Transactional` + inner `callAsAdmin` (admin scope now active before the connection is acquired). Regression test added in `web/AuthControllerTest.resolveUserIdSucceedsWhileThreadIsTenantScoped` (note: the H2 test profile cannot reproduce the Postgres role-permission failure — live verification is the authoritative check, and it passed).

**Frontend — API integration**
- `features/loan-applications/api.ts` — F3: `requestJsonWithHeaders` + `readPaginationHeaders` (X-Total-Count); dead body-envelope type removed.
- `features/borrowers/api-list.ts` — F3: same header pattern; pagination params always sent (fixes both the phantom-full-list page 1 and the "Showing 26–3 of 3" footer).
- `features/loan-applications/api-detail.ts` — F7: `safeChannel` recognises `ONBOARDING_API`; transition body now carries `reasonCode`; SYSTEM_ADMIN `manual-status` fallback sends `MANUAL_ADMIN_OVERRIDE` instead of the invalid `"OTHER"` enum value (latent 400).
- `features/lsps/{types,api}.ts`, `components/LspsTable.tsx` — F8: fabricated `apiClientCount: 0` removed; column is now "Users" bound to the served `userCount`.
- `features/my-loans/api.ts` — LSP docs checklist: backend→frontend document-type normalisation (PAN_CARD/AADHAAR_FILE/SELFIE_PHOTOGRAPH uploads showed as missing slots).
- `features/auth/auth-service.ts` — F9: login failure after a successful credential check now says the workspace couldn't be loaded instead of "An unexpected error occurred".

**Frontend — lifecycle / Reject flow (F2)**
- `components/app/lifecycle/actions.ts` — `requiresReasonCode` on `LifecycleAction` (REJECTED, DISBURSEMENT_RETRY targets).
- `components/app/lifecycle/reason-codes.ts` (new) — humanized `LoanApplicationStatusReasonCode` catalog.
- `components/app/lifecycle/schema.ts` — `lifecycleDialogSchema` factory (reason + conditional reason code).
- `components/app/lifecycle/TransitionConfirmDialog.tsx` — reason-code Select; `errorMessage` rendered inside the dialog.
- `components/app/lifecycle/ActionBar.tsx` — keeps the dialog open on failure and passes the error into it; forwards `reasonCode`.
- `features/loan-applications/components/DetailHeader.tsx`, `types.ts` — `reasonCode` plumbed into the mutation input.

**Frontend — design system / a11y**
- `components/ui/dialog.tsx` — F6: `max-h-[calc(100dvh-2rem)] overflow-y-auto` on `DialogContent` (every dialog inherits; product form verified at 674px with validation errors).
- `lib/format.ts` — F4: compact INR keeps ≥1 fraction digit (₹1.5L, ₹13.5K).
- `components/app/data/DataTable.tsx` — F13: interactive rows keep `role=row` (no more button-in-button); keyboard activation retained.
- `components/app/data/DataTableColumnHeader.tsx` — F12: column name stays the accessible name; sort state is sr-only text.
- `components/app/data/EntityRowActions.tsx` — `disabled`/`disabledTitle` per item; `components/app/data/FilterBarShell.tsx` — search inputs get `id`/`name`.
- `features/users/components/UsersTable.tsx` — F14/F18: row-context aria-labels; self-disable guarded with an explanatory title.
- `features/lsps/components/LspsTable.tsx`, `features/api-clients/components/ApiClientsTable.tsx` — F14: row context in action labels.
- `routes/route-fallback.tsx` (+ `guards.tsx`, `landing-redirect.tsx`) — F17: standalone fallbacks render a `main` + sr-only h1; in-shell fallback stays a `role=status` div (landmarks must not nest).
- `features/loan-applications/components/LoanApplicationsFilterBar.tsx` — F5: status filter single-select (mirrors the single `status` API param); ids on loan-ID inputs.
- `features/loan-applications/components/ForeclosureQuotePanel.tsx` — F11: quote date uses `DatePickerField` (dd/MM/yyyy).

**Frontend — copy / presentation**
- `components/app/repayment/RepaymentPostDialog.tsx` — "Per BR-13" removed.
- `features/api-clients/components/IpAllowListEditor.tsx` + `features/lsps/components/LspIpAllowlistDialog.tsx` — surface-appropriate empty-state copy; "add an entry first" hint on the disabled Enforce checkbox.
- `components/app/audit/AuditEventNode.tsx` — "Reference id" hidden when the event has no correlation id.
- `features/borrowers/components/tabs/LoansTab.tsx` — F10: column labelled "Account status".
- `features/my-loans/components/MaskedBorrowerCard.tsx` — "masked everywhere" claim corrected ("Borrower identity"; Aadhaar-masked wording).

**Tests updated/added**: `AuthControllerTest` (new regression), `TransitionConfirmDialog.test.tsx` (reason-code flow + in-dialog error, fixtures), `ActionBar.test.tsx` (reject flow with code), `TransitionDisabledTooltip.test.tsx` (fixtures), `LoanApplicationsFilterBar.test.tsx` (single-select), `LoanApplicationsTable.test.tsx` + `DataTable.test.tsx` (sort-name/row-role locators), `UsersTable.test.tsx` (session mock).

**Live verification (Chrome DevTools, both roles)**: LSP sign-in + /my-loans + detail (docs checklist, identity copy); admin reject flow end-to-end (Fodder Borrower 22 → Rejected with FAILED_VERIFICATION); loan-apps "1–25 of 28"/page 2; borrowers page 1+2 footers; reports ₹1.5L/₹13.5K; LSPs Users column; source channel "API"; product dialog scroll containment at 674px; users self-disable guard; sort-header names; IP-allowlist copy; foreclosure date picker; repayment dialog copy.

## Fix plan (this pass)
Backend: F1 (+ integration test). Frontend: F2, F3, F4, F5, F6, F7, F8, F9 (login message only), F10, F11 (foreclosure date), F12, F13, F14, F15, F16 (copy), F17, F18 (FE guard).
Deferred: self-service change-password (needs a backend current-password-verified endpoint), backend `apiClientCount` aggregation, backend multi-status list param, datetime design-system component ("Posted at"), F19 dedupe, audit presets (ADMIN-02), remaining raw-enum humanization on audit/alerts admin surfaces.
