# Bhavana LMS Architecture Review Pack

**Current-worktree update (2026-07-24):** H-01, the concurrent one-open-loan
approval race, has been remediated. The dated audit remains an immutable
description of the 2026-07-23 baseline and now carries a remediation note.

Read the documents in this order:

1. **`BHAVANA-PLATFORM-DECISION-HANDBOOK.md`**  
   Start here. This explains how the platform works, why the major business and
   technical decisions were made, credible alternatives, and when the current
   design should be retained or changed.

2. **`BHAVANA-PLATFORM-DEFENSE-CHEAT-SHEET.md`**  
   Use this to prepare for architecture reviews, leadership discussions, and
   common objections after studying the handbook.

3. **`BHAVANA-ARCHITECTURE-BUSINESS-AUDIT-2026-07-23.md`**  
   Use this as the evidence reference. It contains the 31-spec inventory,
   specification-to-code traceability, exact findings, source anchors, ADRs,
   validation results, and implementation roadmap.

## Suggested study sequence

- First session: handbook sections 1–4.
- Second session: handbook sections 5.1–5.12.
- Third session: handbook sections 5.13–5.22.
- Fourth session: alternatives, defense questions, and known gaps.
- Final session: use the audit evidence index to verify the code behind the
  decisions you expect to discuss.
