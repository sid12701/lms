import { spawnSync } from "node:child_process";

const steps = ["typecheck", "lint", "format:check", "check:encoding", "test", "build"];

const failures = [];

for (const step of steps) {
  console.log(`\n▶ npm run ${step}`);
  const result = spawnSync("npm", ["run", step], {
    stdio: "inherit",
    shell: true,
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
