# Backend

Spring Boot service for the LMS platform.

## Prerequisites

- Java 21
- Local infra from `infra/docker-compose.yml`

Maven is provided via the wrapper (`mvnw` / `mvnw.cmd`) — no system Maven required.

## Run locally

```bash
cd backend
./mvnw spring-boot:run        # macOS/Linux/Git Bash
mvnw.cmd spring-boot:run      # Windows PowerShell/cmd
```

The default profile is `local`, which expects:

- PostgreSQL on `localhost:5432`
- Redis on `localhost:6379`
- RabbitMQ on `localhost:5672`
- MinIO on `localhost:9000`

## Bootstrap auth

The service exposes `POST /api/v1/auth/login` for local user sign-in and `POST /api/v1/auth/token` for API client token issuance.

Default local credentials:

- Username: `ops.admin`
- Password: `ChangeMe123!`

Set `APP_SECURITY_JWT_SECRET` before running outside local development.
