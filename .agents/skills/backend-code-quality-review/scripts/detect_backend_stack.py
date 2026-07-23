#!/usr/bin/env python3
"""Read-only backend stack detector used by backend-code-quality-review."""

from __future__ import annotations

import argparse
import json
import os
from collections import Counter
from pathlib import Path
from typing import Any


IGNORED_DIRS = {
    ".git",
    ".gradle",
    ".idea",
    ".mypy_cache",
    ".pytest_cache",
    ".ruff_cache",
    ".tox",
    ".venv",
    "__pycache__",
    "bin",
    "build",
    "coverage",
    "dist",
    "node_modules",
    "obj",
    "out",
    "target",
    "vendor",
    "venv",
}

LANGUAGE_EXTENSIONS = {
    ".java": "Java",
    ".kt": "Kotlin",
    ".kts": "Kotlin",
    ".js": "JavaScript",
    ".mjs": "JavaScript",
    ".cjs": "JavaScript",
    ".ts": "TypeScript",
    ".mts": "TypeScript",
    ".cts": "TypeScript",
    ".py": "Python",
    ".go": "Go",
    ".cs": "C#",
    ".rs": "Rust",
    ".rb": "Ruby",
    ".php": "PHP",
    ".scala": "Scala",
    ".ex": "Elixir",
    ".exs": "Elixir",
}

BACKEND_MARKERS = {
    "pom.xml",
    "build.gradle",
    "build.gradle.kts",
    "settings.gradle",
    "settings.gradle.kts",
    "go.mod",
    "Cargo.toml",
    "Gemfile",
    "composer.json",
    "mix.exs",
    "requirements.txt",
    "Pipfile",
    "poetry.lock",
    "uv.lock",
    "pyproject.toml",
}

ARCHITECTURE_NAMES = {
    "adapter",
    "adapters",
    "api",
    "application",
    "controller",
    "controllers",
    "domain",
    "handler",
    "handlers",
    "infrastructure",
    "job",
    "jobs",
    "migration",
    "migrations",
    "model",
    "models",
    "repository",
    "repositories",
    "route",
    "routes",
    "scheduler",
    "service",
    "services",
    "usecase",
    "usecases",
    "worker",
    "workers",
}


def read_text(path: Path, limit: int = 1_000_000) -> str:
    try:
        with path.open("r", encoding="utf-8", errors="ignore") as handle:
            return handle.read(limit)
    except OSError:
        return ""


def relative(path: Path, root: Path) -> str:
    try:
        return path.relative_to(root).as_posix() or "."
    except ValueError:
        return path.as_posix()


def walk_repository(root: Path) -> tuple[list[Path], list[Path]]:
    files: list[Path] = []
    directories: list[Path] = []
    for current, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(name for name in dirnames if name not in IGNORED_DIRS)
        current_path = Path(current)
        directories.extend(current_path / name for name in dirnames)
        files.extend(current_path / name for name in filenames)
    return files, directories


def contains_any(text: str, needles: list[str]) -> bool:
    lowered = text.lower()
    return any(needle.lower() in lowered for needle in needles)


def detect_frameworks(files: list[Path], root: Path) -> list[dict[str, Any]]:
    manifest_names = {
        "package.json",
        "pom.xml",
        "build.gradle",
        "build.gradle.kts",
        "pyproject.toml",
        "requirements.txt",
        "Pipfile",
        "go.mod",
        "Cargo.toml",
        "Gemfile",
        "composer.json",
        "mix.exs",
        "global.json",
    }
    manifests = [path for path in files if path.name in manifest_names or path.suffix == ".csproj"]
    rules = {
        "Spring Boot": ["spring-boot", "org.springframework.boot"],
        "Quarkus": ["quarkus"],
        "Micronaut": ["micronaut"],
        "Jakarta EE": ["jakarta.platform", "jakarta.jakartaee"],
        "NestJS": ["@nestjs/core"],
        "Express": ["\"express\"", "'express'"],
        "Fastify": ["fastify"],
        "Koa": ["\"koa\""],
        "Hapi": ["@hapi/hapi"],
        "AdonisJS": ["@adonisjs/core"],
        "Hono": ["\"hono\""],
        "Django": ["django"],
        "Flask": ["flask"],
        "FastAPI": ["fastapi"],
        "Starlette": ["starlette"],
        "Celery": ["celery"],
        "Gin": ["github.com/gin-gonic/gin"],
        "Echo": ["github.com/labstack/echo"],
        "Fiber": ["github.com/gofiber/fiber"],
        "Chi": ["github.com/go-chi/chi"],
        "gRPC": ["grpc"],
        "ASP.NET Core": ["microsoft.aspnetcore", "web.sdk"],
        "Axum": ["axum"],
        "Actix Web": ["actix-web"],
        "Rocket": ["rocket"],
        "Ruby on Rails": ["rails"],
        "Sinatra": ["sinatra"],
        "Laravel": ["laravel/framework"],
        "Symfony": ["symfony/framework-bundle"],
        "Phoenix": ["phoenix"],
    }
    found: dict[str, set[str]] = {}
    for manifest in manifests:
        text = read_text(manifest)
        for framework, needles in rules.items():
            if contains_any(text, needles):
                found.setdefault(framework, set()).add(relative(manifest, root))
    return [
        {"name": name, "evidence": sorted(evidence)}
        for name, evidence in sorted(found.items())
    ]


