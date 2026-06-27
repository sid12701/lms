# UX Remediation — Locked Decisions & Verification Checklist

Cursor implements; **Claude verifies each issue once done**. For each: the chosen approach, **acceptance criteria** (the bar I'll check), and how I'll verify.

Done already (this session): **C1 mojibake** ✅ · **A1 schedule/repayments** ✅ (friendly empty on NOT_FOUND; raw message kept on genuine errors).

Verify method legend: **[code]** diff review · **[test]** vitest/typecheck/build · **[live]** running-app screenshot (needs Vite; some need backend too).

---

### 3 · B1 — Friendly validation messages  → global `z.setErrorMap`
- **Accept:** No raw Zod strings ("String must contain at least N character(s)", "Required" as the only text) anywhere; empty required fields read like "X is required" / "Must be at least N characters". Error map installed once at app startup; applies to all forms.
- **Verify:** [code] map module + `main.tsx` wiring · [test] a form-validation unit test asserts friendly copy · [live] submit empty LSP/User/Product dialog.

### 4 · E1 — Wire Notifications + Help
- **Accept:** Bell navigates to `/alerts` (and is hidden/disabled for roles without /alerts access); Help opens a real popover (keyboard shortcuts). No dead onClick-less buttons remain. Both keyboard-focusable with correct aria.
- **Verify:** [code] `TopBar.tsx` · [live] click both; bell routes, Help opens.

### 5 · F1 — DEV-gate the "Sign in by role" panel
- **Accept:** Panel renders in `dev`, absent from production build output (`import.meta.env.DEV`). Login form unaffected.
- **Verify:** [code] `LoginPage.tsx` guard · [test] `vite build` + grep dist for "Sign in by role" (should be absent).

### 6 · G2 — Robust relative dates
- **Accept:** Epoch/implausible/invalid timestamps no longer render "57 years ago" — they fall back to absolute date or "—". Seed/bootstrap user `createdAt` fixed at source. Normal dates unchanged ("2 hours ago").
- **Verify:** [code] `lib/format.ts` guard (+ backend seed) · [test] formatRelative unit test for epoch/invalid · [live] Users page row.

### 7 · D1 — dd/mm/yyyy dates
- **Accept:** `<html lang="en-IN">`; native date pickers render dd/mm/yyyy. Currency/`Intl` unaffected.
- **Verify:** [code] `index.html` · [live] Loan-apps/Reports/Audit date inputs show dd/mm/yyyy.

### 8 · D3 — Reports LSP dropdown
- **Accept:** LSP filter is a name dropdown (reusing existing LSP-options), not a paste-a-UUID text field; selecting scopes the report; clear works.
- **Verify:** [code] `ReportsFilterBar.tsx` · [live] Reports filter shows LSP names.

### 9 · G1 — PII masking: **KEEP AS-IS (no change)**
- **Accept:** No masking behavior changed. (Conscious decision.) Bank-account-unmasked remains a *separate* tracked bug — not in this batch.
- **Verify:** [code] confirm Cursor introduced **no** masking changes; flag any as out-of-scope.

### 10 · C2 — "Lending Service Provider"
- **Accept:** All user-facing "Loan Service Provider" → "Lending Service Provider" (LSPs subtitle, empty-state, New-LSP dialog).
- **Verify:** [code] grep shows zero "Loan Service Provider" in `src` · [live] LSPs page.

### 11 · D4 — Reports table overflow
- **Accept:** Portfolio-preview table scrolls horizontally within a contained region; the empty ("No rows") state is centered in the viewport, not pushed right. No page-level horizontal scrollbar.
- **Verify:** [code] table wrapper · [live] Reports empty + populated.

### 12 · D2 — Unify Loan-apps + Borrowers filter controls
- **Accept:** Within those two filter bars, status/LSP/product selects all use the same design-system control (no native-`<select>`-next-to-custom-dropdown mismatch). Other pages untouched. Keyboard/aria intact.
- **Verify:** [code] `LoanApplicationsFilterBar.tsx` + borrowers filter · [live] side-by-side controls match.

### 13 · A3 — Disabled "Approve" affordance
- **Accept:** Disabled Approve reads as inactive (no bright/clickable green); existing hover/focus tooltip still states the blocking reason. Enabled state unchanged.
- **Verify:** [code] `ActionBar.tsx` tone · [live] awaiting-approval loan: Approve looks disabled + tooltip on hover.

### 14 · A2 — Humanize Activity feed
- **Accept:** No raw enums in the Activity timeline — `INITIALIZED → AWAITING_APPROVAL` becomes human labels; action like `STATUS_TRANSITION` reads "Status transition" (or is dropped). Consistent with the /audit log.
- **Verify:** [code] `AuditEventNode.summarize()` · [test] node unit test · [live] loan Activity tab.

### 15 · E2 — 404 keeps the shell
- **Accept:** A bad URL while authenticated renders inside the app shell (sidebar + topbar present) with working nav; unauthenticated still gets the bare page. Copy unchanged.
- **Verify:** [code] `router.tsx` (catch-all inside `AuthenticatedLayout`) + `not-found.tsx` · [live] bad URL while logged in.

### 16 · E3 — Responsive card layout for tables (HEAVY)
- **Accept:** At mobile breakpoints, the main list tables (loan-applications, borrowers, users, lsps, products, api-clients, alerts) present as stacked cards (or equivalent) showing the key fields — Status/Amount no longer off-screen; no horizontal scroll needed for primary info; tap targets adequate; desktop layout unchanged.
- **Verify:** [code] `DataTable.tsx` + list pages · [test] responsive-qa test if present · [live] 390px screenshots of each main list.

### 17 · C3/C4 — Copy nits
- **Accept:** "BHAW LOAN ID"/"Bhaw Loan ID" → "Bhawana loan ID" (column + filter); audit "…document placeholders" → "…documents".
- **Verify:** [code] grep · [live] loan-apps table + audit.

### 18 · H1 — Test data — informational, no code change expected.

### Cross-cutting · Mojibake hardening
- **Accept:** `.editorconfig` has `charset = utf-8`; a CI step fails on reintroduced mojibake (e.g. `scratch/fix_mojibake.py --check` or a grep guard).
- **Verify:** [code] `.editorconfig` + CI workflow · [test] run the guard against current tree (should pass).

---

## Global gate for the whole batch
- `npm run verify` (typecheck + lint + format:check + test + build) green.
- Re-screenshot the originally-flagged screens (servers back up) and confirm each finding is visibly resolved.
- No scope creep: PII masking (G1) untouched; only listed pages changed.
