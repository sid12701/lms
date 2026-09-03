// Generates the technical tracker MD + the gh issue-creation script from the
// decision artifact, so both stay in sync with the single source of truth.
import fs from "node:fs";
import vm from "node:vm";

const ART = "/Users/siddhant/Desktop/lms/.lavish/bhawana-audit-resolution.html";
const OUT_MD =
  "/Users/siddhant/Desktop/lms/outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md";
const OUT_SH = "/Users/siddhant/Desktop/lms/scripts/create-technical-issues.sh";

const html = fs.readFileSync(ART, "utf8");
const blocks = [...html.matchAll(/<script([^>]*)>([\s\S]*?)<\/script>/g)]
  .filter(([, a]) => !/type="module"/.test(a))
  .map((m) => m[2])
  .join("\n")
  .split("(function () {")[0];

const sandbox = {};
vm.createContext(sandbox);
vm.runInContext(blocks + "\n; globalThis.__F = FINDINGS;", sandbox);
const ALL = sandbox.__F;

const TECH = ALL.filter((f) => f.track === "Technical");
const BLOCKED = ALL.filter((f) => f.track === "Blocked");

// ---- de-HTML the copy so the markdown reads cleanly -------------------------
const clean = (s) =>
  String(s)
    .replace(/<code>(.*?)<\/code>/g, "`$1`")
    .replace(/<strong>(.*?)<\/strong>/g, "**$1**")
    .replace(/<em>(.*?)<\/em>/g, "_$1_")
    .replace(/<br\s*\/?>/g, "\n")
    .replace(/<[^>]+>/g, "")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&mdash;/g, "—")
    .replace(/&nbsp;/g, " ")
    .trim();

// Owner-approved items carry a recorded decision of Approved.
const approved = new Set(
  ALL.filter((f) => f.recorded && /^Approved/.test(f.recorded.d)).map((f) => f.id),
);

const deferred = new Set(
  ALL.filter((f) => f.recorded && /^Deferred/.test(f.recorded.d)).map((f) => f.id),
);

const statusOf = (f) =>
  deferred.has(f.id)
    ? "DEFERRED by the owner — do not start"
    : approved.has(f.id)
      ? "APPROVED — ready to build"
      : "PROPOSED — solution drafted, not yet approved";

const labelOf = (f) => (approved.has(f.id) && !deferred.has(f.id) ? "ready-for-agent" : "needs-triage");

// Pull the distinct source paths out of the evidence lines so an agent knows
// where to start reading.
function filesFor(f) {
  const paths = new Set();
  for (const e of f.ev) {
    const re =
      /((?:backend|frontend|infra|docs|scripts)\/[\w./@-]+|[\w/]+\/[\w]+\.(?:java|ts|tsx|sql|yml|yaml|xml)|V\d+__[\w]+\.sql|[\w]+\.(?:yml|xml))/g;
    let m;
    while ((m = re.exec(clean(e)))) paths.add(m[1]);
  }
  return [...paths];
}

function section(f, num) {
  const files = filesFor(f);
  return `
### ${f.id} — ${clean(f.title)}

| | |
|---|---|
| **Status** | ${statusOf(f)} |
| **Severity** | ${f.sev} |
| **Workstream** | ${f.ws} — ${f.area} |
| **Effort** | ${clean(f.effort)} |
| **Dependencies** | ${clean(f.deps)} |
| **GitHub issue** | ${num ? num : "_not created yet_"} |
| **Triage label** | \`${labelOf(f)}\` |

**Evidence — read these first**

${f.ev.map((e) => `- \`${clean(e).split(" — ")[0]}\`${clean(e).includes(" — ") ? " — " + clean(e).split(" — ").slice(1).join(" — ") : ""}`).join("\n")}

${files.length ? `**Files in scope:** ${files.map((p) => `\`${p}\``).join(", ")}\n` : ""}
**Root cause**

${clean(f.why)}

**Implementation spec**

${f.fix.map((s, i) => `${i + 1}. ${clean(s)}`).join("\n")}
${
  f.rej
    ? `
**Do NOT do this** — considered and rejected

