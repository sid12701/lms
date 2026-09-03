# Wave 7 — Operations & Audit: Bank-Grade Read-Only Audit

**Specs reviewed:** `manual-status-override-and-lifecycle-transitions`, `audit-explorer`, `loan-and-borrower-search`, `portfolio-dashboard-and-home-kpis`, `operations-alerts-subsystem`  
**Code baseline:** `/Users/siddhant/Desktop/lms` (current worktree)  
**Focus areas:** SoD of manual override · audit completeness vs money paths · PII in search · KPI correctness · alert workflow

---

## §1 — Executive Summary

Wave 7 implements a credible ops control plane: lifecycle mutations are `SYSTEM_ADMIN`-only, every status change flows through `LoanApplicationStatusWriter` (transition + audit + webhook in one transaction), the unified audit explorer covers eight streams with keyset paging, and alerts provide a workable NEW→ACKNOWLEDGED inbox.

**Bank-grade gaps concentrate in four themes:**

| Theme | Verdict |
|-------|---------|
| **SoD / break-glass** | OPS is correctly blocked from lifecycle mutation; **single-actor override with no maker-checker** remains the dominant control weakness. |
| **Audit vs money paths** | Repayments and disbursement outcomes are auditable; **bank-detail changes, webhook redrives, and auth events are outside the unified explorer**. |
| **PII in search** | Aadhaar/bank account masked; **PAN/mobile/email exposed cleartext to OPS_USER with no reveal audit**. |
| **KPIs & alerts** | Backend math is mostly sound but **UI mislabels all-time disbursed as MTD and DPD90+ as “overdue loans”**; alerts inbox **does not server-page** and silently operates on ~50 rows. |

**Finding count (High+):** 12 High · 0 Critical (no evidence of bypassable money mutation by OPS or missing transactional audit on status writer path).

---

## §2 — Scope & Methodology

Read-only trace of specs → controllers/services → frontend surfaces → persistence. Evidence paths include:

- `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanApplicationLifecycleService.java`
- `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanApplicationStatusWriter.java`
- `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/AuditExplorerService.java`
- `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/repo/AuditExplorerRepository.java`
- `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/LoanApplicationOpsResponses.java`
- `/Users/siddhant/Desktop/lms/frontend/src/features/alerts/api.ts`
- `/Users/siddhant/Desktop/lms/frontend/src/features/home/api.ts`

---

## §3 — Manual Override: Segregation of Duties

### Implemented controls (aligned)

- `POST …/status-transitions` and `POST …/manual-status` are `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` on `LoanApplicationOpsController`.
- `OPS_USER` sees escalation UI only (`DetailHeader.tsx`); backend tests confirm 403 on transition and manual-status (`opsUserCannotManuallyTransitionStatus`, `opsUserCannotUseManualStatusUpdate`).
- Manual override guards block servicing sources/targets and require note + reason code (`LoanApplicationLifecycleService.manuallyOverrideStatus`).

### Gaps

| ID | Sev | Finding |
|----|-----|---------|
| **W7-F01** | **HIGH** | **No maker-checker / dual control.** One `SYSTEM_ADMIN` can execute manual override, approve, disburse, and acknowledge the resulting `MANUAL_RULE_ENGINE_OVERRIDE` alert. Spec G-010 explicitly documents this gap; no step-up, second approver, or approval queue exists. |
| **W7-F02** | **HIGH** | **Break-glass is implicit in UI.** `api-detail.ts` falls back from `status-transitions` to `/manual-status` on 400/403 for admins — no dedicated override form, no extra confirmation. Operators may not realize they invoked override vs standard transition. |
| **W7-F03** | **HIGH** | **Override to `DISBURSEMENT_RETRY` without account repair.** Allowed from early statuses; spec G-008 notes missing loan-account/schedule repair. Downstream disbursement may fail or behave inconsistently — money-path operational risk after override. |

**SoD verdict:** OPS↔ADMIN separation is **sound**; ADMIN self-approval on override is **not bank-grade**.

---

## §4 — Manual Override: Lifecycle, Audit & Idempotency

### Implemented (aligned)

- Shared writer records `loan_application_status_transition` + `loan_application_audit_event` (`MANUAL_STATUS_OVERRIDE` vs `STATUS_TRANSITION`) and enqueues `LOAN_STATUS_CHANGED` webhook atomically.
- `recordManualRuleEngineOverride` appends rule-engine context to note and emits `MANUAL_RULE_ENGINE_OVERRIDE` alert.
- Optional `Idempotency-Key` supported when header supplied.

### Gaps

