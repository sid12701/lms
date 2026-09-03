# Wave 8 — Target-State Gaps Audit (Read-Only)

**Scope:** `three-way-ledger-and-reconciliation`, `ckyc-reporting-and-sftp-submission`, `dwh-read-interface-and-reporting-boundary` vs LMS codebase + `docs/deferred-implementation.md`, sequenced against W5 money findings.  
**Baseline:** HEAD worktree (2026-07-30); specs cite `2269d064` (2026-07-16) — operational MIS masking has moved since DWH spec was written.  
**Verdict:** Gap documentation is **mostly honest**; severity calibration and build sequencing need tightening to avoid treating Analyst Draft target specs as imminent P0 blockers for mock UAT, while not understating P0/P1 gates for real-money and collections-SoR cutover.

---

## §1 — Executive verdict (W8-F01)

**W8-F01 — Gap docs are honest on absence; severity is context-dependent, not uniform Critical.**

| Artifact | Honesty | Caveat |
|---|---|---|
| Three-way ledger spec | ✅ Accurate “zero capability” | Must not be read as substituting for deferred **S13** |
| CKYC spec | ✅ Accurate “zero LMS capability” | Local desktop scripts cited but **not in LMS repo** — reference-only claim unverifiable here |
| DWH boundary spec | ✅ Accurate CDC absence | **G-6 partially stale** on MIS PAN/bank masking (see W8-F11) |
| `deferred-implementation.md` | ✅ Honest on S13/S5/S6/S14 | Silent on CKYC and three-way recon — correct (not deferred, simply unbuilt) |
| `DELIVERABLE-1-INVENTORY.md` §6.1 | ✅ Matches `grep` evidence | Aligns with code |

Under **mock/pre-prod posture** (W5 conclusion): W8 items are **observations + roadmap**, not Critical for real funds. Under **live ICICI + collections SoR**: W5 P0 stack (S5→S6→S14→S13) precedes three-way recon; CKYC is **P1 onboarding** (W5-A15-R07), not a disbursement-rail blocker unless product gates it.

---

## §2 — Three-way ledger: implementation surface (W8-F02)

**W8-F02 — Entire three-way recon platform is absent; operational money tables are not a subledger.**

Verified absence:
- No `ledger_*`, `reconciliation_*`, `bank_reconciliation_*`, or `lsp_recon_*` migrations/tables.
- No ingest workers, matching engine, exception dashboard, or adjustment-approval flow.
- `grep -ril ckyc\|ledger\|reconciliation` over `backend/src/main/java` → disbursement “ledger” comments only; no financial ledger domain.

What **does** exist (feeds future shadow mode, not recon):
- `loan_disbursement_request_log` + V98 ICICI-shaped fields (`payment_mode`, `bank_rrn`, `act_code`, etc.) — provider attempt evidence, not bank-statement ingest.
- `loan_payment_transaction` (V21) — single-row operational payment log, not double-entry.
- `loan_account` / `loan_repayment_schedule_installment` — servicing projections.

Spec claim at lines 23–34 (“Missing capability: No immutable ledger entries…”) is **verified true**.

---

## §3 — Three-way ledger vs deferred S13 (W8-F03)

**W8-F03 — Conflating three-way recon (W8) with receipt ledger (S13) overstates W8 urgency and understates S13.**

| Layer | Deferred ID | What it is | W8 spec overlap |
|---|---|---|---|
| **S13 / MNY-02** | `deferred-implementation.md` L43–54 | Receipt, allocation, suspense, reversal; collections book | Supplies **LMS-leg** postings three-way recon would match |
| **Three-way recon** | W8 Analyst Draft | LMS ledger + bank ingest + LSP ingest + matching + exceptions | Assumes a **trustworthy LMS financial record** already exists (spec US1, FR-001) |

W5-A15-R01 correctly notes RBI expects reconciled books but **does not mandate a named “three-way recon LMS module.”** W5 remediation sequence places three-way recon at **60–90d after S13** — this is the honest sequencing.

**Gap-doc honesty issue:** Architecture audit (§8.3) calls three-way recon “launch dependency for authoritative money accounting.” That is true for **collections SoR**, but `deferred-implementation.md` defers only S13, not W8 — readers may infer W8 is similarly “approved deferred” when it is simply **unscheduled target state**.

---

## §4 — Three-way ledger: bank/LSP ingest prerequisites (W8-F04)

**W8-F04 — Bank-side ingest has no code path; LSP recon contract `bhawana-lsp-recon-v1` is spec-only.**

Concrete blockers before any matching run:
1. **Unresolved business decisions** (spec table L286–295): chart of accounts, authoritative bank source (MIS vs statement), matching tolerances, adjustment authority, historical backfill window.
2. **No configuration** (spec L264): “intentionally assigns no defaults until bank and finance controls are agreed.”
3. **ICICI integration is mock-only** (W5-F02, S17 not built) — bank file format for production recon unknown.
4. **`bhawana-lsp-recon-v1` CSV** — zero references in LMS codebase.

