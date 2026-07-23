#!/usr/bin/env python3

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


DETECTOR = Path(__file__).with_name("detect_backend_stack.py")


def write(root: Path, relative: str, content: str = "") -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def detect(root: Path) -> dict:
    result = subprocess.run(
        [sys.executable, str(DETECTOR), str(root)],
        check=True,
        capture_output=True,
        text=True,
    )
    return json.loads(result.stdout)


class DetectBackendStackTest(unittest.TestCase):
    def test_node_backend_uses_exact_tools_and_local_package_manager(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write(
                root,
                "package.json",
                json.dumps(
                    {
                        "dependencies": {"@nestjs/core": "1.0.0"},
                        "devDependencies": {
                            "vitest": "1.0.0",
                            "@testing-library/jest-dom": "1.0.0",
                        },
                        "scripts": {"test": "vitest run", "lint": "eslint ."},
                    }
                ),
            )
            write(root, "pnpm-lock.yaml", "lockfileVersion: 9")
            write(root, "src/server.ts", "export const server = true")

            report = detect(root)

            self.assertEqual(report["backend_roots"], ["."])
            self.assertIn("NestJS", {row["name"] for row in report["frameworks"]})
            self.assertIn("Vitest", {row["name"] for row in report["backend_analysis_tools"]})
            self.assertNotIn("Jest", {row["name"] for row in report["backend_analysis_tools"]})
            self.assertIn(
                "pnpm test", {row["command"] for row in report["suggested_existing_commands"]}
            )

    def test_maven_aggregator_is_not_an_executable_backend_root(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write(root, "pom.xml", "<project><packaging>pom</packaging></project>")
            write(
                root,
                "backend/pom.xml",
                "<project><dependency>spring-boot-starter-web</dependency></project>",
            )
            write(root, "backend/mvnw", "#!/bin/sh")
            write(root, "backend/src/main/java/App.java", "class App {}")

            report = detect(root)

            self.assertEqual(report["build_roots"], [".", "backend"])
            self.assertEqual(report["backend_roots"], ["backend"])
            verify = next(
                row
                for row in report["suggested_existing_commands"]
                if row["purpose"] == "full verification"
            )
            self.assertEqual(verify["cwd"], "backend")
            self.assertEqual(verify["command"], "./mvnw verify")

    def test_python_backend_detects_framework_and_declared_tools(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write(
                root,
                "pyproject.toml",
                """
[project]
dependencies = ["fastapi", "sqlalchemy"]
[dependency-groups]
dev = ["pytest", "ruff", "mypy"]
""",
            )
            write(root, "src/app.py", "from fastapi import FastAPI")

            report = detect(root)

            self.assertEqual(report["backend_roots"], ["."])
            self.assertIn("FastAPI", {row["name"] for row in report["frameworks"]})
            tools = {row["name"] for row in report["backend_analysis_tools"]}
            self.assertTrue({"Pytest", "Ruff", "mypy"}.issubset(tools))
            commands = {row["command"] for row in report["suggested_existing_commands"]}
            self.assertTrue({"python -m pytest", "python -m ruff check .", "python -m mypy ."}.issubset(commands))

    def test_go_commands_are_scoped_to_the_service_root(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write(root, "services/orders/go.mod", "module example.com/orders")
            write(root, "services/orders/main.go", "package main")

            report = detect(root)

            self.assertEqual(report["backend_roots"], ["services/orders"])
            go_commands = [
                row
                for row in report["suggested_existing_commands"]
                if row["command"].startswith("go ")
            ]
            self.assertEqual(len(go_commands), 3)
            self.assertTrue(all(row["cwd"] == "services/orders" for row in go_commands))

    def test_dotnet_project_without_solution_is_detected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write(
                root,
                "services/accounts/Accounts.csproj",
                '<Project Sdk="Microsoft.NET.Sdk.Web"></Project>',
            )
            write(root, "services/accounts/Program.cs", "var app = WebApplication.Create();")

            report = detect(root)

            self.assertEqual(report["backend_roots"], ["services/accounts"])
            self.assertIn(
                ".NET/NuGet",
                {row["name"] for row in report["backend_package_managers"]},
            )
            dotnet_commands = [
                row
                for row in report["suggested_existing_commands"]
                if row["command"].startswith("dotnet ")
            ]
            self.assertEqual(len(dotnet_commands), 2)
            self.assertTrue(all(row["cwd"] == "services/accounts" for row in dotnet_commands))


if __name__ == "__main__":
    unittest.main()
