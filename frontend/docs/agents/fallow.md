# Fallow (frontend)

Static analysis for unused code, duplication, and complexity. Config: [`.fallowrc.json`](../../.fallowrc.json).

## Agent / CI rules

1. **Never run `fallow fix --yes` without a reviewed dry-run.** Bulk auto-fix can remove `Component` exports that [`lazyPage`](../../src/routes/lazy-page.ts) reads at runtime (`mod.Component ?? mod.default`), which breaks lazy-loaded routes.
2. Prefer **`fallow fix --dry-run --format json`** (or per-issue manual edits) after triage.
3. Use **`--format json --quiet`** for machine-readable output; exit code `1` means issues found, not tool failure.
4. **`axe-core`** is listed in `ignoreDependencies` because the app loads it via `@axe-core/react` in dev only ([`axe-runtime.tsx`](../../src/app/axe-runtime.tsx)).

## Common commands

```bash
cd frontend
npx fallow dead-code --format json
npx fallow dupes --format json
npx fallow health --format json
npx fallow fix --dry-run --format json
```

Reports from audits may be stored under `.fallow-reports/` (local only; not required for CI).
