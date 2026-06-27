#!/usr/bin/env node
/**
 * CI guard: fail if common UTF-8-as-Latin-1 mojibake sequences appear in UI source.
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const FRONTEND_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const SRC = join(FRONTEND_ROOT, "src");
const MOJIBAKE = /â€|â†|Â§/u;
const EXT = new Set([".ts", ".tsx", ".js", ".jsx", ".html", ".css"]);

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    const st = statSync(path);
    if (st.isDirectory()) {
      if (name === "node_modules" || name === "dist") continue;
      walk(path, out);
    } else if (EXT.has(name.slice(name.lastIndexOf(".")))) {
      out.push(path);
    }
  }
  return out;
}

const hits = [];
for (const file of walk(SRC)) {
  const text = readFileSync(file, "utf8");
  if (MOJIBAKE.test(text)) {
    hits.push(relative(FRONTEND_ROOT, file));
  }
}

if (hits.length > 0) {
  console.error("Mojibake detected in:\n" + hits.map((f) => `  - ${f}`).join("\n"));
  process.exit(1);
}

console.log("Encoding check passed.");
