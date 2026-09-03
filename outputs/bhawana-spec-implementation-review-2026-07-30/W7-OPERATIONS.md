# W7 — Operations & Audit (Verified)

**Agent**: [W7](8aaae954-515c-45a8-9369-a18c4d4dea24) · Lead verified KPI FE mapping + PAN exposure pattern

## Assessment
Ops plane is **role-disciplined**: lifecycle money mutations SYSTEM_ADMIN-only; StatusWriter atomic. Gaps: no maker-checker on override, incomplete audit explorer coverage, OPS PII without reveal audit, **KPI UI mislabels** (DPD90+ as generic overdue; MTD label vs all-time backend risk per agent).

## High themes (lead-accepted)
| ID | Finding |
|---|---|
| W7-F01 | Manual override single-actor SoD (ties S14) |
| W7-F02 | Audit explorer incomplete vs bank-detail / auth / redrive |
| W7-F03 | OPS search exposes cleartext PAN/contact without reveal audit |
| W7-F04 | Home KPI labeling: `overdueLoansCount` ← `dpd90PlusLoanCount` (`home/api.ts` 143–144); UI "MTD disbursed" may not match backend window |
| W7-F05 | Alerts inbox not server-paged (~50 row client window) |

## Positives
OPS blocked from disbursement/status mutation; StatusWriter sole path; Aadhaar/bank masking on some surfaces.
