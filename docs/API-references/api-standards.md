# LMS API Standards

This document defines the response contract expected from LMS APIs.

## 1. Success Response Shape

### Single resource endpoints

Single resource endpoints return the resource payload directly.

Tenant-facing LSP success payloads may deliberately mask high-risk PII in otherwise successful responses. Masking is part of the contract, not an error condition.

Example:

```json
{
  "id": "0f9a8f53-7f77-4a88-9a26-5b8e2b3ef201",
  "externalLoanId": "EXT-001",
  "status": "INITIALIZED",
  "borrowerFullName": "Rahul Shah",
  "productId": "9b2de3cb-8c55-4d0d-8d6e-8a6f1b1a6d11"
}
```

### Collection endpoints

Collection endpoints return a raw array. They do not wrap items in `data`, `items`, or `content`.

For LSP loan-application endpoints, masked-by-default fields include Aadhaar, PAN, bank account number, IFSC, account holder name, employee ID, and reference contact details. Full PII is exposed only through dedicated privileged reveal endpoints.

Example:

```json
[
  {
    "id": "0f9a8f53-7f77-4a88-9a26-5b8e2b3ef201",
    "externalLoanId": "EXT-001",
    "status": "INITIALIZED"
  },
  {
    "id": "8fdab3a9-2b1d-4f88-b0ff-2ce1e5f4f122",
    "externalLoanId": "EXT-002",
    "status": "APPROVED_PENDING_DISBURSAL"
  }
]
```

### Empty success responses

Operations with no body return `204 No Content`.

### File and binary responses

File download endpoints return the binary body directly with the appropriate `Content-Type` and, where relevant, `Content-Disposition`.

## 2. Pagination Standard

Pagination applies to list endpoints that support `offset` and `limit`.

Request parameters:

- `offset`: zero-based starting row
- `limit`: maximum number of rows to return
- `paginationDetails`: set to `ON` to emit pagination metadata headers

When pagination metadata is requested, the response body remains a raw array and metadata is returned in headers:

```http
X-Total-Count: 57
X-Limit: 10
X-Offset: 20
```

Example:

```http
GET /api/v1/lsp/loan-applications?offset=20&limit=10&paginationDetails=ON
```

Response body:

```json
[
  {
    "id": "7d377ce0-88d2-49f6-bce0-4c4d4911bfa2",
    "lspLoanId": "LSP-021",
    "status": "INITIALIZED"
  }
]
```

## 3. Error Response Standard

All non-2xx responses return a common JSON structure.

Canonical fields:

- `timestamp`: server timestamp of the failure
- `status`: HTTP status code
- `code`: machine-readable error code
- `error`: machine-readable error code
- `message`: human-readable summary
- `path`: request path
- `correlationId`: request trace identifier
- `errorCode`: machine-readable error code
- `errorReason`: machine-readable error code
- `errorSource`: human-readable summary or field-level explanation
- `violations[]`: simple field/message pairs for compatibility
- `errors[]`: richer structured error details

Field meanings:

- `code`, `error`, `errorCode`, and `errorReason` currently carry the same machine-readable value.
- `message` is the top-level human-readable summary.
- `errorSource` is the top-level human-readable explanation.
- `violations[]` is intended for simpler consumers.
- `errors[]` is intended for richer, production-grade client handling.

### Generic error example

```json
{
  "timestamp": "2026-04-15T10:15:30.123Z",
  "status": 400,
  "code": "INVALID_REQUEST",
  "error": "INVALID_REQUEST",
  "message": "Idempotency-Key header is required.",
  "path": "/api/v1/lsp/loan-applications/123/invalid",
  "correlationId": "2f3f6cf7-0d7c-4ff9-88ef-e6e3a3e79b7b",
  "errorCode": "INVALID_REQUEST",
  "errorReason": "INVALID_REQUEST",
  "errorSource": "Idempotency-Key header is required.",
  "violations": [],
  "errors": [
    {
      "errorCode": "INVALID_REQUEST",
      "errorReason": "INVALID_REQUEST",
      "errorSource": "Idempotency-Key header is required.",
      "field": null,
      "message": "Idempotency-Key header is required."
    }
  ]
}
```

