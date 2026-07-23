# Stack Detection and Tool Routing

## Contents

1. Universal selection rules
2. Node.js and TypeScript
3. Java and Kotlin
4. Python
5. Go
6. .NET
7. Rust
8. Ruby
9. PHP
10. Mixed repositories and optional tools

## 1. Universal selection rules

Run `scripts/detect_backend_stack.py` first, then verify manifests and CI. Prefer commands already used by the repository.

Expected JSON output includes:

- `build_roots`: aggregators and independently buildable roots.
- `backend_roots`: roots with backend source or a detected server framework.
- `languages` and `frameworks`, each with file/manifest evidence.
- `backend_package_managers` and `backend_analysis_tools` for command selection.
- `backend_runtime_integrations` for integrations evidenced by detected backend roots, plus
  `repository_runtime_integrations` for the unfiltered repository-wide view. These include
  databases, ORMs, brokers, caches, storage, and migration tools.
- `architecture_signals`, `ci_files`, and `suggested_existing_commands`.
- Repository-wide package managers/tools separately, so mixed frontend tooling is not mistaken for a backend command.

An empty array means “not detected,” not “not present.” Verify unusual layouts manually.

Tool priority:

1. Checked-in verification script or CI command.
2. Checked-in wrapper and pinned plugin.
3. Declared package script/tool dependency.
4. Language built-in that does not alter dependencies.
5. Temporary analyzer only with approval.

Before every command, determine whether it can:

- Download dependencies or contact an external service.
- Rewrite code, lockfiles, generated sources, snapshots, migrations, or schema files.
- Start containers, databases, brokers, emulators, or daemons.
- Read secrets or production-like credentials.
- Mutate a local/shared database.

Use report/check/dry-run modes. Never run `--fix`, formatting writes, dependency upgrades, migration apply, or destructive cleanup in review mode.

## 2. Node.js and TypeScript

### Detection

- `package.json`, lockfile, workspaces.
- Framework dependencies: NestJS, Express, Fastify, Koa, Hapi, Adonis, Hono, serverless functions.
- ORM/data: Prisma, TypeORM, Sequelize, Knex, Drizzle, Mongoose.
- Existing scripts: `verify`, `check`, `typecheck`, `lint`, `test`, `coverage`, `build`.

### Preferred commands

Use the detected package manager and declared scripts:

```bash
npm run typecheck
npm run lint
npm test
npm run test:coverage
npm run build
```

Equivalent `pnpm`, `yarn`, or `bun run` commands are preferred when their lockfile is authoritative.

Existing analyzers may include ESLint/TypeScript ESLint, Biome, Knip, Fallow, dependency-cruiser, Madge, c8/nyc, Jest/Vitest, Stryker, Semgrep, CodeQL, or framework CLIs.

### Manual emphasis

- Promise rejection and lost `await` paths.
- Event-loop blocking, unbounded concurrency, timers, streams, and connection cleanup.
- Express/Nest/Fastify middleware order and error translation.
- Runtime validation versus TypeScript-only types.
- ORM eager/lazy loading, transaction propagation, and pool configuration.
- Package entry points, dynamic imports, decorators, DI, and route discovery before declaring code unused.

## 3. Java and Kotlin

### Detection

- `pom.xml`, `mvnw`, Gradle files, `gradlew`, toolchains, Java/Kotlin versions.
- Spring Boot, Quarkus, Micronaut, Jakarta EE, Vert.x, Ktor.
- JPA/Hibernate, jOOQ, MyBatis, JDBC, Flyway, Liquibase.
- Maven/Gradle plugins for JaCoCo, Checkstyle, PMD, SpotBugs, Error Prone, Detekt, ArchUnit, PIT, OWASP dependency-check.

### Preferred commands

Use wrappers when present:

```bash
./mvnw test
./mvnw verify
./gradlew test
./gradlew check
```

Run plugin-specific goals only when configured or already available; arbitrary goals may download plugins or produce misleading defaults.

Useful configured checks include:

```bash
./mvnw dependency:analyze
./mvnw spotbugs:check
./mvnw pmd:check
./gradlew jacocoTestReport
./gradlew detekt
```

Treat `dependency:analyze` reflection/service-loader/framework results cautiously. Dependency version and vulnerability checks commonly require network access.

### Manual emphasis

- Spring proxy boundaries: `@Transactional`, `@Async`, caching, security, and self-invocation.
- Exception translation and rollback rules, especially checked exceptions.
- JPA fetch plans, Open Session in View, N+1 queries, cascade/orphan behavior, entity equality, optimistic locking.
- Controller validation groups, status mapping, idempotency, and backward-compatible DTOs.
- Thread pools, scheduled jobs, singleton mutable state, blocking work on reactive threads.
- Reflection, annotations, component scanning, repositories, serialization, and migration callbacks before dead-code classification.

