# Wave 4 Agent A10 — KYC / Document Upload, Storage & Review Audit

**Mode:** READ-ONLY  
**Specs reviewed:**
- `/Users/siddhant/Desktop/work/ferratum-products-specs-res/areas/bhawana/origination-and-underwriting/kyc-document-checklist-and-gates/spec.md`
- `/Users/siddhant/Desktop/work/ferratum-products-specs-res/areas/bhawana/operations/document-upload-storage-and-review/spec.md`

**Code baseline:** `/Users/siddhant/Desktop/lms` (Java backend + React frontend surfaces referenced in ops spec)

**Focus areas:** presence vs verification, malware scan, review verdicts, tenancy on downloads, PII in storage keys

---

## 1. Executive Summary

The Bhawana LMS prototype implements a **checklist-driven, upload-equals-submitted** KYC document model with no human verify/reject gate. Partners upload via LSP-scoped REST APIs; internal ops inspect via download/preview/ZIP with mandatory access audit. Upload validation is solid for MIME allowlists, magic-byte checks, and per-type size caps.

Against **bank-grade** expectations, the largest gaps are intentional prototype limitations documented in both specs: **no malware scanning or quarantine**, **no document verification verdicts**, and a **deliberate two-tier “complete” model** where metadata-only submissions satisfy approval/auto-approval but not automated disbursement preflight. Additional production risks include **PII-bearing filenames embedded in object-storage keys**, **orphaned storage objects** on re-upload/batch partial failure, and **heap-buffered** upload/ZIP paths.

Overall spec alignment for *as-is documented behavior* is **high (~90%)**. Alignment for *typical regulated-lender target* is **moderate (~55%)**, driven by absence of AV, verification workflow, and stricter disbursement gate uniformity.

---

## 2. Audit Scope & Methodology

**In scope**
- Checklist seeding, status model, completion detection
- `DocumentUploadPolicy`, `LoanApplicationDocumentRequirements`
- `LoanDocumentService`, `LoanApplicationDocumentChecklistService`
- `ConfigurableLoanDocumentStorageService` (+ LOCAL/R2 adapters)
- LSP upload/list controllers, internal ops KYC endpoints
- Origination gates: approval, disbursement initiation, auto-approval, worker preflight
- Document access audit (`loan_application_document_access_audit`)
- DB migrations for status collapse / removed review columns

**Out of scope (per specs)**
- Auto-approval rule engine internals, webhook delivery, encryption-at-rest KMS policy, CKYC/OCR

**Method**
- Spec-to-code trace of call paths
- Migration review (`V50__document_checklist_status_collapse.sql`)
- Integration/unit test corroboration (`LspLoanApplicationApiControllerTest`, `LoanApplicationOpsControllerDocumentDownloadAuditTest`, `DocumentUploadPolicyTest`)

---

## 3. Checklist Data Model & Eight-Document Lifecycle

| Element | Implementation | Spec match |
|---------|----------------|------------|
| Table | `loan_application_document_checklist` | Yes |
| Types | 8 enums in `LoanApplicationDocumentType` | Yes |
| Statuses | `PENDING`, `SUBMITTED`, `NOT_REQUIRED` only | Yes |
| Seeding | `LoanApplicationDocumentChecklistService.seedDocumentChecklist` creates all 8 `PENDING` | Yes |
| Re-upload | `update()` overwrites row; last write wins | Yes (G-5/G-7) |

**Key files**
- `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/LoanApplicationDocumentType.java`
- `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/LoanApplicationDocumentChecklist.java`
- `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanApplicationDocumentChecklistService.java`

**Note:** Spec table lists KFS/LOAN_AGREEMENT as “not required for approval,” but code defines intake completion via `isRequiredForDisbursement()` for all eight types (`LoanApplicationDocumentRequirements.isIntakeRequired`). All eight gate auto-approval and KYC completion — matching kyc spec FR-006/FR-009, not the ops spec’s approval column.

---

## 4. Presence vs Verification — Two-Tier Completeness

This is the central architectural nuance.

### Tier A — “Present” (approval / auto-approval / webhook)
`LoanApplicationDocumentRequirements.isChecklistItemComplete()` returns true for `SUBMITTED` or `NOT_REQUIRED` **regardless of `lmsManagedContent`**.