| ID | Sev | Finding |
|----|-----|---------|
| **W7-F04** | **HIGH** | **Cannot reset `APPROVED_PENDING_DISBURSAL`.** `blocksManualOverrideSource()` includes that status (spec G-009). Pre-disbursement stuck loans require standard transition paths, not override — limits exception handling for approved-but-not-disbursed cases. |
| **W7-F05** | MED | **Idempotency optional.** Without `Idempotency-Key`, retries rely on state-machine guards only (spec G-004). |
| **W7-F06** | MED | **Note length mismatch.** Frontend allows up to 1000 chars; backend DB/API caps at 500 (spec G-005). |
| **W7-F07** | MED | **Alert conflation.** Standard approval to `APPROVED_PENDING_DISBURSAL` emits same `MANUAL_RULE_ENGINE_OVERRIDE` type as true manual override (spec G-007), diluting alert signal for triage. |

---

## §5 — Audit Explorer: Coverage Model

### Implemented (aligned)

- `SYSTEM_ADMIN`-only `GET /api/v1/internal/admin/audit-events` with eight streams: APPLICATION, INTAKE, DOCUMENT_ACCESS, PRODUCT, APP_USER, API_CLIENT, DISBURSEMENT, REPORT_ACCESS.
- Keyset cursor on `(occurred_at DESC, stream DESC, native_id DESC)`; 7-day default window, 90-day max.
- Intake Aadhaar fields masked in `AuditExplorerService` before response.

### Gaps

| ID | Sev | Finding |
|----|-----|---------|
| **W7-F08** | **HIGH** | **Persisted money/security audits excluded from unified explorer:** `borrower_bank_details_update_audit`, `webhook_outbox_redrive_audit`, `auth_event_audit`, `lsp_audit_event`, `borrower_pii_reveal_audit`. Spec documents each; only auth has a separate endpoint with no frontend surface. |
| **W7-F09** | MED | **Frontend `PII_REVEAL` tab is a false stream.** Selecting only `PII_REVEAL` omits `streams` param → backend returns **all** streams (spec EC-010). Misleading for investigators. |
| **W7-F10** | MED | **Detail sheet is page-local only.** No per-event fetch; deep links to `eventId` outside loaded page fail (spec G-011). |
| **W7-F11** | MED | **Backend filters (LSP, borrower, product) not exposed in UI** (spec G-008). |

---

## §6 — Audit Explorer: Money-Path Completeness

| Money-adjacent action | Persisted audit | In unified explorer? |
|----------------------|-----------------|----------------------|
| Status transition / manual override | `loan_application_audit_event` | ✅ APPLICATION |
| Repayment posting | `PAYMENT_RECORDED` via status writer | ✅ APPLICATION |
| Disbursement outcome | `disbursement_outcome_audit` | ✅ DISBURSEMENT |
| Foreclosure execution | `FORECLOSURE_EXECUTED` | ✅ APPLICATION |
| Borrower bank detail update | `borrower_bank_details_update_audit` | ❌ |
| Webhook redrive | `webhook_outbox_redrive_audit` | ❌ |
| Report/MIS download | `report_access_audit` | ✅ REPORT_ACCESS |

| ID | Sev | Finding |
|----|-----|---------|
| **W7-F12** | **HIGH** | **Bank-detail change trail invisible in `/audit`.** `BorrowerBankDetailsService` writes `borrower_bank_details_update_audit`, but `AuditExplorerRepository` has no branch for it. Fraud/disbursement-fraud investigations cannot correlate bank changes with disbursement/repayment from one explorer. |
| **W7-F13** | **HIGH** | **Webhook redrive audit excluded.** Partner integration recovery actions are not searchable in the primary audit surface — gap for money-adjacent integration controls. |

**D9 verdict:** Mutation paths are mostly auditable at persistence layer; **unified explorer is incomplete for money-path forensics**.

---

## §7 — Audit Explorer: PII & Sensitive Detail Exposure

| Stream | Redaction |
|--------|-----------|
| INTAKE | Aadhaar field names masked |
| APP_USER | Full `beforeState`/`afterState` JSON returned (no password hash — `UserAuditSnapshot` excludes it) |
| API_CLIENT | Full `details` JSON |
| DISBURSEMENT / REPORT_ACCESS | Actor IP, provider IDs exposed |

| ID | Sev | Finding |
|----|-----|---------|
| **W7-F14** | MED | **No universal redaction policy** beyond intake Aadhaar (spec G-012). APP_USER/API_CLIENT/REPORT_ACCESS may expose operational metadata suitable for insider threat review but not uniformly governed. |

---

## §8 — Loan & Borrower Search: PII Exposure

### Implemented (aligned)

- Aadhaar masked on borrower admin responses (`BorrowerAdminController.maskAadhar`).
- Bank account masked as `XXXXXXXX<last4>` on borrower detail.
- LSP routes tenant-scoped; internal routes limited to `SYSTEM_ADMIN` / `OPS_USER`.
- Search reads do not write audit rows or webhooks (by design).

### Gaps

