# Backend

Spring Boot service for the LMS platform.

## Prerequisites

- Java 21
- Maven 3.9+
- Local infra from `infra/docker-compose.yml`

## Run locally

```bash
cd backend
mvn spring-boot:run
```

The default profile is `local`, which expects:

- PostgreSQL on `localhost:5432`
- Redis on `localhost:6379`
- RabbitMQ on `localhost:5672`
- MinIO on `localhost:9000`

## Bootstrap auth

The service exposes `POST /api/v1/auth/token` for initial local auth bootstrapping.

Default local credentials:

- Username: `ops.admin`
- Password: `ChangeMe123!`

Set `APP_SECURITY_JWT_SECRET` before running outside local development.