Metadata JSON upload sets `lmsManagedContent=false` with no `storageKey` (`LoanDocumentService.submitDocumentMetadataForLsp`).

### Tier B — “Verified stored” (automated disbursement worker only)
`isChecklistItemCompleteForDisbursement()` additionally requires `lmsManagedContent=true` (unless `NOT_REQUIRED`).

Used by `DisbursementPreflightValidator.validateAutomatedDisbursement` → `hasAllRequiredLmsManagedDocuments`.

### Tier C — Disbursement initiation (ops/LSP trigger)
`validateRequiredDocumentsUploadedBeforeDisbursement` checks only `SUBMITTED`/`NOT_REQUIRED` status — **does not require LMS-managed content**.

```77:94:/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanApplicationDocumentChecklistService.java
    public void validateRequiredDocumentsUploadedBeforeDisbursement(UUID applicationId) {
        // ...
        List<LoanApplicationDocumentType> blockingDocumentTypes = loanApplicationDocumentChecklistRepository
                // ...
                .filter(item -> item.getStatus() != LoanApplicationDocumentChecklistStatus.SUBMITTED
                        && item.getStatus() != LoanApplicationDocumentChecklistStatus.NOT_REQUIRED)
```

**Test evidence:** `metadataDocumentUploadsAutoApproveWhenAllEightDocumentsComplete` in `LspLoanApplicationApiControllerTest` — seven file uploads + one metadata LOAN_AGREEMENT → `APPROVED_PENDING_DISBURSAL`.

**Bank-grade assessment:** Presence ≠ verified content. Spec documents this (EC-003, G-3/G-5). For regulated lending, the split is a **controlled risk** only if disbursement initiation enforced the same LMS-managed rule as the worker (it currently does not).

---

## 5. DocumentUploadPolicy & Ingest Validation

`DocumentUploadPolicy` enforces:

| Control | Rule |
|---------|------|
| Global MIME | PDF, JPEG, PNG |
| PAN/Aadhaar MIME | PDF, JPEG only; 5 MB cap |
| Loan Agreement | PDF only |
| Others | 10 MB cap |
| Filename | Non-empty, no `..` |
| Content | Magic bytes: `%PDF`, JPEG `FF D8 FF`, PNG signature |
| Empty body | `DOCUMENT_CONTENT_INVALID` |

Called from `ConfigurableLoanDocumentStorageService.store` after empty-file check (`DOCUMENT_FILE_EMPTY`).

**Strengths**
- Per-type constraints match both specs
- Magic-byte validation blocks MIME spoofing (EC-007)
- `DocumentPreviewSupport` mirrors upload allowlist for inline preview safety

**Weaknesses (bank-grade)**
- **W4-A10-F09:** Full file read into heap twice (`DocumentUploadPolicy.readContent` + `file.getBytes()` in storage service) — violates NFR-007 streaming intent for uploads
- **W4-A10-F18:** Redundant size check in storage service after policy already validated
- No content entropy / polyglot / embedded script analysis beyond magic bytes
- No staged “pending scan” state — valid bytes go directly to production storage path

**File:** `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/DocumentUploadPolicy.java`

---

## 6. Storage Architecture, Keys & PII

### Provider selection
`ConfigurableLoanDocumentStorageService` routes `LOCAL` → `FileSystemLoanDocumentStorageService`, `R2` → `R2LoanDocumentStorageService`.

### Storage key format (as documented)
```
loan/<applicationId>/<document-type-lower>/<epochMillis>-<uuid>-<sanitized-file-name>
```

Built in `ConfigurableLoanDocumentStorageService.buildStorageKey`.

### PII in keys — **W4-A10-F05 (Medium)**
- `applicationId` is UUID (not direct PII)
- **`<sanitized-file-name>` preserves partner-supplied basename** after slash/quote stripping only
- Upload of `Rajesh-Sharma-Aadhaar.pdf` embeds borrower-identifying text in object key visible in R2 console, logs, backup manifests, and internal ops API (`storageKey` exposed)

Sanitization does **not** hash or neutralize semantic PII in filenames.

### Retrieval security
- Keys are system-generated at upload; not partner-supplied on download
- Download resolves `(applicationId, documentType)` → DB row → `storageKey`; no cross-check that key path segment matches `applicationId` (**W4-A10-F06** low — DB integrity assumption)
- Local filesystem resolves `rootPath.resolve(storageKey)` — path traversal via DB corruption theoretically possible if `..` entered storage_key column (not possible via normal upload path)