def detect_package_managers(files: list[Path], root: Path) -> list[dict[str, Any]]:
    rules = {
        "Maven Wrapper": ["mvnw"],
        "Maven": ["pom.xml"],
        "Gradle Wrapper": ["gradlew"],
        "Gradle": ["build.gradle", "build.gradle.kts"],
        "pnpm": ["pnpm-lock.yaml", "pnpm-workspace.yaml"],
        "Yarn": ["yarn.lock"],
        "Bun": ["bun.lock", "bun.lockb"],
        "npm": ["package-lock.json"],
        "Poetry": ["poetry.lock"],
        "uv": ["uv.lock"],
        "Pipenv": ["Pipfile.lock", "Pipfile"],
        "pip": ["requirements.txt"],
        "Go modules": ["go.mod"],
        "Cargo": ["Cargo.lock", "Cargo.toml"],
        "Bundler": ["Gemfile.lock", "Gemfile"],
        "Composer": ["composer.lock", "composer.json"],
        ".NET/NuGet": ["packages.lock.json", "global.json"],
        "Mix": ["mix.lock", "mix.exs"],
    }
    by_name: dict[str, list[Path]] = {}
    for path in files:
        by_name.setdefault(path.name, []).append(path)
    detected = []
    for manager, names in rules.items():
        evidence = sorted({relative(path, root) for name in names for path in by_name.get(name, [])})
        if evidence:
            detected.append({"name": manager, "evidence": evidence})
    dotnet_projects = sorted(relative(path, root) for path in files if path.suffix == ".csproj")
    if dotnet_projects and not any(row["name"] == ".NET/NuGet" for row in detected):
        detected.append({"name": ".NET/NuGet", "evidence": dotnet_projects})
    return detected


def detect_tools(files: list[Path], root: Path) -> list[dict[str, Any]]:
    config_rules = {
        "Checkstyle": ["checkstyle.xml", "checkstyle-suppressions.xml"],
        "PMD": ["pmd.xml", "ruleset.xml"],
        "SpotBugs": ["spotbugs-exclude.xml", "findbugs-exclude.xml"],
        "SonarQube": ["sonar-project.properties"],
        "Semgrep": [".semgrep.yml", ".semgrep.yaml"],
        "golangci-lint": [".golangci.yml", ".golangci.yaml"],
        "Ruff": ["ruff.toml", ".ruff.toml"],
        "ESLint": ["eslint.config.js", "eslint.config.mjs", "eslint.config.cjs", ".eslintrc"],
        "Biome": ["biome.json", "biome.jsonc"],
        "Detekt": ["detekt.yml", "detekt.yaml"],
        "ArchUnit": ["archunit.properties"],
    }
    dependency_rules = {
        "JaCoCo": ["jacoco"],
        "Checkstyle": ["checkstyle"],
        "PMD": ["maven-pmd", "pmd"],
        "SpotBugs": ["spotbugs"],
        "ArchUnit": ["archunit"],
        "Error Prone": ["error_prone", "error-prone"],
        "Pytest": ["pytest"],
        "coverage.py": ["coverage"],
        "Ruff": ["ruff"],
        "mypy": ["mypy"],
        "Pylint": ["pylint"],
        "Bandit": ["bandit"],
        "Vulture": ["vulture"],
        "Radon": ["radon"],
        "golangci-lint": ["golangci-lint"],
        "Staticcheck": ["staticcheck"],
        "govulncheck": ["govulncheck"],
        "Clippy": ["clippy"],
        "RuboCop": ["rubocop"],
        "PHPStan": ["phpstan"],
        "Psalm": ["vimeo/psalm", "psalm"],
    }
    found: dict[str, set[str]] = {}
    by_name: dict[str, list[Path]] = {}
    for path in files:
        by_name.setdefault(path.name, []).append(path)
    for tool, names in config_rules.items():
        for name in names:
            for path in by_name.get(name, []):
                found.setdefault(tool, set()).add(relative(path, root))
    manifests = [
        path
        for path in files
        if path.name
        in {
            "package.json",
            "pom.xml",
            "build.gradle",
            "build.gradle.kts",
            "pyproject.toml",
            "requirements.txt",
            "Pipfile",
            "go.mod",
            "Cargo.toml",
            "Gemfile",
            "composer.json",
        }
        or path.suffix == ".csproj"
    ]
    for manifest in manifests:
        text = read_text(manifest)
        if manifest.name == "package.json":
            try:
                payload = json.loads(text)
            except json.JSONDecodeError:
                payload = {}
            dependency_names = {
                name
                for field in ("dependencies", "devDependencies", "optionalDependencies", "peerDependencies")
                for name in (payload.get(field, {}) if isinstance(payload, dict) else {})
            }
            node_tools = {
                "ESLint": lambda name: name == "eslint" or name.startswith("eslint-"),
                "Biome": lambda name: name == "@biomejs/biome",
                "Jest": lambda name: name == "jest",
                "Vitest": lambda name: name == "vitest",
                "c8": lambda name: name == "c8",
                "nyc": lambda name: name == "nyc",
                "Knip": lambda name: name == "knip",
                "Fallow": lambda name: name in {"fallow", "@fallow-cli/fallow-node"},
            }
            for tool, predicate in node_tools.items():
                if any(predicate(name) for name in dependency_names):
                    found.setdefault(tool, set()).add(relative(manifest, root))
            continue
        for tool, needles in dependency_rules.items():
            if contains_any(text, needles):
                found.setdefault(tool, set()).add(relative(manifest, root))
    return [
        {"name": name, "evidence": sorted(evidence)}
        for name, evidence in sorted(found.items())
    ]


