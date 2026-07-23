import fs from "node:fs";
import path from "node:path";
import { gzipSync } from "node:zlib";

const DIST_DIR = path.resolve("dist");
const MANIFEST_PATH = path.join(DIST_DIR, ".vite", "manifest.json");
const HOME_MODULE = "src/features/home/page.tsx";
const CHART_MODULE = "src/features/home/components/LoansByDpdBucketCard.tsx";
const MAX_INITIAL_GZIP_BYTES = 220_000;
const MAX_HOME_SHELL_GZIP_BYTES = 250_000;

if (!fs.existsSync(MANIFEST_PATH)) {
  throw new Error("The Vite manifest is missing. Run the production build before check:bundle.");
}

const manifest = JSON.parse(fs.readFileSync(MANIFEST_PATH, "utf8"));
const entryKey = Object.keys(manifest).find((key) => manifest[key].isEntry);

if (!entryKey) {
  throw new Error("Could not identify the production entry in the Vite manifest.");
}
if (!manifest[HOME_MODULE]) {
  throw new Error(`The Vite manifest does not contain the Home route (${HOME_MODULE}).`);
}
if (!manifest[CHART_MODULE]) {
  throw new Error(`The Vite manifest does not contain the DPD chart (${CHART_MODULE}).`);
}

function staticClosure(rootKeys) {
  const visited = new Set();
  const pending = [...rootKeys];
  while (pending.length > 0) {
    const key = pending.pop();
    if (!key || visited.has(key) || !manifest[key]) continue;
    visited.add(key);
    pending.push(...(manifest[key].imports ?? []));
  }
  return visited;
}

function gzipBytes(moduleKeys) {
  const files = new Set([...moduleKeys].map((key) => manifest[key].file));
  return [...files].reduce((total, file) => {
    const source = fs.readFileSync(path.join(DIST_DIR, file));
    return total + gzipSync(source).length;
  }, 0);
}

const initialModules = staticClosure([entryKey]);
const homeShellModules = staticClosure([entryKey, HOME_MODULE]);
const homeDynamicImports = manifest[HOME_MODULE].dynamicImports ?? [];
const initialGzipBytes = gzipBytes(initialModules);
const homeShellGzipBytes = gzipBytes(homeShellModules);

if (initialModules.has(CHART_MODULE) || homeShellModules.has(CHART_MODULE)) {
  throw new Error("The DPD chart is statically reachable before the Home shell renders.");
}
if (!homeDynamicImports.includes(CHART_MODULE)) {
  throw new Error("The DPD chart is no longer a direct dynamic dependency of the Home route.");
}
if (initialGzipBytes > MAX_INITIAL_GZIP_BYTES) {
  throw new Error(
    `Initial JS is ${initialGzipBytes} gzip bytes; budget is ${MAX_INITIAL_GZIP_BYTES}.`,
  );
}
if (homeShellGzipBytes > MAX_HOME_SHELL_GZIP_BYTES) {
  throw new Error(
    `Home shell JS is ${homeShellGzipBytes} gzip bytes; budget is ${MAX_HOME_SHELL_GZIP_BYTES}.`,
  );
}

console.log(
  `Bundle boundaries passed: initial ${initialGzipBytes} gzip bytes, Home shell ${homeShellGzipBytes} gzip bytes; charts remain dynamically loaded.`,
);
