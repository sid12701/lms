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

## Bootstrap auth

The service exposes `POST /api/v1/auth/login` for local user sign-in and `POST /api/v1/auth/token` for API client token issuance.

Local credentials are defined in the repo-root `.env`:

- `APP_SECURITY_BOOTSTRAP_USERNAME` (default in `backend/.env.example`: `ops.admin`)
- `APP_SECURITY_BOOTSTRAP_PASSWORD`

## Production / staging

Use a non-`local` Spring profile (for example `prod`). Startup **fails** if bootstrap password or JWT secret are missing, too short, or still set to development placeholders.
Do not commit the repo-root `.env` or real credentials in YAML.