### Orphan objects — **W4-A10-F07, W4-A10-F08**
- Re-upload writes new object; old key not deleted
- Batch: each file stored before transaction completes; later failure leaves orphans (spec G-11)

---

## 7. Malware Scan & Quarantine Posture

**Finding W4-A10-F01 (High — bank-grade gap, documented in spec):**

| Expected (regulated target) | Actual |
|----------------------------|--------|
| AV scan before promotion to readable storage | **None** |
| Quarantine bucket / scan-pending status | **None** |
| Scan result gates `SUBMITTED` | Upload → immediate `SUBMITTED` + readable by ops |

Repo search for `quarantine`, `malware`, `clamav`, `virus` in backend Java: **zero production implementations**. Both specs explicitly list AV as out of scope / current-vs-target gap (G-4, G-8).

**Implication:** A malicious PDF/JPEG/PNG passing magic-byte checks is stored and internally downloadable without scan delay. For bank-grade KYC, this is the single largest control deficiency.

---

## 8. Review Verdicts & Ops Inspection Model

### Historical state removed
`V50__document_checklist_status_collapse.sql` collapsed `RECEIVED`/`VERIFIED` → `SUBMITTED`, migrated `REJECTED`, dropped `review_reason` / `rejection_reason`.

### Current model — **W4-A10-F02 (Informational, by design)**
- No `VERIFIED` / `REJECTED` checklist status
- No API to record document approval/rejection
- Ops “review” = `CHECKLIST_VIEWED`, `SINGLE_DOCUMENT_PREVIEWED`, `SINGLE_DOCUMENT_DOWNLOADED`, `BULK_ZIP_DOWNLOADED`
- UI may say “verification” but backend persists no per-document verdict (spec G-1/G-2)

**Finding W4-A10-F11 (Medium):** LSP upload has **no application lifecycle status guard** — uploads allowed on `REJECTED`, post-approval, etc. if LSP owns app (`Issue85Issue135LspDocumentUploadIntegrationTest` confirms upload on rejected app succeeds; gate skipped via `skipped_status` metric).

---

## 9. Partner Upload & Read APIs — Tenancy

### Endpoints (LSP)
`/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/LspLoanApplicationApiController.java`

| Operation | Roles | Tenancy |
|-----------|-------|---------|
| `GET .../documents` | API_CLIENT, UI_READ, UI_WRITE | `getApplicationForLsp` |
| `POST .../documents` JSON/multipart | API_CLIENT, UI_WRITE | Same |
| `POST .../documents/batch` | API_CLIENT, UI_WRITE | Same + duplicate type rejection |

### Tenancy enforcement — **Compliant (W4-A10-F12 partial)**
`LoanApplicationQueryService.getApplicationForLsp` returns 404 (not 403) on LSP mismatch — tested in `lspDocumentsListReturnsUploadsOnlyForOwnerWithStatusSubmitted`.

### Partner data minimization — **Compliant (W4-A10-F13)**
`LspDocumentChecklistDetailResponse` excludes `storageKey` and `fileChecksum`. Partner list folds status to `SUBMITTED`.

### Idempotency
Optional UUID-v4 `Idempotency-Key` with SHA-256 file fingerprint — matches ops spec FR-019.

---

## 10. Internal Download / Preview / ZIP — Tenancy & Caching

### Endpoints
`/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/LoanApplicationOpsController.java`

- `GET .../kyc-documents` — full checklist + `CHECKLIST_VIEWED` audit
- `GET .../kyc-documents/{type}/content?disposition=inline|attachment`
- `GET .../kyc-documents/download-all`
- `GET .../document-access-audits`

### Tenancy on downloads — **W4-A10-F12 (Informational)**
Internal ops (`SYSTEM_ADMIN`/`OPS_USER`) is **intentionally cross-tenant** (spec D8). No LSP filter on `applicationId` — any authorized internal user can download any application’s KYC content if they know the UUID.

This is spec-correct for Bhawana ops model; bank-grade deployments often add LSP-scoped ops roles or break-glass justification — **not implemented**.

