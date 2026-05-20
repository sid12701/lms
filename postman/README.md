# LMS Postman Collection

Importable Postman collection that drives the full Bhawana LMS loan lifecycle end-to-end and surfaces the business-management demo views. Pairs with three manual UI verification steps on the React frontend.

## Files

| File | Purpose |
| --- | --- |
| `LMS.postman_collection.json` | v2.1 collection, 12 folders, ~45 requests |
| `LMS.postman_environment.json` | Environment variables (tokens, IDs, defaults) |
| `assets/sample-pan.pdf` | Demo PAN document for multipart upload |
| `assets/sample-aadhaar.pdf` | Demo Aadhaar document |
| `assets/sample-bank-statement.pdf` | Demo bank statement |
| `_build_collection.py` | Generator (re-emits the collection JSON) |

## Prerequisites

1. **Backend running** on `http://localhost:8080`:
   ```bash
   cd backend && ./mvnw spring-boot:run
   ```
   Profile `local`, Postgres on Supabase (see `backend/src/main/resources/application-local.yml`), Redis on `localhost:6379`, RabbitMQ on `localhost:5672`, MailHog/SMTP on `localhost:1025`.
2. **Frontend running** on `http://127.0.0.1:5173`:
   ```bash
   cd frontend && npm run dev
   ```
3. **Bootstrap admin seeded** by `LocalBootstrapAdminSyncService`: `ops.admin` / `ChangeMe123!` — first login forces a password change.

## Import & Run

1. In Postman: **Import** → drop both JSON files → select **LMS — Local** as the active environment.
2. Re-select multipart files in Postman desktop (Postman cannot persist file paths across machines):
   - Folder 6 → each upload request → Body → form-data → click the file row and pick `postman/assets/sample-pan.pdf` / `sample-aadhaar.pdf` / `sample-bank-statement.pdf`.
3. Open **Collection Runner**, pick this collection, enable **Save responses**, and run folders **0 → 10** top-to-bottom.
   - **Collection Runner is required** for Folder 8 (installment loop uses `postman.setNextRequest`) and Folder 10 (MIS poll loop). Hitting Send manually on a single request will not advance the loop.
4. Expect every request green. Variables populate automatically as the run progresses; no manual copy/paste.

### Newman (CLI)

```bash
newman run postman/LMS.postman_collection.json -e postman/LMS.postman_environment.json
```

The loop patterns work under Newman too.

## Auth variables

The environment now keeps auth state explicitly so you can inspect or override it without editing request bodies:

| Actor | Username var | Password vars | Token var |
| --- | --- | --- | --- |
| Admin | `adminUsername` | `adminBootstrapPassword`, `adminNewPassword`, `adminCurrentPassword` | `adminToken` |
| LSP UI user | `lspUiUsername` | `lspUiBootstrapPassword`, `lspUiNewPassword`, `lspUiCurrentPassword`, `lspUiPassword` | `lspUiToken` |
| LSP API client | `lspApiClientId` | `lspApiClientSecret` | `lspApiToken` |

Supporting flags:

- `adminPasswordChangeRequired`
- `lspUiPasswordChangeRequired`

Runtime behavior:

- Admin login sets `adminCurrentPassword` before the request and keeps it synced after inline password rotation.
- LSP UI user creation uses `lspUiBootstrapPassword`.
- LSP UI login sets `lspUiCurrentPassword` before the request and updates `lspUiCurrentPassword`, `lspUiPassword`, and `lspUiToken` after inline password rotation.
- Logout clears `adminToken`, `lspUiToken`, and `lspApiToken`.

## What the collection covers