def build_roots(files: list[Path], root: Path) -> list[str]:
    return sorted(
        {
            relative(path.parent, root)
            for path in files
            if path.name in BACKEND_MARKERS or path.suffix == ".csproj"
        }
    )


def backend_roots(files: list[Path], root: Path, frameworks: list[dict[str, Any]]) -> list[str]:
    candidates: set[Path] = set()
    framework_manifests = {item for row in frameworks for item in row["evidence"]}
    for path in files:
        if path.name in {"pom.xml", "build.gradle", "build.gradle.kts"}:
            has_source = (path.parent / "src").is_dir()
        elif path.name == "go.mod":
            has_source = any(
                candidate.suffix.lower() == ".go" and path.parent in candidate.parents
                for candidate in files
            )
        elif path.name in {"Cargo.toml", "Gemfile", "composer.json", "mix.exs"}:
            has_source = any((path.parent / name).is_dir() for name in ("src", "app", "lib"))
        elif path.name in {"pyproject.toml", "requirements.txt", "Pipfile"}:
            has_source = any(
                candidate.suffix.lower() == ".py" and path.parent in candidate.parents
                for candidate in files
            )
        elif path.suffix == ".csproj":
            has_source = any(
                candidate.suffix.lower() == ".cs" and path.parent in candidate.parents
                for candidate in files
            )
        else:
            has_source = False
        if (path.name in BACKEND_MARKERS or path.suffix == ".csproj") and has_source:
            candidates.add(path.parent)
        elif path.name == "package.json" and relative(path, root) in framework_manifests:
            candidates.add(path.parent)
    return sorted(relative(path, root) for path in candidates)


