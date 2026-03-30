# Infrastructure

Local development stack for the LMS platform.

## Services

- PostgreSQL for transactional data
- Redis for caching and rate limiting
- RabbitMQ for async workflows
- MinIO for S3-compatible document storage
- MailHog for local SMTP capture

## Usage

1. Copy `.env.example` to `.env` if you need to override defaults.
2. Start the stack:

```powershell
docker compose -f infra/docker-compose.yml up -d
```

3. Stop the stack:

```powershell
docker compose -f infra/docker-compose.yml down
```

## Default Endpoints

- PostgreSQL: `localhost:5432`
- Redis: `localhost:6379`
- RabbitMQ UI: `http://localhost:15672`
- MinIO API: `http://localhost:9000`
- MinIO Console: `http://localhost:9001`
- MailHog UI: `http://localhost:8025`
