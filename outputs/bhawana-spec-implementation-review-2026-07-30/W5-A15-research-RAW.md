# W5-A15 Regulatory Research Notes — Servicing & Money Controls

**Scope:** Compare eight implementation facts (from prior waves; code read deferred per instruction) against authoritative India regulatory and standards sources.  
**Jurisdictional assumption (all findings):** India-regulated **NBFC as Regulated Entity (RE)** operating **digital lending** in a **B2B2C / LSP** model; LMS is RE-controlled origination/servicing software, not the statutory books-of-account system of record unless separately qualified.

**Severity scale for W5 synthesis:**  
- **P0** — Real-money / regulatory go-live blocker  
- **P1** — Material compliance or operational-risk gap; pilot only with explicit acceptance  
- **P2** — Product/ops limitation or guidance gap; not a direct statutory breach  
- **P3** — Aligned or positive; residual validation/assurance only  

---

## Executive matrix

| Finding ID | Fact | Regulatory posture | W5 severity |
|---|---|---|---|
| W5-A15-R01 | No GL / three-way recon (deferred S13) | Indirect mandatory (books, audit, IRACP); no RBI “three-way recon” product spec | **P0** at collections SoR go-live |
| W5-A15-R02 | Mock ICICI always registered; no exclusive live/mock (deferred S6) | Indirect mandatory (IT controls, fund-flow integrity) | **P0** at real-rail go-live |
| W5-A15-R03 | No maker-checker on disbursement (deferred S14) | Mandatory (RBI IT Framework); strong guidance (IT Gov 2023, OWASP ASVS L2+) | **P0** at real-rail go-live |
| W5-A15-R04 | Full installment payments only (D6) | Guidance/product (partial prepay expected); not “full EMI only” mandate | **P1** servicing gap |
| W5-A15-R05 | NUMERIC(19,2) money | Standards-aligned (ISO 4217 INR); good practice | **P3** |
| W5-A15-R06 | Disbursement intent + point-of-no-return | Supports fund-flow / STP / audit expectations | **P2** positive, incomplete alone |
| W5-A15-R07 | KYC = document presence; no CKYC | Mandatory CDD + CKYCR upload/retrieve (KYC MD + PML) | **P1** onboarding compliance |
| W5-A15-R08 | RLS + admin dual datasource tenancy | Guidance (OWASP); RE data-locality rules separate | **P2** architecture sound; assurance needed |

---

## W5-A15-R01 — No general ledger / three-way reconciliation (deferred S13)

**Implementation fact:** No receipt/allocation/suspense/reversal ledger; no three-way recon between LMS sub-ledger, bank statement, and GL. Deferred as Spec **S13 / MNY-02**.

### Sources

