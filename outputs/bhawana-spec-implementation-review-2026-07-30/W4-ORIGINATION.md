# W4 — Origination & Underwriting (Verified)

**Status**: Verified  
**Agents**: [A9 Intake](419ecb5f-92e7-4ac9-86a1-c0ed9420e6a0), [A10 KYC](7df2f2c9-4247-4c67-9387-19efcabcae22), [A11 Auto-approval](6a198d5a-ba5e-458e-ad5a-60a27fe5b6ee)  
**Specs**: intake, auto-approval, KYC checklist, document upload, cancellation · D1–D3  
**Dirty worktree**: borrower pessimistic lock in `LoanApplicationStatusWriter` / Lifecycle — **material to D3**

---

## 1. Executive assessment

Origination is a **deliberate STP design** (D1 API-only, D2 auto decision, D3 one open loan). The eight-rule engine, status writer as sole mutation owner, and KYC presence gates match the as-is specs. 

**Material residual risk**: (1) malware/quarantine absent on document path (High for bank intake of partner files); (2) D3 at intake only sees open *accounts*, so concurrent cross-LSP applications can coexist until approval — dirty worktree closes the **approval double-account race** with borrower-level `PESSIMISTIC_WRITE` (must be committed); (3) KYC is presence/metadata completeness, not verification (product-intentional, compliance gap if treated as KYC).

No Critical under HEAD+dirty baseline once the uncommitted lock is treated as in-scope remediation.

---

## 2–4. Traceability (selected)

| Req | Status | Notes |
|---|---|---|
| D1 API-only create | Complete | LSP_API_CLIENT only |
| D2 auto credit | Complete | 8 rules; manual override advisory + alert |
| D3 one open loan | Partial→hardening | Account-stage lock in dirty tree; intake still multi-app |
| Idempotent create | Partial | Optional key; reconstructor exists |
| 8-doc checklist | Complete (presence) | Not verification |
| Malware scan | Missing | Spec G-4/G-8 |
| CKYC | Missing | O5 Analyst Draft (W8) |

---

## 5. Verified findings

### W4-F01 — No malware scan / quarantine on uploads
- **Severity**: High · **A10-F01/F17** · Lead confirmed (no AV code)  
- **Scenario**: Partner uploads weaponized PDF; ops opens in browser  
- **Fix**: Quarantine bucket + AV before ops access; block download until clean

### W4-F02 — D3 approval race closed only in uncommitted worktree
- **Severity**: Medium (High on clean HEAD without dirty) · **A11-F01 downgraded for approved baseline**  
- **Evidence**: `findBorrowerByApplicationIdForUpdate` + re-check in `LoanApplicationStatusWriter` 115–132; concurrency IT uncommitted  
- **Fix**: Commit + CI the lock; never ship HEAD without it

### W4-F03 — Intake D3 ignores in-flight applications
- **Severity**: Medium · **A9-F01** (was High; downgraded — D3 text is open *loan*, account-gated)  
- **Evidence**: `BorrowerActiveLoanChecker` OPEN_STATUSES are account statuses only  
- **Residual**: Multiple concurrent apps across LSPs until one approves  
- **Fix**: Optional application-status open set at intake if product wants single in-flight app

### W4-F04 — No borrower lock at intake (multi-app race)
- **Severity**: Medium · **A9-F02**  
- **Mitigated by**: approval-time borrower lock (dirty)

### W4-F05 — Disbursement initiate vs worker document gate mismatch
- **Severity**: Medium · **A10-F04**  
- **Evidence**: initiation allows metadata-only; worker requires LMS-managed content  

### W4-F06 — Storage keys may embed PII filenames
- **Severity**: Medium · **A10-F05**

### W4-F07 — Cancellation / INVALID vs REJECTED taxonomy
- **Severity**: Low–Medium · **A9-F05/F06**  
- Prior audits noted REJECTED→INVALID tension

### Positives
- Eight rules accumulate-all; gate prevents re-decision; KYC before manual approval; StatusWriter sole owner; STP outside doc txn (#85)

---

## Remediation
Immediate: commit D3 borrower lock + tests; plan AV/quarantine before partner pilot. 30d: align disbursement doc gates; intake open-application policy decision; storage key hygiene.