Honest readiness criterion for **shadow recon start**: S13 ledger posting events emitting idempotent balanced transactions **plus** at least one agreed bank file fixture and one LSP file fixture with checksum dedup (spec EC-001/002, FR-006).

---

## §5 — Three-way ledger: shadow-mode design honesty (W8-F05)

**W8-F05 — Shadow-mode assumption is sound and honestly documented; W5 operational gaps poison shadow inputs.**

Spec correctly states (L245–246, NFR-006): operational tables remain live loan-action source; recon must not silently mutate balances (FR-012).

**W5 inputs that would corrupt shadow recon if started today:**

| W5 finding | Shadow recon impact |
|---|---|
| W5-F01 Admin initiate skips preflight | LMS disbursement leg may not match bank evidence |
| W5-F05 Mock outcome doesn't terminalize intent | LMS vs provider state divergence |
| W5-F07 Foreclosure vs concurrent EMI | Duplicate/conflicting LMS payment events |
| W5-F09 No S13 receipt ledger | **No LMS leg for partial/bunch/bounce/reversal** — matching would be structurally incomplete |

Shadow recon is **honest as a design** but **dishonest as a near-term milestone** until W5 containment + S13 land.

---

## §6 — CKYC: LMS implementation surface (W8-F06)

**W8-F06 — CKYC is 0% implemented in LMS; KYC foundation is presence-only.**

Verified:
- `grep -ril ckyc` → **0 files** in `backend/` and `frontend/`.
- No `ckyc_batch`, `ckyc_transfer_attempt`, SFTP client, or response parser.
- No admin UI routes for CKYC lifecycle.

Implemented foundations CKYC would consume (spec “Implemented today” L25–31 — **verified**):
- Document upload + checklist (`loan_application_document_checklist`, access audit).
- Borrower identity fields (PAN, Aadhaar-derived, demographics).
- Async report worker pattern (`ReportRequestProcessingWorker`, R2 storage) — reusable for batch artifacts.

W4-ORIGINATION correctly routes CKYC to W8 with status **Missing** (O5 Analyst Draft).

---

## §7 — CKYC spec honesty & external dependencies (W8-F07)

**W8-F07 — CKYC spec is honest about unknowns; “local scripts as reference” is unverified in-repo.**

Honest elements:
- Explicitly excludes browser automation, DSC USB, Gmail extraction from LMS runtime (Out of Scope L62–66).
- Settings section (L242–244): no SFTP config, institution id, or host-key fingerprint — **true**.
- Ten unresolved business decisions (L265–276) including integration channel (SFTP vs portal), gate timing, credit impact.

**Unverified claim:** “Local desktop CKYC automation scripts exist” (L19) — no `*ckyc*` artifacts found under `/Users/siddhant/Desktop/lms`. Scripts may live outside repo; spec should not be read as “half-implemented.”

**Regulatory framing (W5-A15-R07):** CKYCR upload/retrieve is **mandatory** under KYC MD for in-scope customers — **P1 onboarding compliance**, not a disbursement-rail control like S14.

---

## §8 — CKYC go-live readiness criteria (W8-F08)

**W8-F08 — CKYC go-live requires compliance inputs before engineering, not the reverse.**

Concrete readiness gates (derived from spec SC-001–008 + unresolved decisions):

| Gate | Owner | Evidence required |
|---|---|---|
| G1 Official file format + naming | Compliance | Signed CKYC spec version, not script reverse-engineering |
| G2 SFTP endpoint + host-key pin | Infra/Compliance | Test-server upload with fail-closed mismatch (EC-007) |
| G3 Institution id + sequence policy | Compliance | Registry of batch sequences, no duplicate remote submission (FR-012, NFR-001) |
| G4 Mandatory document set for new-record | Compliance | Maps to existing checklist doc types |
| G5 Gate timing decision | Product/Risk | Whether CKYC blocks `APPROVED_PENDING_DISBURSAL` — **currently unset** (spec L230) |
| G6 Maker/checker model | Compliance | LMS-only vs portal-only vs both (FR-015) |
| G7 PII reveal policy | Privacy | Align with deferred S15 D3 matrix |

**Pilot without CKYC:** Possible for synthetic UAT if product explicitly accepts document-presence KYC (W4 assessment). **Regulated origination at scale:** P1 blocker per W5-A15-R07.

---

## §9 — DWH boundary: spec type honesty (W8-F09)

**W8-F09 — DWH spec is an honest as-is boundary document, not a false implementation claim.**

Spec status **Engineering Reviewed** with explicit FR-001: “must not be described as having an implemented DWH read interface” — **verified accurate**.