## 4. Python

### Detection

- `pyproject.toml`, requirements files, Poetry, uv, Pipenv, tox/nox.
- Django, Flask, FastAPI/Starlette, Celery, SQLAlchemy, Django ORM, Alembic.
- Pytest, coverage.py, Ruff, mypy/pyright, Pylint, Bandit, Vulture, Radon, Semgrep.

### Preferred commands

Use the repository environment and configuration:

```bash
python -m pytest
python -m pytest --cov
python -m ruff check .
python -m mypy .
```

Do not create or replace an environment, install packages globally, or regenerate a lockfile without approval.

### Manual emphasis

- Sync work inside async endpoints and missing cancellation/timeouts.
- Mutable module/class defaults and process-global state.
- ORM lazy loads inside loops and transaction/session lifetime.
- Exception swallowing, broad `except`, retry decorators, Celery acknowledgment semantics.
- Dynamic imports, Django app discovery, routes, signals, migrations, and CLI registration before dead-code claims.

## 5. Go

### Detection

- `go.mod`, workspace files, Make/Task targets.
- net/http, Gin, Echo, Fiber, Chi, gRPC; database/sql, sqlx, GORM, Ent.
- golangci-lint config, Staticcheck, govulncheck, race-enabled tests.

### Preferred commands

```bash
go test ./...
go test -race ./...
go vet ./...
```

Use `golangci-lint run`, `staticcheck ./...`, or `govulncheck ./...` only when installed/configured or approved. Go commands may download missing modules; inspect cache/module state first.

### Manual emphasis

- Goroutine leaks, blocked channels, loop-variable capture, unsafe map access.
- Context propagation, deadlines, cancellation, and response-body closure.
- Transaction/error handling and ignored errors.
- Worker shutdown, retry loops, and at-least-once job delivery.
- Interface wrappers that obscure direct dependencies without enabling substitution.

## 6. .NET

Detect `.sln`, `.csproj`, SDK pinning, ASP.NET Core, EF Core, analyzers, StyleCop, coverlet, NetArchTest, and test projects.

Preferred existing commands:

```bash
dotnet restore --locked-mode
dotnet build --no-restore
dotnet test --no-build
dotnet format --verify-no-changes
```

Do not run restore if it requires unapproved network access. Review async-over-sync, `CancellationToken`, `HttpClient` lifetime, DI scopes, EF query materialization, transaction scopes, authorization policies, hosted services, and nullable-reference boundaries.

## 7. Rust

Detect Cargo workspaces, Actix/Axum/Rocket, Tokio, SQLx/Diesel, Clippy, rustfmt, cargo-audit/deny, and test features.

```bash
cargo check --all-targets
cargo test --all-targets
cargo clippy --all-targets -- -D warnings
cargo fmt --check
```

Network may be required for uncached crates. Review `unsafe`, blocking work on async runtimes, cancellation, panic boundaries, lock ordering, task lifetime, SQL transaction handling, and feature-gated code.

## 8. Ruby

Detect Bundler, Rails/Sinatra, RSpec/Minitest, RuboCop, Brakeman, SimpleCov, ActiveRecord, and Sidekiq.

Prefer `bundle exec` commands already configured. Review callbacks, transactions, N+1 queries, mass assignment, job uniqueness/retries, autoloading, constant discovery, and migrations. Do not classify Rails-discovered classes as dead from static references alone.

## 9. PHP

Detect Composer, Laravel/Symfony, PHPUnit/Pest, PHPStan/Psalm, PHP-CS-Fixer check mode, Doctrine/Eloquent, and migrations.

Prefer `composer` scripts or `vendor/bin` pinned tools. Review request validation, authorization voters/policies, ORM lazy loading, queue retries, service-container discovery, serialization, transaction scope, and exception-to-response mapping.

## 10. Mixed repositories and optional tools

For polyglot services:

- Map each entry point to its manifest and runtime.
- Run tools from the owning service root.
- Keep coverage and findings attributed to the correct module.
- Inspect cross-service API/event contracts and generated clients separately.

Optional tools must not be installed automatically. Before proposing one, state:

- The evidence gap it closes.
- Why existing tooling cannot close it.
- Install location and whether manifests/lockfiles change.
- Network or source-upload behavior.
- Cleanup plan for temporary artifacts.

Prefer a targeted manual inspection over introducing a heavyweight scanner for a one-off question.