### Security controls on content delivery — **Strong**
- `Cache-Control: no-store` on single-document responses
- Inline preview restricted to PDF/JPEG/PNG (`DocumentPreviewSupport`); 415 otherwise
- Single-document streaming avoids holding DB connection during storage I/O (`LoanDocumentService.openDocumentStream`, `LoanApplicationServicingReadService.accessDocumentContent`)
- Audit written **after** successful stream open; failed/missing docs not audited as success

### ZIP weakness — **W4-A10-F10**
`buildDocumentZip` loads every object into memory and builds in-heap ZIP — acceptable for prototype, risky at scale.

### Internal metadata exposure
Ops checklist response includes `storageKey` and `fileChecksum` (spec G-10) — appropriate for internal tooling.

---

## 11. Origination Gates & Completion Triggers

| Gate | Trigger | Check | Exception |
|------|---------|-------|-----------|
| Auto-approval | Completion edge + `INITIALIZED`/`AWAITING_APPROVAL` | 8 types complete (presence) | Metrics: `lms.auto_approval.gate` |
| Manual approval → disbursal | Status transition | `validateKycCompletionBeforeApproval` | `KycCompletionRequiredException` |
| Disbursement initiation | Ops/LSP request | `validateRequiredDocumentsUploadedBeforeDisbursement` (status only) | `DocumentUploadRequiredException` |
| Automated disbursement worker | Worker preflight | `hasAllRequiredLmsManagedDocuments` | Reject with violations map |

**Completion edge detection:** `updateDocumentChecklistItem` computes `!wasComplete && isComplete`, enqueues `DOCUMENTS_UPLOADED` webhook if subscribed.

**Finding W4-A10-F04 (Medium):** Disbursement **initiation** gate is weaker than **worker preflight** — metadata-only docs can pass initiation but fail automation later.

**Finding W4-A10-F03 (Low, documented):** Metadata can complete checklist and trigger auto-approval without any bytes in LMS storage — by design per D2 + EC-003.

---

## 12. Document Access Audit Trail

**Entity:** `LoanApplicationDocumentAccessAudit`  
**Actions:** `CHECKLIST_VIEWED`, `INTAKE_AUDITS_VIEWED`, `SINGLE_DOCUMENT_PREVIEWED`, `SINGLE_DOCUMENT_DOWNLOADED`, `BULK_ZIP_DOWNLOADED`

**Fields captured:** actor, summary, document types (normalized join table `loan_application_document_access_audit_type`), correlation ID, actor IP, byte count, timestamp.

**Test coverage:** `LoanApplicationOpsControllerDocumentDownloadAuditTest` validates IP, byte count, correlation ID on download/preview.

**Gaps (bank-grade) — W4-A10-F14, W4-A10-F15**
- No immutable/tamper-evident store; rows are mutable DB records
- No dedicated partner **upload** audit stream — only checklist row mutation + optional webhook
- Partner uploads not mirrored to document-access audit table
- Retention/purge policy undocumented

---

## 13. Transactions, Idempotency & Resiliency

