# Backend

Spring Boot service for the LMS platform.

## Prerequisites

- Java 21
- Local infra from `infra/docker-compose.yml`

Maven is provided via the wrapper (`mvnw` / `mvnw.cmd`) — no system Maven required.

## Run locally

1. Set secrets in the **repo-root** `.env` (see `backend/.env.example` for variable names). Gitignored — do not commit.

2. Start the API from `backend/` (default profile `local` loads **repo-root** `.env` via `application-local.yml`):

```bash
./mvnw spring-boot:run        # macOS/Linux/Git Bash
mvnw.cmd spring-boot:run      # Windows PowerShell/cmd
```

Rate limiting is **off** by default in `local` (`app.rate-limit.enabled=false`) so the API starts without Redis. Set `APP_RATE_LIMIT_ENABLED=true` in the repo-root `.env` when Redis is up (`docker compose -f infra/docker-compose.yml up -d redis`).

RabbitMQ and mail still default to localhost unless overridden in the root `.env`. PostgreSQL comes from `LMS_DB_URL`, `LMS_DB_USERNAME`, and `LMS_DB_PASSWORD` in the **repo-root** `.env`.

### Supabase (remote PostgreSQL)

Use the **Session pooler** connection string from the Supabase dashboard (Connect → JDBC / URI). Do **not** use the direct host `db.<project-ref>.supabase.co` on Windows unless IPv6 DNS works end-to-end — it is often IPv6-only and Java fails with `UnknownHostException`.

```properties
LMS_DB_URL=jdbc:postgresql://aws-1-<region>.pooler.supabase.com:5432/postgres?sslmode=require
LMS_DB_USERNAME=postgres.<project-ref>
LMS_DB_PASSWORD=<database-password>
```

Port `5432` = session mode (required for Flyway). Port `6543` is transaction mode only.

The tenant connection pool auto-detects `pooler.supabase.com` URLs: it authenticates with the pooler user above, then runs `SET ROLE` to `APP_TENANT_DATASOURCE_USERNAME` (default `lms_tenant_app`). On PostgreSQL 16+ that `SET ROLE` requires an explicit `WITH SET TRUE` membership grant; migration `V96` applies it automatically. Restart the backend after changing database settings.

The session pooler caps clients at ~15. Both Hikari pools honor `spring.datasource.hikari.*` from `application-local.yml` (max 5 / min idle 2 each), keeping the combined maximum under the cap so Flyway and external scripts can still connect.

Optional: copy `src/main/resources/application-local.yml.example` if you need to customize non-secret local settings.

## Tests

From `backend/`:

```bash
./mvnw test          # macOS/Linux/Git Bash
mvnw.cmd test        # Windows
```

Compile test sources only: `mvnw.cmd test-compile`.

### Database safety (default `mvn test`)

Routine `mvn test` is safe when a repo-root `.env` points at a shared database:

1. **`IntegrationTestDatabaseTargetGuard`** — `IntegrationTestDatabaseCleaner` refuses non-ephemeral JDBC URLs unless `LMS_IT_EXTERNAL_DB=true` (with a logged warning).
2. **Testcontainers by default** — upload regression runs on ephemeral PostgreSQL (`DocumentUploadPostgresIntegrationTest`), not `@ActiveProfiles("local")`.
3. **Opt-in external DB** — `DocumentUploadExternalDbIntegrationTest` is tagged `external-db`, excluded from the default Surefire run. Run explicitly: `mvnw test -Pexternal-it` with `LMS_IT_EXTERNAL_DB=true`.

### Integration-test tenant scope

`TenantContextTestExecutionListener` (`src/test/java/com/bhawana/lms/support/`) applies admin datasource scope before each `@SpringBootTest` method so fixture setup can write across tenants. Tests that assert missing context opt out with `@RequiresEmptyTenantContext`. Registered in `src/test/resources/META-INF/spring.factories` and via `@TestExecutionListeners` on many controller tests; the `@LmsSpringBootTest` stereotype bundles the same listeners.

### IDE troubleshooting

The Maven reactor is rooted at the repo `pom.xml` (`lms` aggregator → `backend` module). If the editor reports unresolved imports for classes under `com.bhawana.lms.support` while `mvnw.cmd test-compile` succeeds, reload the Java language server (**Java: Clean Java Language Server Workspace** → Reload). Repo-wide editor settings live in `.vscode/settings.json`.

### Disbursement intent workflow (S3 / MNY-01)

When `app.disbursement.intent-workflow.enabled=true` (default in `application.yml`):

1. **Request** — `POST …/disbursement-requests` commits a `disbursement_intent` row before any bank call.
2. **Execute** — `LoanDisbursementWorker` claims intents with `SKIP LOCKED` and calls the provider outside a transaction.
3. **Outcome** — request log, intent state, and webhooks are written in a short follow-up transaction.

Ops money preview (Spec S12):

- `GET /api/v1/internal/ops/loan-applications/{id}/disbursement-preview` — principal, fee, net, payment mode, masked beneficiary (`beneficiarySource=LIVE_BORROWER` until Spec S5).
- `GET /api/v1/internal/ops/loan-applications/{id}/disbursement-reference` — durable `tranRefNo` from live intent (after Tx-A) or request log.

Integration tests default to the legacy inline path (`application-test.yml` sets intent workflow `enabled: false`). Opt-in: `DisbursementIntentWorkflowIntegrationTest` enables the workflow via `@TestPropertySource`.

Full record: `docs/implementation-log.md`.

### Partner repayment schedule validation (S20 / SCH-01)

`PUT /api/v1/lsp/loan-applications/{id}/repayment-schedule` with `mode: LSP_PROVIDED` always enforces principal integrity **and** date/interest discipline under `app.schedule.validation.*` (product-accepted defaults; checks always on).

| Bound | Default |
|---|---|
| First due after approval | 1–60 days |
| Cadence drift vs `firstDue + i months` | ±7 days |
| Horizon grace beyond tenure | 75 days |
| Interest row / total tolerance | max(₹10, 2%) / max(₹100, 1%) |

Partner contract and violation codes: `docs/partner-schedule-validation.md`. Platform `mode: GENERATED` schedules self-validate.

## Bootstrap auth

The service exposes `POST /api/v1/auth/login` for local user sign-in and `POST /api/v1/auth/token` for API client token issuance.

Local credentials are defined in the repo-root `.env`:

- `APP_SECURITY_BOOTSTRAP_USERNAME` (default in `backend/.env.example`: `ops.admin`)
- `APP_SECURITY_BOOTSTRAP_EMAIL` (optional; defaults from username)
- `APP_SECURITY_BOOTSTRAP_PASSWORD` (single password input — do not use deprecated `APP_SECURITY_BOOTSTRAP_LOGIN_PASSWORD`)

On-demand heal without restart (Spec S10): `POST /api/v1/internal/system/bootstrap-sync` (SYSTEM_ADMIN) re-runs `LocalBootstrapAdminSyncService`.

## Production / staging

Use a non-`local` Spring profile (for example `prod`). Startup **fails** if bootstrap password or JWT secret are missing, too short, or still set to development placeholders.
Do not commit the repo-root `.env` or real credentials in YAML.