| Folder | Flow |
| --- | --- |
| 0 | `/actuator/health` fail-fast |
| 1 | Bootstrap admin login + inline password rotation (handled inside the login request when `passwordChangeRequired=true`), metadata, whoami |
| 2 | Create LSP, list, configure webhook subscription |
| 3 | Create loan product, map to LSP, fetch product audit trail |
| 4 | Create LSP UI user (with inline rotation), create LSP API client, both logins — tokens saved to `{{lspUiToken}}` and `{{lspApiToken}}` |
| 5 | Create loan application (full borrower DTO) via LSP API |
| 6 | Upload PAN + Aadhaar + bank statement; admin verifies documents received |
| 7 | Move to AWAITING_APPROVAL → APPROVED_PENDING_DISBURSAL, LSP requests disbursement, admin resolves mock outcome, fetch 12-row repayment schedule |
| 8 | Loop-pay all 12 installments, verify `loanAccount.status === CLOSED`, fallback force-close if auto-close did not fire |
| 9 | Business-management demo views: portfolio KPI, DPD breakdown, ops alerts + acknowledge, webhook outbox + manual dispatch, borrower 360, loan lifecycle audit trail (status transitions, assignment events, document access audits) |
| 10 | MIS reports: async request + poll + download, synchronous CSV shortcut, JSON preview |
| 11 | Logout |

## Manual UI verification steps (after Folders 0–10 finish)

Open `http://127.0.0.1:5173` and log in as `ops.admin` using `{{adminNewPassword}}`:

1. **View loan as admin** — Loan Applications → search for `{{externalLoanId}}` (visible in the environment after Folder 5) → open detail. Confirm the status timeline, borrower panel, repayment schedule (all 12 installments `SETTLED`), and documents panel render.
2. **Download documents through UI** — on the same loan detail, open the verification documents panel and download PAN (and Aadhaar / bank statement). Files should save as PDFs matching the uploaded demo assets.
3. **Download MIS through UI** — Reports → MIS page → filter `lspId` to the demo LSP, request export, then download the row matching `{{reportRequestId}}`. Confirm the CSV opens in a spreadsheet.

Backend logs tag every request with an `X-Correlation-Id` injected by the collection-level pre-request script — useful for correlating Postman and UI actions.

## Troubleshooting

- **401 after admin login (preferred fix)** - set `adminPasswordChangeRequired=false` and `adminCurrentPassword` to the `adminNewPassword` value, then rerun.
- **401 after LSP UI login** - set `lspUiPasswordChangeRequired=false` and `lspUiCurrentPassword` to the `lspUiNewPassword` value, then rerun.

- **401 after admin login** — the bootstrap password is already rotated (rerun wipes env var state but the DB still holds the new password). Set `adminBootstrapPassword` in the environment to `{{adminNewPassword}}` value and rerun.
- **409 on LSP/product create** — the timestamp-based codes collide only if runs happen in the same second; hit Send once more.
- **Documents won't upload** — Postman desktop must have the file picker re-pointed at each `assets/*.pdf` file (see step 2 above). Newman picks them up by relative path automatically.
- **Disbursement auto-resolves unexpectedly** — the local profile uses mock disbursement; if the mock outcome POST 404s, the disbursement processor already ran. Check `GET /api/v1/internal/ops/loan-applications/{{applicationId}}` — status should already be `DISBURSED`.
- **MIS download empty / 404** — the report generator runs on a 15-second delay (see `app.reports.processing.fixed-delay-ms`). The poll loop retries up to 10 times; increase `reportPollAttempts` cap in the test script if your machine is slow.
- **Email notifications** — point `LMS_MAIL_HOST` / `LMS_MAIL_PORT` at MailHog (`localhost:1025`). Without it, the backend logs the email but skips SMTP.
- **Document storage** — the default provider is `R2`. For demos without R2 credentials, set `APP_STORAGE_DOCUMENTS_PROVIDER=FILE_SYSTEM` and uploads will land under `${java.io.tmpdir}/lms-documents-local`.
- **Webhook endpoint** — Folder 2 uses `https://webhook.site/{{webhookSiteId}}`. Replace `webhookSiteId` with a real UUID from webhook.site to watch live delivery, or leave as-is to exercise the outbox retry loop.

## Regenerating the collection

The collection JSON is built by `_build_collection.py`. After editing the generator:

```bash
python postman/_build_collection.py
```

## Out of scope

- Foreclosure flow (collection drives installment-driven closure only)
- LSP IP allowlist, password reset, PII reveal audits
- Admin delete flows (no delete-LSP / delete-product endpoints exist)