### Validation or business-rule error example

```json
{
  "timestamp": "2026-04-15T10:18:10.456Z",
  "status": 422,
  "code": "KYC_COMPLETION_REQUIRED",
  "error": "KYC_COMPLETION_REQUIRED",
  "message": "Loan application cannot be approved until required KYC documents are complete.",
  "path": "/api/v1/internal/ops/loan-applications/123/approve",
  "correlationId": "9f377a8a-1f73-4e5a-9d2d-8bb2a9cbe441",
  "errorCode": "KYC_COMPLETION_REQUIRED",
  "errorReason": "KYC_COMPLETION_REQUIRED",
  "errorSource": "Loan application cannot be approved until required KYC documents are complete.",
  "violations": [
    {
      "field": "PAN_CARD",
      "message": "PAN Card must be VERIFIED before approval."
    }
  ],
  "errors": [
    {
      "errorCode": "KYC_COMPLETION_REQUIRED",
      "errorReason": "KYC_COMPLETION_REQUIRED",
      "errorSource": "PAN_CARD: PAN Card must be VERIFIED before approval.",
      "field": "PAN_CARD",
      "message": "PAN Card must be VERIFIED before approval."
    }
  ]
}
```

### Authentication failure example

```json
{
  "timestamp": "2026-04-15T10:21:42.110Z",
  "status": 401,
  "code": "UNAUTHORIZED",
  "error": "UNAUTHORIZED",
  "message": "Authentication is required to access this resource.",
  "path": "/api/v1/lsp/loan-applications",
  "correlationId": "2f4fdcd2-cf01-4b5d-a9f0-d9f3cf64973f",
  "errorCode": "UNAUTHORIZED",
  "errorReason": "UNAUTHORIZED",
  "errorSource": "Authentication is required to access this resource.",
  "violations": [],
  "errors": [
    {
      "errorCode": "UNAUTHORIZED",
      "errorReason": "UNAUTHORIZED",
      "errorSource": "Authentication is required to access this resource.",
      "field": null,
      "message": "Authentication is required to access this resource."
    }
  ]
}
```

### Authorization failure example

```json
{
  "timestamp": "2026-04-15T10:22:08.901Z",
  "status": 403,
  "code": "ACCESS_DENIED",
  "error": "ACCESS_DENIED",
  "message": "Access denied",
  "path": "/api/v1/internal/admin/users",
  "correlationId": "0fc16b62-2525-4066-bff4-91f52f0d1b57",
  "errorCode": "ACCESS_DENIED",
  "errorReason": "ACCESS_DENIED",
  "errorSource": "Access denied",
  "violations": [],
  "errors": [
    {
      "errorCode": "ACCESS_DENIED",
      "errorReason": "ACCESS_DENIED",
      "errorSource": "Access denied",
      "field": null,
      "message": "Access denied"
    }
  ]
}
```

### Refresh token rejection example (`POST /api/v1/auth/refresh`)

When the refresh cookie is missing, expired, revoked, or otherwise unusable, the endpoint returns `401 Unauthorized` with a compact body (not the full `ApiError` envelope):

```json
{
  "code": "TOKEN_REVOKED",
  "message": "Refresh token was revoked"
}
```

| `code` | When |
| --- | --- |
| `MISSING_REFRESH_COOKIE` | No `lms-refresh` cookie on the request |
| `TOKEN_EXPIRED` | Unknown hash or `expiresAt` in the past |
| `TOKEN_REVOKED` | Row exists but `revoked = true` |
| `REFRESH_INVALID` | Orphan token (neither user nor API client) |

