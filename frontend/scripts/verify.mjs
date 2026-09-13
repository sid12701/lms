import { spawnSync } from "node:child_process";

const steps = [
  "typecheck",
  "lint",
  "format:check",
  "check:encoding",
  "test:cov",
  "build",
  "check:bundle",
];

const failures = [];

// Resolve the npm binary explicitly instead of going through a shell. `shell: true` would let
// step names reach a command interpreter, and dropping it outright breaks Windows, where the
// executable on PATH is npm.cmd and a shell-less spawn fails with ENOENT. This repo still ships
// local-start-backend.cmd and generate-reference.ps1, so Windows is a supported dev platform.
const NPM = process.platform === "win32" ? "npm.cmd" : "npm";

for (const step of steps) {
  console.log(`\n▶ npm run ${step}`);
  const result = spawnSync(NPM, ["run", step], {
    stdio: "inherit",
  });

  if (result.status !== 0) {
    failures.push(step);
  }
}

if (failures.length > 0) {
  console.error(`\n✖ verify failed: ${failures.join(", ")}`);
  process.exit(1);
}

console.log("\n✔ verify passed");