| ID | Sev | Finding |
|----|-----|---------|
| **W7-F15** | **HIGH** | **Cleartext PAN, mobile, email on internal loan list/detail** (`LoanApplicationOpsResponses.toResponse` lines 47–50). Same for borrower directory. `loan_application_pii_reveal_audit` table exists but **no writer/reader** — spec FR-011 / SC-006 **not met**. |
| **W7-F16** | **HIGH** | **OPS_USER receives full PII on search surfaces** without role-differentiated masking or reveal workflow. Bank-grade norm: ops sees masked identifiers; reveal is audited and admin-gated. |
| **W7-F17** | MED | **Free-text `q` matches PAN and mobile** in `LoanApplicationReadRepository` — broad exposure surface for shoulder-surfing and log leakage. |

---

## §9 — Loan & Borrower Search: Access & Operational Fit

| Control | Status |
|---------|--------|
| Cross-tenant internal search | ✅ By design (D8) |
| LSP isolation on `/my-loans` | ✅ |
| `PRODUCT_ADMIN` route access | ❌ Excluded (spec G-006 — documented gap) |
| Search read audit | ❌ Not implemented (spec G-009) |
| Backend sort on loan list | ❌ Frontend sort state ignored (spec G-001) |

No additional High+ findings in this section.

---

## §10 — Portfolio KPIs: Calculation Correctness

### Implemented (aligned)

- Heavy aggregates from `portfolio_kpi_snapshot` (15-min worker, advisory lock 42109).
- DPD buckets from oldest overdue installment due date vs `BusinessCalendar.today()`.
- `applicationsAwaitingApproval` / `applicationsInDisbursement` derived from snapshot status JSON.
- Open alerts count + top-5 summaries are **live** reads.
- `SYSTEM_ADMIN`-only API; non-admins redirected from `/home`.

### Gaps

| ID | Sev | Finding |
|----|-----|---------|
| **W7-F18** | **HIGH** | **“MTD disbursed” label is wrong.** `InternalKpiSummary.tsx` labels `mtdDisbursedAmount`; `HomeDashboardService` / snapshot compute **all-time** disbursed principal (`disbursedAt != null`). Spec G-001. Regulatory/ops misinterpretation risk. |
| **W7-F19** | **HIGH** | **“Overdue loans” shows only DPD_90_PLUS.** Frontend maps `dpd90PlusLoanCount` / `dpd90PlusAmount` to “Overdue loans” tile; excludes DPD 1–90. Spec G-002. Material understatement of early delinquency. |
| **W7-F20** | MED | **`borrowerNameMasked` field is unmasked full name** (`HomeDashboardService.toRecentApplication` uses `getFullName()`). Spec G-006. |

---

## §11 — Portfolio KPIs: Staleness & Presentation

| ID | Sev | Finding |
|----|-----|---------|
| **W7-F21** | **HIGH** | **Headline KPIs can lag up to ~15 minutes** (snapshot refresh) while open alerts/recent apps are live. Backend exposes `dataAsOf`; **frontend drops it** in `mapBackendHomeOverviewToInternalKpis` — operators cannot see snapshot age. Spec G-010. |
| **W7-F22** | MED | **Unused backend payloads:** `lspBreakdown`, `priorityAccounts`, `applicationsByStatus` returned but not rendered (spec G-003–G-005). |
| **W7-F23** | MED | **Frontend silently maps unknown DPD buckets / alert severities to defaults** — can hide contract drift (spec G-011). |

---

## §12 — Operations Alerts: Lifecycle & SoD

### Implemented (aligned)

- Persisted `ops_alert` with NEW → ACKNOWLEDGED lifecycle.
- Event + scheduled producers; duplicate suppression via `createAlertIfAbsent` where designed.
- `OPS_USER_ESCALATION` creates new alert (does not mutate existing).
- Acknowledgement records actor, timestamp, optional note; rejects double-ack.
- Scheduled worker uses admin scope + advisory lock (42110).

### Gaps

| ID | Sev | Finding |
|----|-----|---------|
| **W7-F24** | **HIGH** | **No separate acknowledgement audit stream.** Ack mutates `ops_alert` row in place; no immutable ack history (spec G-008, D9 partial). Same actor who triggered `MANUAL_RULE_ENGINE_OVERRIDE` can acknowledge it — weak SoD on alert closure. |
| **W7-F25** | MED | **No resolved/closed/snooze/assign states** (spec G-001). Acknowledged is terminal; cannot distinguish handled vs dismissed. |
| **W7-F26** | MED | **Escalate API allows `SYSTEM_ADMIN`** though UI is OPS-focused — minor; not a control failure. |

---

## §13 — Operations Alerts: Triage at Scale

