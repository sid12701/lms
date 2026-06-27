# Bhawana LMS — UX/UI Issue Tracker (merged register)

**Updated:** 2026-06-21 (agent pass — RPT-02 legacy MIS + tracker fixes; masking deferred)
**Sources:** (1) the area-prefixed audit register (BORROW-/RPT-/API-/LSP-/SHELL-/AUTH-/HOME-/LOAN-/DOC-/ALERT-/ADMIN-/NAV-/A11Y-); (2) Claude's independent A1–H1 walkthrough (`UX-UI-AUDIT.md`). Where both found the same thing, IDs are cross-referenced on one row.

**Status legend:**
✅ Fixed & verified · 🟢 Fixed (code/gate, not live-verified) · ⏳ In progress · 🔁 Reverted by decision · 🟦 Settled — no change (by decision) · ⏸ Deferred · ❓ Needs re-check · ⬜ Open (not started) · ℹ️ Informational

> **Verification caveat:** Claude's live pass ran **as `ops.admin` only** (LSP role login was unavailable). Items on LSP surfaces (`/my-loans`, LSP cards, LSP-only flows) and backend failure-path items were **reported from your register but NOT independently re-verified** this session — marked ⬜ Open with a note.

---

## Summary

| Severity | Total | ✅/🟢 Fixed | ⏳ In progress | 🔁/🟦/⏸ Decision | ❓ Re-check | ⬜ Open | ℹ️ Info |
|---|---|---|---|---|---|---|---|
| Critical | 5 | 3 | 0 | 0 | 0 | 1 | 0 |
| High | 9 | 7 | 0 | 0 | 0 | 2 | 0 |
| Medium | 20 | 15 | 0 | 1 | 0 | 3 | 0 |
| Low / Info | 4 | 2 | 0 | 0 | 0 | 1 | 1 |
| **Claude A1–H1 pass** | 20 | 15 | 0 | 3 | 0 | 0 | 1 (+1 dup) |

---

## 🔴 Critical / ship-blockers

| ID | Area | Issue (detail) | Status | Notes / cross-ref |
|---|---|---|---|---|
| **BORROW-01** | Borrowers | Full **bank account number shown unmasked** in Profile tab; backend places cleartext into the `bankAccountNumberMasked` field. PII/regulatory exposure. | ⏸ Deferred | Masking policy deferred this pass. |
| **RPT-02** | Reports / finance | Legacy loans (`processing_fee_amount IS NULL`): MIS showed calculator fee/net though borrower received full principal. | ✅ Fixed & verified | `AdminReportingService.resolveProcessingFeeAmount` returns **0** for null persisted fee; ADR 0004 §4 updated. Unit test `AdminReportingProcessingFeeTest`. |
| **API-03** | Backend → UX | LSP validation endpoints return **500 instead of structured 422**. | 🟢 Fixed | Already returns 422 via `BusinessRuleViolationException` + `AdminScopedTransactionExecutor`; integration tests pass (`Issue62`, `LspLoanApplicationApiControllerTest`). |
| **LSP-01** | LSP workspace | `/my-loans/new` 404; broken "Submit new loan" affordance. | 🟢 Fixed | Submit card **disabled** with "API-only origination" tooltip; `LspLinkCardGrid` mounted on `/my-loans`. |
| **SHELL-01** | Shell | Command palette empty state reads **"Search lands in Phase 8."** | 🟢 Fixed | Neutral empty copy in `CommandPalette.tsx`. |

---

## 🟠 High

| ID | Area | Issue (detail) | Status | Notes / cross-ref |
|---|---|---|---|---|
| **AUTH-04** | Auth / guards | Permission-denied surfaces **raw role enums**. | 🟢 Fixed | `formatRoleLabel` / `formatPermissionDeniedDescription` in guards + `PermissionDeniedState`. |
| **SHELL-03** | User menu | Disabled **"Profile (Phase 8)"** menu item shipped. | 🟢 Fixed | Removed from `UserMenu.tsx`. |
| **SHELL-04** | Top bar | Role-scope badge falls back to hardcoded **"Bhawana Demo LSP"**. | 🟢 Fixed | Uses `session.user.lspName` from system context; neutral "LSP workspace" fallback. |
| **HOME-02** | Home | Orphaned LSP components (`LspLinkCardGrid`, `LspKpiSummary`). | 🟢 Fixed (partial) | `LspLinkCardGrid` on `/my-loans`; `LspKpiSummary` still unwired. |
| **LSP-02** | My loans | Bare HTML table, raw status strings. | 🟢 Fixed (partial) | `StatusBadge` on list; filters/pagination still open (E3 deferred). |
| **LOAN-01** | Loan detail | Transition toasts/errors pass **backend messages / enum-style status text**. | 🟢 Fixed | `mapApiErrorMessage` + `formatLoanStatusLabel` in `DetailHeader`. |
| **LOAN-04** | Loan detail | Foreclosure quote/request dialog unwired. | ⬜ Open (partial) | Settle-foreclosure lifecycle action exists; `ForeclosureRequestDialog` still unwired. |
| **API-01** | API errors | `IllegalArgumentException` passthrough → technical messages. | 🟢 Fixed | Friendly mapping in `GlobalExceptionHandler` + `mapApiErrorMessage`. |
| **API-02** | API errors | Backend Jakarta validation defaults reach the UI. | 🟢 Fixed | `friendlyValidationMessage` in `GlobalExceptionHandler`. |

