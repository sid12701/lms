// Backfills the GitHub issue links into the technical tracker after
// scripts/create-technical-issues.sh has run.
//
// Reads scripts/.technical-issue-map.tsv ("ID<TAB>URL" per line) and rewrites
// both the summary table and the per-item "GitHub issue" row in the tracker.
import fs from "node:fs";

const ROOT = new URL("..", import.meta.url).pathname;
const MAP = `${ROOT}scripts/.technical-issue-map.tsv`;
const TRACKER = `${ROOT}outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`;

if (!fs.existsSync(MAP)) {
  console.error(`No issue map at ${MAP}. Run scripts/create-technical-issues.sh first.`);
  process.exit(1);
}

const links = new Map();
for (const line of fs.readFileSync(MAP, "utf8").split("\n")) {
  const [id, url] = line.split("\t");
  if (id && url) links.set(id.trim(), url.trim());
}

let md = fs.readFileSync(TRACKER, "utf8");
let summaryPatched = 0;
let detailPatched = 0;

// Summary table rows: | **C1** | title | ... | _pending_ |
md = md.replace(/^\| \*\*([A-Z]+\d+)\*\* \|(.*)\| _pending_ \|$/gm, (whole, id, mid) => {
  const url = links.get(id);
  if (!url) return whole;
  summaryPatched++;
  const num = url.split("/").pop();
  return `| **${id}** |${mid}| [#${num}](${url}) |`;
});

// Per-item rows: | **GitHub issue** | _not created yet_ |
const sections = md.split(/^### /m);
md = sections
  .map((sec, i) => {
    if (i === 0) return sec;
    const id = sec.match(/^([A-Z]+\d+) —/)?.[1];
    const url = id && links.get(id);
    if (!url) return sec;
    const num = url.split("/").pop();
    const next = sec.replace(
      /\| \*\*GitHub issue\*\* \| _not created yet_ \|/,
      `| **GitHub issue** | [#${num}](${url}) |`,
    );
    if (next !== sec) detailPatched++;
    return next;
  })
  .join("### ");

fs.writeFileSync(TRACKER, md);
console.log(`Backfilled ${links.size} issue links (${summaryPatched} summary, ${detailPatched} detail).`);
