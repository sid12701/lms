# Loan document preview & download

Lets internal ops users (`SYSTEM_ADMIN`, `OPS_USER`) **preview** and **download**
the KYC/loan documents attached to a loan, from the **Documents** tab on the
loan-application detail page.

## Why this design

Document bytes are streamed only through the authenticated backend — there is no
public/permanent storage URL and no signed-URL infrastructure to operate. The
browser fetches the bytes (JWT in the `Authorization` header) into an ephemeral
`blob:` object URL and renders that. This is the existing download mechanism,
extended with an `inline` disposition for preview. Signed URLs were intentionally
not introduced: a controlled streaming endpoint already exists, keeps every
access audited, and avoids extra moving parts.

On the server, single-document download/preview streams bytes straight from object
storage (R2 or local filesystem) to the HTTP response without buffering the whole
file into JVM heap. Metadata lookup and the access-audit insert run in short,
separate database transactions so a pooled connection is never held across the
(potentially slow) storage round-trip.

## API contract

`GET /api/v1/internal/ops/loan-applications/{applicationId}/kyc-documents/{documentType}/content`

| Query param   | Values                  | Default      | Effect |
| ------------- | ----------------------- | ------------ | ------ |
| `disposition` | `attachment` \| `inline`| `attachment` | `attachment` → `Content-Disposition: attachment` (download). `inline` → `Content-Disposition: inline` (preview). |

Responses:

- `200` — body is the document bytes with the stored `Content-Type`, plus
  `Cache-Control: no-store` (KYC PII is never cached).
- `404 DOCUMENT_NOT_FOUND` — no such application/document, or content not
  LMS-managed.
- `415 DOCUMENT_PREVIEW_UNSUPPORTED` — `disposition=inline` requested for a
  content type not on the inline allowlist.
- `503 DOCUMENT_STORAGE_UNAVAILABLE` — storage backend transiently unavailable.

Bulk download is unchanged: `…/kyc-documents/download-all` (ZIP).

## Security controls

- **AuthN/Z** — endpoint is gated by `@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS_USER')")`
  on `LoanApplicationOpsController`; the internal ops console serves the LMS
  tenant. Tenant isolation is enforced by RLS (`V41`).
- **IDOR** — `applicationId` must resolve (else `404`); `documentType` is a typed
  enum path variable (invalid → `400`).
- **Unsafe rendering** — inline serving is restricted server-side to an allowlist
  (`application/pdf`, `image/jpeg`, `image/png`) in `DocumentPreviewSupport`,
  mirroring `DocumentUploadPolicy`. Scriptable formats (SVG/HTML) can never be
  served inline. `X-Content-Type-Options: nosniff` is applied globally by
  `SecurityConfig`. The API CSP (`default-src 'none'; frame-ancestors 'none'`)
  blocks framing the API itself; preview rendering uses ephemeral `blob:` URLs in
  the SPA, so any future SPA Content-Security-Policy must allow
  `frame-src blob:` and `img-src blob:` (or equivalent) or inline preview will
  stop rendering.
- **No URL/credential leakage** — the frontend never points an `<iframe>`/`<img>`
  at the API URL (that is blocked by `X-Frame-Options: DENY` and carries no auth
  header). It fetches bytes and renders a local `blob:` URL, revoked on close.

## Audit

Every access writes a `loan_application_document_access_audit` row with actor,
client IP, correlation id, byte count and document type:

- `SINGLE_DOCUMENT_PREVIEWED` — inline preview
- `SINGLE_DOCUMENT_DOWNLOADED` — single download
- `BULK_ZIP_DOWNLOADED` — ZIP download

Rows are visible via `…/document-access-audits`. No new migration was required —
the `action` column is `varchar(64)`.

Each preview open re-fetches bytes from storage and appends a new audit row (by
design for PII — no client-side caching). At scale, plan a retention/archival
policy for `loan_application_document_access_audit` and monitor object-storage
egress if ops users repeatedly preview large documents.

## UI behaviour (Documents tab)

- **View** opens `DocumentPreviewModal`: PDFs render in a sandboxed `<iframe>`,
  images in `<img>`. States covered: loading, ready, unsupported (download-only
  fallback), error (friendly message), and the tab-level empty/loading/error
  states.
- **Download** streams the original with its correct filename and content type.
- Supported preview formats mirror the upload allowlist: PDF, JPEG, PNG. Anything
  else degrades gracefully to download-only.

## Tests

- Backend — `DocumentPreviewSupportTest` (allowlist), and
  `LoanApplicationOpsControllerDocumentDownloadAuditTest` (inline → `inline`
  disposition + `SINGLE_DOCUMENT_PREVIEWED` audit; default → attachment +
  `SINGLE_DOCUMENT_DOWNLOADED`).
- Frontend — `DocumentPreviewModal.test.tsx` (renderer selection, fallbacks,
  object-URL revoke) and `DocumentsTab.test.tsx` (View opens the modal and
  requests `disposition=inline`).