---

## 🟡 Medium

| ID | Area | Issue (detail) | Status | Notes / cross-ref |
|---|---|---|---|---|
| **AUTH-02** | Session | Blank loading states on auth guard / landing redirect. | 🟢 Fixed | `RouteFallback` in `RequireAuth` + `landing-redirect`. |
| **AUTH-03** | Change password | No password-policy copy aligned with backend. | 🟢 Fixed | Min **12** chars + description on `ChangePasswordPage` (matches `AuthController`). |
| **SHELL-05** | Sidebar | Footer shows the **raw `session.user.role` enum**. | 🟢 Fixed | `formatRoleLabel` in `Sidebar.tsx`. |
| **LSP-03** | LSP home cards | "Help & docs" permanently **"Coming soon"**. | ⬜ Open | Still disabled by design; not a broken link. |
| **LSP-04** | My loan detail | Mark-invalid reasons load failure swallowed. | 🟢 Fixed | `reasonsLoadError` surfaced in `MarkInvalidDialog`. |
| **HOME-01** | Home | OPS / PRODUCT_ADMIN have **no dashboard**. | 🟦 Settled | Product decision — unchanged. |
| **LOAN-02** | Loan detail header | **Full application UUID** in page description. | 🟢 Fixed | `shortId()` in `DetailHeader`. |
| **LOAN-03** | Loan applications list | Product filter from **current page** only. | 🟢 Fixed | Loads from `listProducts()` catalog. |
| **LOAN-05** | Loan detail | `maskName()` dead code. | 🟢 Fixed | Removed (masking deferred). |
| **LOAN-06** | Loan detail | OPS_USER only sees "Escalate" — no onboarding. | 🟢 Fixed | Explainer copy already in `DetailHeader` ops bar. |
| **BORROW-02** | Borrowers | **Mobile in cleartext** on Profile tab. | ⏸ Deferred | Masking policy deferred this pass. |
| **DOC-02** | Documents | Download errors toast **raw API messages**. | 🟢 Fixed | `mapApiErrorMessage` in `DocumentsTab`. |
| **ALERT-01** | Alerts | `LOAN_ACCOUNT` alerts link to audit log, not loan application. | 🟢 Fixed | `resolveAlertSubjectHref` reads `contextJson.applicationId`. |
| **ADMIN-02** | Audit | Explorer expert-only, no presets. | ⬜ Open | Out of scope this pass. |
| **NAV-01** | 404 | "Back to home" always goes to `/home`. | 🟢 Fixed | Role-aware `defaultLandingFor()` in `not-found.tsx`. |
| **NAV-02** | Guards | Silent redirect when LSP hits internal routes. | 🟢 Fixed | `PermissionDeniedState` in `RequireInternal` / `RequireLsp`. |
| **API-05** | Auth | HTTP **428** not handled in `http-client`. | 🟢 Fixed | Redirect to `/change-password` on 428. |
| **API-04** | API errors | `correlationId` not parsed from API errors. | 🔁 Reverted by decision | Kept reverted per prior call. |
| **A11Y-02** | Responsive | `/my-loans` & `/borrowers` not in responsive-overflow E2E matrix. | 🟢 Fixed | `e2e/responsive.spec.ts` — borrowers (admin), my-loans (LSP read via `signInAsLspUser`). |
| **DOC-01** | Documents | Dead `DocumentPreviewSheet`. | 🟢 Fixed | Sheet + test removed. |

---

## ⚪ Low / Informational