Confirmed implemented operational reporting (spec L96–107, code-verified):
- `HomeDashboardController` / `HomeDashboardService` + `portfolio_kpi_snapshot`
- `ReportAdminController` / `AdminReportingService` / `PortfolioMisReadRepository`
- Async `report_request` + R2 storage + `report_access_audit`
- `SYSTEM_ADMIN`-only guard on `/api/v1/internal/reports/**`

Confirmed absent (spec G-1 through G-10):
- No publication, replication slot, `wal_level`, Debezium, `APP_DWH_*` config, or warehouse read role.

**Honesty score: high** — this is the model for how Analyst Draft targets should label themselves.

---

## §10 — DWH: operational reporting vs warehouse extraction (W8-F10)

**W8-F10 — Portfolio MIS is production-grade ops reporting; it is not a DWH substitute and must not delay money-path work.**

Concrete implemented capabilities (inventory I4, N1):
- Sync CSV + async request lifecycle with `FOR UPDATE SKIP LOCKED` claiming
- Download audit (`MIS_CSV_DOWNLOADED`, `MIS_REQUEST_DOWNLOADED`)
- `report_request` RLS by `lsp_id`

**Residual gaps that matter for warehouse handoff (spec G-4, G-5, G-6):**
- No versioned table/column contract for downstream consumers
- Queries hit live operational tables (no read replica / CDC / projections)
- Preview/summary reads are **not audited** (spec EC-008) — acceptable for ops, insufficient for warehouse PII governance

DWH work is **infra + contract delivery** (DWH-3088 referenced in spec), not LMS application feature parity.

---

## §11 — DWH spec staleness: MIS masking (W8-F11)

**W8-F11 — DWH spec G-6/NFR-008 understates current MIS masking; gap doc is slightly stale.**

Spec claims (L353, G-6): “PAN and bank account number are currently returned unmasked.”

**Current code** (`AdminReportingService` L454–456):
- `AadhaarMasking.mask(...)`
- `PanMasking.mask(...)` — `XXXXXX<last4>` format
- `BankAccountMasking.mask(...)`

MIS export path masks all three. **Remaining honest gaps:**
- Masking format ≠ approved D3 first-2/last-3 (deferred S15)
- LSP/partner API surfaces still return raw PAN (S15 residual — outside DWH spec scope)
- No DWH-specific masking policy or tokenization

**Recommendation:** Update DWH spec G-6 to “MIS CSV masks Aadhaar/PAN/bank; partner APIs and admin detail surfaces differ; warehouse contract TBD.”

---

## §12 — Consolidated go-live readiness matrix (W8-F12)

**W8-F12 — W8 capabilities have distinct go-live tiers; do not bundle into one “Wave 8 blocker.”**

| Capability | Mock UAT / mgmt review | Live ICICI disbursement | Collections SoR | Regulated origination scale | Partner pilot reporting |
|---|---|---|---|---|---|
| **Three-way recon** | Not required | Not required (shadow optional) | **Required** (after S13) | Required for audit | Optional |
| **S13 receipt ledger** | Deferred (accepted) | Not required for disburse-only | **P0** (W5-F09) | P0 | — |
| **CKYC SFTP** | Optional | **P1** unless product gates disburse | P1 | **P1** (R07) | — |
| **DWH/CDC** | Not required | Not required | P1 (IRACP/reporting scale) | P1 | **P1** (if warehouse is consumer) |
| **Ops MIS (existing)** | ✅ Sufficient | ✅ Sufficient | ✅ + S13 for receipt truth | ✅ | ✅ with PII review |

---

## §13 — Sequencing vs W5 money findings (W8-F13)

**W8-F13 — W5 defines the critical path; W8 must not compete with S5/S6/S14 for live-rail go-live.**

W5 remediation sequence (verified alignment):

```
Containment (now):     foreclosure lock, admin preflight, DPD DISBURSED
Before live ICICI:     S5 beneficiary freeze → S6 mock/live exclusivity → S14 maker-checker
                       + retry debit-return gate + intent terminalization
60–90d post-live:      S13 receipt ledger → three-way recon shadow (W8)
Parallel track:        CKYC (compliance; not on ICICI critical path unless gated)
Post-pilot infra:      DWH/CDC contract + replication (external consumer)
```

| W5 finding | Blocks W8 work? | Which W8 item |
|---|---|---|
| W5-F09 (S13) | **Yes** — LMS ledger leg | Three-way recon |
| W5-F02 (S6) | **Yes** — bank evidence trust | Bank ingest / matching |
| W5-F03 (S14) | **Yes** — adjustment authority model | FR-011 financial adjustments |
| W5-F01 (S5) | Partial — disbursement leg integrity | Bank-side disbursement matching |
| W5-F06/F07 foreclosure | **Yes** — payment event integrity | All matching |
| R07 CKYC | No ICICI block | CKYC only |