> **${clean(f.rej.opt)}**
> ${clean(f.rej.why)}
`
    : ""
}${
    f.scale
      ? `
**Scale note**

${clean(f.scale)}
`
      : ""
  }${
    f.recorded
      ? `
**Owner decision (recorded)** — ${clean(f.recorded.d)}

${clean(f.recorded.n)}
`
      : ""
  }
**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- \`mvn -pl backend test\` passes; for frontend items \`npm run verify\` passes in \`frontend/\`.
- If the change alters an architectural decision, an ADR is added under \`docs/adr/\`.
- If the change adds a migration, it is numbered from \`V114\` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---
`;
}

// ---------------------------------------------------------------- tracker ---
const now = "2026-08-02";
const summaryStatus = (f) =>
  deferred.has(f.id) ? "DEFERRED" : approved.has(f.id) ? "APPROVED" : "Proposed";

let md = `# Bhawana LMS — Technical Tracker

**Source audit:** [\`CONSOLIDATED-AUDIT.md\`](./CONSOLIDATED-AUDIT.md)
**Audit baseline:** \`bfd571f\`
**Generated:** ${now}
**Decision surface:** \`.lavish/bhawana-audit-resolution.html\`

This tracker covers the **technical track only** — work that engineering can specify and
build without a business or pricing answer. Business decisions and the items blocked
behind them are listed in §4 for reference and are **out of scope** for this tracker.

---

## 1. Read this before implementing anything

### 1.1 Status is not uniform — check each item

| Status | Meaning | Count |
|---|---|---|
| **APPROVED — ready to build** | The owner has approved this solution. Implement as written. | ${TECH.filter((f) => approved.has(f.id) && !deferred.has(f.id)).length} |
| **DEFERRED — do not start** | The owner has parked it. The spec is kept current so work can start the day it is un-parked. | ${TECH.filter((f) => deferred.has(f.id)).length} |
| **PROPOSED — not yet approved** | Solution drafted from the code, not signed off. Confirm before starting. | ${TECH.filter((f) => !approved.has(f.id) && !deferred.has(f.id)).length} |

### 1.1.1 ICICI is out of scope

The ICICI relationship is **not finalised**. Do not build, change, or design against anything
that depends on it. Concretely:

- The disbursement bank adapter is **mocked** today (\`MockLoanDisbursementAdapter\`,
  \`MockIciciDisbursementScenario\`, \`LoanDisbursementMockProperties\`). Leave the real
  integration alone.
- **C2 Option B** needs a bank-account verification provider. That decision is deferred, so
  C2 is deferred with it.
- **Q4** (account mandate confirmation) and the \`CONTEXT.md\` bank-account glossary rewrite
  are deferred.
- **H7 and H8 are still in scope and safe.** They touch \`LoanDisbursementCommandService\`
  and the intent workflow, but neither changes the bank integration contract: H7 deletes a
  legacy code path, and H8 adds an operator resolution state. Both work against the mock and
  against any future provider.

### 1.1.2 Also deferred (2026-08-02 validation walk)

- **C7** — payment reversal / CLOSED reopen. Spec kept. When lifted, build **with H1**.
- **C12 + H44** — custom metrics export and OpsAlert delivery. Waiting on the
  observability stack choice (Prometheus vs alternatives).
- **H27** remains parked on the business track (maker-checker). Do not ship C7
  production reversals without it.

### 1.1.3 First-pass validation notes (2026-08-02)

Codebase validation of tracker items #252–#329 confirmed the defects are real.
Corrections to keep in mind when implementing (spec text may still carry the old wording):

- **H8** — \`DisbursementIntentWorkflowService\` **throws** \`DISBURSEMENT_ALREADY_REQUESTED\`;
  the worker catches \`RuntimeException\` at \`LoanDisbursementWorkerProcessor:145-150\`.
- **H10** — \`loan_product_version_id\` is already stored at intake; eligibility still reads
  the live product row.
- **H13** — no day-end/COB process (confirmed); the repo **does** have \`@Scheduled\` workers.
- **H31** — title says six tables; body/spec correctly cover **ten** without RLS.
- **H46** — \`processStatus\` lacks per-item try/catch; \`processPendingStatusChecks\` already has it.
- **M4** — \`CREATE INDEX\` count is **98**, not 103 (still zero \`CONCURRENTLY\`).
- **Q3** in §4.2 (OPS_USER cross-LSP create) is **not** Question 3 in
  \`BUSINESS-DECISIONS-NEEDED.md\` (early-closure interest).

### 1.2 Build order is load-bearing

1. **C13 first, always.** Until Testcontainers + Flyway is the test default, the test suite
   cannot distinguish fixed code from broken code: on H2 the tenant datasource silently
   becomes the admin datasource (\`TenantIsolationDataSourceConfig.java:34-37\`). A green
   build before C13 is not evidence.
2. **C1 + N2 together**, immediately after C13. Same class, same concern.
3. **H2 (precision) before H1 (allocator mapping)**, or the stored allocation portions are
   wrong from the first row.
4. **C7 (reversal) with H1, not after** — when C7 is un-parked. A replay can only correct a
   wrong split if it can reverse the original. Until then, H1 may still proceed alone for
   the dual-allocator fix; do not invent a partial reversal path.
5. **Schedule + DPD wave:** H12 → H11 → H13 → C6/H15 (approved together on 2026-08-02).

### 1.3 Environment facts an implementer needs

- Java 21, Maven (no wrapper — use \`mvn\`), single module \`backend\`.
- Frontend: React 19 + Vite + Tailwind v4 (CSS-first) + shadcn. Scripts: \`npm run verify\`,
  \`test\`, \`lint\`, \`typecheck\`, \`e2e\`.
- Database: PostgreSQL. **Supabase is a local development convenience only.** The real
  target for dev and production is **Azure Database for PostgreSQL Flexible Server**, which
  has *no superuser* (admin role is \`azure_pg_admin\`) and built-in PgBouncer in transaction
  mode. Do not write anything that requires superuser — see M2 and N2.
- Migrations: Flyway, currently at \`V113\`. New migrations start at \`V114\`.
- \`IntegrationTestDatabaseTargetGuard\` refuses remote database URLs in tests. Keep it.

### 1.4 Repo conventions to preserve

- Zero public setters on domain entities. Behaviour methods with intention-revealing names.
- No \`@Lazy\` injection — enforced by a test with an empty allowlist.
- Layering enforced by ArchUnit.
- Where this codebase already does something well, copy that pattern rather than inventing
  a second one. Named exemplars: \`LspAuditEventService\` and \`BorrowerBankDetailsService\`
  for audit writes; the webhook outbox claim query for lease/claim semantics;
  \`ApiClientLockoutService\` for synchronous lockout.

---

## 2. Summary — ${TECH.length} technical items

| ID | Title | Sev | WS | Status | Issue |
|---|---|---|---|---|---|
${TECH.map(
  (f) =>
    `| **${f.id}** | ${clean(f.title)} | ${f.sev} | ${f.ws} | ${summaryStatus(f)} | _pending_ |`,
).join("\n")}

---

## 3. Detailed specifications

${TECH.map((f) => section(f)).join("\n")}

---

## 4. Out of scope for this tracker

### 4.1 Blocked — technical work waiting on a business answer (${BLOCKED.length})

Do not start these. Each needs one specific business decision first.

| ID | Title | Waiting on |
|---|---|---|
${BLOCKED.map((f) => `| ${f.id} | ${clean(f.title)} | ${clean(f.trackNote)} |`).join("\n")}

### 4.2 Business track — parked by the owner (${ALL.filter((f) => f.track === "Business").length})

${ALL.filter((f) => f.track === "Business")
  .map((f) => `- **${f.id}** — ${clean(f.title)}`)
  .join("\n")}

---

## 5. Regenerating this file

This file is generated from the decision artifact so the two cannot drift. After the owner
records new decisions, re-run the generator rather than hand-editing:

\`\`\`
node scripts/gen-technical-tracker.mjs
\`\`\`
`;