| ID | Area | Issue (detail) | Status | Notes |
|---|---|---|---|---|
| **A11Y-03** | LSP cards | Disabled card uses `role="link"` + `tabIndex={0}`. | 🟢 Fixed | Disabled cards use `role="group"` + `tabIndex={-1}`. |
| **DOC-01** (low) | Documents | Legacy preview sheet duplication. | 🟢 Fixed | Same as DOC-01 above. |
| **H1** | Environment | Pervasive obvious test data ("Doc Borrower", 619-byte `pan.pdf`). | ℹ️ Info | Confirm prod seeding/cleanup story. |

---

## Claude A1–H1 pass — status of work done this session

Cross-referenced to your IDs where they overlap.

| Claude ID | Area | Issue | Status | Your-ID xref / notes |
|---|---|---|---|---|
| **C1** | Copy | Repo-wide mojibake (`â€"`/`â†'`/`…`/`§`) in UI copy | ✅ Fixed & verified | = **RPT-03**, **ADMIN-01**. 152 fixes / 26 files, 0 residual. |
| **A1** | Loan detail | Schedule/Repayments raw NOT_FOUND error + leaked UUID | ✅ Fixed & verified | NOT_FOUND → friendly empty; genuine errors keep raw msg (your call). ≠ LOAN-01. |
| **A2** | Loan detail | Activity feed raw enums | ✅ Fixed & verified | Humanized in `AuditEventNode.summarize()`. |
| **A3** | Loan detail | Disabled "Approve" looked enabled | ✅ Fixed & verified | Muted disabled tone. |
| **B1** | Forms | Raw Zod validation messages (frontend) | 🟢 Fixed | Global `z.setErrorMap`. **Backend copy = API-02 (open).** |
| **C2** | Copy | "Loan" → "Lending" Service Provider | ✅ Fixed & verified | 3 strings. |
| **C3** | Copy | "BHAW LOAN ID" → "Bhawana loan ID" | ✅ Fixed & verified | |
| **C4** | Copy | Audit "…document placeholders" → "…documents" | 🟢 Fixed | Backend copy + test. |
| **D1** | Locale | US `mm/dd/yyyy` date inputs | ✅ Fixed & verified | `DatePickerField` (`dd/MM/yyyy` display, ISO emit) on loan-apps/reports/audit; filter-bar tests pass. |
| **D2** | Controls | Inconsistent filter controls | ✅ Fixed & verified | Unified loan-apps + borrowers filters. |
| **D3** | Reports | LSP filter = paste-a-UUID | ✅ Fixed & verified | = **RPT-01**. Now a name dropdown. |
| **D4** | Reports | Portfolio table overflow + mispositioned empty state | ✅ Fixed & verified | Centered empty + scroll container. |
| **E1** | Top bar | Dead Notifications/Help buttons | ✅ Fixed & verified | Bell→/alerts, Help popover. |
| **E2** | Nav | 404 dropped the app shell | ✅ Fixed | Catch-all inside auth layout. **Back-to-home target still wrong = NAV-01 (open).** |
| **E3** | Responsive | Tables not mobile-optimized | ⏸ Deferred | Your decision. Related: A11Y-02, LSP-02. |
| **F1** | Auth | Dev "Sign in by role" panel in prod | 🟢 Fixed | Gated behind `import.meta.env.DEV`. |
| **G1** | PII | PAN/mobile unmasked in directory | 🟦 Settled (keep unmasked) | Your decision. **Bank acct = BORROW-01 (open), profile mobile = BORROW-02 (open).** |
| **G2** | Data | "Created 57 years ago" | 🟢 Fixed | `formatRelative` guard. |
| **H1** | Env | Test data hygiene | ℹ️ Info | |
| **—** | Cross-cut | Mojibake hardening | ✅ Fixed | `.editorconfig` charset + `check:encoding` CI guard. |
| **—** | Cleanup | Unused `maskPan`/`maskMobile` | ✅ Removed | After G1 kept-unmasked. |

---

## Notes on this register
- **RPT-02** resolved: legacy MIS reports **zero** fee when `processing_fee_amount` is null (no calculator fiction).
- **Distinct-not-duplicate pairs:** A1≠LOAN-01, E2≠NAV-01, G1≠BORROW-01/02, B1≠API-02, D3=RPT-01, C1=RPT-03/ADMIN-01.
- **Not independently verified this session** (LSP surface / backend failure paths): LSP-01/02/03/04, HOME-02, SHELL-04, AUTH-04, API-01/02/03/05, ALERT-01, A11Y-02/03. Reported faithfully from your register.
- 10 seeded loans (`external_loan_id LIKE 'SEED-%'`) are **retained** — do not clean up.