def suggested_commands(
    files: list[Path],
    root: Path,
    managers: list[dict[str, Any]],
    tools: list[dict[str, Any]],
    detected_backend_roots: list[str],
) -> list[dict[str, str]]:
    names = {item["name"] for item in managers}
    tool_names = {item["name"] for item in tools}
    commands: list[dict[str, str]] = []
    file_names = {path.name for path in files}

    def command(
        purpose: str, value: str, directory: Path | None = None
    ) -> dict[str, str]:
        row = {"purpose": purpose, "command": value}
        if directory is not None:
            row["cwd"] = relative(directory, root)
        return row

    maven_wrappers = [path for path in files if path.name == "mvnw"]
    gradle_wrappers = [path for path in files if path.name == "gradlew"]
    if "Maven Wrapper" in names:
        for wrapper in maven_wrappers:
            cwd = relative(wrapper.parent, root)
            commands.extend(
                [
                    {"purpose": "test", "command": "./mvnw test", "cwd": cwd},
                    {"purpose": "full verification", "command": "./mvnw verify", "cwd": cwd},
                ]
            )
    elif "Maven" in names:
        for manifest in (path for path in files if path.name == "pom.xml"):
            if relative(manifest.parent, root) in detected_backend_roots:
                commands.extend(
                    [
                        command("test", "mvn test", manifest.parent),
                        command("full verification", "mvn verify", manifest.parent),
                    ]
                )
    if "Gradle Wrapper" in names:
        for wrapper in gradle_wrappers:
            commands.append(
                {
                    "purpose": "full verification",
                    "command": "./gradlew check",
                    "cwd": relative(wrapper.parent, root),
                }
            )
    elif "Gradle" in names:
        for manifest in (
            path for path in files if path.name in {"build.gradle", "build.gradle.kts"}
        ):
            if relative(manifest.parent, root) in detected_backend_roots:
                commands.append(command("full verification", "gradle check", manifest.parent))

    backend_directories = {
        (root / backend_root).resolve() if backend_root != "." else root
        for backend_root in detected_backend_roots
    }
    package_jsons = [
        path
        for path in files
        if path.name == "package.json"
        and any(directory == path.parent or directory in path.parents for directory in backend_directories)
    ]
    for package_json in package_jsons:
        try:
            payload = json.loads(read_text(package_json))
        except json.JSONDecodeError:
            continue
        scripts = payload.get("scripts", {}) if isinstance(payload, dict) else {}
        ancestors = [package_json.parent, *package_json.parents]
        manager = "npm run"
        for ancestor in ancestors:
            if ancestor == root.parent:
                break
            if (ancestor / "pnpm-lock.yaml").exists():
                manager = "pnpm"
                break
            if (ancestor / "yarn.lock").exists():
                manager = "yarn"
                break
            if (ancestor / "bun.lock").exists() or (ancestor / "bun.lockb").exists():
                manager = "bun run"
                break
            if (ancestor / "package-lock.json").exists():
                manager = "npm run"
                break
        cwd = relative(package_json.parent, root)
        for script in ("verify", "check", "typecheck", "lint", "test", "test:coverage", "coverage", "build"):
            if script in scripts:
                command = f"{manager} {script}" if manager != "npm run" else f"npm run {script}"
                commands.append({"purpose": script, "command": command, "cwd": cwd})

    if "pyproject.toml" in file_names or "requirements.txt" in file_names or "Pipfile" in file_names:
        for backend_root in detected_backend_roots:
            directory = root if backend_root == "." else root / backend_root
            if not any((directory / name).exists() for name in ("pyproject.toml", "requirements.txt", "Pipfile")):
                continue
            local_tools = {
                row["name"]
                for row in tools
                if any(
                    (root / evidence).resolve().parent == directory.resolve()
                    for evidence in row.get("evidence", [])
                )
            }
            if "Pytest" in local_tools:
                commands.append(command("test", "python -m pytest", directory))
            if "Ruff" in local_tools:
                commands.append(command("lint", "python -m ruff check .", directory))
            if "mypy" in local_tools:
                commands.append(command("typecheck", "python -m mypy .", directory))

    if "Go modules" in names:
        for manifest in (path for path in files if path.name == "go.mod"):
            if relative(manifest.parent, root) in detected_backend_roots:
                commands.extend(
                    [
                        command("test", "go test ./...", manifest.parent),
                        command("race test", "go test -race ./...", manifest.parent),
                        command("static analysis", "go vet ./...", manifest.parent),
                    ]
                )
    if "Cargo" in names:
        for manifest in (path for path in files if path.name == "Cargo.toml"):
            if relative(manifest.parent, root) in detected_backend_roots:
                commands.extend(
                    [
                        command("test", "cargo test --all-targets", manifest.parent),
                        command(
                            "lint",
                            "cargo clippy --all-targets -- -D warnings",
                            manifest.parent,
                        ),
                    ]
                )
    dotnet_roots = {
        path.parent
        for path in files
        if path.suffix in {".sln", ".csproj"}
        and relative(path.parent, root) in detected_backend_roots
    }
    for directory in sorted(dotnet_roots):
        commands.extend(
            [
                command("build", "dotnet build --no-restore", directory),
                command("test", "dotnet test --no-build", directory),
            ]
        )
    return commands