fs.writeFileSync(OUT_MD, md);

// ------------------------------------------------------------- gh script ---
const bodyFor = (f) => section(f).replace(/^### /, "## ");

let sh = `#!/usr/bin/env bash
# Creates one GitHub issue per technical item in the Bhawana LMS audit tracker.
#
# WHY THIS IS A SCRIPT AND NOT SOMETHING CLAUDE RAN:
#   The active gh account on this machine is the WORK account
#   (siddhant-daryanani_mlt). Creating these issues as sid12701 needs
#   \`gh auth switch\`, which mutates global machine state and silently changes
#   the identity of every later gh command. That is yours to run, not mine.
#
# USAGE:
#   gh auth switch -u sid12701          # do this yourself first
#   bash scripts/create-technical-issues.sh
#   gh auth switch -u siddhant-daryanani_mlt   # switch back when done
#
# The script is idempotent by title: it skips any item whose issue already exists.
# It appends "ID<TAB>URL" to scripts/.technical-issue-map.tsv for the tracker backfill.

set -euo pipefail

REPO="sid12701/lms"
MAP="scripts/.technical-issue-map.tsv"

if [ "\$(gh api user --jq .login)" != "sid12701" ]; then
  echo "Active gh account is \$(gh api user --jq .login), not sid12701." >&2
  echo "Run: gh auth switch -u sid12701" >&2
  exit 1
fi

touch "\$MAP"

ensure_label() {
  gh label create "\$1" --repo "\$REPO" --color "\$2" --description "\$3" 2>/dev/null || true
}
ensure_label "ready-for-agent" "0e8a16" "Fully specified, ready for an AFK agent"
ensure_label "needs-triage"    "fbca04" "Maintainer needs to evaluate this issue"
ensure_label "audit-2026-07-31" "1d76db" "From the consolidated audit of 2026-07-31"

create_issue() {
  local id="\$1" title="\$2" label="\$3" body_file="\$4"
  if grep -q "^\$id\\t" "\$MAP" 2>/dev/null; then
    echo "skip \$id (already in \$MAP)"; return
  fi
  local existing
  existing=\$(gh issue list --repo "\$REPO" --state all --search "\\"\$title\\" in:title" \\
              --json number,title --jq ".[] | select(.title==\\"\$title\\") | .number" | head -1)
  if [ -n "\$existing" ]; then
    echo "skip \$id (issue #\$existing already exists)"
    printf '%s\\t%s\\n' "\$id" "https://github.com/\$REPO/issues/\$existing" >> "\$MAP"
    return
  fi
  local url
  url=\$(gh issue create --repo "\$REPO" --title "\$title" --label "\$label" \\
        --label "audit-2026-07-31" --body-file "\$body_file")
  echo "created \$id -> \$url"
  printf '%s\\t%s\\n' "\$id" "\$url" >> "\$MAP"
  sleep 1   # stay under the secondary rate limit
}

BODYDIR=\$(mktemp -d)
trap 'rm -rf "\$BODYDIR"' EXIT

`;

for (const f of TECH) {
  const title = `[${f.id}] ${clean(f.title)}`;
  const bodyPath = `$BODYDIR/${f.id}.md`;
  const body = `> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: \`outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md\`
> Source finding: \`${f.id}\` in \`CONSOLIDATED-AUDIT.md\` (baseline \`bfd571f\`)

${bodyFor(f)}`;
  sh += `cat > "${bodyPath}" <<'LMSEOF'
${body}
LMSEOF
create_issue "${f.id}" "${title.replace(/"/g, '\\"')}" "${labelOf(f)}" "${bodyPath}"

`;
}

sh += `echo
echo "Done. Issue map written to \$MAP"
echo "Now backfill the tracker with the issue links:"
echo "  node scripts/backfill-issue-links.mjs"
`;

fs.writeFileSync(OUT_SH, sh);
fs.chmodSync(OUT_SH, 0o755);

console.log("technical items:", TECH.length);
console.log("  approved:", TECH.filter((f) => approved.has(f.id) && !deferred.has(f.id)).length);
console.log("  deferred:", TECH.filter((f) => deferred.has(f.id)).length);
console.log(
  "  proposed:",
  TECH.filter((f) => !approved.has(f.id) && !deferred.has(f.id)).length,
);
console.log("blocked:", BLOCKED.length);
console.log("wrote:", OUT_MD);
console.log("wrote:", OUT_SH);