| ID | Sev | Finding |
|----|-----|---------|
| **W7-F27** | **HIGH** | **Alerts inbox does not server-page.** `frontend/src/features/alerts/api.ts` calls `GET /alerts` with **status only** — backend defaults to **50 rows** (`PaginationResponseBuilder.DEFAULT_LIMIT`). Client then filters by severity/subject/search and paginates in-memory via `paginate()`. Alerts outside the newest 50 are **invisible** to filters and counts. Spec G-012. |
| **W7-F28** | MED | **Controller `limit` capped at 200** vs spec max 1000 — secondary to W7-F27 since frontend never passes pagination params. |
| **W7-F29** | MED | **Duplicate alert storms** on identity conflict, LSP disable, manual override, ops escalation (`createAlert` always inserts). By design but noisy at scale (spec G-006). |

---

## §14 — Cross-Cutting: D8/D9/D10 Compliance

| Decision | Wave 7 posture |
|----------|----------------|
| **D8 Tenant isolation** | ✅ Internal cross-tenant views admin-gated; LSP search scoped. |
| **D9 Every action auditable** | ⚠️ Mutations mostly audited; **unified explorer + search reads + dashboard reads + alert ack history** incomplete for compliance-grade “single pane.” |
| **D10 No borrower self-service** | ✅ No borrower access to override, audit, search, KPIs, or alerts. |

---

## §15 — Findings Register (High+ Priority)

| ID | Severity | Area | Finding | Spec ref | Primary evidence |
|----|----------|------|---------|----------|------------------|
| **W7-F01** | HIGH | Override SoD | No maker-checker on manual override | G-010 | `LoanApplicationLifecycleService`, spec |
| **W7-F02** | HIGH | Override UX | Implicit manual-status fallback obscures break-glass | G-003 | `frontend/.../api-detail.ts:440–470` |
| **W7-F03** | HIGH | Override / money | `DISBURSEMENT_RETRY` override without account repair | G-008 | `LoanApplicationStatus.java`, lifecycle service |
| **W7-F04** | HIGH | Override scope | Cannot override from `APPROVED_PENDING_DISBURSAL` | G-009 | `LoanApplicationStatus.blocksManualOverrideSource()` |
| **W7-F08** | HIGH | Audit coverage | Five persisted audit tables outside unified explorer | audit-explorer §Persisted…Not Included | `AuditExplorerQuery.AuditStream` enum |
| **W7-F12** | HIGH | Audit / money | Bank-detail update audit not searchable | audit-explorer G-004 | `BorrowerBankDetailsService`, no repo branch |
| **W7-F13** | HIGH | Audit / integration | Webhook redrive audit not searchable | audit-explorer G-005 | `WebhookOutboxRedriveAudit` entity |
| **W7-F15** | HIGH | Search PII | Cleartext PAN/mobile/email on loan/borrower reads | search G-005, SC-006 | `LoanApplicationOpsResponses.java:47–50` |
| **W7-F16** | HIGH | Search PII / SoD | OPS_USER gets full PII without reveal audit | search FR-011, NFR-003 | Role guards + response mappers |
| **W7-F18** | HIGH | KPI correctness | UI “MTD disbursed” = all-time backend total | KPI G-001 | `InternalKpiSummary.tsx`, `HomeDashboardService` |
| **W7-F19** | HIGH | KPI correctness | “Overdue loans” = DPD90+ only | KPI G-002 | `frontend/.../home/api.ts:142–144` |
| **W7-F21** | HIGH | KPI staleness | Snapshot lag; `dataAsOf` not shown in UI | KPI G-010 | `mapBackendHomeOverviewToInternalKpis` |
| **W7-F24** | HIGH | Alert SoD | No immutable ack audit; same actor can close override alerts | alerts G-008 | `OpsAlertService.acknowledge` |
| **W7-F27** | HIGH | Alert workflow | Inbox operates on ~50 newest rows; client-side filter/page | alerts G-012 | `frontend/.../alerts/api.ts:97–122` |

### Controls operating as designed (no High+ finding)

- OPS_USER blocked from lifecycle mutation (backend + UI + tests).
- Status writer transactional audit + webhook enqueue.
- Audit explorer admin-only with intake Aadhaar masking.
- Alert creation, dedup for scheduled rules, escalation as new alert.
- LSP tenant scoping on partner loan search.

### Recommended remediation priority

1. **W7-F27** — Wire server pagination + filters on alerts API (blocks ops at scale today).
2. **W7-F01 / W7-F02** — Explicit override UI + dual control or step-up for override/disbursement.
3. **W7-F12 / W7-F08** — Extend unified explorer with bank-detail + webhook-redrive (+ auth) streams.
4. **W7-F15 / W7-F16** — Mask PAN/mobile in search; gated reveal with audit writer.
5. **W7-F18 / W7-F19 / W7-F21** — Fix KPI labels; surface `dataAsOf`; clarify overdue vs PAR buckets.

[REDACTED]