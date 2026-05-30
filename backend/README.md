# Backend

Spring Boot service for the LMS platform.

## Prerequisites

- Java 21
- Local infra from `infra/docker-compose.yml`

Maven is provided via the wrapper (`mvnw` / `mvnw.cmd`) — no system Maven required.

## Run locally

1. Copy env template and set secrets (database URL, passwords, JWT secret):

```bash
cd backend
cp .env.example .env
# Edit .env — values are gitignored
```

2. Start the API (default profile `local` loads `backend/.env` via `application-local.yml`):

```bash
./mvnw spring-boot:run        # macOS/Linux/Git Bash
mvnw.cmd spring-boot:run      # Windows PowerShell/cmd
```

The `local` profile expects Redis, RabbitMQ, and mail on localhost unless overridden in `.env`.
PostgreSQL connection settings come entirely from `LMS_DB_URL`, `LMS_DB_USERNAME`, and `LMS_DB_PASSWORD` in `.env`.

Optional: copy `src/main/resources/application-local.yml.example` if you need to customize non-secret local settings.

## Bootstrap auth

The service exposes `POST /api/v1/auth/login` for local user sign-in and `POST /api/v1/auth/token` for API client token issuance.

Local credentials are defined in `.env`:

- `APP_SECURITY_BOOTSTRAP_USERNAME` (default in `.env.example`: `ops.admin`)
- `APP_SECURITY_BOOTSTRAP_PASSWORD`

## Production / staging

Use a non-`local` Spring profile (for example `prod`). Startup **fails** if bootstrap password or JWT secret are missing, too short, or still set to development placeholders.
Do not commit `backend/.env` or real credentials in YAML.