Clients may stash `code` for UX (e.g. distinguish natural expiry from admin revocation). Shipped 2026-06-08 ([#132](https://github.com/sid12701/lms/issues/132) / [PR #195](https://github.com/sid12701/lms/pull/195)).

### Document download / storage errors

Loan document download endpoints (`GET …/kyc-documents/{type}/content`, `GET …/documents.zip`) map storage failures through `GlobalExceptionHandler`:

**Not found (`404`)**

```json
{
  "timestamp": "2026-06-08T09:00:00.000Z",
  "status": 404,
  "code": "DOCUMENT_NOT_FOUND",
  "message": "Document content is not available for this checklist item.",
  "path": "/api/v1/internal/ops/loan-applications/{id}/kyc-documents/PAN_CARD/content",
  "correlationId": "…"
}
```

**Storage temporarily unavailable (`503`, retryable)**

```json
{
  "timestamp": "2026-06-08T09:00:00.000Z",
  "status": 503,
  "code": "DOCUMENT_STORAGE_UNAVAILABLE",
  "message": "Document storage is temporarily unavailable. Please retry.",
  "path": "/api/v1/internal/ops/loan-applications/{id}/kyc-documents/PAN_CARD/content",
  "correlationId": "…",
  "retryable": "true",
  "provider": "LOCAL"
}
```

Micrometer counter `lms.document.storage.unavailable` increments per 503 (tag `provider`). Alert spike rule deferred.

**Misconfigured provider (`500`)**

```json
{
  "timestamp": "2026-06-08T09:00:00.000Z",
  "status": 500,
  "code": "DOCUMENT_STORAGE_MISCONFIGURED",
  "message": "Document storage provider R2 is not configured (missing: accessKeyId).",
  "path": "/api/v1/internal/ops/loan-applications/{id}/kyc-documents/PAN_CARD/content",
  "correlationId": "…",
  "provider": "R2",
  "missingField": "accessKeyId"
}
```

Shipped 2026-06-08 ([#92](https://github.com/sid12701/lms/issues/92) / [PR #194](https://github.com/sid12701/lms/pull/194)).

### Password change required example

```json
{
  "timestamp": "2026-04-15T10:22:44.208Z",
  "status": 428,
  "code": "PASSWORD_CHANGE_REQUIRED",
  "error": "PASSWORD_CHANGE_REQUIRED",
  "message": "Password change is required before accessing internal routes",
  "path": "/api/v1/internal/system/context",
  "correlationId": "cc0ff6e9-8f16-48be-ae1b-7c55a2f2b3eb",
  "errorCode": "PASSWORD_CHANGE_REQUIRED",
  "errorReason": "PASSWORD_CHANGE_REQUIRED",
  "errorSource": "Password change is required before accessing internal routes",
  "violations": [],
  "errors": [
    {
      "errorCode": "PASSWORD_CHANGE_REQUIRED",
      "errorReason": "PASSWORD_CHANGE_REQUIRED",
      "errorSource": "Password change is required before accessing internal routes",
      "field": null,
      "message": "Password change is required before accessing internal routes"
    }
  ]
}
```

## 4. Status Code Conventions

- `200 OK`: successful read or mutation returning a body
- `201 Created`: use when a newly created resource is explicitly returned as created
- `204 No Content`: successful mutation with no response body
- `400 Bad Request`: malformed request, missing required inputs, invalid request combinations
- `401 Unauthorized`: authentication missing or invalid
- `403 Forbidden`: authenticated but not authorized
- `404 Not Found`: resource does not exist or is not visible in scope
- `409 Conflict`: uniqueness or idempotency conflict
- `503 Service Unavailable`: transient dependency failure (e.g. document storage outage); body may include `retryable: true`
- `422 Unprocessable Entity`: domain validation or business-rule failure
- `428 Precondition Required`: password change or similar required precondition before broader access
- `500 Internal Server Error`: unexpected server-side failure

## 5. Client Handling Guidance

- Use `status` for transport-level branching.
- Use `code` or `errorReason` for machine-driven client behavior.
- Use `message` for primary display text.
- Use `violations[]` or `errors[]` to map field-level feedback into forms.
- Log `correlationId` with client-side failures to support backend tracing.

## 6. Compatibility Rule

New endpoints should follow this standard from the start.

Existing endpoints should preserve current success payload shapes unless there is an explicit versioned breaking-change decision. Error responses should converge on the common error contract everywhere.
