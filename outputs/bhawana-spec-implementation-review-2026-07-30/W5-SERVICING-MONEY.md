# W5 — Servicing & Money Movement (Verified)

**Status**: Verified  
**Agents**: [A13 Disbursement](146d732d-cbea-4a56-93bc-5e7f7cfdb2e5), [A14 Servicing](55996ea8-c639-4318-a892-9965681f9cb2), [A15 Research](4fe0e3da-9373-41d3-8bfa-b00ec58a526f)  
**Cross-ref**: W1-F01 foreclosure null idempotency  

---

## 1. Executive assessment

Disbursement **intent workflow (S3)** is the strongest bank-grade money control in the platform: intent committed before provider call, unique `tran_ref_no`, one live intent per account, `UNKNOWN` not blindly re-initiated. That design is deliberate and aligns with CONTEXT.md point-of-no-return *intent*.

It is **not yet a live-money platform**: mock adapter is unconditional (S6), no maker-checker (S14), live beneficiary at intent-create (S5), no receipt/reversal ledger (S13), admin initiate bypasses worker preflight bank/schedule gates, foreclosure settlement races remain High, and DPD alerts miss `DISBURSED` loans before first payment.

Under approved **pre-prod/mock** posture: **no Critical for real funds**; several findings are **High now** and **Critical blockers before ICICI go-live** (research R02/R03).

---

## 2. Verified findings (lead-calibrated)

### W5-F01 — Admin initiate bypasses worker preflight (live beneficiary)
- **Severity**: High (Critical at live rails) · **A13-F01**  
- **Evidence**: `initiateDisbursement` docs gate only (129); worker runs `DisbursementPreflightValidator` (Processor 93–100); intent snapshots live borrower bank fields (IntentWorkflow 97–99)  
- **Fix**: Same validator on admin path; land S5 approval-time freeze

### W5-F02 — Mock rail always on / mock-outcome API (S6)
- **Severity**: High · go-live Critical · **A13-F02** · deferred documented  

### W5-F03 — No maker-checker / STP caps (S14)
- **Severity**: High · go-live Critical · **A13-F04** · RBI IT dual-control research R03  

### W5-F04 — Technical retry new tranRef without debit-return proof
- **Severity**: High (mock benign) · **A13-F05**  

### W5-F05 — Mock outcome does not terminalize intent
- **Severity**: High · **A13-F03**  

### W5-F06 — Foreclosure null idempotency + no quote lock (reaffirm W1-F01)
- **Severity**: High · **A14-F01/F02** · lead previously verified  

### W5-F07 — Foreclosure vs concurrent EMI / stale quote amount
- **Severity**: High · **A14-F03/F04**  
- **Fix**: Account/quote FOR UPDATE; recompute settlement at execute  

### W5-F08 — DPD alerts only for UNDER_REPAYMENT
- **Severity**: High · **A14-F05**  
- **Evidence**: `AlertRuleSetQueryRepository` filters `UNDER_REPAYMENT` — `DISBURSED` past due silent until first EMI  

### W5-F09 — No receipt/reversal ledger (S13)
- **Severity**: High as collections SoR · Medium for full-EMI UAT · **A14-F06** · research R01  

### W5-F10 — Quote no expiry / dating asymmetry
- **Severity**: Medium · **A14-F07/F08**  

### Positives
- Intent Tx-A/out-of-tx/Tx-B; SKIP LOCKED claims; bank details locked in-flight; NUMERIC money; D6/D7 product rules; schedule version freeze (W3)

---

## 3. Research implications (not legal advice)

| ID | Topic | Go-live |
|---|---|---|
| R01 | Ledger/recon | P0 before collections SoR |
| R02 | Mock/live exclusive | P0 before real rails |
| R03 | Maker-checker | P0 before real rails (RBI IT Framework dual control) |
| R04 | Full EMI only | P1 product/servicing |
| R07 | CKYC/CDD | P1 onboarding compliance |

---

## Remediation sequence
**Containment:** Fix foreclosure lock+idempotency; align admin preflight; DPD include DISBURSED.  
**Before live ICICI:** S5, S6, S14, retry debit-return gate, intent terminalization.  
**60–90d:** S13 ledger; three-way recon (W8).