**Honesty check:** W5-SERVICING-MONEY L75 (“60–90d: S13 ledger; three-way recon (W8)”) is the correct dependency statement. Any doc implying three-way recon before S13 is **not honest**.

---

## §14 — Three-way recon concrete build prerequisites (W8-F14)

**W8-F14 — Three-way recon MVP scope is definable; spec correctly defers ERP/period-close.**

Minimum viable shadow (spec SC-001–008 mapped to dependencies):

1. **Ledger posting hook** on: disbursement success, repayment (`LoanRepaymentCommandService`), foreclosure settlement, processing fee — idempotent by source key (FR-002).
2. **Chart of accounts** decision (unresolved L287) — blocking schema design.
3. **Bank file ingest** — admin upload + checksum dedup; ICICI MIS format from S17/live adapter contract.
4. **LSP file ingest** — `bhawana-lsp-recon-v1` + tenant isolation (NFR-003).
5. **Matching worker** — exact then secondary rules (L248–249); grace window for `TIMING_DIFFERENCE`.
6. **Exception ops UI** — typed outcomes EC-005–010; no balance mutation without adjustment (FR-012).
7. **Metrics** — NFR-005 lifecycle visibility.

**Explicitly out of first release (honest):** ERP integration, automated write-off, period close (FR-017 MAY), LSP-visible recon results (FR-014 SHOULD — product decision).

Flyway headroom: deferred register cites **V114+** for next money migration — ledger + recon would be a **large multi-migration** program, not a single ticket.

---

## §15 — CKYC + DWH sequencing & residual risks (W8-F15)

**W8-F15 — CKYC and DWH are parallel post-core tracks; neither excuses W5 P0 deferrals.**

**CKYC recommended sequence:**
1. Compliance signs SFTP + file format (blocks all FR-002–011).
2. Reuse MIS async artifact pattern for batch generation/storage (NFR-008).
3. SFTP adapter with host-key pin + transfer attempt table (FR-008–009).
4. Response parser with checksum idempotency (FR-012).
5. **Only then** decide disbursement gate (unresolved L269) — product decision, not engineering default.

**DWH recommended sequence:**
1. Name downstream consumer + required datasets (spec G-4).
2. Infra: read replica or logical replication (spec G-2, G-3) — **outside LMS repo**.
3. Versioned contract + PII rules (G-6, G-7) — align S15 before warehouse PAN policy.
4. Extraction observability (G-8) — lag, slot retention, backfill.

**Residual risks if gap docs are read uncritically:**
- Treating Analyst Draft W8 specs as “deferred like S13” → hides that **no owner/resume criteria exist** for W8 in `deferred-implementation.md`.
- Building three-way recon on `loan_payment_transaction` without S13 → **false confidence** in collections matching.
- Starting CKYC from desktop scripts → compliance/audit failure (spec explicitly warns L19).
- Claiming DWH readiness because MIS CSV exists → **category error** (W8-F10).

---

## Finding index

| ID | One-line summary | Severity (live money / collections SoR) |
|---|---|---|
| W8-F01 | Gap docs honest on absence; tiered severity | Calibration |
| W8-F02 | Three-way recon 0% implemented | High at collections SoR |
| W8-F03 | S13 ≠ three-way recon; S13 first | Critical sequencing |
| W8-F04 | Bank/LSP ingest + business decisions unset | High — blocks recon start |
| W8-F05 | Shadow recon poisoned by W5 gaps | High until containment |
| W8-F06 | CKYC 0% in LMS | P1 regulated origination |
| W8-F07 | CKYC external deps unverified in-repo | Medium documentation |
| W8-F08 | CKYC gates are compliance-owned | P1 when scaled |
| W8-F09 | DWH spec honest boundary doc | N/A (positive) |
| W8-F10 | Ops MIS ≠ DWH | Medium category error risk |
| W8-F11 | DWH G-6 stale on MIS masking | Low doc drift |
| W8-F12 | Distinct go-live tiers per capability | Calibration |
| W8-F13 | W5 P0 before W8 recon | Critical sequencing |
| W8-F14 | Three-way MVP definable post-S13 | High engineering |
| W8-F15 | CKYC/DWH parallel; don’t excuse W5 | Medium program risk |

---

**Bottom line:** The three W8 specs and inventory gap section are **bank-grade honest** about what does not exist. The main honesty failures are **cross-document**: (1) conflating S13 with three-way recon, (2) DWH MIS masking drift, (3) missing explicit “W8 is unscheduled target state” register entry distinct from deferred S5–S18. For go-live: **W5 containment → S5/S6/S14 → S13 → three-way shadow recon**; **CKYC in parallel for compliance**; **DWH when a named warehouse consumer exists**.

[REDACTED]