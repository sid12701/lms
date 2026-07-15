import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const frontendDir = path.dirname(scriptDir);
const openApiPath = path.resolve(frontendDir, "../openapi/openapi.json");
const outputPath = path.resolve(frontendDir, "src/lib/api/generated/loan-account-status.ts");

const contract = JSON.parse(fs.readFileSync(openApiPath, "utf8"));
const values = contract.components?.schemas?.LoanAccountStatus?.enum;
if (
  !Array.isArray(values) ||
  values.length === 0 ||
  values.some((value) => typeof value !== "string")
) {
  throw new Error("OpenAPI schema LoanAccountStatus must contain a non-empty string enum");
}

const source = `/** This file was auto-generated from openapi/openapi.json. Do not edit manually. */
import type { components } from "./schema";

export const LOAN_ACCOUNT_STATUSES = ${JSON.stringify(values, null, 2)} as const;
export type LoanAccountStatus = components["schemas"]["LoanAccountStatus"];
`;

fs.writeFileSync(outputPath, source, "utf8");