| Source | URL | What it supports | Mandatory vs guidance |
|---|---|---|---|
| **Companies Act, 2013 — s.128** | [Section 128 — books of account](https://ca2013.com/128-books-of-account-etc-to-be-kept-by-company/) | Every company must keep books on **accrual basis** and **double-entry** system giving a **true and fair view** and explaining all transactions | **Mandatory** (statute) |
| **RBI — NBFC Auditor’s Report Directions** | [Notification Id=5088](https://www.rbi.org.in/Scripts/NotificationUser.aspx?Id=5088&Mode=0) | Auditors must opine on prudential norms; returns/assessment tied to **proper books of account** | **Mandatory** (RBI Directions under RBI Act s.45MA) |
| **RBI — NBFC supervisory returns / SAC** | [Master Direction id=10620](https://rbi.org.in/scripts/BS_ViewMasDirections.aspx?id=10620) | Returns compiled from **figures in books of account**; SAC based on examined books | **Mandatory** for registered NBFCs |
| **RBI — IRACP Directions, 2025** | [Scale-based regulation / IRACP context](https://rbi.org.in/scripts/BS_ViewMasDirections.aspx?id=12945) | Loan classification, provisioning, income recognition require **accurate loan balances** and borrower-level performance | **Mandatory** prudential norms |
| **CARO 2020 — Clause 3(ii)(b)** | [CARO bank recon commentary](https://www.terra-insight.com/insights/caro-2020-bank-reconciliation-audit-india/) | Where aggregate WC limits > ₹5 crore, auditors report whether bank filings agree with books; persistent unreconciled items → IFC weakness | **Mandatory** audit reporting (Companies Act s.143(11)) for in-scope companies |

### India jurisdictional assumption
NBFC must maintain statutory books (typically core banking/ERP + GL) and reconcile cash/bank movements. RBI does **not** prescribe a named “three-way recon” LMS module, but expects reconciled, auditable money records.

### Relevance to go-live
- **Synthetic UAT / management review:** Acceptable if LMS is explicitly **not** collections system of record (per deferred register).
- **Real collections / partner receipt ingestion:** **P0 blocker** — without S13, partial/bunched/advance/bounce/reversal cannot be booked faithfully; statutory books and IRACP will diverge from LMS operational state.
- **Three-way recon specifically:** Best-practice **internal control** (ops ↔ bank ↔ GL), not a verbatim RBI product requirement; absence is an **audit and ops-risk** finding, not a named DL Direction breach.

### W5 severity implication
**P0** for any go-live where LMS is authoritative for receipts; **P2** if another SoR exists and LMS is workflow-only (must be documented and evidenced).

---

## W5-A15-R02 — Mock ICICI adapter always registered; no exclusive live/mock mode (deferred S6)

**Implementation fact:** `MockLoanDisbursementAdapter` always registered; mock outcome endpoint available; no fail-closed `mock` vs `icici` exclusivity. Deferred as Spec **S6 / MOCK-01**.

### Sources

| Source | URL | What it supports | Mandatory vs guidance |
|---|---|---|---|
| **RBI — Digital Lending Directions, 2025 — Para 9** | [Notification Id=12848](https://www.rbi.org.in/scripts/NotificationUser.aspx?Id=12848) | Disbursement to borrower account; repayments direct to RE; **no LSP/third-party control of fund flows** | **Mandatory** |
| **RBI — Digital Lending Directions, 2025 — Para 5(vii)** | Same | RE **fully responsible** for LSP acts/omissions under outsourcing | **Mandatory** |
| **RBI — IT Governance Directions, 2023 — Para 9(b), 13, 17, 19** | [Master Direction id=12562](https://www.rbi.org.in/scripts/BS_ViewMasDirections.aspx?id=12562) | **Segregation of duties** in IT ops; controlled change management; **STP with audit trails** for critical transfers; privileged access controls | **Mandatory** for Top/Upper/Middle Layer NBFCs (from 1 Apr 2024) |
| **RBI — IT Framework for NBFC Sector (2017)** | [Notification Id=10999](https://www.rbi.org.in/Scripts/NotificationUser.aspx?Id=10999) | Board-approved IT policy; risk assessment; controls for smaller NBFCs | **Mandatory** (all NBFCs, phased) |

### India jurisdictional assumption
No RBI rule says “disable mock adapter.” The risk is **production misconfiguration** causing simulated payouts or operator-triggered mock outcomes on live money — a control failure under RE outsourcing and IT-governance obligations.

### Relevance to go-live
- **Mock-only UAT:** Acceptable with deployment guardrails documented.
- **Real ICICI / live rail:** **P0 blocker** without S6 — accidental mock path is an existential funds-movement failure, not a disclosure issue.
- **Digital Lending Para 9:** Mock rail itself is not non-compliant; **uncontrolled coexistence in production** is.

### W5 severity implication
**P0** at real-rail go-live; **P1** configuration risk even pre-live if prod-like environments lack exclusivity.

---

## W5-A15-R03 — No maker-checker on disbursement (deferred S14)

**Implementation fact:** Single `SYSTEM_ADMIN` (or worker) can initiate disbursement; no checker queue, STP caps, or segregation. Deferred as Spec **S14 / CTRL-01**.

### Sources

| Source | URL | What it supports | Mandatory vs guidance |
|---|---|---|---|
| **RBI — IT Framework for NBFC Sector — Maker-checker** | [Notification Id=10999](https://www.rbi.org.in/Scripts/NotificationUser.aspx?Id=10999) | “For each transaction, there must be at least **two individuals** necessary for its completion”; maker-checker reduces error/misuse | **Mandatory principle** in RBI Master Direction (all NBFCs per phased compliance) |
| **RBI — IT Governance Directions, 2023 — Para 9(b), 23(b)** | [id=12562](https://www.rbi.org.in/scripts/BS_ViewMasDirections.aspx?id=12562) | **Effective segregation of duties** in IT service management; identify/eliminate **role conflicts** in IT risk management | **Mandatory** (in-scope REs) |
| **RBI — Digital Lending Directions, 2025 — Para 9** | [Id=12848](https://www.rbi.org.in/scripts/NotificationUser.aspx?Id=12848) | Fund flows must not be controlled by LSP/third party; implies RE-controlled authorization | **Mandatory** (fund-flow); maker-checker not spelled out here |
| **OWASP ASVS 4.0.3 — V4.3.3** | [ASVS V4 Access Control](https://asvs.dev/v4.0.3/V4-Access-Control/) | **Segregation of duties** / step-up auth for high-value apps to enforce anti-fraud controls | **Guidance** (L2/L3); risk-based |

### India jurisdictional assumption
RBI expects **maker-checker in financial entity information systems** for material transactions. Disbursement is a canonical high-value, irreversible operation.

### Relevance to go-live
- **Mock UAT:** Deferred register accepts single-admin for management review.
- **Live disbursement:** **P0 blocker** — aligns with production audit verdict (“single admin can move money”).
- **Intent workflow (S3):** Reduces duplicate payout risk but **does not substitute** authorization controls (per deferred register).

### W5 severity implication
**P0** at real-rail go-live; cross-reference S17 prerequisite chain (S14 before live adapter).

---

## W5-A15-R04 — Full installment payments only (D6)

**Implementation fact:** `LoanRepaymentCommandService` rejects non-exact-EMI amounts; `PARTIALLY_PAID` unreachable on public path.

### Sources

| Source | URL | What it supports | Mandatory vs guidance |
|---|---|---|---|
| **RBI — Pre-payment Charges on Loans Directions, 2025** | [Notification Id=12878](https://www.rbi.org.in/scripts/NotificationUser.aspx?Id=12878) | Prepayment allowed **“either in part or in full”** without lock-in (subject to charge exemptions) | **Mandatory** borrower-right framing for in-scope loans from 1 Jan 2026 |
| **RBI — NBFC Responsible Business Conduct Directions, 2025** | [TaxGuru summary of RBI text](https://taxguru.in/rbi/rbi-non-banking-financial-companies-responsible-business-conduct-directions-2025.html) | Borrowers shall have choice to **prepay in part or in full** at any point (subject to charge rules) | **Mandatory** conduct norm |
| **RBI — Digital Lending Directions, 2025 — Para 9(ii)** | [Id=12848](https://www.rbi.org.in/scripts/NotificationUser.aspx?Id=12848) | Servicing/repayment executed to RE account — does not prescribe allocation algorithm | **Mandatory** rail; silent on partial allocation |
| **RBI — IRACP Directions** | [id=12945](https://rbi.org.in/scripts/BS_ViewMasDirections.aspx?id=12945) | Accurate DPD/NPA requires correct application of receipts to dues | **Mandatory** prudential outcome |

### India jurisdictional assumption
RBI does **not** mandate “full EMI only.” It expects borrowers can prepay partially and that RE records reflect true outstanding. Allocation policy is RE board/product decision, but **rejecting all partial receipts** conflicts with prepayment norms and real-world collections (NACH partial, manual adjustments).

### Relevance to go-live
- **Demo / exact-EMI UAT:** **P2** acceptable with documented limitation.
- **Production collections / partner APIs:** **P1** — cannot handle partial prepay, bunched receipts, suspense, or reversals (compounds S13 gap).
- Not typically a direct **licence** breach if another system books receipts — but **borrower conduct** and **IRACP accuracy** risk remains.

### W5 severity implication
**P1** servicing/product gap; becomes **P0** when combined with S13 deferral and real receipt ingestion.

---

## W5-A15-R05 — NUMERIC(19,2) money storage

**Implementation fact:** Money columns use `NUMERIC(19,2)` — aligned with INR minor units.

### Sources

| Source | URL | What it supports | Mandatory vs guidance |
|---|---|---|---|
| **ISO 4217:2015 — INR minor unit** | [ISO 4217 standard excerpt](https://cdn.standards.iteh.ai/samples/64758/bd374e5824f444d3936c81afaf9c108a/ISO-4217-2015.pdf) | INR minor unit exponent **2** (100:1 rupee:paise) | **International standard** (not Indian statute) |
| **RBI / Indian accounting practice** | Indirect via Companies Act + notified Accounting Standards | Monetary amounts in INR presented to **paise** in statutory accounts | **Mandatory** presentation; storage precision is implementation |

### India jurisdictional assumption
INR retail lending amounts are conventionally stored and settled to **2 decimal places**. Higher internal precision (e.g. 4 dp for accrual) is optional; lower precision would be defective.

### Relevance to go-live
**P3 — no finding.** `NUMERIC(19,2)` is appropriate for INR ledger-facing amounts. Residual note: ensure **interest accrual / APR** intermediate calculations do not round prematurely before persistence (implementation detail, not regulatory breach).

### W5 severity implication
**None** — record as **control strength** for W5.

---

## W5-A15-R06 — Disbursement intent + point-of-no-return design exists

**Implementation fact:** Disbursement intent workflow (S3) with point-of-no-return — reduces duplicate payout / ambiguous state.

### Sources

| Source | URL | What it supports | Mandatory vs guidance |
|---|---|---|---|
| **RBI — Digital Lending Directions, 2025 — Para 9(i)-(iii)** | [Id=12848](https://www.rbi.org.in/scripts/NotificationUser.aspx?Id=12848) | Controlled disbursement to borrower; no third-party fund control | **Mandatory** outcome |
| **RBI — IT Governance Directions, 2023 — Para 15-17** | [id=12562](https://www.rbi.org.in/scripts/BS_ViewMasDirections.aspx?id=12562) | **Audit trails**; **no unauthorised manual modification** in critical STP chains; authenticated automated transfer | **Mandatory** (in-scope REs) |
| **RBI — IT Framework NBFC — Maker-checker** | [Id=10999](https://www.rbi.org.in/Scripts/NotificationUser.aspx?Id=10999) | Reliability of transaction data | **Mandatory principle** |

### India jurisdictional assumption
RBI does not name “disbursement intent” or “PONR.” These are **sound operational controls** supporting DL fund-flow integrity and auditability.

### Relevance to go-live
**Positive P2** — necessary but **not sufficient** without S14 (authorization), S6 (live/mock), S5 (beneficiary freeze), and bank acknowledgement verification. Intent reduces **duplicate** payout risk; does not address **unauthorized single-actor** payout.

### W5 severity implication
Credit in W5 as **mitigating control**; do not let it downgrade S14/S6 severity.

---

## W5-A15-R07 — KYC is document presence, not verification; no CKYC

**Implementation fact:** Approval checks required documents exist; no OVD verification, V-CIP, offline Aadhaar, or CKYCR retrieve/upload integration.

### Sources

| Source | URL | What it supports | Mandatory vs guidance |
|---|---|---|---|
| **RBI — KYC Master Direction, 2016** | [Master Direction Id=2607](https://www.rbi.org.in/commonman/english/Scripts/MasterDirection.aspx/Notification.aspx?Id=2607) | **CDD = identifying and verifying** customer; CIP; OVD / e-document verification; V-CIP standards | **Mandatory** |
| **RBI — KYC MD — Section 56/57 (CKYCR)** | Same; also [Amendment Id=12008](https://www.rbi.org.in/scripts/NotificationUser.aspx?Id=12008) | Upload KYC to **CKYCR** (individuals from 1 Jan 2017; LEs from 1 Apr 2021); retrieve via KYC Identifier | **Mandatory** under MD + PML Rules |
| **PML Rules — Rule 9(1A)** | Referenced in KYC MD / RBI amendment | Legal basis for CKYCR upload for legal entities | **Mandatory** (Rules under PML Act) |
| **RBI — Digital Lending Directions, 2025 — Para 12** | [Id=12848](https://www.rbi.org.in/scripts/NotificationUser.aspx?Id=12848) | Need-based data collection with **consent and audit trail** for onboarding/KYC | **Mandatory** |

### India jurisdictional assumption
NBFC as RE must perform **full CDD** before lending, not merely file upload. CKYCR integration is required for new/updated customers per phased rules. Document **presence** in LMS ≠ **verification** under KYC MD.

### Relevance to go-live
- **Internal demo:** **P2** with explicit compliance waiver.
- **Onboarding live borrowers / DL channel:** **P1** — ML/TF and KYC MD exposure; supervisory scrutiny on digital onboarding.
- **CKYC absence:** Not optional for in-scope new accounts; periodic updation must also migrate records to CKYCR.

### W5 severity implication
**P1** for any pilot with real borrowers; separate from money-movement P0s but may block **regulatory approval narrative** for DL go-live.

---

## W5-A15-R08 — RLS + admin dual datasource tenancy

**Implementation fact:** PostgreSQL RLS for tenant isolation; admin operations via separate datasource path.

### Sources

| Source | URL | What it supports | Mandatory vs guidance |
|---|---|---|---|
| **OWASP Top 10:2021 — A01 Broken Access Control** | [A01 Broken Access Control](https://owasp.org/Top10/2021/A01_2021-Broken_Access_Control/) | Enforce access server-side; **record ownership**; deny by default | **Industry guidance** |
| **OWASP — Multi-Tenant Security Cheat Sheet** | [Multi-Tenant Security](https://cheatsheetseries.owasp.org/cheatsheets/Multi_Tenant_Security_Cheat_Sheet.html) | Tenant context from **auth**, not client headers; DB-level isolation as defense-in-depth; `WITH CHECK` on writes | **Guidance** |
| **RBI — Digital Lending Directions, 2025 — Para 12-13** | [Id=12848](https://www.rbi.org.in/scripts/NotificationUser.aspx?Id=12848) | Data minimization, consent, **India storage**, security of borrower data | **Mandatory** (privacy/storage) |
| **RBI — IT Governance Directions, 2023 — Para 19, 24** | [id=12562](https://www.rbi.org.in/scripts/BS_ViewMasDirections.aspx?id=12562) | Need-based access; MFA for privileged users; IS policy | **Mandatory** (in-scope REs) |

### India jurisdictional assumption
RBI does not mandate PostgreSQL RLS. It mandates **adequate controls** over customer data in DL and IT governance. Dual admin datasource is acceptable **if** admin path has equivalent authorization, audit, and cannot bypass tenant boundaries without trace.

### Relevance to go-live
**P2 architecture positive** — RLS matches OWASP defense-in-depth for B2B2C multi-LSP tenancy. **Residual risks for W5:** admin datasource bypass testing, `FORCE ROW LEVEL SECURITY`, pool `SET LOCAL` discipline, break-glass procedures. Not a statutory gap by itself.

### W5 severity implication
**No regulatory finding**; include as **technical control** with **assurance** requirement (pen test / IDOR tests per OWASP cheat sheet).

---

## W5 synthesis — severity rollup

### P0 cluster (real-money go-live — consistent with deferred register)
1. **S13 / R01** — No authoritative receipt ledger or recon path to books  
2. **S6 / R02** — Mock rail can coexist with live configuration  
3. **S14 / R03** — No disbursement maker-checker despite RBI IT Framework mandate  

**Synthesis line:** Digital Lending Para 9 governs **where money flows**; IT Framework + IT Governance govern **who can move it**; Companies Act + IRACP govern **what books must show**. Current deferrals leave all three legs incomplete for production.

### P1 cluster (pilot with real borrowers / collections)
4. **R04** — Full-EMI-only conflicts with partial prepay norms and real collections  
5. **R07** — Document presence ≠ KYC MD verification; CKYCR not integrated  

### P3 strengths (do not over-credit)
6. **R05** — NUMERIC(19,2) INR-aligned  
7. **R06** — Intent + PONR is real mitigating control for payout idempotency  
8. **R08** — RLS tenancy model aligns with OWASP; needs operational proof  

### Recommended W5 narrative (one paragraph)
Bhawana LMS is **defensible for synthetic/mock management review** but **not for India NBFC real-money digital lending** until S6+S14 harden disbursement authorization and S13 establishes collections SoR. KYC and partial-payment gaps are **P1** for any live borrower pilot. NUMERIC precision, disbursement intent, and RLS are **implementation strengths** that must not obscure the P0 control cluster.

---

## Deferred gaps cited (no code audit performed)

| Spec | Register reference | Stated severity |
|---|---|---|
| S13 | `docs/deferred-implementation.md` | P0 for collections SoR |
| S6 | Same | P1 config / P0 if prod+mock |
| S14 | Same | P0 at real rails |
| D6 full EMI | Same (S13 residual) | Servicing limitation |

---

## Source index (authoritative preferred)

| Topic | Primary URL |
|---|---|
| Digital Lending 2025 | https://www.rbi.org.in/scripts/NotificationUser.aspx?Id=12848 |
| DL press release | https://rbi.org.in/Scripts/BS_PressReleaseDisplay.aspx?prid=60403 |
| KYC Master Direction 2016 | https://www.rbi.org.in/commonman/english/Scripts/MasterDirection.aspx/Notification.aspx?Id=2607 |
| CKYCR amendment | https://www.rbi.org.in/scripts/NotificationUser.aspx?Id=12008 |
| IT Framework NBFC (maker-checker) | https://www.rbi.org.in/Scripts/NotificationUser.aspx?Id=10999 |
| IT Governance 2023 | https://www.rbi.org.in/scripts/BS_ViewMasDirections.aspx?id=12562 |
| Pre-payment Charges 2025 | https://www.rbi.org.in/scripts/NotificationUser.aspx?Id=12878 |
| NBFC Auditor’s Report | https://www.rbi.org.in/Scripts/NotificationUser.aspx?Id=5088 |
| Companies Act s.128 | https://ca2013.com/128-books-of-account-etc-to-be-kept-by-company/ |
| ISO 4217 INR | https://cdn.standards.iteh.ai/samples/64758/bd374e5824f444d3936c81afaf9c108a/ISO-4217-2015.pdf |
| OWASP A01 | https://owasp.org/Top10/2021/A01_2021-Broken_Access_Control/ |
| OWASP Multi-Tenant | https://cheatsheetseries.owasp.org/cheatsheets/Multi_Tenant_Security_Cheat_Sheet.html |
| OWASP ASVS 4.3.3 | https://asvs.dev/v4.0.3/V4-Access-Control/ |

---

**Disclaimer:** This is research mapping implementation facts to published requirements — not legal advice. Several obligations depend on NBFC layer (Base/Middle/Upper), deposit-taking status, and whether LMS is system of record vs. workflow overlay; those scoping decisions belong in W5 sign-off.

[REDACTED]