def detect_runtime_integrations(files: list[Path], root: Path) -> list[dict[str, Any]]:
    rules = {
        "PostgreSQL": ["postgresql", "org.postgresql", "psycopg", "asyncpg"],
        "MySQL/MariaDB": ["mysql", "mariadb"],
        "Redis": ["redis", "lettuce", "jedis"],
        "RabbitMQ/AMQP": ["rabbitmq", "spring-boot-starter-amqp", "amqplib", "pika"],
        "Kafka": ["kafka"],
        "Amazon S3": ["software.amazon.awssdk", "@aws-sdk/client-s3", "boto3"],
        "JPA/Hibernate": ["spring-boot-starter-data-jpa", "hibernate-core"],
        "Prisma": ["@prisma/client", "prisma"],
        "TypeORM": ["typeorm"],
        "Sequelize": ["sequelize"],
        "Mongoose": ["mongoose"],
        "SQLAlchemy": ["sqlalchemy"],
        "Flyway": ["flyway"],
        "Liquibase": ["liquibase"],
        "Alembic": ["alembic"],
    }
    manifests = [
        path
        for path in files
        if path.name in BACKEND_MARKERS | {"package.json", "composer.json", "mix.exs"}
        or path.suffix == ".csproj"
    ]
    found: dict[str, set[str]] = {}
    for manifest in manifests:
        text = read_text(manifest)
        for integration, needles in rules.items():
            if contains_any(text, needles):
                found.setdefault(integration, set()).add(relative(manifest, root))
    return [
        {"name": name, "evidence": sorted(evidence)}
        for name, evidence in sorted(found.items())
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description="Detect backend stack and existing review tooling")
    parser.add_argument("root", nargs="?", default=".", help="repository or service root")
    args = parser.parse_args()
    root = Path(args.root).expanduser().resolve()
    if not root.is_dir():
        parser.error(f"not a directory: {root}")

    files, directories = walk_repository(root)
    extension_counts = Counter(path.suffix.lower() for path in files)
    languages = [
        {
            "name": language,
            "file_count": sum(extension_counts[ext] for ext, value in LANGUAGE_EXTENSIONS.items() if value == language),
            "evidence": sorted(
                relative(path, root)
                for path in files
                if LANGUAGE_EXTENSIONS.get(path.suffix.lower()) == language
            )[:5],
        }
        for language in sorted(set(LANGUAGE_EXTENSIONS.values()))
        if any(extension_counts[ext] for ext, value in LANGUAGE_EXTENSIONS.items() if value == language)
    ]
    languages.sort(key=lambda item: (-item["file_count"], item["name"]))
    frameworks = detect_frameworks(files, root)
    managers = detect_package_managers(files, root)
    tools = detect_tools(files, root)
    detected_backend_roots = backend_roots(files, root, frameworks)
    backend_directories = {
        (root / item).resolve() if item != "." else root for item in detected_backend_roots
    }

    def belongs_to_backend(row: dict[str, Any]) -> bool:
        for evidence in row.get("evidence", []):
            evidence_path = (root / evidence).resolve()
            if any(
                directory == evidence_path.parent or directory in evidence_path.parents
                for directory in backend_directories
            ):
                return True
        return False

    backend_prefixes = tuple(
        f"{item.rstrip('/')}/" if item != "." else "" for item in detected_backend_roots
    )
    architecture = sorted(
        {
            relative(path, root)
            for path in directories
            if path.name.lower() in ARCHITECTURE_NAMES
            and (
                not backend_prefixes
                or any(relative(path, root).startswith(prefix) for prefix in backend_prefixes)
            )
        }
    )[:100]
    ci_files = sorted(
        relative(path, root)
        for path in files
        if ".github/workflows" in path.as_posix()
        or path.name in {"Jenkinsfile", ".gitlab-ci.yml", "azure-pipelines.yml", "buildkite.yml"}
    )

    integrations = detect_runtime_integrations(files, root)
    output = {
        "schema_version": 1,
        "root": root.as_posix(),
        "build_roots": build_roots(files, root),
        "backend_roots": detected_backend_roots,
        "languages": languages,
        "frameworks": frameworks,
        "backend_package_managers": [row for row in managers if belongs_to_backend(row)],
        "backend_analysis_tools": [row for row in tools if belongs_to_backend(row)],
        "repository_package_managers": managers,
        "repository_analysis_tools": tools,
        "backend_runtime_integrations": [
            row for row in integrations if belongs_to_backend(row)
        ],
        "repository_runtime_integrations": integrations,
        "architecture_signals": architecture,
        "ci_files": ci_files,
        "suggested_existing_commands": suggested_commands(
            files, root, managers, tools, detected_backend_roots
        ),
        "notes": [
            "Detection is heuristic; verify every result against manifests and source.",
            "Suggested commands are not executed and may require service-specific working directories or infrastructure.",
        ],
    }
    print(json.dumps(output, indent=2, sort_keys=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