| Concern | Behavior |
|---------|----------|
| Upload + checklist update | Same `@Transactional` on `persistStoredDocumentForLsp` |
| Storage write vs DB | Object written **before** DB commit — orphan risk on rollback |
| Auto-approval | Fired **outside** persist transaction from controller/service layer (Issue #85 pattern) |
| Storage unavailable | `DOCUMENT_STORAGE_UNAVAILABLE` + metric `lms.document.storage.unavailable` |
| Batch atomicity | DB transaction wraps all items; storage not rolled back per item |

---

## 14. Spec Compliance Matrix (Selected FR/NFR)

| ID | Requirement | Status |
|----|-------------|--------|
| FR-001–005 | Partner upload endpoints, validation, batch dedup | **PASS** |
| FR-006–008 | 8-doc completion, webhook, auto-approval edge | **PASS** |
| FR-009 | KYC gate before approval | **PASS** (presence-based) |
| FR-010 | Disbursement doc gate | **PARTIAL** — initiation status-only; worker LMS-managed |
| FR-011–015 | Partner metadata-only read; ops review; no verify gate | **PASS** |
| FR-018 | No verified/rejected state | **PASS** |
| FR-019 | LSP idempotency | **PASS** |
| NFR-001–003 | MIME, no-store, preview allowlist | **PASS** |
| NFR-004 | Partner hides storage key | **PASS** |
| NFR-005 | 100% internal access audited | **PASS** |
| NFR-008–009 | Stream single doc; no DB during storage | **PASS** |
| Out-of-scope AV | No malware scan | **N/A (documented gap)** |

---

## 15. Findings Register

| ID | Severity | Area | Finding |
|----|----------|------|---------|
| **W4-A10-F01** | **High** | Malware | No antivirus/malware scan or quarantine pipeline; uploaded content immediately stored and ops-accessible. Spec-acknowledged (G-4/G-8). |
| **W4-A10-F02** | Info | Review verdicts | No verify/reject workflow; `SUBMITTED` = complete. Migration V50 removed historical model. Matches D2/FR-015/FR-018. |
| **W4-A10-F03** | Low | Presence vs verification | Metadata-only submissions satisfy approval/auto-approval; intentional per EC-003. |
| **W4-A10-F04** | **Medium** | Gates | `validateRequiredDocumentsUploadedBeforeDisbursement` (initiation) does not require `lmsManagedContent`; worker `hasAllRequiredLmsManagedDocuments` does — inconsistent disbursement path. |
| **W4-A10-F05** | **Medium** | PII in keys | Storage keys embed sanitized original filename; partner may supply PII-bearing names (`ConfigurableLoanDocumentStorageService.buildStorageKey`). |
| **W4-A10-F06** | Low | Storage integrity | Retrieval trusts DB `storageKey`; no runtime assertion that key’s `applicationId` segment matches request. |
| **W4-A10-F07** | Low | Lifecycle | Re-upload does not delete superseded objects — storage leak / retention risk. |
| **W4-A10-F08** | Low | Resiliency | Batch partial failure can orphan stored objects (spec G-11). |
| **W4-A10-F09** | Medium | Performance/security | Upload path buffers entire file in heap (policy + store); not streaming. |
| **W4-A10-F10** | Medium | Performance | ZIP download buffers all documents in memory. |
| **W4-A10-F11** | Medium | Lifecycle | No loan-status guard on LSP document upload/replace post-approval/rejection. |
| **W4-A10-F12** | Info | Tenancy | LSP downloads tenant-scoped (404 on cross-tenant); internal ops cross-tenant by design (D8). |
| **W4-A10-F13** | Pass | Tenancy | Partner responses omit `storageKey`/`fileChecksum`; tests confirm cross-tenant list denial. |
| **W4-A10-F14** | Low | Audit | No immutable upload audit; partner mutations only on mutable checklist rows. |
| **W4-A10-F15** | Low | Audit | Access audit is append-only in practice but not tamper-evident; no retention policy. |
| **W4-A10-F16** | Info | Requirements | All 8 types required for completion via `isRequiredForDisbursement`; ops spec approval column understates KFS/agreement role. |
| **W4-A10-F17** | High | Quarantine | No staging/quarantine bucket — “scan pending” state absent (companion to F01). |
| **W4-A10-F18** | Low | Validation | Duplicate size validation in policy and storage service. |

---

### Primary Code Anchors

| Concern | Path |
|---------|------|
| Upload policy | `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/DocumentUploadPolicy.java` |
| Completeness rules | `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanApplicationDocumentRequirements.java` |
| Checklist + gates | `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanApplicationDocumentChecklistService.java` |
| Document orchestration | `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanDocumentService.java` |
| Storage + keys | `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/ConfigurableLoanDocumentStorageService.java` |
| LSP API | `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/LspLoanApplicationApiController.java` |
| Ops review/download | `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/LoanApplicationOpsController.java` |
| Access audit | `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanApplicationServicingReadService.java` |
| Status collapse migration | `/Users/siddhant/Desktop/lms/backend/src/main/resources/db/migration/V50__document_checklist_status_collapse.sql` |

---

### Bottom Line

The implementation is a **faithful, well-tested prototype** of the documented as-is specs: upload validation, tenant-scoped partner access, audited internal inspection, and upload-triggered auto-approval without human document verification. For **bank-grade KYC**, the critical missing controls are **malware scanning/quarantine (F01/F17)**, **uniform LMS-managed enforcement at disbursement initiation (F04)**, and **PII-safe object naming (F05)**. Review “verdicts” are intentionally absent (F02); ops review is read-only inspection with strong access logging but no accept/reject persistence.

[REDACTED]